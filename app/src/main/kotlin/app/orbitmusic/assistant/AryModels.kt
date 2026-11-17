package app.orbitmusic.assistant

import app.orbitmusic.core.data.LocalPlaylist
import app.orbitmusic.core.data.PlaylistEntry
import app.orbitmusic.core.model.MediaKind
import app.orbitmusic.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
enum class AryAction {
    create_playlist,
    add_songs,
    remove_songs,
}

@Serializable
data class AryOperation(
    val action: AryAction,
    val playlistId: Long? = null,
    val playlistTitle: String? = null,
    val songQueries: List<String> = emptyList(),
    val removeEntryIds: List<Long> = emptyList(),
    val removeTrackTitles: List<String> = emptyList(),
)

@Serializable
data class AryPlan(
    val reply: String,
    val operations: List<AryOperation> = emptyList(),
)

sealed interface AryUiState {
    data object Idle : AryUiState
    data class Loading(val status: String) : AryUiState
    data class Error(val message: String) : AryUiState
}

sealed interface AryCloudSyncStatus {
    data object LocalOnly : AryCloudSyncStatus
    data class Synced(val handle: String) : AryCloudSyncStatus
    data class Paused(val handle: String) : AryCloudSyncStatus
}

enum class AryChangeKind {
    CREATED_PLAYLIST,
    ADDED,
    REMOVED,
    NOT_FOUND,
}

data class AryChange(
    val kind: AryChangeKind,
    val playlistId: Long?,
    val playlistTitle: String,
    val summary: String,
    val track: Track? = null,
)

sealed interface AryMessage {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
    ) : AryMessage

    data class Assistant(
        override val id: Long,
        val reply: String,
        val changes: List<AryChange> = emptyList(),
    ) : AryMessage
}

data class AryPlaylistContext(
    val playlist: LocalPlaylist,
    val entries: List<PlaylistEntry>,
)

internal object AryJson {
    val codec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun decodePlan(raw: String): AryPlan = codec.decodeFromString(AryPlan.serializer(), raw)
}

internal fun normalizeText(input: String): String =
    input.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Picks the closest [Track] from [candidates] for a natural-language [query] like "Blinding Lights - The Weeknd".
 */
internal fun findBestTrackMatch(query: String, candidates: List<Track>): Track? {
    if (candidates.isEmpty()) return null
    val queryTokens = normalizeText(query).split(' ').filter { it.length >= 2 }.toSet()
    if (queryTokens.isEmpty()) return candidates.firstOrNull { it.kind == MediaKind.SONG } ?: candidates.first()

    return candidates.maxByOrNull { track ->
        val titleText = normalizeText(track.title)
        val artistText = normalizeText(track.artistLine)
        val combined = "$titleText $artistText"
        val titleHits = queryTokens.count { it in titleText }
        val artistHits = queryTokens.count { it in artistText }
        val combinedHits = queryTokens.count { it in combined }
        val kindBonus = when (track.kind) {
            MediaKind.SONG -> 4
            MediaKind.MUSIC_VIDEO, MediaKind.VIDEO -> 1
            MediaKind.EPISODE -> 0
        }
        val exactBonus = if (titleText.isNotEmpty() && titleText in normalizeText(query)) 6 else 0
        combinedHits * 3 + titleHits * 2 + artistHits * 2 + kindBonus + exactBonus
    }
}

/**
 * Resolves playlist entries to remove: first by explicit [entryIds], then by normalized [titles]
 * matched against existing [entries]. Preserves order and avoids duplicates.
 */
internal fun findMatchingEntries(
    entries: List<PlaylistEntry>,
    entryIds: List<Long>,
    titles: List<String>,
): List<PlaylistEntry> {
    if (entries.isEmpty()) return emptyList()
    val matched = LinkedHashMap<Long, PlaylistEntry>()
    val byId = entries.associateBy { it.entryId }

    for (id in entryIds) {
        byId[id]?.let { matched[it.entryId] = it }
    }

    for (rawTitle in titles) {
        val target = normalizeText(rawTitle)
        if (target.isEmpty()) continue
        val targetTokens = target.split(' ').filter { it.length >= 2 }

        val candidate = entries.firstOrNull { entry ->
            entry.entryId !in matched && normalizeText(entry.track.title) == target
        } ?: entries.firstOrNull { entry ->
            if (entry.entryId in matched) return@firstOrNull false
            val hay = normalizeText("${entry.track.title} ${entry.track.artistLine}")
            target in hay || normalizeText(entry.track.title) in target
        } ?: if (targetTokens.isNotEmpty()) {
            entries
                .filter { it.entryId !in matched }
                .map { entry ->
                    val hay = normalizeText("${entry.track.title} ${entry.track.artistLine}")
                    entry to targetTokens.count { it in hay }
                }
                .filter { (_, hits) -> hits >= (targetTokens.size + 1) / 2 }
                .maxByOrNull { (_, hits) -> hits }
                ?.first
        } else {
            null
        }

        if (candidate != null) matched[candidate.entryId] = candidate
    }

    return matched.values.toList()
}

internal fun buildAryPrompt(
    assistantName: String,
    userName: String?,
    focusedPlaylistId: Long?,
    playlists: List<AryPlaylistContext>,
    currentTrack: Track?,
    history: List<AryMessage>,
    userPrompt: String,
): String = buildString {
    appendLine("You are $assistantName, the built-in music assistant inside Orbit Music.")
    if (!userName.isNullOrBlank()) appendLine("The listener's name is $userName.")
    appendLine("Your primary role is to help the listener manage their local playlists: creating playlists, adding songs, and removing songs, plus answering questions about their playlists.")
    appendLine()
    appendLine("Rules:")
    appendLine("1. Always respond concisely, warmly, and clearly in `reply`.")
    appendLine("2. Only emit operations in `operations` when the listener asks or clearly confirms adding, removing, or creating playlists.")
    appendLine("3. For `add_songs`: put clean search queries in `songQueries` as \"Title - Artist\" (for example, \"Blinding Lights - The Weeknd\"). If the listener refers to \"this song\" or \"what's playing\" and a currently playing track is shown below, use its exact title and artist.")
    appendLine("4. If adding songs to a playlist that already exists in the context, set `playlistId` to its exact numeric id and `playlistTitle` to its title.")
    appendLine("5. If the listener asks to add songs to a brand-new playlist name that does not exist yet, either emit a `create_playlist` operation followed by `add_songs` with that same `playlistTitle`, or set `playlistTitle` on `add_songs`.")
    appendLine("6. For `remove_songs`: match songs present in the playlist context below. Populate `removeEntryIds` with the exact numeric `entryId` values and also include `removeTrackTitles` as fallback.")
    appendLine("7. Never fabricate that an operation succeeded if the playlist or song is not specified and there is no focused playlist—instead ask which playlist they'd like to update.")
    appendLine()
    if (currentTrack != null) {
        appendLine("Currently playing track:")
        appendLine("- Title: \"${currentTrack.title}\" | Artist: \"${currentTrack.artistLine}\"")
        appendLine()
    }
    if (focusedPlaylistId != null) {
        val focused = playlists.firstOrNull { it.playlist.id == focusedPlaylistId }
        if (focused != null) {
            appendLine("Focused playlist right now: id=${focused.playlist.id}, title=\"${focused.playlist.title}\" (${focused.entries.size} songs)")
            appendLine()
        }
    }
    appendLine("Listener's local playlists:")
    if (playlists.isEmpty()) {
        appendLine("- (No local playlists created yet.)")
    } else {
        for (ctx in playlists) {
            val isFocused = ctx.playlist.id == focusedPlaylistId
            val maxEntries = if (isFocused) 120 else 45
            appendLine("- Playlist id=${ctx.playlist.id} title=\"${ctx.playlist.title}\" (${ctx.entries.size} songs):")
            if (ctx.entries.isEmpty()) {
                appendLine("  (empty)")
            } else {
                ctx.entries.take(maxEntries).forEach { e ->
                    appendLine("  * entryId=${e.entryId}: \"${e.track.title}\" by ${e.track.artistLine}")
                }
                if (ctx.entries.size > maxEntries) {
                    appendLine("  * ... (${ctx.entries.size - maxEntries} more songs)")
                }
            }
        }
    }
    if (history.isNotEmpty()) {
        appendLine()
        appendLine("Recent conversation:")
        history.takeLast(8).forEach { msg ->
            when (msg) {
                is AryMessage.User -> appendLine("User: ${msg.text}")
                is AryMessage.Assistant -> {
                    appendLine("$assistantName: ${msg.reply}")
                    if (msg.changes.isNotEmpty()) {
                        appendLine("  Applied changes: ${msg.changes.joinToString("; ") { "${it.playlistTitle}: ${it.summary}" }}")
                    }
                }
            }
        }
    }
    appendLine()
    appendLine("Latest user request:")
    appendLine(userPrompt)
}
