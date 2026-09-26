package com.hackathon.recall.data

import android.content.Context
import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Encrypted copies of original images and PDFs in app-private storage (brief §4). Each file is
 * encrypted with Tink streaming AEAD; the file name is bound as associated data so files can't be swapped.
 */
class VaultFileStore(context: Context, private val aead: StreamingAead) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }

    fun write(name: String, bytes: ByteArray) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        aead.newEncryptingStream(FileOutputStream(tmp), name.toByteArray()).use { it.write(bytes) }
        check(tmp.renameTo(target)) { "vault write failed" }
    }

    fun open(name: String): InputStream = aead.newDecryptingStream(FileInputStream(File(dir, name)), name.toByteArray())

    fun readBytes(name: String): ByteArray = open(name).use { it.readBytes() }

    fun exists(name: String): Boolean = File(dir, name).exists()

    fun delete(name: String) {
        File(dir, name).delete()
    }

    /** Vault files that no database row references (left behind if the app died mid-ingest). */
    fun orphans(referenced: Set<String>): List<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it !in referenced && !it.endsWith(".tmp") }

    fun sizeBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }
}
