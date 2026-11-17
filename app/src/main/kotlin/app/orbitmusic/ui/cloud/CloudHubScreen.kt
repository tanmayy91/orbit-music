@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.orbitmusic.ui.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.orbitmusic.cloud.CloudValidation
import app.orbitmusic.cloud.CollabPlaylist
import app.orbitmusic.cloud.Friendship
import app.orbitmusic.cloud.ListeningCardSnapshot
import app.orbitmusic.cloud.UiState
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.core.designsystem.component.GlassButton
import app.orbitmusic.core.designsystem.component.GlassPanel
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.GlassSegments
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.GridDots
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.Notice
import app.orbitmusic.core.designsystem.component.PanelDivider
import app.orbitmusic.core.designsystem.component.PillToggle
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
import app.orbitmusic.core.model.songCount
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.Route
import app.orbitmusic.ui.account.DiscordSignInActivity
import app.orbitmusic.ui.common.Colophon
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.NameDialog
import app.orbitmusic.ui.common.rememberPageListState
import app.orbitmusic.ui.detail.BackLine
import app.orbitmusic.ui.profile.ProfileStats
import app.orbitmusic.ui.profile.StatsWindow
import app.orbitmusic.ui.profile.mended
import app.orbitmusic.ui.profile.profileStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun CloudHubScreen() {
    val container = LocalContainer.current
    val currentUser by container.cloudAuth.authState.collectAsStateWithLifecycle(container.cloudAuth.currentUser)

    val user = currentUser
    if (user == null) {
        CloudSignInGate()
    } else {
        key(user.uid) {
            CloudSignedInHub(
                uid = user.uid,
                suggestedName = user.displayName ?: container.settings.profileName.value.orEmpty(),
            )
        }
    }
}

@Composable
private fun CloudSignInGate() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discordUser by container.discordStore.user.collectAsStateWithLifecycle()
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = rememberPageListState(),
        contentPadding = PaddingValues(bottom = 0.dp),
    ) {
        item { BackLine() }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.edge, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = 600.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Glyph.Profile, size = 34.dp, color = Ink.White)
                        Spacer(Modifier.height(14.dp))
                        Txt("Cloud & Friends", Type.Monument, align = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Txt(
                            "Optional cloud sync for Orbit Music. Listening offline and locally never requires an account.",
                            Type.Aside,
                            color = Ink.Grey,
                            align = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        GlassPanel {
                            FeatureBullet(
                                glyph = Glyph.Library,
                                title = "Collaborative playlists",
                                detail = "Create shared playlists, invite friends by code, and add songs together in real time.",
                            )
                            PanelDivider()
                            FeatureBullet(
                                glyph = Glyph.Sparkle,
                                title = "Ary conversation sync",
                                detail = "Keep your playlist assistant history across app sessions when turned on.",
                            )
                            PanelDivider()
                            FeatureBullet(
                                glyph = Glyph.Share,
                                title = "Friends & listening cards",
                                detail = "Find listeners by Orbit @handle or linked Discord account and share taste snapshots.",
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        GlassButton(
                            text = if (signingIn) "Signing in…" else "Sign in with Google",
                            lit = true,
                            modifier = Modifier.fillMaxWidth().testTag("cloud_google_signin_button"),
                            onClick = {
                                if (signingIn) return@GlassButton
                                signingIn = true
                                signInError = null
                                scope.launch {
                                    val res = container.cloudAuth.signInWithGoogle(context)
                                    signingIn = false
                                    res.onFailure { e ->
                                        signInError = e.localizedMessage ?: "Sign-in did not complete."
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Txt(
                            "Uses your Google account to secure Firebase sync. You can link Discord right after signing in.",
                            Type.Meta,
                            color = Ink.Grey,
                            align = TextAlign.Center,
                        )
                        if (discordUser != null) {
                            Spacer(Modifier.height(8.dp))
                            Strapline("Discord ready: @${discordUser!!.username}")
                        }
                        signInError?.let { err ->
                            Spacer(Modifier.height(14.dp))
                            GlassPanel {
                                Txt(
                                    err,
                                    Type.Meta,
                                    color = Ink.White,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        GlassPill(
                            text = "Discord Rich Presence",
                            icon = Glyph.Forward,
                            small = true,
                            modifier = Modifier.testTag("cloud_gate_discord_rpc_button"),
                            onClick = { actions.nav.open(Route.Discord) },
                        )
                    }
                }
            }
        }
        item { Colophon() }
    }
}

@Composable
private fun CloudSignedInHub(
    uid: String,
    suggestedName: String,
) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: CloudViewModel = viewModel(
        key = "cloud:$uid",
        factory = CloudViewModel.Factory(container.cloud, uid),
    )

    val profileState by vm.profileState.collectAsStateWithLifecycle()
    val friendshipsState by vm.friendshipsState.collectAsStateWithLifecycle()
    val cardsState by vm.cardsState.collectAsStateWithLifecycle()
    val playlistsState by vm.playlistsState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val discordUser by container.discordStore.user.collectAsStateWithLifecycle()

    val consent by container.settings.artistArt.collectAsStateWithLifecycle()
    val stats by remember {
        container.library.history(StatsWindow).map { profileStats(mended(it, container)) }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(null)
    val artists = stats?.artists?.take(12).orEmpty()
    val topGenre by produceState<String?>(null, artists.map { it.name to it.plays }, consent) {
        if (!consent || artists.isEmpty()) {
            value = null
            return@produceState
        }
        val weights = LinkedHashMap<String, Int>()
        artists.forEach { a -> container.artistArt.genreOf(a.name)?.let { g -> weights[g] = (weights[g] ?: 0) + a.plays } }
        value = weights.maxByOrNull { it.value }?.key
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showJoinPlaylist by remember { mutableStateOf(false) }
    var renamingProfile by remember { mutableStateOf(false) }
    var openCard by remember { mutableStateOf<ListeningCardSnapshot?>(null) }

    val currentProfile = (profileState as? UiState.Success)?.data
    val friendships = (friendshipsState as? UiState.Success)?.data.orEmpty()
    val acceptedFriendUids = remember(friendships, uid) {
        friendships.filter { it.accepted }.map { it.otherUid(uid) }
    }

    LaunchedEffect(acceptedFriendUids) {
        if (acceptedFriendUids.isNotEmpty()) {
            vm.syncFriendCards(acceptedFriendUids)
        }
    }

    // When listener returns from DiscordSignInActivity, sync Discord link onto registered Orbit profile
    LifecycleResumeEffect(currentProfile?.uid, discordUser?.id, discordUser?.username) {
        val prof = currentProfile
        val dc = discordUser
        if (prof != null && dc != null && (prof.discordId != dc.id || prof.discordUsername != CloudValidation.normalizeDiscordKey(dc.username))) {
            vm.registerOrUpdateProfile(
                displayName = prof.displayName,
                rawHandle = prof.handle,
                discordUser = dc,
                arySyncEnabled = prof.arySyncEnabled,
                cardShared = prof.cardShared,
                successMessage = "Linked Discord @${dc.username}.",
            )
        }
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberPageListState(),
            contentPadding = PaddingValues(bottom = 0.dp),
        ) {
            item { BackLine() }
            when (val pState = profileState) {
                UiState.Loading -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GridDots(size = 24.dp)
                        Spacer(Modifier.height(12.dp))
                        Txt("Opening your Orbit account…", Type.Meta, color = Ink.Grey)
                    }
                }
                is UiState.Error -> item {
                    Notice(
                        title = "Couldn\u2019t load account",
                        body = pState.message,
                        action = "Sign out",
                        onAction = { scope.launch { container.cloudAuth.signOut() } },
                    )
                }
                is UiState.Success -> {
                    val profile = pState.data
                    if (profile == null) {
                        item(key = "register") {
                            CloudRegistrationPane(
                                suggestedName = suggestedName.ifBlank { "Listener" },
                                discordUser = discordUser,
                                busy = busy,
                                feedback = feedback,
                                onLinkDiscord = { context.startActivity(DiscordSignInActivity.intent(context)) },
                                onSubmit = { name, handle, arySync ->
                                    vm.registerOrUpdateProfile(
                                        displayName = name,
                                        rawHandle = handle,
                                        discordUser = discordUser,
                                        arySyncEnabled = arySync,
                                        cardShared = false,
                                        successMessage = "Welcome to Orbit Cloud, @$handle.",
                                    )
                                },
                                onSignOut = { scope.launch { container.cloudAuth.signOut() } },
                            )
                        }
                    } else {
                        item(key = "header") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Space.edge, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Txt(profile.displayName, Type.Monument, align = TextAlign.Center, maxLines = 2)
                                Spacer(Modifier.height(4.dp))
                                Strapline(
                                    buildString {
                                        append("@${profile.handle}")
                                        if (!profile.discordUsername.isNullOrBlank()) {
                                            append("  ·  Discord @${profile.discordUsername}")
                                        }
                                    },
                                    color = Ink.White,
                                )
                                feedback?.let { (isError, msg) ->
                                    Spacer(Modifier.height(12.dp))
                                    GlassPanel {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .quietClick { vm.clearFeedback() }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Txt(
                                                msg,
                                                Type.Meta,
                                                color = if (isError) Ink.White else Ink.Grey,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(Glyph.Close, size = 14.dp, color = Ink.Dim)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                                GlassSegments(
                                    options = listOf("Playlists", "Friends", "Account"),
                                    selected = selectedTab,
                                    onSelect = { selectedTab = it },
                                    modifier = Modifier.testTag("cloud_hub_segments"),
                                )
                                Spacer(Modifier.height(14.dp))
                            }
                        }

                        when (selectedTab) {
                            0 -> item(key = "tab_playlists") {
                                CollabPlaylistsTab(
                                    playlistsState = playlistsState,
                                    onCreate = { showCreatePlaylist = true },
                                    onJoin = { showJoinPlaylist = true },
                                    onOpen = { actions.nav.open(Route.CollabPlaylist(it.id)) },
                                    onCopyCode = { code ->
                                        copyInviteCode(context, code)
                                        actions.keep.note("Copied code $code")
                                    },
                                )
                            }
                            1 -> item(key = "tab_friends") {
                                val cards = (cardsState as? UiState.Success)?.data.orEmpty()
                                FriendsAndCardsContent(
                                    profile = profile,
                                    friendships = friendships,
                                    cards = cards,
                                    stats = stats,
                                    topGenre = topGenre,
                                    busy = busy,
                                    vm = vm,
                                    onOpenCard = { openCard = it },
                                )
                            }
                            else -> item(key = "tab_account") {
                                CloudAccountTab(
                                    profile = profile,
                                    discordUser = discordUser,
                                    busy = busy,
                                    onEditName = { renamingProfile = true },
                                    onToggleArySync = { vm.setArySyncEnabled(!profile.arySyncEnabled) },
                                    onOpenAry = { actions.go(Route.Ary()) },
                                    onLinkDiscord = { context.startActivity(DiscordSignInActivity.intent(context)) },
                                    onUnlinkDiscord = {
                                        container.discordStore.signOut()
                                        vm.registerOrUpdateProfile(
                                            displayName = profile.displayName,
                                            rawHandle = profile.handle,
                                            discordUser = null,
                                            arySyncEnabled = profile.arySyncEnabled,
                                            cardShared = profile.cardShared,
                                            successMessage = "Unlinked Discord from Orbit profile.",
                                        )
                                    },
                                    onOpenDiscordRpc = { actions.nav.open(Route.Discord) },
                                    onSignOut = { scope.launch { container.cloudAuth.signOut() } },
                                )
                            }
                        }
                    }
                }
            }
            item { Colophon() }
        }

        if (showCreatePlaylist && currentProfile != null) {
            CreateCollabPlaylistDialog(
                busy = busy,
                onDismiss = { showCreatePlaylist = false },
                onCreate = { title, desc ->
                    vm.createCollabPlaylist(currentProfile, title, desc) { created ->
                        showCreatePlaylist = false
                        actions.nav.open(Route.CollabPlaylist(created.id))
                    }
                },
            )
        }

        if (showJoinPlaylist && currentProfile != null) {
            JoinCollabPlaylistDialog(
                busy = busy,
                onDismiss = { showJoinPlaylist = false },
                onJoin = { code ->
                    vm.joinCollabPlaylist(currentProfile, code) { playlistId ->
                        showJoinPlaylist = false
                        actions.nav.open(Route.CollabPlaylist(playlistId))
                    }
                },
            )
        }

        if (renamingProfile && currentProfile != null) {
            NameDialog(
                title = "Display name",
                initial = currentProfile.displayName,
                onDone = { newName ->
                    renamingProfile = false
                    if (newName.isNotBlank()) {
                        vm.registerOrUpdateProfile(
                            displayName = newName,
                            rawHandle = currentProfile.handle,
                            discordUser = discordUser,
                            arySyncEnabled = currentProfile.arySyncEnabled,
                            cardShared = currentProfile.cardShared,
                            successMessage = "Updated display name.",
                        )
                    }
                },
                onDismiss = { renamingProfile = false },
            )
        }

        openCard?.let { card ->
            SharedCardModal(card = card, onDismiss = { openCard = null })
        }
    }
}

@Composable
private fun CloudRegistrationPane(
    suggestedName: String,
    discordUser: DiscordUser?,
    busy: Boolean,
    feedback: Pair<Boolean, String>?,
    onLinkDiscord: () -> Unit,
    onSubmit: (displayName: String, handle: String, arySync: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val defaultHandle = remember(discordUser?.username, suggestedName) {
        CloudValidation.sanitizeHandleSuggestion(discordUser?.username ?: suggestedName)
    }
    var displayName by rememberSaveable { mutableStateOf(discordUser?.displayName ?: suggestedName) }
    var handle by rememberSaveable(defaultHandle) { mutableStateOf(defaultHandle) }
    var arySync by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt("Register profile", Type.Monument, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Txt(
            "Choose your Orbit @handle so friends can find you and share playlists.",
            Type.Aside,
            color = Ink.Grey,
            align = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        GlassPanel {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Strapline("Display name")
                Spacer(Modifier.height(6.dp))
                InlineGlassInput(
                    value = displayName,
                    onValueChange = { displayName = it },
                    placeholder = "Your name",
                    modifier = Modifier.testTag("cloud_register_name_input"),
                )
            }
            PanelDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Strapline("Orbit @handle (3–30 a–z, 0–9, _)")
                Spacer(Modifier.height(6.dp))
                InlineGlassInput(
                    value = handle,
                    onValueChange = { handle = it },
                    placeholder = "orbit_listener",
                    prefix = "@",
                    modifier = Modifier.testTag("cloud_register_handle_input"),
                )
            }
            PanelDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Discord link", Type.TitleSmall)
                    Txt(
                        discordUser?.let { "Linked @${it.username}" } ?: "Optional: let friends find you by Discord username",
                        Type.Meta,
                        color = Ink.Grey,
                    )
                }
                GlassPill(
                    text = if (discordUser == null) "Link Discord" else "Linked",
                    small = true,
                    lit = discordUser != null,
                    modifier = Modifier.testTag("cloud_register_discord_button"),
                    onClick = onLinkDiscord,
                )
            }
            PanelDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Sync Ary conversations", Type.TitleSmall)
                    Txt("Keep playlist chat history across sessions", Type.Meta, color = Ink.Grey)
                }
                PillToggle(
                    on = arySync,
                    onToggle = { arySync = !arySync },
                    label = "Sync Ary conversations",
                    modifier = Modifier.testTag("cloud_register_ary_toggle"),
                )
            }
        }
        feedback?.let { (_, msg) ->
            Spacer(Modifier.height(12.dp))
            Txt(msg, Type.Meta, color = Ink.White, align = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        GlassButton(
            text = if (busy) "Creating profile…" else "Complete registration",
            lit = true,
            modifier = Modifier.fillMaxWidth().testTag("cloud_register_submit_button"),
            onClick = { if (!busy) onSubmit(displayName, handle, arySync) },
        )
        Spacer(Modifier.height(14.dp))
        GlassPill(
            text = "Use a different Google account",
            small = true,
            modifier = Modifier.testTag("cloud_register_signout_button"),
            onClick = onSignOut,
        )
    }
}

@Composable
private fun CollabPlaylistsTab(
    playlistsState: UiState<List<CollabPlaylist>>,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpen: (CollabPlaylist) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(
                text = "Create",
                lit = true,
                modifier = Modifier.weight(1f).testTag("cloud_create_collab_button"),
                onClick = onCreate,
            )
            GlassButton(
                text = "Join by code",
                modifier = Modifier.weight(1f).testTag("cloud_join_collab_button"),
                onClick = onJoin,
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionHeading("Your collaborative playlists", strapline = "Live sync with collaborators")
        Spacer(Modifier.height(14.dp))
        when (playlistsState) {
            UiState.Loading -> GridDots(Modifier.padding(24.dp), size = 20.dp)
            is UiState.Error -> Notice("Couldn\u2019t load playlists", playlistsState.message)
            is UiState.Success -> {
                val lists = playlistsState.data
                if (lists.isEmpty()) {
                    Notice(
                        "No collaborative playlists yet",
                        "Create a shared playlist or enter an 8-character invite code from a friend.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        lists.forEach { playlist ->
                            GlassPanel {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .quietClick { onOpen(playlist) }
                                        .testTag("cloud_collab_item_${playlist.id}")
                                        .padding(vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Txt(playlist.title, Type.TitleSmall, maxLines = 1)
                                        Spacer(Modifier.height(3.dp))
                                        val count = maxOf(playlist.trackCount, playlist.trackDocIds.size)
                                        val members = playlist.memberUids.size.coerceAtLeast(1)
                                        Txt(
                                            "${songCount(count)} · $members ${if (members == 1) "member" else "members"}",
                                            Type.Meta,
                                            color = Ink.Grey,
                                            maxLines = 1,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    GlassPill(
                                        text = playlist.inviteCode,
                                        icon = Glyph.Share,
                                        small = true,
                                        modifier = Modifier.testTag("cloud_collab_copy_code_${playlist.id}"),
                                        onClick = { onCopyCode(playlist.inviteCode) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudAccountTab(
    profile: UserProfile,
    discordUser: DiscordUser?,
    busy: Boolean,
    onEditName: () -> Unit,
    onToggleArySync: () -> Unit,
    onOpenAry: () -> Unit,
    onLinkDiscord: () -> Unit,
    onUnlinkDiscord: () -> Unit,
    onOpenDiscordRpc: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeading("Orbit identity", strapline = "Profile & synced services")
        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Row(
                Modifier
                    .fillMaxWidth()
                    .quietClick(onEditName)
                    .testTag("cloud_account_edit_name_row")
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Display name", Type.TitleSmall)
                    Txt("${profile.displayName}  ·  @${profile.handle}", Type.Meta, color = Ink.Grey)
                }
                Icon(Glyph.Edit, size = 16.dp, color = Ink.Grey)
            }
            PanelDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Ary conversation sync", Type.TitleSmall)
                    Txt(
                        if (profile.arySyncEnabled) "Synced across sessions" else "Paused (local-only mode)",
                        Type.Meta,
                        color = Ink.Grey,
                    )
                }
                PillToggle(
                    on = profile.arySyncEnabled,
                    onToggle = { if (!busy) onToggleArySync() },
                    label = "Ary conversation sync",
                    modifier = Modifier.testTag("cloud_ary_sync_toggle"),
                )
            }
            PanelDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Discord account", Type.TitleSmall)
                    Txt(
                        discordUser?.let { "Linked as @${it.username}" }
                            ?: profile.discordUsername?.let { "Linked as @$it" }
                            ?: "Optional: let friends add you by Discord handle",
                        Type.Meta,
                        color = Ink.Grey,
                    )
                }
                if (discordUser == null && profile.discordUsername == null) {
                    GlassPill(
                        text = "Link",
                        small = true,
                        lit = true,
                        modifier = Modifier.testTag("cloud_account_link_discord_button"),
                        onClick = onLinkDiscord,
                    )
                } else {
                    GlassPill(
                        text = "Unlink",
                        small = true,
                        modifier = Modifier.testTag("cloud_account_unlink_discord_button"),
                        onClick = { if (!busy) onUnlinkDiscord() },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(
                text = "Open Ary",
                icon = Glyph.Sparkle,
                small = true,
                lit = true,
                modifier = Modifier.testTag("cloud_account_open_ary_button"),
                onClick = onOpenAry,
            )
            GlassPill(
                text = "Discord RPC",
                icon = Glyph.Forward,
                small = true,
                modifier = Modifier.testTag("cloud_account_open_rpc_button"),
                onClick = onOpenDiscordRpc,
            )
        }
        Spacer(Modifier.height(24.dp))
        GlassPill(
            text = "Sign out of Cloud",
            modifier = Modifier.testTag("cloud_account_signout_button"),
            onClick = onSignOut,
        )
    }
}

@Composable
private fun FeatureBullet(glyph: Glyph, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, size = 18.dp, color = Ink.White)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Type.TitleSmall)
            Spacer(Modifier.height(2.dp))
            Txt(detail, Type.Meta, color = Ink.Grey)
        }
    }
}

@Composable
private fun InlineGlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (prefix != null) {
            Txt(prefix, Type.TitleSmall, color = Ink.Grey)
            Spacer(Modifier.width(2.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(placeholder, Type.TitleSmall, color = Ink.Dim, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.TitleSmall.copy(
                    color = Ink.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.02.em,
                    fontFeatureSettings = null,
                ),
                cursorBrush = SolidColor(Ink.White),
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CreateCollabPlaylistDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt("New shared playlist", Type.Headline, align = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (title.isEmpty()) Txt("Playlist title", Type.Title.copy(color = Ink.Dim), align = TextAlign.Center)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = Type.Title.copy(color = Ink.White, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("create_collab_title_input"),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(0.5.dp).background(Ink.Hairline))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (description.isEmpty()) Txt("Note (optional)", Type.Aside.copy(color = Ink.Dim), align = TextAlign.Center)
                BasicTextField(
                    value = description,
                    onValueChange = { description = it },
                    singleLine = true,
                    textStyle = Type.Aside.copy(color = Ink.White, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (!busy && title.isNotBlank()) onCreate(title, description) },
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("create_collab_desc_input"),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("Cancel", onClick = onDismiss)
                GlassButton(
                    text = if (busy) "Creating…" else "Create",
                    lit = title.isNotBlank(),
                    modifier = Modifier.testTag("create_collab_confirm_button"),
                    onClick = { if (!busy && title.isNotBlank()) onCreate(title, description) },
                )
            }
        }
    }
}

@Composable
private fun JoinCollabPlaylistDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onJoin: (code: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt("Join playlist", Type.Headline, align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Txt("Enter the invite code shared by the playlist creator.", Type.Meta, color = Ink.Grey, align = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (code.isEmpty()) Txt("8-character code", Type.Title.copy(color = Ink.Dim), align = TextAlign.Center)
                BasicTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(12) },
                    singleLine = true,
                    textStyle = Type.Title.copy(color = Ink.White, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (!busy && code.isNotBlank()) onJoin(code) },
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("join_collab_code_input"),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.75f).height(0.5.dp).background(Ink.Hairline))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("Cancel", onClick = onDismiss)
                GlassButton(
                    text = if (busy) "Joining…" else "Join",
                    lit = code.isNotBlank(),
                    modifier = Modifier.testTag("join_collab_confirm_button"),
                    onClick = { if (!busy && code.isNotBlank()) onJoin(code) },
                )
            }
        }
    }
}

internal fun copyInviteCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Orbit Playlist Invite Code", code))
}
