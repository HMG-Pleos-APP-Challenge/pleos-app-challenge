# 점령 원장 격리 프로토타입

2026-11-14 후속: 사용자의 새 시험 환경 지시로 분리된 hosted Supabase에도 검토된 SQL을 적용하고 실제 Auth/PostgREST10개를 확인했다. [test-environment.md](test-environment.md)를 따른다. 아래 'in-memory에서만 시험'은 초기 단계 기록이며 현재도 운영 프로젝트/앱 rollout/Cron 활성화는 아니다.

2026-11-14 후속: [settlement-contract.md](settlement-contract.md)에 정산/스냅샷의 최신 격리 명세와 검증 범위를 기록했다. 아래 초기 단계의 '정산 미구현' 기록은 이 후속으로 보완한다. 앱 실제 연결·운영 DB·Cron은 여전히 비활성이다. 작업 공간 tools/build-local.ps1 다음, 저장소 루트에서 node supabase/tests/conquest-settlement.mjs로 검증한다.

이 폴더의 SQL은 **격리 메모리 DB에서만 시험하는 프로토타입**이다. 운영 SQL Editor에 붙여 넣거나 migration으로 자동 적용하지 않는다. 후속 gateway-prototype.sql에는 인증된 팀선택/제출 RPC 후보를 추가했지만 운영 배포·앱 실제 호출은 활성화하지 않았다. 명세는 [gateway-contract.md](gateway-contract.md)를 따른다.

## 구현된 범위

- 최초 팀 소속과 선택 시각 보존, 같은 팀 재요청, 다른 팀 변경 차단, 데이터 기반 세 팀 이상.
- 계정/출처/eventId별 불변 canonical 내용과 원래 처리 결과 보관. 다른 내용의 같은 ID는 충돌.
- 계정/출처/카탈로그/방향/KST 날짜·반일당 인정 기여 유일성.
- 마감 이후 새 이벤트 거부와 기존 인정 결과 재전송의 분리, 미래 입력은 최종 receipt 없이 재시도 가능.
- partition별 world 행 잠금, 실제 DB 시계는 해당 잠금 획득 후 읽기.
- receipt와 인정 기여의 같은 트랜잭션 기록, 예외 시 함께 롤백.
- 비노출 schema, RLS, 클라이언트 테이블/함수 권한 차단.

원장 테스트는 실제 앱 assets의1,453방향 ID를 읽어 catalog에 넣고 기존6방향 ID 포함도 확인한다. 후속 proof 시험은 카탈로그 geometry도 읽어 서버에서 경로를 재생한다.

## 신뢰 경계와 미구현

`accept_verified`의 사용자 ID·검증 근거 해시·판정기 버전은 **신뢰된 서버 검증기/시험 대역** 입력이다. 해시 문자열의 형식 검사로 실제 주행이 검증되는 것은 아니다. `proof-prototype.sql`의 격리 판정기와 인증 gateway 후보는 추가했지만 앱 이벤트를 이 내부 원장에 직접 전달하면 안 된다.

내부 함수들은 SECURITY INVOKER이며 PUBLIC/anon/authenticated의 실행권한과 schema/table 권한을 회수했다. 후속 gateway가 auth.uid·서버시각을 고정하고 항목별 응답을 반환한다. 내부 시험 함수의 `p_test_admitted_ms`는 경계 테스트 전용이고 앱/API에 노출하면 안 된다. 생략하면 잠금 획득 후 clock_timestamp를 사용한다.

활성 catalog 교체·정산·소유 상태/revision·누적 캐시·서버 큐·앱의 근거 수집은 미구현이다. receipt의 최종 거부 코드 등은 시험 계약이며 공통 wire API 승인으로 간주하지 않는다. 기기 근거 보관·용량·운영 환경/담당은 미결을 유지한다.

## 서버 근거 재판정 프로토타입

2026-11-13 사용자는 해당 통과의 최소 좌표·시각·정확도를 제출하고 판정 후 서버 원문을 보관하지 않는 방향으로 진행하도록 선택했다. `proof-prototype.sql`은 위치 배열을 함수 안에서만 재생하고 원장에는 근거 SHA-256·판정기 버전과 결과만 남긴다. 영구 위치 cursor/후보를 만들지 않는다. 별도 road_geometry는 공개 도로 카탈로그이며 사용자 통과 좌표가 아니다. 운영 전 HTTP/DB 로그의 본문 보관도 별도로 확인해야 한다.

도로별 투영·30m 끝점 연장·입구/출구·10m 정확도·15초 공백·60m 이동·40m/s·U턴/이탈·겹친 완주 차단을 기존 DirectedRoadDetector와 같은 입력으로 비교한다. 최소 근거는 입구 관측부터 실제 출구 진출까지이며 하나의 완료를 마지막 샘플에서 증명해야 한다. source/user 정보는 미래 인증/입력 경계가 책임지며 클라이언트 좌표만으로 물리적 주행의 진위를 보증하지 않는다.

같은 `docs/data/conquest-proof-fixtures.json`을 PostgreSQL replay와 Kotlin ServerProofParityTest가 읽는다. 18개 사례에는 양방향/중간 출발/U턴/공백/정확도/중복/역순/겹친 도로/교차로 진출/굽은 도로와 실제 전역1,453방향 카탈로그의 한 경로가 있다. 실제 카탈로그 유래 좌표의 출처·라이선스는 기존 docs/data/CONQUEST-DATA-NOTICE.md를 따른다.

내부 submit_proof는 방향/eventId/완료시각을 판정 결과와 대조한 뒤 원장으로 넘긴다. 잘못된 근거의 최종 거부도 receipt로 남겨 동일 ID·다른 근거를 충돌 처리한다. 형식 오류/범위 초과는 SQL 요청 실패다. 배열 상한2048은 격리 시험용 방어 한도이며 승인된 기기 큐/최종 API 용량 정책이 아니다. 후속 gateway 후보는 원문 JSON 문자열을 불변으로 전송하고 UTF-8 해시를 대조하므로 PostgreSQL JSON 수치 표현을 앱에서 재현하지 않는다. 실제 인증 환경·기기 업로드·배포는 후속이다.

## 실행

기존 친구의 고정된 PGlite/PostGIS 테스트 의존성만 사용한다. Node22+와 npm 환경에서:

```sh
cd supabase
npm ci --ignore-scripts --no-audit --no-fund
node tests/conquest-ledger.mjs
node tests/conquest-proof.mjs
node tests/conquest-gateway.mjs
node tests/exploration.mjs
```

각 시험은 새 in-memory PostgreSQL/PostGIS를 만들고 종료 시 닫는다. URL/키/원격 DB 연결을 읽지 않는다. 점령 시험은 기존 sql-editor schema/catalog도 함께 적용한다. 단일 PGlite 연결의 순차 시험이며 실제 다중 DB 연결 경쟁·Supabase Auth/PostgREST·두 기기를 검증한 것은 아니다.

2026-11-13 실행 결과: 점령17개 검사 전부 통과, 기존 탐험 서버 테스트 종료코드0/PASS. 오류 주입 시 receipt/기여 동시 롤백, 양쪽 클라이언트 역할 접근 차단 포함. Android 소스 변경이 없어 이번에는 Gradle을 다시 실행하지 않았다.

후속 근거 판정 최종 검증(2026-11-13): 원장17개/근거24개(공통 입력18개 포함) 및 기존 탐험 서버 시험 PASS. Kotlin의 공통 입력18개 대조를 포함한 전체202개 단위 테스트 실패/오류0, lintDebug/assembleDebug 성공(Warning5/Information1). 생성 fixture 재생성 해시 일치. 사용자 좌표를 수집하거나 운영 DB/실제 Auth/HTTP·기기 업로드를 실행하지 않았다. 모든 입력의 동등성·다중 연결 경쟁·최종 API 성능까지 검증한 결과는 아니다.

## 운영용 전환 전에 할 일

1. 기존 detector와 일치하는 서버 완주 판정·근거 수집/보관 계약 확정.
2. 인증 gateway와 역할별 권한, server-only 시계·소유자·판정 입력 고정.
3. 부분 결과/ACK·계정 복원·다중 연결 경합·정산과의 경쟁 검증.
4. 기존 스키마와 충돌 없는 추가 변경안 리뷰 후 CLI로 정식 migration 생성.
5. 격리 Auth 환경·두 계정/두 기기 검증 후 운영 담당자가 적용.

기존 `conquest_road_catalog`/`conquest_ownership` 예약 구조·탐험 schema/functions·SQL Editor 배포 파일은 이 작업에서 수정하지 않았다.
