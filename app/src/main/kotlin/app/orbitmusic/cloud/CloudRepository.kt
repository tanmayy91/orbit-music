package app.orbitmusic.cloud

import android.content.Context
import app.orbitmusic.R
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.core.model.Track
import app.orbitmusic.core.model.withoutEmoji
import app.orbitmusic.ui.profile.ProfileStats
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

class CloudRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("Sign in with Google to use cloud features.")

    private fun formatHours(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes < 600 -> String.format(Locale.US, "%.1f hr", minutes / 60f)
        else -> "${minutes / 60} hr"
    }

    // --- 1. PROFILE, HANDLE REGISTRATION & DISCORD LINK ---

    fun observeUserProfile(uid: String): Flow<UserProfile?> =
        db.collection("users").document(uid)
            .snapshots()
            .map { snap ->
                if (snap.exists()) snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                else null
            }

    suspend fun getUserProfile(uid: String): Result<UserProfile?> =
        try {
            val snap = db.collection("users").document(uid).get().await()
            val profile = if (snap.exists()) {
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "users/$uid")
            Result.failure(e)
        }

    suspend fun registerOrUpdateProfile(
        displayName: String,
        rawHandle: String,
        discordUser: DiscordUser?,
        arySyncEnabled: Boolean = true,
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

        val discordKey = discordUser?.username?.let(CloudValidation::normalizeDiscordKey)
            ?.takeIf { CloudValidation.isValidDiscordKey(it) }
        val discordId = discordUser?.id?.trim()?.takeIf { it.length in 4..40 }

        return try {
            val handleRef = db.collection("listenerDirectory").document(handle)
            val existingHandleSnap = handleRef.get().await()
            if (existingHandleSnap.exists() && existingHandleSnap.getString("uid") != uid) {
                return Result.failure(IllegalStateException("@$handle is already taken."))
            }

            if (discordKey != null) {
                val dcRef = db.collection("discordDirectory").document(discordKey)
                val existingDc = dcRef.get().await()
                if (existingDc.exists() && existingDc.getString("uid") != uid) {
                    return Result.failure(IllegalStateException("Discord @$discordKey is already linked to another Orbit profile."))
                }
            }

            val userRef = db.collection("users").document(uid)
            val existingUserSnap = userRef.get().await()
            val existingProfile = if (existingUserSnap.exists()) {
                existingUserSnap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null

            val batch = db.batch()
            if (existingProfile == null) {
                val userMap = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "displayName" to cleanName,
                    "handle" to handle,
                    "discordUsername" to discordKey,
                    "discordId" to discordId,
                    "arySyncEnabled" to arySyncEnabled,
                    "cardShared" to cardShared,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ).filterValues { it != null }
                batch.set(userRef, userMap)
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to cleanName,
                    "handle" to handle,
                    "arySyncEnabled" to arySyncEnabled,
                    "cardShared" to cardShared,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (discordKey != null && discordId != null) {
                    updateMap["discordUsername"] = discordKey
                    updateMap["discordId"] = discordId
                } else {
                    if (existingProfile.discordUsername != null) updateMap["discordUsername"] = FieldValue.delete()
                    if (existingProfile.discordId != null) updateMap["discordId"] = FieldValue.delete()
                }
                batch.update(userRef, updateMap)
            }

            // Directory entry
            val oldHandle = existingProfile?.handle
            if (oldHandle != null && oldHandle != handle) {
                batch.delete(db.collection("listenerDirectory").document(oldHandle))
            }
            if (oldHandle == null || oldHandle != handle || !existingHandleSnap.exists()) {
                val dirCreateMap = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "handle" to handle,
                    "displayName" to cleanName,
                    "discordUsername" to discordKey,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ).filterValues { it != null }
                batch.set(handleRef, dirCreateMap)
            } else {
                val dirUpdateMap = mutableMapOf<String, Any>(
                    "displayName" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (discordKey != null) {
                    dirUpdateMap["discordUsername"] = discordKey
                } else if (existingHandleSnap.contains("discordUsername")) {
                    dirUpdateMap["discordUsername"] = FieldValue.delete()
                }
                batch.update(handleRef, dirUpdateMap)
            }

            // Discord directory entry
            val oldDc = existingProfile?.discordUsername?.let(CloudValidation::normalizeDiscordKey)
            if (oldDc != null && oldDc != discordKey) {
                batch.delete(db.collection("discordDirectory").document(oldDc))
            }
            if (discordKey != null) {
                val dcRef = db.collection("discordDirectory").document(discordKey)
                if (oldDc == null || oldDc != discordKey) {
                    batch.set(
                        dcRef,
                        mapOf(
                            "uid" to uid,
                            "discordUsername" to discordKey,
                            "handle" to handle,
                            "displayName" to cleanName,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                } else {
                    batch.update(
                        dcRef,
                        mapOf(
                            "handle" to handle,
                            "displayName" to cleanName,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                }
            }

            batch.commit().await()
            val refreshed = userRef.get().await()
                .toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserProfile(
                    id = uid,
                    uid = uid,
                    displayName = cleanName,
                    handle = handle,
                    discordUsername = discordKey,
                    discordId = discordId,
                    arySyncEnabled = arySyncEnabled,
                    cardShared = cardShared,
                )
            Result.success(refreshed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun setArySyncEnabled(uid: String, enabled: Boolean): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == uid) { "Signed-in user changed." }
            db.collection("users").document(uid)
                .update(
                    mapOf(
                        "arySyncEnabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }

    // --- 2. ARY CONVERSATION SYNC ---

    fun observeAryMessages(uid: String): Flow<List<ArySyncMessage>> =
        db.collection("users").document(uid).collection("aryMessages")
            .snapshots()
            .map { snap ->
                snap.toObjects(ArySyncMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedWith(compareBy<ArySyncMessage> { it.seq }.thenBy { it.createdAt ?: Timestamp(0, 0) })
            }

    suspend fun saveAryMessage(
        uid: String,
        role: String,
        text: String,
        changeSummaries: List<String>,
        focusedPlaylistTitle: String?,
        seq: Int,
    ): Result<Unit> {
        val boundedText = text.trim().take(4000)
        if (boundedText.isEmpty()) return Result.success(Unit)
        return try {
            val currentUid = requireUid()
            check(currentUid == uid) { "Signed-in user changed." }
            val msgRef = db.collection("users").document(uid).collection("aryMessages").document()
            val payload = mutableMapOf<String, Any?>(
                "role" to if (role == "assistant") "assistant" else "user",
                "text" to boundedText,
                "changeSummaries" to changeSummaries.take(15).map { it.trim().take(200) }.filter { it.isNotEmpty() },
                "focusedPlaylistTitle" to focusedPlaylistTitle?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
                "seq" to seq.coerceAtLeast(0),
                "createdAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null }
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/$uid/aryMessages")
            Result.failure(e)
        }
    }

    suspend fun clearAryMessages(uid: String): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == uid) { "Signed-in user changed." }
            val snap = db.collection("users").document(uid).collection("aryMessages").get().await()
            if (!snap.isEmpty) {
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid/aryMessages")
            Result.failure(e)
        }

    // --- 3. FRIENDS & LISTENER LOOKUP ---

    suspend fun resolveRecipient(rawQuery: String): Result<DirectoryEntry> {
        val cleaned = rawQuery.trim().removePrefix("@").lowercase(Locale.ROOT)
        if (cleaned.length < 2) {
            return Result.failure(IllegalArgumentException("Enter a listener @handle or linked Discord username."))
        }
        return try {
            requireUid()
            if (CloudValidation.isValidHandle(cleaned)) {
                val handleSnap = db.collection("listenerDirectory").document(cleaned).get().await()
                if (handleSnap.exists()) {
                    handleSnap.toObject(DirectoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?.let { return Result.success(it) }
                }
            }
            if (CloudValidation.isValidDiscordKey(cleaned)) {
                val dcSnap = db.collection("discordDirectory").document(cleaned).get().await()
                if (dcSnap.exists()) {
                    val dc = dcSnap.toObject(DiscordLookupEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    if (dc != null) {
                        return Result.success(
                            DirectoryEntry(
                                id = dc.handle,
                                uid = dc.uid,
                                handle = dc.handle,
                                displayName = dc.displayName,
                                discordUsername = dc.discordUsername,
                            )
                        )
                    }
                }
            }
            Result.failure(NoSuchElementException("No Orbit Music listener found for @$cleaned."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "listenerDirectory/$cleaned")
            Result.failure(e)
        }
    }

    fun observeFriendships(uid: String): Flow<List<Friendship>> =
        db.collection("friendships")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(Friendship::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
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
        val ref = db.collection("friendships").document(fid)
        return try {
            val existing = ref.get().await()
            if (existing.exists()) {
                val status = existing.getString("status")
                return Result.failure(
                    IllegalStateException(
                        if (status == "accepted") "You are already friends with @${recipient.handle}."
                        else "A friend request with @${recipient.handle} is already pending."
                    )
                )
            }
            ref.set(
                mapOf(
                    "memberUids" to listOf(uid, recipient.uid),
                    "requesterUid" to uid,
                    "recipientUid" to recipient.uid,
                    "requesterName" to myProfile.displayName.take(40),
                    "requesterHandle" to myProfile.handle,
                    "recipientName" to recipient.displayName.take(40),
                    "recipientHandle" to recipient.handle,
                    "status" to "pending",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "friendships/$fid")
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
        val ref = db.collection("friendships").document(friendship.id)
        return try {
            ref.update(
                mapOf(
                    "status" to "accepted",
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            val otherUid = friendship.otherUid(uid)
            // Best-effort: register mutual visibility on published listening cards
            runCatching {
                val myCardRef = db.collection("listeningCards").document(uid)
                val myCard = myCardRef.get().await()
                if (myCard.exists()) {
                    myCardRef.update(
                        mapOf(
                            "viewerUids" to FieldValue.arrayUnion(otherUid),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    ).await()
                }
            }
            runCatching {
                val friendCardRef = db.collection("listeningCards").document(otherUid)
                val friendCard = friendCardRef.get().await()
                if (friendCard.exists()) {
                    val viewers = (friendCard.get("viewerUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (uid !in viewers && viewers.size < 100) {
                        friendCardRef.update("viewerUids", FieldValue.arrayUnion(uid)).await()
                    }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "friendships/${friendship.id}")
            Result.failure(e)
        }
    }

    suspend fun removeFriendship(friendshipId: String): Result<Unit> =
        try {
            requireUid()
            db.collection("friendships").document(friendshipId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "friendships/$friendshipId")
            Result.failure(e)
        }

    // --- 4. LISTENING CARD SHARING ---

    fun observeVisibleListeningCards(uid: String): Flow<List<ListeningCardSnapshot>> =
        db.collection("listeningCards")
            .whereArrayContains("viewerUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(ListeningCardSnapshot::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }

    suspend fun syncFriendListeningCards(myUid: String, acceptedFriendUids: List<String>): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == myUid)
            for (friendUid in acceptedFriendUids) {
                try {
                    val cardRef = db.collection("listeningCards").document(friendUid)
                    val snap = cardRef.get().await()
                    if (snap.exists()) {
                        val viewers = (snap.get("viewerUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                        if (myUid !in viewers && viewers.size < 100) {
                            cardRef.update("viewerUids", FieldValue.arrayUnion(myUid)).await()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: FirebaseFirestoreException) {
                    // Friend hasn't published a card yet or unshared; skip silently
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "listeningCards")
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
        val cardRef = db.collection("listeningCards").document(uid)
        val userRef = db.collection("users").document(uid)
        val viewers = (listOf(uid) + acceptedFriendUids).distinct().take(100)
        val topArtists = stats.artists.take(4).map { a ->
            val playsLabel = if (a.plays == 1) "1 play" else "${a.plays} plays"
            "${a.name.withoutEmoji().trim()} · $playsLabel".take(80)
        }
        val topSong = stats.songs.firstOrNull()?.track
        val topSongTitle = topSong?.title?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
        val topSongArtist = topSong?.artistLine?.trim()?.take(140)?.takeIf { it.isNotEmpty() }

        return try {
            val existing = cardRef.get().await()
            val batch = db.batch()
            if (!existing.exists()) {
                val createMap = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "ownerName" to profile.displayName.take(40),
                    "handle" to profile.handle,
                    "discordUsername" to profile.discordUsername?.take(32),
                    "persona" to stats.persona.take(40).ifBlank { "The listener" },
                    "sinceLabel" to stats.sinceLabel?.take(40),
                    "hoursLabel" to formatHours(stats.minutes).take(32),
                    "plays" to stats.plays.coerceAtLeast(0),
                    "artistCount" to stats.artistCount.coerceAtLeast(0),
                    "bestStreak" to stats.bestStreak.coerceAtLeast(0),
                    "topSongTitle" to topSongTitle,
                    "topSongArtist" to topSongArtist,
                    "topGenre" to topGenre?.trim()?.take(48)?.takeIf { it.isNotEmpty() },
                    "topArtists" to topArtists,
                    "viewerUids" to viewers,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ).filterValues { it != null }
                batch.set(cardRef, createMap)
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "ownerName" to profile.displayName.take(40),
                    "handle" to profile.handle,
                    "persona" to stats.persona.take(40).ifBlank { "The listener" },
                    "hoursLabel" to formatHours(stats.minutes).take(32),
                    "plays" to stats.plays.coerceAtLeast(0),
                    "artistCount" to stats.artistCount.coerceAtLeast(0),
                    "bestStreak" to stats.bestStreak.coerceAtLeast(0),
                    "topArtists" to topArtists,
                    "viewerUids" to viewers,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                val dcUser = profile.discordUsername?.take(32)
                if (dcUser != null) updateMap["discordUsername"] = dcUser
                else if (existing.contains("discordUsername")) updateMap["discordUsername"] = FieldValue.delete()
                stats.sinceLabel?.take(40)?.let { updateMap["sinceLabel"] = it }
                topSongTitle?.let { updateMap["topSongTitle"] = it }
                topSongArtist?.let { updateMap["topSongArtist"] = it }
                topGenre?.trim()?.take(48)?.takeIf { it.isNotEmpty() }?.let { updateMap["topGenre"] = it }
                batch.update(cardRef, updateMap)
            }
            batch.update(
                userRef,
                mapOf(
                    "cardShared" to true,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "listeningCards/$uid")
            Result.failure(e)
        }
    }

    suspend fun unpublishListeningCard(uid: String): Result<Unit> =
        try {
            val currentUid = requireUid()
            check(currentUid == uid)
            val batch = db.batch()
            batch.delete(db.collection("listeningCards").document(uid))
            batch.update(
                db.collection("users").document(uid),
                mapOf(
                    "cardShared" to false,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "listeningCards/$uid")
            Result.failure(e)
        }

    // --- 5. COLLABORATIVE PLAYLISTS & TRACKS ---

    fun observeCollabPlaylists(uid: String): Flow<List<CollabPlaylist>> =
        db.collection("collabPlaylists")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(CollabPlaylist::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }

    fun observeCollabPlaylist(playlistId: String): Flow<CollabPlaylist?> =
        db.collection("collabPlaylists").document(playlistId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) snap.toObject(CollabPlaylist::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                else null
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
        val playlistRef = db.collection("collabPlaylists").document()
        val inviteRef = db.collection("playlistInvites").document(inviteCode)
        val memberTag = CloudValidation.memberTag(profile.displayName, profile.handle)

        return try {
            val batch = db.batch()
            val playlistMap = mutableMapOf<String, Any?>(
                "title" to cleanTitle,
                "description" to cleanDesc,
                "ownerId" to uid,
                "ownerName" to profile.displayName.take(40),
                "inviteCode" to inviteCode,
                "memberUids" to listOf(uid),
                "memberNames" to listOf(memberTag),
                "trackCount" to 0,
                "trackDocIds" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null }
            batch.set(playlistRef, playlistMap)
            batch.set(
                inviteRef,
                mapOf(
                    "playlistId" to playlistRef.id,
                    "ownerId" to uid,
                    "title" to cleanTitle,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
            Result.success(
                CollabPlaylist(
                    id = playlistRef.id,
                    title = cleanTitle,
                    description = cleanDesc,
                    ownerId = uid,
                    ownerName = profile.displayName.take(40),
                    inviteCode = inviteCode,
                    memberUids = listOf(uid),
                    memberNames = listOf(memberTag),
                    trackCount = 0,
                    trackDocIds = emptyList(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "collabPlaylists/${playlistRef.id}")
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
            val inviteSnap = db.collection("playlistInvites").document(code).get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(NoSuchElementException("No collaborative playlist found for code $code."))
            }
            val playlistId = inviteSnap.getString("playlistId")?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Invite code is incomplete."))

            val playlistRef = db.collection("collabPlaylists").document(playlistId)
            val alreadyMember = try {
                val existing = playlistRef.get().await()
                existing.exists()
            } catch (_: FirebaseFirestoreException) {
                false
            }

            if (!alreadyMember) {
                val memberTag = CloudValidation.memberTag(profile.displayName, profile.handle)
                playlistRef.update(
                    mapOf(
                        "memberUids" to FieldValue.arrayUnion(uid),
                        "memberNames" to FieldValue.arrayUnion(memberTag),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            }

            // Now a member: fetch playlist and synchronize trackDocIds so existing songs appear in query
            val joined = playlistRef.get().await()
                .toObject(CollabPlaylist::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            if (joined != null && joined.trackDocIds.isNotEmpty()) {
                syncTrackMembership(playlistId, joined.trackDocIds, joined.memberUids)
            }
            Result.success(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "playlistInvites/$code")
            Result.failure(e)
        }
    }

    suspend fun syncTrackMembership(
        playlistId: String,
        trackDocIds: List<String>,
        memberUids: List<String>,
    ) {
        val tracksCol = db.collection("collabPlaylists").document(playlistId).collection("tracks")
        for (docId in trackDocIds.take(200)) {
            try {
                tracksCol.document(docId).update("memberUids", memberUids).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort sync for deleted or already-synced docs
            }
        }
    }

    suspend fun leaveCollabPlaylist(profile: UserProfile, playlistId: String): Result<Unit> =
        try {
            val uid = requireUid()
            val playlistRef = db.collection("collabPlaylists").document(playlistId)
            val snap = playlistRef.get().await()
            val playlist = snap.toObject(CollabPlaylist::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(NoSuchElementException("Playlist not found."))
            if (playlist.ownerId == uid) {
                return Result.failure(IllegalStateException("Owners cannot leave their own playlist. Delete it instead."))
            }
            val nextUids = playlist.memberUids.filter { it != uid }
            val myTag = CloudValidation.memberTag(profile.displayName, profile.handle)
            val nextNames = playlist.memberNames
                .filterNot { it == myTag || it.endsWith("(@${profile.handle})") }
                .ifEmpty { playlist.memberNames.take(1) }
            playlistRef.update(
                mapOf(
                    "memberUids" to nextUids,
                    "memberNames" to nextNames,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "collabPlaylists/$playlistId")
            Result.failure(e)
        }

    suspend fun renameCollabPlaylist(playlist: CollabPlaylist, newTitle: String, description: String? = playlist.description): Result<Unit> {
        val cleanTitle = newTitle.trim().take(80)
        if (cleanTitle.isEmpty()) return Result.failure(IllegalArgumentException("Title cannot be empty."))
        return try {
            requireUid()
            val batch = db.batch()
            val updateMap = mutableMapOf<String, Any>(
                "title" to cleanTitle,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            description?.trim()?.take(240)?.takeIf { it.isNotEmpty() }?.let { updateMap["description"] = it }
            batch.update(db.collection("collabPlaylists").document(playlist.id), updateMap)
            batch.update(
                db.collection("playlistInvites").document(playlist.inviteCode),
                mapOf(
                    "title" to cleanTitle,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "collabPlaylists/${playlist.id}")
            Result.failure(e)
        }
    }

    suspend fun deleteCollabPlaylist(playlist: CollabPlaylist): Result<Unit> =
        try {
            val uid = requireUid()
            check(playlist.ownerId == uid) { "Only the playlist creator can delete it." }
            val tracksSnap = db.collection("collabPlaylists").document(playlist.id)
                .collection("tracks")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            val batch = db.batch()
            tracksSnap.documents.forEach { batch.delete(it.reference) }
            batch.delete(db.collection("playlistInvites").document(playlist.inviteCode))
            batch.delete(db.collection("collabPlaylists").document(playlist.id))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "collabPlaylists/${playlist.id}")
            Result.failure(e)
        }

    fun observeCollabTracks(playlistId: String, uid: String): Flow<List<CollabTrack>> =
        db.collection("collabPlaylists").document(playlistId).collection("tracks")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(CollabTrack::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.createdAt ?: Timestamp(0, 0) }
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
            val playlistRef = db.collection("collabPlaylists").document(playlistId)
            val latestSnap = playlistRef.get().await()
            val latest = latestSnap.toObject(CollabPlaylist::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(NoSuchElementException("Collaborative playlist no longer exists."))
            if (latest.trackDocIds.size >= 200) {
                return Result.failure(IllegalStateException("Collaborative playlists hold up to 200 songs."))
            }

            val trackRef = playlistRef.collection("tracks").document()
            val artworkUrl = track.artwork.url(600)?.take(500)?.takeIf { it.isNotBlank() }
            val trackMap = mutableMapOf<String, Any?>(
                "playlistId" to playlistId,
                "memberUids" to latest.memberUids,
                "mediaId" to track.id.value.take(200),
                "title" to track.title.trim().take(160).ifEmpty { "Untitled track" },
                "artistLine" to track.artistLine.trim().take(160),
                "albumTitle" to track.album?.title?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
                "artworkUrl" to artworkUrl,
                "durationMs" to track.durationMs?.takeIf { it >= 0L },
                "addedByUid" to uid,
                "addedByName" to profile.displayName.trim().take(40).ifBlank { "Listener" },
                "createdAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null }

            val nextTrackIds = (latest.trackDocIds + trackRef.id).distinct().take(200)
            val summaryUpdate = mutableMapOf<String, Any>(
                "trackCount" to nextTrackIds.size,
                "trackDocIds" to nextTrackIds,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (latest.coverUrl == null && artworkUrl != null) {
                summaryUpdate["coverUrl"] = artworkUrl
            }

            val batch = db.batch()
            batch.set(trackRef, trackMap)
            batch.update(playlistRef, summaryUpdate)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "collabPlaylists/$playlistId/tracks")
            Result.failure(e)
        }
    }

    suspend fun removeTrackFromCollabPlaylist(
        playlist: CollabPlaylist,
        trackDocId: String,
    ): Result<Unit> =
        try {
            requireUid()
            val playlistRef = db.collection("collabPlaylists").document(playlist.id)
            val trackRef = playlistRef.collection("tracks").document(trackDocId)
            val nextIds = playlist.trackDocIds.filter { it != trackDocId }
            val batch = db.batch()
            batch.delete(trackRef)
            batch.update(
                playlistRef,
                mapOf(
                    "trackCount" to nextIds.size,
                    "trackDocIds" to nextIds,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "collabPlaylists/${playlist.id}/tracks/$trackDocId")
            Result.failure(e)
        }
}
