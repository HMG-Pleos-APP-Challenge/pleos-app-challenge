package com.daehyundai.map.feature.exploration.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import kotlinx.coroutines.*

/** ON_DEVICE never registers server credentials. A voice failure must not roll back an unlock. */
class PleosUnlockSpeaker(context:Context,private val scope:CoroutineScope,private val status:(String)->Unit) {
    private val sdk=TextToSpeech(context.applicationContext,Mode.ON_DEVICE)
    private val audio=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var ready=false
    private var closed=false
    private var playing=false
    private var latest:String?=null
    private var watchdog:Job?=null
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if(change<0) scope.launch { runCatching { sdk.stop() }; finish("음성 알림 중단 · 기록은 저장됨") }
        }.build()
    private val listener=object:EventListener {
        override fun onReady()=Unit
        override fun onStart() { scope.launch { status("해금 음성 재생 중") } }
        override fun onUpdatedRms(rms:Double)=Unit
        override fun onDone() { scope.launch { finish("해금 음성 재생 완료") } }
        override fun onStop() { scope.launch { finish("음성 알림 중단 · 기록은 저장됨") } }
        override fun onError(errorType:ErrorType) { scope.launch { finish("음성 오류: ${errorType.name} · 기록은 저장됨") } }
    }
    init {
        runCatching {
            sdk.initialize(object:OnInitializeListener {
                override fun onSuccess() { scope.launch {
                    if(closed) return@launch
                    sdk.addEventListener(listener); sdk.setLocale(Locale.KOREA); sdk.setAudioAttributes(attributes)
                    ready=true; status("기기 내 음성 알림 준비됨")
                    latest?.let { latest=null; speak(it) }
                } }
                override fun onFailure(errorType:ErrorType) { scope.launch { status("음성 초기화 실패: ${errorType.name} · 기록은 계속됨") } }
            })
        }.onFailure { status("음성 서비스 사용 불가 · 기록은 계속됨") }
    }
    fun speak(text:String) {
        if(closed) return
        if(!ready || playing) { latest=text; return }
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status("다른 오디오 우선 · 음성 생략, 기록은 저장됨"); return
        }
        playing=true
        // The SDK streams audio directly; a bounded pre-roll scheduling delay is the available fallback.
        scope.launch {
            delay(180)
            if(!playing || closed) return@launch
            runCatching { sdk.speak(text) }.onFailure { finish("음성 요청 실패 · 기록은 저장됨") }
        }
        watchdog=scope.launch { delay(20_000); runCatching { sdk.stop() }; finish("음성 시간 초과 · 기록은 저장됨") }
    }
    private fun finish(message:String) {
        if(closed) return
        watchdog?.cancel(); watchdog=null; playing=false
        audio.abandonAudioFocusRequest(focus); status(message)
        latest?.let { latest=null; speak(it) }
    }
    fun close() {
        closed=true; latest=null; watchdog?.cancel(); playing=false
        audio.abandonAudioFocusRequest(focus)
        runCatching { sdk.stop() }; runCatching { sdk.removeEventListener(listener) }; runCatching { sdk.release() }
    }
}
