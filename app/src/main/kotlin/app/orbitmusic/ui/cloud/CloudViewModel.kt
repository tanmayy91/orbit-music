package app.orbitmusic.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.orbitmusic.cloud.CloudRepository
import app.orbitmusic.cloud.CollabPlaylist
import app.orbitmusic.cloud.Friendship
import app.orbitmusic.cloud.ListeningCardSnapshot
import app.orbitmusic.cloud.OperationType
import app.orbitmusic.cloud.UiState
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.cloud.asUiState
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.ui.profile.ProfileStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CloudViewModel(
    private val repository: CloudRepository,
    val currentUserId: String,
) : ViewModel() {

    val profileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId)
            .asUiState(OperationType.GET, "users/$currentUserId", "Failed to load Orbit profile.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val friendshipsState: StateFlow<UiState<List<Friendship>>> =
        repository.observeFriendships(currentUserId)
            .asUiState(OperationType.LIST, "friendships", "Failed to load friends.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val cardsState: StateFlow<UiState<List<ListeningCardSnapshot>>> =
        repository.observeVisibleListeningCards(currentUserId)
            .asUiState(OperationType.LIST, "listeningCards", "Failed to load shared listening cards.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val playlistsState: StateFlow<UiState<List<CollabPlaylist>>> =
        repository.observeCollabPlaylists(currentUserId)
            .asUiState(OperationType.LIST, "collabPlaylists", "Failed to load collaborative playlists.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _feedback = MutableStateFlow<Pair<Boolean, String>?>(null)
    val feedback: StateFlow<Pair<Boolean, String>?> = _feedback.asStateFlow()

    fun clearFeedback() {
        _feedback.value = null
    }

    private inline fun runAction(
        successMessage: String? = null,
        crossinline block: suspend () -> Result<Unit>,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            val res = block()
            _busy.value = false
            res.fold(
                onSuccess = {
                    if (successMessage != null) _feedback.value = false to successMessage
                },
                onFailure = { e ->
                    _feedback.value = true to (e.localizedMessage ?: "Couldn\u2019t complete action.")
                },
            )
        }
    }

    fun registerOrUpdateProfile(
        displayName: String,
        rawHandle: String,
        discordUser: DiscordUser?,
        arySyncEnabled: Boolean,
        cardShared: Boolean,
        successMessage: String = "Profile saved.",
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            val res = repository.registerOrUpdateProfile(
                displayName = displayName,
                rawHandle = rawHandle,
                discordUser = discordUser,
                arySyncEnabled = arySyncEnabled,
                cardShared = cardShared,
            )
            _busy.value = false
            res.fold(
                onSuccess = { _feedback.value = false to successMessage },
                onFailure = { e ->
                    _feedback.value = true to (e.localizedMessage ?: "Couldn\u2019t save profile.")
                },
            )
        }
    }

    fun setArySyncEnabled(enabled: Boolean) = runAction(
        if (enabled) "Ary conversation sync turned on." else "Ary conversation sync paused.",
    ) {
        repository.setArySyncEnabled(currentUserId, enabled)
    }

    fun sendFriendRequest(profile: UserProfile, query: String, onSent: () -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            val target = repository.resolveRecipient(query)
            val res = target.fold(
                onSuccess = { entry -> repository.sendFriendRequest(profile, entry) },
                onFailure = { e -> Result.failure(e) },
            )
            _busy.value = false
            res.fold(
                onSuccess = {
                    onSent()
                    _feedback.value = false to "Friend request sent."
                },
                onFailure = { e ->
                    _feedback.value = true to (e.localizedMessage ?: "Couldn\u2019t send request.")
                },
            )
        }
    }

    fun acceptFriendRequest(friendship: Friendship) = runAction("Friend request accepted.") {
        repository.acceptFriendRequest(friendship)
    }

    fun removeFriendship(friendshipId: String) = runAction("Removed connection.") {
        repository.removeFriendship(friendshipId)
    }

    fun syncFriendCards(friendUids: List<String>) {
        if (friendUids.isEmpty()) return
        viewModelScope.launch {
            repository.syncFriendListeningCards(currentUserId, friendUids)
        }
    }

    fun publishListeningCard(
        profile: UserProfile,
        stats: ProfileStats,
        topGenre: String?,
        acceptedFriendUids: List<String>,
    ) = runAction("Listening card shared with friends.") {
        repository.publishListeningCard(profile, stats, topGenre, acceptedFriendUids)
    }

    fun unpublishListeningCard() = runAction("Listening card hidden from friends.") {
        repository.unpublishListeningCard(currentUserId)
    }

    fun createCollabPlaylist(
        profile: UserProfile,
        title: String,
        description: String?,
        onCreated: (CollabPlaylist) -> Unit,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            val res = repository.createCollabPlaylist(profile, title, description)
            _busy.value = false
            res.fold(
                onSuccess = { created ->
                    _feedback.value = false to "Created \u201c${created.title}\u201d"
                    onCreated(created)
                },
                onFailure = { e ->
                    _feedback.value = true to (e.localizedMessage ?: "Couldn\u2019t create collaborative playlist.")
                },
            )
        }
    }

    fun joinCollabPlaylist(
        profile: UserProfile,
        rawCode: String,
        onJoined: (String) -> Unit,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            val res = repository.joinCollabPlaylistByCode(profile, rawCode)
            _busy.value = false
            res.fold(
                onSuccess = { playlistId ->
                    _feedback.value = false to "Joined collaborative playlist."
                    onJoined(playlistId)
                },
                onFailure = { e ->
                    _feedback.value = true to (e.localizedMessage ?: "Couldn\u2019t join with that code.")
                },
            )
        }
    }

    class Factory(
        private val repository: CloudRepository,
        private val currentUserId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CloudViewModel(repository, currentUserId) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
