# FusedLocation confidence 조사와 연결 제안

2026-11-09 · 아래는 최초 조사 시점의 기록

후속 반영: 사용자가 자동 해금 적용과 로컬 커밋을 승인했다. 현재 구현은 [SDK 연동 검토 문서](pleos-location-sdk-review.md)를 따른다. 검증된 디버그 에뮬레이터/SDK/서비스 조합에서 미터 정확도 매핑을 적용하고, 실제 방문 기록과 분리한다. 아래의 “미반영/미커밋” 표현은 최초 조사 시점에 한정한다.

## 결론과 적용 범위

**현재 검증 환경에서 confidence는 Android Location.accuracy의 미터 값을 그대로 반환한다.** 0~1 확률이나 백분율로 해석할 근거가 없다. 아래 대조 결과에 따라 이 환경에서는 `confidence -> accuracyMeters` 직접 매핑이 가능하다. 이는 서비스의 관측된 동작이며 모든 Pleos 실차/서비스 버전에 대한 공식 사양 확정은 아니다.

| 구성 | 검증 값 |
|---|---|
| 가상 기기 | Pleos Connect 2.1.0 / Android API34 |
| SDK | ai.pleos.playground:FusedLocation:1.1.3 |
| FusedLocation Service | ai.pleos.playground.service.location.fused 1.0.0 / versionCode 6 |
| 입력 | Android GPS test provider; 합성 좌표, 실제 GPS 아님 |
| 대조 | 주입 좌표와 timestampMillis가 모두 일치하는 SDK 콜백만 인정 |

| 주입한 Android 수평 정확도(m) | 수신 confidence | 좌표·시각 일치 |
|---:|---:|---|
| 5 | 5.0 | 확인 |
| 25 | 25.0 | 확인 |
| 75 | 75.0 | 확인 |
| 5 (복귀 대조) | 5.0 | 확인 |

첫 실험의 연속 단발 입력에서는 두 번째 콜백이 누락됐다. 서비스의 갱신 간격 영향을 분리하기 위해 각 단계에서 새 시각의 위치를 2초 간격으로 재주입했다. 그 결과 네 단계 모두 일치했다. 이 실험으로 실차 위치 정확도, 실제 서비스의 자연 갱신 주기, 주차·방문 진위를 검증한 것은 아니다.

## 공식 자료 조사

1. [공식 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)는 confidence를 신뢰도로 소개한다. [필드 Reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/confidence)는 Float 선언뿐이며 단위·범위가 없다.
2. [공식 배포 SDK sources JAR](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/FusedLocation/1.1.3/FusedLocation-1.1.3-sources.jar)의 LocationData/Mapper, fused-location-aidl 0.0.5 소스는 confidence를 그대로 전달하며 단위를 설명하지 않는다. 저장소 metadata에서 최신 배포는 1.1.3으로 확인했다.
3. [공식 샘플 화면](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app/blob/c955c3a811371892e8803f3b1d9ad3f0426e832c/app/src/main/java/ai/pleos/playground/location/fused/sample/ui/screen/MainScreen.kt#L306)은 CONFIDENCE를 단위 없이 출력한다. 미리보기의 0.9f는 실제 SDK 값이나 확률 범위의 증거가 아니다.
4. [공식 샘플 입력 코드](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app/blob/c955c3a811371892e8803f3b1d9ad3f0426e832c/app/src/main/java/ai/pleos/playground/location/fused/sample/location/FusedLocationClient.kt)는 Android GPS test provider와 accuracy=5f를 사용한다. 이 방식으로 정확도를 바꾸어 대조했다.
5. [Android Location.getAccuracy](https://developer.android.com/reference/android/location/Location#getAccuracy())는 68% 신뢰 수준의 추정 수평 오차 반경(m)이다. 값이 작을수록 정밀하다. 이것을 최대 오차 보장이나 100% 포함 반경으로 설명해서는 안 된다. 이 통계적 정의를 Pleos 전체에 보장하는 답변은 아직 받지 않았다.

설치된 위치 서비스 APK의 읽기 권한이 없어 서비스 내부 변환 코드는 확인하지 못했다. 권한 우회나 root 전환은 하지 않았으며, 결론은 공개 SDK 소스와 재현한 대조 결과에 한정한다.

## 권장 연결 방법

기존 판정 규칙을 버리거나 25m 조건을 제거할 이유는 현재 없다. 앱 내부에 **명시적인 정확도 해석 설정**을 두고, 검증된 개발 환경에서만 `HORIZONTAL_RADIUS_METERS`를 선택하는 방식을 권장한다. 검증되지 않은 환경/서비스 버전은 `UNVERIFIED`로 둔다. 기기 모델명만으로 의미를 단정하지 않는다.

- 검증된 해석: 유한하고 양수인 confidence를 accuracyMeters로 그대로 전달한다. 0/음수/NaN/무한대는 정확도 미상(null)으로 전달한다. 0 처리는 SDK에 hasAccuracy가 없다는 점을 고려한 앱 정책 제안이며 공식 불능값 사양이 아니다.
- 25m 초과 값도 숨기거나 25로 보정하지 않는다. 75를 그대로 전달해야 기존 판정기가 거부하고 진행 중 체류/해금 후보를 끊는다. 콜백 자체를 버리면 불량 구간을 건너뛰어 시간이 합산될 수 있다.
- 출처 PLEOS, 원본 timestampMillis, 안정 eventId는 유지한다. confidence에 100을 곱하거나 확률→미터 공식을 만들지 않는다. 다른 공급자의 accuracy를 Pleos 좌표에 붙이지 않는다.
- 공통 모델의 accuracyMeters 필드와 인터페이스는 변경할 필요가 없다. 해석과 검증은 feature/exploration/location 어댑터에 둔다.

| 구현 기능 | 매핑 이후 기존 규칙 |
|---|---|
| 지역 발견 | 정확도 25m 이하 + 경계에서 오차 반경만큼 안쪽 + 연속 관측 3초 |
| 도로 셀 | 위 조건 + 정확도 원이 셀 안에 들어옴 + 도로 거리/이동/연속 관측 조건 |
| 랜드마크 | 정확도 25m 이하 + 중심 거리와 오차 반경 합이 100m 이하 + 120초 연속 관측, 15초 초과 단절 시 재시작 |

이 해석 설정을 적용한 뒤 SDK→판정→별도 기록→재시작 복원까지 개발 환경에서 통합 검증하면, 현재의 '정확도 미상으로 전부 해금 보류' 상태를 해당 개발 환경에 한해 해제할 수 있다. 이 조사에서는 앱의 매핑/해금 로직을 아직 변경하지 않았다.

실차 적용 전에는 Pleos 기술지원으로 confidence가 수평 오차 반경(m)인지, 신뢰 수준이 Android와 같은 68%인지, 0/불능값 의미와 서비스 버전별 일관성을 확인한다. 필요 시 실차의 문서화된 위치 공급자와 교차 검증한다. SDK가 지원된다는 isSupported 결과만으로 confidence 단위까지 보장된다고 해석하지 않는다.

단순 ENTER/EXIT Proximity Alert로 바꾸는 방법은 기존 도로 셀과 복잡한 행정 경계의 대체가 아니다. 또한 이벤트 모델에 원본 위치 시각/체류 완료 정보가 없어 ENTER 후 타이머만으로 연속 2분 체류를 입증할 수 없다. 현재의 연속 좌표 판정기를 유지하는 편이 변경 범위가 작다.

## 실험 코드와 재현

`app/src/androidTest/java/com/daehyundai/map/ConfidenceProbe.kt`는 SdkUnlockProbe의 mode=confidence로 실행하는 진단 구현이다. 명시적 `syntheticEmulator=true` 인자와 에뮬레이터 하드웨어 확인을 요구하는 수동 진단 도구다. 일반 앱 APK에는 포함되지 않는다. 앱 탐험 저장소를 호출하지 않으며 좌표/시각 일치만 검사한 뒤 숫자 대조 결과를 출력한다. 테스트 GPS는 finally에서 제거하고 SDK 리스너와 인스턴스를 해제한다. 실제 차량에 실행하지 않는다.

앱을 먼저 설치하고 앱 UI에서 Pleos 위치 권한을 허용한 뒤, 아래처럼 선택한 에뮬레이터에서만 실행한다. user ID는 `am get-current-user`로 확인한다. 이번 검증은 10이었다. 기존 mock-location AppOp가 default인 상태에서 실행했고 종료 후 default로 원복했다. 다른 원래 설정이면 해당 값을 별도로 보존해야 한다.

```powershell
.\gradlew.bat assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell appops set --user 10 com.daehyundai.map android:mock_location allow
try {
    adb -s emulator-5554 shell am instrument --user 10 -w -e syntheticEmulator true -e mode confidence com.daehyundai.map.test/com.daehyundai.map.SdkUnlockProbe
} finally {
    adb -s emulator-5554 shell appops set --user 10 com.daehyundai.map android:mock_location default
}
```

## 변경 범위와 검증

추가한 것은 androidTest 전용 진단 코드/manifest와 이 조사 문서다. production 의존성·권한·공통 모델·해금 로직은 이번 조사에서 변경하지 않았다. 이전 SDK 연동의 미커밋 변경은 보존했다. 커밋/push/PR/병합 없음. 최종 testDebugUnitTest / lintDebug / assembleDebug / assembleDebugAndroidTest 모두 성공했다. 기존 단위 테스트 61개 실패 0건, Lint 오류 0개·기존 경고 5개다. 실험 종료 후 GPS test provider 제거 및 mock-location AppOp default 복귀를 확인했고, 기존 지역/셀/명소 파일의 SHA256은 변경 전과 동일했다.
