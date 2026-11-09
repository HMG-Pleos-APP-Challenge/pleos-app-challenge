package com.daehyundai.map.feature.exploration.location

/** Empirical SDK 1.1.3 / service 1.0.0 (code 6) contract; not a promise for other devices. */
enum class PleosAccuracyPolicy(val recordPrefix: String, val description: String) {
    UNVERIFIED("pleos", "정확도 해석 미검증 · 자동 해금 보류"),
    VERIFIED_EMULATOR_METERS("pleos-emulator", "검증한 에뮬레이터 · 정확도(m) 기반 자동 해금"),
    ;

    fun accuracyMeters(confidence: Float): Float? =
        if (this == VERIFIED_EMULATOR_METERS && confidence.isFinite() && confidence > 0f) confidence else null
}

fun selectPleosAccuracyPolicy(
    debugBuild: Boolean, emulator: Boolean, sdkVersion: String,
    serviceVersion: String?, serviceVersionCode: Long?,
): PleosAccuracyPolicy =
    if (debugBuild && emulator && sdkVersion == "1.1.3" && serviceVersion == "1.0.0" && serviceVersionCode == 6L)
        PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS else PleosAccuracyPolicy.UNVERIFIED
