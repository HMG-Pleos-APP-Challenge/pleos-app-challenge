package com.daehyundai.map

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.ConquestLocationPipeline
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpandedSharedLocationIntegrationTest {
    @get:Rule val temp=TemporaryFolder()
    private val source=LocationSourceType.PLEOS
    private val raw=File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private val roads=ConquestCatalog(raw.version, raw.roads.sortedBy { if (it.id == "osm:way:55055096:v24:414929156:4641680981") 0 else 1 })
    private val base=Instant.parse("2026-11-11T04:00:00Z").toEpochMilli()
    private fun samples():List<LocationSample> {
        val road=roads.roads.first()
        val points=road.points.zipWithNext().flatMap { (a,b) -> (0..4).map { n ->
            val t=n/5.0;ConquestPoint(a.latitude+(b.latitude-a.latitude)*t,a.longitude+(b.longitude-a.longitude)*t)
        } } + road.points.last()
        val a=road.points[road.points.lastIndex-1];val b=road.points.last()
        val beyond=ConquestPoint(b.latitude+(b.latitude-a.latitude)*.2,b.longitude+(b.longitude-a.longitude)*.2)
        return (listOf(points.first())+points+beyond).mapIndexed { n,p -> LocationSample("shared-$n","u",p.latitude,p.longitude,base+n*2000,3f,source) }
    }
    private class Memory:ConquestStore {
        var record:ConquestRecord?=null
        override fun read()=record
        override fun write(record:ConquestRecord) { this.record=record }
    }
    @Test fun sameSdkSamplesUnlockExplorationAndCompleteOneDirectedRoadWithoutDoubleCounting()=runTest {
        var now=base
        val boundary=File("src/main/assets/exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
        val cells=File("src/main/assets/exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
        val exploration=LocalExplorationFeature(boundary,source,
            FileDiscoveryStore(File(temp.root,"regions.bin"),boundary.datasetVersion,source),
            LocalCellExploration(boundary,cells,source,FileCellUnlockStore(File(temp.root,"cells.bin"),cells.version,source)))
        val game=LocalConquestFeature(setOf("red","blue"),roads.roads.map { it.id }.toSet(),roads.version,source,Memory(),{now})
        val conquest=ConquestLocationPipeline(game,roads,source);conquest.selectTeam("u","red")
        val fixes=samples()
        fixes.forEach { sample -> now=sample.timestampUtcMs;exploration.onLocation(sample);conquest.onSample(sample) }
        assertEquals(1,game.getSummary("u").contributionCount)
        assertEquals(1,exploration.getSummary("u").discoveredRegionCount)
        assertTrue(exploration.getSummary("u").exploredUnitCount>0)
        val explored=exploration.getSummary("u").exploredUnitCount
        fixes.forEach { exploration.onLocation(it);conquest.onSample(it) }
        assertEquals(1,game.getSummary("u").contributionCount)
        assertEquals(explored,exploration.getSummary("u").exploredUnitCount)
    }
    @Test fun serviceStopCannotJoinTwoIncompleteRoadTraversals()=runTest {
        var now=base
        val game=LocalConquestFeature(setOf("red"),roads.roads.map { it.id }.toSet(),roads.version,source,Memory(),{now})
        val pipeline=ConquestLocationPipeline(game,roads,source);pipeline.selectTeam("u","red")
        val fixes=samples();val half=fixes.size/2
        fixes.take(half).forEach { now=it.timestampUtcMs;pipeline.onSample(it) }
        pipeline.resetObservations("u")
        fixes.drop(half).forEach { now=it.timestampUtcMs;pipeline.onSample(it) }
        assertEquals(0,game.getSummary("u").contributionCount)
    }
}
