package com.daehyundai.map

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.conquest.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Actual Android storage/session check. Private cache folder; never touches GPS or user files. */
class IntegrationSmokeProbe:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            runBlocking {
                val folder=File(targetContext.cacheDir,"integration-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val isolated=object:ContextWrapper(targetContext) { override fun getFilesDir()=folder }
                val first=async { ConquestSession.open(isolated,"test-user") }
                val second=async { ConquestSession.open(isolated,"test-user") }
                val mock=first.await();check(mock===second.await())
                mock.refresh();mock.selectTeam("team-red")
                val sdk=ConquestSession.open(isolated,"test-user",source=LocationSourceType.PLEOS,recordPrefix="pleos-emulator")
                sdk.refresh();check(sdk!==mock);check(sdk.state?.summary?.teamId==null);check(!sdk.demoEnabled)
                mock.replayDemo(DemoRoute.COMPLETE)
                check(mock.state?.summary?.contributionCount==1);check(mock.state?.summary?.ownedRoadCount==0)
                mock.settleDemo();check(mock.state?.summary?.ownedRoadCount==1)
                mock.exitDemo();check(mock.state?.summary?.contributionCount==0)
                for(route in listOf(DemoRoute.MIDWAY,DemoRoute.U_TURN)) {
                    mock.replayDemo(route);mock.settleDemo();check(mock.state?.summary?.ownedRoadCount==0)
                }
                mock.exitDemo();check(sdk.state?.summary?.contributionCount==0)
                finish(Activity.RESULT_OK,Bundle().apply { putString("result","PASS: single instance per file, SDK/mock isolation, complete route + noon settlement, midway/U-turn rejection, demo leaves saved progress unchanged; GPS untouched") })
            }
        } catch(e:Exception) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","FAIL: ${e.javaClass.simpleName}: ${e.message}") }) }
    }
}
