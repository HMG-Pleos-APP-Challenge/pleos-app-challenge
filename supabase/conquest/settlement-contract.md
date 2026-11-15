# 점령 서버 정산·스냅샷 격리 계약

2026-11-14. settlement-prototype.sql은 격리 시험용이며 운영 migration·Cron 활성화·공동 Notion 승인 기록이 아니다. 기존 ledger/proof/gateway 프로토타입 뒤에 적용한다. 기존 탐험과 예약 conquest_ownership 테이블의 의미는 변경하지 않는다.

## 운영 기준과 시간

운영자는 출처별 시작 시각 S를 정해 initialize_settlement로 한 번만 초기화한다. S는 UTC epoch milliseconds이고 첫 정산 T₀는 S보다 엄격히 뒤인 첫 KST 낮12시다. S가 정확히 낮12시면 다음 날 낮12시부터 정산한다. 첫 상태는 중립/revision0/lastSettlementUtcMs=null이다. 같은 시작값의 재실행은 무변경이고 다른 시작값·카탈로그로 초기화해 진행 상황을 덮어쓸 수 없다. 실제 운영 S는 아직 정하지 않았다.

각 T에서 [max(S,T−36시간),T)의 인정 기여만 집계한다. 하한은 포함하고 T 자체는 제외한다. T에 완료된 기록은 즉시 누적 기여에 보이더라도 그 T의 소유 정산에는 들어가지 않는다. S 이전의 기록은 이 운영의 기여와 초기 정산에서 제외한다.

방향별 팀 득점은 인정 기여 수다. 단독 최다 팀이 소유하고, 동점이면 기존 소유 팀이 공동1위일 때 유지한다. 기존 소유 팀이 공동1위가 아니면 중립이다. 기여가 없으면 중립이다. CAPTURED/RETAINED/TAKEN_OVER/TIE_RETAINED/TIE_NEUTRAL/NO_CONTRIBUTIONS의 기존 Kotlin 이유와 일치한다. 초기 미정산만 INITIAL로 구별한다.

## 제출과 정산 순서

정산은 기여 제출과 같은 worlds 출처 행을 FOR UPDATE한 뒤 settlement scope를 잠근다. 기본 서버 시각도 world 잠금을 얻은 뒤 읽는다. HTTP·외부 I/O는 이 트랜잭션에 없다. 새로운 과거 완료 기록의 마감 판단은 기존 제출 규칙 그대로 서버 접수 시각의 최근 KST 낮12시를 따른다. 이미 인정한 동일 이벤트 재시도는 원래 결과를 반환하고 점수를 늘리지 않는다.

private settle_due는 due 시각을 앱에서 받지 않고 저장한 첫/마지막 정산에서 다음 낮12시를 계산한다. 앱/anon/authenticated는 초기화·정산·내부 테이블을 실행/수정할 수 없다. p_test_now_ms는 기존 격리 시험 방식과 같은 신뢰된 내부 override이며 공개 API 인자가 아니다. 실제 운영 스케줄에서는 이 인자를 생략해야 한다.

## 누락 복구와 원자성

누락 정산은 시간순으로 처리한다. 동점이 이전 소유 상태에 의존하므로 마지막 정산만 건너뛰어 계산하지 않는다. 한 호출은 기본7회, 최대31회까지만 처리하고 hasMore와 다음 정산 시각을 반환한다. hasMore이면 운영 작업자가 같은 함수를 다시 호출해 이어간다. 이는 검증된 운영 성능 수치가 아닌 초기 보호 한도다.

한 호출의 run·모든 도로 점수/이유/소유·마지막 정산 포인터/revision은 같은 트랜잭션이다. 뒤쪽 정산에서 오류가 나면 앞쪽 정산을 포함해 해당 호출 전체가 롤백된다. 재호출하면 마지막 커밋된 포인터부터 다시 실행한다. 이미 완료된 정산은 재계산/덮어쓰기하지 않는다. 시계 역행·이전 도로 결과 누락·카탈로그 변경/도로 수 변경은 오류로 중지하며 빈/중립 결과로 조용히 초기화하지 않는다.

settlement_scopes는 출처별 S·첫/마지막 시각·revision·카탈로그·도로 수를, settlement_runs는 정산별 창·완료 시각·revision을, settlement_roads는 방향별 이전/현재 팀·점수·이유를 보관한다. 세 테이블은 RLS를 켜고 클라이언트 접근을 철회한다. 카탈로그 교체·이력 정리·백업에서 실제 복원하는 운영 절차는 별도 작업이다. 이 프로토타입은 같은 버전의 카탈로그를 직접 수정하는 운영을 지원하지 않는다.

## 스냅샷 초안

conquest_snapshot_v1(p_partition,p_catalog)은 authenticated 전용 읽기 함수다. 소유자는 auth.uid()이며 요청 UID·시각 인자가 없다. 고정 search_path의 SECURITY DEFINER 경계로 private 상태를 읽으며 정산을 실행하거나 테이블에 쓰지 않는다. STABLE 함수의 한 조회문 스냅샷으로 revision·소유·요약을 함께 읽는다.

응답 필드는 다음과 같다.

- protocol=1, userId, partition, catalogVersion, revision
- generatedUtcMs, startedUtcMs, lastSettlementUtcMs(null 가능), nextSettlementUtcMs
- summary: teamId(null 가능), contributionCount, ownedRoadCount, lastUpdatedUtcMs(null 가능)
- roads: 모든 방향의 roadId, ownerTeamId, previousOwnerTeamId, scores, reason

누적 기여는 현재 계정/출처/카탈로그에서 S 이후 인정된 수이며 36시간 만료로 줄지 않는다. 소유 도로는 현재 계정 팀이 마지막 정산에서 소유한 모든 방향의 수다. 다른 사용자 UUID·이동 원문·개별 기여 기록은 반환하지 않는다. 공개된 팀별 집계 점수와 지도 소유는 제공한다. raw proof 보관 테이블은 추가하지 않았다.

generatedUtcMs가 nextSettlementUtcMs를 넘었는데 revision이 그대로라면 정산 지연 상태다. 조회가 자동 복구하지 않으며 클라이언트가 '현재 정산 완료'로 오인하면 안 된다. 앱의 엄격한 응답 검사·계정별 영속 캐시·역행 revision 차단·지도/요약 적용은 다음 작업이다. 현재 앱은 이 RPC를 호출하지 않고 기존 로컬 게임을 유지한다.

## 운영 적용 전

운영자는 별도 시험 Supabase에서 실제 Auth/PostgREST·다중 연결의 제출/정산 경쟁·동시 실행·statement timeout·부하·권한을 확인해야 한다. 실제 스케줄 생성/실행 권한·UTC 시간대·누락 감시·hasMore 재호출·실패 알림·복구 담당자를 정한 뒤 적용한다. UTC 스케줄에서 KST 낮12시는 03:00이다. Cron이 DB 함수를 실행할 수 있다는 점과 실행 이력 확인 방법은 [Supabase Cron 공식 문서](https://supabase.com/docs/guides/cron)를 기준으로 한다. 이번 작업은 Cron job을 생성하지 않았다.

정식 migration은 기존 SQL와 충돌/적용 순서/복구를 리뷰한 뒤 CLI로 생성한다. 이 프로토타입 파일을 운영 SQL Editor에 자동 적용하지 않는다. 운영 S와 허용된 catalog/team 목록도 그때 확인한다.

## 실제 검증

작업 공간 tools/build-local.ps1 실행 후 저장소에서 node supabase/tests/conquest-settlement.mjs를 실행한다. Kotlin의 실제 정산 객체가 합성7개 시나리오의 모든 도로별 소유·이전 소유·점수·이유를 ignored build/conquest-proof/settlements.json에 출력하고 독립 SQL 구현과 대조한다. 기존 앱 판정/정산 코드를 시험에 맞춰 바꾸지 않았다.

전체 Android257개 단위 실패/오류0, lintDebug·assembleDebug 성공(기존 Warning5/Information1). 정산/스냅샷 SQL27개 통과. Kotlin 캡처 JSON을 공개 팀 선택→근거 제출→정산→스냅샷까지 통과시켜 서버 인정·원문 해시·재전송 무중복·신규 지연 제출 거부를 확인했다. 전체1,453방향 스냅샷의 합성 시험 크기는 UTF-8 212,246bytes였다. 이 값은 일반 운영 용량/속도 보증이 아니다.

시험은 단일 PGlite 연결과 합성 JWT subject/role·시계다. 잠금 순서와 경계의 순차 결과 및 오류 롤백은 확인했지만 실제 다중 연결 경쟁·운영 Auth/PostgREST·Cron·실차·모든 도로의 실제 주행을 확인한 결과는 아니다.
