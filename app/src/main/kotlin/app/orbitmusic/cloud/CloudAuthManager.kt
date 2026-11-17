package app.orbitmusic.cloud

import app.orbitmusic.core.data.DiscordAccountStore
import app.orbitmusic.core.data.DiscordUser
import app.orbitmusic.discord.DiscordRpcClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CloudUser(
    val uid: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String?,
)

private fun DiscordUser.toCloudUser(): CloudUser = CloudUser(
    uid = id,
    displayName = displayName,
    username = username,
    avatarUrl = avatarUrl,
)

/**
 * Authenticates Orbit Cloud & Supabase operations using the listener's Discord account ID.
 * Credentials stay sealed in Android Keystore via [DiscordAccountStore].
 */
class CloudAuthManager(
    private val discordStore: DiscordAccountStore,
    private val discordRpc: DiscordRpcClient,
) {
    val authState: Flow<CloudUser?> = discordStore.user.map { it?.toCloudUser() }

    val currentUser: CloudUser?
        get() = discordStore.user.value?.toCloudUser()

    suspend fun signInWithToken(rawToken: String): Result<CloudUser> =
        discordRpc.verifyAndSaveToken(rawToken).map { it.toCloudUser() }

    fun signOut() {
        discordRpc.signOut()
    }
}
