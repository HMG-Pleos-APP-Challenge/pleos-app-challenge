package com.daehyundai.map.feature.exploration.cloud

import java.util.concurrent.CancellationException

internal class StoredAuthSession(
    val binding:AccountBinding,val expiresAtSeconds:Long,val accessToken:String,val refreshToken:String,
)

/** Shares the existing auth monitor with signup, refresh, email changes and restore. */
internal class ExistingAccountBoundary(
    private val lock:Any,
    private val read:()->StoredAuthSession?,
    private val renew:(StoredAuthSession)->StoredAuthSession,
    private val request:(String,String,StoredAuthSession)->String,
    private val nowSeconds:()->Long={System.currentTimeMillis()/1000},
):ExistingAccountAuth {
    override fun existingAccount():AccountAuthResult<Unit> = synchronized(lock) {
        when(val loaded=load(null)) {
            is AccountAuthResult.Success -> AccountAuthResult.Success(loaded.binding,Unit)
            AccountAuthResult.NoSession -> AccountAuthResult.NoSession
            AccountAuthResult.AccountChanged -> AccountAuthResult.AccountChanged
            is AccountAuthResult.Failed -> loaded
        }
    }

    override fun rpcForAccount(expected:AccountBinding,name:String,body:String):AccountAuthResult<String> = synchronized(lock) {
        when(val loaded=load(expected)) {
            is AccountAuthResult.Success -> try {
                AccountAuthResult.Success(loaded.binding,request(name,body,loaded.value))
            } catch(cancelled:CancellationException) { throw cancelled
            } catch(_:Exception) { AccountAuthResult.Failed(AuthFailureStage.REQUEST) }
            AccountAuthResult.NoSession -> AccountAuthResult.NoSession
            AccountAuthResult.AccountChanged -> AccountAuthResult.AccountChanged
            is AccountAuthResult.Failed -> loaded
        }
    }

    override fun <T> withCurrentAccount(expected:AccountBinding,apply:()->T):AccountAuthResult<T> = synchronized(lock) {
        val current=try { read() } catch(cancelled:CancellationException) { throw cancelled
        } catch(_:Exception) { return@synchronized AccountAuthResult.Failed(AuthFailureStage.READ_SESSION) }
        if(current==null) return@synchronized AccountAuthResult.NoSession
        if(current.binding!=expected) return@synchronized AccountAuthResult.AccountChanged
        try { AccountAuthResult.Success(expected,apply()) }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { AccountAuthResult.Failed(AuthFailureStage.APPLY) }
    }

    private fun load(expected:AccountBinding?):AccountAuthResult<StoredAuthSession> {
        val saved=try { read() } catch(cancelled:CancellationException) { throw cancelled
        } catch(_:Exception) { return AccountAuthResult.Failed(AuthFailureStage.READ_SESSION) }
        if(saved==null) return AccountAuthResult.NoSession
        if(expected!=null && saved.binding!=expected) return AccountAuthResult.AccountChanged
        val session=if(saved.expiresAtSeconds<=nowSeconds()+60) {
            try { renew(saved) } catch(cancelled:CancellationException) { throw cancelled
            } catch(_:Exception) { return AccountAuthResult.Failed(AuthFailureStage.REFRESH_SESSION) }
        } else saved
        if(session.binding!=saved.binding) return AccountAuthResult.AccountChanged
        return AccountAuthResult.Success(session.binding,session)
    }
}
