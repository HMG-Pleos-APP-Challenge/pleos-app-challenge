package com.daehyundai.map.feature.profile

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import com.daehyundai.map.feature.profile.data.InstallationIdentity
import com.daehyundai.map.feature.exploration.data.migrateLegacyExploration
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.*

@Composable fun IdentityGate(content:@Composable (String)->Unit) {
    val context=LocalContext.current.applicationContext
    var user by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try {
            user=withContext(Dispatchers.IO) {
                val id=InstallationIdentity(File(context.filesDir,"identity/installation-user.txt")).getOrCreate()
                val boundary=context.assets.open("exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
                val roads=context.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
                val landmarks=context.assets.open("exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
                migrateLegacyExploration(File(context.filesDir,"exploration"),boundary,roads,landmarks,id)
                id
            }
        } catch(cancelled:CancellationException) { throw cancelled
        } catch(_:Exception) { error=true }
    }
    val id=user
    if(id!=null) content(id)
    else Text(if(error) "사용자·기록 파일을 확인해 주세요. 기존 파일은 보존했으며 다시 실행하면 이전 작업부터 재시도합니다." else "내 탐험 기록 불러오는 중…")
}
