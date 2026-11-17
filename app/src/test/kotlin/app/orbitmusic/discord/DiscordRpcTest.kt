package app.orbitmusic.discord

import app.orbitmusic.core.data.DiscordActivityType
import app.orbitmusic.core.data.DiscordConfig
import app.orbitmusic.core.data.DiscordOnlineStatus
import app.orbitmusic.core.data.DiscordStatusDisplay
import app.orbitmusic.core.model.AlbumRef
import app.orbitmusic.core.model.ArtistRef
import app.orbitmusic.core.model.Artwork
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.PlaybackState
import app.orbitmusic.core.model.PlaybackStatus
import app.orbitmusic.core.model.Track
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordRpcTest {

    private fun ytmTrack() = Track(
        id = MediaId.of("ytm", "dQw4w9WgXcQ"),
        title = "Midnight City",
        artists = listOf(ArtistRef("M83")),
        album = AlbumRef("Hurry Up, We're Dreaming"),
        durationMs = 243_000L,
        artwork = Artwork.single("https://example.com/cover.jpg", 600, 600),
    )

    private fun localTrack() = Track(
        id = MediaId.of("local", "42"),
        title = "Offline Song",
        artists = listOf(ArtistRef("Artist A"), ArtistRef("Artist B")),
        album = AlbumRef("Local Tape"),
        durationMs = 185_000L,
    )

    @Test
    fun formatTemplateSubstitutesAllPlaceholders() {
        val ctx = DiscordRpcTrackContext.fromPlayback(
            PlaybackState(status = PlaybackStatus.PLAYING, queue = listOf(ytmTrack()), index = 0),
            positionMs = 60_000L,
        )!!
        val rendered = DiscordRpcFormatter.formatTemplate(
            "{song_name} by {artist_name} on {album_name} ({track_duration}) via {app_name} [{yt_url}]",
            ctx,
        )
        assertEquals(
            "Midnight City by M83 on Hurry Up, We're Dreaming (4:03) via Orbit Music [https://music.youtube.com/watch?v=dQw4w9WgXcQ]",
            rendered,
        )
    }

    @Test
    fun resolveDisabledOrPausedRespectsToggles() {
        val playback = PlaybackState(status = PlaybackStatus.PAUSED, queue = listOf(ytmTrack()), index = 0, positionMs = 30_000L)
        val ctx = DiscordRpcTrackContext.fromPlayback(playback, 30_000L)

        assertNull(DiscordRpcFormatter.resolve(DiscordConfig(enabled = false), ctx))
        assertNull(DiscordRpcFormatter.resolve(DiscordConfig(enabled = true, showWhenPaused = false), ctx))

        val paused = DiscordRpcFormatter.resolve(DiscordConfig(enabled = true, showWhenPaused = true), ctx)
        assertNotNull(paused)
        assertTrue(paused.isPaused)
        assertNull(paused.startTimestampMs)
        assertNull(paused.endTimestampMs)
        assertEquals("M83 · Paused", paused.state)
    }

    @Test
    fun resolveComputesTimestampsAndOmitsInvalidButtonsForLocalTracks() {
        val playback = PlaybackState(status = PlaybackStatus.PLAYING, queue = listOf(localTrack()), index = 0)
        val ctx = DiscordRpcTrackContext.fromPlayback(playback, positionMs = 45_000L)
        val cfg = DiscordConfig(
            enabled = true,
            showTimestamps = true,
            showButtons = true,
            button1Label = "Listen on YouTube Music",
            button1Url = "{yt_url}",
            button2Label = "Orbit Music",
            button2Url = "https://github.com/orbit-music",
        )

        val resolved = DiscordRpcFormatter.resolve(cfg, ctx, nowMs = 1_000_000L)!!
        assertEquals(955_000L, resolved.startTimestampMs)
        assertEquals(1_140_000L, resolved.endTimestampMs)
        assertEquals(listOf("Orbit Music" to "https://github.com/orbit-music"), resolved.buttons)
    }

    @Test
    fun externalAssetNormalizationAddsMpPrefix() {
        assertEquals("mp:external/abc/https/cover.jpg", DiscordRpcFormatter.normalizeExternalAssetPath("external/abc/https/cover.jpg"))
        assertEquals("mp:external/abc/https/cover.jpg", DiscordRpcFormatter.normalizeExternalAssetPath("mp:external/abc/https/cover.jpg"))
        assertTrue(DiscordRpcFormatter.isDirectAssetRef("mp:external/abc"))
        assertTrue(DiscordRpcFormatter.isDirectAssetRef("custom_asset_key"))
        assertFalse(DiscordRpcFormatter.isDirectAssetRef("https://example.com/art.png"))
    }

    @Test
    fun buildPresencePayloadMatchesGatewayV10Schema() {
        val cfg = DiscordConfig(
            enabled = true,
            applicationId = "999888777",
            activityType = DiscordActivityType.LISTENING,
            statusDisplayType = DiscordStatusDisplay.DETAILS,
            onlineStatus = DiscordOnlineStatus.DND,
            customSmallImageUrl = "orbit_badge",
        )
        val ctx = DiscordRpcTrackContext.fromPlayback(
            PlaybackState(status = PlaybackStatus.PLAYING, queue = listOf(ytmTrack()), index = 0),
            positionMs = 10_000L,
        )
        val resolved = DiscordRpcFormatter.resolve(cfg, ctx, nowMs = 500_000L)!!
        val payload = DiscordRpcFormatter.buildPresenceUpdatePayload(
            presence = resolved,
            resolvedLargeAsset = "mp:external/123/cover.jpg",
            resolvedSmallAsset = "orbit_badge",
        )

        assertEquals(3, payload["op"]!!.jsonPrimitive.int)
        val d = payload["d"]!!.jsonObject
        assertEquals("dnd", d["status"]!!.jsonPrimitive.content)
        val activity = d["activities"]!!.jsonArray.first().jsonObject
        assertEquals("Orbit Music", activity["name"]!!.jsonPrimitive.content)
        assertEquals(2, activity["type"]!!.jsonPrimitive.int)
        assertEquals(2, activity["status_display_type"]!!.jsonPrimitive.int)
        assertEquals("999888777", activity["application_id"]!!.jsonPrimitive.content)
        assertEquals("Midnight City", activity["details"]!!.jsonPrimitive.content)
        assertEquals("M83", activity["state"]!!.jsonPrimitive.content)
        assertEquals(490_000L, activity["timestamps"]!!.jsonObject["start"]!!.jsonPrimitive.long)
        assertEquals(733_000L, activity["timestamps"]!!.jsonObject["end"]!!.jsonPrimitive.long)
        assertEquals("mp:external/123/cover.jpg", activity["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
        assertEquals("orbit_badge", activity["assets"]!!.jsonObject["small_image"]!!.jsonPrimitive.content)
        assertEquals(listOf("Listen on YouTube Music"), activity["buttons"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
            activity["metadata"]!!.jsonObject["button_urls"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
