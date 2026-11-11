package com.daehyundai.map.core.data

import com.daehyundai.map.core.contract.ProgressRepository
import com.daehyundai.map.core.model.*
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single-process app-private award ledger. Separate files partition mock/emulator/real progress. */
class FileProgressRepository(private val file:File,private val xpPerLevel:Long=100):ProgressRepository {
    init { require(xpPerLevel>0) }
    private val mutex=locks.getOrPut(file.canonicalPath) { Mutex() }
    override suspend fun awardOnce(award:XpAward):Boolean=awardAllOnce(listOf(award))==1
    suspend fun awardAllOnce(awards:List<XpAward>):Int=withContext(Dispatchers.IO) { mutex.withLock {
        awards.forEach(::validate)
        val all=read().associateByTo(linkedMapOf()) { it.awardId }
        var added=0
        for(award in awards) {
            val existing=all[award.awardId]
            if(existing!=null) require(existing==award) { "경험치 지급 ID 충돌" }
            else { all[award.awardId]=award;added++ }
        }
        if(added>0) {
            require(all.size<=100_000)
            all.values.groupBy { it.userId }.values.forEach { own -> own.fold(0L) { sum,a -> Math.addExact(sum,a.amount) } }
            write(all.values.toList())
        }
        added
    } }
    override suspend fun getProgress(userId:String):UserProgress=withContext(Dispatchers.IO) { mutex.withLock {
        require(userId.isNotBlank())
        val total=read().filter { it.userId==userId }.fold(0L) { sum,a -> Math.addExact(sum,a.amount) }
        val completedLevels=total/xpPerLevel
        UserProgress(userId,total,if(completedLevels>=Int.MAX_VALUE-1L) Int.MAX_VALUE else completedLevels.toInt()+1)
    } }
    private fun validate(a:XpAward) {
        require(listOf(a.awardId,a.userId,a.reason,a.sourceEventId).all { it.isNotBlank() && it.length<=4096 })
        require(a.amount>0 && a.occurredUtcMs>=0)
    }
    private fun read():List<XpAward> {
        if(!file.exists()) return emptyList()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readUTF()=="PLEOS_XP_V1") { "경험치 저장 형식 확인 필요" }
            val count=input.readInt();require(count in 0..100_000)
            val records=List(count) { XpAward(input.readUTF(),input.readUTF(),input.readLong(),input.readUTF(),input.readUTF(),input.readLong()).also(::validate) }
            require(records.distinctBy { it.awardId }.size==records.size && input.read()==-1)
            records
        }
    }
    private fun write(records:List<XpAward>) {
        val parent=requireNotNull(file.parentFile);check(parent.isDirectory || parent.mkdirs())
        val temp=File.createTempFile("xp-",".tmp",parent)
        try {
            FileOutputStream(temp).use { out ->
                val data=DataOutputStream(out);data.writeUTF("PLEOS_XP_V1");data.writeInt(records.size)
                records.forEach { a -> data.writeUTF(a.awardId);data.writeUTF(a.userId);data.writeLong(a.amount)
                    data.writeUTF(a.reason);data.writeUTF(a.sourceEventId);data.writeLong(a.occurredUtcMs) }
                data.flush();out.fd.sync()
            }
            Files.move(temp.toPath(),file.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    companion object { private val locks=ConcurrentHashMap<String,Mutex>() }
}
