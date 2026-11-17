package app.orbitmusic.sources.youtube

import app.orbitmusic.core.model.AlbumPage
import app.orbitmusic.core.model.VideoStream
import app.orbitmusic.core.model.ArtistPage
import app.orbitmusic.core.model.Capability
import app.orbitmusic.core.model.Charts
import app.orbitmusic.core.model.Destination
import app.orbitmusic.core.model.Explore
import app.orbitmusic.core.model.Feed
import app.orbitmusic.core.model.FeedFilter
import app.orbitmusic.core.model.FeedSection
import app.orbitmusic.core.model.Lyrics
import app.orbitmusic.core.model.MediaEntity
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.MoodGroup
import app.orbitmusic.core.model.MusicSource
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.Page
import app.orbitmusic.core.model.PageToken
import app.orbitmusic.core.model.PlaylistPage
import app.orbitmusic.core.model.SearchFilter
import app.orbitmusic.core.model.SearchResults
import app.orbitmusic.core.model.SourceError
import app.orbitmusic.core.model.Stream
import app.orbitmusic.core.model.Suggestions
import app.orbitmusic.core.model.Track
import app.orbitmusic.core.model.UpNext
import app.orbitmusic.ytmusic.Chip
import app.orbitmusic.ytmusic.WatchTarget
import app.orbitmusic.ytmusic.YouTubeMusic

/** YouTube Music as a [MusicSource]: its whole catalogue, and its audio through [streams]. */
class YouTubeSource(
    private val yt: YouTubeMusic,
    private val streams: YouTubeStreamResolver,
) : MusicSource {

    override val id = SOURCE
    override val name = "YouTube Music"
    override val capabilities = setOf(
        Capability.HOME, Capability.EXPLORE, Capability.CHARTS, Capability.MOODS, Capability.SEARCH, Capability.ALBUMS,
        Capability.ARTISTS, Capability.PLAYLISTS, Capability.RADIO, Capability.LYRICS, Capability.STREAM,
    )

    override suspend fun home(): Outcome<Feed> = yt.home().toOutcome { it.toFeed() }

    override suspend fun homeMore(token: PageToken): Outcome<Feed> = yt.homeMore(token.toContinuation()).toOutcome { it.toFeed() }

    override suspend fun filter(filter: FeedFilter): Outcome<Feed> =
        yt.home(Chip(filter.title, filter.destination.toYt())).toOutcome { it.toFeed() }

    override suspend fun explore(): Outcome<Explore> = yt.explore().toOutcome { it.toExplore() }

    override suspend fun charts(countryCode: String?): Outcome<Charts> = yt.charts(countryCode).toOutcome { it.toCharts() }

    override suspend fun moods(): Outcome<List<MoodGroup>> = yt.moods().toOutcome { groups -> groups.map { it.toMoodGroup() } }

    override suspend fun browse(destination: Destination): Outcome<Feed> = when (destination.key) {
        // Explore's entries that are pages of their own kind.
        YouTubeMusic.CHARTS -> charts(null).let { r -> if (r is Outcome.Ok) Outcome.Ok(Feed(r.value.sections, title = "Charts")) else r as Outcome.Err }
        // A "See all" that is a playlist (Explore's Trending, say): its songs and videos, as one section.
        else -> if (destination.key.startsWith("VL")) {
            yt.playlist(destination.key).toOutcome { c ->
                val page = c.toPlaylistPage(destination.key.removePrefix("VL"))
                Feed(
                    sections = listOf(app.orbitmusic.core.model.FeedSection(page.playlist.title, page.tracks, wide = page.tracks.isNotEmpty() && page.tracks.all { it.kind == app.orbitmusic.core.model.MediaKind.MUSIC_VIDEO || it.kind == app.orbitmusic.core.model.MediaKind.VIDEO })),
                    title = page.playlist.title,
                )
            }
        } else {
            yt.browse(destination.toYt()).toOutcome { it.toFeed() }
        }
    }


    override suspend fun browseMore(token: PageToken): Outcome<Feed> = yt.browseMore(token.toContinuation()).toOutcome { it.toFeed() }

    override suspend fun album(id: MediaId): Outcome<AlbumPage> = yt.album(id.key).toOutcome { it.toAlbumPage(id.key) }

    override suspend fun artist(id: MediaId): Outcome<ArtistPage> = yt.artist(id.key).toOutcome { it.toArtistPage(id.key) }

    override suspend fun account(): Outcome<app.orbitmusic.core.model.ListenerAccount> =
        yt.account().toOutcome { app.orbitmusic.core.model.ListenerAccount(it.name, it.handle, it.photo.toArtwork()) }

    override suspend fun accountPlaylists(): Outcome<List<app.orbitmusic.core.model.Playlist>> {
        val first = yt.library(YouTubeMusic.LIBRARY_PLAYLISTS)
        val page = (first as? app.orbitmusic.ytmusic.YtResult.Success)?.value ?: return first.toOutcome { emptyList() }
        val all = page.items.toMutableList()
        var next = page.continuation
        // A long library comes a page at a time; twenty pages is far past any real one.
        var pages = 0
        while (next != null && pages++ < 20) {
            val more = (yt.libraryMore(next) as? app.orbitmusic.ytmusic.YtResult.Success)?.value ?: break
            all += more.items
            next = more.continuation
        }
        return Outcome.Ok(all.map { it.toEntity() }.filterIsInstance<app.orbitmusic.core.model.Playlist>().distinctBy { it.id })
    }

    override suspend fun playlist(id: MediaId): Outcome<PlaylistPage> = yt.playlist(id.key).toOutcome { it.toPlaylistPage(id.key) }

    override suspend fun tracksMore(token: PageToken): Outcome<Page<Track>> =
        yt.tracksMore(token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toTrack() }, p.continuation?.toToken()) }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> =
        yt.search(query, filter?.let { app.orbitmusic.ytmusic.SearchFilter(it.label, it.key) }).toOutcome { it.toResults() }

    override suspend fun searchMore(query: String, token: PageToken): Outcome<Page<MediaEntity>> =
        yt.searchMore(query, token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toEntity() }, p.continuation?.toToken()) }

    override suspend fun suggestions(input: String): Outcome<Suggestions> = yt.suggestions(input).toOutcome { it.toSuggestions() }

    override suspend fun upNext(track: Track): Outcome<UpNext> {
        if (track.id.source != SOURCE) return Outcome.Err(SourceError.Unsupported)
        val ctx = track.context
        val target = if (ctx?.key != null) WatchTarget(track.id.key, ctx.key, ctx.params) else WatchTarget.radio(track.id.key)
        return yt.upNext(target).toOutcome { it.toUpNext() }
    }

    override suspend fun lyrics(destination: Destination): Outcome<Lyrics?> = yt.lyrics(destination.toYt()).toOutcome { it?.toLyrics() }

    override suspend fun related(destination: Destination): Outcome<List<FeedSection>> =
        yt.related(destination.toYt()).toOutcome { shelves -> shelves.map { it.toSection() } }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> = streams.resolve(id.key, efficient)

    override suspend fun video(id: MediaId, maxHeight: Int): Outcome<VideoStream> = streams.video(id.key, maxHeight)

    override fun invalidate(id: MediaId) = streams.forget(id.key)
}
