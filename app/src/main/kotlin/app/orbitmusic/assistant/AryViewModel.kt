package app.orbitmusic.assistant

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.orbitmusic.Container
import app.orbitmusic.cloud.ArySyncMessage
import app.orbitmusic.cloud.OperationType
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.cloud.handleFirestoreError
import app.orbitmusic.core.data.Settings
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.Track
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "AryViewModel"
private const val MODEL_NAME = "gemini-3.8-flash"
private const val GENERATION_TIMEOUT_MS = 60_000L

class AryViewModel(
    private val container: Container,
    initialPlaylistId: Long?,
) : ViewModel() {

    private val responseSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "reply" to Schema.string(description = "Warm, concise plain-text reply to the listener."),
                "operations" to Schema.array(
                    description = "Ordered playlist operations to perform on the listener's library.",
                    items = Schema.obj(
                        properties = mapOf(
                            "action" to Schema.enumeration(
                                values = listOf("create_playlist", "add_songs", "remove_songs"),
                                description = "The playlist operation to perform.",
                            ),
                            "playlistId" to Schema.integer(
                                description = "Numeric ID of an existing local playlist, or null when creating/matching by title.",
                                nullable = true,
                            ),
                            "playlistTitle" to Schema.string(
                                description = "Title of the playlist to create or match.",
                                nullable = true,
                            ),
                            "songQueries" to Schema.array(
                                items = Schema.string(),
                                description = "For add_songs: clean 'Title - Artist' queries to search and add.",
                                nullable = true,
                            ),
                            "removeEntryIds" to Schema.array(
                                items = Schema.integer(),
                                description = "For remove_songs: exact entryId numbers from the playlist context.",
                                nullable = true,
                            ),
                            "removeTrackTitles" to Schema.array(
                                items = Schema.string(),
                                description = "For remove_songs: song titles to match in the playlist.",
                                nullable = true,
                            ),
                        ),
                        optionalProperties = listOf(
                            "playlistId",
                            "playlistTitle",
                            "songQueries",
                            "removeEntryIds",
                            "removeTrackTitles",
                        ),
                    ),
                ),
            ),
        )
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@AryViewModel.responseSchema
            },
        )
    }

    private var nextMessageId = 1L
    private var maxSyncedSeq = 0
    private var hydratedCloudUid: String? = null
    private var activeSyncProfile: UserProfile? = null

    private val _focusedPlaylistId = MutableStateFlow(initialPlaylistId)
    val focusedPlaylistId: StateFlow<Long?> = _focusedPlaylistId.asStateFlow()

    private val _messages = MutableStateFlow<List<AryMessage>>(emptyList())
    val messages: StateFlow<List<AryMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<AryUiState>(AryUiState.Idle)
    val uiState: StateFlow<AryUiState> = _uiState.asStateFlow()

    private val _cloudSyncStatus = MutableStateFlow<AryCloudSyncStatus>(AryCloudSyncStatus.LocalOnly)
    val cloudSyncStatus: StateFlow<AryCloudSyncStatus> = _cloudSyncStatus.asStateFlow()

    private var lastSubmittedPrompt: String? = null

    init {
        observeCloudConversation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCloudConversation() {
        viewModelScope.launch {
            container.cloudAuth.authState
                .flatMapLatest { user ->
                    if (user == null) {
                        hydratedCloudUid = null
                        activeSyncProfile = null
                        _cloudSyncStatus.value = AryCloudSyncStatus.LocalOnly
                        flowOf<Pair<UserProfile?, List<ArySyncMessage>?>>(null to null)
                    } else {
                        container.cloud.observeUserProfile(user.uid)
                            .catch { e ->
                                if (e is CancellationException) throw e
                                if (e is FirebaseFirestoreException) {
                                    handleFirestoreError(e, OperationType.GET, "users/${user.uid}")
                                    emit(null)
                                } else {
                                    throw e
                                }
                            }
                            .flatMapLatest { profile ->
                                activeSyncProfile = profile
                                when {
                                    profile == null -> {
                                        _cloudSyncStatus.value = AryCloudSyncStatus.LocalOnly
                                        flowOf(null to null)
                                    }
                                    !profile.arySyncEnabled -> {
                                        _cloudSyncStatus.value = AryCloudSyncStatus.Paused(profile.handle)
                                        flowOf(profile to null)
                                    }
                                    else -> {
                                        _cloudSyncStatus.value = AryCloudSyncStatus.Synced(profile.handle)
                                        container.cloud.observeAryMessages(profile.uid)
                                            .map<List<ArySyncMessage>, Pair<UserProfile?, List<ArySyncMessage>?>> { msgs ->
                                                profile to msgs
                                            }
                                            .catch { e ->
                                                if (e is CancellationException) throw e
                                                if (e is FirebaseFirestoreException) {
                                                    handleFirestoreError(e, OperationType.LIST, "users/${profile.uid}/aryMessages")
                                                    emit(profile to null)
                                                } else {
                                                    throw e
                                                }
                                            }
                                    }
                                }
                            }
                    }
                }
                .collect { (profile, remote) ->
                    if (profile == null || remote == null) return@collect
                    maxSyncedSeq = maxOf(maxSyncedSeq, remote.maxOfOrNull { it.seq } ?: _messages.value.size)
                    val shouldHydrate = hydratedCloudUid != profile.uid ||
                        (remote.size > _messages.value.size && _uiState.value !is AryUiState.Loading)
                    if (shouldHydrate) {
                        hydratedCloudUid = profile.uid
                        if (remote.isNotEmpty()) {
                            val existing = _messages.value
                            val merged = remote.mapIndexed { idx, msg ->
                                val kept = existing.getOrNull(idx)
                                when (msg.role) {
                                    "user" -> {
                                        if (kept is AryMessage.User && kept.text == msg.text) kept
                                        else AryMessage.User(nextMessageId++, msg.text)
                                    }
                                    else -> {
                                        if (kept is AryMessage.Assistant && kept.reply == msg.text) kept
                                        else AryMessage.Assistant(
                                            id = nextMessageId++,
                                            reply = msg.text,
                                            changes = msg.changeSummaries.map { summary ->
                                                AryChange(
                                                    kind = if (summary.startsWith("Removed", ignoreCase = true)) {
                                                        AryChangeKind.REMOVED
                                                    } else if (summary.startsWith("Created", ignoreCase = true)) {
                                                        AryChangeKind.CREATED_PLAYLIST
                                                    } else if (summary.startsWith("Couldn", ignoreCase = true)) {
                                                        AryChangeKind.NOT_FOUND
                                                    } else {
                                                        AryChangeKind.ADDED
                                                    },
                                                    playlistId = null,
                                                    playlistTitle = msg.focusedPlaylistTitle ?: "Playlist",
                                                    summary = summary,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            _messages.value = merged
                        }
                    }
                }
        }
    }

    fun selectPlaylist(id: Long?) {
        _focusedPlaylistId.value = id
    }

    fun clearConversation() {
        _messages.value = emptyList()
        _uiState.value = AryUiState.Idle
        lastSubmittedPrompt = null
        maxSyncedSeq = 0
        val syncProfile = activeSyncProfile
        if (syncProfile != null && syncProfile.arySyncEnabled) {
            viewModelScope.launch {
                container.cloud.clearAryMessages(syncProfile.uid)
            }
        }
    }

    fun dismissError() {
        if (_uiState.value is AryUiState.Error) {
            _uiState.value = AryUiState.Idle
        }
    }

    fun retryLast() {
        val last = lastSubmittedPrompt ?: return
        runPrompt(last, appendUserBubble = false)
    }

    fun sendMessage(rawPrompt: String) {
        val trimmed = rawPrompt.trim()
        if (trimmed.isEmpty()) return
        if (_uiState.value is AryUiState.Loading) return
        lastSubmittedPrompt = trimmed
        runPrompt(trimmed, appendUserBubble = true)
    }

    private fun runPrompt(prompt: String, appendUserBubble: Boolean) {
        val assistantName = container.settings.assistantName.value
            ?.takeIf { it.isNotBlank() }
            ?: Settings.DEFAULT_ASSISTANT_NAME
        if (appendUserBubble) {
            _messages.value = _messages.value + AryMessage.User(nextMessageId++, prompt)
            val syncProfile = activeSyncProfile
            if (syncProfile != null && syncProfile.arySyncEnabled) {
                val userSeq = ++maxSyncedSeq
                viewModelScope.launch {
                    val focusedTitle = _focusedPlaylistId.value?.let { fid ->
                        runCatching { container.library.playlists.first().firstOrNull { it.id == fid }?.title }.getOrNull()
                    }
                    container.cloud.saveAryMessage(
                        uid = syncProfile.uid,
                        role = "user",
                        text = prompt,
                        changeSummaries = emptyList(),
                        focusedPlaylistTitle = focusedTitle,
                        seq = userSeq,
                    )
                }
            }
        }

        viewModelScope.launch {
            _uiState.value = AryUiState.Loading("$assistantName is thinking…")
            try {
                val playlists = loadPlaylistContexts()
                val currentTrack = container.playback.state.value.current
                val fullPrompt = buildAryPrompt(
                    assistantName = assistantName,
                    userName = container.settings.profileName.value,
                    focusedPlaylistId = _focusedPlaylistId.value,
                    playlists = playlists,
                    currentTrack = currentTrack,
                    history = _messages.value.dropLast(1),
                    userPrompt = prompt,
                )

                val response = withTimeout(GENERATION_TIMEOUT_MS) {
                    model.generateContent(fullPrompt)
                }

                val jsonText = response.text
                if (jsonText.isNullOrBlank()) {
                    _uiState.value = AryUiState.Error("$assistantName returned an empty response.")
                    return@launch
                }

                val plan = AryJson.decodePlan(jsonText)
                val appliedChanges = if (plan.operations.isNotEmpty()) {
                    _uiState.value = AryUiState.Loading("Updating playlists…")
                    executeOperations(plan.operations, playlists, currentTrack)
                } else {
                    emptyList()
                }

                val replyText = plan.reply.trim().ifEmpty { "Done." }
                _messages.value = _messages.value + AryMessage.Assistant(
                    id = nextMessageId++,
                    reply = replyText,
                    changes = appliedChanges,
                )
                _uiState.value = AryUiState.Idle

                val syncProfile = activeSyncProfile
                if (syncProfile != null && syncProfile.arySyncEnabled) {
                    val replySeq = ++maxSyncedSeq
                    val focusedTitle = appliedChanges.lastOrNull()?.playlistTitle
                        ?: playlists.firstOrNull { it.playlist.id == _focusedPlaylistId.value }?.playlist?.title
                    container.cloud.saveAryMessage(
                        uid = syncProfile.uid,
                        role = "assistant",
                        text = replyText,
                        changeSummaries = appliedChanges.map { it.summary },
                        focusedPlaylistTitle = focusedTitle,
                        seq = replySeq,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Firebase AI generation timed out", e)
                _uiState.value = AryUiState.Error("Request timed out after 60 seconds. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI generation failed", e)
                _uiState.value = AryUiState.Error(e.localizedMessage ?: "Unable to reach $assistantName right now.")
            }
        }
    }

    private suspend fun loadPlaylistContexts(): List<AryPlaylistContext> {
        val all = container.library.playlists.first()
        return all.map { playlist ->
            val entries = container.library.entries(playlist.id).first()
            AryPlaylistContext(playlist = playlist, entries = entries)
        }
    }

    private suspend fun executeOperations(
        operations: List<AryOperation>,
        initialContexts: List<AryPlaylistContext>,
        currentTrack: Track?,
    ): List<AryChange> {
        val changes = mutableListOf<AryChange>()
        val createdByTitle = LinkedHashMap<String, Pair<Long, String>>()
        val knownById = initialContexts.associate { it.playlist.id to it.playlist.title }.toMutableMap()

        suspend fun resolveOrCreatePlaylist(
            opPlaylistId: Long?,
            opTitle: String?,
            createIfMissing: Boolean,
        ): Pair<Long, String>? {
            if (opPlaylistId != null && opPlaylistId > 0L) {
                knownById[opPlaylistId]?.let { title -> return opPlaylistId to title }
            }
            val cleanTitle = opTitle?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanTitle != null) {
                createdByTitle[cleanTitle.lowercase()]?.let { return it }
                val existing = knownById.entries.firstOrNull { it.value.equals(cleanTitle, ignoreCase = true) }
                if (existing != null) return existing.key to existing.value
                if (createIfMissing) {
                    val createdId = container.library.createPlaylist(cleanTitle)
                    createdByTitle[cleanTitle.lowercase()] = createdId to cleanTitle
                    knownById[createdId] = cleanTitle
                    _focusedPlaylistId.value = createdId
                    changes += AryChange(
                        kind = AryChangeKind.CREATED_PLAYLIST,
                        playlistId = createdId,
                        playlistTitle = cleanTitle,
                        summary = "Created playlist \u201c$cleanTitle\u201d",
                    )
                    return createdId to cleanTitle
                }
            }
            val focused = _focusedPlaylistId.value
            if (focused != null) {
                knownById[focused]?.let { title -> return focused to title }
            }
            return null
        }

        for (op in operations) {
            when (op.action) {
                AryAction.create_playlist -> {
                    val title = op.playlistTitle?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val existing = knownById.entries.firstOrNull { it.value.equals(title, ignoreCase = true) }
                        ?: createdByTitle[title.lowercase()]?.let { java.util.AbstractMap.SimpleEntry(it.first, it.second) }
                    if (existing == null) {
                        val createdId = container.library.createPlaylist(title)
                        createdByTitle[title.lowercase()] = createdId to title
                        knownById[createdId] = title
                        _focusedPlaylistId.value = createdId
                        changes += AryChange(
                            kind = AryChangeKind.CREATED_PLAYLIST,
                            playlistId = createdId,
                            playlistTitle = title,
                            summary = "Created playlist \u201c$title\u201d",
                        )
                    }
                }

                AryAction.add_songs -> {
                    val target = resolveOrCreatePlaylist(
                        opPlaylistId = op.playlistId,
                        opTitle = op.playlistTitle,
                        createIfMissing = true,
                    ) ?: continue
                    val (playlistId, playlistTitle) = target
                    val tracksToAdd = mutableListOf<Track>()

                    for (query in op.songQueries) {
                        val cleanQuery = query.trim()
                        if (cleanQuery.isEmpty()) continue
                        val matched = resolveTrack(cleanQuery, currentTrack)
                        if (matched != null) {
                            tracksToAdd += matched
                            changes += AryChange(
                                kind = AryChangeKind.ADDED,
                                playlistId = playlistId,
                                playlistTitle = playlistTitle,
                                summary = "Added \u201c${matched.title}\u201d \u00b7 ${matched.artistLine}",
                                track = matched,
                            )
                        } else {
                            changes += AryChange(
                                kind = AryChangeKind.NOT_FOUND,
                                playlistId = playlistId,
                                playlistTitle = playlistTitle,
                                summary = "Couldn\u2019t find \u201c$cleanQuery\u201d",
                            )
                        }
                    }

                    if (tracksToAdd.isNotEmpty()) {
                        container.library.add(playlistId, tracksToAdd)
                    }
                }

                AryAction.remove_songs -> {
                    val target = resolveOrCreatePlaylist(
                        opPlaylistId = op.playlistId,
                        opTitle = op.playlistTitle,
                        createIfMissing = false,
                    ) ?: continue
                    val (playlistId, playlistTitle) = target
                    val currentEntries = container.library.entries(playlistId).first()
                    val toRemove = findMatchingEntries(
                        entries = currentEntries,
                        entryIds = op.removeEntryIds,
                        titles = op.removeTrackTitles,
                    )
                    if (toRemove.isEmpty()) {
                        val missed = op.removeTrackTitles.joinToString(", ").ifBlank { "requested song" }
                        changes += AryChange(
                            kind = AryChangeKind.NOT_FOUND,
                            playlistId = playlistId,
                            playlistTitle = playlistTitle,
                            summary = "Couldn\u2019t match \u201c$missed\u201d in $playlistTitle",
                        )
                    } else {
                        for (entry in toRemove) {
                            container.library.remove(playlistId, entry.entryId)
                            changes += AryChange(
                                kind = AryChangeKind.REMOVED,
                                playlistId = playlistId,
                                playlistTitle = playlistTitle,
                                summary = "Removed \u201c${entry.track.title}\u201d \u00b7 ${entry.track.artistLine}",
                                track = entry.track,
                            )
                        }
                    }
                }
            }
        }
        return changes
    }

    private suspend fun resolveTrack(query: String, currentTrack: Track?): Track? {
        if (currentTrack != null) {
            val normalizedQuery = normalizeText(query)
            val currentTitle = normalizeText(currentTrack.title)
            if (currentTitle.isNotEmpty() && currentTitle in normalizedQuery) {
                return currentTrack
            }
        }

        val primaryResults = (container.sources.primary.search(query) as? Outcome.Ok)
            ?.value?.items?.filterIsInstance<Track>().orEmpty()
        findBestTrackMatch(query, primaryResults)?.let { return it }

        val localResults = (container.local.search(query) as? Outcome.Ok)
            ?.value?.items?.filterIsInstance<Track>().orEmpty()
        return findBestTrackMatch(query, localResults)
    }

    class Factory(
        private val container: Container,
        private val initialPlaylistId: Long?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AryViewModel(container, initialPlaylistId) as T
    }
}
