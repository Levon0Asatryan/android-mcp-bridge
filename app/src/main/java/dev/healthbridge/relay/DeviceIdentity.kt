package dev.healthbridge.relay

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Signs device proofs. Implementations return the standard DER ECDSA signature produced by SHA256withECDSA. */
interface DeviceSigner {
    val publicKey: ECPublicKey
    fun signDer(data: ByteArray): ByteArray
}

class JcaDeviceSigner(private val privateKey: PrivateKey, override val publicKey: ECPublicKey) : DeviceSigner {
    override fun signDer(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(data); sign() }
}

/** Non-exportable P-256 device key held by Android Keystore. Only the public key ever leaves the phone. */
object KeystoreDeviceIdentity {
    private const val ALIAS = "healthbridge.device.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = keyStore().containsAlias(ALIAS)

    fun getOrCreate(): DeviceSigner {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) generate()
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return JcaDeviceSigner(entry.privateKey, entry.certificate.publicKey as ECPublicKey)
    }

    fun delete() { keyStore().takeIf { it.containsAlias(ALIAS) }?.deleteEntry(ALIAS) }

    /** Honest description of where the key lives, from KeyInfo. */
    fun securityLevel(): String = runCatching {
        val key = (keyStore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted execution environment"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
            else -> "Unknown"
        } else @Suppress("DEPRECATION") if (info.isInsideSecureHardware) "Secure hardware" else "Software"
    }.getOrDefault("Unavailable")

    private fun generate() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            generator.initialize(spec(strongBox = true)); generator.generateKeyPair()
        } catch (e: Exception) {
            // StrongBox may be absent (StrongBoxUnavailableException) or fail with a generic ProviderException.
            generator.initialize(spec(strongBox = false)); generator.generateKeyPair()
        }
    }
}

/** RFC 7515 compact JWS with ES256 (RFC 7518 §3.4 raw r||s signature) and RFC 7638 thumbprints. */
object DeviceJws {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val random = SecureRandom()
    fun b64url(bytes: ByteArray): String = b64.encodeToString(bytes)

    private fun coordinate(value: BigInteger): String {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= 32) { "Invalid P-256 coordinate." }
        return b64url(ByteArray(32 - raw.size) + raw)
    }
    fun jwk(key: ECPublicKey) = JsonObject(mapOf("kty" to JsonPrimitive("EC"), "crv" to JsonPrimitive("P-256"),
        "x" to JsonPrimitive(coordinate(key.w.affineX)), "y" to JsonPrimitive(coordinate(key.w.affineY))))
    fun thumbprint(key: ECPublicKey): String {
        val x = coordinate(key.w.affineX); val y = coordinate(key.w.affineY)
        val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        return b64url(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    /** Converts ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` into the fixed 64-byte JOSE form. */
    fun derToJose(der: ByteArray): ByteArray {
        var i = 0
        fun length(): Int {
            val first = der[i++].toInt() and 0xff
            if (first < 0x80) return first
            val count = first and 0x7f
            require(count in 1..2) { "Unsupported DER length." }
            var value = 0
            repeat(count) { value = (value shl 8) or (der[i++].toInt() and 0xff) }
            return value
        }
        fun integer(): ByteArray {
            require(der[i++] == 0x02.toByte()) { "Expected DER INTEGER." }
            val len = length()
            val bytes = der.copyOfRange(i, i + len).also { i += len }.dropWhile { it == 0.toByte() }.toByteArray()
            require(bytes.size <= 32) { "Invalid ECDSA integer." }
            return ByteArray(32 - bytes.size) + bytes
        }
        require(der[i++] == 0x30.toByte()) { "Expected DER SEQUENCE." }
        val total = length()
        require(i + total == der.size) { "Trailing DER data." }
        val r = integer(); val s = integer()
        require(i == der.size) { "Trailing DER data." }
        return r + s
    }

    fun sign(signer: DeviceSigner, header: JsonObject, claims: JsonObject): String {
        val input = b64url(header.toString().toByteArray(Charsets.UTF_8)) + "." + b64url(claims.toString().toByteArray(Charsets.UTF_8))
        return input + "." + b64url(derToJose(signer.signDer(input.toByteArray(Charsets.US_ASCII))))
    }

    private fun claims(purpose: String, audience: String, nonce: String, nowSeconds: Long, extra: Map<String, JsonPrimitive> = emptyMap()) =
        JsonObject(mapOf("v" to JsonPrimitive(1), "purpose" to JsonPrimitive(purpose), "aud" to JsonPrimitive(audience),
            "nonce" to JsonPrimitive(nonce), "iat" to JsonPrimitive(nowSeconds), "exp" to JsonPrimitive(nowSeconds + 120),
            "jti" to JsonPrimitive(b64url(ByteArray(18).also(random::nextBytes)))) + extra)

    /** Pairing proof embeds the public JWK; the backend pins it on first successful pairing. */
    fun pairingProof(signer: DeviceSigner, origin: String, challenge: String, nowSeconds: Long): String = sign(signer,
        JsonObject(mapOf("alg" to JsonPrimitive("ES256"), "typ" to JsonPrimitive("hb-pair+jwt"), "jwk" to jwk(signer.publicKey))),
        claims("pair", origin, challenge, nowSeconds))

    /** Reconnect proof names the pinned key by thumbprint and is bound to the connect audience and pairing generation. */
    fun connectProof(signer: DeviceSigner, origin: String, nonce: String, pairingGeneration: Int, nowSeconds: Long): String = sign(signer,
        JsonObject(mapOf("alg" to JsonPrimitive("ES256"), "typ" to JsonPrimitive("hb-connect+jwt"), "kid" to JsonPrimitive(thumbprint(signer.publicKey)))),
        claims("connect", "$origin/device/connect", nonce, nowSeconds, mapOf("pairing_generation" to JsonPrimitive(pairingGeneration))))
}
