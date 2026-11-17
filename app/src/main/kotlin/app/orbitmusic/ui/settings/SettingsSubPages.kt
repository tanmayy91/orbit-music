package app.orbitmusic.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbitmusic.CrashNote
import app.orbitmusic.core.designsystem.art.ArtTreatment
import app.orbitmusic.core.designsystem.component.GlassPill
import app.orbitmusic.core.designsystem.component.GlassSegments
import app.orbitmusic.core.designsystem.component.Glyph
import app.orbitmusic.core.designsystem.component.Txt
import app.orbitmusic.core.designsystem.theme.Ink
import app.orbitmusic.core.designsystem.theme.Type
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.SourceError
import app.orbitmusic.core.model.songCount
import app.orbitmusic.core.model.songsIncluded
import app.orbitmusic.output.CodecCompanion
import app.orbitmusic.output.LosslessOutput
import app.orbitmusic.player.CrossfadeRules
import app.orbitmusic.sources.addon.AddonManifest
import app.orbitmusic.sources.addon.AddonSetting
import app.orbitmusic.sources.addon.AddonSource
import app.orbitmusic.ui.LocalActions
import app.orbitmusic.ui.Route
import app.orbitmusic.ui.common.Colophon
import app.orbitmusic.ui.common.LocalContainer
import app.orbitmusic.ui.common.NameDialog
import app.orbitmusic.ui.common.rememberPageListState
import app.orbitmusic.ui.detail.BackLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun AppearanceSettingsScreen() {
    val settings = LocalContainer.current.settings
    val navAutoFold by settings.navAutoFold.collectAsStateWithLifecycle()
    val navShowLabels by settings.navShowLabels.collectAsStateWithLifecycle()
    val fullScreen by settings.fullScreen.collectAsStateWithLifecycle()
    val simpleGlass by settings.simpleGlass.collectAsStateWithLifecycle()
    val lyricsStyle by settings.lyricsStyle.collectAsStateWithLifecycle()
    val lyricsAnimations by settings.lyricsAnimations.collectAsStateWithLifecycle()
    val artwork by settings.artwork.collectAsStateWithLifecycle()
    val haptics by settings.haptics.collectAsStateWithLifecycle()
    val reduce by settings.reduceMotion.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Appearance",
                subtitle = "Navigation, glass, cover treatment, lyrics style, and motion.",
            )
        }
        item {
            SettingsSection("I", "Navigation & Screen") {
                SettingsToggle(
                    "Fold navigation bar",
                    "The bottom bar folds into a compact circle while scrolling down a page.",
                    navAutoFold,
                    Modifier.testTag("settings_nav_autofold_toggle"),
                ) { settings.setNavAutoFold(!navAutoFold) }
                SettingsToggle(
                    "Navigation bar labels",
                    "Show room names beneath each icon in the bottom navigation bar.",
                    navShowLabels,
                    Modifier.testTag("settings_nav_labels_toggle"),
                ) { settings.setNavShowLabels(!navShowLabels) }
                SettingsToggle(
                    "Full screen",
                    "Hide Android's system bars so pages use the whole screen. Swipe from an edge to peek.",
                    fullScreen,
                    Modifier.testTag("settings_fullscreen_toggle"),
                ) { settings.setFullScreen(!fullScreen) }
            }
        }
        item {
            SettingsSection("II", "Glass & Cover") {
                SettingsChoice(
                    title = "Glass",
                    detail = "Simple is lighter frosted glass; Liquid refracts light at every edge live.",
                    options = listOf("Simple", "Liquid"),
                    selected = if (simpleGlass) 0 else 1,
                    onSelect = { settings.setSimpleGlass(it == 0) },
                    modifier = Modifier.testTag("settings_glass_choice"),
                )
                SettingsChoice(
                    title = "The cover",
                    detail = "How the album print is treated on Now Playing.",
                    options = listOf("Original", "Mono", "Halftone", "ASCII", "Dither"),
                    selected = artwork.mod(ArtTreatment.Cycle.size),
                    onSelect = { settings.setArtwork(it) },
                    modifier = Modifier.testTag("settings_cover_choice"),
                )
            }
        }
        item {
            SettingsSection("III", "Lyrics Display") {
                SettingsChoice(
                    title = "How lyrics are drawn",
                    detail = "Constellations, editorial Verse, handwritten Line, or Apple Music live word glow.",
                    options = listOf("Constellations", "Verse", "Line", "Apple Music"),
                    selected = when (lyricsStyle) { 2 -> 1; 1 -> 2; 3 -> 3; else -> 0 },
                    onSelect = { settings.setLyricsStyle(when (it) { 1 -> 2; 2 -> 1; 3 -> 3; else -> 0 }) },
                    modifier = Modifier.testTag("settings_lyrics_style_choice"),
                )
                SettingsToggle(
                    "Lyrics animations",
                    "Animate word sweeps, ambient cover glow, depth blur, and spring transitions while playing.",
                    lyricsAnimations,
                    Modifier.testTag("settings_lyrics_animations_toggle"),
                ) { settings.setLyricsAnimations(!lyricsAnimations) }
            }
        }
        item {
            SettingsSection("IV", "Feel") {
                SettingsToggle(
                    "Haptics",
                    "A light touch you can feel on the actions that matter.",
                    haptics,
                    Modifier.testTag("settings_haptics_toggle"),
                ) { settings.setHaptics(!haptics) }
                SettingsToggle(
                    "Reduce motion",
                    "No stretch, ripples, or drifting. Also follows your phone's setting.",
                    reduce,
                    Modifier.testTag("settings_reduce_motion_toggle"),
                ) { settings.setReduceMotion(!reduce) }
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val actions = LocalActions.current

    val efficient by settings.efficient.collectAsStateWithLifecycle()
    val crossfade by settings.crossfade.collectAsStateWithLifecycle()
    val eqShaping by container.equaliser.shaping.collectAsStateWithLifecycle()
    val timedLyrics by settings.timedLyrics.collectAsStateWithLifecycle()
    val sourcesOff by settings.lyricsSourcesOff.collectAsStateWithLifecycle()
    val romanise by settings.romanise.collectAsStateWithLifecycle()
    val songVideo by settings.songVideo.collectAsStateWithLifecycle()
    val artistArt by settings.artistArt.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Playback & Sound",
                subtitle = "Audio quality, crossfade, equaliser, lyrics sources, and visual media.",
            )
        }
        item {
            SettingsSection("I", "Sound") {
                SettingsToggle(
                    "Data saver",
                    "Lower-bitrate streams for metered networks. Off plays the best the source has.",
                    efficient,
                    Modifier.testTag("settings_data_saver_toggle"),
                ) { settings.setEfficient(!efficient) }
                SettingsChoice(
                    title = "Crossfade",
                    detail = "Overlap between songs in seconds. Skipped for gapless album tracks and bit-perfect DAC output.",
                    options = CrossfadeRules.Choices.map { if (it == 0) "Off" else "$it" },
                    selected = CrossfadeRules.Choices.indexOf(crossfade).coerceAtLeast(0),
                    onSelect = { settings.setCrossfade(CrossfadeRules.Choices[it]) },
                    modifier = Modifier.testTag("settings_crossfade_choice"),
                )
                SettingsNavRow(
                    title = "Equaliser",
                    detail = eqShaping?.let { listOfNotNull(it.group, it.name).joinToString(" ") }
                        ?: "Off. Tunings, measured headphone corrections, or custom profiles.",
                    testTag = "settings_equaliser_row",
                    onClick = { actions.nav.open(Route.Equaliser) },
                )
            }
        }
        item {
            SettingsSection("II", "Now Playing & Artists") {
                SettingsToggle(
                    "Song video",
                    "Play cover films and music videos in place of cover art when available.",
                    songVideo,
                    Modifier.testTag("settings_song_video_toggle"),
                ) { settings.setSongVideo(!songVideo) }
                SettingsToggle(
                    "Artist and album art",
                    "Load artist portraits, lettering, moving covers, and genres from Apple Music's public pages.",
                    artistArt,
                    Modifier.testTag("settings_artist_art_toggle"),
                ) { settings.setArtistArt(!artistArt) }
                SettingsToggle(
                    "Timed lyrics",
                    "Fetch word-by-word or line-timed lyrics from the enabled libraries below.",
                    timedLyrics,
                    Modifier.testTag("settings_timed_lyrics_toggle"),
                ) { settings.setTimedLyrics(!timedLyrics) }
                if (timedLyrics) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                        Txt("Libraries", Type.Label, color = Ink.Grey, upper = true)
                        Spacer(Modifier.height(4.dp))
                        container.lyricsLibraries.forEach { library ->
                            val on = library.id !in sourcesOff
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SettingsWords(library.attribution.name, LibraryNotes[library.id], Modifier.weight(1f))
                                Spacer(Modifier.width(16.dp))
                                GlassSegments(
                                    listOf("Off", "On"),
                                    if (on) 1 else 0,
                                    { if ((it == 1) != on) settings.setLyricsSource(library.id, !on) },
                                )
                            }
                        }
                    }
                    SettingsRule()
                }
                SettingsToggle(
                    "Words in Latin letters",
                    "Romanise Korean, Chinese, Japanese kana, and other scripts locally on your phone.",
                    romanise,
                    Modifier.testTag("settings_romanise_toggle"),
                ) { settings.setRomanise(!romanise) }
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
fun LocalLibrarySettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val phoneFolders by container.local.folders.collectAsStateWithLifecycle()
    val phoneChoice by container.local.selection.collectAsStateWithLifecycle()
    val lyricsFolders by settings.lyricsFolders.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (container.local.hasPermission() && phoneFolders == null) container.local.refresh()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            settings.addLyricsFolder(tree.toString())
            scope.launch { container.localLyrics.forget() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Local Music & Lyrics",
                subtitle = "Folders on this phone that Orbit Music reads for songs and .lrc files.",
            )
        }
        item {
            SettingsSection("I", "Music on this phone") {
                SettingsNavRow(
                    title = "Music folders",
                    detail = phoneFolders?.let { f ->
                        val n = songsIncluded(f, phoneChoice)
                        "${songCount(n)} from the folders you've chosen. Voice notes and other apps' audio stay excluded."
                    } ?: "Choose which folders Orbit Music reads for On this phone.",
                    testTag = "settings_music_folders_row",
                    onClick = { actions.nav.open(Route.MusicFolders()) },
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsWords(
                        "Lyrics folders",
                        if (lyricsFolders.isEmpty()) "Embedded tags are read automatically. Pick a folder to read external .lrc files."
                        else "Reading .lrc files from ${lyricsFolders.joinToString { folderName(it) }}.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill(
                        if (lyricsFolders.isEmpty()) "Choose" else "Add",
                        small = true,
                        modifier = Modifier.testTag("settings_add_lyrics_folder_button"),
                        onClick = { pickFolder.launch(null) },
                    )
                }
                lyricsFolders.forEach { tree ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(folderName(tree), Type.Meta, color = Ink.Grey, modifier = Modifier.weight(1f))
                        GlassPill(
                            "Remove",
                            small = true,
                            onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(tree),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                                settings.removeLyricsFolder(tree)
                                scope.launch { container.localLyrics.forget() }
                            },
                        )
                    }
                }
                SettingsRule()
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
fun SourcesSettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val scope = rememberCoroutineScope()

    val sources by container.sources.added.collectAsStateWithLifecycle()
    val addresses by settings.addons.collectAsStateWithLifecycle()
    val choices by settings.addonChoices.collectAsStateWithLifecycle()
    val output by container.losslessOutput.state.collectAsStateWithLifecycle()

    var adding by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Custom Sources",
                subtitle = "External addon servers asked first for lossless or exact copies.",
            )
        }
        item {
            SettingsSection("I", "Your own sources") {
                SettingsWords(
                    "Open media addons",
                    "Add a source by address speaking the addon protocol. Your sources are asked first for exact recordings; YouTube Music plays anything they don't have.",
                    Modifier.padding(vertical = 18.dp),
                )
                addresses.forEach { address ->
                    val connected = sources.firstOrNull { (it as? AddonSource)?.address == address } as? AddonSource
                    AddedSource(
                        address = address,
                        source = connected,
                        chosen = choices[address].orEmpty(),
                        output = output,
                        onChoose = { key, value -> settings.setAddonChoice(address, key, value) },
                        onRetry = { container.reconnectAddons() },
                        onOutputChanged = { container.losslessOutput.refresh() },
                    ) {
                        settings.removeAddon(address)
                        container.sources.unregister(AddonSource.idFor(address))
                    }
                }
                addError?.let { Txt(it, Type.Meta, color = Ink.Grey, modifier = Modifier.padding(top = 12.dp)) }
                GlassPill(
                    if (connecting) "Asking…" else "Add a source",
                    icon = Glyph.Plus,
                    modifier = Modifier.testTag("settings_add_source_button").padding(vertical = 18.dp),
                    onClick = { if (!connecting) adding = true },
                )
                SettingsRule()
            }
        }
        item(key = "colophon") { Colophon() }
    }

    if (adding) {
        NameDialog(
            title = "Source address",
            initial = "https://",
            onDone = { typed ->
                adding = false
                val address = typed.trim()
                if (address in addresses) {
                    addError = "That source is already in your list."
                    return@NameDialog
                }
                connecting = true
                scope.launch {
                    when (val r = container.connectAddon(address)) {
                        is Outcome.Ok -> {
                            container.sources.register(r.value)
                            settings.addAddon(address)
                            addError = null
                        }
                        is Outcome.Err -> addError = when (val e = r.error) {
                            is SourceError.NotPlayable -> e.reason ?: "That address didn't answer as a source."
                            SourceError.Network, SourceError.Offline -> "That address didn't answer. Check it, and your connection, and try again."
                            is SourceError.Server -> "That address answered, but not as a source${e.code?.let { " (it said $it)" }.orEmpty()}."
                            else -> "That address didn't answer as a source."
                        }
                    }
                    connecting = false
                }
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
fun SystemSettingsScreen() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val updatesAutomatic by container.updates.automatic.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Data & Updates",
                subtitle = "Manage on-device history and Orbit Music releases.",
            )
        }
        item {
            SettingsSection("I", "Your data") {
                SettingsErase(
                    "Listening history",
                    "Everything you've played, gone from this phone.",
                    testTag = "settings_clear_history_button",
                ) { scope.launch { container.library.clearHistory() } }
                SettingsErase(
                    "Recent searches",
                    "What you've looked for on Discover.",
                    testTag = "settings_clear_searches_button",
                ) { scope.launch { container.library.clearSearches() } }
            }
        }
        item {
            SettingsSection("II", "Software update") {
                if (container.updates.looksByItself) {
                    SettingsToggle(
                        "Look for updates",
                        "Checks GitHub periodically on launch for a newer signed release.",
                        updatesAutomatic,
                        Modifier.testTag("settings_auto_update_toggle"),
                    ) { container.updates.setAutomatic(!updatesAutomatic) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsWords(
                        "Orbit Music ${container.updates.version}",
                        if (container.updates.looksByItself) "The version on this phone."
                        else "A test build: checks for updates only when asked, and releases install from GitHub.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill(
                        "Check now",
                        small = true,
                        modifier = Modifier.testTag("settings_check_updates_button"),
                        onClick = { container.updates.checkNow() },
                    )
                }
                SettingsRule()
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
fun AboutSettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashNote.read(context)) }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            SettingsHeader(
                title = "Privacy & About",
                subtitle = "Orbit Music ${container.updates.version}",
            )
        }
        item {
            SettingsSection("I", "Privacy") {
                SettingsParagraph(
                    "Orbit Music has no analytics and no accounts of its own. Your likes, playlists and history stay on this phone. The only requests it makes are the ones that load pages and play music, and, if you leave them on, the ones for timed lyrics, song video, artist and album art, Discord Rich Presence, and a newer Orbit Music.",
                )
            }
        }
        item {
            SettingsSection("II", "About") {
                Spacer(Modifier.height(16.dp))
                Txt("Orbit Music ${container.updates.version}", Type.TitleSmall)
                crash?.let { note ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsWords(
                            "Orbit Music closed by itself",
                            "What went wrong is kept on this phone. Share it if you like; nothing is sent otherwise.",
                            Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill("Share", small = true, onClick = {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, note)
                                context.startActivity(Intent.createChooser(send, "Share what went wrong"))
                            })
                            GlassPill("Clear", small = true, onClick = {
                                CrashNote.clear(context)
                                crash = null
                            })
                        }
                    }
                }
                SettingsParagraph(
                    "Free software under the GNU GPL v3. Built on PODIUM's, LastWave's, BitChord's and NewPipe's work, Media3, Coil and Kyant's Backdrop; set in Pinyon Script, EB Garamond, Montserrat and JetBrains Mono (SIL Open Font License).",
                    top = 6.dp,
                )
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
private fun AddedSource(
    address: String,
    source: AddonSource?,
    chosen: Map<String, String>,
    output: LosslessOutput.State,
    onChoose: (String, String) -> Unit,
    onRetry: () -> Unit,
    onOutputChanged: () -> Unit,
    onRemove: () -> Unit,
) {
    val host = remember(address) { AddonSource.base(address)?.host ?: address }
    val changes = (source?.changes ?: remember { MutableStateFlow(0L) }).collectAsStateWithLifecycle()
    changes.value
    val wantsLossless = source?.manifest?.requiresLosslessOutput == true
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onOutputChanged() }
    val associate = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onOutputChanged() }
    var companionTrouble by remember { mutableStateOf<String?>(null) }
    val nearbyGranted = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val state = when {
            source == null -> "Not reachable just now, at $host. Asked again when the connection's back."
            wantsLossless && !output.capable -> "$host. It asks to be used only with an output that carries lossless audio: wired or USB headphones, HDMI, or Bluetooth on LDAC, LHDC or aptX Lossless. " +
                when {
                    output.bluetoothCodec != null -> "Your headphones are on ${output.bluetoothCodec}, so it waits."
                    output.bluetoothCodecUnknown && !nearbyGranted -> "Android tells Orbit Music your headphones' codec only with Nearby devices."
                    output.needsCompanion -> "Android names your headphones' codec only to an app it knows them by: allow Orbit Music once, and it can tell when they play over LDAC."
                    output.bluetoothCodecUnknown -> "Android hasn't named your headphones' codec yet: it does when they connect."
                    else -> "None is connected, so it waits."
                } + (companionTrouble?.let { " ($it)" }.orEmpty())
            source.trouble != null -> "$host. Resting: ${source.trouble}"
            source.lastError != null -> "$host. Its last answer: \u201c${source.lastError}\u201d"
            wantsLossless -> "$host. Lossless output connected${output.bluetoothCodec?.let { " ($it)" }.orEmpty()}: asked first for exact copies of songs from YouTube Music."
            else -> "$host. Asked first for exact copies of songs from YouTube Music."
        }
        SettingsWords(source?.name ?: "A source", state, Modifier.weight(1f))
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (source == null) {
                GlassPill("Try again", small = true, onClick = onRetry)
                Spacer(Modifier.height(10.dp))
            }
            if (wantsLossless && output.bluetoothCodecUnknown && !nearbyGranted) {
                GlassPill("Nearby devices", small = true, lit = true, onClick = { ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) })
                Spacer(Modifier.height(10.dp))
            } else if (wantsLossless && output.needsCompanion) {
                GlassPill("Allow headphones", small = true, lit = true, onClick = {
                    val btAddress = LosslessOutput.headphonesAddress
                    if (btAddress == null) {
                        companionTrouble = "Android didn't say which headphones are connected."
                    } else {
                        CodecCompanion.ask(
                            context, btAddress,
                            onPending = { sender -> associate.launch(IntentSenderRequest.Builder(sender).build()) },
                            onDone = { companionTrouble = null; onOutputChanged() },
                            onFailed = { why -> companionTrouble = why },
                        )
                    }
                })
                Spacer(Modifier.height(10.dp))
            }
            GlassPill("Remove", small = true, onClick = onRemove)
        }
    }
    source?.manifest?.settings?.filter { it.type != AddonSetting.Type.TEXT }?.forEach { setting ->
        val options = when (setting.type) {
            AddonSetting.Type.SELECT -> setting.options
            else -> listOf(AddonSetting.Option("Off", "false"), AddonSetting.Option("On", "true"))
        }.take(4)
        if (options.size < 2) return@forEach
        val value = chosen[setting.key]?.takeIf { v -> options.any { it.value == v } }
            ?: (if (setting.key == AddonManifest.QUALITY) options.first().value else setting.accept(null))
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Txt(setting.label, Type.Label, color = Ink.Grey, upper = true)
            Spacer(Modifier.height(10.dp))
            GlassSegments(options.map { it.label }, options.indexOfFirst { it.value == value }.coerceAtLeast(0), { i -> onChoose(setting.key, options[i].value) })
        }
    }
    SettingsRule()
}

private fun folderName(tree: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
    id.substringAfter(':').ifBlank { "the phone's storage" }
}.getOrDefault("a folder")

private val LibraryNotes = mapOf(
    "lrcred" to "Apple Music's timings, syllable by syllable, found by the recording.",
    "bini" to "The same timings, matched on the recording itself.",
    "betterlyrics" to "Apple Music's timings, word by word, from the YouTube Music extension's library.",
    "lyricsplus" to "Syllable by syllable from several catalogues, on community mirrors.",
    "unison" to "Lyrics written and voted on by listeners.",
    "lrclib" to "An open library of lyrics, timed by the line.",
    "kugou" to "Timed by the line, and strong outside the English-speaking catalogue.",
)
