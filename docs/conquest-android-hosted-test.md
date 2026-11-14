# Android 점령 hosted 시험

`ConquestHostedProbe`는 별도 시험 프로젝트의 Debug APK에서만 실행한다. 운영/다른 URL 및 Release는 거부한다. 일반 앱의 rollout/업로드 설정은 변경하지 않는다. 기존 인증 구현과 조립 어댑터를 재사용하며 토큰을 점령 코드나 결과 로그에 전달하지 않는다.

## 준비와 보존

- 기존 AVD/사용자 데이터를 복사하거나 지우지 말고 새 시험 AVD 두 개를 생성한다.
- Git 밖 `local.properties`를 정확한 원본 바이트로 백업한다. 시험 URL/공개 키로 `assembleDebug assembleDebugAndroidTest`를 빌드한 후 원본을 `finally`에서 복원한다. APK도 별도 개인 시험 폴더에 보관한다.
- 시험 APK는 새 AVD에만 설치한다. 기존 기기의 APK/설정/기록을 교체하지 않는다.
- 시험 데이터는 `pleos-emulator`에만 보낸다. `live-road-proof.json`은 `docs/data/conquest-proof-fixtures.json`의 `runtimeCatalog` 픽스처를 복사한 것으로, 주 앱의 도로 카탈로그/판정기와 대조한다. 실제 GPS·SDK 주행이 아니다.
- AndroidTest Manifest에만 Instrumentation을 등록한다. Gradle, 주 앱 Manifest, 공통 모델과 친구 기능 내부는 수정하지 않는다.

## 단계별 실행

실제 serial을 확인하고 명시한다. 아래 예시는 새 AVD가 `emulator-5556`일 때다. runner package는 `com.daehyundai.map.test`다.

```powershell
adb -s emulator-5556 shell am instrument -w -e phase prepare -e team team-red com.daehyundai.map.test/com.daehyundai.map.ConquestHostedProbe
adb -s emulator-5556 shell am instrument -w -e phase queue com.daehyundai.map.test/com.daehyundai.map.ConquestHostedProbe
adb -s emulator-5556 shell am instrument -w -e phase lost-reply com.daehyundai.map.test/com.daehyundai.map.ConquestHostedProbe
adb -s emulator-5556 shell am instrument -w -e phase sync com.daehyundai.map.test/com.daehyundai.map.ConquestHostedProbe
adb -s emulator-5556 shell am instrument -w -e phase inspect com.daehyundai.map.test/com.daehyundai.map.ConquestHostedProbe
```

두 번째 기기는 `prepare -e team team-blue`로 시작한다. 두 기기의 `queue` 완료 후 `lost-reply`를 동시에 실행하면 서로 다른 Auth 계정의 실제 같은 방향 제출을 확인할 수 있다. 프로세스별 실행이 끝난 뒤 다음 Instrumentation에서 같은 Keystore 세션/SQLite를 재사용한다. 이전 pending이 있으면 새 `queue`는 실패하여 기존 원문을 보존한다.

`prepare`만 기존 인증 담당의 `account()`로 명시적 가입을 허용한다. 나머지는 가입 없는 계정 조회다. `queue`는 서버 시각과 팀 선택 이후의 합성 근거를 만들어 영속 저장하고 전송 실패를 주입한다. `lost-reply`는 실제 HTTP 응답이 인정/반일 중복인지 확인한 다음 응답 유실만 모의한다. `sync`는 원문 재전송·ACK/결과 저장·빈 큐 재시도·기여 조회를 확인한다. 모든 단계는 전체1,453방향 스냅샷의 엄격한 파싱/디스크 재읽기, 잘못된 인증 세대의 적용 차단, 실제 복원 차단 저장소 검사를 수행한다.

`PASS` 결과의 Auth UUID는 두 계정 구분용이며 토큰이 아니다. 오류는 예외 종류만 반환한다. 비밀키, 세션 원문, HTTP 오류 본문을 로그에 추가하지 않는다. 실제 결과는 실행 후 기록하며 빌드 성공만으로 Android 검증을 완료 처리하지 않는다.

## 별도로 남는 검증

두 물리 기기/실제 Pleos 입력, 같은 계정의 두 기기, 실제 계정 복원 A→B→A와 응답 도착 직전 전환, 장시간 실제 네트워크 단절·재부팅 후 background 재개, 낮12시 제출/정산 경합, Cron/운영 배포는 이 Probe만으로 완료되지 않는다. `offline`와 `lost-reply`는 장애 주입이며 기기 전체 네트워크를 끊었다는 뜻이 아니다.

## 2026-11-14 실제 실행 결과

새 API37.0 AVD 두 대의 서로 다른 익명 Auth 계정에서 다섯 단계가 모두 통과했다(총10회 Instrumentation). 각 단계는 새 앱 프로세스에서 실행했고 인증 담당의 URL별 Keystore 세션과 같은 점령 SQLite를 재사용했다.

| 단계 | 빨강 기기 | 파랑 기기 |
| --- | --- | --- |
| prepare | 미전송0 / 기여0 | 미전송0 / 기여0 |
| queue·실패 주입 | 미전송1 / 기여0 | 미전송1 / 기여0 |
| 실제 제출·응답 유실 주입 | 미전송1 / 기여1 | 미전송1 / 기여1 |
| 새 프로세스 재전송·ACK | 미전송0 / 기여1 | 미전송0 / 기여1 |
| 재조회 | 미전송0 / 기여1 | 미전송0 / 기여1 |

두 `lost-reply` 프로세스를 동시에 시작해 같은 방향의 실제 HTTP 제출을 수행했다. 요청이 서버에서 같은 순간 실행됐다는 보장은 아니며, 정산과의 경합 검증도 아니다. 이후 별도 서버 읽기 조회에서 두 Auth UUID 각각 인정1건/방향1개를 확인했다. 모든 단계에서 전체1453방향 파싱·원자적 파일 캐시 재읽기·복원 차단·잘못된 세대 적용 콜백 미실행을 확인했다. 잘못된 세대 인자는 실제 A→B→A 복원 시험의 대체가 아니다. 첫 정산 전 revision0이다.

전체 단위270개 실패/오류0, lintDebug(기존 Warning5/Information1), assembleDebug/assembleDebugAndroidTest가 성공했다. 시험 APK는 Git 밖에 별도 보관했고 `local.properties`는 백업과 바이트 일치로 복원을 확인했다. 일반 개발용 APK도 원래 설정으로 다시 빌드해 성공했다.

환경 준비 중 구형 Command-line Tools가 새 AVD target을 `android-0`으로 기록했고 SurfaceFlinger가 반복 종료됐다. 설치 이미지의 source.properties와 기존 정상 AVD에서 API37.0을 확인해 새 AVD 두 개의 target만 교정한 후 그래픽 서비스 PID가 유지되고 설치/시험이 통과했다. GPU 모드만 바꾼 첫 시도는 해결하지 못했다. 새 AVD는 SwiftShader/headless로 실행했다. 또한 가상 Wi-Fi가 연결되지 않아 최초 실제 인증은 UnknownHostException으로 실패했다. `cmd wifi help`를 확인하고 두 새 AVD만 AndroidWifi에 연결한 뒤 성공했다. 기존 AVD와 시스템 SDK/앱 데이터는 수정하지 않았다.
