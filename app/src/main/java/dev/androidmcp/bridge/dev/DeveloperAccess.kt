package dev.androidmcp.bridge.dev

import dev.androidmcp.bridge.api.ActionModule
import dev.androidmcp.bridge.api.ModuleException
import dev.androidmcp.bridge.api.obj
import dev.androidmcp.bridge.api.text
import dev.androidmcp.bridge.security.ClientPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** A time-boxed unlock of developer access. Only the owner, after device authentication, opens it; it never extends itself. */
class DeveloperSession(private val now: () -> Long = System::currentTimeMillis, val windowMillis: Long = 10 * 60_000L) {
    private val until = MutableStateFlow(0L)
    val unlockedUntil: StateFlow<Long> = until.asStateFlow()
    fun unlock() { until.value = now() + windowMillis }
    fun lock() { until.value = 0L }
    fun remainingMillis() = (until.value - now()).coerceAtLeast(0)
    fun isUnlocked() = remainingMillis() > 0
}

class ShellResult(val exitCode: Int?, val output: String, val truncated: Boolean, val timedOut: Boolean)

interface ShellExecutor {
    /** Runs [command] with the privileges of Android's debug shell. Throws [ModuleException] when the debug connection is unavailable. */
    suspend fun exec(command: String, timeoutSeconds: Int): ShellResult
    fun connected(): Boolean
}

const val MAX_COMMAND_CHARS = 2_000
const val MAX_SHELL_OUTPUT_CHARS = 30_000

class DeveloperModule(
    private val session: DeveloperSession,
    private val shell: ShellExecutor,
    private val onLocked: () -> Unit = {},
    private val onCommand: suspend (String) -> Unit = {},
) : ActionModule {
    override val id = "dev"
    override val title = "Developer access"
    override val operations: Map<String, ClientPermission?> = mapOf(
        "run_shell" to ClientPermission.DEV_SHELL, "get_developer_status" to ClientPermission.DEV_SHELL)
    override val idempotent = emptySet<String>()

    override suspend fun run(operation: String, args: JsonObject): JsonObject = when (operation) {
        "get_developer_status" -> {
            require(args.isEmpty())
            obj("unlocked" to JsonPrimitive(session.isUnlocked()), "seconds_remaining" to JsonPrimitive(session.remainingMillis() / 1000),
                "adb_connected" to JsonPrimitive(shell.connected()),
                "note" to text("The owner unlocks developer access on the phone with biometrics for 10 minutes at a time."))
        }
        "run_shell" -> {
            require(args.keys.all { it == "command" || it == "timeout_seconds" }) { "Unexpected field." }
            val command = (args["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(command != null && command.isNotBlank() && command.length <= MAX_COMMAND_CHARS) { "command must contain 1-$MAX_COMMAND_CHARS characters." }
            val timeout = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 20
            require(timeout in 1..60) { "timeout_seconds must be 1-60." }
            if (!session.isUnlocked()) {
                onLocked()
                throw ModuleException("approval_required", "Developer access is locked. The owner must unlock it on the phone (biometric); it then stays open for 10 minutes.")
            }
            onCommand(command)
            val result = shell.exec(command, timeout)
            obj("success" to JsonPrimitive(true), "exit_code" to (result.exitCode?.let { JsonPrimitive(it) } ?: JsonNull),
                "output" to text(result.output), "truncated" to JsonPrimitive(result.truncated), "timed_out" to JsonPrimitive(result.timedOut),
                "seconds_remaining" to JsonPrimitive(session.remainingMillis() / 1000))
        }
        else -> error("Unsupported operation.")
    }
}
