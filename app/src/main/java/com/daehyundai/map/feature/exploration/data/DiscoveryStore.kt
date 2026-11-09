package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.core.model.RegionDiscovered
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

interface DiscoveryStore {
    fun read(): List<RegionDiscovered>
    fun write(records: List<RegionDiscovered>)
}

/** App-private, bounded MVP snapshot; stores discoveries, never raw GPS or pending dwell state. */
class FileDiscoveryStore(
    private val file: File,
    private val datasetVersion: String,
    private val source: LocationSourceType,
) : DiscoveryStore {
    override fun read(): List<RegionDiscovered> {
        if (!file.exists()) return emptyList()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == "PLEOS_DISCOVERY_V1") { "저장 형식 확인 필요" }
            require(input.readUTF() == datasetVersion && input.readUTF() == source.name) { "데이터 버전 마이그레이션 필요" }
            val count = input.readInt()
            require(count in 0..100_000)
            val records = List(count) {
                RegionDiscovered(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), source)
            }
            require(records.all { it.eventId.isNotBlank() && it.userId.isNotBlank() && it.regionId.isNotBlank() && it.timestampUtcMs >= 0 })
            require(records.distinctBy { it.userId to it.regionId }.size == records.size)
            require(input.read() == -1) { "알 수 없는 저장 데이터" }
            records
        }
    }

    override fun write(records: List<RegionDiscovered>) {
        require(records.size <= 100_000 && records.all { it.source == source })
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "저장 폴더를 만들 수 없습니다" }
        val temporary = File.createTempFile("discovery-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeUTF("PLEOS_DISCOVERY_V1")
                data.writeUTF(datasetVersion)
                data.writeUTF(source.name)
                data.writeInt(records.size)
                records.forEach {
                    data.writeUTF(it.eventId); data.writeUTF(it.userId); data.writeUTF(it.regionId)
                    data.writeLong(it.timestampUtcMs)
                }
                data.flush()
                output.fd.sync()
            }
            // Same-directory atomic replacement: failure must keep the previous snapshot intact.
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
