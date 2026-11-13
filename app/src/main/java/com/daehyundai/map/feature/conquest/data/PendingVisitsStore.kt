package com.daehyundai.map.feature.conquest.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.core.model.RoadVisited
import java.io.File

/** Feature-private local recovery journal. Uses the existing atomic file writer. */
interface PendingVisitsStore {
    fun read(): List<RoadVisited>
    fun write(events: List<RoadVisited>)
}

class MemoryPendingVisitsStore : PendingVisitsStore {
    private var events = emptyList<RoadVisited>()
    override fun read() = events.toList()
    override fun write(events: List<RoadVisited>) { this.events = events.toList() }
}

class FilePendingVisitsStore(file: File, private val version: String,
    private val source: LocationSourceType, private val userId: String) : PendingVisitsStore {
    private val store = FileConquestStore(file)

    override fun read(): List<RoadVisited> {
        val record = store.read() ?: return emptyList()
        require(record.datasetVersion == version && record.source == source)
        require(record.startedUtcMs == 0L && record.observedUtcMs == 0L && record.teams.isEmpty())
        validate(record.events)
        return record.events
    }

    override fun write(events: List<RoadVisited>) {
        validate(events)
        store.write(ConquestRecord(version, source, 0, 0, events = events))
    }

    private fun validate(events: List<RoadVisited>) {
        require(events.map { it.eventId }.distinct().size == events.size)
        require(events.all { it.userId == userId && it.source == source &&
            it.eventId.isNotBlank() && it.roadSegmentId.isNotBlank() && it.timestampUtcMs >= 0 })
    }
}
