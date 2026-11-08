package com.petasos.app.storage

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class PayloadCipherTest {
    private class Keys : PayloadKeys {
        override val version = "test-v1"
        private val encryption = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val authentication = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
        var missing = false
        override fun provisionNewStore() { }
        override fun encryptionKey(): SecretKey = if (missing) throw StorageUnavailable("key_unavailable") else encryption
        override fun authenticationKey(): SecretKey = if (missing) throw StorageUnavailable("key_unavailable") else authentication
    }
    private val identity = PayloadIdentity("session", "turn", 1)
    private val plaintext = "private test text".toByteArray()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: StorageUnavailable) { }
    }

    @Test fun roundTripUsesFreshNonceOnEveryEncryption() {
        val cipher = PayloadCipher(Keys())
        val seen = mutableSetOf<String>()
        repeat(20) {
            val encrypted = cipher.encrypt(identity, plaintext)
            assertEquals(12, encrypted.nonce.size)
            assertTrue(seen.add(encrypted.nonce.joinToString()))
            assertArrayEquals(plaintext, cipher.decrypt(identity, encrypted))
            assertFalse(encrypted.ciphertext.contentEquals(plaintext))
        }
    }

    @Test fun associatedDataBindsSessionTurnAndVersion() {
        val cipher = PayloadCipher(Keys())
        val encrypted = cipher.encrypt(identity, plaintext)
        rejected { cipher.decrypt(identity.copy(sessionId = "other"), encrypted) }
        rejected { cipher.decrypt(identity.copy(turnId = "other"), encrypted) }
        rejected { cipher.decrypt(identity.copy(version = 2), encrypted) }
    }

    @Test fun cipherAndMacTamperingAreRejected() {
        val cipher = PayloadCipher(Keys())
        val encrypted = cipher.encrypt(identity, plaintext)
        rejected { cipher.decrypt(identity, EncryptedPayload(encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, "0".repeat(64))) }
        val changed = encrypted.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        rejected { cipher.decrypt(identity, EncryptedPayload(changed, encrypted.nonce, encrypted.keyVersion, encrypted.payloadHash)) }
    }

    @Test fun hashIsSecretKeyBasedAndScopeBound() {
        val cipher = PayloadCipher(Keys())
        val first = cipher.encrypt(identity, plaintext)
        val second = cipher.encrypt(identity, plaintext)
        assertEquals(first.payloadHash, second.payloadHash)
        assertNotEquals(first.payloadHash, cipher.encrypt(identity.copy(turnId = "other"), plaintext).payloadHash)
        assertNotEquals(first.payloadHash, PayloadCipher(Keys()).encrypt(identity, plaintext).payloadHash)
        val raw = MessageDigest.getInstance("SHA-256").digest(plaintext).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertNotEquals(raw, first.payloadHash)
    }

    @Test fun missingKeysNeverReturnPlaintextOrEncryptFallback() {
        val keys = Keys()
        val cipher = PayloadCipher(keys)
        val encrypted = cipher.encrypt(identity, plaintext)
        keys.missing = true
        rejected { cipher.decrypt(identity, encrypted) }
        rejected { cipher.encrypt(identity, plaintext) }
    }

    @Test fun requestFingerprintIsStableKeyedAndSeparatedFromPayloadHash() {
        val cipher = PayloadCipher(Keys())
        val first = cipher.requestFingerprint(identity, "summary", plaintext)
        assertEquals(first, cipher.requestFingerprint(identity, "summary", plaintext))
        assertNotEquals(first, cipher.requestFingerprint(identity, "answer", plaintext))
        assertNotEquals(first, cipher.encrypt(identity, plaintext).payloadHash)
        assertNotEquals(first, PayloadCipher(Keys()).requestFingerprint(identity, "summary", plaintext))
    }

    @Test fun diagnosticRepresentationDoesNotRevealEncryptedPayloadOrHash() {
        val encrypted = PayloadCipher(Keys()).encrypt(identity, plaintext)
        assertEquals("EncryptedPayload([redacted])", encrypted.toString())
        assertFalse(encrypted.toString().contains(encrypted.payloadHash))
    }
}
