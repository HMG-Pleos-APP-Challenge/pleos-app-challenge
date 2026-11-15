package com.daehyundai.map

import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import com.daehyundai.map.feature.exploration.cloud.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConquestAccountAssemblyTest {
    @get:Rule val temp = TemporaryFolder()
    private val project = "abcdefghijklmnopqrst"
    private val userA = "11111111-1111-4111-8111-111111111111"
    private val userB = "22222222-2222-4222-8222-222222222222"
    private fun session(user: String = userA, generation: Long = 1, expires: Long = 10_000) =
        StoredAuthSession(AccountBinding(user, generation), expires, "fixture-access", "fixture-refresh")
    private var saved: StoredAuthSession? = session()
    private var requests = 0
    private var renewals = 0
    private val auth = ExistingAccountBoundary(Any(), { saved }, {
        renewals++
        session(it.binding.authUserId, it.binding.generation).also { next -> saved = next }
    }, { _, _, _ -> requests++; "unused" }, { 1_000 })
    private val adapter get() = ConquestAccountAdapter(auth, project)
    private val directory get() = File(temp.root, "server-accounts")
    private fun link(partition: ConquestServerPartition = ConquestServerPartition.PLEOS) =
        ConquestAccountLink(directory, "u-installation", partition, "roads-v1")
    private fun current(): ConquestAuthSession =
        (adapter.existingAccount() as ConquestAccountResult.Success).session

    @Test fun inspectionUsesExistingAccountWithoutCreatingBindingOrCallingRpc() {
        val result = link().inspect(adapter) as ConquestAccountResult.Success
        assertEquals(ConquestAuthSession(project, userA, 1), result.session)
        assertFalse(directory.exists())
        assertTrue(ConquestAccountRestore.canRestore(temp.root))
        assertEquals(0, requests)
    }

    @Test fun absentSessionPreservesFilesAndCannotApply() {
        saved = null
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION), link().inspect(adapter))
        val expected = ConquestAuthSession(project, userA, 1)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION), link().bindCurrent(adapter, expected))
        assertFalse(directory.exists())
        assertEquals(0, renewals)
        assertEquals(0, requests)
    }

    @Test fun refreshKeepsGenerationAndMapsReadFailureWithoutCredentials() {
        saved = session(expires = 1_050)
        assertEquals(1L, current().generation)
        assertEquals(1, renewals)
        val broken = ExistingAccountBoundary(Any(), { throw IOException("private fixture detail") },
            { error("No refresh") }, { _, _, _ -> error("No RPC") })
        val result = ConquestAccountAdapter(broken, project).existingAccount()
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.READ_SESSION), result)
        assertFalse(result.toString().contains("private fixture detail"))
    }

    @Test fun changedAccountGenerationAndProjectNeverWriteBinding() {
        val original = current()
        saved = session(userB, 2)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED), link().bindCurrent(adapter, original))
        saved = session(userA, 3) // A -> B -> A still invalidates the original response.
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED), link().bindCurrent(adapter, original))
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),
            link().bindCurrent(adapter, current().copy(projectRef = "tsrqponmlkjihgfedcba")))
        assertFalse(directory.exists())
    }

    @Test fun finalRestoreCheckSeesNewBindingEvenIfUiCheckPassed() {
        assertTrue(ConquestAccountRestore.canRestore(temp.root)) // Earlier UI preflight.
        val result = link().bindCurrent(adapter, current()) as ConquestAccountResult.Success
        assertEquals(userA, result.value.authUserId)
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
        var replaced = false
        try {
            ConquestAccountRestore.restoreWhenEmpty(temp.root) { replaced = true }
            fail("Final check must block replacement")
        } catch (_: IllegalStateException) { }
        assertFalse(replaced)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun bindingFailureMapsApplyFailureAndPreservesCorruptBytes() {
        link().bindCurrent(adapter, current())
        val file = directory.listFiles()!!.single()
        file.writeText("corrupt")
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY), link().bindCurrent(adapter, current()))
        assertEquals("corrupt", file.readText())
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
    }

    @Test fun partitionSeparationAndRepeatBindingDoNotReplaceEachOther() {
        val expected = current()
        link().bindCurrent(adapter, expected)
        val first = directory.listFiles()!!.single()
        val bytes = first.readBytes()
        link(ConquestServerPartition.PLEOS_EMULATOR).bindCurrent(adapter, expected)
        link().bindCurrent(adapter, expected)
        assertEquals(2, directory.listFiles()!!.size)
        assertArrayEquals(bytes, first.readBytes())
    }

    @Test fun unknownServerStateBlocksRestoreButEmptyNamespaceDoesNot() {
        directory.mkdirs()
        assertTrue(ConquestAccountRestore.canRestore(temp.root))
        File(directory, "future-outbox.bin").writeText("unknown")
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
        assertEquals("unknown", File(directory, "future-outbox.bin").readText())
    }

    @Test fun malformedServerNamespaceBlocksRestore() {
        directory.writeText("not a directory")
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
        assertEquals("not a directory", directory.readText())
    }

    @Test fun applyCancellationPropagatesWithoutInventingSuccess() {
        try {
            adapter.withCurrentAccount(current()) { throw CancellationException("fixture") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(directory.exists())
    }
}
