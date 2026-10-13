package dev.healthbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.healthbridge.HealthBridgeApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiState(val busy: Boolean = false, val error: String? = null, val granted: Int = 0)

class UiModel(application: Application) : AndroidViewModel(application) {
    val app = application as HealthBridgeApplication
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val shared = MutableStateFlow<String?>(null)
    val sharedText = shared.asStateFlow()
    fun acceptSharedText(text: String) {
        if (text.length > 32768) mutable.update { it.copy(error = "Shared text exceeds 32 KB. Share only the nutrition JSON.") }
        else shared.value = text
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }
    fun run(work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { work() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(error = when (e) {
                    is dev.healthbridge.relay.RelayException -> e.message ?: "The instance request failed."
                    is SecurityException -> "Health permission is missing or revoked. Open system health permissions."
                    is IllegalArgumentException -> e.message?.takeIf { it.isNotBlank() } ?: "Invalid input. Check the JSON fields, timestamps and measurements."
                    else -> "Operation failed (${e.javaClass.simpleName}). A successful write is not being claimed. Retry uncertain writes only with the same request."
                }) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() {
        viewModelScope.launch {
            try {
                val granted = withContext(Dispatchers.IO) { app.health.grantedPermissions().size }
                mutable.update { it.copy(granted = granted) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = "Could not check health permissions (${e.javaClass.simpleName}).") } }
        }
    }
}
