package app.orbitmusic.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orbitmusic.cloud.Friendship
import app.orbitmusic.cloud.ListeningCardSnapshot
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.core.designsystem.component.GlassButton
import app.orbitmusic.core.designsystem.component.GlassPanel
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.Notice
import app.orbitmusic.core.designsystem.component.PanelDivider
import app.orbitmusic.core.designsystem.component.SectionHeading
import app.orbitmusic.core.designsystem.component.Strapline
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.component.quietClick
import app.orbitmusic.core.designsystem.glass.GlassWeight
import app.orbitmusic.core.designsystem.glass.liquidGlass
import app.orbitmusic.core.designsystem.theme.Corners
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Space
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.Track
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.profile.ProfileStats
import kotlinx.coroutines.launch

@Composable
internal fun FriendsAndCardsContent(
    profile: UserProfile,
    friendships: List<Friendship>,
    cards: List<ListeningCardSnapshot>,
    stats: ProfileStats?,
    topGenre: String?,
    busy: Boolean,
    vm: CloudViewModel,
    onOpenCard: (ListeningCardSnapshot) -> Unit,
) {
    val myUid = profile.uid
    val accepted = remember(friendships) { friendships.filter { it.accepted } }
    val incoming = remember(friendships, myUid) { friendships.filter { !it.accepted && it.recipientUid == myUid } }
    val outgoing = remember(friendships, myUid) { friendships.filter { !it.accepted && it.requesterUid == myUid } }
    val friendUids = remember(accepted, myUid) { accepted.map { it.otherUid(myUid) } }
    val myCard = remember(cards, myUid) { cards.firstOrNull { it.uid == myUid } }
    val friendCards = remember(cards, myUid) { cards.filter { it.uid != myUid } }
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        SectionHeading("Your listening card", strapline = "Share stats & top music with friends")
        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                if (myCard != null) {
                    Txt("${myCard.persona} · ${myCard.hoursLabel}", Type.TitleSmall)
                    Spacer(Modifier.height(4.dp))
                    Txt(
                        buildString {
                            append("${myCard.plays} plays")
                            if (!myCard.topSongTitle.isNullOrBlank()) {
                                append(" · Top: ${myCard.topSongTitle}")
                            }
                            if (!profile.discordUsername.isNullOrBlank()) {
                                append(" · Discord @${profile.discordUsername}")
                            }
                        },
                        Type.Meta,
                        color = Ink.Grey,
                    )
                } else {
                    Txt("Not shared yet", Type.TitleSmall)
                    Spacer(Modifier.height(4.dp))
                    Txt(
                        if (stats != null && !stats.isEmpty) {
                            "Publish your ${stats.persona.lowercase()} listening card so friends can see what you're spinning."
                        } else {
                            "Listen to a song first, then publish your listening card for friends."
                        },
                        Type.Meta,
                        color = Ink.Grey,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canShare = stats != null && !stats.isEmpty && !busy
                    GlassPill(
                        text = if (myCard != null) "Refresh card" else "Share my card",
                        icon = Glyph.Share,
                        lit = true,
                        small = true,
                        modifier = Modifier.testTag("cloud_share_card_button"),
                        onClick = {
                            if (canShare) {
                                vm.publishListeningCard(profile, stats!!, topGenre, friendUids)
                            }
                        },
                    )
                    if (myCard != null) {
                        GlassPill(
                            text = "View",
                            small = true,
                            modifier = Modifier.testTag("cloud_view_my_card_button"),
                            onClick = { onOpenCard(myCard) },
                        )
                        GlassPill(
                            text = "Hide",
                            small = true,
                            modifier = Modifier.testTag("cloud_hide_card_button"),
                            onClick = { if (!busy) vm.unpublishListeningCard() },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeading("Add a friend", strapline = "Orbit @handle or linked Discord username")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .liquidGlass(Corners.Pill, GlassWeight.Key)
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyph.Profile, size = 16.dp, color = Ink.Grey)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Txt("@handle or Discord username", Type.TitleSmall, color = Ink.Dim, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Type.TitleSmall.copy(
                        color = Ink.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.02.em,
                        fontFeatureSettings = null,
                    ),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!busy && query.isNotBlank()) {
                                focusManager.clearFocus()
                                vm.sendFriendRequest(profile, query) { query = "" }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("cloud_friend_search_input"),
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassPill(
                text = "Send",
                small = true,
                lit = query.isNotBlank(),
                modifier = Modifier.testTag("cloud_send_friend_request_button"),
                onClick = {
                    if (!busy && query.isNotBlank()) {
                        focusManager.clearFocus()
                        vm.sendFriendRequest(profile, query) { query = "" }
                    }
                },
            )
        }

        if (incoming.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionHeading("Incoming requests", strapline = "${incoming.size} waiting")
            Spacer(Modifier.height(12.dp))
            GlassPanel {
                incoming.forEachIndexed { index, f ->
                    if (index > 0) PanelDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(f.requesterName, Type.TitleSmall, maxLines = 1)
                            Txt("@${f.requesterHandle}", Type.Meta, color = Ink.Grey, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            text = "Accept",
                            small = true,
                            lit = true,
                            modifier = Modifier.testTag("cloud_accept_friend_${f.id}"),
                            onClick = { if (!busy) vm.acceptFriendRequest(f) },
                        )
                        Spacer(Modifier.width(6.dp))
                        GlassPill(
                            text = "Decline",
                            small = true,
                            modifier = Modifier.testTag("cloud_decline_friend_${f.id}"),
                            onClick = { if (!busy) vm.removeFriendship(f.id) },
                        )
                    }
                }
            }
        }

        if (outgoing.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeading("Sent requests", strapline = "Waiting for reply")
            Spacer(Modifier.height(12.dp))
            GlassPanel {
                outgoing.forEachIndexed { index, f ->
                    if (index > 0) PanelDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(f.recipientName, Type.TitleSmall, maxLines = 1)
                            Txt("@${f.recipientHandle}", Type.Meta, color = Ink.Grey, maxLines = 1)
                        }
                        GlassPill(
                            text = "Cancel",
                            small = true,
                            modifier = Modifier.testTag("cloud_cancel_friend_${f.id}"),
                            onClick = { if (!busy) vm.removeFriendship(f.id) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeading("Shared listening cards", strapline = "Friends on Orbit")
        Spacer(Modifier.height(12.dp))
        if (friendCards.isEmpty()) {
            Notice(
                "No friend cards yet",
                if (accepted.isEmpty()) "Add a friend above to trade listening cards and taste stats."
                else "When your friends share their listening card, it appears here.",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                friendCards.forEach { card ->
                    GlassPanel {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .quietClick { onOpenCard(card) }
                                .testTag("cloud_friend_card_${card.uid}")
                                .padding(vertical = 12.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Txt(card.ownerName, Type.TitleSmall, maxLines = 1)
                                    val sub = buildString {
                                        append("@${card.handle}")
                                        if (!card.discordUsername.isNullOrBlank()) {
                                            append(" · Discord @${card.discordUsername}")
                                        }
                                    }
                                    Txt(sub, Type.Meta, color = Ink.Grey, maxLines = 1)
                                }
                                Strapline(card.persona)
                            }
                            Spacer(Modifier.height(8.dp))
                            val detail = buildString {
                                append("${card.hoursLabel} · ${card.plays} plays · ${card.artistCount} artists")
                                if (!card.topSongTitle.isNullOrBlank()) {
                                    append("\nTop song: ${card.topSongTitle}")
                                    if (!card.topSongArtist.isNullOrBlank()) append(" — ${card.topSongArtist}")
                                }
                            }
                            Txt(detail, Type.Aside, color = Ink.Grey, maxLines = 3)
                        }
                    }
                }
            }
        }

        if (accepted.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionHeading("Friends", strapline = "${accepted.size} connected")
            Spacer(Modifier.height(12.dp))
            GlassPanel {
                accepted.forEachIndexed { index, f ->
                    if (index > 0) PanelDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(f.otherName(myUid), Type.TitleSmall, maxLines = 1)
                            Txt("@${f.otherHandle(myUid)}", Type.Meta, color = Ink.Grey, maxLines = 1)
                        }
                        GlassPill(
                            text = "Remove",
                            small = true,
                            modifier = Modifier.testTag("cloud_remove_friend_${f.id}"),
                            onClick = { if (!busy) vm.removeFriendship(f.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SharedCardModal(
    card: ListeningCardSnapshot,
    onDismiss: () -> Unit,
) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var playingSong by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 26.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Strapline(card.persona, color = Ink.White)
            Spacer(Modifier.height(8.dp))
            Txt(card.ownerName, Type.Display, align = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Txt(
                buildString {
                    append("@${card.handle}")
                    if (!card.discordUsername.isNullOrBlank()) append("  ·  Discord @${card.discordUsername}")
                },
                Type.Meta,
                color = Ink.Grey,
                align = TextAlign.Center,
            )
            card.sinceLabel?.let {
                Spacer(Modifier.height(4.dp))
                Txt("Listening since $it", Type.Meta, color = Ink.Dim, align = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CardStatItem(card.hoursLabel, "Time")
                CardStatItem("${card.plays}", "Plays")
                CardStatItem("${card.artistCount}", "Artists")
                CardStatItem("${card.bestStreak}d", "Best streak")
            }
            card.topGenre?.let { genre ->
                Spacer(Modifier.height(16.dp))
                Txt("Top sound: $genre", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            }
            if (card.topArtists.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                GlassPanel {
                    card.topArtists.forEachIndexed { i, artist ->
                        if (i > 0) PanelDivider()
                        Txt(artist, Type.TitleSmall, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1)
                    }
                }
            }
            if (!card.topSongTitle.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Txt(
                    "Top song: ${card.topSongTitle}" + (card.topSongArtist?.let { " — $it" } ?: ""),
                    Type.Aside,
                    color = Ink.White,
                    align = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill(
                    text = "Close",
                    modifier = Modifier.testTag("shared_card_close_button"),
                    onClick = onDismiss,
                )
                if (!card.topSongTitle.isNullOrBlank()) {
                    GlassButton(
                        text = if (playingSong) "Finding…" else "Play top song",
                        lit = true,
                        modifier = Modifier.testTag("shared_card_play_top_button"),
                        onClick = {
                            if (playingSong) return@GlassButton
                            playingSong = true
                            scope.launch {
                                val query = listOfNotNull(card.topSongTitle, card.topSongArtist).joinToString(" - ")
                                val res = container.sources.primary.search(query)
                                val track = (res as? Outcome.Ok)?.value?.items?.filterIsInstance<Track>()?.firstOrNull()
                                playingSong = false
                                if (track != null) {
                                    actions.playRadio(track)
                                    onDismiss()
                                } else {
                                    actions.keep.note("Couldn\u2019t find \u201c${card.topSongTitle}\u201d to play")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CardStatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(value, Type.Headline, align = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Txt(label, Type.Label, color = Ink.Grey, upper = true, align = TextAlign.Center)
    }
}
