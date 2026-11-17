package app.orbitmusic.cloud

import androidx.annotation.Keep
import app.orbitmusic.core.model.AlbumRef
import app.orbitmusic.core.model.ArtistRef
import app.orbitmusic.core.model.Artwork
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.Track
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.security.SecureRandom
import java.util.Locale

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val handle: String = "",
    val discordUsername: String? = null,
    val discordId: String? = null,
    val arySyncEnabled: Boolean = true,
    val cardShared: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class DirectoryEntry(
    @DocumentId val id: String = "",
    val uid: String = "",
    val handle: String = "",
    val displayName: String = "",
    val discordUsername: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class DiscordLookupEntry(
    @DocumentId val id: String = "",
    val uid: String = "",
    val discordUsername: String = "",
    val handle: String = "",
    val displayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ArySyncMessage(
    @DocumentId val id: String = "",
    val role: String = "user",
    val text: String = "",
    val changeSummaries: List<String> = emptyList(),
    val focusedPlaylistTitle: String? = null,
    val seq: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class Friendship(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val requesterUid: String = "",
    val recipientUid: String = "",
    val requesterName: String = "",
    val requesterHandle: String = "",
    val recipientName: String = "",
    val recipientHandle: String = "",
    val status: String = "pending",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    val accepted: Boolean get() = status == "accepted"
    fun otherUid(myUid: String): String = if (requesterUid == myUid) recipientUid else requesterUid
    fun otherName(myUid: String): String = if (requesterUid == myUid) recipientName else requesterName
    fun otherHandle(myUid: String): String = if (requesterUid == myUid) recipientHandle else requesterHandle
}

@Keep
@IgnoreExtraProperties
data class ListeningCardSnapshot(
    @DocumentId val id: String = "",
    val uid: String = "",
    val ownerName: String = "",
    val handle: String = "",
    val discordUsername: String? = null,
    val persona: String = "The listener",
    val sinceLabel: String? = null,
    val hoursLabel: String = "0 min",
    val plays: Int = 0,
    val artistCount: Int = 0,
    val bestStreak: Int = 0,
    val topSongTitle: String? = null,
    val topSongArtist: String? = null,
    val topGenre: String? = null,
    val topArtists: List<String> = emptyList(),
    val viewerUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class PlaylistInvite(
    @DocumentId val id: String = "",
    val playlistId: String = "",
    val ownerId: String = "",
    val title: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CollabPlaylist(
    @DocumentId val id: String = "",
    val title: String = "",
    val description: String? = null,
    val ownerId: String = "",
    val ownerName: String = "",
    val inviteCode: String = "",
    val memberUids: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val trackCount: Int = 0,
    val trackDocIds: List<String> = emptyList(),
    val coverUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CollabTrack(
    @DocumentId val id: String = "",
    val playlistId: String = "",
    val memberUids: List<String> = emptyList(),
    val mediaId: String = "",
    val title: String = "",
    val artistLine: String = "",
    val albumTitle: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val addedByUid: String = "",
    val addedByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
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
