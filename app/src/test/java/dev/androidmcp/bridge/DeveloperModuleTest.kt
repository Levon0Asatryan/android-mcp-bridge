package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.dev.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FakeShell : ShellExecutor {
    val commands = mutableListOf<String>()
    override suspend fun exec(command: String, timeoutSeconds: Int): ShellResult { commands += command; return ShellResult(0, "ok", false, false) }
    override fun connected() = true
}

class DeveloperModuleTest {
    private var now = 1_000_000L
    private val session = DeveloperSession({ now })
    private val shell = FakeShell()
    private var alerts = 0
    private val logged = mutableListOf<String>()
    private val module = DeveloperModule(session, shell, { alerts++ }, { logged += it })

    @Test fun lockedByDefaultAndNotifiesOwner() = runTest {
        val e = runCatching { module.run("run_shell", obj("command" to text("id"))) }.exceptionOrNull() as ModuleException
        assertEquals("approval_required", e.category)
        assertEquals(1, alerts)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun unlockedRunsAndLogsThenExpiresAfterTenMinutes() = runTest {
        session.unlock()
        val out = module.run("run_shell", obj("command" to text("id")))
        assertEquals("ok", out["output"]!!.jsonPrimitive.content)
        assertEquals(listOf("id"), logged)
        now += 10 * 60_000L
        assertFalse(session.isUnlocked())
        assertEquals("approval_required", (runCatching { module.run("run_shell", obj("command" to text("id"))) }.exceptionOrNull() as ModuleException).category)
    }

    @Test fun lockNowAndInputValidation() = runTest {
        session.unlock(); session.lock()
        assertFalse(session.isUnlocked())
        session.unlock()
        assertTrue(runCatching { module.run("run_shell", obj("command" to text(""))) }.isFailure)
        assertTrue(runCatching { module.run("run_shell", obj("command" to text("id"), "timeout_seconds" to JsonPrimitive(61))) }.isFailure)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun statusReportsRemainingTime() = runTest {
        session.unlock(); now += 60_000
        assertEquals(540L, module.run("get_developer_status", obj())["seconds_remaining"]!!.jsonPrimitive.long)
    }
}
