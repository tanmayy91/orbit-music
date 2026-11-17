@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.orbitmusic.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.orbitmusic.cloud.CollabPlaylist
import app.orbitmusic.cloud.CollabTrack
import app.orbitmusic.cloud.UiState
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.core.designsystem.art.ArtTreatment
import app.orbitmusic.core.designsystem.art.TreatedArtwork
import app.orbitmusic.core.designsystem.component.GlassButton
import app.orbitmusic.core.designsystem.component.GlassPanel
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.GridDots
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.IconAction
import app.orbitmusic.core.designsystem.component.Notice
import app.orbitmusic.core.designsystem.component.SearchField
import app.orbitmusic.core.designsystem.component.Strapline
import app.orbitmusic.core.designsystem.component.TrackRow
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.glass.GlassWeight
import app.orbitmusic.core.designsystem.glass.liquidGlass
import app.orbitmusic.core.designsystem.theme.Corners
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Space
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.core.model.Artwork
import app.orbitmusic.core.model.Track
import app.orbitmusic.core.model.songCount
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.Route
import app.orbitmusic.ui.common.Colophon
import app.orbitmusic.ui.common.ConfirmDialog
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.NameDialog
import app.orbitmusic.ui.common.PageAction
import app.orbitmusic.ui.common.PageMenu
import app.orbitmusic.ui.common.rememberPageListState
import app.orbitmusic.ui.detail.BackLine

@Composable
fun CollabPlaylistScreen(playlistId: String) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val user by container.cloudAuth.authState.collectAsStateWithLifecycle(container.cloudAuth.currentUser)

    val activeUser = user
    if (activeUser == null) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState()) {
            item { BackLine() }
            item {
                Notice(
                    title = "Sign in to open playlist",
                    body = "Collaborative playlists require your Orbit Cloud sign-in.",
                    action = "Open Cloud",
                    onAction = { actions.nav.open(Route.Cloud) },
                )
            }
        }
        return
    }

    key("${activeUser.uid}:$playlistId") {
        CollabPlaylistContent(uid = activeUser.uid, playlistId = playlistId)
    }
}

@Composable
private fun CollabPlaylistContent(uid: String, playlistId: String) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val vm: CollabPlaylistViewModel = viewModel(
        key = "collab:$uid:$playlistId",
        factory = CollabPlaylistViewModel.Factory(container.cloud, container.sources, uid, playlistId),
    )

    val playlistState by vm.playlistState.collectAsStateWithLifecycle()
    val tracksState by vm.tracksState.collectAsStateWithLifecycle()
    val profileState by vm.profileState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()

    var showAddSongs by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            actions.keep.note(it)
            vm.clearMessage()
        }
    }

    val playlist = (playlistState as? UiState.Success)?.data
    val tracks = (tracksState as? UiState.Success)?.data.orEmpty()
    val profile = (profileState as? UiState.Success)?.data

    LaunchedEffect(playlist?.id, playlist?.trackDocIds?.size, tracks.size) {
        if (playlist != null && tracksState is UiState.Success) {
            vm.ensureTracksSynced(playlist, tracks)
        }
    }

    if (playlist != null) {
        val isOwner = playlist.ownerId == uid
        val menuActions = buildList {
            add(
                PageAction(Glyph.Share, "Copy invite code") {
                    copyInviteCode(context, playlist.inviteCode)
                    actions.keep.note("Copied code ${playlist.inviteCode}")
                },
            )
            if (isOwner) {
                add(PageAction(Glyph.Edit, "Rename playlist") { renaming = true })
                add(PageAction(Glyph.Trash, "Delete playlist") { confirmingDelete = true })
            } else {
                add(PageAction(Glyph.Close, "Leave playlist") { confirmingLeave = true })
            }
        }
        PageMenu(menuActions)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberPageListState(),
            contentPadding = PaddingValues(bottom = 0.dp),
        ) {
            item { BackLine() }
            when (val pState = playlistState) {
                UiState.Loading -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GridDots(size = 24.dp)
                    }
                }
                is UiState.Error -> item {
                    Notice(
                        title = "Couldn\u2019t load playlist",
                        body = pState.message,
                        action = "Go back",
                        onAction = { actions.nav.back() },
                    )
                }
                is UiState.Success -> {
                    val pl = pState.data
                    if (pl == null) {
                        item {
                            Notice(
                                title = "Playlist unavailable",
                                body = "This collaborative playlist may have been deleted by its creator.",
                                action = "Go back",
                                onAction = { actions.nav.back() },
                            )
                        }
                    } else {
                        item(key = "head") {
                            CollabPlaylistHead(
                                playlist = pl,
                                visibleTracks = tracks,
                                onCopyCode = {
                                    copyInviteCode(context, pl.inviteCode)
                                    actions.keep.note("Copied code ${pl.inviteCode}")
                                },
                                onPlay = {
                                    val queue = tracks.map { it.toTrack() }
                                    if (queue.isNotEmpty()) actions.play(queue, 0)
                                },
                                onShuffle = {
                                    val queue = tracks.map { it.toTrack() }.shuffled()
                                    if (queue.isNotEmpty()) actions.play(queue, 0)
                                },
                                onAddSongs = { showAddSongs = true },
                            )
                        }
                        when (val tState = tracksState) {
                            UiState.Loading -> item {
                                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                                    GridDots(size = 20.dp)
                                }
                            }
                            is UiState.Error -> item {
                                Notice("Couldn\u2019t load songs", tState.message)
                            }
                            is UiState.Success -> {
                                val list = tState.data
                                if (list.isEmpty()) {
                                    item {
                                        Notice(
                                            "No songs yet",
                                            "Tap Add songs above, or share invite code ${pl.inviteCode} so friends can add tracks.",
                                        )
                                    }
                                } else {
                                    val queue = list.map { it.toTrack() }
                                    itemsIndexed(list, key = { _, ct -> ct.id }) { index, ct ->
                                        val track = remember(ct.id, ct.title, ct.artworkUrl) { ct.toTrack() }
                                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
                                            TrackRow(
                                                track = track,
                                                onClick = { actions.play(queue, index) },
                                                onLongClick = { actions.openMenu(track) },
                                                trailing = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Txt(
                                                            ct.addedByName,
                                                            Type.Meta,
                                                            color = Ink.Dim,
                                                            maxLines = 1,
                                                            modifier = Modifier.widthIn(max = 88.dp).padding(end = 4.dp),
                                                        )
                                                        IconAction(
                                                            glyph = Glyph.Close,
                                                            label = "Remove ${track.title}",
                                                            size = 16.dp,
                                                            modifier = Modifier.testTag("collab_remove_track_${ct.id}"),
                                                            onClick = {
                                                                if (!busy) vm.removeTrack(pl, ct.id)
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Colophon() }
        }

        if (showAddSongs && profile != null) {
            AddSongOverlay(
                searching = searching,
                results = searchResults,
                onSearch = vm::searchSongs,
                onAdd = { track -> vm.addTrack(profile, track) },
                onDismiss = { showAddSongs = false },
            )
        }

        if (renaming && playlist != null) {
            NameDialog(
                title = "Playlist name",
                initial = playlist.title,
                onDone = { newName ->
                    renaming = false
                    if (newName.isNotBlank()) vm.renamePlaylist(playlist, newName)
                },
                onDismiss = { renaming = false },
            )
        }

        if (confirmingLeave && playlist != null && profile != null) {
            ConfirmDialog(
                title = "Leave playlist?",
                body = "You can always rejoin \u201c${playlist.title}\u201d later using its invite code.",
                confirm = "Leave",
                onConfirm = {
                    confirmingLeave = false
                    vm.leavePlaylist(profile) { actions.nav.back() }
                },
                onDismiss = { confirmingLeave = false },
            )
        }

        if (confirmingDelete && playlist != null) {
            ConfirmDialog(
                title = "Delete playlist?",
                body = "\u201c${playlist.title}\u201d and all collaborative tracks in it will be removed for everyone.",
                confirm = "Delete",
                onConfirm = {
                    confirmingDelete = false
                    vm.deletePlaylist(playlist) { actions.nav.back() }
                },
                onDismiss = { confirmingDelete = false },
            )
        }
    }
}

@Composable
private fun CollabPlaylistHead(
    playlist: CollabPlaylist,
    visibleTracks: List<CollabTrack>,
    onCopyCode: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddSongs: () -> Unit,
) {
    val art = remember(visibleTracks.firstOrNull()?.artworkUrl, playlist.coverUrl) {
        val url = visibleTracks.firstOrNull()?.artworkUrl ?: playlist.coverUrl
        if (!url.isNullOrBlank()) Artwork.single(url, 600, 600) else Artwork.None
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!art.isEmpty) {
            TreatedArtwork(art, ArtTreatment.Cover, Modifier.size(164.dp).clip(Corners.Large), sizeHintPx = 500)
            Spacer(Modifier.height(18.dp))
        }
        Txt(playlist.title, Type.Monument, align = TextAlign.Center, maxLines = 2)
        playlist.description?.let { desc ->
            Spacer(Modifier.height(6.dp))
            Txt(desc, Type.Aside, color = Ink.Grey, align = TextAlign.Center, maxLines = 3)
        }
        Spacer(Modifier.height(10.dp))
        val songTotal = maxOf(playlist.trackCount, visibleTracks.size)
        Strapline("Collaborative  ·  ${songCount(songTotal)}  ·  Created by ${playlist.ownerName}")
        Spacer(Modifier.height(14.dp))
        GlassPill(
            text = "Invite code: ${playlist.inviteCode}",
            icon = Glyph.Share,
            small = true,
            modifier = Modifier.testTag("collab_invite_code_pill"),
            onClick = onCopyCode,
        )
        if (playlist.memberNames.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                playlist.memberNames.take(12).forEach { member ->
                    GlassPill(text = member, small = true)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (visibleTracks.isNotEmpty()) {
                GlassButton(
                    text = "Play",
                    lit = true,
                    modifier = Modifier.testTag("collab_play_button"),
                    onClick = onPlay,
                )
                GlassPill(
                    text = "Shuffle",
                    icon = Glyph.Shuffle,
                    modifier = Modifier.testTag("collab_shuffle_button"),
                    onClick = onShuffle,
                )
            }
            GlassPill(
                text = "Add songs",
                icon = Glyph.Plus,
                lit = visibleTracks.isEmpty(),
                modifier = Modifier.testTag("collab_add_songs_button"),
                onClick = onAddSongs,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AddSongOverlay(
    searching: Boolean,
    results: List<Track>,
    onSearch: (String) -> Unit,
    onAdd: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("Add songs", Type.Headline, modifier = Modifier.weight(1f))
                IconAction(Glyph.Close, "Close song search", onDismiss, size = 18.dp)
            }
            Spacer(Modifier.height(12.dp))
            SearchField(
                query = query,
                onQuery = {
                    query = it
                    onSearch(it)
                },
                onSubmit = onSearch,
                loading = searching,
                modifier = Modifier.testTag("collab_add_song_search_input"),
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp)) {
                when {
                    query.isBlank() -> Txt(
                        "Search for a song or artist to add to this collaborative playlist.",
                        Type.Aside,
                        color = Ink.Grey,
                        align = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    searching && results.isEmpty() -> GridDots(Modifier.align(Alignment.Center), size = 20.dp)
                    results.isEmpty() -> Txt(
                        "No songs matched \u201c$query\u201d.",
                        Type.Aside,
                        color = Ink.Grey,
                        align = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(results.take(30), key = { it.id.value }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onAdd(track) },
                                trailing = {
                                    IconAction(
                                        glyph = Glyph.Plus,
                                        label = "Add ${track.title}",
                                        size = 18.dp,
                                        modifier = Modifier.testTag("collab_search_add_${track.id.key}"),
                                        onClick = { onAdd(track) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
