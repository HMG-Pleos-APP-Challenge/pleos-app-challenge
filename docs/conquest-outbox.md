# 점령 최소 근거와 영속 전송 큐

2026-11-14. 기존 로컬 게임·인증·프로필·탐험은 유지한다. 이 문서는 수집/큐/동기화 구성요소의 구현 범위를 기록하며 운영 API 배포나 실사용자 업로드 활성화 지시가 아니다.

## 확정한 보관 기준

사용자는 최소 통과 근거를 제출하고 서버에는 원문을 보관하지 않는 방향, 기기에서는 최종 확정 응답까지 자동 삭제 없이 보관하는 방향을 선택했다. 초기 한도는 **1,000건·64MiB**이며 가득 차면 기존 미전송을 보존하고 새 제출을 보류한다.

한도는 server-accounts 폴더의 계정/출처별 SQLite 큐를 합산한 pending/blocked 행과 불변 JSON의 UTF-8 바이트에 적용한다. DB 파일의 인덱스·저널·빈 페이지·처리 결과 메타데이터까지 포함한 파일 크기 한도는 아니다. CONFLICT는 미확정 격리 상태이므로 원문과 용량을 계속 유지한다. 정상 최종 ACK 때만 원문을 제거한다.

## 최소 근거 수집

DirectedRoadDetector의 기본 동작에서는 근거 수집이 꺼져 있다. ConquestServerCapture는 서버 계정과 소속을 확인한 뒤 별도로 생성하는 구성요소로, 같은 판정기에 선택적으로 근거를 붙인다. SDK를 새로 구독하거나 탐험 내부를 읽지 않는다.

- 입구에서 시작한 진행 중 통과만 메모리에 보관하고 출구 진출 시 제출 문자열을 만든다. 입구 이전 이력·정확한 중복 샘플은 포함하지 않는다.
- 이탈/역행/공백/초기화/잘못된 출처·사용자 입력은 기존 판정처럼 진행 중 근거를 끊는다. 팀 선택 전 입력으로 중간 시작을 인정하지 않는다.
- 겹친 도로에서 복수 완주가 나오면 제출하지 않는다. 각 통과 최대2048점과 이벤트 JSON256KiB는 현재 gateway 시험 방어 한도를 따른다.
- 근거가 넘치거나 완전한 문자열을 만들 수 없으면 request=null을 명시적으로 반환한다. 이 결과를 서버 인정/대기열 저장 성공으로 표시하면 안 된다. 기존 로컬 완주 판정은 보존한다.

계정/세대·팀·출처·카탈로그가 바뀌거나 앱이 종료되면 진행 중 메모리 관측은 폐기하고 새 입구부터 시작해야 한다. 이전 로컬 기여를 서버 제출로 자동 승격하지 않는다. 저장 실패/한도 초과로 새 근거가 디스크에 들어가지 못한 상황까지 프로세스 종료 후 무손실이라고 안내하지 않는다.

## 저장 경계

ConquestOutbox.openCurrent는 withCurrentAccount에서 다시 계정을 확인한 뒤 files/conquest/server-accounts/<scope hash>.sqlite를 만든다. project/Auth UUID/설치ID/partition/catalog는 DB scope에 보관한다. 인증 세대·토큰·키는 복제하지 않는다. 새 scope/DB 생성과 기록·복원 최종 검사는 ConquestAccountRestore의 같은 monitor로 조정한다.

SQLite v1에는 scope/outbox/outcomes가 있다. outbox는 ID·불변 JSON·해시·바이트 수·격리 상태를, outcomes는 eventId·해시·최종 결과·서버 시각만 보관한다. 같은 이벤트/같은 내용 추가는 멱등이며 다른 내용은 기존 자료를 보존하고 실패한다.

DB 손상·소유자/출처/버전 불일치·일부만 생성된 DB는 자동 초기화하지 않는다. 기본 SQLite 손상 처리의 삭제 대신 오류를 드러내는 handler를 사용한다. 최종 복원 검사는 이 폴더의 DB/미전송/귀속 상태를 만나면 보수적으로 차단한다. 빈 DB만 있어도 소유자 귀속이 만들어졌으므로 현재는 복원 차단 대상이다.

secure_delete를 켜고 최종 ACK의 원문 삭제와 처리 결과 저장을 같은 SQLite 트랜잭션으로 수행한다. 서버 지도/소유 스냅샷 캐시는 아직 없으며 outcomes는 전송 처리 결과 캐시다. 논리적 삭제 검증을 OS 전체의 복구 불가능한 삭제 보증으로 확대하지 않는다.

## 전송과 ACK

ConquestOutboxSync는 배치를 읽은 record/SQLite 잠금을 해제하고 인증된 submit을 호출한다. 현재 배치는 최대20건/요청1MiB 시험 한도 안에서 구성한다. 실제 큐 용량과 서버 배치 한도는 별개다.

응답은 기존 ConquestSubmitResponse로 계정·출처·버전·전송ID·도로·완료시각·해시·상태/flag 전체를 확인한다. 그 뒤 withCurrentAccount 안에서 짧은 로컬 트랜잭션으로 해당 행만 ACK하고 결과를 캐시한다. 네트워크 중 새로 추가된 행과 응답에 없는 행은 보존한다.

FUTURE는 그대로 pending이다. CONFLICT는 원문을 보존한 채 blocked로 만들어 자동 전송에서 제외한다. 정상 최종 거부는 사유만 남기고 원문을 제거하며 서버 인정 기여로 표시하지 않는다. 같은 계정으로 돌아와 새 인증 세대가 확인되면 이전 큐의 원문을 그대로 재전송할 수 있지만, 이전 세대 응답으로 ACK하지 않는다.

호출자는 전체 coordinator를 IO에서 실행하고 sample/record/queue 잠금을 잡은 채 진입하지 않는다. coroutine 호출에서는 checkCancellation에 ensureActive 검사만 주입한다. HTTP·suspend·UI 콜백을 인증 적용 블록에 넣지 않는다. 실제 SDK/서비스 연결에서는 수집 결과를 record/sample 잠금 밖으로 전달한 뒤 저장해야 한다.

## 활성화 전 남은 연결

P4-3 후속에서 서버 팀 선택/기존 소속 복구 결과를 전달하는 조립부와 세션 연결을 구현했다. 아래 '조립부 미활성'은 계속 유효하며, 구현한 흐름은 별도 rollout gate=false로 꺼져 있다. 실제 활성화 전 검증 경계는 [conquest-membership.md](conquest-membership.md)를 따른다.

기존 게임은 로컬로 실행한다. ConquestSession은 이제 서버 계정·팀 응답이 검증된 경우에만 prepareServerTracking으로 별도 coordinator를 준비할 수 있다. 해당 준비 함수를 호출하는 앱 조립부의 팀 선택/복구 흐름은 아직 활성화하지 않았다. 단순 계정 조회나 로컬 팀 선택으로 근거 수집·서버 업로드를 시작하지 않는다.

준비된 세션의 onSample은 기존 로컬 기록 처리 후, 기록 잠금 밖에서 같은 입력을 수집기에 전달한다. 외부 서비스의 sampleAccess는 여전히 잡혀 있을 수 있으므로 여기서는 메모리 작업만 한다. 완료 근거는 최대16개 작업의 Channel로 IO worker에 넘긴다. worker가 원래 계정·세대를 다시 확인하고 영속 큐에 넣으며 인증/HTTP를 위치 호출자에게서 기다리지 않는다. 입력 초기화는 두 판정기를 함께 초기화한다. MOCK은 서버 coordinator를 준비할 수 없다.

메모리 handoff는 영속 저장이 아니다. 가득 참·불완전 근거·디스크 실패·계정 불일치는 이번 실행의 '저장하지 못한 근거'로 표시하며 서버 대기 건수에 포함하지 않는다. 프로세스 종료 시 이 짧은 handoff 구간까지 무손실이라고 보장하지 않는다. stop은 새 관측을 멈추고 이미 전달한 작업은 원래 인증 경계로 끝까지 처리한 뒤 DB를 닫는다. 계정·세대 불일치나 세션 부재는 수집을 중지하고 기존 디스크 큐를 유지한다. 계정 재확인도 이전 세대 coordinator를 중지한다. 새 coordinator 준비는 이전 worker 종료 후 수행한다.

재시도는 IO worker에서 처리한다. 업로드를 허용한 격리 시험에서는 신규 저장 후/15초 간격/명시적 요청으로 시도하며 재시도 요청은 중복 적재하지 않는다. 앱의 prepareServerTracking은 uploadsEnabled=false로 고정한다. 실제 앱에서는 HTTP를 보내지 않고 기기 보관 상태만 갱신한다. 지도 화면은 서버 비활성/기기 보관/미전송/충돌/미저장 상태를 표시하고 기존 로컬 요약을 서버 인정 결과로 바꾸지 않는다.

남은 활성화 작업은 검증된 서버 환경에서 조립부의 팀 선택·기존 소속 복구 결과를 준비 함수에 전달하고, 배포 확인 후 업로드 정책을 켜는 것이다. 운영체제에 의한 프로세스 재시작·앱 종료 후 자동 전송은 이 process-local worker가 제공하지 않는다. 별도의 영속 background 작업 도입은 아직 하지 않았다.

운영 API 배포·실제 Auth/PostgREST·두 계정/두 기기·SDK 파일시스템·장기 성능·서버 정산/지도 스냅샷은 이번 구성요소 검증과 구분한다. 기존 계정 복원의 sampleAccess 네트워크 대기는 이 변경에서 해결하지 않았다.

## 검증 방법

```powershell
./tools/build-local.ps1
cd pleos-app-challenge
node supabase/tests/conquest-captured.mjs
```

ConquestOutboxTest는 재시작·용량·부분ACK·동시enqueue·응답 유실·계정/세대/세션 변경·취소·손상·저장 장애 롤백·실제 캡처한 제출의 재시도를 검증한다. factory 시험은 Windows의 sqlite4java가 사용할 수 있는 독립된 짧은 ContextWrapper filesDir를 쓴다. 실제 Android 기기 경로에서 실행한 결과는 아니다.

ConquestServerCaptureTest는 공통18개 입력을 수집한 뒤 최소 근거로 재생하고, 실제 Kotlin 제출문자열/해시7개를 app/build/conquest-proof/captured.json에 출력한다. conquest-captured.mjs가 이 문자열을 재직렬화하지 않고 격리 인증 SQL gateway에 넣어 ACCEPTED 및 SHA-256 일치를 확인한다. 이 산출물은 무시되는 build 폴더의 합성 시험 자료이며 사용자 이동 이력이 아니다.

2026-11-14 실제 검증: 전체 단위 테스트232개 실패/오류0, lintDebug·assembleDebug 성공(Warning5/Information1), 격리 SQL의 Kotlin 캡처 패킷7개 모두 통과. 신규 시험은 큐18개와 수집4개다. 실제 Auth/PostgREST 및 기기 업로드 검증은 포함하지 않는다.

후속 P4-2의 ConquestServerTrackingTest는 계정/팀 검증 전 저장소 미생성, 실제 캡처 저장/재시작, 한도·근거 누락·계정 전환·부분 처리 상태, 인증 지연 중 비차단 입력/메모리 한도/종료 시 전달 작업 보관, 실패 후 명시적 재시도를 검증한다. 실제 전체 카탈로그를 읽은 ConquestSession.onSample과 MOCK 차단도 포함한다. 이 JVM 시험의 AssetManager에는 두 기존 도로 asset만 넣은 임시 APK형 ZIP을 제공하며 앱 assets 자체를 바꾸지 않는다. 실제 SDK 위치 구독·실차·UI 조작 시험은 포함하지 않는다.

P4-2 최종 실행: 신규 연결 시험11개 포함 전체243개 실패/오류0, lintDebug·assembleDebug 성공(기존 Warning5/Information1). 첫 시험의 독립 주행 사이 순간 이동은 입력 초기화를 추가해 바로잡았고, 느슨한 asset 디렉터리를 열 수 없던 JVM 시험은 위 APK형 ZIP으로 수정했다. 판정기·실제 assets를 시험에 맞춰 바꾸지 않았다.
