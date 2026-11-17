package app.orbitmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbitmusic.core.designsystem.art.ArtTreatment
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.GlassSegments
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.Icon
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.component.quietClick
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Space
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.core.designsystem.theme.separate
import app.orbitmusic.core.model.songCount
import app.orbitmusic.core.model.songsIncluded
import app.orbitmusic.discord.DiscordConnectionState
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.Route
import app.orbitmusic.ui.account.SignInActivity
import app.orbitmusic.ui.common.Colophon
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.rememberPageListState
import app.orbitmusic.ui.detail.BackLine
import kotlinx.coroutines.delay

/**
 * Settings directory: organized into focused sub-pages so every setting sits in its own room
 * rather than crowding one long page.
 */
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val actions = LocalActions.current
    val context = LocalContext.current

    val session by container.account.cookies.collectAsStateWithLifecycle()
    val discordConfig by container.discordStore.config.collectAsStateWithLifecycle()
    val discordUser by container.discordStore.user.collectAsStateWithLifecycle()
    val discordConnection by container.discordRpc.connectionState.collectAsStateWithLifecycle()

    val simpleGlass by settings.simpleGlass.collectAsStateWithLifecycle()
    val lyricsStyle by settings.lyricsStyle.collectAsStateWithLifecycle()
    val artwork by settings.artwork.collectAsStateWithLifecycle()
    val efficient by settings.efficient.collectAsStateWithLifecycle()
    val crossfade by settings.crossfade.collectAsStateWithLifecycle()
    val eqShaping by container.equaliser.shaping.collectAsStateWithLifecycle()
    val addresses by settings.addons.collectAsStateWithLifecycle()
    val lyricsFolders by settings.lyricsFolders.collectAsStateWithLifecycle()
    val phoneFolders by container.local.folders.collectAsStateWithLifecycle()
    val phoneChoice by container.local.selection.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (container.local.hasPermission() && phoneFolders == null) container.local.refresh()
    }

    val lyricsStyleName = when (lyricsStyle) {
        2 -> "Verse"
        1 -> "Line"
        3 -> "Apple Music"
        else -> "Constellations"
    }
    val artworkName = listOf("Original", "Mono", "Halftone", "ASCII", "Dither")
        .getOrElse(artwork.mod(ArtTreatment.Cycle.size)) { "Original" }

    val appearanceSummary = "${if (simpleGlass) "Simple" else "Liquid"} glass · $lyricsStyleName lyrics · $artworkName cover"
    val playbackSummary = buildString {
        append(if (efficient) "Data saver" else "Best quality")
        append(" · ")
        append(if (crossfade == 0) "Crossfade off" else "Crossfade ${crossfade}s")
        append(" · ")
        append(eqShaping?.let { listOfNotNull(it.group, it.name).joinToString(" ") } ?: "EQ off")
    }
    val localSummary = buildList {
        phoneFolders?.let { f -> add("${songCount(songsIncluded(f, phoneChoice))} from chosen folders") }
            ?: add("Music folders")
        if (lyricsFolders.isNotEmpty()) add("${lyricsFolders.size} lyrics folder${if (lyricsFolders.size == 1) "" else "s"}")
    }.joinToString(" · ")
    val sourcesSummary = if (addresses.isEmpty()) {
        "No external sources added"
    } else {
        "${addresses.size} source${if (addresses.size == 1) "" else "s"} added"
    }

    val discordSummary = when (val conn = discordConnection) {
        is DiscordConnectionState.Connected -> "${discordUser?.handle ?: "Connected"} · ${conn.summary}"
        is DiscordConnectionState.Idle -> "${discordUser?.handle ?: "Signed in"} · Ready"
        is DiscordConnectionState.Error -> conn.message
        DiscordConnectionState.Connecting -> "Connecting to Discord…"
        DiscordConnectionState.SignedOut -> "Connect your account for Kizzy Rich Presence"
        DiscordConnectionState.Disabled ->
            if (discordUser != null) "${discordUser?.handle} · Off"
            else "Off · Tap to set up Rich Presence"
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Settings",
                subtitle = "How Orbit Music looks, how it sounds, and what it keeps.",
            )
        }
        item {
            SettingsSection("I", "Accounts") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsWords(
                        "YouTube Music",
                        if (session != null) "Signed in. Home, your library and mixes are your own."
                        else "Sign in on Google's page for your Home, library and mixes.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    if (session != null) {
                        GlassPill(
                            "Sign out",
                            small = true,
                            modifier = Modifier.testTag("settings_ytm_signout_button"),
                            onClick = {
                                container.account.signOut()
                                container.catalog.clear()
                            },
                        )
                    } else {
                        GlassPill(
                            "Sign in",
                            small = true,
                            lit = true,
                            modifier = Modifier.testTag("settings_ytm_signin_button"),
                            onClick = { context.startActivity(SignInActivity.intent(context)) },
                        )
                    }
                }
                SettingsRule()
                SettingsNavRow(
                    title = "Discord (Kizzy RPC)",
                    detail = discordSummary,
                    badge = if (discordConfig.enabled) "On" else null,
                    testTag = "settings_discord_row",
                    onClick = { actions.nav.open(Route.Discord) },
                )
            }
        }
        item {
            SettingsSection("II", "Customize") {
                SettingsNavRow(
                    title = "Appearance",
                    detail = appearanceSummary,
                    testTag = "settings_appearance_row",
                    onClick = { actions.nav.open(Route.SettingsAppearance) },
                )
                SettingsNavRow(
                    title = "Playback & Sound",
                    detail = playbackSummary,
                    testTag = "settings_playback_row",
                    onClick = { actions.nav.open(Route.SettingsPlayback) },
                )
            }
        }
        item {
            SettingsSection("III", "Library & Sources") {
                SettingsNavRow(
                    title = "Local music & lyrics",
                    detail = localSummary,
                    testTag = "settings_library_row",
                    onClick = { actions.nav.open(Route.SettingsLibrary) },
                )
                SettingsNavRow(
                    title = "Custom sources",
                    detail = sourcesSummary,
                    testTag = "settings_sources_row",
                    onClick = { actions.nav.open(Route.SettingsSources) },
                )
            }
        }
        item {
            SettingsSection("IV", "System & Info") {
                SettingsNavRow(
                    title = "Data & updates",
                    detail = "Clear listening history or recent searches, and check for updates.",
                    testTag = "settings_system_row",
                    onClick = { actions.nav.open(Route.SettingsSystem) },
                )
                SettingsNavRow(
                    title = "Privacy & about",
                    detail = "Orbit Music ${container.updates.version} · Crash notes, privacy, and credits.",
                    testTag = "settings_about_row",
                    onClick = { actions.nav.open(Route.SettingsAbout) },
                )
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
internal fun SettingsHeader(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge + 12.dp)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(title, Type.Monument, align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Txt(subtitle, Type.Aside, color = Ink.Grey, align = TextAlign.Center)
    }
}

@Composable
internal fun SettingsSection(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .separate()
            .fillMaxWidth()
            .padding(horizontal = Space.edge)
            .padding(top = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(number, Type.Aside.copy(fontSize = 15.sp, lineHeight = 18.sp), color = Ink.Dim)
            Spacer(Modifier.width(12.dp))
            Txt(title, Type.Label, color = Ink.Grey, upper = true)
        }
        Spacer(Modifier.height(12.dp))
        SettingsRule()
        content()
    }
}

@Composable
internal fun SettingsNavRow(
    title: String,
    detail: String?,
    testTag: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
            .semantics { role = Role.Button }
            .quietClick(onClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsWords(title, detail, Modifier.weight(1f))
        if (badge != null) {
            Spacer(Modifier.width(12.dp))
            Txt(badge, Type.Label, color = Ink.White, upper = true)
        }
        Spacer(Modifier.width(14.dp))
        Icon(Glyph.Forward, size = 14.dp, color = Ink.Dim)
    }
    SettingsRule()
}

@Composable
internal fun SettingsRule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

@Composable
internal fun SettingsWords(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Txt(title, Type.TitleSmall)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Txt(detail, Type.Meta, color = Ink.Grey)
        }
    }
}

@Composable
internal fun SettingsToggle(
    title: String,
    detail: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = false) { role = Role.Switch; stateDescription = if (on) "On" else "Off" }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsWords(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassSegments(listOf("Off", "On"), if (on) 1 else 0, { if ((it == 1) != on) onToggle() }, modifier = modifier)
    }
    SettingsRule()
}

@Composable
internal fun SettingsChoice(
    title: String,
    detail: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        SettingsWords(title, detail)
        Spacer(Modifier.height(16.dp))
        GlassSegments(options, selected, onSelect, modifier = modifier)
    }
    SettingsRule()
}

@Composable
internal fun SettingsErase(title: String, detail: String, testTag: String = "", onErase: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3500); armed = false } }
    LaunchedEffect(done) { if (done) { delay(2200); done = false } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsWords(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassPill(
            when { done -> "Cleared"; armed -> "Touch again"; else -> "Clear" },
            small = true,
            lit = armed,
            icon = if (done) Glyph.Check else null,
            modifier = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier,
            onClick = { if (armed) { onErase(); armed = false; done = true } else if (!done) armed = true },
        )
    }
    SettingsRule()
}

@Composable
internal fun SettingsParagraph(text: String, top: Dp = 16.dp) {
    Txt(text, Type.Body.copy(lineHeight = 21.sp), color = Ink.Grey, modifier = Modifier.padding(top = top, bottom = 4.dp))
}
