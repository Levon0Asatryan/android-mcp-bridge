package dev.healthbridge.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Only destructive operations request phone approval; authorized adds run without a prompt. */
data class ApprovalRequest(val id: String, val clientName: String, val recordType: String, val recordId: String)
class ApprovalDeniedException : SecurityException("The operation was not approved on the phone.")
class ApprovalBusyException : IllegalStateException("Another deletion is already awaiting confirmation.")

class PhoneApproval {
    companion object { const val MAX_WAIT_MILLIS = 20_000L }
    private val mutable = MutableStateFlow<ApprovalRequest?>(null)
    val pending = mutable.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null

    /** Cancellation of the caller (relay disconnect, backend cancel, deadline) removes the prompt immediately. */
    suspend fun request(clientName: String, recordType: String, recordId: String, timeoutMillis: Long = MAX_WAIT_MILLIS) {
        val request = ApprovalRequest(UUID.randomUUID().toString(), clientName, recordType, recordId)
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (mutable.value != null) throw ApprovalBusyException()
            answer = deferred
            mutable.value = request
        }
        try {
            val wait = timeoutMillis.coerceIn(0, MAX_WAIT_MILLIS)
            if (withTimeoutOrNull(wait) { deferred.await() } != true) throw ApprovalDeniedException()
        } finally {
            synchronized(this) {
                if (mutable.value?.id == request.id) { mutable.value = null; answer = null }
            }
        }
    }
    @Synchronized fun respond(id: String, approved: Boolean) {
        if (mutable.value?.id == id) answer?.complete(approved)
    }
    @Synchronized fun invalidate() {
        answer?.complete(false)
    }
}
