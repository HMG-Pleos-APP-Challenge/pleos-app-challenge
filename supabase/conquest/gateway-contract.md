# 점령 인증 API 격리 계약 초안

최신 제출 중지 경계는 [intake-contract.md](intake-contract.md)를 따른다. 공개 함수의 성공 계약은 유지하고, 시험 서버에서 기본 중지/HTTP503 전체 배치 보류를 추가했다. 기존 검증 gateway는 private 구현으로 유지한다. 이 추가는 공동 계약 승인이나 운영 적용을 뜻하지 않는다.

2026-11-14 정산/지도 조회 후속은 [settlement-contract.md](settlement-contract.md)를 따른다. conquest_snapshot_v1은 authenticated 전용 읽기 후보이며 정산 함수는 앱에 노출하지 않는다. 실제 배포/앱 호출/Cron 활성화는 하지 않았다.

2026-11-13. `gateway-prototype.sql`을 새 in-memory DB에 적용해 시험하는 명세다. 운영 migration/배포 지시가 아니며 앱의 실제 업로드도 활성화하지 않는다. 기존 탐험 API/DB/공통 모델은 유지한다.

## 팀 선택

2026-11-14 추가: 가입·팀 생성 없이 소속만 읽는 `conquest_membership_v1(p_partition,p_catalog)`를 분리했다. MEMBER는 실제 팀/최초 선택시각을, NO_TEAM은 명시적 null 필드를 반환한다. 동일한 auth.uid()/고정 search_path/권한 경계를 사용하며 내부 테이블 쓰기는 없다. 앱의 연결/활성화 조건과 상태 계약은 [conquest-membership.md](../../docs/conquest-membership.md)를 따른다. 이것은 격리 프로토타입 추가이며 운영 배포 기록이 아니다.

`conquest_select_team_v1(p_partition, p_catalog, p_team)`

소유자는 `auth.uid()`이고 선택 시각은 scope 잠금을 얻은 뒤 서버 시계로 결정한다. body에 소유 UUID/설치ID/접수 시각 인자가 없다. 응답은 protocol=1, userId, partition, catalogVersion, status, teamId, selectedUtcMs다. SELECTED/UNCHANGED는 요청한 팀과 같고 TEAM_LOCKED는 기존 확정 팀을 반환한다. 기존 로컬 기록의 소속을 이 응답으로 자동 덮어쓰지는 않는다.

## 기여 제출

`conquest_submit_v1(p_partition, p_catalog, p_events)`

`p_events`는 **이벤트 JSON 문자열의 배열**이다. 각 문자열의 필드는 eventId, roadId, occurredMs, samples 네 개뿐이다. samples는 id/time/lat/lon/accuracy 입력이다. 앱은 ConquestSubmission을 만들 때 문자열을 한 번 확정하고 디스크·재전송에서도 바이트를 유지해야 한다. JSON 파싱 후 재직렬화·공백/숫자 표현 변경도 하지 않는다.

서버는 원본 문자열의 UTF-8 SHA-256을 requestHash로 돌려준다. 이는 PostgreSQL jsonb의 수치/정렬 표현을 앱이 재현할 필요 없이, 실제 보낸 항목과 ACK를 대조하기 위한 해시다. 서버 내부의 근거 canonical hash와 구별한다. 같은 계정/출처/eventId에 확정된 requestHash가 있으면 다른 문자열은 CONFLICT다. 큐 내용의 자동 수정/새ID 발급으로 회복하지 않는다.

응답 envelope는 protocol=1, userId, partition, catalogVersion, receivedUtcMs, results다. 각 결과는 eventId, roadId, occurredMs, requestHash, status, acknowledge, retryable를 포함한다.

| 결과 | acknowledge | retryable | 후속 큐 처리 |
| --- | --- | --- | --- |
| ACCEPTED / DUPLICATE_HALF_DAY | true | false | 검증 후 해당 전송 항목만 완료 |
| LATE / NO_TEAM / BEFORE_TEAM_SELECTION / UNKNOWN_ROAD / WRONG_CATALOG / INVALID_PROOF | true | false | 사유를 보존하고 완료. 인정 기여로 표시하지 않음 |
| FUTURE | false | true | 원문 시각/ID/내용을 그대로 유지하며 제한된 재시도 |
| CONFLICT | false | false | 보존·격리 후 확인. 자동 반복/삭제 없음 |

이미 인정/거부한 동일 요청은 마감 뒤에도 원래 결과다. 알려진 구버전 catalog의 기존 receipt도 조회 가능하며 새 구버전 이벤트는 원장 규칙으로 거부한다. 카탈로그 전체 업그레이드·ID 재분할 정책을 구현한 것은 아니다.

배치의 JSON 문법/필드/중복ID/상한 오류는 전체 SQL 요청 실패다. 항목별 게임 규칙 거부는 results에 담을 수 있다. 후속 항목 처리 중 DB 오류가 발생하면 앞선 기여/receipt/requestHash도 함께 롤백한다. 무인증/권한 오류와 요청 결과 불명확은 ACK하지 않는다.

## 인증·권한

두 gateway만 authenticated에 EXECUTE를 주고 PUBLIC/anon은 차단한다. SECURITY DEFINER는 내부 원장을 쓰기 위한 좁은 진입점이며 고정 search_path와 명시적 auth.uid 검사를 둔다. 내부 schema/table/함수는 직접 접근을 허용하지 않는다. 운영 전 owner 권한·Advisors·노출 schema·요청 로그 보관을 별도로 검토해야 한다.

접수 시각은 동일 scope 잠금 안에서 배치당 한 번 읽는다. private admission_now_ms는 앱 호출/시각 인자가 없다. 시험 코드만 private clock 함수를 교체해 경계를 재현하며 실제 서버 시각을 클라이언트가 고르는 API는 없다.

## 앱 연결 경계

ConquestServerAccess의 selectTeam/submit 두 동작만 조립부가 rpcForAccount에 매핑한다. 점령에 인증 토큰·SupabaseCloud·임의 RPC 이름을 넘기지 않는다. 프로젝트/예상 UUID/전환 세대가 바뀌면 RPC를 실행하지 않는다. 기존 조회 및 withCurrentAccount도 같은 경계를 유지한다.

ConquestTeamResponse와 ConquestSubmitResponse는 전체 envelope/형식/소유자/출처/버전을 검증한다. 제출 결과는 전송 ID/도로/완료시각/requestHash/결과코드/ACK 플래그도 모두 검사한다. 중복·없는ID·알수없는코드·모순된flag가 있으면 응답 전체를 적용하지 않는다. 빠진 항목은 pending으로 남긴다.

이 parser가 디스크 ACK를 수행하는 것은 아니다. 실제 큐가 구현되면 배치를 읽은 잠금을 해제한 뒤 RPC를 호출하고, 파싱 후 withCurrentAccount 안의 짧은 로컬 트랜잭션으로 해당 행 ACK/캐시를 원자적으로 처리해야 한다. HTTP/suspend/UI 콜백·바깥 잠금 재획득을 그 안에 넣지 않는다.

배치20/이벤트256KiB/요청1MiB/근거2048점은 격리 구현의 방어 한도이며 기기 큐/최종 서비스 용량 계약은 아니다. 실제 근거 수집·기기 저장/보관·배치 선택·캐시 revision·서버 정산·배포는 후속이다.

## 실제 검증

`node supabase/tests/conquest-gateway.mjs`: 14개 검사 PASS. JWT subject/role은 격리 fixture이고 실제 GoTrue/PostgREST/서명 검증 시험은 아니다. 원장17/근거24/기존 탐험 서버 회귀도 PASS. Android 전체210개 단위(새 protocol/adapter8개 포함)/Lint/APK 성공, Warning5/Information1. 기기 업로드·운영 적용·다중 연결 경합은 미실행이다.
