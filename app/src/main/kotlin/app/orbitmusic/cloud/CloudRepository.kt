package app.orbitmusic.cloud

import app.orbitmusic.BuildConfig
import app.orbitmusic.await
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.core.model.Track
import app.orbitmusic.core.model.withoutEmoji
import app.orbitmusic.ui.profile.ProfileStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Locale

class CloudRepository(
    private val http: OkHttpClient,
    private val auth: CloudAuthManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val refreshBus = MutableSharedFlow<String>(extraBufferCapacity = 32)

    private fun notifyTopic(vararg topics: String) {
        topics.forEach { refreshBus.tryEmit(it) }
    }

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("Sign in with Discord to use cloud features.")

    private fun requireConfig(): Pair<String, String> {
        val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (
            !url.startsWith("https://") ||
            url.contains("your-project-id") ||
            key.isBlank() ||
            key == "your_supabase_anon_key_here"
        ) {
            error("Supabase is not configured yet. Add SUPABASE_URL and SUPABASE_ANON_KEY in AI Studio Secrets.")
        }
        return url to key
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun pgArrayLiteral(values: List<String>): String =
        values.joinToString(prefix = "{", postfix = "}", separator = ",") { v ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }

    private fun <T> observeTopic(
        topic: String,
        intervalMs: Long = POLL_INTERVAL_MS,
        fetch: suspend () -> T,
    ): Flow<T> = flow {
        emit(fetch())
        while (true) {
            withTimeoutOrNull(intervalMs) {
                refreshBus.filter { it == topic || it == "*" }.first()
            }
            emit(fetch())
        }
    }

    private suspend fun <T> restGetList(
        table: String,
        queryParams: List<Pair<String, String>>,
        serializer: KSerializer<T>,
    ): List<T> = withContext(Dispatchers.IO) {
        val (baseUrl, anonKey) = requireConfig()
        val urlBuilder = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseSupabaseError(resp.code, body))
            }
            if (body.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(serializer), body)
        }
    }

    private suspend fun <T> restPostList(
        table: String,
        payload: JsonElement,
        serializer: KSerializer<T>,
        queryParams: List<Pair<String, String>> = emptyList(),
        prefer: String = "return=representation",
    ): List<T> = withContext(Dispatchers.IO) {
        val (baseUrl, anonKey) = requireConfig()
        val urlBuilder = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseSupabaseError(resp.code, body))
            }
            if (body.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(serializer), body)
        }
    }

    private suspend fun restPostUnit(
        table: String,
        payload: JsonElement,
        queryParams: List<Pair<String, String>> = emptyList(),
        prefer: String = "return=minimal",
    ) = withContext(Dispatchers.IO) {
        val (baseUrl, anonKey) = requireConfig()
        val urlBuilder = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseSupabaseError(resp.code, body))
            }
        }
    }

    private suspend fun restPatchUnit(
        table: String,
        queryParams: List<Pair<String, String>>,
        payload: JsonObject,
    ) = withContext(Dispatchers.IO) {
        val (baseUrl, anonKey) = requireConfig()
        val urlBuilder = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .patch(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseSupabaseError(resp.code, body))
            }
        }
    }

    private suspend fun restDelete(
        table: String,
        queryParams: List<Pair<String, String>>,
    ) = withContext(Dispatchers.IO) {
        val (baseUrl, anonKey) = requireConfig()
        val urlBuilder = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .delete()
            .build()
        http.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseSupabaseError(resp.code, body))
            }
        }
    }

    private fun parseSupabaseError(code: Int, body: String): String {
        val parsedMsg = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["message"]?.jsonPrimitive?.content
                ?: obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
        }.getOrNull()
        return when {
            code == 401 || code == 403 -> parsedMsg ?: "Supabase authentication rejected ($code). Check SUPABASE_ANON_KEY and table grants."
            code == 404 -> "Supabase table not found (404). Run supabase_schema.sql in your Supabase SQL Editor."
            !parsedMsg.isNullOrBlank() -> parsedMsg
            else -> "Supabase request failed (HTTP $code)."
        }
    }

    private fun formatHours(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes < 600 -> String.format(Locale.US, "%.1f hr", minutes / 60f)
        else -> "${minutes / 60} hr"
    }

    // --- 1. PROFILE, HANDLE REGISTRATION & DISCORD IDENTITY ---

    fun observeUserProfile(uid: String): Flow<UserProfile?> =
        observeTopic("profile:$uid") {
            restGetList(
                table = "orbit_users",
                queryParams = listOf("uid" to "eq.$uid", "select" to "*"),
                serializer = UserProfile.serializer(),
            ).firstOrNull()
        }

    suspend fun getUserProfile(uid: String): Result<UserProfile?> =
        try {
            val profile = restGetList(
                table = "orbit_users",
                queryParams = listOf("uid" to "eq.$uid", "select" to "*"),
                serializer = UserProfile.serializer(),
            ).firstOrNull()
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.GET, "orbit_users/$uid", uid)
            Result.failure(e)
        }

    suspend fun registerOrUpdateProfile(
        displayName: String,
        rawHandle: String,
        discordUser: DiscordUser?,
        cardShared: Boolean = false,
    ): Result<UserProfile> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val cleanName = displayName.trim().take(40)
        val handle = CloudValidation.normalizeHandle(rawHandle)
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a display name (up to 40 characters)."))
        }
        if (!CloudValidation.isValidHandle(handle)) {
            return Result.failure(IllegalArgumentException("Handle must be 3–30 lowercase letters, numbers, or underscores."))
        }

        val discordKey = (discordUser?.username ?: auth.currentUser?.username)
            ?.let(CloudValidation::normalizeDiscordKey)
            ?.takeIf { CloudValidation.isValidDiscordKey(it) }
        val avatarUrl = discordUser?.avatarUrl ?: auth.currentUser?.avatarUrl

        return try {
            val existingHandle = restGetList(
                table = "orbit_users",
                queryParams = listOf("handle" to "eq.$handle", "select" to "uid,handle,display_name,discord_username"),
                serializer = DirectoryEntry.serializer(),
            ).firstOrNull()
            if (existingHandle != null && existingHandle.uid != uid) {
                return Result.failure(IllegalStateException("@$handle is already taken."))
            }

            if (discordKey != null) {
                val existingDc = restGetList(
                    table = "orbit_users",
                    queryParams = listOf("discord_username" to "eq.$discordKey", "select" to "uid,handle,display_name,discord_username"),
                    serializer = DirectoryEntry.serializer(),
                ).firstOrNull()
                if (existingDc != null && existingDc.uid != uid) {
                    return Result.failure(IllegalStateException("Discord @$discordKey is already linked to another Orbit profile."))
                }
            }

            val payload = buildJsonObject {
                put("uid", uid)
                put("display_name", cleanName)
                put("handle", handle)
                if (discordKey != null) put("discord_username", discordKey) else put("discord_username", JsonNull)
                if (avatarUrl != null) put("discord_avatar_url", avatarUrl) else put("discord_avatar_url", JsonNull)
                put("card_shared", cardShared)
                put("updated_at", nowIso())
            }

            val saved = restPostList(
                table = "orbit_users",
                payload = payload,
                serializer = UserProfile.serializer(),
                queryParams = listOf("on_conflict" to "uid"),
                prefer = "resolution=merge-duplicates,return=representation",
            ).firstOrNull() ?: UserProfile(
                uid = uid,
                displayName = cleanName,
                handle = handle,
                discordUsername = discordKey,
                discordAvatarUrl = avatarUrl,
                cardShared = cardShared,
            )

            notifyTopic("profile:$uid")
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.WRITE, "orbit_users/$uid", uid)
            Result.failure(e)
        }
    }

    // --- 2. FRIENDS & LISTENER LOOKUP ---

    suspend fun resolveRecipient(rawQuery: String): Result<DirectoryEntry> {
        val cleaned = rawQuery.trim().removePrefix("@").lowercase(Locale.ROOT)
        val rawId = rawQuery.trim()
        if (cleaned.length < 2) {
            return Result.failure(IllegalArgumentException("Enter a listener @handle, Discord username, or Discord ID."))
        }
        return try {
            val myUid = requireUid()
            val filters = buildList {
                if (CloudValidation.isValidHandle(cleaned)) add("handle.eq.$cleaned")
                if (CloudValidation.isValidDiscordKey(cleaned)) add("discord_username.eq.$cleaned")
                if (rawId.all { it.isDigit() } && rawId.length in 15..25) add("uid.eq.$rawId")
            }
            if (filters.isEmpty()) {
                return Result.failure(IllegalArgumentException("Enter a valid Orbit @handle, Discord username, or Discord ID."))
            }
            val found = restGetList(
                table = "orbit_users",
                queryParams = listOf(
                    "or" to "(${filters.joinToString(",")})",
                    "select" to "uid,handle,display_name,discord_username",
                    "limit" to "1",
                ),
                serializer = DirectoryEntry.serializer(),
            ).firstOrNull()

            if (found != null) {
                Result.success(found)
            } else {
                handleCloudError(NoSuchElementException("Not found"), OperationType.GET, "orbit_users/$cleaned", myUid)
                Result.failure(NoSuchElementException("No Orbit Music listener found for $rawQuery."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.GET, "orbit_users/$cleaned")
            Result.failure(e)
        }
    }

    fun observeFriendships(uid: String): Flow<List<Friendship>> =
        observeTopic("friendships:$uid") {
            restGetList(
                table = "friendships",
                queryParams = listOf(
                    "member_uids" to "cs.${pgArrayLiteral(listOf(uid))}",
                    "select" to "*",
                    "order" to "updated_at.desc",
                ),
                serializer = Friendship.serializer(),
            )
        }

    suspend fun sendFriendRequest(myProfile: UserProfile, recipient: DirectoryEntry): Result<Unit> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (uid != myProfile.uid || recipient.uid == uid) {
            return Result.failure(IllegalArgumentException("You cannot send a friend request to yourself."))
        }
        val fid = CloudValidation.pairDocId(uid, recipient.uid)
        return try {
            val existing = restGetList(
                table = "friendships",
                queryParams = listOf("id" to "eq.$fid", "select" to "*"),
                serializer = Friendship.serializer(),
            ).firstOrNull()
            if (existing != null) {
                return Result.failure(
                    IllegalStateException(
                        if (existing.accepted) "You are already friends with @${recipient.handle}."
                        else "A friend request with @${recipient.handle} is already pending."
                    )
                )
            }

            val payload = buildJsonObject {
                put("id", fid)
                put("member_uids", buildJsonArray { add(JsonPrimitive(uid)); add(JsonPrimitive(recipient.uid)) })
                put("requester_uid", uid)
                put("recipient_uid", recipient.uid)
                put("requester_name", myProfile.displayName.take(40))
                put("requester_handle", myProfile.handle)
                put("recipient_name", recipient.displayName.take(40))
                put("recipient_handle", recipient.handle)
                put("status", "pending")
                put("updated_at", nowIso())
            }
            restPostUnit("friendships", payload)
            notifyTopic("friendships:$uid", "friendships:${recipient.uid}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.CREATE, "friendships/$fid", uid)
            Result.failure(e)
        }
    }

    suspend fun acceptFriendRequest(friendship: Friendship): Result<Unit> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (friendship.recipientUid != uid) {
            return Result.failure(IllegalStateException("Only the recipient can accept this request."))
        }
        return try {
            restPatchUnit(
                table = "friendships",
                queryParams = listOf("id" to "eq.${friendship.id}"),
                payload = buildJsonObject {
                    put("status", "accepted")
                    put("updated_at", nowIso())
                },
            )
            val otherUid = friendship.otherUid(uid)
            runCatching { addCardViewer(ownerUid = uid, viewerUid = otherUid) }
            runCatching { addCardViewer(ownerUid = otherUid, viewerUid = uid) }
            notifyTopic("friendships:$uid", "cards:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.UPDATE, "friendships/${friendship.id}", uid)
            Result.failure(e)
        }
    }

    private suspend fun addCardViewer(ownerUid: String, viewerUid: String) {
        val card = restGetList(
            table = "listening_cards",
            queryParams = listOf("uid" to "eq.$ownerUid", "select" to "*"),
            serializer = ListeningCardSnapshot.serializer(),
        ).firstOrNull() ?: return
        if (viewerUid !in card.viewerUids && card.viewerUids.size < 100) {
            val nextViewers = (card.viewerUids + viewerUid).distinct().take(100)
            restPatchUnit(
                table = "listening_cards",
                queryParams = listOf("uid" to "eq.$ownerUid"),
                payload = buildJsonObject {
                    put("viewer_uids", buildJsonArray { nextViewers.forEach { add(JsonPrimitive(it)) } })
                    put("updated_at", nowIso())
                },
            )
        }
    }

    suspend fun removeFriendship(friendshipId: String): Result<Unit> =
        try {
            val uid = requireUid()
            restDelete("friendships", listOf("id" to "eq.$friendshipId"))
            notifyTopic("friendships:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.DELETE, "friendships/$friendshipId")
            Result.failure(e)
        }

    // --- 4. LISTENING CARD SHARING ---

    fun observeVisibleListeningCards(uid: String): Flow<List<ListeningCardSnapshot>> =
        observeTopic("cards:$uid") {
            restGetList(
                table = "listening_cards",
                queryParams = listOf(
                    "viewer_uids" to "cs.${pgArrayLiteral(listOf(uid))}",
                    "select" to "*",
                    "order" to "updated_at.desc",
                ),
                serializer = ListeningCardSnapshot.serializer(),
            )
        }

    suspend fun syncFriendListeningCards(myUid: String, acceptedFriendUids: List<String>): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == myUid)
            for (friendUid in acceptedFriendUids) {
                runCatching { addCardViewer(ownerUid = friendUid, viewerUid = myUid) }
            }
            notifyTopic("cards:$myUid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.GET, "listening_cards", myUid)
            Result.failure(e)
        }

    suspend fun publishListeningCard(
        profile: UserProfile,
        stats: ProfileStats,
        topGenre: String?,
        acceptedFriendUids: List<String>,
    ): Result<Unit> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (uid != profile.uid) {
            return Result.failure(IllegalStateException("Signed-in user mismatch."))
        }
        val viewers = (listOf(uid) + acceptedFriendUids).distinct().take(100)
        val topArtists = stats.artists.take(4).map { a ->
            val playsLabel = if (a.plays == 1) "1 play" else "${a.plays} plays"
            "${a.name.withoutEmoji().trim()} · $playsLabel".take(80)
        }
        val topSong = stats.songs.firstOrNull()?.track
        val topSongTitle = topSong?.title?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
        val topSongArtist = topSong?.artistLine?.trim()?.take(140)?.takeIf { it.isNotEmpty() }

        return try {
            val payload = buildJsonObject {
                put("uid", uid)
                put("owner_name", profile.displayName.take(40))
                put("handle", profile.handle)
                profile.discordUsername?.take(32)?.let { put("discord_username", it) } ?: put("discord_username", JsonNull)
                put("persona", stats.persona.take(40).ifBlank { "The listener" })
                stats.sinceLabel?.take(40)?.let { put("since_label", it) } ?: put("since_label", JsonNull)
                put("hours_label", formatHours(stats.minutes).take(32))
                put("plays", stats.plays.coerceAtLeast(0))
                put("artist_count", stats.artistCount.coerceAtLeast(0))
                put("best_streak", stats.bestStreak.coerceAtLeast(0))
                topSongTitle?.let { put("top_song_title", it) } ?: put("top_song_title", JsonNull)
                topSongArtist?.let { put("top_song_artist", it) } ?: put("top_song_artist", JsonNull)
                topGenre?.trim()?.take(48)?.takeIf { it.isNotEmpty() }?.let { put("top_genre", it) } ?: put("top_genre", JsonNull)
                put("top_artists", buildJsonArray { topArtists.forEach { add(JsonPrimitive(it)) } })
                put("viewer_uids", buildJsonArray { viewers.forEach { add(JsonPrimitive(it)) } })
                put("updated_at", nowIso())
            }
            restPostUnit(
                table = "listening_cards",
                payload = payload,
                queryParams = listOf("on_conflict" to "uid"),
                prefer = "resolution=merge-duplicates,return=minimal",
            )
            restPatchUnit(
                table = "orbit_users",
                queryParams = listOf("uid" to "eq.$uid"),
                payload = buildJsonObject {
                    put("card_shared", true)
                    put("updated_at", nowIso())
                },
            )
            notifyTopic("cards:$uid", "profile:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.WRITE, "listening_cards/$uid", uid)
            Result.failure(e)
        }
    }

    suspend fun unpublishListeningCard(uid: String): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == uid)
            restDelete("listening_cards", listOf("uid" to "eq.$uid"))
            restPatchUnit(
                table = "orbit_users",
                queryParams = listOf("uid" to "eq.$uid"),
                payload = buildJsonObject {
                    put("card_shared", false)
                    put("updated_at", nowIso())
                },
            )
            notifyTopic("cards:$uid", "profile:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.DELETE, "listening_cards/$uid", uid)
            Result.failure(e)
        }

    // --- 5. COLLABORATIVE PLAYLISTS & TRACKS ---

    fun observeCollabPlaylists(uid: String): Flow<List<CollabPlaylist>> =
        observeTopic("playlists:$uid") {
            restGetList(
                table = "collab_playlists",
                queryParams = listOf(
                    "member_uids" to "cs.${pgArrayLiteral(listOf(uid))}",
                    "select" to "*",
                    "order" to "updated_at.desc",
                ),
                serializer = CollabPlaylist.serializer(),
            )
        }

    fun observeCollabPlaylist(playlistId: String): Flow<CollabPlaylist?> =
        observeTopic("playlist:$playlistId") {
            restGetList(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.$playlistId", "select" to "*"),
                serializer = CollabPlaylist.serializer(),
            ).firstOrNull()
        }

    suspend fun createCollabPlaylist(
        profile: UserProfile,
        title: String,
        description: String? = null,
    ): Result<CollabPlaylist> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanTitle = title.trim().take(80)
        if (cleanTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("Playlist title cannot be empty."))
        }
        val cleanDesc = description?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
        val inviteCode = CloudValidation.generateInviteCode()
        val memberTag = CloudValidation.memberTag(profile.displayName, profile.handle)

        return try {
            val payload = buildJsonObject {
                put("title", cleanTitle)
                cleanDesc?.let { put("description", it) }
                put("owner_id", uid)
                put("owner_name", profile.displayName.take(40))
                put("invite_code", inviteCode)
                put("member_uids", buildJsonArray { add(JsonPrimitive(uid)) })
                put("member_names", buildJsonArray { add(JsonPrimitive(memberTag)) })
                put("track_count", 0)
                put("track_doc_ids", buildJsonArray {})
                put("updated_at", nowIso())
            }
            val created = restPostList(
                table = "collab_playlists",
                payload = payload,
                serializer = CollabPlaylist.serializer(),
            ).firstOrNull() ?: error("Failed to create collaborative playlist.")
            notifyTopic("playlists:$uid")
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.CREATE, "collab_playlists", uid)
            Result.failure(e)
        }
    }

    suspend fun joinCollabPlaylistByCode(profile: UserProfile, rawCode: String): Result<String> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val code = CloudValidation.normalizeInviteCode(rawCode)
        if (!CloudValidation.isValidInviteCode(code)) {
            return Result.failure(IllegalArgumentException("Enter a valid 6–12 character invite code."))
        }
        return try {
            val playlist = restGetList(
                table = "collab_playlists",
                queryParams = listOf("invite_code" to "eq.$code", "select" to "*"),
                serializer = CollabPlaylist.serializer(),
            ).firstOrNull() ?: return Result.failure(NoSuchElementException("No collaborative playlist found for code $code."))

            val memberTag = CloudValidation.memberTag(profile.displayName, profile.handle)
            if (uid !in playlist.memberUids) {
                val nextUids = (playlist.memberUids + uid).distinct()
                val nextNames = (playlist.memberNames + memberTag).distinct()
                restPatchUnit(
                    table = "collab_playlists",
                    queryParams = listOf("id" to "eq.${playlist.id}"),
                    payload = buildJsonObject {
                        put("member_uids", buildJsonArray { nextUids.forEach { add(JsonPrimitive(it)) } })
                        put("member_names", buildJsonArray { nextNames.forEach { add(JsonPrimitive(it)) } })
                        put("updated_at", nowIso())
                    },
                )
                syncTrackMembership(playlist.id, playlist.trackDocIds, nextUids)
            }
            notifyTopic("playlists:$uid", "playlist:${playlist.id}", "tracks:${playlist.id}")
            Result.success(playlist.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.UPDATE, "collab_playlists/invite/$code", uid)
            Result.failure(e)
        }
    }

    suspend fun syncTrackMembership(
        playlistId: String,
        trackDocIds: List<String>,
        memberUids: List<String>,
    ) {
        if (trackDocIds.isEmpty()) return
        runCatching {
            restPatchUnit(
                table = "collab_tracks",
                queryParams = listOf("playlist_id" to "eq.$playlistId"),
                payload = buildJsonObject {
                    put("member_uids", buildJsonArray { memberUids.forEach { add(JsonPrimitive(it)) } })
                },
            )
        }
    }

    suspend fun leaveCollabPlaylist(profile: UserProfile, playlistId: String): Result<Unit> =
        try {
            val uid = requireUid()
            val playlist = restGetList(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.$playlistId", "select" to "*"),
                serializer = CollabPlaylist.serializer(),
            ).firstOrNull() ?: return Result.failure(NoSuchElementException("Playlist not found."))
            if (playlist.ownerId == uid) {
                return Result.failure(IllegalStateException("Owners cannot leave their own playlist. Delete it instead."))
            }
            val nextUids = playlist.memberUids.filter { it != uid }
            val myTag = CloudValidation.memberTag(profile.displayName, profile.handle)
            val nextNames = playlist.memberNames
                .filterNot { it == myTag || it.endsWith("(@${profile.handle})") }
                .ifEmpty { playlist.memberNames.take(1) }
            restPatchUnit(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.$playlistId"),
                payload = buildJsonObject {
                    put("member_uids", buildJsonArray { nextUids.forEach { add(JsonPrimitive(it)) } })
                    put("member_names", buildJsonArray { nextNames.forEach { add(JsonPrimitive(it)) } })
                    put("updated_at", nowIso())
                },
            )
            notifyTopic("playlists:$uid", "playlist:$playlistId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.UPDATE, "collab_playlists/$playlistId")
            Result.failure(e)
        }

    suspend fun renameCollabPlaylist(
        playlist: CollabPlaylist,
        newTitle: String,
        description: String? = playlist.description,
    ): Result<Unit> {
        val cleanTitle = newTitle.trim().take(80)
        if (cleanTitle.isEmpty()) return Result.failure(IllegalArgumentException("Title cannot be empty."))
        return try {
            val uid = requireUid()
            val payload = buildJsonObject {
                put("title", cleanTitle)
                description?.trim()?.take(240)?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                put("updated_at", nowIso())
            }
            restPatchUnit(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.${playlist.id}"),
                payload = payload,
            )
            notifyTopic("playlists:$uid", "playlist:${playlist.id}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.UPDATE, "collab_playlists/${playlist.id}")
            Result.failure(e)
        }
    }

    suspend fun deleteCollabPlaylist(playlist: CollabPlaylist): Result<Unit> =
        try {
            val uid = requireUid()
            check(playlist.ownerId == uid) { "Only the playlist creator can delete it." }
            restDelete("collab_playlists", listOf("id" to "eq.${playlist.id}"))
            notifyTopic("playlists:$uid", "playlist:${playlist.id}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.DELETE, "collab_playlists/${playlist.id}")
            Result.failure(e)
        }

    fun observeCollabTracks(playlistId: String, uid: String): Flow<List<CollabTrack>> =
        observeTopic("tracks:$playlistId") {
            restGetList(
                table = "collab_tracks",
                queryParams = listOf(
                    "playlist_id" to "eq.$playlistId",
                    "select" to "*",
                    "order" to "created_at.asc",
                ),
                serializer = CollabTrack.serializer(),
            )
        }

    suspend fun addTrackToCollabPlaylist(
        profile: UserProfile,
        playlistId: String,
        track: Track,
    ): Result<Unit> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            val latest = restGetList(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.$playlistId", "select" to "*"),
                serializer = CollabPlaylist.serializer(),
            ).firstOrNull() ?: return Result.failure(NoSuchElementException("Collaborative playlist no longer exists."))

            if (latest.trackDocIds.size >= 200) {
                return Result.failure(IllegalStateException("Collaborative playlists hold up to 200 songs."))
            }

            val artworkUrl = track.artwork.url(600)?.take(500)?.takeIf { it.isNotBlank() }
            val trackPayload = buildJsonObject {
                put("playlist_id", playlistId)
                put("member_uids", buildJsonArray { latest.memberUids.forEach { add(JsonPrimitive(it)) } })
                put("media_id", track.id.value.take(200))
                put("title", track.title.trim().take(160).ifEmpty { "Untitled track" })
                put("artist_line", track.artistLine.trim().take(160))
                track.album?.title?.trim()?.take(160)?.takeIf { it.isNotEmpty() }?.let { put("album_title", it) }
                artworkUrl?.let { put("artwork_url", it) }
                track.durationMs?.takeIf { it >= 0L }?.let { put("duration_ms", it) }
                put("added_by_uid", uid)
                put("added_by_name", profile.displayName.trim().take(40).ifBlank { "Listener" })
            }

            val inserted = restPostList(
                table = "collab_tracks",
                payload = trackPayload,
                serializer = CollabTrack.serializer(),
            ).firstOrNull() ?: error("Failed to insert track.")

            val nextTrackIds = (latest.trackDocIds + inserted.id).distinct().take(200)
            val summaryUpdate = buildJsonObject {
                put("track_count", nextTrackIds.size)
                put("track_doc_ids", buildJsonArray { nextTrackIds.forEach { add(JsonPrimitive(it)) } })
                if (latest.coverUrl == null && artworkUrl != null) put("cover_url", artworkUrl)
                put("updated_at", nowIso())
            }
            restPatchUnit(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.$playlistId"),
                payload = summaryUpdate,
            )
            notifyTopic("tracks:$playlistId", "playlist:$playlistId", "playlists:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.WRITE, "collab_tracks/$playlistId", uid)
            Result.failure(e)
        }
    }

    suspend fun removeTrackFromCollabPlaylist(
        playlist: CollabPlaylist,
        trackDocId: String,
    ): Result<Unit> =
        try {
            val uid = requireUid()
            restDelete("collab_tracks", listOf("id" to "eq.$trackDocId"))
            val nextIds = playlist.trackDocIds.filter { it != trackDocId }
            restPatchUnit(
                table = "collab_playlists",
                queryParams = listOf("id" to "eq.${playlist.id}"),
                payload = buildJsonObject {
                    put("track_count", nextIds.size)
                    put("track_doc_ids", buildJsonArray { nextIds.forEach { add(JsonPrimitive(it)) } })
                    put("updated_at", nowIso())
                },
            )
            notifyTopic("tracks:${playlist.id}", "playlist:${playlist.id}", "playlists:$uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleCloudError(e, OperationType.DELETE, "collab_tracks/$trackDocId")
            Result.failure(e)
        }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
