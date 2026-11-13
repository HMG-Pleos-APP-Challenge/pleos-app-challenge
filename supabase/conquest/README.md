# 점령 원장 격리 프로토타입

`ledger-prototype.sql`은 **격리 메모리 DB에서만 시험하는 내부 구현**이다. 운영 SQL Editor에 붙여 넣거나 migration으로 자동 적용하지 않는다. 앱에서 호출할 수 있는 RPC는 추가하지 않았다.

## 구현된 범위

- 최초 팀 소속과 선택 시각 보존, 같은 팀 재요청, 다른 팀 변경 차단, 데이터 기반 세 팀 이상.
- 계정/출처/eventId별 불변 canonical 내용과 원래 처리 결과 보관. 다른 내용의 같은 ID는 충돌.
- 계정/출처/카탈로그/방향/KST 날짜·반일당 인정 기여 유일성.
- 마감 이후 새 이벤트 거부와 기존 인정 결과 재전송의 분리, 미래 입력은 최종 receipt 없이 재시도 가능.
- partition별 world 행 잠금, 실제 DB 시계는 해당 잠금 획득 후 읽기.
- receipt와 인정 기여의 같은 트랜잭션 기록, 예외 시 함께 롤백.
- 비노출 schema, RLS, 클라이언트 테이블/함수 권한 차단.

테스트는 실제 앱 assets의1,453방향 ID를 읽어 catalog에 넣는다. geometry와 좌표 검증은 아직 추가하지 않았다. 기존6방향 ID 포함도 확인한다.

## 신뢰 경계와 미구현

`accept_verified`의 사용자 ID·검증 근거 해시·판정기 버전은 **신뢰된 서버 검증기/시험 대역** 입력이다. 해시 문자열의 형식 검사로 실제 주행이 검증되는 것은 아니다. 서버 완주 검증기와 인증 gateway를 만들기 전 앱 이벤트를 이 함수에 전달하면 안 된다.

함수들은 SECURITY INVOKER이며 PUBLIC/anon/authenticated의 실행권한과 schema/table 권한을 회수했다. 외부 호출의 auth.uid 확인, 팀 선택 RPC의 서버 선택 시각, 배치 응답/부분 ACK 형식은 후속 gateway에서 구현한다. 시험 함수의 `p_test_admitted_ms`는 경계 테스트 전용이고 앱/API에 노출하면 안 된다. 생략하면 잠금 획득 후 clock_timestamp를 사용한다.

활성 catalog 교체·정산·소유 상태/revision·누적 캐시·서버 큐·원문 근거 수집/삭제는 미구현이다. 초기 고정 catalog의 내부 원장만 검증한다. receipt의 최종 거부 코드 등은 시험 계약이며 공통 wire API 승인으로 간주하지 않는다. 보관 기간·실제 근거 규격·시험 환경·운영 담당은 기존 미결을 유지한다.

## 실행

기존 친구의 고정된 PGlite/PostGIS 테스트 의존성만 사용한다. Node22+와 npm 환경에서:

```sh
cd supabase
npm ci --ignore-scripts --no-audit --no-fund
node tests/conquest-ledger.mjs
node tests/exploration.mjs
```

두 파일은 각각 새 in-memory PostgreSQL/PostGIS를 만들고 종료 시 닫는다. URL/키/원격 DB 연결을 읽지 않는다. 점령 시험은 기존 sql-editor schema/catalog도 함께 적용해 함수 본문·245개 탐험 target 보존을 확인한다. 단일 PGlite 연결의 순차 시험이며 실제 다중 DB 연결 경쟁·Supabase Auth/PostgREST·두 기기를 검증한 것은 아니다.

2026-11-13 실행 결과: 점령17개 검사 전부 통과, 기존 탐험 서버 테스트 종료코드0/PASS. 오류 주입 시 receipt/기여 동시 롤백, 양쪽 클라이언트 역할 접근 차단 포함. Android 소스 변경이 없어 이번에는 Gradle을 다시 실행하지 않았다.

## 운영용 전환 전에 할 일

1. 기존 detector와 일치하는 서버 완주 판정·근거 수집/보관 계약 확정.
2. 인증 gateway와 역할별 권한, server-only 시계·소유자·판정 입력 고정.
3. 부분 결과/ACK·계정 복원·다중 연결 경합·정산과의 경쟁 검증.
4. 기존 스키마와 충돌 없는 추가 변경안 리뷰 후 CLI로 정식 migration 생성.
5. 격리 Auth 환경·두 계정/두 기기 검증 후 운영 담당자가 적용.

기존 `conquest_road_catalog`/`conquest_ownership` 예약 구조·탐험 schema/functions·SQL Editor 배포 파일은 이 작업에서 수정하지 않았다.
