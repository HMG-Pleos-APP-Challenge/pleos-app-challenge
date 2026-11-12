package com.daehyundai.map.feature.exploration.tracking

fun recordingStatus(running:Boolean,lastSampleTimestamp:Long?,message:String,now:Long):String = when {
    !running -> message
    message.startsWith("탐험 저장 오류") -> message
    lastSampleTimestamp==null || lastSampleTimestamp > now+5_000 || now-lastSampleTimestamp > 30_000 ->
        "위치 신호를 기다리고 있어요. 차량 위치 공유와 위치 설정을 확인해 주세요."
    else -> "탐험 기록 중 · 화면 밖에서도 기록해요."
}
