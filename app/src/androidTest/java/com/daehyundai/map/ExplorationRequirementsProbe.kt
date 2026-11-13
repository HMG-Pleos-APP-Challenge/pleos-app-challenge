package com.daehyundai.map

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.openExplorationSession
import com.daehyundai.map.feature.exploration.cloud.SupabaseCloud
import com.daehyundai.map.feature.profile.data.AccountStartChoice
import com.daehyundai.map.feature.exploration.domain.CellIndex
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class ExplorationRequirementsProbe:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val checks=mutableListOf<String>()
        val folder=File(targetContext.cacheDir,"exploration-requirements-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val isolated=object:ContextWrapper(targetContext) {
            override fun getFilesDir()=folder
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(name:String)=File(folder,name)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler:android.database.DatabaseErrorHandler?) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
        }
        val result=runCatching {
            runBlocking {
                val user="requirements-user"
                val first=openExplorationSession(isolated,user,LocationSourceType.MOCK)
                first.refresh()
                check(!first.regionDiscovered && first.unlockedCells.isEmpty())
                first.replay("outside");first.replay("inaccurate")
                check(!first.regionDiscovered)
                first.replay("inside")
                check(first.regionDiscovered && first.summary.discoveredRegionCount==1)
                checks.add("EXP-03 PASS: local discovery opens collection without a server")
                first.replay("road")
                check(first.unlockedCells.size==4 && first.progress.totalXp==140L && first.progress.level==2)
                first.replay("road")
                check(first.unlockedCells.size==4 && first.progress.totalXp==140L)
                checks.add("EXP-10 PASS: 140 XP, level 2, repeat grants no XP")
                first.replay("landmark-short");first.replay("landmark-exit")
                check(first.landmarkSummary!!.unlockedCount==0)
                first.replay("landmark-dwell")
                check(first.landmarkSummary!!.unlockedCount==1 && first.progress.totalXp==340L)
                val restored=openExplorationSession(isolated,user,LocationSourceType.MOCK)
                restored.refresh()
                check(restored.regionDiscovered && restored.unlockedCells==first.unlockedCells)
                check(restored.progress.totalXp==340L)
                checks.add("EXP-08 EXP-09 PASS: dwell collection and achievements survive reopen")
                // Persist more than the former 30-row limit through the production store.
                val catalog=first.feature.cellExploration!!.catalog
                val many=catalog.cells.keys.take(35)
                first.feature.mergeConfirmed(user,many.map {
                    com.daehyundai.map.feature.exploration.cloud.CloudGrant("cell",catalog.cellId(it),System.currentTimeMillis())
                })
                first.refresh()
                check(first.unlockedCells.containsAll(many))
                checks.add("EXP-19 PASS: 35 distinct stored cells available for the complete list")
            }
            if(SupabaseCloud.configured) runBlocking {
                val user="cloud-fixture"
                var count=0
                var offline=true
                val remote=object:com.daehyundai.map.feature.exploration.cloud.ExplorationCloud {
                    override fun synchronize(partition:String,samples:String):String {
                        if(offline) throw java.io.IOException("Simulated offline")
                        val catalog=isolated.assets.open("exploration/namyang.roadcells").reader()
                            .use(com.daehyundai.map.feature.exploration.domain.RoadCellCatalogReader::read)
                        val indices=catalog.demo.map { catalog.grid.index(catalog.grid.project(it.point)) }.distinct()
                        val grants=org.json.JSONArray().put(JSONObject().put("kind","region").put("target_id",catalog.regionId).put("earned_ms",1_000))
                        indices.take(count).forEach {
                            grants.put(JSONObject().put("kind","cell").put("target_id",catalog.cellId(it)).put("earned_ms",1_000))
                        }
                        return JSONObject().put("catalog_version",catalog.version).put("grants",grants).put("xp",100+10*count).toString()
                    }
                }
                val live=openExplorationSession(isolated,user,LocationSourceType.PLEOS,remote=remote)
                live.refresh()
                val catalog=live.feature.cellExploration!!.catalog
                val base=System.currentTimeMillis()
                for(fix in catalog.demo) live.acceptSdk(com.daehyundai.map.core.model.LocationSample(
                    "cloud-"+fix.id,user,fix.point.latitude,fix.point.longitude,base+fix.offsetMillis,5f,LocationSourceType.PLEOS))
                live.syncCloud()
                check(live.regionDiscovered && live.pendingCells.size==4 && live.summary.exploredUnitCount==0)
                offline=false;count=2;live.syncCloud()
                check(live.unlockedCells.size==4 && live.pendingCells.size==2 && live.summary.exploredUnitCount==2)
                check(live.pendingCells.all { live.cellLabel(it).contains("동기화 대기") })
                count=4;live.syncCloud()
                check(live.pendingCells.isEmpty() && live.summary.exploredUnitCount==4 && live.progress.totalXp==140L)
                checks.add("EXP-07 EXP-14 PASS: offline four local cells, two then four confirmed, XP=140")
            }
            val prefs=targetContext.getSharedPreferences("onboarding-probe-"+folder.name,Context.MODE_PRIVATE)
            val choice=AccountStartChoice(prefs)
            check(!choice.prepare(false))
            check(!AccountStartChoice(prefs).prepare(true)) // process restart after identity creation
            check(choice.complete() && AccountStartChoice(prefs).prepare(true))
            prefs.edit().clear().commit()
            check(AccountStartChoice(prefs).prepare(true)) // existing installation
            prefs.edit().clear().commit()
            checks.add("EXP-18 PASS: pending first-run choice survives restart and completed choice persists")
            val calls=mutableListOf<String>()
            var linked=false
            var verified=false
            fun auth(token:String)=JSONObject().put("access_token",token).put("refresh_token","refresh-"+token)
                .put("expires_in",3600).put("user",JSONObject().put("id",token).put("email_confirmed_at",if(verified) "verified" else JSONObject.NULL)).toString()
            val accountFiles=File(folder,"account").apply { check(mkdirs()) }
            val accountContext=object:ContextWrapper(isolated) {
                override fun getFilesDir()=accountFiles
                override fun getApplicationContext():Context=this
            }
            val cloud=SupabaseCloud(accountContext,"probe-"+folder.name) { path,body,token,method ->
                calls.add(method+" "+path)
                when {
                    path=="/auth/v1/signup" -> auth("guest")
                    path=="/auth/v1/token?grant_type=password" -> {
                        check(body.getString("email")=="fixture@example.invalid")
                        check(body.getString("password")=="fixture-password")
                        auth("restored")
                    }
                    path=="/auth/v1/user" && method=="GET" -> JSONObject().put("id",token)
                        .put("email_confirmed_at",if(verified) "verified" else JSONObject.NULL).toString()
                    path=="/auth/v1/user" && method=="PUT" -> {
                        if(body.has("email")) linked=true
                        if(body.has("password")) check(verified)
                        "{}"
                    }
                    else -> error("Unexpected simulated request")
                }
            }
            check(cloud.existingAccount()==com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.NoSession)
            check(calls.isEmpty())
            check(cloud.account().getString("id")=="guest")
            cloud.linkEmail("fixture@example.invalid")
            check(linked)
            check(runCatching { cloud.setPassword("fixture-password") }.isFailure)
            verified=true
            cloud.setPassword("fixture-password")
            val oldBinding=(cloud.existingAccount() as com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.Success).binding
            cloud.restore("fixture@example.invalid","fixture-password","empty-user")
            val newBinding=(cloud.existingAccount() as com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.Success).binding
            check(newBinding.generation>oldBinding.generation)
            check(cloud.withCurrentAccount(oldBinding){error("old account response applied")}==
                com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.AccountChanged)
            cloud.restore("fixture@example.invalid","fixture-password","empty-user")
            val sameAccount=(cloud.existingAccount() as com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.Success).binding
            check(sameAccount.authUserId==newBinding.authUserId && sameAccount.generation>newBinding.generation)
            check(cloud.withCurrentAccount(newBinding){error("prior login response applied")}==
                com.daehyundai.map.feature.exploration.cloud.AccountAuthResult.AccountChanged)
            checks.add("AUTH PASS: no signup on lookup; replacement generation persists; stale and same-account responses blocked")
            check(cloud.account().getString("id")=="restored")
            val reopened=SupabaseCloud(accountContext,"probe-"+folder.name) { path,_,token,_ ->
                check(path=="/auth/v1/user" && token=="restored")
                JSONObject().put("id",token).toString()
            }
            check(reopened.account().getString("id")=="restored")
            checks.add("EXP-17 PASS: simulated email verification, password and restored encrypted session")
        }
        val report=checks.joinToString("\n")+"\n"+result.fold({"PASS: exploration requirement simulations completed"},{"FAIL: "+it.javaClass.simpleName+": "+it.message})
        File(targetContext.cacheDir,"exploration-requirements-result.txt").writeText(report)
        // Remove only the unique temporary fixture tree created above.
        folder.deleteRecursively()
        finish(if(result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report) })
    }
}
