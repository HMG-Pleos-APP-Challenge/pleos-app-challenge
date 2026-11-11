package com.daehyundai.map.feature.conquest.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.core.model.RoadVisited
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class TeamSelection(val userId: String, val teamId: String, val selectedUtcMs: Long)
data class ConquestRecord(
    val datasetVersion: String,
    val source: LocationSourceType,
    val startedUtcMs: Long,
    val observedUtcMs: Long,
    val teams: List<TeamSelection> = emptyList(),
    val events: List<RoadVisited> = emptyList(),
)

/** Only conquest owns this store. Use different files for MOCK and PLEOS. */
interface ConquestStore {
    fun read(): ConquestRecord?
    fun write(record: ConquestRecord)
}

/** Atomic replacement: a failed write does not truncate the previous accepted record. */
class FileConquestStore(private val file: File) : ConquestStore {
    override fun read(): ConquestRecord? {
        if (!file.exists()) return null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == "PLEOS_CONQUEST_V1") { "Unsupported conquest record" }
            val version = input.readUTF()
            val source = LocationSourceType.valueOf(input.readUTF())
            val start = input.readLong()
            val observed = input.readLong()
            fun count(): Int = input.readInt().also { require(it in 0..1_000_000) }
            val teams = List(count()) { TeamSelection(input.readUTF(), input.readUTF(), input.readLong()) }
            val events = List(count()) { RoadVisited(input.readUTF(), input.readUTF(), input.readUTF(),
                input.readLong(), LocationSourceType.valueOf(input.readUTF())) }
            require(input.read() == -1) { "Unexpected trailing record data" }
            return ConquestRecord(version, source, start, observed, teams, events)
        }
    }

    override fun write(record: ConquestRecord) {
        val target = file.absoluteFile
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create conquest directory" }
        val temporary = File.createTempFile("conquest-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeUTF("PLEOS_CONQUEST_V1")
                out.writeUTF(record.datasetVersion)
                out.writeUTF(record.source.name)
                out.writeLong(record.startedUtcMs)
                out.writeLong(record.observedUtcMs)
                out.writeInt(record.teams.size)
                record.teams.forEach { out.writeUTF(it.userId); out.writeUTF(it.teamId); out.writeLong(it.selectedUtcMs) }
                out.writeInt(record.events.size)
                record.events.forEach {
                    out.writeUTF(it.eventId); out.writeUTF(it.userId); out.writeUTF(it.roadSegmentId)
                    out.writeLong(it.timestampUtcMs); out.writeUTF(it.source.name)
                }
                out.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
