package app.orbitmusic.cloud

import app.orbitmusic.core.model.AlbumRef
import app.orbitmusic.core.model.ArtistRef
import app.orbitmusic.core.model.Artwork
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Locale

@Serializable
data class UserProfile(
    val uid: String = "",
    @SerialName("display_name") val displayName: String = "",
    val handle: String = "",
    @SerialName("discord_username") val discordUsername: String? = null,
    @SerialName("discord_avatar_url") val discordAvatarUrl: String? = null,
    @SerialName("card_shared") val cardShared: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val id: String get() = uid
    val discordId: String? get() = uid.takeIf { it.isNotBlank() }
}

@Serializable
data class DirectoryEntry(
    val uid: String = "",
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("discord_username") val discordUsername: String? = null,
) {
    val id: String get() = handle
}

@Serializable
data class Friendship(
    val id: String = "",
    @SerialName("member_uids") val memberUids: List<String> = emptyList(),
    @SerialName("requester_uid") val requesterUid: String = "",
    @SerialName("recipient_uid") val recipientUid: String = "",
    @SerialName("requester_name") val requesterName: String = "",
    @SerialName("requester_handle") val requesterHandle: String = "",
    @SerialName("recipient_name") val recipientName: String = "",
    @SerialName("recipient_handle") val recipientHandle: String = "",
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val accepted: Boolean get() = status == "accepted"
    fun otherUid(myUid: String): String = if (requesterUid == myUid) recipientUid else requesterUid
    fun otherName(myUid: String): String = if (requesterUid == myUid) recipientName else requesterName
    fun otherHandle(myUid: String): String = if (requesterUid == myUid) recipientHandle else requesterHandle
}

@Serializable
data class ListeningCardSnapshot(
    val uid: String = "",
    @SerialName("owner_name") val ownerName: String = "",
    val handle: String = "",
    @SerialName("discord_username") val discordUsername: String? = null,
    val persona: String = "The listener",
    @SerialName("since_label") val sinceLabel: String? = null,
    @SerialName("hours_label") val hoursLabel: String = "0 min",
    val plays: Int = 0,
    @SerialName("artist_count") val artistCount: Int = 0,
    @SerialName("best_streak") val bestStreak: Int = 0,
    @SerialName("top_song_title") val topSongTitle: String? = null,
    @SerialName("top_song_artist") val topSongArtist: String? = null,
    @SerialName("top_genre") val topGenre: String? = null,
    @SerialName("top_artists") val topArtists: List<String> = emptyList(),
    @SerialName("viewer_uids") val viewerUids: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val id: String get() = uid
}

@Serializable
data class CollabPlaylist(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("owner_name") val ownerName: String = "",
    @SerialName("invite_code") val inviteCode: String = "",
    @SerialName("member_uids") val memberUids: List<String> = emptyList(),
    @SerialName("member_names") val memberNames: List<String> = emptyList(),
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("track_doc_ids") val trackDocIds: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CollabTrack(
    val id: String = "",
    @SerialName("playlist_id") val playlistId: String = "",
    @SerialName("member_uids") val memberUids: List<String> = emptyList(),
    @SerialName("media_id") val mediaId: String = "",
    val title: String = "",
    @SerialName("artist_line") val artistLine: String = "",
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("added_by_uid") val addedByUid: String = "",
    @SerialName("added_by_name") val addedByName: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toTrack(): Track {
        val artists = artistLine
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ArtistRef(it) }
        return Track(
            id = MediaId(mediaId),
            title = title,
            artists = artists,
            album = albumTitle?.takeIf { it.isNotBlank() }?.let { AlbumRef(it) },
            durationMs = durationMs?.takeIf { it > 0L },
            artwork = artworkUrl?.takeIf { it.isNotBlank() }?.let { Artwork.single(it, 600, 600) } ?: Artwork.None,
        )
    }
}

object CloudValidation {
    private val HandleRegex = Regex("^[a-z0-9_]{3,30}$")
    private val DiscordKeyRegex = Regex("^[a-z0-9_.]{2,32}$")
    private val InviteRegex = Regex("^[A-Z0-9]{6,12}$")
    private val InviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val Random = SecureRandom()

    fun normalizeHandle(raw: String): String =
        raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun isValidHandle(handle: String): Boolean = HandleRegex.matches(handle)

    fun normalizeDiscordKey(raw: String): String =
        raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun isValidDiscordKey(key: String): Boolean = DiscordKeyRegex.matches(key)

    fun normalizeInviteCode(raw: String): String =
        raw.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

    fun isValidInviteCode(code: String): Boolean = InviteRegex.matches(code)

    fun pairDocId(uidA: String, uidB: String): String =
        if (uidA < uidB) "${uidA}_$uidB" else "${uidB}_$uidA"

    fun memberTag(displayName: String, handle: String): String {
        val cleanName = displayName.trim().ifBlank { "Listener" }.take(36)
        return "$cleanName (@$handle)".take(80)
    }

    fun generateInviteCode(length: Int = 8): String = buildString(length) {
        repeat(length) {
            append(InviteAlphabet[Random.nextInt(InviteAlphabet.length)])
        }
    }

    fun sanitizeHandleSuggestion(raw: String): String {
        val cleaned = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(24)
        return when {
            cleaned.length >= 3 -> cleaned
            cleaned.isNotEmpty() -> cleaned.padEnd(3, '1')
            else -> "listener_${100 + Random.nextInt(899)}"
        }
    }
}
