package app.orbitmusic.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.orbitmusic.cloud.CloudRepository
import app.orbitmusic.cloud.CollabPlaylist
import app.orbitmusic.cloud.CollabTrack
import app.orbitmusic.cloud.OperationType
import app.orbitmusic.cloud.UiState
import app.orbitmusic.cloud.UserProfile
import app.orbitmusic.cloud.asUiState
import app.orbitmusic.core.data.SourceManager
import app.orbitmusic.core.model.Outcome
import app.orbitmusic.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollabPlaylistViewModel(
    private val repository: CloudRepository,
    private val sources: SourceManager,
    val currentUserId: String,
    val playlistId: String,
) : ViewModel() {

    val playlistState: StateFlow<UiState<CollabPlaylist?>> =
        repository.observeCollabPlaylist(playlistId)
            .asUiState(OperationType.GET, "collabPlaylists/$playlistId", "Couldn\u2019t open playlist.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val tracksState: StateFlow<UiState<List<CollabTrack>>> =
        repository.observeCollabTracks(playlistId, currentUserId)
            .asUiState(OperationType.LIST, "collabPlaylists/$playlistId/tracks", "Couldn\u2019t load playlist tracks.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val profileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId)
            .asUiState(OperationType.GET, "users/$currentUserId", "Couldn\u2019t load profile.")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun ensureTracksSynced(playlist: CollabPlaylist, visibleTracks: List<CollabTrack>) {
        if (playlist.trackDocIds.size > visibleTracks.size) {
            val missing = playlist.trackDocIds.filter { id -> visibleTracks.none { it.id == id } }
            if (missing.isNotEmpty()) {
                viewModelScope.launch {
                    repository.syncTrackMembership(playlistId, missing, playlist.memberUids)
                }
            }
        }
    }

    fun searchSongs(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searching.value = true
            try {
                val primary = (sources.primary.search(clean) as? Outcome.Ok)
                    ?.value?.items?.filterIsInstance<Track>().orEmpty()
                val local = sources.of("local")?.let { src ->
                    (src.search(clean) as? Outcome.Ok)?.value?.items?.filterIsInstance<Track>().orEmpty()
                }.orEmpty()
                _searchResults.value = (primary + local).distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _searching.value = false
            }
        }
    }

    fun addTrack(profile: UserProfile, track: Track, onAdded: () -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = repository.addTrackToCollabPlaylist(profile, playlistId, track)
            _busy.value = false
            res.fold(
                onSuccess = {
                    _message.value = "Added \u201c${track.title}\u201d"
                    onAdded()
                },
                onFailure = { e ->
                    _message.value = e.localizedMessage ?: "Couldn\u2019t add track."
                },
            )
        }
    }

    fun removeTrack(playlist: CollabPlaylist, trackDocId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = repository.removeTrackFromCollabPlaylist(playlist, trackDocId)
            _busy.value = false
            res.onFailure { e ->
                _message.value = e.localizedMessage ?: "Couldn\u2019t remove track."
            }
        }
    }

    fun renamePlaylist(playlist: CollabPlaylist, newTitle: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = repository.renameCollabPlaylist(playlist, newTitle)
            _busy.value = false
            res.onFailure { e ->
                _message.value = e.localizedMessage ?: "Couldn\u2019t rename playlist."
            }
        }
    }

    fun leavePlaylist(profile: UserProfile, onLeft: () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = repository.leaveCollabPlaylist(profile, playlistId)
            _busy.value = false
            res.fold(
                onSuccess = { onLeft() },
                onFailure = { e -> _message.value = e.localizedMessage ?: "Couldn\u2019t leave playlist." },
            )
        }
    }

    fun deletePlaylist(playlist: CollabPlaylist, onDeleted: () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val res = repository.deleteCollabPlaylist(playlist)
            _busy.value = false
            res.fold(
                onSuccess = { onDeleted() },
                onFailure = { e -> _message.value = e.localizedMessage ?: "Couldn\u2019t delete playlist." },
            )
        }
    }

    class Factory(
        private val repository: CloudRepository,
        private val sources: SourceManager,
        private val currentUserId: String,
        private val playlistId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CollabPlaylistViewModel(repository, sources, currentUserId, playlistId) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
