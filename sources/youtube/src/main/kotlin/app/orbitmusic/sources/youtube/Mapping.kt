package app.orbitmusic.sources.youtube

import app.orbitmusic.core.model.AlbumPage
import app.orbitmusic.core.model.AlbumRef
import app.orbitmusic.core.model.ArtistRef
import app.orbitmusic.core.model.Artwork
import app.orbitmusic.core.model.ArtworkSize
import app.orbitmusic.core.model.Charts
import app.orbitmusic.core.model.Destination
import app.orbitmusic.core.model.Explore
import app.orbitmusic.core.model.Feed
import app.orbitmusic.core.model.FeedFilter
import app.orbitmusic.core.model.FeedSection
import app.orbitmusic.core.model.Lyrics
import app.orbitmusic.core.model.MediaEntity
import app.orbitmusic.core.model.MediaId
import app.orbitmusic.core.model.MediaKind
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.PageToken
import app.orbitmusic.core.model.PlayContext
import app.orbitmusic.core.model.PlaylistPage
import app.orbitmusic.core.model.SearchFilter
import app.orbitmusic.core.model.SearchResults
import app.orbitmusic.core.model.SectionStyle
import app.orbitmusic.core.model.SourceError
import app.orbitmusic.core.model.Suggestions
import app.orbitmusic.core.model.Track
import app.orbitmusic.core.model.UpNext
import app.orbitmusic.ytmusic.Album
import app.orbitmusic.ytmusic.Artist
import app.orbitmusic.ytmusic.ArtistPage
import app.orbitmusic.ytmusic.BrowsePage
import app.orbitmusic.ytmusic.ChartsPage
import app.orbitmusic.ytmusic.Collection
import app.orbitmusic.ytmusic.Continuation
import app.orbitmusic.ytmusic.Country
import app.orbitmusic.ytmusic.ExplorePage
import app.orbitmusic.ytmusic.HomePage
import app.orbitmusic.ytmusic.Item
import app.orbitmusic.ytmusic.Link
import app.orbitmusic.ytmusic.MoodGroup
import app.orbitmusic.ytmusic.Playlist
import app.orbitmusic.ytmusic.Podcast
import app.orbitmusic.ytmusic.Ref
import app.orbitmusic.ytmusic.Shelf
import app.orbitmusic.ytmusic.ShelfStyle
import app.orbitmusic.ytmusic.Song
import app.orbitmusic.ytmusic.Thumbnail
import app.orbitmusic.ytmusic.WatchTarget
import app.orbitmusic.ytmusic.YtError
import app.orbitmusic.ytmusic.YtResult
import app.orbitmusic.ytmusic.urlFor
import app.orbitmusic.ytmusic.MediaKind as YtKind

/*
 * YouTube Music's answers as AETHER's model. Ids are `ytm:` plus the service's own key: a video id
 * for tracks, a browse id for albums (MPRE…), artists (UC…) and shows, a playlist id (no VL) for
 * playlists. Only this module reads those keys.
 */

internal const val SOURCE = "ytm"

internal fun id(key: String) = MediaId.of(SOURCE, key)

internal fun <T, R> YtResult<T>.toOutcome(transform: (T) -> R): Outcome<R> = when (this) {
    is YtResult.Success -> Outcome.Ok(transform(value))
    is YtResult.Failure -> Outcome.Err(error.toSourceError())
}

internal fun YtError.toSourceError(): SourceError = when (this) {
    YtError.Offline -> SourceError.Offline
    YtError.Network -> SourceError.Network
    is YtError.Server -> SourceError.Server(code)
    is YtError.RateLimited -> SourceError.RateLimited(retryAfterMs)
    YtError.AuthRequired -> SourceError.AuthRequired
    YtError.AuthExpired -> SourceError.AuthExpired
    is YtError.NotFound -> SourceError.NotFound(what)
}

// Continuations travel as page tokens: "b:" when the token goes in the body, "q:" in the query.
internal fun Continuation.toToken() = PageToken((if (inBody) "b:" else "q:") + token)

internal fun PageToken.toContinuation() = Continuation(value.substring(2), inBody = value.startsWith("b:"))

internal fun Destination.toYt() = app.orbitmusic.ytmusic.Destination(key, params)

internal fun app.orbitmusic.ytmusic.Destination.toDestination() = Destination(SOURCE, browseId, params)

/**
 * The service's images, plus the sizes its image hosts make on request (album and artist art), so
 * screens can ask for exactly what they draw.
 */
internal fun List<Thumbnail>.toArtwork(): Artwork {
    if (isEmpty()) return Artwork.None
    val given = map { ArtworkSize(it.url, it.width, it.height) }
    val largest = last()
    val host = largest.url.substringAfter("://").substringBefore('/')
    val resizable = host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")
    if (!resizable) return Artwork(given)
    val made = RESIZED.mapNotNull { px ->
        val url = urlFor(px) ?: return@mapNotNull null
        val height = if (largest.width > 0 && largest.height > 0) px * largest.height / largest.width else px
        ArtworkSize(url, px, height)
    }
    return Artwork((given + made).distinctBy { it.width }.sortedBy { it.width })
}

private val RESIZED = listOf(226, 544, 1080, 1440)

internal fun Ref.toArtistRef() = ArtistRef(name, browseId?.let { id(it) })

internal fun Song.toTrack(): Track = Track(
    id = id(videoId),
    title = title,
    artists = artists.map { it.toArtistRef() },
    album = album?.let { AlbumRef(it.name, it.browseId?.let { b -> id(b) }) },
    durationMs = durationMs,
    artwork = thumbnails.toArtwork(),
    kind = when (kind) {
        YtKind.SONG -> MediaKind.SONG
        YtKind.MUSIC_VIDEO -> MediaKind.MUSIC_VIDEO
        YtKind.VIDEO -> MediaKind.VIDEO
        YtKind.EPISODE -> MediaKind.EPISODE
    },
    explicit = explicit,
    trackNumber = trackNumber,
    year = year,
    plays = plays,
    available = playable,
    context = if (playlistId != null || playParams != null) PlayContext(playlistId, playParams) else null,
    subtitle = subtitle,
)

internal fun Album.toAlbum() = app.orbitmusic.core.model.Album(
    id = id(browseId),
    title = title,
    artists = artists.map { it.toArtistRef() },
    year = year,
    artwork = thumbnails.toArtwork(),
    type = type,
    explicit = explicit,
    subtitle = subtitle,
)

internal fun Artist.toArtist() = app.orbitmusic.core.model.Artist(id(browseId), title, thumbnails.toArtwork(), subtitle)

internal fun Playlist.toPlaylist() =
    app.orbitmusic.core.model.Playlist(id(playlistId), title, author, trackCount, thumbnails.toArtwork(), subtitle)

internal fun Podcast.toPodcast() = app.orbitmusic.core.model.Podcast(id(browseId), title, author, thumbnails.toArtwork(), subtitle)

internal fun Link.toLink() = app.orbitmusic.core.model.Link(title, destination.toDestination(), color, icon)

internal fun Item.toEntity(): MediaEntity = when (this) {
    is Song -> toTrack()
    is Album -> toAlbum()
    is Artist -> toArtist()
    is Playlist -> toPlaylist()
    is Podcast -> toPodcast()
    is Link -> toLink()
}

internal fun Shelf.toSection() = FeedSection(
    title = title,
    items = items.map { it.toEntity() },
    strapline = strapline,
    style = when (style) {
        ShelfStyle.CAROUSEL -> SectionStyle.CAROUSEL
        ShelfStyle.ROWS -> SectionStyle.ROWS
        ShelfStyle.LIST -> SectionStyle.LIST
        ShelfStyle.GRID -> SectionStyle.GRID
    },
    wide = wide,
    more = more?.toDestination(),
)

internal fun HomePage.toFeed() = Feed(
    sections = shelves.map { it.toSection() },
    filters = chips.map { FeedFilter(it.title, it.destination.toDestination()) },
    backdrop = background.toArtwork(),
    next = continuation?.toToken(),
)

internal fun BrowsePage.toFeed() = Feed(sections = shelves.map { it.toSection() }, title = title, next = continuation?.toToken())

internal fun ExplorePage.toExplore() = Explore(links.map { it.toLink() }, shelves.map { it.toSection() })

internal fun Country.toCountry() = app.orbitmusic.core.model.Country(code, name)

internal fun ChartsPage.toCharts() = Charts(country?.toCountry(), countries.map { it.toCountry() }, shelves.map { it.toSection() })

internal fun MoodGroup.toMoodGroup() = app.orbitmusic.core.model.MoodGroup(title, moods.map { it.toLink() })

internal fun Collection.toAlbumPage(browseId: String) = AlbumPage(
    album = app.orbitmusic.core.model.Album(
        id = id(browseId),
        title = title,
        artists = artists.map { it.toArtistRef() },
        year = year,
        artwork = thumbnails.toArtwork(),
        subtitle = subtitle,
    ),
    // Album rows rarely carry their own art or album: they're the album's.
    tracks = tracks.map { song ->
        val t = song.toTrack()
        t.copy(
            artwork = if (t.artwork.isEmpty) thumbnails.toArtwork() else t.artwork,
            album = t.album ?: AlbumRef(title, id(browseId)),
            artists = t.artists.ifEmpty { artists.map { it.toArtistRef() } },
            context = t.context ?: playlistId?.let { PlayContext(it, null) },
        )
    },
    details = secondSubtitle,
    description = description,
    next = continuation?.toToken(),
)

internal fun Collection.toPlaylistPage(playlistId: String) = PlaylistPage(
    playlist = app.orbitmusic.core.model.Playlist(
        id = id(playlistId),
        title = title,
        author = artists.firstOrNull()?.name,
        trackCount = trackCount,
        artwork = thumbnails.toArtwork(),
        subtitle = subtitle,
    ),
    tracks = tracks.map { song -> song.toTrack().let { it.copy(context = it.context ?: PlayContext(playlistId, null)) } },
    details = secondSubtitle,
    description = description,
    next = continuation?.toToken(),
)

internal fun ArtistPage.toArtistPage(browseId: String) = app.orbitmusic.core.model.ArtistPage(
    artist = app.orbitmusic.core.model.Artist(id(browseId), name, thumbnails.toArtwork(), monthlyAudience),
    description = description,
    subscribers = subscribers,
    monthlyAudience = monthlyAudience,
    sections = shelves.map { it.toSection() },
    topTracks = songs.map { it.toTrack() },
    radio = radio?.toContext(),
    shuffle = shuffle?.toContext(),
)

internal fun WatchTarget.toContext() = PlayContext(playlistId, params)

internal fun app.orbitmusic.ytmusic.SearchPage.toResults() = SearchResults(
    items = items.map { it.toEntity() },
    filters = filters.map { SearchFilter(it.label, it.params) },
    next = continuation?.toToken(),
)

internal fun app.orbitmusic.ytmusic.Suggestions.toSuggestions() = Suggestions(queries, items.map { it.toEntity() })

internal fun app.orbitmusic.ytmusic.UpNext.toUpNext() = UpNext(
    tracks = songs.map { it.toTrack() },
    next = continuation?.toToken(),
    lyrics = lyrics?.toDestination(),
    related = related?.toDestination(),
)

internal fun app.orbitmusic.ytmusic.Lyrics.toLyrics() = Lyrics(text, source)
