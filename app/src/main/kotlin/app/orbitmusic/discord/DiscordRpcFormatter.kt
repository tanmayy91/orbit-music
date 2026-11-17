package app.orbitmusic.discord

import app.orbitmusic.core.data.DiscordActivityType
import app.orbitmusic.core.data.DiscordConfig
import app.orbitmusic.core.data.DiscordOnlineStatus
import app.orbitmusic.core.data.DiscordStatusDisplay
import app.orbitmusic.core.model.PlaybackState
import app.orbitmusic.core.model.PlaybackStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

data class DiscordRpcTrackContext(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val artworkUrl: String?,
    val trackUrl: String?,
) {
    companion object {
        fun fromPlayback(state: PlaybackState, positionMs: Long = state.positionMs): DiscordRpcTrackContext? {
            val track = state.current ?: return null
            val playing = state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.BUFFERING
            val ytUrl = if (track.id.source == "ytm" && track.id.key.isNotBlank()) {
                "https://music.youtube.com/watch?v=${track.id.key}"
            } else {
                null
            }
            return DiscordRpcTrackContext(
                trackId = track.id.value,
                title = track.title,
                artist = track.artistLine,
                album = track.album?.title.orEmpty(),
                durationMs = (track.durationMs ?: state.durationMs).coerceAtLeast(0L),
                positionMs = positionMs.coerceAtLeast(0L),
                isPlaying = playing,
                artworkUrl = track.artwork.url(600),
                trackUrl = ytUrl,
            )
        }

        fun sample(): DiscordRpcTrackContext = DiscordRpcTrackContext(
            trackId = "ytm:sample",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationMs = 243_000L,
            positionMs = 74_000L,
            isPlaying = true,
            artworkUrl = null,
            trackUrl = "https://music.youtube.com/watch?v=dX3k_QDnzHE",
        )
    }
}

data class ResolvedDiscordPresence(
    val applicationId: String,
    val activityName: String,
    val activityType: DiscordActivityType,
    val statusDisplayType: DiscordStatusDisplay,
    val onlineStatus: DiscordOnlineStatus,
    val details: String?,
    val state: String?,
    val largeImageRaw: String?,
    val largeText: String?,
    val smallImageRaw: String?,
    val smallText: String?,
    val startTimestampMs: Long?,
    val endTimestampMs: Long?,
    val buttons: List<Pair<String, String>>,
    val isPaused: Boolean,
)

object DiscordRpcFormatter {

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatTemplate(template: String, ctx: DiscordRpcTrackContext): String {
        if (template.isBlank()) return ""
        return template
            .replace("{song_name}", ctx.title)
            .replace("{artist_name}", ctx.artist)
            .replace("{album_name}", ctx.album)
            .replace("{app_name}", "Orbit Music")
            .replace("{track_duration}", formatDuration(ctx.durationMs))
            .replace("{yt_url}", ctx.trackUrl.orEmpty())
            .trim()
    }

    fun resolve(
        config: DiscordConfig,
        ctx: DiscordRpcTrackContext?,
        nowMs: Long = System.currentTimeMillis(),
        ignorePausedGate: Boolean = false,
    ): ResolvedDiscordPresence? {
        if (!ignorePausedGate && !config.enabled) return null
        val track = ctx ?: return null
        if (!ignorePausedGate && !track.isPlaying && !config.showWhenPaused) return null

        val name = formatTemplate(config.activityName, track).ifBlank { "Orbit Music" }.take(128)
        val details = formatTemplate(config.detailsTemplate, track).take(128).ifBlank { null }
        val baseState = formatTemplate(config.stateTemplate, track).take(128).ifBlank { null }
        val state = if (!track.isPlaying && config.showWhenPaused) {
            listOfNotNull(baseState, "Paused").joinToString(" · ").take(128)
        } else {
            baseState
        }

        val largeImageRaw = when {
            config.customLargeImageUrl.isNotBlank() -> config.customLargeImageUrl.trim()
            config.showArtwork -> track.artworkUrl
            else -> null
        }
        val largeText = formatTemplate(config.largeImageTextTemplate, track).take(128).ifBlank { null }

        val smallImageRaw = if (config.showSmallIcon) {
            config.customSmallImageUrl.trim().ifBlank { null }
        } else {
            null
        }
        val smallText = if (config.showSmallIcon) {
            formatTemplate(config.smallImageTextTemplate, track).take(128).ifBlank { null }
        } else {
            null
        }

        val (startTs, endTs) = if (config.showTimestamps && track.isPlaying) {
            val start = (nowMs - track.positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            val end = if (track.durationMs > 0L) start + track.durationMs else null
            start to end
        } else {
            null to null
        }

        val buttons = if (config.showButtons) {
            buildList {
                addValidButton(config.button1Label, config.button1Url, track)
                addValidButton(config.button2Label, config.button2Url, track)
            }
        } else {
            emptyList()
        }

        return ResolvedDiscordPresence(
            applicationId = config.applicationId.trim().ifBlank { DiscordConfig.DEFAULT_APPLICATION_ID },
            activityName = name,
            activityType = config.activityType,
            statusDisplayType = config.statusDisplayType,
            onlineStatus = config.onlineStatus,
            details = details,
            state = state,
            largeImageRaw = largeImageRaw,
            largeText = largeText,
            smallImageRaw = smallImageRaw,
            smallText = smallText,
            startTimestampMs = startTs,
            endTimestampMs = endTs,
            buttons = buttons,
            isPaused = !track.isPlaying,
        )
    }

    private fun MutableList<Pair<String, String>>.addValidButton(
        labelTemplate: String,
        urlTemplate: String,
        track: DiscordRpcTrackContext,
    ) {
        if (size >= 2) return
        val label = formatTemplate(labelTemplate, track).take(32)
        val url = formatTemplate(urlTemplate, track)
        if (label.isNotBlank() && isValidButtonUrl(url)) {
            add(label to url)
        }
    }

    fun isValidButtonUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
    }

    fun normalizeExternalAssetPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        return if (trimmed.startsWith("mp:") || !trimmed.contains("/")) trimmed else "mp:$trimmed"
    }

    fun isDirectAssetRef(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("mp:") || (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true))
    }

    fun buildIdentifyPayload(token: String): JsonObject = buildJsonObject {
        put("op", 2)
        put("d", buildJsonObject {
            put("token", token)
            put("capabilities", 0)
            put("properties", buildJsonObject {
                put("os", "Android")
                put("browser", "Discord Android")
                put("device", "Android")
            })
            put("compress", false)
            put("intents", 0)
        })
    }

    fun buildResumePayload(token: String, sessionId: String, sequence: Long): JsonObject = buildJsonObject {
        put("op", 6)
        put("d", buildJsonObject {
            put("token", token)
            put("session_id", sessionId)
            put("seq", sequence)
        })
    }

    fun buildHeartbeatPayload(sequence: Long?): JsonObject = buildJsonObject {
        put("op", 1)
        if (sequence == null) put("d", JsonPrimitive(null as String?)) else put("d", sequence)
    }

    fun buildClearPresencePayload(status: DiscordOnlineStatus): JsonObject = buildJsonObject {
        put("op", 3)
        put("d", buildJsonObject {
            put("since", 0L)
            put("activities", JsonArray(emptyList()))
            put("status", status.wireValue)
            put("afk", false)
        })
    }

    fun buildPresenceUpdatePayload(
        presence: ResolvedDiscordPresence,
        resolvedLargeAsset: String?,
        resolvedSmallAsset: String?,
    ): JsonObject = buildJsonObject {
        put("op", 3)
        put("d", buildJsonObject {
            put("since", 0L)
            put("status", presence.onlineStatus.wireValue)
            put("afk", false)
            put("activities", buildJsonArray {
                add(buildJsonObject {
                    put("name", presence.activityName)
                    put("type", presence.activityType.code)
                    if (presence.activityType == DiscordActivityType.LISTENING) {
                        put("status_display_type", presence.statusDisplayType.code)
                    }
                    put("application_id", presence.applicationId)
                    presence.details?.let { put("details", it) }
                    presence.state?.let { put("state", it) }
                    if (presence.startTimestampMs != null || presence.endTimestampMs != null) {
                        put("timestamps", buildJsonObject {
                            presence.startTimestampMs?.let { put("start", it) }
                            presence.endTimestampMs?.let { put("end", it) }
                        })
                    }
                    val hasAnyAsset = !resolvedLargeAsset.isNullOrBlank() ||
                        !presence.largeText.isNullOrBlank() ||
                        !resolvedSmallAsset.isNullOrBlank() ||
                        !presence.smallText.isNullOrBlank()
                    if (hasAnyAsset) {
                        put("assets", buildJsonObject {
                            resolvedLargeAsset?.takeIf { it.isNotBlank() }?.let { put("large_image", it) }
                            presence.largeText?.takeIf { it.isNotBlank() }?.let { put("large_text", it) }
                            resolvedSmallAsset?.takeIf { it.isNotBlank() }?.let { put("small_image", it) }
                            presence.smallText?.takeIf { it.isNotBlank() }?.let { put("small_text", it) }
                        })
                    }
                    if (presence.buttons.isNotEmpty()) {
                        put("buttons", buildJsonArray {
                            presence.buttons.forEach { (label, _) -> add(label) }
                        })
                        put("metadata", buildJsonObject {
                            put("button_urls", buildJsonArray {
                                presence.buttons.forEach { (_, url) -> add(url) }
                            })
                        })
                    }
                })
            })
        })
    }
}
