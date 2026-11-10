package com.daehyundai.map.feature.exploration

import android.content.Context
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.cloud.CloudCache
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudCacheTest {
    private lateinit var context:Context
    private val empty="""{"grants":[],"xp":0}"""
    @Before fun setup() { context=RuntimeEnvironment.getApplication();context.deleteDatabase("exploration-cloud.db") }
    private fun sample(id:String,t:Long=1000)=LocationSample(id,"local-user",37.2,126.8,t,5f,LocationSourceType.PLEOS)
    private fun CloudCache.withCache(block:(CloudCache)->Unit) { try { block(this) } finally { close() } }
    @Test fun restartRetainsUnacknowledgedSamples() {
        CloudCache(context).withCache { it.enqueue("u","pleos",sample("a")) }
        CloudCache(context).withCache { assertEquals(1,it.count("u","pleos")) }
    }
    @Test fun ackDoesNotEraseSamplesEnqueuedDuringRequest() {
        CloudCache(context).withCache {
            it.enqueue("u","pleos",sample("a"));val sent=it.batch("u","pleos")
            it.enqueue("u","pleos",sample("b",2000));it.acknowledge("u","pleos",sent.first,empty)
            assertEquals(1,it.count("u","pleos"));assertTrue(it.batch("u","pleos").second.contains("b"))
            assertEquals(0L,it.snapshot("u","pleos")!!.xp)
        }
    }
    @Test fun duplicatesAndPartitionsAreIsolated() {
        CloudCache(context).withCache {
            repeat(2) { _ -> it.enqueue("u","pleos",sample("a")) }
            it.enqueue("u","pleos-emulator",sample("a"));it.enqueue("other","pleos",sample("a"))
            assertEquals(1,it.count("u","pleos"));val sent=it.batch("u","pleos")
            it.acknowledge("u","pleos",sent.first,empty)
            assertEquals(1,it.count("u","pleos-emulator"));assertEquals(1,it.count("other","pleos"))
        }
    }
    @Test fun malformedServerResponseCannotBeAcknowledgedByParser() {
        CloudCache(context).withCache {
            it.enqueue("u","pleos",sample("a"))
            try { CloudCache.parse("""{"error":"failed"}""");fail("must reject") } catch(_:org.json.JSONException) { }
            assertEquals(1,it.count("u","pleos"))
        }
    }
}
