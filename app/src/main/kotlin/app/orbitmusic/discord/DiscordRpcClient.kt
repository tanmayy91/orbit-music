package app.orbitmusic.discord

import android.util.LruCache
import app.orbitmusic.core.data.DiscordAccountStore
import app.orbitmusic.core.data.DiscordConfig
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.core.model.PlaybackState
import app.orbitmusic.core.model.PlaybackStatus
import app.orbitmusic.player.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.abs
import kotlin.random.Random

sealed interface DiscordConnectionState {
    data object Disabled : DiscordConnectionState
    data object SignedOut : DiscordConnectionState
    data object Connecting : DiscordConnectionState
    data class Idle(val userLabel: String?) : DiscordConnectionState
    data class Connected(val userLabel: String?, val summary: String) : DiscordConnectionState
    data class Error(val message: String) : DiscordConnectionState
}

/**
 * Kizzy-protocol Discord Gateway v10 WebSocket client & external-asset resolver.
 * Keeps playback in sync with the listener's Discord profile without leaking credentials.
 */
class DiscordRpcClient(
    private val store: DiscordAccountStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val assetCache = object : LruCache<String, String>(128) {}
    private val socketMutex = Mutex()
    private val presenceMutex = Mutex()

    private val _connectionState = MutableStateFlow<DiscordConnectionState>(DiscordConnectionState.Disabled)
    val connectionState: StateFlow<DiscordConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var heartbeatJob: Job? = null
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var ready: Boolean = false
    @Volatile
    private var heartbeatAcked: Boolean = true
    @Volatile
    private var lastSequence: Long? = null
    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var resumeGatewayUrl: String? = null
    @Volatile
    private var attemptCount: Int = 0
    @Volatile
    private var authFailed: Boolean = false
    @Volatile
    private var currentToken: String? = null
    @Volatile
    private var lastPublishedSignature: String? = null
    @Volatile
    private var lastSentPositionMs: Long = 0L
    @Volatile
    private var lastSentAtMs: Long = 0L
    @Volatile
    private var lastWasPlaying: Boolean = false
    @Volatile
    private var isOnlineNow: Boolean = true

    private var latestPlayback: PlaybackState = PlaybackState()
    private var latestPositionMs: Long = 0L
    private var attached = false

    fun attach(playback: PlaybackConnection, online: StateFlow<Boolean>) {
        if (attached) return
        attached = true

        scope.launch {
            combine(playback.state, store.config, store.token, online) { state, cfg, token, up ->
                SyncSnapshot(state, cfg, token, up)
            }.collect { snap ->
                latestPlayback = snap.state
                latestPositionMs = playback.positionNow()
                isOnlineNow = snap.online
                reconcile(snap.state, latestPositionMs, snap.config, snap.token, snap.online)
            }
        }

        // Detect seeks/scrubs without flooding the gateway on every playback tick.
        scope.launch {
            while (isActive) {
                delay(1_200L)
                val cfg = store.config.value
                val token = store.token.value
                val state = latestPlayback
                val pos = playback.positionNow()
                latestPositionMs = pos
                if (!cfg.enabled || token.isNullOrBlank() || !isOnlineNow || !ready) continue
                if (state.status == PlaybackStatus.PLAYING && lastWasPlaying && state.current != null) {
                    val elapsedSinceSend = (System.currentTimeMillis() - lastSentAtMs).coerceAtLeast(0L)
                    val expectedPos = lastSentPositionMs + elapsedSinceSend
                    if (abs(pos - expectedPos) >= SEEK_THRESHOLD_MS) {
                        publishPresence(state, pos, cfg, token, force = true)
                    }
                }
            }
        }
    }

    suspend fun verifyAndSaveToken(rawToken: String): Result<DiscordUser> {
        val clean = rawToken.trim().trim('"')
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Enter a Discord token."))
        return fetchProfile(clean).onSuccess { user ->
            authFailed = false
            attemptCount = 0
            sessionId = null
            resumeGatewayUrl = null
            lastSequence = null
            store.saveToken(clean)
            store.saveUser(user)
            reconcile(latestPlayback, latestPositionMs, store.config.value, clean, isOnlineNow)
        }
    }

    fun signOut() {
        authFailed = false
        attemptCount = 0
        store.signOut()
        disconnect(DiscordConnectionState.SignedOut)
    }

    fun refreshNow() {
        scope.launch {
            reconcile(latestPlayback, latestPositionMs, store.config.value, store.token.value, isOnlineNow, forcePresence = true)
        }
    }

    suspend fun fetchProfile(token: String): Result<DiscordUser> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(USERS_ME_URL)
                .header("Authorization", token)
                .header("User-Agent", DISCORD_USER_AGENT)
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val hint = if (code == 401 || code == 403) "Discord rejected that token ($code)." else "Discord returned HTTP $code."
                    throw IllegalStateException(hint)
                }
                val obj = json.parseToJsonElement(body).jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank() || username.isBlank()) throw IllegalStateException("Malformed Discord profile response.")
                DiscordUser(
                    id = id,
                    username = username,
                    globalName = obj["global_name"]?.jsonPrimitive?.contentOrNull,
                    avatarHash = obj["avatar"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    private suspend fun reconcile(
        state: PlaybackState,
        positionMs: Long,
        config: DiscordConfig,
        token: String?,
        online: Boolean,
        forcePresence: Boolean = false,
    ) {
        if (!config.enabled) {
            disconnect(DiscordConnectionState.Disabled)
            return
        }
        if (token.isNullOrBlank()) {
            disconnect(DiscordConnectionState.SignedOut)
            return
        }
        if (currentToken != null && currentToken != token) {
            sessionId = null
            resumeGatewayUrl = null
            lastSequence = null
            disconnect(DiscordConnectionState.Connecting)
        }
        if (authFailed) {
            disconnect(DiscordConnectionState.Error("Invalid Discord token. Sign in again."))
            return
        }
        if (!online) {
            disconnect(DiscordConnectionState.Idle(store.user.value?.handle))
            return
        }

        if (webSocket == null) {
            connect(token, resumeGatewayUrl ?: GATEWAY_URL)
            return
        }
        if (ready) {
            publishPresence(state, positionMs, config, token, force = forcePresence)
        }
    }

    private suspend fun connect(token: String, url: String) = socketMutex.withLock {
        if (webSocket != null && currentToken == token) return@withLock
        reconnectJob?.cancel()
        reconnectJob = null
        currentToken = token
        ready = false
        heartbeatAcked = true
        lastPublishedSignature = null
        _connectionState.value = DiscordConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DISCORD_USER_AGENT)
            .build()

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleGatewayMessage(webSocket, token, text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { handleSocketClosed(webSocket, code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { handleSocketFailure(webSocket, t) }
            }
        })
        webSocket = ws
    }

    private suspend fun handleGatewayMessage(ws: WebSocket, token: String, raw: String) {
        if (ws !== webSocket) return
        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val op = payload["op"]?.jsonPrimitive?.intOrNull ?: return
        payload["s"]?.jsonPrimitive?.longOrNull?.let { lastSequence = it }

        when (op) {
            10 -> { // Hello
                val intervalMs = payload["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
                startHeartbeat(ws, intervalMs)
                val sid = sessionId
                val seq = lastSequence
                val authPayload = if (sid != null && seq != null) {
                    DiscordRpcFormatter.buildResumePayload(token, sid, seq)
                } else {
                    DiscordRpcFormatter.buildIdentifyPayload(token)
                }
                ws.send(authPayload.toString())
            }
            11 -> { // Heartbeat ACK
                heartbeatAcked = true
            }
            1 -> { // Heartbeat requested immediately
                ws.send(DiscordRpcFormatter.buildHeartbeatPayload(lastSequence).toString())
            }
            7 -> { // Reconnect requested by Discord
                ws.close(4000, "Reconnect")
            }
            9 -> { // Invalid session
                val resumable = payload["d"]?.jsonPrimitive?.booleanOrNull == true
                if (!resumable) {
                    sessionId = null
                    lastSequence = null
                    resumeGatewayUrl = null
                }
                ws.close(4000, "Invalid session")
            }
            0 -> { // Dispatch
                val event = payload["t"]?.jsonPrimitive?.contentOrNull
                val data = payload["d"] as? JsonObject
                if (event == "READY" && data != null) {
                    ready = true
                    attemptCount = 0
                    sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull
                    resumeGatewayUrl = data["resume_gateway_url"]?.jsonPrimitive?.contentOrNull?.let { base ->
                        if (base.contains("?")) base else "$base/?v=10&encoding=json"
                    }
                    parseReadyUser(data)?.let { store.saveUser(it) }
                    publishPresence(latestPlayback, latestPositionMs, store.config.value, token, force = true)
                } else if (event == "RESUMED") {
                    ready = true
                    attemptCount = 0
                    publishPresence(latestPlayback, latestPositionMs, store.config.value, token, force = true)
                }
            }
        }
    }

    private fun parseReadyUser(data: JsonObject): DiscordUser? {
        val obj = data["user"] as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val username = obj["username"]?.jsonPrimitive?.contentOrNull ?: return null
        return DiscordUser(
            id = id,
            username = username,
            globalName = obj["global_name"]?.jsonPrimitive?.contentOrNull,
            avatarHash = obj["avatar"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val jitter = (intervalMs * (0.2 + Random.nextDouble() * 0.4)).toLong().coerceAtLeast(1_000L)
            delay(jitter)
            while (isActive && ws === webSocket) {
                if (!heartbeatAcked) {
                    ws.close(4000, "Missed heartbeat ACK")
                    break
                }
                heartbeatAcked = false
                ws.send(DiscordRpcFormatter.buildHeartbeatPayload(lastSequence).toString())
                delay(intervalMs)
            }
        }
    }

    private suspend fun handleSocketClosed(ws: WebSocket, code: Int, reason: String) {
        if (ws !== webSocket) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket = null
        ready = false
        lastPublishedSignature = null

        if (code == 4004) { // Authentication failed
            authFailed = true
            sessionId = null
            resumeGatewayUrl = null
            lastSequence = null
            _connectionState.value = DiscordConnectionState.Error("Discord authentication failed (4004). Sign in again.")
            return
        }
        if (code == 4010 || code == 4011 || code == 4012 || code == 4013 || code == 4014) {
            _connectionState.value = DiscordConnectionState.Error("Discord closed connection ($code: $reason).")
            return
        }
        scheduleReconnect()
    }

    private suspend fun handleSocketFailure(ws: WebSocket, t: Throwable) {
        if (ws !== webSocket) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket = null
        ready = false
        lastPublishedSignature = null
        if (!store.config.value.enabled || store.token.value.isNullOrBlank()) return
        _connectionState.value = DiscordConnectionState.Idle(store.user.value?.handle)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val cfg = store.config.value
        val token = store.token.value
        if (!cfg.enabled || token.isNullOrBlank() || authFailed || !isOnlineNow) return
        if (reconnectJob?.isActive == true) return

        val delayMs = (2_000L shl attemptCount.coerceAtMost(4)).coerceAtMost(30_000L)
        attemptCount++
        reconnectJob = scope.launch {
            delay(delayMs)
            reconcile(latestPlayback, latestPositionMs, store.config.value, store.token.value, isOnlineNow)
        }
    }

    private fun disconnect(nextState: DiscordConnectionState) {
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        val ws = webSocket
        webSocket = null
        if (ws != null && ready) {
            runCatching {
                ws.send(DiscordRpcFormatter.buildClearPresencePayload(store.config.value.onlineStatus).toString())
            }
        }
        ready = false
        lastPublishedSignature = null
        currentToken = null
        ws?.close(1000, "Disabled")
        _connectionState.value = nextState
    }

    private suspend fun publishPresence(
        state: PlaybackState,
        positionMs: Long,
        config: DiscordConfig,
        token: String,
        force: Boolean,
    ) = presenceMutex.withLock {
        val ws = webSocket ?: return@withLock
        if (!ready) return@withLock

        val nowMs = System.currentTimeMillis()
        val trackCtx = DiscordRpcTrackContext.fromPlayback(state, positionMs)
        val resolved = DiscordRpcFormatter.resolve(config, trackCtx, nowMs)

        val userHandle = store.user.value?.handle
        if (resolved == null) {
            val clearSig = "CLEAR:${config.onlineStatus.wireValue}"
            if (!force && lastPublishedSignature == clearSig) return@withLock
            lastPublishedSignature = clearSig
            lastWasPlaying = false
            ws.send(DiscordRpcFormatter.buildClearPresencePayload(config.onlineStatus).toString())
            _connectionState.value = DiscordConnectionState.Idle(userHandle)
            return@withLock
        }

        val largeAsset = resolveAsset(resolved.applicationId, token, resolved.largeImageRaw)
        val smallAsset = resolveAsset(resolved.applicationId, token, resolved.smallImageRaw)

        val signature = buildString {
            append(trackCtx?.trackId.orEmpty()).append('|')
            append(resolved.applicationId).append('|')
            append(resolved.activityType.code).append('|')
            append(resolved.statusDisplayType.code).append('|')
            append(resolved.onlineStatus.wireValue).append('|')
            append(resolved.activityName).append('|')
            append(resolved.details.orEmpty()).append('|')
            append(resolved.state.orEmpty()).append('|')
            append(largeAsset.orEmpty()).append('|')
            append(resolved.largeText.orEmpty()).append('|')
            append(smallAsset.orEmpty()).append('|')
            append(resolved.smallText.orEmpty()).append('|')
            append(resolved.buttons.joinToString()).append('|')
            append(resolved.isPaused).append('|')
            append(config.showTimestamps)
        }

        if (!force && signature == lastPublishedSignature) return@withLock

        val payload = DiscordRpcFormatter.buildPresenceUpdatePayload(resolved, largeAsset, smallAsset)
        if (ws.send(payload.toString())) {
            lastPublishedSignature = signature
            lastSentPositionMs = positionMs
            lastSentAtMs = nowMs
            lastWasPlaying = !resolved.isPaused
            val line = listOfNotNull(resolved.details, resolved.state).joinToString(" — ")
                .ifBlank { resolved.activityName }
            _connectionState.value = DiscordConnectionState.Connected(userHandle, line)
        }
    }

    private suspend fun resolveAsset(appId: String, token: String, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (DiscordRpcFormatter.isDirectAssetRef(value)) return value

        val cacheKey = "$appId|$value"
        assetCache.get(cacheKey)?.let { return it }

        val mapped = withContext(Dispatchers.IO) {
            runCatching {
                val requestBody = buildString {
                    append("""{"urls":[""")
                    append(json.encodeToString(kotlinx.serialization.serializer<String>(), value))
                    append("]}")
                }.toRequestBody(JSON_MEDIA)

                val request = Request.Builder()
                    .url("https://discord.com/api/v9/applications/$appId/external-assets")
                    .header("Authorization", token)
                    .header("User-Agent", DISCORD_USER_AGENT)
                    .post(requestBody)
                    .build()

                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val arr: JsonArray = json.parseToJsonElement(body).jsonArray
                    val first = arr.firstOrNull()?.jsonObject ?: return@use null
                    val path = first["external_asset_path"]?.jsonPrimitive?.contentOrNull ?: return@use null
                    DiscordRpcFormatter.normalizeExternalAssetPath(path)
                }
            }.getOrNull()
        }

        if (!mapped.isNullOrBlank()) {
            assetCache.put(cacheKey, mapped)
        }
        return mapped
    }

    private data class SyncSnapshot(
        val state: PlaybackState,
        val config: DiscordConfig,
        val token: String?,
        val online: Boolean,
    )

    private companion object {
        const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        const val USERS_ME_URL = "https://discord.com/api/v9/users/@me"
        const val DISCORD_USER_AGENT = "Discord-Android/244012;RNA"
        const val SEEK_THRESHOLD_MS = 2_800L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
