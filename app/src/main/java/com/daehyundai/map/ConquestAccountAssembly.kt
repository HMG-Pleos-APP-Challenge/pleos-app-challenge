package com.daehyundai.map

import android.content.Context
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.exploration.cloud.*
import java.net.URI

/** Composition adapter: the conquest feature never imports the exploration auth implementation. */
internal class ConquestAccountAdapter(
    private val auth: ExistingAccountAuth,
    private val projectRef: String,
) : ConquestAccountAccess {
    override fun existingAccount(): ConquestAccountResult<Unit> = map(auth.existingAccount())

    override fun <T> withCurrentAccount(expected: ConquestAuthSession, apply: () -> T): ConquestAccountResult<T> {
        if (expected.projectRef != projectRef) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        return map(auth.withCurrentAccount(AccountBinding(expected.authUserId, expected.generation), apply))
    }

    private fun <T> map(result: AccountAuthResult<T>): ConquestAccountResult<T> = when (result) {
        is AccountAuthResult.Success -> ConquestAccountResult.Success(
            ConquestAuthSession(projectRef, result.binding.authUserId, result.binding.generation), result.value)
        AccountAuthResult.NoSession -> ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
        AccountAuthResult.AccountChanged -> ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        is AccountAuthResult.Failed -> ConquestAccountResult.Unavailable(when (result.stage) {
            AuthFailureStage.READ_SESSION -> ConquestAccountIssue.READ_SESSION
            AuthFailureStage.REFRESH_SESSION -> ConquestAccountIssue.REFRESH_SESSION
            AuthFailureStage.REQUEST -> ConquestAccountIssue.REQUEST
            AuthFailureStage.APPLY -> ConquestAccountIssue.APPLY
        })
    }
}

internal fun conquestAccountAccess(context: Context): ConquestAccountAccess {
    // No signup, key copying or independently namespaced session store.
    val host = runCatching { URI(BuildConfig.SUPABASE_URL).host }.getOrNull().orEmpty()
    val ref = Regex("([a-z0-9]{20})\\.supabase\\.co").matchEntire(host)?.groupValues?.get(1)
    if (!SupabaseCloud.configured || ref == null) return object : ConquestAccountAccess {
        override fun existingAccount() = ConquestAccountResult.Unavailable(ConquestAccountIssue.NOT_CONFIGURED)
        override fun <T> withCurrentAccount(expected: ConquestAuthSession, apply: () -> T) =
            ConquestAccountResult.Unavailable(ConquestAccountIssue.NOT_CONFIGURED)
    }
    return ConquestAccountAdapter(SupabaseCloud(context.applicationContext), ref)
}
