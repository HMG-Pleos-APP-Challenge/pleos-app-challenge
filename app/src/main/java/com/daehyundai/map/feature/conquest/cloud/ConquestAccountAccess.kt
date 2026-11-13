package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import java.io.File

/** No exploration types or credentials cross this feature-owned boundary. */
internal data class ConquestAuthSession(val projectRef: String, val authUserId: String, val generation: Long)

internal enum class ConquestAccountIssue {
    NOT_CONFIGURED, NO_SESSION, ACCOUNT_CHANGED, READ_SESSION, REFRESH_SESSION, REQUEST, APPLY,
    READ_OUTBOX, INVALID_RESPONSE, OUTBOX_FULL,
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
                ConquestAccountBindingStore(File(directory,"${binding.storageKey()}.bin")).bind(binding)
            }
        }
}
