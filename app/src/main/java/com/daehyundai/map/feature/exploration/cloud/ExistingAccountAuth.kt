package com.daehyundai.map.feature.exploration.cloud

/** Local transition generation is installation-local and must not be used as a server identity. */
data class AccountBinding(val authUserId:String,val generation:Long) {
    init { require(authUserId.isNotBlank() && generation>=0) }
}

sealed interface AccountAuthResult<out T> {
    data class Success<T>(val binding:AccountBinding,val value:T):AccountAuthResult<T>
    data object NoSession:AccountAuthResult<Nothing>
    data object AccountChanged:AccountAuthResult<Nothing>
    data class Failed(val stage:AuthFailureStage):AccountAuthResult<Nothing>
}
enum class AuthFailureStage { READ_SESSION, REFRESH_SESSION, REQUEST, APPLY }

/** Contains no token-returning API. Call blocking operations on an IO dispatcher. */
interface ExistingAccountAuth {
    fun existingAccount():AccountAuthResult<Unit>
    fun rpcForAccount(expected:AccountBinding,name:String,body:String):AccountAuthResult<String>

    /** Only short local transactions. No HTTP, suspending work, UI callbacks or acquiring outer locks. */
    fun <T> withCurrentAccount(expected:AccountBinding,apply:()->T):AccountAuthResult<T>
}
