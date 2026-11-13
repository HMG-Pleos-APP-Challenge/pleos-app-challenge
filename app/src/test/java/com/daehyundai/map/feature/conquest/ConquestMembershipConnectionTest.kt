package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.cloud.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestMembershipConnectionTest {
    private val owner = ConquestAuthSession("abcdefghijklmnopqrst","11111111-1111-4111-8111-111111111111",1)
    private val partition = ConquestServerPartition.PLEOS
    private fun reply(status: String, team: String? = null, time: Long? = null): String =
        JSONObject().put("protocol",1).put("userId",owner.authUserId).put("partition","pleos").put("catalogVersion","v1")
            .put("status",status).put("teamId",team ?: JSONObject.NULL).put("selectedUtcMs",time ?: JSONObject.NULL).toString()

    private inner class Server : ConquestServerAccess {
        var current: ConquestAuthSession? = owner
        var raw = reply("NO_TEAM")
        var selected = reply("SELECTED","red",100)
        var reads = 0
        var selections = 0
        var beforeReply: () -> Unit = {}
        var replySession = owner
        override fun existingAccount(): ConquestAccountResult<Unit> = current?.let { ConquestAccountResult.Success(it,Unit) }
            ?: ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
        override fun <T> withCurrentAccount(expected: ConquestAuthSession, apply: () -> T): ConquestAccountResult<T> =
            if (current == null) ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
            else if (current != expected) ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
            else ConquestAccountResult.Success(expected,apply())
        override fun membership(expected: ConquestAuthSession, partition: ConquestServerPartition, catalog: String): ConquestAccountResult<String> {
            reads++; beforeReply(); return ConquestAccountResult.Success(replySession,raw)
        }
        override fun selectTeam(expected: ConquestAuthSession, partition: ConquestServerPartition, catalog: String, teamId: String): ConquestAccountResult<String> {
            selections++
            return withCurrentAccount(expected) { selected }
        }
        override fun submit(expected: ConquestAuthSession, partition: ConquestServerPartition, catalog: String, events: List<ConquestSubmission>)
            : ConquestAccountResult<String> = error("Membership must not upload location proofs")
    }
    private val installed = mutableListOf<Pair<ConquestAuthSession,ConquestTeamResponse>>()
    private fun connection(server: Server) = ConquestMembershipConnection(server,partition,"v1",listOf("red","blue")) { account,team ->
        server.withCurrentAccount(account) { installed.add(account to team); Unit }
    }

    @Test fun noSessionNeverReadsServerOrInstallsTracking() = runBlocking {
        val server = Server().also { it.current=null }
        val connection = connection(server)
        connection.connect(); connection.selectTeam("red")
        assertEquals(ConquestAccountIssue.NO_SESSION,connection.state.value.issue)
        assertEquals(0,server.reads); assertEquals(0,server.selections); assertTrue(installed.isEmpty())
    }

    @Test fun noTeamRecoveryWaitsForExplicitSelectionAndDoesNotInstallOrCreateTeam() = runBlocking {
        val server = Server(); val connection = connection(server)
        connection.connect()
        assertTrue(connection.state.value.needsTeam)
        assertEquals(1,server.reads); assertEquals(0,server.selections); assertTrue(installed.isEmpty())
        connection.selectTeam("unknown")
        assertEquals(0,server.selections)
        connection.selectTeam("red")
        assertEquals("red",connection.state.value.teamId)
        assertEquals(owner,installed.single().first)
        assertEquals(100L,installed.single().second.selectedUtcMs)
        connection.selectTeam("blue")
        assertEquals(1,server.selections)
    }

    @Test fun existingMembershipRecoversOriginalTeamWithoutCallingSelect() = runBlocking {
        val server = Server().also { it.raw=reply("MEMBER","blue",25) }
        val connection = connection(server)
        connection.connect()
        assertEquals("blue",connection.state.value.teamId)
        assertEquals(25L,installed.single().second.selectedUtcMs)
        assertEquals(0,server.selections)
    }

    @Test fun anotherDeviceFirstSelectionUsesLockedServerTeam() = runBlocking {
        val server = Server().also { it.selected=reply("TEAM_LOCKED","blue",10) }
        val connection = connection(server)
        connection.connect(); connection.selectTeam("red")
        assertEquals("blue",connection.state.value.teamId)
        assertEquals(10L,installed.single().second.selectedUtcMs)
    }

    @Test fun changedGenerationBeforeSelectionCannotChooseForNewSession() = runBlocking {
        val server = Server(); val connection = connection(server)
        connection.connect(); server.current=owner.copy(generation=2); connection.selectTeam("red")
        assertEquals(ConquestAccountIssue.ACCOUNT_CHANGED,connection.state.value.issue)
        assertTrue(installed.isEmpty())
    }

    @Test fun changedAccountBeforeInstallOrEmptyMembershipApplyIsRejected() = runBlocking {
        for (raw in listOf(reply("MEMBER","blue",25),reply("NO_TEAM"))) {
            val server = Server().also { it.raw=raw; it.beforeReply={ it.current=owner.copy(generation=2) } }
            val connection = connection(server)
            connection.connect()
            assertEquals(ConquestAccountIssue.ACCOUNT_CHANGED,connection.state.value.issue)
            assertTrue(installed.isEmpty()); assertFalse(connection.state.value.needsTeam)
        }
    }

    @Test fun malformedIdentityVersionAndMembershipFieldsNeverInstall() = runBlocking {
        val valid = reply("MEMBER","red",25)
        val variants = listOf("broken",JSONObject(valid).put("userId","other").toString(),
            JSONObject(valid).put("partition","pleos-emulator").toString(),JSONObject(valid).put("catalogVersion","other").toString(),
            JSONObject(valid).put("selectedUtcMs",-1).toString(),reply("NO_TEAM","red",25),reply("MEMBER"),reply("MEMBER","unknown",25),
            JSONObject(reply("NO_TEAM")).apply { remove("teamId") }.toString())
        for (raw in variants) {
            val server = Server().also { it.raw=raw }; val connection = connection(server)
            connection.connect()
            assertEquals(raw,ConquestAccountIssue.INVALID_RESPONSE,connection.state.value.issue)
            assertTrue(installed.isEmpty())
        }
    }

    @Test fun oldReplySessionCannotInstallEvenIfBodyMatchesCurrentUuid() = runBlocking {
        val server = Server().also { it.raw=reply("MEMBER","red",25); it.replySession=owner.copy(generation=2) }
        val connection = connection(server)
        connection.connect()
        assertEquals(ConquestAccountIssue.ACCOUNT_CHANGED,connection.state.value.issue)
        assertTrue(installed.isEmpty())
    }

    @Test fun deactivatedConnectionIgnoresLateReadAndCannotStartSelection() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val server = Server().also { it.raw=reply("MEMBER","red",25); it.beforeReply={ entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) } }
        val connection = connection(server)
        val job = async(Dispatchers.Default) { connection.connect() }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); connection.deactivate()
        } finally { release.countDown() }
        job.await(); connection.selectTeam("red")
        assertTrue(installed.isEmpty()); assertEquals(0,server.selections)
    }

    @Test fun storageFailureNeverShowsReadyAndCancellationIsPropagated() = runBlocking {
        val server = Server().also { it.raw=reply("MEMBER","red",25) }
        val failed = ConquestMembershipConnection(server,partition,"v1",listOf("red")) { _,_ ->
            ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY)
        }
        failed.connect()
        assertNull(failed.state.value.teamId); assertEquals(ConquestAccountIssue.APPLY,failed.state.value.issue)
        server.beforeReply={ throw CancellationException("fixture") }
        try { connection(server).connect(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun explicitAccountInspectionInvalidatesPreviousMembershipActions() = runBlocking {
        val server=Server(); val connection=connection(server)
        connection.connect()
        connection.inspectAccount(ConquestAccountResult.Success(owner.copy(generation=2),Unit))
        connection.selectTeam("red")
        assertEquals(ConquestAccountIssue.ACCOUNT_CHANGED,connection.state.value.issue)
        assertEquals(0,server.selections)
    }
}
