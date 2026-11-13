package com.daehyundai.map

import com.daehyundai.map.feature.exploration.cloud.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*

class ExistingAccountBoundaryTest {
    private val lock=Any()
    private fun session(id:String="a",generation:Long=1,expires:Long=10_000)=
        StoredAuthSession(AccountBinding(id,generation),expires,"fixture-access","fixture-refresh")
    private var saved:StoredAuthSession?=session()
    private var renewals=0
    private var requests=0
    private val boundary=ExistingAccountBoundary(lock,{saved},{
        renewals++;session(it.binding.authUserId,it.binding.generation).also { saved=it }
    },{_,_,_->requests++;"response"}, {1000})

    @Test fun absentSessionNeverRefreshesOrCallsRequest() {
        saved=null
        assertEquals(AccountAuthResult.NoSession,boundary.existingAccount())
        assertEquals(AccountAuthResult.NoSession,boundary.rpcForAccount(AccountBinding("a",1),"read","{}"))
        assertEquals(0,renewals);assertEquals(0,requests)
    }
    @Test fun expiredSessionRefreshesWithoutChangingBinding() {
        saved=session(expires=1059)
        val result=boundary.rpcForAccount(AccountBinding("a",1),"read","{}") as AccountAuthResult.Success
        assertEquals(AccountBinding("a",1),result.binding)
        assertEquals("response",result.value)
        assertEquals(1,renewals);assertEquals(1,requests)
    }
    @Test fun wrongAccountOrGenerationIsBlockedBeforeRefresh() {
        saved=session("b",2,0)
        assertEquals(AccountAuthResult.AccountChanged,boundary.rpcForAccount(AccountBinding("a",1),"read","{}"))
        assertEquals(AccountAuthResult.AccountChanged,boundary.rpcForAccount(AccountBinding("b",1),"read","{}"))
        assertEquals(0,renewals);assertEquals(0,requests)
    }
    @Test fun refreshFailureCannotSendRequestOrInventSession() {
        val expired=session(expires=0)
        val failed=ExistingAccountBoundary(lock,{expired},{throw IOException("fixture")},{_,_,_->error("must not send")},{1000})
        assertEquals(AccountAuthResult.Failed(AuthFailureStage.REFRESH_SESSION),failed.existingAccount())
    }
    @Test fun refreshChangingIdentityCannotSendRequest() {
        val boundary=ExistingAccountBoundary(lock,{session(expires=0)},{session("b",2)},{_,_,_->error("must not send")},{1000})
        assertEquals(AccountAuthResult.AccountChanged,boundary.rpcForAccount(AccountBinding("a",1),"read","{}"))
    }
    @Test fun responseAfterAtoBtoACannotAckOrReplaceSnapshot() {
        val response=boundary.rpcForAccount(AccountBinding("a",1),"read","{}") as AccountAuthResult.Success
        synchronized(lock) {saved=session("b",2);saved=session("a",3)}
        var applied=false
        assertEquals(AccountAuthResult.AccountChanged,boundary.withCurrentAccount(response.binding){applied=true})
        assertFalse(applied)
    }
    @Test fun localApplyRequiresCurrentBindingAndReportsFailure() {
        var acked=false
        assertTrue(boundary.withCurrentAccount(AccountBinding("a",1)){acked=true} is AccountAuthResult.Success)
        assertTrue(acked)
        assertEquals(AccountAuthResult.Failed(AuthFailureStage.APPLY),
            boundary.withCurrentAccount(AccountBinding("a",1)){throw IOException("transaction rolled back")})
    }
    @Test fun requestAndReplacementAreSerializedOnSameMonitor() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val replacing=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        val boundary=ExistingAccountBoundary(lock,{saved},{it},{_,_,_->
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            assertEquals("a",saved!!.binding.authUserId);"old-response"
        },{1000})
        try {
            val rpc=executor.submit<AccountAuthResult<String>> {boundary.rpcForAccount(AccountBinding("a",1),"read","{}")}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val restore=executor.submit {replacing.countDown();synchronized(lock){saved=session("b",2)}}
            assertTrue(replacing.await(5,TimeUnit.SECONDS))
            assertFalse(restore.isDone)
            release.countDown()
            val result=rpc.get(5,TimeUnit.SECONDS) as AccountAuthResult.Success
            restore.get(5,TimeUnit.SECONDS)
            assertEquals(AccountAuthResult.AccountChanged,boundary.withCurrentAccount(result.binding){error("stale ACK")})
        } finally {release.countDown();executor.shutdownNow()}
    }
    @Test fun requestErrorsAndCancellationAreNotSuccess() {
        val failure=ExistingAccountBoundary(lock,{saved},{it},{_,_,_->throw IOException("fixture")},{1000})
        assertEquals(AccountAuthResult.Failed(AuthFailureStage.REQUEST),failure.rpcForAccount(AccountBinding("a",1),"read","{}"))
        val cancelled=ExistingAccountBoundary(lock,{saved},{it},{_,_,_->throw CancellationException()},{1000})
        assertThrows(CancellationException::class.java){cancelled.rpcForAccount(AccountBinding("a",1),"read","{}")}
    }
}
