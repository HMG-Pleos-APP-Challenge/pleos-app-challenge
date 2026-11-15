package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The same persisted inputs are replayed by the isolated PostgreSQL proof test. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[28])
class ServerProofParityTest {
    @Test fun sharedProofFixturesMatchExistingDetector() {
        val fixtures=JSONArray(File("../docs/data/conquest-proof-fixtures.json").readText())
        assertEquals(18,fixtures.length())
        for(index in 0 until fixtures.length()) {
            val fixture=fixtures.getJSONObject(index)
            val jsonRoads=fixture.optJSONArray("roads") ?: JSONArray()
            val roads=(0 until jsonRoads.length()).map { i ->
                val row=jsonRoads.getJSONObject(i)
                val points=row.getJSONArray("points")
                DirectedRoad(row.getString("id"),"fixture","a","b",(0 until points.length()).map { j ->
                    val point=points.getJSONArray(j);ConquestPoint(point.getDouble(0),point.getDouble(1))
                })
            }
            val catalog=if(fixture.optBoolean("runtimeCatalog")) File("src/main/assets/conquest/namyang.segments")
                .reader().use(ConquestCatalogReader::read) else ConquestCatalog("fixture",roads)
            val detector=DirectedRoadDetector(catalog,LocationSourceType.PLEOS)
            val samples=fixture.getJSONArray("samples")
            val completed=(0 until samples.length()).flatMap { i ->
                val row=samples.getJSONObject(i)
                detector.onSample(LocationSample(row.getString("id"),"fixture-user",row.getDouble("lat"),
                    row.getDouble("lon"),row.getLong("time"),
                    if(row.isNull("accuracy")) null else row.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS))
            }
            val expected=fixture.getJSONArray("expected")
            assertEquals(fixture.getString("name"),(0 until expected.length()).map(expected::getString),completed.map { it.roadSegmentId })
            completed.forEach { assertEquals(samples.getJSONObject(samples.length()-1).getLong("time"),it.timestampUtcMs) }
        }
    }
}
