package dev.androidmcp.bridge.dev

import android.content.Context
import android.os.Build
import dev.androidmcp.bridge.api.ModuleException
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.sun.security.x509.*
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** ADB client that talks to this phone's own Wireless debugging daemon, the same channel `adb shell` uses over USB. */
class AdbShell(private val context: Context) : ShellExecutor {
    private val manager = object : AbsAdbConnectionManager() {
        private val keyFile = File(context.noBackupFilesDir, "adb_key.pk8")
        private val certFile = File(context.noBackupFilesDir, "adb_cert.der")
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val name = X500Name("CN=Android MCP Bridge")
                val from = Date(); val to = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)
                val info = X509CertInfo().apply {
                    set("version", CertificateVersion(2))
                    set("serialNumber", CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
                    set("algorithmID", CertificateAlgorithmId(AlgorithmId.get("SHA512withRSA")))
                    set("subject", CertificateSubjectName(name)); set("issuer", CertificateIssuerName(name))
                    set("key", CertificateX509Key(pair.public)); set("validity", CertificateValidity(from, to))
                }
                val signed = X509CertImpl(info).also { it.sign(pair.private, "SHA512withRSA") }
                key = pair.private; cert = signed
                keyFile.writeBytes(pair.private.encoded); certFile.writeBytes(signed.encoded)
            }
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "AndroidMcpBridge"
    }

    override fun connected() = manager.isConnected

    /** One-time pairing with the code shown under Developer options > Wireless debugging > Pair device with pairing code. */
    suspend fun pair(port: Int, code: String): Boolean = withContext(Dispatchers.IO) { manager.pair("127.0.0.1", port, code) }

    private suspend fun ensureConnected() = withContext(Dispatchers.IO) {
        if (manager.isConnected) return@withContext
        val ok = try { manager.autoConnect(context, 8_000) } catch (e: Exception) { false }
        if (!ok) throw ModuleException("phone_unavailable",
            "Could not reach the phone's debug daemon. On the phone enable Developer options > Wireless debugging (Wi-Fi required) and pair it in the app.")
    }

    override suspend fun exec(command: String, timeoutSeconds: Int): ShellResult {
        ensureConnected()
        return withContext(Dispatchers.IO) {
            val marker = "__amb_exit_" + java.util.UUID.randomUUID().toString().replace("-", "") + "="
            val head = StringBuilder()
            val tail = StringBuilder()
            var truncated = false
            val finished = withTimeoutOrNull(timeoutSeconds * 1000L) {
                manager.openStream("shell:($command) 2>&1; echo \"\n$marker\$?\"").use { stream ->
                    stream.openInputStream().bufferedReader().use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            val room = (MAX_SHELL_OUTPUT_CHARS - head.length).coerceAtLeast(0)
                            head.appendRange(buffer, 0, minOf(n, room))
                            if (n > room) truncated = true
                            tail.appendRange(buffer, 0, n)
                            if (tail.length > 256) tail.delete(0, tail.length - 256)
                        }
                    }
                }
                true
            } == true
            val source = if (truncated) tail.toString() else head.toString()
            val at = source.lastIndexOf("\n$marker")
            val exit = if (finished && at >= 0) source.substring(at + marker.length + 1).trim().toIntOrNull() else null
            val output = if (truncated) head.toString() else if (at >= 0) head.substring(0, at) else head.toString()
            ShellResult(exit, output, truncated, !finished)
        }
    }
}
