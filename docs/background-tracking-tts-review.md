# 화면 밖 탐험 기록과 TTS

2026-11-09 · 사용자 1번 커밋 요청에 따라 구현·검증 후 로컬 커밋

## 동작

탐험 기록 시작을 누르고 Pleos 위치와 Android 위치 권한을 허용하면 location 타입 포그라운드 서비스가 단일 SDK 세션을 소유한다. Compose 화면/Activity 생명주기에서 SDK 수신을 제거했다. 다른 앱 전환, 홈 복귀, 화면이 보이지 않는 동안에도 같은 세션과 미완료 체류를 유지한다. 모의 기록 보기로 바꿔도 수신은 계속된다. 앱의 기록 중지 또는 지속 알림의 기록 중지로 종료한다. 중지/권한·위치 오류/프로세스 종료 후 미완료 체류는 이어 붙이지 않는다. START_NOT_STICKY이므로 강제 종료·재부팅 후 자동 재시작을 주장하지 않는다.

Android34 foregroundServiceType=location, FOREGROUND_SERVICE/FOREGROUND_SERVICE_LOCATION, ACCESS_FINE/COARSE_LOCATION을 선언한다. 시작은 사용자가 앱 화면에서 요청할 때만 수행하고 ACCESS_BACKGROUND_LOCATION은 요구하지 않는다. POST_NOTIFICATIONS는 Android13 이상에서 요청하며 거절해도 플랫폼이 허용하는 FGS 실행 범위에서 기록 가능하다. 지속 알림과 앱의 중지 동작을 제공한다. Pleos SDK 자체의 위치 권한·공유 설정 검사는 별개다.

SDK 정확도 해석과 저장 공간 분리는 기존과 같다. 검증한 디버그 에뮬레이터/서비스 조합에서 자동 해금하며 실차/릴리스/미검증 버전은 보류한다.

## 음성

새 기록의 저장이 성공한 DiscoveryResult만 음성 알림에 전달한다. 10초 동안의 지역·셀·명소 해금을 합쳐 반복 발화를 줄이고, 이미 획득한 항목·단순 위치 수신·과거 기록 복원은 읽지 않는다. 중지하면 대기 알림도 취소한다.

Pleos TextToSpeech 3.3.1을 ON_DEVICE 모드로 초기화하고 한국어를 설정한다. 서버 자격증명이나 서버 합성 모드는 사용하지 않는다. TTS AAR가 INTERNET 및 CAAS 버전 metadata를 병합하지만 앱은 ON_DEVICE 합성을 요청한다. 알림 용도의 AudioAttributes.USAGE_NOTIFICATION과 일시적 MAY_DUCK 오디오 포커스를 사용한다. 경로 안내·NAVIGATION/긴급 컨텍스트를 사칭하지 않는다. 포커스 거절 시 음성을 생략하고, 포커스 상실 시 중단한다. 오류/시간 초과가 나도 저장된 탐험 기록은 유지한다.

SDK가 합성 오디오를 직접 스트리밍해 PCM 패딩을 제어할 수 없어 180ms 재생 요청 지연을 적용했다. 이는 데이터 수준 선행 묵음과 동일한 보장이 아니며 실차 첫 음절/오디오 경합 검증은 남아 있다. ON_DEVICE 초기화·재생 실패는 화면의 음성 상태에 표시한다. 길 안내가 아닌 탐험 상태 알림으로 구현했으며 최종 앱 카테고리/백그라운드 알림 허용 범위는 배포 심사에서 별도 확인해야 한다.

## 변경과 검증

- 공통 모델/인터페이스/route 변경 없음. 공통 보호 파일 Manifest·Gradle 의존성, MainActivity와 유 담당 feature만 수정했다. 상대 담당 공유 대상이다.
- Maven 허용 그룹에 공식 전이 의존성 ai.pleos.playground.security를 추가했다. universal-access 0.6.0은 공식 TTS가 요구하는 의존성이며 제외하지 않았다.
- 단위 테스트 69개 통과. 음성 배치·중복 무음·중지 취소 검증 포함. Lint 오류 0개·기존 경고 5개. 앱/진단 APK 빌드 성공.
- Pleos Connect 2.1.0 / API34에서 Activity/Compose 없이 서비스 시작, SDK 입력 수신, 지역 1개·도로 셀 4개 저장, ON_DEVICE TTS의 재생 완료 콜백, 재로딩, 명시적 중지 후 수신 중단을 확인했다. dumpsys에서 isForeground=true, location 타입과 지속 알림도 확인했다.
- 단발 위치가 서비스 갱신 주기에 걸려 빠지는 경우를 배제하기 위해 각 합성 입력의 원본 시각이 실제 서비스에 도착했는지 대조한다. 실제 센서 GPS나 사람의 청취 평가를 완료했다고 주장하지 않는다. SDK 에뮬레이터 저장소의 별도 tracking-probe-* 사용자로 검증했으며 기존 사용자 모의 기록은 유지했다.
- 검증 명령: 기존 SdkUnlockProbe 실행에서 `-e mode background`를 추가한다. Android 위치/알림 권한을 먼저 허용하고 에뮬레이터의 mock-location AppOp를 일시 허용한 뒤 finally에서 원복한다.

## 공식 근거

- [Android 위치 FGS 요건](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
- [Pleos 위치 권한과 플랫폼 위치 정책 구분](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [TTS 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)
- [TTS 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions)
- [오디오 포커스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)
- [선행 묵음](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide)
