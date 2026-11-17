package app.orbitmusic.assistant

import app.orbitmusic.core.data.LocalPlaylist
import app.orbitmusic.core.data.PlaylistEntry
import app.orbitmusic.core.model.ArtistRef
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.MediaKind
import app.orbitmusic.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AryModelsTest {
    private fun track(
        id: String,
        title: String,
        artist: String,
        kind: MediaKind = MediaKind.SONG,
    ) = Track(
        id = MediaId.of("ytm", id),
        title = title,
        artists = listOf(ArtistRef(artist)),
        kind = kind,
    )

    @Test
    fun `decodes structured add and remove plan from Gemini JSON`() {
        val raw = """
            {
              "reply": "Added Starboy and removed Blinding Lights from Chill.",
              "operations": [
                {
                  "action": "add_songs",
                  "playlistId": 42,
                  "playlistTitle": "Chill",
                  "songQueries": ["Starboy - The Weeknd"]
                },
                {
                  "action": "remove_songs",
                  "playlistId": 42,
                  "removeEntryIds": [7],
                  "removeTrackTitles": ["Blinding Lights"]
                }
              ]
            }
        """.trimIndent()

        val plan = AryJson.decodePlan(raw)
        assertEquals("Added Starboy and removed Blinding Lights from Chill.", plan.reply)
        assertEquals(2, plan.operations.size)
        assertEquals(AryAction.add_songs, plan.operations[0].action)
        assertEquals(42L, plan.operations[0].playlistId)
        assertEquals(listOf("Starboy - The Weeknd"), plan.operations[0].songQueries)
        assertEquals(AryAction.remove_songs, plan.operations[1].action)
        assertEquals(listOf(7L), plan.operations[1].removeEntryIds)
        assertEquals(listOf("Blinding Lights"), plan.operations[1].removeTrackTitles)
    }

    @Test
    fun `findBestTrackMatch prefers song over video when title and artist match`() {
        val video = track("v1", "Blinding Lights", "The Weeknd", kind = MediaKind.MUSIC_VIDEO)
        val song = track("s1", "Blinding Lights", "The Weeknd", kind = MediaKind.SONG)
        val cover = track("s2", "Blinding Lights (Acoustic)", "Someone Else", kind = MediaKind.SONG)

        val picked = findBestTrackMatch("Blinding Lights - The Weeknd", listOf(video, cover, song))
        assertEquals("s1", picked?.id?.key)
    }

    @Test
    fun `findMatchingEntries resolves by id first and falls back to title without duplicates`() {
        val entries = listOf(
            PlaylistEntry(101L, track("a", "Midnight City", "M83")),
            PlaylistEntry(102L, track("b", "After Hours", "The Weeknd")),
            PlaylistEntry(103L, track("c", "Nightcall", "Kavinsky")),
        )

        val matched = findMatchingEntries(
            entries = entries,
            entryIds = listOf(102L),
            titles = listOf("After Hours", "nightcall"),
        )
        assertEquals(listOf(102L, 103L), matched.map { it.entryId })
    }

    @Test
    fun `buildAryPrompt includes playlist ids, entry ids, and focused context`() {
        val playlist = LocalPlaylist(
            id = 5L,
            title = "Late Night",
            description = null,
            artworkUri = null,
            folderId = null,
            count = 1,
            updatedAt = 0L,
            preview = emptyList(),
        )
        val entry = PlaylistEntry(88L, track("x", "Resonance", "HOME"))

        val prompt = buildAryPrompt(
            assistantName = "Ary",
            userName = "Tanmay",
            focusedPlaylistId = 5L,
            playlists = listOf(AryPlaylistContext(playlist, listOf(entry))),
            currentTrack = null,
            history = emptyList(),
            userPrompt = "Remove Resonance",
        )

        assertTrue("Ary" in prompt)
        assertTrue("Tanmay" in prompt)
        assertTrue("id=5" in prompt)
        assertTrue("entryId=88" in prompt)
        assertTrue("Resonance" in prompt)
    }
}
