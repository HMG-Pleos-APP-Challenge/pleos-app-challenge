package com.daehyundai.map.feature.profile

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daehyundai.map.feature.profile.data.InstallationIdentity
import com.daehyundai.map.feature.exploration.data.migrateLegacyExploration
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.*

@Composable fun IdentityGate(content:@Composable (String)->Unit) {
    val context=LocalContext.current.applicationContext
    var user by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val preferences=remember { context.getSharedPreferences("exploration-onboarding",android.content.Context.MODE_PRIVATE) }
    val accountChoice=remember { com.daehyundai.map.feature.profile.data.AccountStartChoice(preferences) }
    var chosen by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try {
            user=withContext(Dispatchers.IO) {
                val identityFile=File(context.filesDir,"identity/installation-user.txt")
                // Existing installations keep their established recording flow.
                val existing=identityFile.exists()
                accountChoice.prepare(existing)
                val id=InstallationIdentity(identityFile).getOrCreate()
                val boundary=context.assets.open("exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
                val roads=context.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
                val landmarks=context.assets.open("exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
                com.daehyundai.map.feature.exploration.data.migrateGrid500(File(context.filesDir,"exploration"),roads)
                migrateLegacyExploration(File(context.filesDir,"exploration"),boundary,roads,landmarks,id)
                id
            }
            chosen=preferences.getBoolean("chosen",false)
        } catch(cancelled:CancellationException) { throw cancelled
        } catch(_:Exception) { error=true }
    }
    val id=user
    if(id!=null && chosen) content(id)
    else if(id!=null) {
        var restoring by remember { mutableStateOf(false) }
        var savingError by remember { mutableStateOf(false) }
        fun proceed() {
            if(accountChoice.complete()) chosen=true
            else savingError=true
        }
        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("내 탐험 기록 시작하기",style=MaterialTheme.typography.headlineSmall)
                    Text("기존 계정이 있다면 먼저 복원해 주세요. 계정을 선택하면 위치 권한을 확인하고 탐험을 자동으로 기록해요.")
                    if(restoring) {
                        AccountSettings(id,true,onRestored={proceed()},restoreOnly=true)
                        TextButton(onClick={restoring=false}) { Text("계정 선택으로 돌아가기") }
                    }
                    else {
                        Button(onClick={proceed()},modifier=Modifier.fillMaxWidth()) { Text("새 탐험 시작") }
                        OutlinedButton(onClick={restoring=true},modifier=Modifier.fillMaxWidth()) { Text("기존 계정 복원") }
                    }
                    if(savingError) Text("선택을 저장하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요.")
                }
            }
        }
    }
    else Text(if(error) "사용자·기록 파일을 확인해 주세요. 기존 파일은 보존했으며 다시 실행하면 이전 작업부터 재시도합니다." else "내 탐험 기록 불러오는 중…")
}
