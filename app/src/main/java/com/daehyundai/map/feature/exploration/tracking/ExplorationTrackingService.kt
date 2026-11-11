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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackingSnapshot(val running:Boolean=false,val userId:String?=null,
    val session:ExplorationSession?=null,val conquest:com.daehyundai.map.feature.conquest.ConquestSession?=null,val policy:PleosAccuracyPolicy=PleosAccuracyPolicy.UNVERIFIED,
    val message:String="위치 연결 준비 중",val voice:String="",val receivedCount:Int=0,val lastSampleTimestamp:Long?=null)

object ExplorationTracking {
    // Restoration needs both locks. Upload latency must never hold up incoming location samples.
    internal val sampleAccess = Mutex()
    internal val syncAccess = Mutex()
    internal val mutable=MutableStateFlow(TrackingSnapshot())
    val state=mutable.asStateFlow()
    fun start(context:Context,userId:String,automatic:Boolean=false,recordConquest:Boolean=true) {
        require(userId.isNotBlank())
        context.startForegroundService(Intent(context,ExplorationTrackingService::class.java).putExtra("userId",userId).putExtra("automatic",automatic).putExtra("recordConquest",recordConquest))
    }
    fun stop(context:Context) { context.stopService(Intent(context,ExplorationTrackingService::class.java)) }
}

class ExplorationTrackingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var automatic=false
    private var recordConquest=false
    private var releaseVehicle:(()->Unit)?=null
    private var activeConquest:com.daehyundai.map.feature.conquest.ConquestSession?=null
    private var speaker:PleosUnlockSpeaker?=null
    private var announcements:UnlockAnnouncements?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        releaseVehicle=VehicleSafety.acquire(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"탐험 기록",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        automatic=intent?.getBooleanExtra("automatic",false) ?: false
        recordConquest=intent?.getBooleanExtra("recordConquest",true) ?: true
        if(job?.isActive==true) return START_REDELIVER_INTENT
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
                val conquest=try {
                    com.daehyundai.map.feature.conquest.ConquestSession.open(this@ExplorationTrackingService,userId,
                        source=LocationSourceType.PLEOS,recordPrefix=policy.recordPrefix).also { it.resetInput();it.refresh() }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
                activeConquest=conquest
                session.refresh()
                launch {
                    while(isActive) {
                        session.syncCloud()
                        delay(15_000)
                    }
                }
                ExplorationTracking.mutable.update { it.copy(session=session,conquest=conquest,policy=policy,message="SDK 위치 업데이트 대기") }
                var wasConquestRecording=false
                PleosLocationSource(userId,{ PleosSdkConnection(this@ExplorationTrackingService) },accuracyPolicy=policy)
                    .samples().retryWhen { error, attempt ->
                        if (error is CancellationException || error is SecurityException || error !is Exception) return@retryWhen false
                        ExplorationTracking.mutable.update { it.copy(lastSampleTimestamp=null,message="위치 연결을 다시 시도하고 있어요.") }
                        delay(1_000L shl attempt.coerceAtMost(5).toInt())
                        true
                    }.collect { sample ->
                        ExplorationTracking.sampleAccess.withLock {
                            val result=session.acceptSdk(sample)
                            // Exploration remains continuous. Conquest retains its previous vehicle-state gate.
                            if (recordConquest && shouldRecord(automatic,VehicleSafety.state.value)) {
                                conquest?.onSample(sample)
                                wasConquestRecording=true
                            } else {
                                if(wasConquestRecording) conquest?.onSample(sample.copy(accuracyMeters=null))
                                wasConquestRecording=false
                            }
                            announcements?.record(result)
                            ExplorationTracking.mutable.update { it.copy(receivedCount=it.receivedCount+1,lastSampleTimestamp=sample.timestampUtcMs,message=session.status+" · "+session.cloudStatus) }
                        }
                    }
            } catch(cancelled:CancellationException) { throw cancelled
            } catch(_:Exception) {
                ExplorationTracking.mutable.update { it.copy(message="기록 오류 · 기존 기록 보존, 권한·위치 공유·저장 공간 확인 후 다시 시작") }
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("자동 탐험 기록").setContentText("화면 밖에서도 기록합니다 · 눌러서 확인")
            .setContentIntent(open).setOngoing(true).build()
    }
    override fun onDestroy() {
        activeConquest?.resetInput();activeConquest=null
        announcements?.cancel(); speaker?.close(); scope.cancel();releaseVehicle?.invoke()
        ExplorationTracking.mutable.update { it.copy(running=false,message=if(it.message.startsWith("기록 오류")) it.message else if(it.message.startsWith("기록 시작 실패")) it.message else "위치 권한·설정을 확인해 주세요.") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { private const val CHANNEL="exploration-tracking";private const val ID=7101 }
}
