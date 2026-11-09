# Pleos 위치 SDK와 자동 해금

후속 변경: [화면 밖 기록과 TTS](background-tracking-tts-review.md). 아래의 화면 생명주기 설명은 최초 SDK 연결 시점의 기록이며 현재 수신은 포그라운드 서비스가 소유한다.

2026-11-09 · feature/exploration-profile · 사용자 자동 해금 적용·로컬 커밋 승인

## 동작

`ai.pleos.playground:FusedLocation:1.1.3`의 실시간 위치 업데이트를 기존 LocationSource/LocationSample 계약에 연결한다. 앱에서 SDK 위치 시작을 선택하고 `pleos.car.permission.FUSED_LOCATION` 권한을 허용하면 앱이 STARTED 이상인 동안 수신한다. 중지·백그라운드 전환 시 동일 리스너를 해제하고 SDK를 release한다. 재시작 시 새 SDK와 탐험 세션을 생성해 미완료 체류를 이어 붙이지 않는다. 위치 공유 차단/null/오류/처리 지연은 수신을 종료하며 중지 후 시작으로 재시도한다.

원본 timestampMillis를 사용하며 수신 시각으로 체류 시간을 만들지 않는다. 15초보다 오래된 위치, 5초보다 미래 위치, 유효하지 않은 좌표는 거부한다. 같은 좌표·시각은 같은 eventId를 유지한다. 지역·도로·랜드마크의 기존 판정기와 영구 저장 방식을 유지한다.

## confidence 해석

[조사 및 5/25/75/5m 대조 결과](pleos-confidence-findings.md)에 따라 검증된 환경에만 미터 해석을 적용한다. 다음 조건을 모두 만족해야 자동 해금한다.

- 디버그 앱
- Android emulator 하드웨어(ranchu 또는 goldfish), 실차 아님
- SDK AAR가 APK에 병합한 metadata의 버전 1.1.3
- 설치된 FusedLocation Service 버전 1.0.0, versionCode 6

위 조합은 `VERIFIED_EMULATOR_METERS` 정책으로 confidence를 accuracyMeters에 그대로 전달한다. 75m 등 불량 정확도도 그대로 전달하여 판정기가 후보·체류를 끊도록 한다. 0/음수/NaN/무한대는 정확도 미상(null)이다. 이는 SDK에 hasAccuracy가 없음을 고려한 앱 정책이며, 0이 공식 불능값이라는 의미는 아니다. 25m는 앱의 판정 기준이지 SDK 명세의 강제 조건이 아니다.

다른 기기, 릴리스 빌드, SDK/서비스 버전 불일치, 버전 확인 실패는 `UNVERIFIED`로 정확도를 null로 전달하고 자동 해금을 보류한다. SDK 업데이트 시 오래된 해석이 그대로 활성화되지 않도록 실제 metadata를 읽는다. 차량 모델명으로 가용성을 단정하지 않으며 `isSupported()`와 권한도 별도로 검사한다.

지역 발견은 정확도·경계 여유·3초 연속 관측, 도로 셀은 추가로 셀 경계·도로 근접·이동 조건, 명소 도감은 100m 반경·120초 연속 관측·15초 단절 제한을 적용한다. 명소 체류는 차량 주차를 증명하지 않는다.

## 기록과 화면

| 입력 | 파일 접두사 | 의미 |
|---|---|---|
| 기존 모의 재생 | mock- | 고정 시나리오 재생 |
| 검증된 SDK 에뮬레이터 | pleos-emulator- | SDK를 통과한 합성/에뮬레이터 위치, 실차 방문 아님 |
| 검증되지 않은 SDK | pleos- | 정확도 미상으로 신규 해금 안 함 |

각 접두사에 discoveries.bin / cells.bin / landmarks.bin을 붙인다. source 헤더도 기존 MOCK/PLEOS로 구분한다. 파일은 신규 획득 때만 생성한다. 기존 mock 파일은 이동하거나 변환하지 않는다. 원시 위치/이동 경로를 별도 저장하거나 서버로 보내지 않는다. 홈의 기존 통합 화면은 모의 기록이고, 개인 탐험·프로필은 상단에서 선택한 기록의 요약을 사용한다. 현재 사용자 ID는 개발용 u-demo-01이며 계정 시스템 검증은 아니다.

## 보호 파일과 공동 통합 영향

- settings.gradle.kts: 공식 Maven을 추가하고 ai.pleos.playground 그룹으로 조회를 제한한다.
- app/build.gradle.kts: FusedLocation 1.1.3, 현재 AndroidX 세대의 lifecycle-runtime-ktx 2.6.2 추가.
- Manifest: 위치 전용 dangerous 권한 추가. SDK AAR의 서비스 바인딩 권한·package visibility queries·버전 metadata가 병합된다. 차량 제어 권한과 Android background/fine/coarse 위치 권한은 추가하지 않는다.
- MainActivity: 권한 요청과 SDK 시작/중지/모의 복귀를 연결한다. core 모델·인터페이스·navigation route·namespace는 변경하지 않는다.
- feature/exploration/location: SDK 어댑터·환경별 정확도 해석·생명주기 수신을 담당한다. Vehicle/NaviHelper/TTS/STT는 호출하는 기능이 없어 추가하지 않는다.

상대 개발자에게 보호 파일 변경과 SDK 입력 연결 위치를 공유한 뒤 공동 통합해야 한다. 노션 계약 v0.1은 여전히 공동 승인 대기다. 사용자 요청 범위는 로컬 커밋이며 push/PR/원격 병합은 하지 않는다.

## 검증

단위 테스트는 SDK 콜백→정확도 매핑→세 판정→파일 저장→재로딩, 75m 입력의 후보/체류 중단, 버전·기기·빌드 제한, 취소/오류 정리를 확인한다. 과거 단계의 회귀 테스트도 포함한다. 최종 빌드·Lint·테스트 집계는 아래 기록한다.

`ConfidenceProbe`는 정확도 대조 도구다. `SdkUnlockProbe`는 별도 임시 cache 저장소에서 **실제 SDK 콜백**을 통해 불량 정확도 거부·지역 1개·도로 셀 4개·실제 경과 2분의 명소 체류·저장 복원을 확인한다. 두 도구는 androidTest APK에만 포함되며 에뮬레이터 확인과 명시적인 syntheticEmulator=true 인자를 요구한다. 앱의 실제 filesDir 기록을 수정하지 않는다. 테스트 GPS 제거·SDK 해제·임시 저장소 삭제와 AppOp 원복을 수행한다.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 앱에서 Pleos 위치 권한을 먼저 허용. 현재 사용자 번호는 am get-current-user로 확인.
# 아래는 원래 mock-location AppOp가 default였고 사용자 번호가 10인 검증 환경.
adb -s emulator-5554 shell appops set --user 10 com.daehyundai.map android:mock_location allow
try {
    adb -s emulator-5554 shell am instrument --user 10 -w -e syntheticEmulator true com.daehyundai.map.test/com.daehyundai.map.SdkUnlockProbe
} finally {
    adb -s emulator-5554 shell appops set --user 10 com.daehyundai.map android:mock_location default
}
```

이 검증은 합성 좌표를 SDK에 통과시킨 개발 검증이며 실제 GPS 센서·실차 방문·주차·백그라운드 주행을 검증한 것은 아니다. 실차 적용 전 confidence 단위/신뢰 수준/불능값/버전 일관성을 Pleos에 확인해야 한다.

## 공식 근거

- [SDK 설정](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release)
- [위치 콜백](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)

## 최종 실행 결과

- testDebugUnitTest: 총 67개, 실패/오류 0개.
- lintDebug: 오류 0개, 경고 5개(버전 알림 3개, 기존 백업 규칙·아이콘 2개).
- assembleDebug / assembleDebugAndroidTest 성공.
- SDK 통합: 수신·처리한 SDK 위치 38개, 정확도 75m에서 지역 해금 없음, 정상 입력으로 지역 1개·도로 셀 4/1579개, 실제 SDK 시각으로 120,009ms 연속 관측 후 랜드마크 1개 획득, 별도 저장소 재로딩 복원 성공. 합성 위치를 사용했으며 실차 검증이 아니다.
- 최종 APK metadata를 읽은 정책 선택과 5→25→75→5m 대조도 기기에서 통과했다. 두 진단 실행기 등록 충돌을 수정하여 SdkUnlockProbe 하나로 실행하고, mode=confidence일 때 정확도 대조만 수행한다.
- 기존 mock 지역/셀/명소 파일의 SHA256은 3차 검증 때와 동일했다. GPS test provider 제거, 임시 cache 정리, mock-location AppOp default 복귀를 확인했다.
- 마지막 화면 캡처는 도구의 창 영역 오류로 확인하지 못했다. 초기 SDK 연결 단계의 화면 확인과 위의 실제 SDK·정책·저장 검증을 구분한다.