package app.orbitmusic.core.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class DiscordActivityType(val code: Int, val label: String) {
    LISTENING(2, "Listening"),
    PLAYING(0, "Playing"),
    WATCHING(3, "Watching"),
    COMPETING(5, "Competing");

    companion object {
        fun fromCode(code: Int): DiscordActivityType = entries.firstOrNull { it.code == code } ?: LISTENING
    }
}

/** Which line Discord displays beside "Listening to …" in a member list (Gateway v10 status_display_type). */
enum class DiscordStatusDisplay(val code: Int, val label: String) {
    NAME(0, "App name"),
    STATE(1, "Artist"),
    DETAILS(2, "Song");

    companion object {
        fun fromCode(code: Int): DiscordStatusDisplay = entries.firstOrNull { it.code == code } ?: DETAILS
    }
}

enum class DiscordOnlineStatus(val wireValue: String, val label: String) {
    ONLINE("online", "Online"),
    IDLE("idle", "Idle"),
    DND("dnd", "DND"),
    INVISIBLE("invisible", "Invisible");

    companion object {
        fun fromWire(value: String?): DiscordOnlineStatus = entries.firstOrNull { it.wireValue == value } ?: ONLINE
    }
}

data class DiscordUser(
    val id: String,
    val username: String,
    val globalName: String? = null,
    val avatarHash: String? = null,
) {
    val displayName: String get() = globalName?.takeIf { it.isNotBlank() } ?: username
    val handle: String get() = "@$username"
    val avatarUrl: String?
        get() = avatarHash?.takeIf { it.isNotBlank() }?.let { "https://cdn.discordapp.com/avatars/$id/$it.png?size=128" }
}

data class DiscordConfig(
    val enabled: Boolean = false,
    val applicationId: String = DEFAULT_APPLICATION_ID,
    val activityType: DiscordActivityType = DiscordActivityType.LISTENING,
    val statusDisplayType: DiscordStatusDisplay = DiscordStatusDisplay.DETAILS,
    val onlineStatus: DiscordOnlineStatus = DiscordOnlineStatus.ONLINE,
    val activityName: String = DEFAULT_ACTIVITY_NAME,
    val detailsTemplate: String = DEFAULT_DETAILS,
    val stateTemplate: String = DEFAULT_STATE,
    val largeImageTextTemplate: String = DEFAULT_LARGE_TEXT,
    val smallImageTextTemplate: String = DEFAULT_SMALL_TEXT,
    val customLargeImageUrl: String = "",
    val customSmallImageUrl: String = "",
    val showArtwork: Boolean = true,
    val showTimestamps: Boolean = true,
    val showSmallIcon: Boolean = true,
    val showWhenPaused: Boolean = false,
    val showButtons: Boolean = true,
    val button1Label: String = DEFAULT_BUTTON1_LABEL,
    val button1Url: String = DEFAULT_BUTTON1_URL,
    val button2Label: String = "",
    val button2Url: String = "",
) {
    companion object {
        const val DEFAULT_APPLICATION_ID = "1205579652398977044"
        const val DEFAULT_ACTIVITY_NAME = "Orbit Music"
        const val DEFAULT_DETAILS = "{song_name}"
        const val DEFAULT_STATE = "{artist_name}"
        const val DEFAULT_LARGE_TEXT = "{album_name}"
        const val DEFAULT_SMALL_TEXT = "{app_name}"
        const val DEFAULT_BUTTON1_LABEL = "Listen on YouTube Music"
        const val DEFAULT_BUTTON1_URL = "{yt_url}"
    }
}

/**
 * Kizzy-style Discord Rich Presence account & customization store.
 * The user's Discord token is sealed with an Android Keystore AES-GCM key at rest and never
 * written to plain preferences.
 */
class DiscordAccountStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("discord_rpc", Context.MODE_PRIVATE)

    private val _token = MutableStateFlow(openToken())
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _user = MutableStateFlow(readUser())
    val user: StateFlow<DiscordUser?> = _user.asStateFlow()

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<DiscordConfig> = _config.asStateFlow()

    fun saveToken(rawToken: String) {
        val clean = rawToken.trim().trim('"')
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_TOKEN_SEALED, seal(clean)).apply()
        _token.value = clean
    }

    fun saveUser(user: DiscordUser?) {
        prefs.edit().apply {
            if (user == null) {
                remove(KEY_USER_ID)
                remove(KEY_USER_NAME)
                remove(KEY_USER_GLOBAL)
                remove(KEY_USER_AVATAR)
            } else {
                putString(KEY_USER_ID, user.id)
                putString(KEY_USER_NAME, user.username)
                putString(KEY_USER_GLOBAL, user.globalName)
                putString(KEY_USER_AVATAR, user.avatarHash)
            }
        }.apply()
        _user.value = user
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_TOKEN_SEALED)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_GLOBAL)
            .remove(KEY_USER_AVATAR)
            .apply()
        _token.value = null
        _user.value = null
    }

    fun update(transform: (DiscordConfig) -> DiscordConfig) {
        val next = transform(_config.value)
        writeConfig(next)
        _config.value = next
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun resetDefaults() {
        val keepEnabled = _config.value.enabled
        val defaults = DiscordConfig(enabled = keepEnabled)
        writeConfig(defaults)
        _config.value = defaults
    }

    private fun readConfig(): DiscordConfig = DiscordConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        applicationId = prefs.getString(KEY_APP_ID, null)?.ifBlank { null } ?: DiscordConfig.DEFAULT_APPLICATION_ID,
        activityType = DiscordActivityType.fromCode(prefs.getInt(KEY_ACTIVITY_TYPE, DiscordActivityType.LISTENING.code)),
        statusDisplayType = DiscordStatusDisplay.fromCode(prefs.getInt(KEY_STATUS_DISPLAY, DiscordStatusDisplay.DETAILS.code)),
        onlineStatus = DiscordOnlineStatus.fromWire(prefs.getString(KEY_ONLINE_STATUS, DiscordOnlineStatus.ONLINE.wireValue)),
        activityName = prefs.getString(KEY_ACTIVITY_NAME, null) ?: DiscordConfig.DEFAULT_ACTIVITY_NAME,
        detailsTemplate = prefs.getString(KEY_DETAILS, null) ?: DiscordConfig.DEFAULT_DETAILS,
        stateTemplate = prefs.getString(KEY_STATE, null) ?: DiscordConfig.DEFAULT_STATE,
        largeImageTextTemplate = prefs.getString(KEY_LARGE_TEXT, null) ?: DiscordConfig.DEFAULT_LARGE_TEXT,
        smallImageTextTemplate = prefs.getString(KEY_SMALL_TEXT, null) ?: DiscordConfig.DEFAULT_SMALL_TEXT,
        customLargeImageUrl = prefs.getString(KEY_CUSTOM_LARGE_URL, null).orEmpty(),
        customSmallImageUrl = prefs.getString(KEY_CUSTOM_SMALL_URL, null).orEmpty(),
        showArtwork = prefs.getBoolean(KEY_SHOW_ARTWORK, true),
        showTimestamps = prefs.getBoolean(KEY_SHOW_TIMESTAMPS, true),
        showSmallIcon = prefs.getBoolean(KEY_SHOW_SMALL_ICON, true),
        showWhenPaused = prefs.getBoolean(KEY_SHOW_PAUSED, false),
        showButtons = prefs.getBoolean(KEY_SHOW_BUTTONS, true),
        button1Label = prefs.getString(KEY_BTN1_LABEL, null) ?: DiscordConfig.DEFAULT_BUTTON1_LABEL,
        button1Url = prefs.getString(KEY_BTN1_URL, null) ?: DiscordConfig.DEFAULT_BUTTON1_URL,
        button2Label = prefs.getString(KEY_BTN2_LABEL, null).orEmpty(),
        button2Url = prefs.getString(KEY_BTN2_URL, null).orEmpty(),
    )

    private fun writeConfig(cfg: DiscordConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_APP_ID, cfg.applicationId.trim().ifBlank { DiscordConfig.DEFAULT_APPLICATION_ID })
            .putInt(KEY_ACTIVITY_TYPE, cfg.activityType.code)
            .putInt(KEY_STATUS_DISPLAY, cfg.statusDisplayType.code)
            .putString(KEY_ONLINE_STATUS, cfg.onlineStatus.wireValue)
            .putString(KEY_ACTIVITY_NAME, cfg.activityName)
            .putString(KEY_DETAILS, cfg.detailsTemplate)
            .putString(KEY_STATE, cfg.stateTemplate)
            .putString(KEY_LARGE_TEXT, cfg.largeImageTextTemplate)
            .putString(KEY_SMALL_TEXT, cfg.smallImageTextTemplate)
            .putString(KEY_CUSTOM_LARGE_URL, cfg.customLargeImageUrl.trim())
            .putString(KEY_CUSTOM_SMALL_URL, cfg.customSmallImageUrl.trim())
            .putBoolean(KEY_SHOW_ARTWORK, cfg.showArtwork)
            .putBoolean(KEY_SHOW_TIMESTAMPS, cfg.showTimestamps)
            .putBoolean(KEY_SHOW_SMALL_ICON, cfg.showSmallIcon)
            .putBoolean(KEY_SHOW_PAUSED, cfg.showWhenPaused)
            .putBoolean(KEY_SHOW_BUTTONS, cfg.showButtons)
            .putString(KEY_BTN1_LABEL, cfg.button1Label)
            .putString(KEY_BTN1_URL, cfg.button1Url.trim())
            .putString(KEY_BTN2_LABEL, cfg.button2Label)
            .putString(KEY_BTN2_URL, cfg.button2Url.trim())
            .apply()
    }

    private fun readUser(): DiscordUser? {
        val id = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val username = prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return DiscordUser(
            id = id,
            username = username,
            globalName = prefs.getString(KEY_USER_GLOBAL, null),
            avatarHash = prefs.getString(KEY_USER_AVATAR, null),
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun openToken(): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(KEY_TOKEN_SEALED, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "orbit_music.discord"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12

        const val KEY_TOKEN_SEALED = "token_sealed"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_GLOBAL = "user_global"
        const val KEY_USER_AVATAR = "user_avatar"

        const val KEY_ENABLED = "enabled"
        const val KEY_APP_ID = "app_id"
        const val KEY_ACTIVITY_TYPE = "activity_type"
        const val KEY_STATUS_DISPLAY = "status_display"
        const val KEY_ONLINE_STATUS = "online_status"
        const val KEY_ACTIVITY_NAME = "activity_name"
        const val KEY_DETAILS = "details_template"
        const val KEY_STATE = "state_template"
        const val KEY_LARGE_TEXT = "large_text_template"
        const val KEY_SMALL_TEXT = "small_text_template"
        const val KEY_CUSTOM_LARGE_URL = "custom_large_url"
        const val KEY_CUSTOM_SMALL_URL = "custom_small_url"
        const val KEY_SHOW_ARTWORK = "show_artwork"
        const val KEY_SHOW_TIMESTAMPS = "show_timestamps"
        const val KEY_SHOW_SMALL_ICON = "show_small_icon"
        const val KEY_SHOW_PAUSED = "show_when_paused"
        const val KEY_SHOW_BUTTONS = "show_buttons"
        const val KEY_BTN1_LABEL = "btn1_label"
        const val KEY_BTN1_URL = "btn1_url"
        const val KEY_BTN2_LABEL = "btn2_label"
        const val KEY_BTN2_URL = "btn2_url"
    }
}
