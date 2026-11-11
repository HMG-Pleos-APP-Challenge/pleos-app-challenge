package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.domain.LandmarkUnlock
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

interface LandmarkUnlockStore {
    fun read(): List<LandmarkUnlock>
    fun write(records: List<LandmarkUnlock>)
}
/** Earned collections only; uncompleted dwell, raw GPS and wall-clock timers are never persisted. */
class FileLandmarkUnlockStore(private val file: File, private val version: String,
    private val source: LocationSourceType) : LandmarkUnlockStore {
    override fun read(): List<LandmarkUnlock> {
        if (!file.exists()) return emptyList()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == "PLEOS_LANDMARKS_V1")
            require(input.readUTF() == version && input.readUTF() == source.name) { "랜드마크 데이터 버전 확인 필요" }
            val count = input.readInt()
            require(count in 0..100_000)
            val records = List(count) { LandmarkUnlock(input.readUTF(),input.readUTF(),input.readLong(),source) }
            require(records.all { it.userId.isNotBlank() && it.landmarkId.isNotBlank() && it.timestampUtcMs >= 0 })
            require(records.distinctBy { it.userId to it.landmarkId }.size == records.size)
            require(input.read() == -1)
            records
        }
    }
    override fun write(records: List<LandmarkUnlock>) {
        require(records.size <= 100_000 && records.all { it.source == source })
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("landmarks-", ".tmp",parent)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeUTF("PLEOS_LANDMARKS_V1");data.writeUTF(version);data.writeUTF(source.name)
                data.writeInt(records.size)
                records.forEach { data.writeUTF(it.userId);data.writeUTF(it.landmarkId);data.writeLong(it.timestampUtcMs) }
                data.flush();output.fd.sync()
            }
            Files.move(temporary.toPath(),file.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
