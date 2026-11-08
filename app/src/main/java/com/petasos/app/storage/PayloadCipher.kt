package com.petasos.app.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never contains key material or platform exception messages. */
class StorageUnavailable(val code: String) : Exception(code)

internal data class PayloadIdentity(val sessionId: String, val turnId: String, val version: Long) {
    init { require(version >= 1) }
    fun associatedData(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            listOf("Petasos.PendingConversation.v1", sessionId, turnId).forEach {
                val value = it.toByteArray(Charsets.UTF_8)
                out.writeInt(value.size)
                out.write(value)
            }
            out.writeLong(version)
        }
        bytes.toByteArray()
    }
}

internal class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyVersion: String,
    val payloadHash: String,
) {
    override fun toString(): String = "EncryptedPayload([redacted])"
}

/** Missing keys may only be provisioned before any encrypted payload exists. */
internal interface PayloadKeys {
    val version: String
    fun provisionNewStore()
    fun encryptionKey(): SecretKey
    fun authenticationKey(): SecretKey
}

internal class AndroidPayloadKeys(private val namespace: String = "com.petasos.app.pending") : PayloadKeys {
    override val version: String = "v1"
    private val encryptionAlias get() = "$namespace.$version.aes"
    private val authenticationAlias get() = "$namespace.$version.hmac"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun provisionNewStore() {
        try {
            val existing = store()
            val hasEncryption = existing.containsAlias(encryptionAlias)
            val hasAuthentication = existing.containsAlias(authenticationAlias)
            if (hasEncryption != hasAuthentication) throw StorageUnavailable("key_set_incomplete")
            if (hasEncryption) return
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(encryptionAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(authenticationAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKey()
        } catch (failure: StorageUnavailable) {
            throw failure
        } catch (_: Exception) {
            throw StorageUnavailable("key_provision_failed")
        }
    }

    private fun key(alias: String): SecretKey = try {
        store().getKey(alias, null) as? SecretKey ?: throw StorageUnavailable("key_unavailable")
    } catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { throw StorageUnavailable("key_unavailable") }

    override fun encryptionKey(): SecretKey = key(encryptionAlias)
    override fun authenticationKey(): SecretKey = key(authenticationAlias)
}

internal class PayloadCipher(private val keys: PayloadKeys) {
    fun provisionNewStore() = keys.provisionNewStore()

    fun encrypt(identity: PayloadIdentity, plaintext: ByteArray): EncryptedPayload = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore creates the IV; caller-supplied encryption IVs remain prohibited.
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
        cipher.updateAAD(identity.associatedData())
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        if (nonce.size != 12) throw StorageUnavailable("invalid_nonce_length")
        EncryptedPayload(ciphertext, nonce, keys.version, hmac(identity, plaintext).toHex())
    }

    fun decrypt(identity: PayloadIdentity, payload: EncryptedPayload): ByteArray = guarded {
        if (payload.keyVersion != keys.version || payload.nonce.size != 12) throw StorageUnavailable("unknown_payload_key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.encryptionKey(), GCMParameterSpec(128, payload.nonce))
        cipher.updateAAD(identity.associatedData())
        val plaintext = cipher.doFinal(payload.ciphertext)
        val actual = hmac(identity, plaintext).toHex().toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(actual, payload.payloadHash.toByteArray(Charsets.US_ASCII))) {
            plaintext.fill(0)
            throw StorageUnavailable("payload_integrity_failed")
        }
        plaintext
    }

    /** Stable, keyed request fingerprint with a distinct domain from encrypted payload hashes. */
    fun requestFingerprint(identity: PayloadIdentity, task: String, canonicalInput: ByteArray): String = guarded {
        Mac.getInstance("HmacSHA256").run {
            init(keys.authenticationKey())
            update("Petasos.Generation.$task.v0.8\u0000".toByteArray(Charsets.UTF_8))
            update(identity.associatedData())
            doFinal(canonicalInput).toHex()
        }
    }

    private fun hmac(identity: PayloadIdentity, plaintext: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(keys.authenticationKey())
            update(identity.associatedData())
            doFinal(plaintext)
        }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { throw StorageUnavailable("payload_crypto_failed") }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
