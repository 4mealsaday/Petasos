package com.petasos.app.generation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, backup-excluded, atomic ciphertext. No intent, exported component or APK token. */
class DeviceGenerationConfigStore internal constructor(context: Context, private val namespace: String) :
    GenerationConfigurationSource, GenerationInputHasher {
    constructor(context: Context) : this(context, "petasos-generation-v1")
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.bin"))
    private val aesAlias = "com.petasos.app.$namespace.aes"
    private val hmacAlias = "com.petasos.app.$namespace.hmac"
    private val aad = "Petasos.GenerationConfiguration.v1".toByteArray(Charsets.UTF_8)

    @Synchronized override fun load(): DeviceGenerationConfiguration? = guarded {
        if (!file.baseFile.exists()) return@guarded null
        val encrypted = file.openRead().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > 16 * 1024) throw GenerationException(GenerationFailureKind.CONFIGURATION)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        if (encrypted.size < 1 + 12 + 16 || encrypted[0] != 1.toByte()) throw GenerationException(GenerationFailureKind.CONFIGURATION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(aesAlias), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        try {
            val parsed = StrictJson.parse(plain).jsonObject()
            if (parsed.keys != setOf("serverOrigin", "bearerToken")) throw GenerationException(GenerationFailureKind.CONFIGURATION)
            DeviceGenerationConfiguration(parsed.text("serverOrigin"), parsed.text("bearerToken"))
        } finally { plain.fill(0) }
    }

    /** The caller supplies a registered device token through a future trusted provisioning flow. */
    @Synchronized fun save(configuration: DeviceGenerationConfiguration) = guarded {
        if (!store().containsAlias(aesAlias)) {
            if (file.baseFile.exists()) throw GenerationException(GenerationFailureKind.CONFIGURATION)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(aesAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
        val plain = StrictJson.encode(mapOf("serverOrigin" to configuration.serverOrigin, "bearerToken" to configuration.bearerToken))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val encrypted = try {
            cipher.init(Cipher.ENCRYPT_MODE, key(aesAlias)); cipher.updateAAD(aad)
            byteArrayOf(1) + cipher.iv.also { if (it.size != 12) throw GenerationException(GenerationFailureKind.CONFIGURATION) } + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    /** Explicit configuration removal does not remove the independent input-HMAC identity. */
    @Synchronized fun clear() { file.delete() }

    @Synchronized override fun hash(canonicalInput: ByteArray): String = guarded {
        if (!store().containsAlias(hmacAlias)) {
            // Unlike encrypted pending payloads this key has no ciphertext dependency. Existing requests
            // already carry their original hash in Room; only future requests use a replacement key.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(hmacAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKey()
        }
        Mac.getInstance("HmacSHA256").run { init(key(hmacAlias)); doFinal(canonicalInput) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String): SecretKey = store().getKey(alias, null) as? SecretKey
        ?: throw GenerationException(GenerationFailureKind.CONFIGURATION)
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (_: Exception) { throw GenerationException(GenerationFailureKind.CONFIGURATION) }
}
