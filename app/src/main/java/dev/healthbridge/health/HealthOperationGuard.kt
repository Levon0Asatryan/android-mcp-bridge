package dev.healthbridge.health

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries remote-request policy through the repository's suspending permission and ownership checks. */
class HealthOperationGuard(val check: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HealthOperationGuard>
}

suspend fun checkHealthOperation() {
    val context = currentCoroutineContext()
    context.ensureActive()
    context[HealthOperationGuard]?.check?.invoke()
}
