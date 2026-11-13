package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.cloud.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConquestAccountBindingTest {
    @get:Rule val folder = TemporaryFolder()
    private fun binding() = ConquestAccountBinding("abcdefghijklmnopqrst",
        "11111111-1111-4111-8111-111111111111", "u-installation",
        ConquestServerPartition.PLEOS, "roads-v1")
    private fun store(name: String = "binding.bin") = ConquestAccountBindingStore(File(folder.root, name))
    private fun fails(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        catch (_: IllegalStateException) { return }
        catch (_: java.io.IOException) { return }
        fail("Expected binding to fail closed")
    }

    @Test fun firstBindingIsRestoredAndRepeatBindingIsIdempotent() {
        assertNull(store().read())
        store().bind(binding())
        val bytes = File(folder.root, "binding.bin").readBytes()
        assertEquals(binding(), store().read())
        store().bind(binding())
        assertArrayEquals(bytes, File(folder.root, "binding.bin").readBytes())
    }

    @Test fun everyScopeMismatchPreservesOriginalBinding() {
        val original = binding()
        store().bind(original)
        val file = File(folder.root, "binding.bin")
        val bytes = file.readBytes()
        listOf(original.copy(projectRef = "tsrqponmlkjihgfedcba"),
            original.copy(authUserId = "22222222-2222-4222-8222-222222222222"),
            original.copy(installationId = "u-other"),
            original.copy(partition = ConquestServerPartition.PLEOS_EMULATOR),
            original.copy(catalogVersion = "roads-v2")).forEach {
            fails { store().bind(it) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun corruptTruncatedFutureAndTrailingDataAreNotReset() {
        val file = File(folder.root, "binding.bin")
        store().bind(binding())
        val valid = file.readBytes()
        listOf("broken".toByteArray(), valid.copyOf(valid.size - 1),
            valid + byteArrayOf(1), valid.copyOf().also { it[2] = 'X'.code.toByte() }).forEach {
            file.writeBytes(it)
            fails { store().bind(binding()) }
            assertArrayEquals(it, file.readBytes())
        }
    }

    @Test fun missingSessionCannotPublishAndNoTicketSurvivesRestart() {
        val fence = ConquestAccountFence()
        assertNull(fence.capture())
        val ticket = fence.activate(binding(), store())
        val restarted = ConquestAccountFence()
        assertNull(restarted.capture())
        assertFalse(restarted.publish(ticket, binding()) { fail("Stale process ticket") })
    }

    @Test fun accountTransitionAndAbaDiscardOldResponseAndAck() {
        val fence = ConquestAccountFence()
        val a = binding()
        val old = fence.activate(a, store())
        fence.invalidate()
        val b = a.copy(authUserId = "22222222-2222-4222-8222-222222222222")
        fence.activate(b, store("b.bin"))
        assertFalse(fence.publish(old, a) { fail("Old account ACK") })
        val active = fence.activate(a, store())
        assertFalse(fence.publish(old, a) { fail("ABA response") })
        var updates = 0
        assertTrue(fence.publish(active, a) { updates++ })
        assertEquals(1, updates)
    }

    @Test fun mismatchedResponseAndForeignFenceCannotPublish() {
        val fence = ConquestAccountFence()
        val ticket = fence.activate(binding(), store())
        val other = ConquestAccountFence().activate(binding(), store())
        assertFalse(fence.publish(other, binding()) { fail("Foreign ticket") })
        assertFalse(fence.publish(ticket, binding().copy(catalogVersion = "roads-v2")) { fail("Wrong scope") })
    }

    @Test fun failedBindingDisablesPreviousGeneration() {
        val fence = ConquestAccountFence()
        val old = fence.activate(binding(), store())
        fails { fence.activate(binding().copy(installationId = "u-other"), store()) }
        assertNull(fence.capture())
        assertFalse(fence.publish(old, binding()) { fail("Failed activation ACK") })
        // A directory where a parent file should be also leaves the fence inactive.
        val parentFile = folder.newFile("not-directory")
        fails { fence.activate(binding(), ConquestAccountBindingStore(File(parentFile, "binding"))) }
        assertNull(fence.capture())
    }

    @Test fun concurrentInitialBindingsHaveOneWinnerAndCannotReplaceIt() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val candidates = listOf(binding(), binding().copy(installationId = "u-other"))
        try {
            val futures = candidates.map { candidate -> pool.submit<Boolean> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                try { store().bind(candidate); true } catch (_: IllegalStateException) { false }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(store().read() in candidates)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun invalidIdentifiersAndMockPartitionAreRejected() {
        fails { binding().copy(projectRef = "https://wrong.example") }
        fails { binding().copy(authUserId = "u-local") }
        fails { binding().copy(installationId = " ") }
        fails { binding().copy(catalogVersion = "") }
        fails { ConquestServerPartition.valueOf("MOCK") }
    }

    @Test fun responseAlreadyInFlightCannotPublishAfterTransition() {
        val fence = ConquestAccountFence()
        val ticket = fence.activate(binding(), store())
        val inFlight = CountDownLatch(1)
        val response = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> {
                inFlight.countDown()
                check(response.await(5, TimeUnit.SECONDS))
                fence.publish(ticket, binding()) { fail("Stale cache or ACK update") }
            }
            assertTrue(inFlight.await(5, TimeUnit.SECONDS))
            fence.invalidate()
            fence.activate(binding(), store())
            response.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
        } finally { response.countDown(); pool.shutdownNow() }
    }
}
