package com.daehyundai.map.feature.conquest.cloud

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import java.io.Closeable
import java.io.File
import org.json.JSONArray

internal data class ConquestOutboxLimits(val maxEvents: Int = 1000, val maxProofBytes: Long = 64L*1024*1024) {
    init { require(maxEvents>0 && maxProofBytes>0) }
}
internal data class QueuedConquestSubmission(val id: Long, val request: ConquestSubmission)
internal class ConquestOutboxFullException:IllegalStateException("Conquest outbox full; existing proofs preserved")

/** Feature-private SQLite. Call inside withCurrentAccount when adding/ACKing account-owned work. */
internal class ConquestOutbox(
    private val file: File,
    val binding: ConquestAccountBinding,
    private val limits: ConquestOutboxLimits,
    private val beforeCommit: () -> Unit = {}, // Deterministic storage failure injection, never HTTP/UI.
) : Closeable {
    private val db: SQLiteDatabase = ConquestAccountRestore.withRecordAccess {
        val existed=file.exists()
        val parent=requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val database=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.CREATE_IF_NECESSARY,
            DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Conquest outbox is corrupt; original preserved") })
        try {
            database.rawQuery("PRAGMA secure_delete=ON",null).use {check(it.moveToFirst() && it.getInt(0)==1)}
            if(!existed) {
                database.beginTransaction()
                try {
                    database.execSQL("CREATE TABLE scope(id INTEGER PRIMARY KEY CHECK(id=1), project TEXT NOT NULL, auth_user TEXT NOT NULL, installation TEXT NOT NULL, partition TEXT NOT NULL, catalog TEXT NOT NULL)")
                    database.execSQL("INSERT INTO scope VALUES(1,?,?,?,?,?)",scopeValues())
                    database.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, hash TEXT NOT NULL, bytes INTEGER NOT NULL CHECK(bytes>0), blocked INTEGER NOT NULL DEFAULT 0 CHECK(blocked IN (0,1)))")
                    database.execSQL("CREATE TABLE outcomes(event TEXT PRIMARY KEY, hash TEXT NOT NULL, status TEXT NOT NULL, received_ms INTEGER NOT NULL)")
                    database.version=1
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
            }
            check(database.version==1) { "Explicit outbox migration required" }
            database.rawQuery("SELECT project,auth_user,installation,partition,catalog FROM scope WHERE id=1",null).use {
                check(it.moveToFirst() && (0..4).map(it::getString)==scopeValues().toList() && !it.moveToNext()) {
                    "Conquest outbox account mismatch"
                }
            }
            // Validate the schema without resetting a partial, damaged or unknown store.
            database.rawQuery("SELECT id,event,payload,hash,bytes,blocked FROM outbox LIMIT 0",null).close()
            database.rawQuery("SELECT event,hash,status,received_ms FROM outcomes LIMIT 0",null).close()
            database
        } catch(failure:Exception) { database.close();throw failure }
    }

    private fun scopeValues(): Array<String> = arrayOf(binding.projectRef,binding.authUserId,binding.installationId,
        binding.partition.name,binding.catalogVersion)
    private fun <T> access(action:()->T):T=ConquestAccountRestore.withRecordAccess(action)
    private fun <T> transaction(action:()->T):T=access {
        db.beginTransaction()
        try { val result=action();beforeCommit();db.setTransactionSuccessful();result }
        finally { db.endTransaction() }
    }

    /** Device-wide pending-proof budget across this app's flat server-accounts namespace. */
    private fun pendingUsage():Pair<Long,Long> {
        var events=0L;var bytes=0L
        val files=checkNotNull(file.absoluteFile.parentFile!!.listFiles())
        for(candidate in files.filter {it.isFile && it.extension=="sqlite"}) {
            val own=candidate.canonicalPath==file.canonicalPath
            val database=if(own) db else SQLiteDatabase.openDatabase(candidate.absolutePath,null,SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler {throw SQLiteDatabaseCorruptException("Conquest quota store corrupt; original preserved")})
            try {
                check(database.version==1) {"Cannot account for unknown outbox version"}
                database.rawQuery("SELECT count(*),coalesce(sum(bytes),0),coalesce(min(bytes),0) FROM outbox",null).use {
                    check(it.moveToFirst() && it.getLong(2)>=0)
                    events=Math.addExact(events,it.getLong(0));bytes=Math.addExact(bytes,it.getLong(1))
                }
            } finally {if(!own) database.close()}
        }
        return events to bytes
    }

    /** Same immutable event is idempotent. Capacity never evicts existing pending/blocked proofs. */
    fun enqueue(request:ConquestSubmission):Boolean=transaction {
        db.rawQuery("SELECT hash FROM outbox WHERE event=? UNION ALL SELECT hash FROM outcomes WHERE event=?",
            arrayOf(request.eventId,request.eventId)).use {
            if(it.moveToFirst()) {
                check(it.getString(0)==request.requestHash) { "Conquest event content changed" }
                return@transaction false
            }
        }
        val bytes=request.requestJson.toByteArray(Charsets.UTF_8).size
        val usage=pendingUsage()
        if(usage.first>=limits.maxEvents || usage.second>limits.maxProofBytes-bytes) throw ConquestOutboxFullException()
        db.execSQL("INSERT INTO outbox(event,payload,hash,bytes) VALUES(?,?,?,?)",
            arrayOf(request.eventId,request.requestJson,request.requestHash,bytes))
        true
    }

    fun pendingCount():Int=access { db.rawQuery("SELECT count(*) FROM outbox",null).use {it.moveToFirst();it.getInt(0)} }
    fun blockedCount():Int=access { db.rawQuery("SELECT count(*) FROM outbox WHERE blocked=1",null).use {it.moveToFirst();it.getInt(0)} }

    /** Release record/SQLite locks before invoking an authenticated RPC. */
    fun batch():List<QueuedConquestSubmission> = access {
        val result=mutableListOf<QueuedConquestSubmission>()
        val payload=JSONArray()
        db.rawQuery("SELECT id,payload,hash,event,bytes FROM outbox WHERE blocked=0 ORDER BY id LIMIT 20",null).use {
            while(it.moveToNext()) {
                val request=ConquestSubmission.restore(it.getString(1))
                check(request.requestHash==it.getString(2) && request.eventId==it.getString(3) &&
                    request.requestJson.toByteArray(Charsets.UTF_8).size==it.getInt(4)) { "Corrupt conquest submission" }
                val candidate=JSONArray(payload.toString()).put(request.requestJson)
                if(candidate.toString().toByteArray(Charsets.UTF_8).size>1048576) break
                payload.put(request.requestJson)
                result.add(QueuedConquestSubmission(it.getLong(0),request))
            }
        }
        result
    }

    /** Durable result metadata and proof deletion/blocking commit together. Missing rows stay pending. */
    fun apply(sent:List<QueuedConquestSubmission>, response:ConquestSubmitResponse)=transaction {
        val byEvent=sent.associateBy {it.request.eventId}
        require(byEvent.size==sent.size)
        for(outcome in response.outcomes) {
            val item=requireNotNull(byEvent[outcome.eventId])
            db.rawQuery("SELECT hash,payload FROM outbox WHERE id=? AND event=? AND blocked=0",arrayOf(item.id.toString(),outcome.eventId)).use {
                check(it.moveToFirst() && it.getString(0)==item.request.requestHash && it.getString(1)==item.request.requestJson) {
                    "Conquest ACK no longer matches pending row"
                }
            }
            if(outcome.status==ConquestSubmitStatus.FUTURE) continue
            db.execSQL("INSERT OR REPLACE INTO outcomes(event,hash,status,received_ms) VALUES(?,?,?,?)",
                arrayOf(outcome.eventId,item.request.requestHash,outcome.status.name,response.receivedUtcMs))
            if(outcome.status.acknowledge) db.delete("outbox","id=? AND event=?",arrayOf(item.id.toString(),outcome.eventId))
            else db.execSQL("UPDATE outbox SET blocked=1 WHERE id=?",arrayOf(item.id))
        }
    }

    fun outcome(eventId:String):ConquestSubmitStatus? = access {
        db.rawQuery("SELECT status FROM outcomes WHERE event=?",arrayOf(eventId)).use {
            if(it.moveToFirst()) ConquestSubmitStatus.valueOf(it.getString(0)) else null
        }
    }
    override fun close()=access {db.close()}

    companion object {
        /** Auth is checked again before the first durable scope/DB creation. Never call under a record lock. */
        fun openCurrent(context:Context, server:ConquestAccountAccess, expected:ConquestAuthSession,
            installationId:String, partition:ConquestServerPartition, catalog:String,
            limits:ConquestOutboxLimits=ConquestOutboxLimits()):ConquestAccountResult<ConquestOutbox> =
            server.withCurrentAccount(expected) {
                val binding=ConquestAccountBinding(expected.projectRef,expected.authUserId,installationId,partition,catalog)
                ConquestOutbox(File(context.filesDir,"conquest/server-accounts/${binding.storageKey()}.sqlite"),binding,limits)
            }
    }
}
