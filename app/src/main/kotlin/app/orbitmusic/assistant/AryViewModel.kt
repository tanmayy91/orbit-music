package app.orbitmusic.assistant

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.orbitmusic.BuildConfig
import app.orbitmusic.Container
import app.orbitmusic.await
import app.orbitmusic.core.data.Settings
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AryViewModel"
private const val MODEL_NAME = "gemini-2.5-flash"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val GENERATION_TIMEOUT_MS = 60_000L
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class AryViewModel(
    private val container: Container,
    initialPlaylistId: Long?,
) : ViewModel() {

    private val responseSchemaJson by lazy {
        JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "reply",
                        JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Warm, concise plain-text reply to the listener.")
                        },
                    )
                    put(
                        "operations",
                        JSONObject().apply {
                            put("type", "ARRAY")
                            put("description", "Ordered playlist operations to perform on the listener's library.")
                            put(
                                "items",
                                JSONObject().apply {
                                    put("type", "OBJECT")
                                    put(
                                        "properties",
                                        JSONObject().apply {
                                            put(
                                                "action",
                                                JSONObject().apply {
                                                    put("type", "STRING")
                                                    put("enum", JSONArray(listOf("create_playlist", "add_songs", "remove_songs")))
                                                    put("description", "The playlist operation to perform.")
                                                },
                                            )
                                            put(
                                                "playlistId",
                                                JSONObject().apply {
                                                    put("type", "INTEGER")
                                                    put("description", "Numeric ID of an existing local playlist, or null when creating/matching by title.")
                                                    put("nullable", true)
                                                },
                                            )
                                            put(
                                                "playlistTitle",
                                                JSONObject().apply {
                                                    put("type", "STRING")
                                                    put("description", "Title of the playlist to create or match.")
                                                    put("nullable", true)
                                                },
                                            )
                                            put(
                                                "songQueries",
                                                JSONObject().apply {
                                                    put("type", "ARRAY")
                                                    put("items", JSONObject().put("type", "STRING"))
                                                    put("description", "For add_songs: clean 'Title - Artist' queries to search and add.")
                                                    put("nullable", true)
                                                },
                                            )
                                            put(
                                                "removeEntryIds",
                                                JSONObject().apply {
                                                    put("type", "ARRAY")
                                                    put("items", JSONObject().put("type", "INTEGER"))
                                                    put("description", "For remove_songs: exact entryId numbers from the playlist context.")
                                                    put("nullable", true)
                                                },
                                            )
                                            put(
                                                "removeTrackTitles",
                                                JSONObject().apply {
                                                    put("type", "ARRAY")
                                                    put("items", JSONObject().put("type", "STRING"))
                                                    put("description", "For remove_songs: song titles to match in the playlist.")
                                                    put("nullable", true)
                                                },
                                            )
                                        },
                                    )
                                    put("required", JSONArray(listOf("action")))
                                },
                            )
                        },
                    )
                },
            )
            put("required", JSONArray(listOf("reply", "operations")))
        }
    }

    private var nextMessageId = 1L

    private val _focusedPlaylistId = MutableStateFlow(initialPlaylistId)
    val focusedPlaylistId: StateFlow<Long?> = _focusedPlaylistId.asStateFlow()

    private val _messages = MutableStateFlow<List<AryMessage>>(emptyList())
    val messages: StateFlow<List<AryMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<AryUiState>(AryUiState.Idle)
    val uiState: StateFlow<AryUiState> = _uiState.asStateFlow()

    private var lastSubmittedPrompt: String? = null

    fun selectPlaylist(id: Long?) {
        _focusedPlaylistId.value = id
    }

    fun clearConversation() {
        _messages.value = emptyList()
        _uiState.value = AryUiState.Idle
        lastSubmittedPrompt = null
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

                val jsonText = withTimeout(GENERATION_TIMEOUT_MS) {
                    generateAryJson(fullPrompt)
                }

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
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini AI generation timed out", e)
                _uiState.value = AryUiState.Error("Request timed out after 60 seconds. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini AI generation failed", e)
                _uiState.value = AryUiState.Error(e.localizedMessage ?: "Unable to reach $assistantName right now.")
            }
        }
    }

    private suspend fun generateAryJson(fullPrompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == "your_gemini_api_key_here") {
            throw IllegalStateException("Gemini API key is not configured. Add GEMINI_API_KEY in AI Studio Secrets.")
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", fullPrompt)))
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", responseSchemaJson)
                },
            )
        }

        val request = Request.Builder()
            .url("$GEMINI_API_BASE/$MODEL_NAME:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        container.http.newCall(request).await().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMessage = runCatching {
                    JSONObject(rawBody).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(apiMessage ?: "Gemini request failed (HTTP ${response.code}).")
            }
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates") ?: return@use null
            val first = candidates.optJSONObject(0) ?: return@use null
            val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return@use null
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.takeIf { it.isNotBlank() }
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
