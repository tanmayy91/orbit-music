package app.orbitmusic.cloud

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleCloudError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    uid: String? = null,
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it !is CancellationException } ?: exception

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        if (!uid.isNullOrBlank()) put("discordUid", uid)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("SupabaseCloudError", "Cloud Error: $jsonString")
    return jsonString
}

fun <T> Flow<T>.asUiState(
    operationType: OperationType,
    path: String,
    fallbackMessage: String,
): Flow<UiState<T>> =
    map<T, UiState<T>> { UiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            handleCloudError(error, operationType, path)
            emit(UiState.Error(error.localizedMessage ?: fallbackMessage))
        }
