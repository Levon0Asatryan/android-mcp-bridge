package dev.healthbridge.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A browser OAuth request the instance asks the owner to approve. Contains no secrets or health data. */
data class LinkRequest(
    val linkId: String, val code: String, val binding: String, val oauthClientId: String,
    val redirectHost: String, val scopes: Set<String>, val expiresAt: Long,
)
data class LinkDecision(val approved: Boolean, val scopes: Set<String> = emptySet())

/** One pending link prompt at a time; anything else, an expired prompt or a dismissed prompt is a denial. */
class LinkApproval(private val onPending: () -> Unit = {}) {
    private val mutable = MutableStateFlow<LinkRequest?>(null)
    val pending = mutable.asStateFlow()
    private var answer: CompletableDeferred<LinkDecision>? = null

    suspend fun request(request: LinkRequest, now: Long = System.currentTimeMillis()): LinkDecision {
        val deferred = CompletableDeferred<LinkDecision>()
        synchronized(this) {
            if (mutable.value != null) return LinkDecision(false)
            answer = deferred
            mutable.value = request
        }
        runCatching(onPending)
        try {
            val decision = withTimeoutOrNull((request.expiresAt - now).coerceIn(0, 120_000)) { deferred.await() } ?: LinkDecision(false)
            // Never grant more than the browser request asked for.
            return if (decision.approved && decision.scopes.isNotEmpty() && request.scopes.containsAll(decision.scopes)) decision else LinkDecision(false)
        } finally {
            synchronized(this) { if (mutable.value?.linkId == request.linkId) { mutable.value = null; answer = null } }
        }
    }
    @Synchronized fun respond(linkId: String, decision: LinkDecision) {
        if (mutable.value?.linkId == linkId) answer?.complete(decision)
    }
    @Synchronized fun invalidate() { answer?.complete(LinkDecision(false)) }
}
