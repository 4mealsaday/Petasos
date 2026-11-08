package com.petasos.app.music

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Worker-only immutable local installation. A corrupt/partial stream is never published. */
internal class ReviewedMusicInstaller(private val root: File, private val openAsset: (String) -> InputStream) {
    fun install(bundle: ReviewedMusicBundle) {
        try {
            if ((!root.isDirectory && !root.mkdirs()) || !root.isDirectory) fail()
            val base = root.canonicalFile
            for (entry in bundle.entries) {
                val target = File(base, entry.asset.relativePath)
                if (target.canonicalFile != target || target.parentFile != base) fail()
                if (matches(target, entry.asset)) continue
                val partial = File(base, ".music-${UUID.randomUUID()}.part")
                try {
                    openAsset(entry.bundledPath).use { input ->
                        FileOutputStream(partial).use { output ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(16_384)
                            var count = 0L
                            while (true) {
                                val length = input.read(buffer)
                                if (length < 0) break
                                if (length == 0) fail()
                                count += length
                                if (count > requireNotNull(entry.asset.expectedBytes)) fail()
                                digest.update(buffer, 0, length)
                                output.write(buffer, 0, length)
                            }
                            if (count != entry.asset.expectedBytes || hex(digest.digest()) != entry.asset.sha256) fail()
                            output.fd.sync()
                        }
                    }
                    // Same directory/filesystem; fail closed if atomic replacement is unsupported.
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    if (!matches(target, entry.asset)) fail()
                } finally { partial.delete() }
            }
        } catch (error: MusicBundleException) { throw error }
          catch (_: Exception) { fail() }
    }

    private fun matches(file: File, asset: ReviewedMusicAsset): Boolean {
        if (!file.isFile || file.length() != asset.expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16_384)
            var count = 0L
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length == 0) return false
                count += length
                if (count > requireNotNull(asset.expectedBytes)) return false
                digest.update(buffer,0,length)
            }
            if (count != asset.expectedBytes) return false
        }
        return hex(digest.digest()) == asset.sha256
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun fail(): Nothing = throw MusicBundleException("MUSIC_INSTALL_FAILED")
}
