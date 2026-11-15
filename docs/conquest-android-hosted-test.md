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

`offline` 단계는 먼저 `queue`를 완료한 뒤 시험 AVD의 Wi-Fi/모바일 데이터를 끄고 실행한다. HTTP를 모의하지 않으며, 원문 행과 마지막 캐시를 비교한다. 통신 성공 시 오히려 실패하므로 연결이 실제로 끊겼는지도 검사한다. `finally`에서 연결을 복원한 뒤 `sync`한다. 마지막 확인한 소속은 계정별로 저장하고 엄격히 재검사하며, 오프라인 소속 캐시로 새 팀을 선택하지 않는다.

`restore-blocked`는 현재 실제 암호화 세션을 그대로 둔 채 기존 `restore()` 진입점을 호출한다. 존재하지 않는 시험용 이메일/암호를 사용하고 HTTP가 호출되면 실패하게 하여, 점령 저장소 검사에서 먼저 차단되는지 확인한다. 계정·세대·미전송 원문이 그대로인지 확인한다. 복원 보호를 우회하거나 실제 계정을 교체하는 기능은 아니다.

`paused`는 네트워크가 연결돼 있지만 시험 서버의 제출이 중지된 상태에서 실행한다. 실제 HTTP 실패 후 큐 행/원문을 비교하고, 조회는 계속 성공해야 한다. `queue`→`paused`→운영자 명시적 재개→`sync` 후 다시 중지한다. 공개 API 성공 형식이나 일반 앱 활성화 설정은 바꾸지 않는다.

`prepare`만 기존 인증 담당의 `account()`로 명시적 가입을 허용한다. 나머지는 가입 없는 계정 조회다. `queue`는 서버 시각과 팀 선택 이후의 합성 근거를 만들어 영속 저장하고 전송 실패를 주입한다. `lost-reply`는 실제 HTTP 응답이 인정/반일 중복인지 확인한 다음 응답 유실만 모의한다. `sync`는 원문 재전송·ACK/결과 저장·빈 큐 재시도·기여 조회를 확인한다. 모든 단계는 전체1,453방향 스냅샷의 엄격한 파싱/디스크 재읽기, 잘못된 인증 세대의 적용 차단, 실제 복원 차단 저장소 검사를 수행한다.

`PASS` 결과의 Auth UUID는 두 계정 구분용이며 토큰이 아니다. 오류는 예외 종류만 반환한다. 비밀키, 세션 원문, HTTP 오류 본문을 로그에 추가하지 않는다. 실제 결과는 실행 후 기록하며 빌드 성공만으로 Android 검증을 완료 처리하지 않는다.

## 별도로 남는 검증

두 물리 기기/실제 Pleos 입력, 같은 계정의 두 기기, 실제 계정 복원 A→B→A와 응답 도착 직전 전환, 장시간 실제 네트워크 단절·재부팅 후 background 재개, 낮12시 제출/정산 경합, Cron/운영 배포는 이 Probe만으로 완료되지 않는다. `queue`의 최초 실패와 `lost-reply`는 장애 주입이다. 별도 `offline`은 실제 AVD 연결을 끊고 실행한다.

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

## 2026-11-14 실제 연결 단절·복원 차단 추가 검증

같은 두 시험 AVD의 데이터/계정을 유지한 채 inspect→queue→restore-blocked→실제 Wi-Fi·모바일 데이터 끄기→offline→재연결→sync→restore-blocked를 실행했다. 총12회 Instrumentation이 통과했다. 실제 네트워크 실패 후 정확한 큐 행/불변 JSON과 마지막1453방향 캐시가 보존됐다. 재연결 후 두 계정 모두 DUPLICATE_HALF_DAY를 반환했고 ACK 후 pending0/기여1로 유지됐다. 서버 응답 결과에 따른 기대 증감(ACCEPTED +1 / DUPLICATE_HALF_DAY +0)을 명시적으로 확인한다.

복원은 pending1일 때와 ACK 후 pending0일 때 모두 점령 저장소가 남아 있어 기존 최종 검사에서 차단됐다. 인증 요청0회, 원문/계정 UUID/세대 변화0이었다. 익명 계정을 지우거나 암호를 만들거나 저장소를 우회하지 않았다. 실제 계정 전환이 허용된 경로의 A→B→A 검증과는 구분한다. 테스트 빌드/Lint가 다시 성공했고, 런타임 소스가 같은 단위 테스트270개는 Gradle UP-TO-DATE 결과를 사용했다.

## 2026-11-14 서버 중지·재개 추가 검증

두 AVD에서 queue/paused/sync 총6회 통과. 중지 중 실제 제출 실패 후 pending1/기여1과 정확한 원문을 유지했고 전체1453방향 조회도 성공했다. 운영자가 시험 emulator 출처만 재개한 후 DUPLICATE_HALF_DAY/증감0으로 ACK돼 두 큐가0이 됐다. 시험 출처는 다시 중지했다. 최소 근거는 합성이며 실제 SDK 주행은 아니다. 이 검증을 위해 친구 인증 구현이나 런타임 앱을 변경하지 않았다.
