package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestServerCaptureTest {
    private val fixtures get()=JSONArray(File("../docs/data/conquest-proof-fixtures.json").readText())
    private fun catalog(fixture:JSONObject):ConquestCatalog {
        if(fixture.optBoolean("runtimeCatalog")) return File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
        val rows=fixture.getJSONArray("roads")
        return ConquestCatalog("fixture",(0 until rows.length()).map {i ->
            val row=rows.getJSONObject(i);val points=row.getJSONArray("points")
            DirectedRoad(row.getString("id"),"fixture","a","b",(0 until points.length()).map {j ->
                val p=points.getJSONArray(j);ConquestPoint(p.getDouble(0),p.getDouble(1))
            })
        })
    }
    private fun samples(fixture:JSONObject):List<LocationSample> {
        val rows=fixture.getJSONArray("samples")
        return (0 until rows.length()).map {i -> val row=rows.getJSONObject(i)
            LocationSample(row.getString("id"),"u",row.getDouble("lat"),row.getDouble("lon"),row.getLong("time"),
                if(row.isNull("accuracy")) null else row.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS)
        }
    }

    @Test fun capturedProofsFromSharedFixturesReproduceTheirCompletionWithoutFullHistory() {
        val data=fixtures
        val exported=JSONArray()
        for(i in 0 until data.length()) {
            val fixture=data.getJSONObject(i);val catalog=catalog(fixture)
            val capture=ConquestServerCapture("u",catalog,0)
            val completed=samples(fixture).flatMap(capture::onSample)
            val expected=fixture.getJSONArray("expected")
            assertEquals(fixture.getString("name"),(0 until expected.length()).map(expected::getString),completed.map {it.event.roadSegmentId})
            for(proof in completed) {
                val request=requireNotNull(proof.request)
                exported.put(JSONObject().put("fixture",fixture.getString("name"))
                    .put("requestJson",request.requestJson).put("requestHash",request.requestHash))
                val rows=JSONObject(request.requestJson).getJSONArray("samples")
                val detector=DirectedRoadDetector(catalog,LocationSourceType.PLEOS)
                val replay=(0 until rows.length()).flatMap {j ->val row=rows.getJSONObject(j)
                    detector.onSample(LocationSample(row.getString("id"),"u",row.getDouble("lat"),row.getDouble("lon"),
                        row.getLong("time"),row.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS))
                }
                assertEquals(listOf(proof.event),replay)
            }
        }
        // Ignored build artifact for the isolated SQL gateway interoperability check; synthetic inputs only.
        val output=File("build/conquest-proof/captured.json")
        val parent=requireNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        output.writeText(exported.toString())
    }

    @Test fun preEntranceSamplesAreNotIncludedInCapturedSubmission() {
        val fixture=fixtures.getJSONObject(0);val samples=samples(fixture)
        val capture=ConquestServerCapture("u",catalog(fixture),0)
        capture.onSample(samples.first().copy(eventId="before",longitude=126.7998,timestampUtcMs=samples.first().timestampUtcMs-2000))
        val result=samples.flatMap(capture::onSample).single()
        val rows=JSONObject(requireNotNull(result.request).requestJson).getJSONArray("samples")
        assertEquals(samples.size,rows.length());assertEquals(samples.first().eventId,rows.getJSONObject(0).getString("id"))
    }

    @Test fun sampleCapacityFailureKeepsLocalCompletionButNeverInventsProof() {
        val fixture=fixtures.getJSONObject(0)
        val capture=ConquestServerCapture("u",catalog(fixture),0,maxSamples=3)
        val result=samples(fixture).flatMap(capture::onSample).single()
        assertEquals("ab",result.event.roadSegmentId);assertNull(result.request)
    }

    @Test fun teamBoundarySourceAndUserChangesOrResetRequireNewEntrance() {
        val fixture=fixtures.getJSONObject(0);val samples=samples(fixture)
        val middle=ConquestServerCapture("u",catalog(fixture),samples[3].timestampUtcMs)
        assertTrue(samples.flatMap(middle::onSample).isEmpty())
        for(change in listOf<(LocationSample)->LocationSample>({it.copy(source=LocationSourceType.MOCK)},{it.copy(userId="other")})) {
            val capture=ConquestServerCapture("u",catalog(fixture),0)
            assertTrue(samples.flatMapIndexed {i,s ->capture.onSample(if(i==3) change(s) else s)}.isEmpty())
        }
        val reset=ConquestServerCapture("u",catalog(fixture),0)
        samples.take(3).forEach(reset::onSample);reset.reset()
        assertTrue(samples.drop(3).flatMap(reset::onSample).isEmpty())
    }
}
