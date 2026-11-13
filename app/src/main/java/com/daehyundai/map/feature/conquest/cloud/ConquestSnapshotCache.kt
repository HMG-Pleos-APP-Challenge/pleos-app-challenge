package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import com.daehyundai.map.feature.conquest.domain.ConquestCatalog
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ConquestSnapshotOrderException : IllegalStateException("Older or incompatible conquest snapshot")

/** Atomic single-file cache. Unknown/corrupt data is preserved, never reset. Caller guards auth first. */
internal class ConquestSnapshotCache(private val file: File, private val binding: ConquestAccountBinding,
    private val catalog: ConquestCatalog, private val teams: List<String>, private val beforeMove: () -> Unit = {}) {
    private val scope = listOf(binding.projectRef,binding.authUserId,binding.installationId,binding.partition.name,binding.catalogVersion)
    private val expected = ConquestAuthSession(binding.projectRef,binding.authUserId,0) // generation is never persisted/copied.

    fun read(): ConquestSnapshot? = ConquestAccountRestore.withRecordAccess { readLocked() }

    private fun readLocked(): ConquestSnapshot? {
        if (!file.exists()) return null
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == "CONQUEST_SNAPSHOT_V1")
            require(List(5) { input.readUTF() } == scope)
            val size = input.readInt().also { require(it in 1..ConquestSnapshot.MAX_BYTES) }
            val digest = ByteArray(32).also(input::readFully)
            val bytes = ByteArray(size).also(input::readFully)
            require(input.read() == -1 && MessageDigest.isEqual(digest,hash(bytes)))
            ConquestSnapshot.parse(bytes.toString(Charsets.UTF_8),expected,binding.partition,catalog,teams)
        }
    }

    fun save(snapshot: ConquestSnapshot) = ConquestAccountRestore.withRecordAccess {
        // Revalidate scope/raw before durable writes, even when callers already parsed the response.
        val incoming = ConquestSnapshot.parse(snapshot.raw,expected,binding.partition,catalog,teams)
        readLocked()?.let { old ->
            try { incoming.requireSuccessorOf(old) } catch (_: IllegalArgumentException) { throw ConquestSnapshotOrderException() }
        }
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("snapshot-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(BufferedOutputStream(stream))
                output.writeUTF("CONQUEST_SNAPSHOT_V1"); scope.forEach(output::writeUTF)
                val bytes = incoming.raw.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size);output.write(hash(bytes));output.write(bytes);output.flush();stream.fd.sync()
            }
            beforeMove()
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
