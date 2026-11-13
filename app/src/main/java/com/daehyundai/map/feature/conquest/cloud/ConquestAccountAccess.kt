package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import java.io.File
import java.security.MessageDigest

/** No exploration types or credentials cross this feature-owned boundary. */
internal data class ConquestAuthSession(val projectRef: String, val authUserId: String, val generation: Long)

internal enum class ConquestAccountIssue {
    NOT_CONFIGURED, NO_SESSION, ACCOUNT_CHANGED, READ_SESSION, REFRESH_SESSION, REQUEST, APPLY,
}

internal sealed interface ConquestAccountResult<out T> {
    data class Success<T>(val session: ConquestAuthSession, val value: T) : ConquestAccountResult<T>
    data class Unavailable(val issue: ConquestAccountIssue) : ConquestAccountResult<Nothing>
}

/** Blocking IO only. apply must be short, synchronous local storage work without UI or HTTP. */
internal interface ConquestAccountAccess {
    fun existingAccount(): ConquestAccountResult<Unit>
    fun <T> withCurrentAccount(expected: ConquestAuthSession, apply: () -> T): ConquestAccountResult<T>
}

/** Account inspection is read-only; binding is reserved for a future server submission workflow. */
internal class ConquestAccountLink(
    private val directory: File,
    private val installationId: String,
    private val partition: ConquestServerPartition,
    private val catalogVersion: String,
) {
    fun inspect(access: ConquestAccountAccess): ConquestAccountResult<Unit> = access.existingAccount()

    fun bindCurrent(access: ConquestAccountAccess, expected: ConquestAuthSession): ConquestAccountResult<ConquestAccountBinding> =
        access.withCurrentAccount(expected) {
            // Auth -> record -> binding store. Never call auth while holding either store lock.
            ConquestAccountRestore.withRecordAccess {
                val binding = ConquestAccountBinding(expected.projectRef, expected.authUserId,
                    installationId, partition, catalogVersion)
                val key = listOf(binding.projectRef, binding.authUserId, binding.installationId,
                    binding.partition.name, binding.catalogVersion).joinToString("\u0000")
                val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                ConquestAccountBindingStore(File(directory, "$digest.bin")).bind(binding)
            }
        }
}
