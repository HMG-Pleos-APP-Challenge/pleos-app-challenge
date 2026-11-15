# 점령 스냅샷의 앱 검증·보관·표시

2026-11-14. 서버 초안은 supabase/conquest/settlement-contract.md를 따른다. 앱 연결 구성요소를 구현했으나 rollout과 실제 업로드는 계속 비활성이다. 운영 DB·Cron·실제 Auth/PostgREST를 활성화한 기록이 아니다.

## 검사와 보관

ConquestSnapshot은 최대1MiB UTF-8 응답에서 protocol·Auth UUID·출처·카탈로그와 모든 등록 방향의 정확한 집합을 확인한다. 누락/중복/알 수 없는 도로·팀·비정수/음수/범위 밖 숫자·잘못된 정산 시각/주기·점수/소유/이유 불일치·요약 소유 수 불일치를 거부한다. 초기 revision0은 미정산 null·중립·INITIAL을 사용한다. 첫 정산은 운영 시작보다 엄격히 뒤인 KST 낮12시이고 이후 revision과 날짜가 맞아야 한다.

계정별 캐시는 files/conquest/server-accounts/<scope hash>.snapshot이다. 기존 project/Auth UUID/설치ID/출처/카탈로그 귀속을 사용하며 인증 세대·토큰·키를 복제하지 않는다. 형식 버전·귀속 메타데이터·길이·SHA-256·원문 응답을 저장하고 읽을 때 다시 검사한다. 해시는 파일 손상 확인용이며 기기의 악의적 변조를 막는 서명/암호화 보증이 아니다.

ConquestSnapshotSync는 잠금 없이 HTTP를 호출하고 응답 전체를 검사한 뒤 withCurrentAccount 안에서 기록 잠금→기존 캐시 비교→임시 파일 flush/fsync→atomic replace 순으로 적용한다. 원래 계정·세대가 바뀌었거나 취소되면 저장하지 않는다. 캐시 읽기도 현재 계정 검사 안에서 수행한다. 손상·알 수 없는 형식·다른 귀속은 자동 삭제/초기화하지 않는다. 기존 ConquestAccountRestore의 최종 복원 검사 대상 폴더에 있으므로 캐시만 남아 있어도 보수적으로 복원을 차단한다.

기존 캐시보다 낮은 revision·확인 시각·누적 기여·갱신 시각, 운영 시작 변경·확정 팀 변경을 거부한다. 같은 revision의 지도/점수/이유는 불변이다. 연속된 revision이면 이전 소유도 직전 캐시의 소유와 같아야 한다. 여러 revision을 건너뛴 최신 스냅샷은 허용하되 첫 정산 이후 시각/주기 검사는 유지한다. 캐시 거부/저장 실패는 기존 파일을 보존한다.

## 실행 흐름

인증된 소속이 확인되고 수집기 준비가 끝난 세션에서만 스냅샷 동기화를 준비한다. 기기에 남은 자기 계정 캐시를 먼저 읽고, 별도 IO worker가 시작/재시도/기존15초 확인 루프에서 서버를 조회한다. HTTP는 sample/record 잠금 및 withCurrentAccount의 로컬 적용 블록 밖이며 기존 rpcForAccount의 인증 요청 잠금은 그대로 사용한다. 새 근거의 영속 handoff와 같은 bounded worker 수명을 사용하므로 전송 지연 중 handoff 용량 한계는 기존 정책대로 표시한다.

네트워크 실패·잘못된 응답·낮은 revision에는 마지막 유효 확인본을 표시하고 '저장된 이전 확인본/최신 확인 실패'를 붙인다. 서버 확인 시각도 KST로 표시한다. generatedUtcMs가 nextSettlementUtcMs 이상이면 마지막 서버 응답 자체가 정산 지연 상태임을 표시한다. 기기 시계만으로 서버가 현재 정산을 완료했다고 가정하지 않는다.

중지와 스냅샷 게시를 같은 짧은 메모리 잠금으로 조정한다. 화면 값은 그 수집기 인스턴스와 묶어서 게시하며 현재 수집기가 아니거나 수집이 중지된 값은 사용하지 않는다. 네트워크/파일 작업은 이 표시 잠금 안에서 하지 않는다. 전환 중 늦은 응답은 새 세션의 화면을 덮어쓰지 않는다. 응답 검사와 캐시 저장에는 별도로 기존 인증 경계도 적용한다.

submit 응답의 큐 ACK+outcomes는 기존 SQLite 트랜잭션으로 처리한다. 전체 지도 스냅샷은 별도 읽기 RPC의 결과이므로 ACK와 같은 트랜잭션인 척하지 않는다. ACK 뒤 스냅샷 조회가 실패하면 마지막 확인본을 유지하며 기여를 기기에서 임의로 더하지 않는다.

## 화면과 담당 경계

서버 스냅샷이 유효하면 홈·지도의 소유/누적 기여/내 팀 소유 도로와 정산 집계 점수/이유를 그 확인본으로 표시한다. 로컬 파일·로컬 팀·로컬 기여는 수정하지 않는다. 연결을 끄거나 계정/세대가 바뀌면 서버 표시를 해제한다. MOCK·시연은 기존처럼 로컬이며 서버와 섞지 않는다.

이 서버 응답에는 방향별 개인 누적 기여나 오전/오후 개인 인정 여부가 없다. 서버 지도에서 그 값을 0으로 만들거나 기존 로컬 값으로 채우지 않는다. 별도 제공하지 않는 정보라고 표시한다. 서버 집계 창의 시작은 max(운영 시작,정산−36h)로 표시한다.

기존 프로필은 '기기 내 기록'이라는 문구가 있으므로 MainActivity의 프로필 전달값은 localState.summary를 유지한다. 프로필 내부를 수정하거나 서버 요약으로 몰래 교체하지 않는다. 프로필까지 서버 기준으로 전환하려면 담당자와 문구/데이터 출처 변경을 별도로 공유한다. 공통 ConquestSummary 필드·Route·인증/탐험 내부·Gradle·Manifest는 바꾸지 않는다.

## 검증과 재현

일반 Gradle 시험은 저장소 docs/data/conquest-server-snapshots.json의 합성 fixture를 읽으므로 ignored build 파일이 미리 있어야만 통과하는 구조가 아니다. fixture는 SQL의 초기/전체1,453방향/공개 제출 후 결과다. 상세 출처와 갱신 방법은 docs/data/CONQUEST-SNAPSHOT-FIXTURES.md를 따른다.

작업 공간 tools/build-local.ps1 다음 저장소 node supabase/tests/conquest-settlement.mjs를 실행한다. SQL 시험은 ignored build/conquest-proof/server-snapshots.json을 생성하고 저장된 fixture와 모든 값을 대조한다. 앱 시험은 실제 SQL payload와 손상·계정 전환·시각/revision 역행·저장 장애·취소·재시작·통신 실패 후 캐시·늦은 응답 중지·실제 세션 지도/홈용 값 연결을 확인한다. 이 결과는 실제 두 기기·운영 Auth/API·실차·시각적 UI 조작 검증과 구분한다.

최종 실제 실행: 전체270개 단위 실패/오류0(신규 스냅샷11개·세션2개), lintDebug·assembleDebug 성공(기존 Warning5/Information1). SQL27개와 고정 fixture 대조 통과. 세션 시험의 최초 SDK recordPrefix 누락을 수정했고, 중지 후 게시와 이전 수집기의 늦은 표시도 보완한 최종 코드로 전체를 재실행했다.
