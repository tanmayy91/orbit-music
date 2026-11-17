package app.orbitmusic.cloud

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.orbitmusic.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "CloudAuthManager"

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class CloudAuthManager(
    private val appContext: Context,
    val auth: FirebaseAuth = Firebase.auth,
) {
    val authState: Flow<FirebaseUser?> = auth.authStateFlow()

    val currentUser: FirebaseUser? get() = auth.currentUser

    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> {
        return try {
            val webClientId = appContext.getString(R.string.default_web_client_id).trim()
            if (webClientId.isEmpty()) {
                return Result.failure(IllegalStateException("Google Sign-In client ID is not configured."))
            }

            val credentialManager = CredentialManager.create(activityContext)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val response = credentialManager.getCredential(
                request = request,
                context = activityContext,
            )

            val credential = response.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.failure(IllegalStateException("Unexpected credential type returned."))
            }

            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCred).await()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("Google Sign-In completed without an active user."))
            Result.success(user)
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "User cancelled Google Sign-In", e)
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager sign-in failed: ${e.message}", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In error", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        withContext(NonCancellable) {
            runCatching {
                CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { e ->
                Log.w(TAG, "Failed to clear Credential Manager state", e)
            }
            auth.signOut()
        }
    }
}
