@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.orbitmusic.ui.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.orbitmusic.R
import app.orbitmusic.assistant.AryChange
import app.orbitmusic.assistant.AryChangeKind
import app.orbitmusic.assistant.AryMessage
import app.orbitmusic.assistant.AryUiState
import app.orbitmusic.assistant.AryViewModel
import app.orbitmusic.core.data.LocalPlaylist
import app.orbitmusic.core.data.Settings
import app.orbitmusic.core.designsystem.art.ArtTreatment
import app.orbitmusic.core.designsystem.art.TreatedArtwork
import app.orbitmusic.core.designsystem.component.GlassCircle
import app.orbitmusic.core.designsystem.component.GlassPanel
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.GridDots
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.LocalHaptics
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.component.quietClick
import app.orbitmusic.core.designsystem.glass.GlassWeight
import app.orbitmusic.core.designsystem.glass.LocalReduceMotion
import app.orbitmusic.core.designsystem.glass.liquidGlass
import app.orbitmusic.core.designsystem.glass.liquidTouch
import app.orbitmusic.core.designsystem.glass.rememberLiquidState
import app.orbitmusic.core.designsystem.theme.Corners
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Space
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.core.model.songCount
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.Route
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.PageAction
import app.orbitmusic.ui.common.PageMenu

@Composable
fun AryScreen(playlistId: Long? = null) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val focusManager = LocalFocusManager.current
    val vm: AryViewModel = viewModel(
        key = "ary:${playlistId ?: "all"}",
        factory = AryViewModel.Factory(container, playlistId),
    )

    val chosenAssistant by container.settings.assistantName.collectAsStateWithLifecycle()
    val assistantName = chosenAssistant?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_ASSISTANT_NAME
    val playlists by container.library.playlists.collectAsStateWithLifecycle(emptyList())
    val focusedId by vm.focusedPlaylistId.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, uiState) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    if (messages.isNotEmpty()) {
        PageMenu(
            entries = listOf(
                PageAction(Glyph.Trash, "Clear chat") { vm.clearConversation() },
            ),
        )
    }

    val focusedPlaylist = playlists.firstOrNull { it.id == focusedId }
    val isLoading = uiState is AryUiState.Loading

    fun submit(text: String = input) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isLoading) return
        if (text === input) input = ""
        focusManager.clearFocus()
        vm.sendMessage(trimmed)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .imePadding(),
    ) {
        // Header room for the shell's floating Back key and PageCornerMenu.
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 72.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AryAvatar(size = 28.dp, contentDescription = "$assistantName icon")
                Spacer(Modifier.width(9.dp))
                Txt(
                    text = assistantName,
                    style = Type.Label,
                    color = Ink.White,
                    upper = true,
                    maxLines = 1,
                )
            }
            focusedPlaylist?.let { p ->
                Txt(
                    text = "${p.title}  ·  ${songCount(p.count)}",
                    style = Type.Meta,
                    color = Ink.Grey,
                    align = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Space.edge),
                )
            }
            if (playlists.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                PlaylistFocusBar(
                    playlists = playlists,
                    selectedId = focusedId,
                    onSelect = vm::selectPlaylist,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
        }

        // Conversation feed
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (messages.isEmpty() && uiState is AryUiState.Idle) {
                item(key = "welcome") {
                    EmptyAryState(
                        assistantName = assistantName,
                        focusedPlaylist = focusedPlaylist,
                        hasPlaylists = playlists.isNotEmpty(),
                        currentSongTitle = playback.current?.title,
                        onSuggestion = { suggestion -> submit(suggestion) },
                    )
                }
            }

            items(messages, key = { it.id }) { msg ->
                when (msg) {
                    is AryMessage.User -> UserBubble(msg.text)
                    is AryMessage.Assistant -> AssistantTurn(
                        assistantName = assistantName,
                        message = msg,
                        onPlayTrack = { track -> actions.play(listOf(track)) },
                        onOpenPlaylist = { pid -> actions.go(Route.LocalPlaylist(pid)) },
                    )
                }
            }

            if (uiState is AryUiState.Loading) {
                item(key = "loading") {
                    LoadingTurn(assistantName, (uiState as AryUiState.Loading).status)
                }
            }

            if (uiState is AryUiState.Error) {
                item(key = "error") {
                    ErrorTurn(
                        message = (uiState as AryUiState.Error).message,
                        onRetry = vm::retryLast,
                    )
                }
            }
        }

        // Composer
        ComposerBar(
            value = input,
            onValueChange = { input = it },
            placeholder = if (focusedPlaylist != null) {
                "Ask $assistantName about ${focusedPlaylist.title}…"
            } else {
                "Ask $assistantName to add or remove songs…"
            },
            sending = isLoading,
            onSend = { submit(input) },
        )
    }
}

@Composable
private fun PlaylistFocusBar(
    playlists: List<LocalPlaylist>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            GlassPill(
                text = "All playlists",
                lit = selectedId == null,
                small = true,
                modifier = Modifier.testTag("ary_playlist_chip_all"),
                onClick = { onSelect(null) },
            )
        }
        items(playlists, key = { it.id }) { p ->
            GlassPill(
                text = p.title,
                lit = selectedId == p.id,
                small = true,
                modifier = Modifier.testTag("ary_playlist_chip_${p.id}"),
                onClick = { onSelect(if (selectedId == p.id) null else p.id) },
            )
        }
    }
}

@Composable
private fun EmptyAryState(
    assistantName: String,
    focusedPlaylist: LocalPlaylist?,
    hasPlaylists: Boolean,
    currentSongTitle: String?,
    onSuggestion: (String) -> Unit,
) {
    val suggestions = remember(focusedPlaylist?.id, hasPlaylists, currentSongTitle) {
        buildList {
            if (focusedPlaylist != null) {
                add("Add 3 chill songs to ${focusedPlaylist.title}")
                if (focusedPlaylist.count > 0) {
                    add("Remove the last song from ${focusedPlaylist.title}")
                    add("Suggest 2 similar tracks for ${focusedPlaylist.title}")
                }
            } else if (hasPlaylists) {
                add("Add Blinding Lights by The Weeknd to a playlist")
                add("Create a Late Night Drive playlist with 3 songs")
            } else {
                add("Create a Chill Mix playlist with 3 songs")
                add("Make a Workout playlist with high-energy tracks")
            }
            if (!currentSongTitle.isNullOrBlank()) {
                add(
                    if (focusedPlaylist != null) "Add this song to ${focusedPlaylist.title}"
                    else "Add this song to my playlist",
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AryAvatar(
            size = 96.dp,
            contentDescription = "$assistantName avatar",
            modifier = Modifier.testTag("ary_avatar_hero"),
        )
        Spacer(Modifier.height(16.dp))
        Txt("With $assistantName", Type.Monument, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Txt(
            text = "Ask me to add songs, remove tracks, or start a new playlist in your library.",
            style = Type.Aside,
            color = Ink.Grey,
            align = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(28.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.forEachIndexed { i, prompt ->
                GlassPill(
                    text = prompt,
                    icon = Glyph.Sparkle,
                    small = true,
                    modifier = Modifier.testTag("ary_suggestion_$i"),
                    onClick = { onSuggestion(prompt) },
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .liquidGlass(Corners.Large, GlassWeight.Key)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Txt(text, Type.Body, color = Ink.White)
        }
    }
}

@Composable
private fun AssistantTurn(
    assistantName: String,
    message: AryMessage.Assistant,
    onPlayTrack: (app.orbitmusic.core.model.Track) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AryAvatar(size = 20.dp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Txt(assistantName, Type.Label, color = Ink.Grey, upper = true)
        }
        Spacer(Modifier.height(8.dp))
        Txt(message.reply, Type.Body, color = Ink.White)
        if (message.changes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GlassPanel {
                message.changes.forEachIndexed { index, change ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
                    ChangeRow(
                        change = change,
                        modifier = Modifier.testTag("ary_change_item_$index"),
                        onClick = {
                            when {
                                change.kind == AryChangeKind.ADDED && change.track != null -> onPlayTrack(change.track)
                                change.playlistId != null -> onOpenPlaylist(change.playlistId)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(
    change: AryChange,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val clickable = (change.kind == AryChangeKind.ADDED && change.track != null) || change.playlistId != null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.quietClick(onClick) else Modifier)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (change.track != null) {
            TreatedArtwork(
                change.track.artwork,
                ArtTreatment.Cover,
                Modifier.size(38.dp).clip(Corners.Small),
                sizeHintPx = 120,
            )
        } else {
            val glyph = when (change.kind) {
                AryChangeKind.CREATED_PLAYLIST -> Glyph.Library
                AryChangeKind.ADDED -> Glyph.Plus
                AryChangeKind.REMOVED -> Glyph.Trash
                AryChangeKind.NOT_FOUND -> Glyph.Close
            }
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(glyph, size = 16.dp, color = if (change.kind == AryChangeKind.NOT_FOUND) Ink.Dim else Ink.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(
                text = change.summary,
                style = Type.TitleSmall,
                color = if (change.kind == AryChangeKind.NOT_FOUND) Ink.Grey else Ink.White,
                maxLines = 2,
            )
            Txt(
                text = change.playlistTitle,
                style = Type.Meta,
                color = Ink.Grey,
                maxLines = 1,
            )
        }
        val badgeGlyph = when (change.kind) {
            AryChangeKind.CREATED_PLAYLIST -> Glyph.Plus
            AryChangeKind.ADDED -> Glyph.Check
            AryChangeKind.REMOVED -> Glyph.Close
            AryChangeKind.NOT_FOUND -> null
        }
        if (badgeGlyph != null) {
            Spacer(Modifier.width(8.dp))
            Icon(badgeGlyph, size = 14.dp, color = Ink.Grey)
        }
    }
}

@Composable
private fun LoadingTurn(assistantName: String, status: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridDots(size = 16.dp)
        Spacer(Modifier.width(12.dp))
        Txt(status.ifBlank { "$assistantName is thinking…" }, Type.Meta, color = Ink.Grey)
    }
}

@Composable
private fun ErrorTurn(message: String, onRetry: () -> Unit) {
    GlassPanel {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Txt("Couldn\u2019t complete request", Type.TitleSmall, color = Ink.White)
            Spacer(Modifier.height(4.dp))
            Txt(message, Type.Meta, color = Ink.Grey)
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = "Try again",
                lit = true,
                small = true,
                modifier = Modifier.testTag("ary_retry_button"),
                onClick = onRetry,
            )
        }
    }
}

@Composable
private fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val liquid = rememberLiquidState()
    val canSend = value.isNotBlank() && !sending

    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(horizontal = Space.edge, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(56.dp)
                .liquidGlass(Corners.Pill, GlassWeight.Control, liquid)
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Txt(placeholder, Type.TitleSmall, color = Ink.Dim, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    maxLines = 3,
                    textStyle = Type.TitleSmall.copy(
                        color = Ink.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.02.em,
                        fontFeatureSettings = null,
                    ),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ary_input_field"),
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassCircle(
                glyph = Glyph.Forward,
                label = "Send message",
                onClick = { if (canSend) onSend() },
                size = 48.dp,
                iconSize = 16.dp,
                lit = canSend,
                iconModifier = Modifier.graphicsLayer { rotationZ = -90f },
                modifier = Modifier
                    .testTag("ary_send_button")
                    .semantics {
                        role = Role.Button
                        contentDescription = "Send message"
                    }
                    .graphicsLayer { alpha = if (canSend) 1f else 0.42f },
            )
        }
    }
}

@Composable
private fun AryAvatar(
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(id = R.drawable.img_ary_avatar),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(0.75.dp, Ink.Hairline, CircleShape),
    )
}
