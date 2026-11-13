package com.daehyundai.map.feature.conquest.cloud

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest

/** Server-eligible input only. MOCK never has an account binding. */
internal enum class ConquestServerPartition(val wireName: String) {
    PLEOS("pleos"), PLEOS_EMULATOR("pleos-emulator")
}

/** Local isolation metadata, never authentication evidence or a replacement for auth.uid(). */
internal data class ConquestAccountBinding(
    val projectRef: String,
    val authUserId: String,
    val installationId: String,
    val partition: ConquestServerPartition,
    val catalogVersion: String,
) {
    init {
        require(projectRef.matches(Regex("[a-z0-9]{20}"))) { "Invalid project reference" }
        require(UUID.fromString(authUserId).toString() == authUserId) { "Invalid Auth UUID" }
        require(installationId.isNotBlank() && installationId.length <= 256)
        require(catalogVersion.isNotBlank() && catalogVersion.length <= 256)
    }
}

internal fun ConquestAccountBinding.storageKey():String {
    val values=listOf(projectRef,authUserId,installationId,partition.name,catalogVersion).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(values.toByteArray(Charsets.UTF_8))
        .joinToString("") {"%02x".format(it.toInt() and 255)}
}

/** A dedicated file per binding scope. No delete, migration or account replacement operation. */
internal class ConquestAccountBindingStore(private val file: File) {
    fun read(): ConquestAccountBinding? = synchronized(lock) { readLocked() }

    /** Existing mismatches and corrupt records fail closed, preserving their original bytes. */
    fun bind(expected: ConquestAccountBinding): ConquestAccountBinding = synchronized(lock) {
        val saved = readLocked()
        if (saved != null) {
            check(saved == expected) { "Conquest account binding mismatch" }
            return@synchronized saved
        }
        val target = file.absoluteFile
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create binding directory" }
        val temporary = File.createTempFile("binding-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(BufferedOutputStream(stream))
                output.writeUTF(MAGIC)
                output.writeUTF(expected.projectRef)
                output.writeUTF(expected.authUserId)
                output.writeUTF(expected.installationId)
                output.writeUTF(expected.partition.name)
                output.writeUTF(expected.catalogVersion)
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        expected
    }

    private fun readLocked(): ConquestAccountBinding? {
        if (!file.exists()) return null
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == MAGIC) { "Unsupported account binding" }
            val binding = ConquestAccountBinding(input.readUTF(), input.readUTF(), input.readUTF(),
                ConquestServerPartition.valueOf(input.readUTF()), input.readUTF())
            require(input.read() == -1) { "Trailing account binding data" }
            binding
        }
    }

    companion object {
        private const val MAGIC = "PLEOS_CONQUEST_ACCOUNT_V1"
        // Coordinate separate store objects within this app process, including first bind.
        private val lock = Any()
    }
}

/**
 * Process-local response fence, not the shared auth/restore lock.
 * Future assembly must invalidate it on every explicit session replacement, even A -> A.
 * Token refresh/email linking for the same session must not start a new generation.
 * Not wired to the app until the shared account transition boundary is agreed.
 */
internal class ConquestAccountFence {
    internal class Ticket internal constructor(val binding: ConquestAccountBinding)
    private var current: Ticket? = null

    @Synchronized
    fun activate(binding: ConquestAccountBinding, store: ConquestAccountBindingStore): Ticket {
        current = null // A failed bind must not leave the previous account active.
        store.bind(binding)
        return Ticket(binding).also { current = it }
    }

    @Synchronized
    fun invalidate() { current = null }

    @Synchronized
    fun capture(): Ticket? = current

    /** Apply a short synchronous local update; never call auth/network code in this block. */
    @Synchronized
    fun publish(ticket: Ticket, responseBinding: ConquestAccountBinding, update: () -> Unit): Boolean {
        if (current !== ticket || ticket.binding != responseBinding) return false
        update()
        return true
    }
}
