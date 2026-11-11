package com.daehyundai.map

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.ConquestMapScreen
import com.daehyundai.map.feature.exploration.*
import com.daehyundai.map.feature.exploration.domain.CellIndex
import com.daehyundai.map.feature.exploration.location.*
import com.daehyundai.map.feature.exploration.tracking.ExplorationTracking
import com.daehyundai.map.feature.home.HomeScreen
import com.daehyundai.map.feature.profile.*
import com.daehyundai.map.navigation.Routes
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IdentityGate { userId -> PrototypeApp(userId) } }
    }
}

// App-private detail pages; the shared Routes contract and conquest feature are unchanged.
private object DetailPages {
    const val REGION="prototype/region"
    const val CELLS="prototype/cells"
    const val CELL="prototype/cell"
    const val LANDMARK="prototype/landmark"
    const val SETTINGS="prototype/settings"
    const val DEVELOPER="prototype/developer"
    const val SOURCES="prototype/sources"
}

@Composable private fun PrototypeApp(userId:String) {
    val context=LocalContext.current
    val vehicleCapability by com.daehyundai.map.feature.exploration.tracking.VehicleSafety.capability.collectAsState()
    val vehicleState by com.daehyundai.map.feature.exploration.tracking.VehicleSafety.state.collectAsState()
    DisposableEffect(context) {
        val release=com.daehyundai.map.feature.exploration.tracking.VehicleSafety.acquire(context)
        onDispose { release() }
    }
    val autoPrefs=remember { context.getSharedPreferences("exploration-options",android.content.Context.MODE_PRIVATE) }
    var automatic by remember { mutableStateOf(autoPrefs.getBoolean("automatic",false)) }
    val developerEnabled=context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0
    val (mockSession,mockError)=rememberExplorationSession(userId)
    val sdkState=rememberPleosExploration()
    val policy=remember(context) { pleosAccuracyPolicy(context) }
    var conquestRetry by remember { mutableIntStateOf(0) }
    val (mockConquest,mockConquestError)=com.daehyundai.map.feature.conquest.rememberConquestSession(userId,retryToken=conquestRetry)
    val (savedConquest,savedConquestError)=com.daehyundai.map.feature.conquest.rememberConquestSession(userId,LocationSourceType.PLEOS,policy.recordPrefix,conquestRetry)
    val (savedSdkSession,sdkError)=rememberExplorationSession(userId,LocationSourceType.PLEOS,policy.recordPrefix)
    var sdkSelected by rememberSaveable { mutableStateOf(true) }
    val liveSession=sdkState.session.takeIf { sdkState.userId==userId }
    val session=if(sdkSelected) liveSession ?: savedSdkSession else mockSession
    val conquest=if(sdkSelected) sdkState.conquest.takeIf { sdkState.userId==userId } ?: savedConquest else mockConquest
    val conquestSummary=conquest?.state?.summary ?: ConquestSummary(userId,null,0,0,null)
    val conquestAvailable=conquest?.state!=null
    val conquestLoadError=(if(sdkSelected) savedConquestError else mockConquestError).takeIf { !conquestAvailable }
    val conquestMessage=conquestLoadError ?: conquest?.error ?: if(!conquestAvailable) "점령 기록을 불러오는 중입니다." else null
    val summary=session?.summary ?: ExplorationSummary(userId,0,0,0,0,null)
    val recording=sdkState.running && sdkState.userId==userId
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.HOME)) }
    val page=stack.last()
    fun go(target:String) { if(target!=page) stack=stack+target }
    fun back() { if(stack.size>1) stack=stack.dropLast(1) }
    var landmarkId by rememberSaveable { mutableStateOf<String?>(null) }
    var cellCol by rememberSaveable { mutableStateOf<Int?>(null) }
    var cellRow by rememberSaveable { mutableStateOf<Int?>(null) }
    fun openLandmark(id:String) { landmarkId=id;go(DetailPages.LANDMARK) }
    var permissionMessage by remember { mutableStateOf("") }
    fun startTracking() {
        runCatching { ExplorationTracking.start(context,userId,automatic) }
            .onFailure { permissionMessage="기록을 시작하지 못했어요. 다시 시도해 주세요." }
    }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val sdk=context.checkSelfPermission(PLEOS_LOCATION_PERMISSION)==PackageManager.PERMISSION_GRANTED
        val location=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(sdk && location) { permissionMessage="";startTracking() }
        else { automatic=false;autoPrefs.edit().putBoolean("automatic",false).apply();permissionMessage="탐험을 기록하려면 위치 권한을 허용해 주세요." }
    }
    fun requestTracking() {
        sdkSelected=true
        val permissions=buildList {
            add(PLEOS_LOCATION_PERMISSION);add(Manifest.permission.ACCESS_FINE_LOCATION);add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    LaunchedEffect(userId,vehicleCapability) { if(automatic && vehicleCapability==com.daehyundai.map.feature.exploration.tracking.VehicleCapability.AVAILABLE) requestTracking() }
    val scope=rememberCoroutineScope()
    BackHandler(stack.size>1) { back() }
    val title=when(page) {
        Routes.HOME -> "대현대여지도"
        Routes.EXPLORATION_MAP -> "지역 탐험"
        Routes.CONQUEST_MAP -> "도로 점령"
        Routes.PROFILE -> "내 프로필"
        DetailPages.REGION -> "남양읍"
        DetailPages.CELLS -> "세부 탐험"
        DetailPages.CELL -> "탐험한 구역"
        DetailPages.LANDMARK -> "명소 상세"
        DetailPages.SETTINGS -> "설정"
        DetailPages.DEVELOPER -> "개발자 도구"
        else -> "지도·데이터 출처"
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF9DE4CF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal=24.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=72.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),
                    verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    if(stack.size>1) TextButton(onClick={back()}) { Text("‹ 뒤로") }
                    Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                    if(!sdkSelected && page!=Routes.CONQUEST_MAP) Text("모의 기록",style=MaterialTheme.typography.labelLarge)
                    if(recording) TextButton(onClick={go(DetailPages.REGION)}) { Text("● 탐험 기록 중") }
                }
                HorizontalDivider()
                key(page) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=20.dp),
                        verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        val loadError=if(sdkSelected) sdkError.takeIf { liveSession==null } else mockError
                        if(loadError!=null && page!=Routes.CONQUEST_MAP) Text(loadError,color=MaterialTheme.colorScheme.error)
                        if (sdkSelected && sdkState.userId==userId) {
                            sdkState.explorationError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                            sdkState.conquestError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        }
                        val emulatorWithoutVehicleApi=developerEnabled && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) && vehicleCapability==com.daehyundai.map.feature.exploration.tracking.VehicleCapability.UNAVAILABLE
                        if(emulatorWithoutVehicleApi) Text("에뮬레이터 수동 테스트 모드 · 차량 상태 API 미지원, 자동 주행 판정 안 함")
                        if(vehicleState!=com.daehyundai.map.feature.exploration.tracking.VehicleInteraction.UNRESTRICTED && !emulatorWithoutVehicleApi) {
                            Text(if(vehicleState==com.daehyundai.map.feature.exploration.tracking.VehicleInteraction.RESTRICTED)
                                "운행 중에는 탐험을 자동으로 기록해요. 결과는 주차 후 확인해 주세요." else "차량 상태 확인 중 · 상세 화면은 확인 후 열립니다.")
                            if(recording) OutlinedButton(onClick={ExplorationTracking.stop(context)}) { Text("기록 중지") }
                        } else {
                        if (page==Routes.PROFILE && conquestMessage!=null) Text(conquestMessage)
                        if ((page==Routes.HOME || page==Routes.PROFILE || page==Routes.CONQUEST_MAP) &&
                            (conquestLoadError!=null || conquest?.error!=null)) {
                            OutlinedButton(onClick={conquestRetry++}) { Text("점령 기록 다시 불러오기") }
                        }
                        when(page) {
                            Routes.HOME -> HomeScreen(conquestSummary,summary,onNavigate={go(it)},
                                explorationPreview={ExplorationMapPreview(session)},
                                conquestPreview={conquest?.state?.let { com.daehyundai.map.feature.conquest.ConquestMapPreview(it.catalog,it.owners,it.teamIds) }},conquestDemo=conquest?.demoLabel!=null,
                                conquestAvailable=conquestAvailable,conquestMessage=conquestMessage)
                            Routes.CONQUEST_MAP -> {
                                val error=conquestLoadError
                                (error ?: conquest?.error)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                                if(developerEnabled) {
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected=sdkSelected,onClick={sdkSelected=true},label={Text("SDK 점령 기록")})
                                        FilterChip(selected=!sdkSelected,onClick={sdkSelected=false},label={Text("점령 시연·모의 기록")})
                                    }
                                }
                                ConquestMapScreen(conquestSummary,conquest?.state,
                                    onSelectTeam={team -> scope.launch { conquest?.selectTeam(team) }},session=conquest)
                            }
                            Routes.EXPLORATION_MAP -> ExplorationMapScreen(summary,session,{go(DetailPages.REGION)},{go(DetailPages.SOURCES)})
                            DetailPages.REGION -> RegionDetailScreen(session,recording,{requestTracking()},
                                {ExplorationTracking.stop(context)},{go(DetailPages.CELLS)},{openLandmark(it)},
                                {go(DetailPages.SOURCES)},permissionMessage,onCell={cell -> cellCol=cell.col;cellRow=cell.row;go(DetailPages.CELL)})
                            DetailPages.CELLS -> CellsScreen(session) { cell -> cellCol=cell.col;cellRow=cell.row;go(DetailPages.CELL) }
                            DetailPages.CELL -> CellDetailScreen(session,if(cellCol!=null && cellRow!=null) CellIndex(cellCol!!,cellRow!!) else null)
                            DetailPages.LANDMARK -> LandmarkDetailScreen(session,landmarkId,{go(DetailPages.SOURCES)})
                            Routes.PROFILE -> if (conquestAvailable) ProfileScreen(conquestSummary,summary,session?.progress ?: UserProgress(userId,0,1),session?.landmarkSummary,
                                {go(Routes.EXPLORATION_MAP)},{go(DetailPages.CELLS)},{openLandmark(it)},{go(DetailPages.SETTINGS)},
                                conquestDemo=conquest?.demoLabel!=null,onConquest={go(Routes.CONQUEST_MAP)})
                                else Text("점령 요약을 불러온 뒤 프로필을 표시합니다.")
                            DetailPages.SETTINGS -> { PrototypeSettings(recording,developerEnabled,{ExplorationTracking.stop(context)},
                                {go(DetailPages.SOURCES)},{go(DetailPages.DEVELOPER)},automatic,{ enabled ->
                                    automatic=enabled;autoPrefs.edit().putBoolean("automatic",enabled).apply()
                                    if(enabled) requestTracking() else ExplorationTracking.stop(context)
                                },automaticSupported=vehicleCapability==com.daehyundai.map.feature.exploration.tracking.VehicleCapability.AVAILABLE)
                                AccountSettings(userId,canRestoreAccount(recording,session?.hasLocalAchievements ?: true)) { session?.syncCloud() }
                            }
                            DetailPages.DEVELOPER -> if(developerEnabled) DeveloperTools(userId,sdkSelected,sdkState,mockSession,
                                {sdkSelected=it},{scenario -> scope.launch { mockSession?.replay(scenario) }}) else Text("개발자 도구는 개발 빌드에서만 열 수 있어요.")
                            DetailPages.SOURCES -> MapSources()
                        }
                        }
                    }
                }
            }
        }
    }
}
