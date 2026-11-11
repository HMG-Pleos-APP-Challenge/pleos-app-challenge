package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.domain.CellUnlock
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

interface CellUnlockStore {
    fun read(): List<CellUnlock>
    fun write(records: List<CellUnlock>)
}

/** Separate versioned cell snapshot; the stage-1 discovery file is retained without a destructive migration. */
class FileCellUnlockStore(private val file: File, private val version: String,
    private val source: LocationSourceType) : CellUnlockStore {
    override fun read(): List<CellUnlock> {
        if (!file.exists()) return emptyList()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF() == "PLEOS_CELLS_V1")
            require(input.readUTF() == version && input.readUTF() == source.name) { "셀 데이터 버전 확인 필요" }
            val count = input.readInt()
            require(count in 0..100_000)
            val records = List(count) { CellUnlock(input.readUTF(), input.readUTF(), input.readLong(), source) }
            require(records.all { it.userId.isNotBlank() && it.cellId.isNotBlank() && it.timestampUtcMs >= 0 })
            require(records.distinctBy { it.userId to it.cellId }.size == records.size)
            require(input.read() == -1)
            records
        }
    }
    override fun write(records: List<CellUnlock>) {
        require(records.size <= 100_000 && records.all { it.source == source })
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("cells-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeUTF("PLEOS_CELLS_V1"); data.writeUTF(version); data.writeUTF(source.name)
                data.writeInt(records.size)
                records.forEach { data.writeUTF(it.userId); data.writeUTF(it.cellId); data.writeLong(it.timestampUtcMs) }
                data.flush(); output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
