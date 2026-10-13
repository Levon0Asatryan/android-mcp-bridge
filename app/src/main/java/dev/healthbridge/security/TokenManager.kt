package dev.healthbridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

interface CredentialProtector { fun digest(value: String): String }

class KeystoreProtector : CredentialProtector {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("healthbridge.credentials.v1", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder("healthbridge.credentials.v1", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKey()
            }
    }
    override fun digest(value: String): String =
        Mac.getInstance("HmacSHA256").run { init(key); doFinal(value.toByteArray(Charsets.UTF_8)).toHex() }
}

class TokenManager(private val protector: CredentialProtector) {
    private val random = SecureRandom()
    fun generate(clientId: String): String {
        val secret = ByteArray(32).also(random::nextBytes)
        return "hb.$clientId.${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"
    }
    fun digest(value: String) = protector.digest(value)
    fun matches(token: String, verifier: String) = MessageDigest.isEqual(
        digest(token).toByteArray(Charsets.US_ASCII), verifier.toByteArray(Charsets.US_ASCII))
    fun clientId(token: String): String? {
        if (token.length !in 70..160) return null
        val parts = token.split('.')
        return parts.getOrNull(1)?.takeIf {
            parts.size == 3 && parts[0] == "hb" && it.matches(Regex("[0-9a-f-]{36}")) &&
                parts[2].matches(Regex("[A-Za-z0-9_-]{43}"))
        }
    }
}
fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
