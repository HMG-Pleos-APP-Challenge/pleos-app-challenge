package com.daehyundai.map.feature.conquest.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.conquest.domain.ConquestCatalog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Offline preparation only. Caller must stop the old session before activating the returned bundle.
 * An atomic directory rename publishes record and pending together. Legacy files remain untouched.
 */
object ConquestCatalogMigration {
    data class Bundle(val record: File, val pending: File)

    /** Prototype mock records may belong to a fixed demo ID, not this installation.
     * Preserve those records and start this user's mock namespace rather than importing another owner.
     */
    fun userDirectory(directory: File, userId: String, source: LocationSourceType,
        old: ConquestCatalog): File {
        if (source != LocationSourceType.MOCK) return directory
        val record = FileConquestStore(File(directory, "mock-${old.version}.bin")).read() ?: return directory
        require(record.datasetVersion == old.version && record.source == source)
        if (record.teams.none { it.userId != userId } && record.events.none { it.userId != userId }) return directory
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "mock-users/$key")
    }

    fun prepare(directory: File, prefix: String, userId: String, teams: Set<String>,
        source: LocationSourceType, old: ConquestCatalog, next: ConquestCatalog,
        allowProgress: Boolean = false): Bundle =
        ConquestAccountRestore.withRecordAccess {
            require(prefix in if (source == LocationSourceType.MOCK) setOf("mock") else setOf("pleos", "pleos-emulator"))
            require(userId.isNotBlank() && old.version != next.version)
            require(listOf(old.version, next.version).all { it.matches(Regex("[A-Za-z0-9._-]+")) })
            val nextRoads = next.roads.associateBy { it.id }
            // This migration is deliberately identity-only: no splitting or duplicating old contributions.
            require(old.roads.all { road -> nextRoads[road.id]?.let {
                it.fromNode == road.fromNode && it.toNode == road.toNode && it.points == road.points
            } == true }) { "Migration requires unchanged legacy road identities and geometry" }
            val legacy = File(directory, "$prefix-${old.version}.bin")
            val legacyPending = File(directory, "${legacy.name}.pending")
            val record = FileConquestStore(legacy).read()
            require(record != null || !legacyPending.exists()) { "Pending journal without legacy record" }
            if (record != null) {
                require(record.teams.all { it.userId == userId } && record.events.all { it.userId == userId })
                LocalConquestFeature(teams, old.roads.map { it.id }.toSet(), old.version, source,
                    FileConquestStore(legacy), { record.observedUtcMs })
            }
            val pending = FilePendingVisitsStore(legacyPending, old.version, source, userId).read()
            require(pending.all { event -> old.roads.any { it.id == event.roadSegmentId } })
            fun fingerprint(file: File): String = if (!file.exists()) "missing" else
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val receipt = listOf("CONQUEST_MIGRATION_V1", prefix, userId, old.version, next.version,
                fingerprint(legacy), fingerprint(legacyPending)).joinToString("\n")
            val target = File(directory, "prepared-$prefix-${next.version}")
            fun bundle(root: File) = Bundle(File(root, "record.bin"), File(root, "record.bin.pending"))
            if (target.exists()) {
                check(File(target, "receipt").readText() == receipt) { "Migration source changed or target conflict" }
                val result = bundle(target)
                check(result.pending.isFile && (record == null || result.record.isFile)) { "Incomplete prepared bundle" }
                val current = FileConquestStore(result.record).read()
                if (allowProgress) {
                    require(current == null || (current.teams.all { it.userId == userId } && current.events.all { it.userId == userId }))
                    LocalConquestFeature(teams, nextRoads.keys, next.version, source, FileConquestStore(result.record))
                    require(FilePendingVisitsStore(result.pending, next.version, source, userId).read().all { it.roadSegmentId in nextRoads })
                } else {
                    check(current == record?.copy(datasetVersion = next.version))
                    check(FilePendingVisitsStore(result.pending, next.version, source, userId).read() == pending)
                }
                return@withRecordAccess result
            }
            check(directory.isDirectory || directory.mkdirs())
            val stage = Files.createTempDirectory(directory.toPath(), ".migration-").toFile()
            try {
                val staged = bundle(stage)
                if (record != null) FileConquestStore(staged.record).write(record.copy(datasetVersion = next.version))
                FilePendingVisitsStore(staged.pending, next.version, source, userId).write(pending)
                check(FileConquestStore(staged.record).read() == record?.copy(datasetVersion = next.version))
                check(FilePendingVisitsStore(staged.pending, next.version, source, userId).read() == pending)
                File(stage, "receipt").outputStream().use { stream ->
                    stream.write(receipt.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    stream.fd.sync()
                }
                check(fingerprint(legacy) == receipt.lines()[5] && fingerprint(legacyPending) == receipt.lines()[6])
                Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                bundle(target)
            } finally {
                // Only the unique staging directory created above may be removed.
                stage.listFiles()?.forEach { it.delete() }
                stage.delete()
            }
        }
}
