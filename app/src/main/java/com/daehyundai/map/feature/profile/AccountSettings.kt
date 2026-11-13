package com.daehyundai.map.feature.profile

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.daehyundai.map.feature.exploration.cloud.SupabaseCloud
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import com.daehyundai.map.feature.exploration.tracking.ExplorationTracking

fun canRestoreAccount(hasLocalAchievements:Boolean)=!hasLocalAchievements

@Composable fun AccountSettings(userId:String,canRestore:Boolean,restoreOnly:Boolean=false,onRestored:suspend ()->Unit) {
    val context=LocalContext.current.applicationContext
    val cloud=remember { SupabaseCloud(context) };val scope=rememberCoroutineScope()
    var email by remember { mutableStateOf("") };var password by remember { mutableStateOf("") }
    if(!SupabaseCloud.configured) {
        Text("계정 서버 연결이 설정되지 않았어요. 이 기기의 기록으로 탐험을 사용할 수 있어요.")
        return
    }
    var message by remember { mutableStateOf("게스트 기록을 보관하려면 이메일을 연결해 주세요.") }
    var busy by remember { mutableStateOf(false) }
    fun act(success:String,action:()->Unit) {
        if(busy) return
        busy=true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() };message=success;password="" }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="처리하지 못했어요. 이메일 인증·입력 정보·네트워크를 확인해 주세요." }
            finally { busy=false }
        }
    }
    HorizontalDivider();Text("계정 연결·복원",style=MaterialTheme.typography.titleLarge)
    Text(if(restoreOnly && !busy && message.startsWith("게스트")) "기존 계정의 이메일과 비밀번호를 입력해 주세요." else message)
    OutlinedTextField(email,{email=it},label={Text("이메일")},enabled=!busy,singleLine=true)
    if(!restoreOnly) OutlinedButton(enabled=!busy && email.contains('@'),onClick={act("받은 메일의 인증 링크를 누른 뒤 비밀번호를 설정해 주세요.") { cloud.linkEmail(email) }}) { Text("현재 게스트 계정에 이메일 연결") }
    OutlinedTextField(password,{password=it},label={Text("비밀번호 · 8자 이상")},enabled=!busy,singleLine=true,visualTransformation=PasswordVisualTransformation())
    if(!restoreOnly) OutlinedButton(enabled=!busy && password.length>=8,onClick={act("계정 연결 완료 · 새 기기에서도 이메일과 비밀번호로 복원할 수 있어요.") { cloud.setPassword(password) }}) { Text("이메일 인증 후 비밀번호 설정") }
    if(!restoreOnly) Text("기존 계정 복원은 탐험 기록이 없는 기기에서만 가능해요. 현재 기록이 있다면 위에서 이메일을 연결해 주세요.")
    if(!canRestore) Text("현재 기기에 탐험 기록이 있어 복원할 수 없어요. 현재 계정에 이메일을 연결해 보관해 주세요.")
    OutlinedButton(enabled=!busy && canRestore && email.contains('@') && password.length>=8,onClick={
        busy=true;scope.launch {
            try {
                ExplorationTracking.syncAccess.withLock {
                    ExplorationTracking.sampleAccess.withLock {
                        withContext(Dispatchers.IO) { cloud.restore(email,password,userId) }
                    }
                }
                password="";onRestored();message="로그인 완료 · 탐험 화면에서 기록 동기화 상태를 확인해 주세요."
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="복원 실패 · 현재 계정은 유지됩니다. 로그인 정보와 미전송 기록을 확인해 주세요." }
            finally { busy=false }
        }
    }) { Text("새 기기에서 기존 계정 복원") }
}
