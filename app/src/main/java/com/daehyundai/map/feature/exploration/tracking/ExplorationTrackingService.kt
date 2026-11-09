package com.daehyundai.map.feature.exploration.tracking

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import com.daehyundai.map.MainActivity
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.ExplorationSession
import com.daehyundai.map.feature.exploration.openExplorationSession
import com.daehyundai.map.feature.exploration.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TrackingSnapshot(val running:Boolean=false,val userId:String?=null,
    val session:ExplorationSession?=null,val policy:PleosAccuracyPolicy=PleosAccuracyPolicy.UNVERIFIED,
    val message:String="탐험 기록 꺼짐",val voice:String="",val receivedCount:Int=0,val lastSampleTimestamp:Long?=null)

object ExplorationTracking {
    internal val mutable=MutableStateFlow(TrackingSnapshot())
    val state=mutable.asStateFlow()
    fun start(context:Context,userId:String) {
        require(userId.isNotBlank())
        context.startForegroundService(Intent(context,ExplorationTrackingService::class.java).putExtra("userId",userId))
    }
    fun stop(context:Context) { context.stopService(Intent(context,ExplorationTrackingService::class.java)) }
}

class ExplorationTrackingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var speaker:PleosUnlockSpeaker?=null
    private var announcements:UnlockAnnouncements?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"탐험 기록",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) { stopSelf(); return START_NOT_STICKY }
        if(job?.isActive==true) return START_NOT_STICKY
        val userId=intent?.getStringExtra("userId")
        if(userId.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            check(checkSelfPermission(PLEOS_LOCATION_PERMISSION)==PackageManager.PERMISSION_GRANTED)
            check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            check(getSystemService(LocationManager::class.java).isLocationEnabled) { "차량 위치 설정을 켜 주세요" }
            if(Build.VERSION.SDK_INT>=29) startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID,notification())
        } catch(_:Exception) {
            ExplorationTracking.mutable.update { it.copy(running=false,message="기록 시작 실패 · 위치 권한·설정 확인 필요") }
            stopSelf(); return START_NOT_STICKY
        }
        ExplorationTracking.mutable.value=TrackingSnapshot(running=true,userId=userId,message="백그라운드 탐험 기록 시작 중")
        speaker=PleosUnlockSpeaker(this,scope) { text -> ExplorationTracking.mutable.update { it.copy(voice=text) } }
        announcements=UnlockAnnouncements(scope,{ text -> speaker?.speak(text) })
        job=scope.launch {
            try {
                val policy=pleosAccuracyPolicy(this@ExplorationTrackingService)
                val session=withContext(Dispatchers.IO) {
                    openExplorationSession(this@ExplorationTrackingService,userId,LocationSourceType.PLEOS,policy.recordPrefix)
                }
                session.refresh()
                ExplorationTracking.mutable.update { it.copy(session=session,policy=policy,message="SDK 위치 업데이트 대기") }
                PleosLocationSource(userId,{ PleosSdkConnection(this@ExplorationTrackingService) },accuracyPolicy=policy)
                    .samples().collect { sample ->
                        val result=session.acceptSdk(sample)
                        announcements?.record(result)
                        ExplorationTracking.mutable.update { it.copy(receivedCount=it.receivedCount+1,lastSampleTimestamp=sample.timestampUtcMs,message=session.status) }
                    }
            } catch(cancelled:CancellationException) { throw cancelled
            } catch(_:Exception) {
                ExplorationTracking.mutable.update { it.copy(message="기록 오류 · 기존 기록 보존, 권한·위치 공유·저장 공간 확인 후 다시 시작") }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,javaClass).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("탐험 기록 중").setContentText("화면 밖에서도 기록합니다 · 눌러서 확인")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"기록 중지",stop).build()).build()
    }
    override fun onDestroy() {
        announcements?.cancel(); speaker?.close(); scope.cancel()
        ExplorationTracking.mutable.update { it.copy(running=false,message=if(it.message.startsWith("기록 오류")) it.message else "탐험 기록 중지") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { private const val CHANNEL="exploration-tracking";private const val ID=7101;private const val STOP="exploration.STOP" }
}
