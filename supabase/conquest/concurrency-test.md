# Hosted PostgreSQL 제출·정산 경합 시험

`hosted-concurrency.mjs`는 알려진 별도 시험 프로젝트만 허용한다. 현재 ledger/settlement 원문을 해시로 기록하고 새 `conquest_race_<12hex>` 비공개 스키마에 복사한다. 설치된 `conquest_internal`·공개 RPC·탐험 SQL은 바꾸지 않는다. 기존 시험 Auth UUID 두 개는 외래 키 참조에만 사용하며 계정/인증 설정을 변경하지 않는다.

## 실행과 격리

CLI 인증은 CLI의 기존 자격증명 저장소를 사용한다. 관리 키·DB 암호·Auth 토큰을 스크립트 인자/앱/보고서에 넣지 않는다. 출력 SQL/보고서는 Git 밖 개인 폴더에 둔다. `--project-ref`와 `--run`을 명시하고 시험 환경 marker도 실제 SQL에서 재확인한다. 운영 ref, 임의 schema 이름, 잘못된/같은 UUID는 거부한다.

```powershell
node supabase/tests/conquest-concurrency-bundle.mjs
node supabase/conquest/hosted-concurrency.mjs --run --project-ref <ISOLATED_REF> --cli <CLI_EXE> --workdir <PRIVATE_TEST_ENV> --out <PRIVATE_REPORT_DIR> --users <AUTH_UUID_A>,<AUTH_UUID_B>
```

SQL 생성 자체는 네트워크를 사용하지 않는다. PGlite 준비 시험은 생성된 SQL의 컴파일·RLS/접근 철회·원래 schema/API 보존·기본 정산 의미를 확인한다. 단일 연결 PGlite 결과를 다중 연결 검증으로 표시하지 않는다.

실제 runner는 CLI를 별도 프로세스로 실행한다. 첫 트랜잭션은 world 잠금을 확보하고 결과를 기록한 채 잠시 유지한다. 관찰 연결이 `pg_stat_activity`의 `PgSleep`을 확인한 뒤 두 번째 트랜잭션을 시작하고, 두 번째의 실제 `Lock` 대기를 관찰한다. 잠금 대기를 확인하지 못하면 실패한다. 단순히 두 요청을 동시에 시작한 것만으로 통과하지 않는다. statement/query/관찰 시간 제한을 사용한다.

시험 schema의 모든 테이블은 RLS가 활성화되고 anon/authenticated의 schema·테이블·함수 접근은 철회한다. 복사된 snapshot 함수도 공개 schema로 만들지 않는다. 설치된 공개 함수의 본문 해시, 원래 점령 기여 수와 scope revision을 시작/종료에 비교한다. 실패하거나 완료한 schema/보고서를 자동 삭제하지 않는다. 다시 실행하면 새 고유 schema를 사용하며 기존 자료를 reset/drop하지 않는다.

## 시험 내용과 시간의 의미

1. 제출이 잠금을 먼저 획득하고 commit하면 기다리던 정산이 그 기여를 포함한다.
2. 정산이 먼저 잠금을 획득하면 기다리던 신규 마감 후 제출은 LATE다.
3. 마감 전 인정됐던 같은 이벤트는 마감 후 재전송에도 원래 결과를 반환하며, 새 지연 이벤트는 거부한다.
4. 같은 계정의 서로 다른 동시 이벤트가 직렬화되어 반일 기여가 한 번만 인정된다.
5. 같은 정산을 요청한 두 worker가 직렬화되어 정산 이력이 한 번만 추가된다.
6. 시험 schema가 닫혀 있고 원래 공개 API/점령 상태는 그대로다.

PostgreSQL 다중 연결과 잠금은 실제 hosted 환경이다. 주행 검증을 통과한 입력을 받는 **private `accept_verified`와 정산 함수의 시험 시간 인자**를 사용하며, 2026-11-10 KST12시를 제어된 경계로 삼는다. 실제 현재 시각을 변경하거나 앱에 마감/정산 API를 노출하지 않는다. 실제 정오를 기다린 시험·JWT/근거 판정 전체 경로의 경합·Cron 실행·운영 배포로 간주하지 않는다. Auth/PostgREST/Android 최소 근거 검증은 별도 hosted/Android 시험 결과와 구분한다.

## 2026-11-14 실행 결과

PGlite 준비 시험이 통과한 뒤 같은 생성기를 별도 hosted PostgreSQL17.11에 실행했다. 위6개 검사가 모두 통과했다. 네 개의 경합에서 실제 첫 연결의 잠금 유지와 두 번째 연결의 Lock 대기를 관찰했다. 제출 우선이면 red1로 정산됐고, 정산 우선이면 신규 지연 제출 LATE/기여0이었다. 반일 경쟁은 ACCEPTED와 DUPLICATE_HALF_DAY 각1회, 정산 worker 경쟁은 processed1과0으로 끝났다. 이전 인정 이벤트의 마감 후 재전송은 ACCEPTED를 유지했다.

설치된 공개 함수 본문 해시·원래 점령 기여 수/scope revision은 시작과 종료에 같았다. 새 schema의 RLS와 anon/authenticated 사용 권한 철회를 실제 조회했다. source SQL 해시·고유 schema·검사 결과·제어 시각은 Git 밖 JSON/SQL에 보관했다. 첫 시도는 CLI의 대상 안내가 stderr에 나오는 것을 stdout에서만 찾던 파서 때문에 초기 읽기에서 실패했다. 대상 안내를 두 스트림에서 확인하도록 수정한 뒤 실제 실행이 통과했다. 첫 실패에서 SQL 설치/경합이 통과했다고 보고하지 않는다.

추가 security advisors 조회(기본 최소 WARN)는 ERROR0/WARN9였다. 기존 authenticated SECURITY DEFINER5개·익명 사용자 정책3개·leaked-password protection 비활성1개는 그대로 남았다. INFO 전체를 재집계하거나 경고를 모두 해소한 결과가 아니다. 시험용으로 닫힌 schema를 공개하거나 친구의 탐험 정책을 수정하지 않았다.
