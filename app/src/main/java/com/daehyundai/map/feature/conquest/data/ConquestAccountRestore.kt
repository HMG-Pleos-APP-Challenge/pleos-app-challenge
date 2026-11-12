package com.daehyundai.map.feature.conquest.data

import com.daehyundai.map.core.model.LocationSourceType
import java.io.File

/** Shared by local record writes and account replacement to prevent a check/write race. */
object ConquestAccountRestore {
    private val lock = Any()

    fun <T> withRecordAccess(action: () -> T): T = synchronized(lock) { action() }

    fun canRestore(directory: File): Boolean = withRecordAccess {
        try { recordsAreEmpty(directory) } catch (_: Exception) { false }
    }

    fun <T> restoreWhenEmpty(directory: File, action: () -> T): T = withRecordAccess {
        check(recordsAreEmpty(directory)) { "점령 기록이 있는 기기는 현재 계정에 이메일을 연결해 주세요" }
        action()
    }

    private fun recordsAreEmpty(directory: File): Boolean {
        if (!directory.exists()) return true
        val files = checkNotNull(directory.listFiles()) { "점령 기록 폴더를 읽을 수 없습니다" }
        val prepared = files.filter { it.name.startsWith("prepared-pleos-") }
        if (!prepared.all { bundle ->
            require(bundle.isDirectory && File(bundle, "receipt").isFile)
            require(File(bundle, "record.bin.pending").isFile)
            listOf("record.bin", "record.bin.pending").all { name ->
                val record = FileConquestStore(File(bundle, name)).read()
                record == null || (record.source == LocationSourceType.PLEOS &&
                    record.datasetVersion.isNotBlank() && record.startedUtcMs >= 0 &&
                    record.observedUtcMs >= record.startedUtcMs && record.teams.isEmpty() && record.events.isEmpty())
            }
        }) return false
        return files.filter { it.name.startsWith("pleos-") }.all { file ->
            val record = checkNotNull(FileConquestStore(file).read())
            require(record.source == LocationSourceType.PLEOS && record.datasetVersion.isNotBlank())
            require(record.startedUtcMs >= 0 && record.observedUtcMs >= record.startedUtcMs)
            record.teams.isEmpty() && record.events.isEmpty()
        }
    }
}
