package com.daehyundai.map.feature.exploration.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.daehyundai.map.core.model.LocationSample
import org.json.JSONArray
import org.json.JSONObject

/** Durable outbox. An acknowledgement removes exactly the transmitted rows, never newer samples. */
class CloudCache(context: Context) : SQLiteOpenHelper(context, "exploration-cloud.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, partition TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(owner,partition,event))")
        db.execSQL("CREATE TABLE snapshots(owner TEXT NOT NULL, partition TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner,partition))")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("Explicit migration required")
    fun enqueue(owner: String, partition: String, sample: LocationSample) {
        val json = JSONObject().put("id", sample.eventId).put("time", sample.timestampUtcMs)
            .put("lat", sample.latitude).put("lon", sample.longitude)
            .put("accuracy", sample.accuracyMeters ?: JSONObject.NULL)
        val values = ContentValues().apply {
            put("owner", owner); put("partition", partition); put("event", sample.eventId); put("payload", json.toString())
        }
        check(count(owner,partition) < 10000) { "전송 대기열 가득 참 · 기존 기록 보존" }
        writableDatabase.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE).also { check(it != -1L || exists(owner,partition,sample.eventId)) }
    }
    private fun exists(owner:String, partition:String,event:String):Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM outbox WHERE owner=? AND partition=? AND event=?", arrayOf(owner,partition,event)).use { it.moveToFirst() }
    fun count(owner:String, partition:String):Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM outbox WHERE owner=? AND partition=?", arrayOf(owner,partition)).use { it.moveToFirst();it.getInt(0) }
    fun batch(owner:String, partition:String): Pair<List<Long>,String> {
        val ids=mutableListOf<Long>();val array=JSONArray()
        readableDatabase.rawQuery("SELECT id,payload FROM outbox WHERE owner=? AND partition=? ORDER BY id LIMIT 100", arrayOf(owner,partition)).use {
            while(it.moveToNext()) { ids.add(it.getLong(0));array.put(JSONObject(it.getString(1))) }
        }
        return ids to array.toString()
    }
    fun acknowledge(owner:String,partition:String,ids:List<Long>,response:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val values=ContentValues().apply { put("owner",owner);put("partition",partition);put("payload",response) }
            db.insertWithOnConflict("snapshots",null,values,SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
            ids.forEach { db.delete("outbox","owner=? AND partition=? AND id=?", arrayOf(owner,partition,it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun snapshot(owner:String,partition:String,expectedVersion:String?=null):CloudSnapshot? = readableDatabase.rawQuery(
        "SELECT payload FROM snapshots WHERE owner=? AND partition=?",arrayOf(owner,partition)).use {
        if(it.moveToFirst()) parse(it.getString(0),expectedVersion) else null
    }
    companion object {
        fun parse(raw:String,expectedVersion:String?=null):CloudSnapshot {
            val json=JSONObject(raw)
            if(expectedVersion!=null && json.optString("catalog_version")!=expectedVersion) throw CatalogVersionMismatch()
            val array=json.getJSONArray("grants")
            val grants=(0 until array.length()).map { array.getJSONObject(it).let { row ->
                CloudGrant(row.getString("kind"),row.getString("target_id"),row.getLong("earned_ms")) } }
            return CloudSnapshot(grants,json.getLong("xp"))
        }
    }
}
