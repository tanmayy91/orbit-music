package app.orbitmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbitmusic.core.data.DiscordActivityType
import app.orbitmusic.core.data.DiscordConfig
import app.orbitmusic.core.data.DiscordOnlineStatus
import app.orbitmusic.core.data.DiscordStatusDisplay
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.core.designsystem.component.GlassPanel
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.component.quietClick
import app.orbitmusic.core.designsystem.theme.Corners
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Space
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.discord.DiscordConnectionState
import app.orbitmusic.discord.DiscordRpcFormatter
import app.orbitmusic.discord.DiscordRpcTrackContext
import app.orbitmusic.ui.account.DiscordSignInActivity
import app.orbitmusic.ui.common.Colophon
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.NameDialog
import app.orbitmusic.ui.common.rememberPageListState
import app.orbitmusic.ui.detail.BackLine
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DiscordField {
    TOKEN,
    ACTIVITY_NAME,
    APP_ID,
}

@Composable
fun DiscordSettingsScreen() {
    val container = LocalContainer.current
    val store = container.discordStore
    val rpc = container.discordRpc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val config by store.config.collectAsStateWithLifecycle()
    val token by store.token.collectAsStateWithLifecycle()
    val user by store.user.collectAsStateWithLifecycle()
    val connection by rpc.connectionState.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()

    var editingField by remember { mutableStateOf<DiscordField?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var verifyingToken by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    val previewTrack = remember(playback.current, playback.status, playback.durationMs, playback.positionMs) {
        DiscordRpcTrackContext.fromPlayback(playback, playback.positionMs) ?: DiscordRpcTrackContext.sample()
    }
    val previewPresence = remember(config, previewTrack) {
        DiscordRpcFormatter.resolve(config, previewTrack, ignorePausedGate = true)
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt("Discord", Type.Monument, align = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Txt("Kizzy Rich Presence, clean and ready.", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            }
        }

        item {
            Spacer(Modifier.height(28.dp))
            Box(Modifier.padding(horizontal = Space.edge)) {
                DiscordPreviewCard(
                    config = config,
                    presence = previewPresence,
                    user = user,
                    connection = connection,
                    isSampleTrack = playback.current == null,
                )
            }
        }

        item {
            SettingsSection("I", "Connection") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsWords(
                        title = user?.displayName ?: "Discord account",
                        detail = connectionDescription(token != null, user, connection, tokenError),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    if (token == null) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill(
                                "Sign in",
                                small = true,
                                lit = true,
                                modifier = Modifier.testTag("discord_sign_in_button"),
                                onClick = {
                                    tokenError = null
                                    context.startActivity(DiscordSignInActivity.intent(context))
                                },
                            )
                            GlassPill(
                                if (verifyingToken) "Checking…" else "Paste token",
                                small = true,
                                modifier = Modifier.testTag("discord_paste_token_button"),
                                onClick = { if (!verifyingToken) editingField = DiscordField.TOKEN },
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill(
                                "Sign out",
                                small = true,
                                modifier = Modifier.testTag("discord_sign_out_button"),
                                onClick = {
                                    tokenError = null
                                    rpc.signOut()
                                },
                            )
                            GlassPill(
                                if (verifyingToken) "Checking…" else "Replace token",
                                small = true,
                                modifier = Modifier.testTag("discord_replace_token_button"),
                                onClick = { if (!verifyingToken) editingField = DiscordField.TOKEN },
                            )
                        }
                    }
                }
                SettingsRule()
                SettingsToggle(
                    title = "Enable Rich Presence",
                    detail = "Share what you are listening to on your Discord profile.",
                    on = config.enabled,
                    modifier = Modifier.testTag("discord_enabled_toggle"),
                    onToggle = { store.setEnabled(!config.enabled) },
                )
            }
        }

        item {
            SettingsSection("II", "Presence") {
                SettingsChoice(
                    title = "Activity type",
                    detail = "How Discord labels your session.",
                    options = DiscordActivityType.entries.map { it.label },
                    selected = DiscordActivityType.entries.indexOf(config.activityType).coerceAtLeast(0),
                    modifier = Modifier.testTag("discord_activity_type_choice"),
                    onSelect = { idx -> store.update { it.copy(activityType = DiscordActivityType.entries[idx]) } },
                )
                if (config.activityType == DiscordActivityType.LISTENING) {
                    SettingsChoice(
                        title = "Profile display line",
                        detail = "What shows next to your name in the member list.",
                        options = DiscordStatusDisplay.entries.map { it.label },
                        selected = DiscordStatusDisplay.entries.indexOf(config.statusDisplayType).coerceAtLeast(0),
                        modifier = Modifier.testTag("discord_status_display_choice"),
                        onSelect = { idx -> store.update { it.copy(statusDisplayType = DiscordStatusDisplay.entries[idx]) } },
                    )
                }
                SettingsChoice(
                    title = "Online status",
                    detail = "Your availability dot while connected.",
                    options = DiscordOnlineStatus.entries.map { it.label },
                    selected = DiscordOnlineStatus.entries.indexOf(config.onlineStatus).coerceAtLeast(0),
                    modifier = Modifier.testTag("discord_online_status_choice"),
                    onSelect = { idx -> store.update { it.copy(onlineStatus = DiscordOnlineStatus.entries[idx]) } },
                )
                SettingsToggle(
                    title = "Show track artwork",
                    detail = "Display the song's cover art on your Discord card.",
                    on = config.showArtwork,
                    modifier = Modifier.testTag("discord_show_artwork_toggle"),
                    onToggle = { store.update { it.copy(showArtwork = !it.showArtwork) } },
                )
                SettingsToggle(
                    title = "Show timestamps",
                    detail = "Display the live progress timer.",
                    on = config.showTimestamps,
                    modifier = Modifier.testTag("discord_show_timestamps_toggle"),
                    onToggle = { store.update { it.copy(showTimestamps = !it.showTimestamps) } },
                )
                SettingsToggle(
                    title = "Keep when paused",
                    detail = "Keep the card visible while music is paused.",
                    on = config.showWhenPaused,
                    modifier = Modifier.testTag("discord_show_paused_toggle"),
                    onToggle = { store.update { it.copy(showWhenPaused = !it.showWhenPaused) } },
                )
                SettingsToggle(
                    title = "YouTube Music button",
                    detail = "Include a Listen on YouTube Music link button on your card.",
                    on = config.showButtons,
                    modifier = Modifier.testTag("discord_show_buttons_toggle"),
                    onToggle = { store.update { it.copy(showButtons = !it.showButtons) } },
                )
            }
        }

        item {
            SettingsSection("III", "Advanced") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("discord_advanced_toggle")
                        .quietClick { showAdvanced = !showAdvanced }
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsWords(
                        title = "Advanced options",
                        detail = if (showAdvanced) "Hide custom app name, Application ID, and reset." else "Custom app name, Application ID, and reset defaults.",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(if (showAdvanced) Glyph.Down else Glyph.Forward, size = 14.dp, color = Ink.Dim)
                }
                SettingsRule()
                AnimatedVisibility(visible = showAdvanced) {
                    Column(Modifier.fillMaxWidth()) {
                        DiscordEditRow(
                            title = "Activity name",
                            value = config.activityName,
                            testTag = "discord_edit_activity_name",
                            onEdit = { editingField = DiscordField.ACTIVITY_NAME },
                        )
                        DiscordEditRow(
                            title = "Application ID",
                            value = config.applicationId,
                            testTag = "discord_edit_app_id",
                            onEdit = { editingField = DiscordField.APP_ID },
                        )
                        DiscordResetRow(onReset = { store.resetDefaults() })
                    }
                }
            }
        }

        item(key = "colophon") { Colophon() }
    }

    editingField?.let { field ->
        val (title, initial) = when (field) {
            DiscordField.TOKEN -> "Discord Token" to ""
            DiscordField.ACTIVITY_NAME -> "Activity Name" to config.activityName
            DiscordField.APP_ID -> "Discord Application ID" to config.applicationId
        }
        NameDialog(
            title = title,
            initial = initial,
            onDismiss = { editingField = null },
            onDone = { input ->
                editingField = null
                val trimmed = input.trim()
                when (field) {
                    DiscordField.TOKEN -> {
                        if (trimmed.isNotEmpty()) {
                            verifyingToken = true
                            tokenError = null
                            scope.launch {
                                val res = rpc.verifyAndSaveToken(trimmed)
                                verifyingToken = false
                                tokenError = res.exceptionOrNull()?.message
                            }
                        }
                    }
                    DiscordField.ACTIVITY_NAME -> store.update {
                        it.copy(activityName = trimmed.ifBlank { DiscordConfig.DEFAULT_ACTIVITY_NAME })
                    }
                    DiscordField.APP_ID -> store.update {
                        it.copy(applicationId = trimmed.ifBlank { DiscordConfig.DEFAULT_APPLICATION_ID })
                    }
                }
            },
        )
    }
}

private fun connectionDescription(
    hasToken: Boolean,
    user: DiscordUser?,
    connection: DiscordConnectionState,
    tokenError: String?,
): String {
    if (!tokenError.isNullOrBlank()) return tokenError
    return when (connection) {
        is DiscordConnectionState.Error -> connection.message
        is DiscordConnectionState.Connected -> "${user?.handle ?: "Signed in"} · Live: ${connection.summary}"
        is DiscordConnectionState.Idle -> "${user?.handle ?: "Signed in"} · Waiting for playback"
        DiscordConnectionState.Connecting -> "${user?.handle ?: "Signed in"} · Connecting…"
        DiscordConnectionState.SignedOut -> "Sign in with Discord or paste a token to enable RPC."
        DiscordConnectionState.Disabled -> if (hasToken) "${user?.handle ?: "Signed in"} · Rich Presence is off." else "Sign in with Discord or paste a token to begin."
    }
}

@Composable
private fun DiscordPreviewCard(
    config: DiscordConfig,
    presence: app.orbitmusic.discord.ResolvedDiscordPresence?,
    user: DiscordUser?,
    connection: DiscordConnectionState,
    isSampleTrack: Boolean,
) {
    GlassPanel {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(config.onlineStatus, enabled = config.enabled && connection !is DiscordConnectionState.Error)
                Spacer(Modifier.width(8.dp))
                val verbPrefix = when (config.activityType) {
                    DiscordActivityType.LISTENING -> "Listening to"
                    DiscordActivityType.PLAYING -> "Playing"
                    DiscordActivityType.WATCHING -> "Watching"
                    DiscordActivityType.COMPETING -> "Competing in"
                }
                Txt(
                    "$verbPrefix ${presence?.activityName ?: config.activityName}".uppercase(),
                    Type.Label,
                    color = Ink.Grey,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Txt(
                    if (isSampleTrack) "PREVIEW" else "NOW",
                    Type.Label,
                    color = Ink.Dim,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(Corners.Medium)
                        .background(Ink.White.copy(alpha = 0.08f))
                        .border(0.5.dp, Ink.Hairline, Corners.Medium),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = presence?.largeImageRaw
                    if (!img.isNullOrBlank() && img.startsWith("http")) {
                        AsyncImage(
                            model = img,
                            contentDescription = "Preview cover art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Glyph.Listen, size = 24.dp, color = Ink.Grey)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(
                        presence?.details ?: "Nothing playing",
                        Type.TitleSmall,
                        maxLines = 1,
                    )
                    presence?.state?.let {
                        Spacer(Modifier.height(2.dp))
                        Txt(it, Type.Meta, color = Ink.Grey, maxLines = 1)
                    }
                    presence?.largeText?.let {
                        Spacer(Modifier.height(2.dp))
                        Txt(it, Type.Meta, color = Ink.Dim, maxLines = 1)
                    }
                    if (config.showTimestamps) {
                        Spacer(Modifier.height(4.dp))
                        Txt("01:14 / 04:03", Type.Label, color = Ink.Dim)
                    }
                }
            }
            if (presence != null && presence.buttons.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presence.buttons.forEach { (label, _) ->
                        Box(
                            Modifier
                                .clip(Corners.Small)
                                .border(0.5.dp, Ink.Hairline, Corners.Small)
                                .background(Ink.White.copy(alpha = 0.05f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Txt(label, Type.Label, color = Ink.White, maxLines = 1)
                        }
                    }
                }
            }
            if (user != null) {
                Spacer(Modifier.height(10.dp))
                Txt("Account: ${user.displayName} (${user.handle})", Type.Meta, color = Ink.Dim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusDot(status: DiscordOnlineStatus, enabled: Boolean) {
    val dotColor = if (!enabled) Ink.Dim else when (status) {
        DiscordOnlineStatus.ONLINE -> Color(0xFF23A55A)
        DiscordOnlineStatus.IDLE -> Color(0xFFF0B232)
        DiscordOnlineStatus.DND -> Color(0xFFF23F43)
        DiscordOnlineStatus.INVISIBLE -> Ink.Grey
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
}

@Composable
private fun DiscordEditRow(
    title: String,
    value: String,
    testTag: String,
    onEdit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsWords(title, value.ifBlank { "Empty" }, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassPill(
            "Edit",
            small = true,
            modifier = Modifier.testTag(testTag),
            onClick = onEdit,
        )
    }
    SettingsRule()
}

@Composable
private fun DiscordResetRow(onReset: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3500); armed = false } }
    LaunchedEffect(done) { if (done) { delay(2200); done = false } }
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsWords("Reset RPC defaults", "Restores default presence settings and Application ID.", Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassPill(
            when {
                done -> "Reset"
                armed -> "Touch again"
                else -> "Reset"
            },
            small = true,
            lit = armed,
            icon = if (done) Glyph.Check else null,
            modifier = Modifier.testTag("discord_reset_button"),
            onClick = {
                if (armed) {
                    onReset()
                    armed = false
                    done = true
                } else if (!done) {
                    armed = true
                }
            },
        )
    }
    SettingsRule()
}
