package com.petasos.app.generation

import android.content.Context
import com.petasos.app.BuildConfig
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * DEMO only: wires the optional local answer server without any receiver, intent extra or argv
 * secret. `adb push` two files into [INBOX] (world-readable; the emulator's secondary driver user
 * has no adb-reachable app storage) and the next launch/resume picks them up:
 *  - `demo-server.token`  the device token (saved encrypted; the plaintext is deleted when allowed)
 *  - `demo-server-ca.pem` the local server's CA certificate (copied to private storage)
 * The CA is trusted only for connections to `localhost`; the platform trust store is untouched.
 */
object DemoServerSetup {
    private const val TOKEN = "demo-server.token"
    private const val CA = "demo-server-ca.pem"
    private const val ORIGIN = "https://localhost:8443"
    private const val INBOX = "/data/local/tmp/petasos-demo"

    @Volatile internal var localTrust: SSLSocketFactory? = null

    fun start(context: Context) {
        if (!BuildConfig.DEMO) return
        val app = context.applicationContext
        thread(name = "demo-server-setup") { try { provision(app) } catch (_: Exception) { /* Optional feature. */ } }
    }

    private fun provision(app: Context) {
        val inbox = File(INBOX)
        val privateCa = File(app.filesDir, CA)
        File(inbox, CA).takeIf { it.isFile && it.canRead() }?.let { pushed ->
            try { pushed.copyTo(privateCa, overwrite = true) } finally { pushed.delete() }
        }
        if (privateCa.isFile) localTrust = try { trustFactory(privateCa) } catch (_: Exception) { privateCa.delete(); null }
        val tokenFile = File(inbox, TOKEN).takeIf { it.isFile && it.canRead() } ?: return
        try {
            val token = tokenFile.readText().trim()
            if (token.isNotEmpty()) DeviceGenerationConfigStore(app).run {
                // The plaintext may be undeletable for the app; do not rewrite an identical config on every resume.
                if (load()?.bearerToken == token) return@run
                clear()
                save(DeviceGenerationConfiguration(ORIGIN, token))
                checkNotNull(load())
            }
        } finally { tokenFile.delete() }
    }

    private fun trustFactory(pem: File): SSLSocketFactory {
        val certificate = pem.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("demo-local-ca", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
