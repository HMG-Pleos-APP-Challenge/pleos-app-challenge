# 점령 서버 v1 migration과 복구 리허설

상태: 배포 검토용 정식 migration 파일을 생성하고 검증한다. **운영 적용·공동 계약 승인·앱 활성화 완료 문서가 아니다.** 기존 탐험/예약 테이블과 main을 유지한다.

## migration 파일과 생성 원칙

CLI `migration new conquest_server_v1`이 생성한 파일은 `supabase/migrations/20261114124543_conquest_server_v1.sql`이다. `build-release.mjs`가 검증된 점령 소스의 normalized-LF SHA256을 기록하고 단일 트랜잭션의 additive DDL로 만든다. 기존 migration 파일/기록을 고치거나 이름을 임의 생성하지 않는다.

```powershell
node supabase/conquest/build-release.mjs --out supabase/migrations/20261114124543_conquest_server_v1.sql --check
node supabase/tests/conquest-release.mjs
```

- Auth/기존 PostGIS와 예약 테이블이 있어야 한다. 기존 conquest_internal 또는 공개 점령 RPC 충돌 시 중지하며, 자동 upgrade/drop/reset하지 않는다.
- 새 schema/함수/기본 중지 상태만 설치한다. catalog/팀/world/S, Cron, 앱 설정을 자동으로 넣지 않는다. 설치 직후 정상 제출이 가능한 상태라고 간주하지 않는다.
- 공개 팀 선택·소속·제출·스냅샷의 입력/성공 계약은 유지한다. 검증 gateway는 처음부터 private `submit_checked`로 만들고 공개 제출은 중지 경계만 거친다. prototype의 함수 이동을 배포 단계에서 수행할 필요가 없다.
- 정산은 `settle_due(partition, max_noons default 7)`다. 시험용 현재 시각 인자/overload는 없다. 실제 서버 시각을 잠금 뒤 읽는다. 내부 `p_admitted_ms`는 private gateway가 서버에서 정한 배치 시각을 필수로 전달하는 값이며 클라이언트 시각 인자가 아니다.
- 기존 함수/테이블의 클라이언트 권한은 넓히지 않는다. raw 운전자 proof를 저장하는 테이블을 추가하지 않는다.

운영 적용 전에는 기존 migration 이력과 실제 스키마를 대조하고, catalog hash/1453방향·팀·출처별 S·담당자/권한/백업을 확정한다. 기존 SQL Editor 설치가 CLI 이력에 반영됐다고 가정하지 않으며 전체 `db push/reset`으로 이전 탐험 SQL을 다시 실행하지 않는다. 운영 배포와 이력 기록 방식은 그 대조 후 결정한다.

## 시험 프로젝트 리허설

`hosted-release.mjs`는 정확한 별도 시험 ref만 허용한다. 기존 public API/conquest_internal을 변경하지 않고 migration의 namespace와 공개 RPC 위치만 새 `conquest_release_<12hex>`로 치환한다. 모든 시험 함수 접근도 닫는다. 원래 migration을 canonical 운영 namespace에 적용한 것과 구분한다.

```powershell
node supabase/conquest/hosted-release.mjs --run --project-ref <ISOLATED_REF> --cli <CLI_EXE> --workdir <PRIVATE_ENV> --out <PRIVATE_REPORT_DIR> --user <EXISTING_TEST_AUTH_UUID>
```

리허설 catalog는 실제 전체1453방향이다. 기존 시험 Auth UUID는 FK 참조/관리 SQL의 시험 subject로만 쓰며 Auth 계정·토큰·탐험 기록을 변경하지 않는다. 누락 정산 시나리오를 위해 운영 시작을 과거로 둔 새 시험 scope와 합성 인정 이력을 넣는다. 현재 서버 시계를 바꾸거나 앱에 지연 제출 우회 API를 만들지 않는다. 합성 이력은 새 시험 schema에만 저장한다.

## Cron 검증과 정리

별도 시험 프로젝트에만 공식 `pg_cron`을 설치하고 실제 timezone/database를 확인한다. 고유 job 이름으로 충돌을 검사해 비활성 생성한다. 분 단위 시험 job은 한 번의 bounded 정산 후 스스로 비활성화하며, 실제 실행 이력/정산 revision을 확인한다. 이후 명시적 비활성 상태에서 다음 분 tick까지 추가 실행이 없는지 관찰한다. 시험 job ID/name/command를 재검사한 뒤 그 job만 unschedule하고 실행 이력은 남긴다.

`test-cron-admin.sql`은 시험 marker·앱 역할의 Cron schema 접근 부재·native RLS를 검사한다. Supabase가 소유하는 기본 정책을 수정하거나 앱 권한을 추가하지 않는다. 운영 스케줄러에는 이 시험 전용 파일을 적용하지 않는다.

누락분은 실제 서버 시각과 `max_noons` 한도로 시간순 복구하고, hasMore=false까지 완료한 뒤 재호출이0건인지 확인한다. 이력/run·현재 소유·마지막 포인터를 같이 검사한다. 1분 cadence 시험은 실제 KST12시 daily 운영 실행 시험이 아니다. 운영 UTC03시 스케줄은 별도로 검토/활성화하며 운영 Cron은 이번 작업에서 설치하지 않는다. [공식 설치](https://supabase.com/docs/guides/cron/install), [실행·중지·이력](https://supabase.com/docs/guides/cron/quickstart)을 따른다.

## 점령 데이터 논리 백업·복원 범위

`release-state.mjs`는 정해진14개 점령 테이블만 내보낸다. 도로 geometry는 EWKB로 보존하고, receipt/gateway hash·기여·멤버십·정산 전체 이력/포인터·운영 제어/감사 이력을 포함한다. 키/암호/JWT/Keystore와 운전자 proof 원문은 백업하지 않는다. catalog 좌표는 공개 지도 데이터이며 proof 이동 원문과 구분한다.

백업은 제출 중지 상태에서 만들고 migration SHA256/데이터 digest와 함께 Git 밖에 저장한다. digest는 손상 검사이며 진위 서명이 아니다. 같은 migration을 설치한 **두 번째 빈 시험 스키마**에만 복원한다. 기존 데이터가 있거나 허용된 시험 schema 이름이 아니면 거부한다. scope↔run FK 순환은 초기 scope→run/road 이력→최종 포인터 순으로 복원하고, 기본 제어 두 행만 백업 값으로 갱신한다. DROP/TRUNCATE나 기존 대상 병합을 수행하지 않는다.

복원 후 전체 데이터/geometry digest를 비교하고 원본도 그대로인지 확인한다. 마감이 지난 인정 원문 재전송이 기존 ACCEPTED를 반환하고 기여가 늘지 않는지도 검사한 뒤 다시 중지한다.

**전체 Supabase DB/PITR/Auth/Storage/탐험 복원이 아니다.** 같은 시험 프로젝트의 기존 Auth FK를 재사용한다. 운영 재해 복구에는 실제 백업 요금제/가용성·Auth 포함 전체 프로젝트 복구·다른 개발자 데이터 영향 검토가 별도로 필요하다. [공식 백업 문서](https://supabase.com/docs/guides/platform/backups)를 기준으로 확인한다.

## 실제 검증 결과 (2026-11-14~15 KST)

PGlite 검사7개 통과. 실제 별도 hosted 리허설5개도 통과했다. pg_cron1.6.4/DB postgres/스케줄 시간대 GMT, 비활성 생성→실제 run1회 succeeded→자동·명시적 비활성→추가65초 동안 실행 없음 확인. Cron이 누락1일을 처리한 뒤 수동으로 나머지3일을 시간순 복구해 revision4/hasMore=false가 됐고, 다시 호출하면0건이었다. 실행한 시험 job은 제거하고 run history1행을 보존했다.

논리 백업은2,116,971bytes였다. 새 대상에 도로/geometry1453개씩, 정산 run4개/도로 결과5812개, receipt/기여/멤버십 각1개, 두 scope/world/제어와 감사 이력을 복원했다. 전체 데이터 digest·EWKB가 일치하고 원본이 유지됐다. 복원된 이전 인정 이벤트는 마감 이후에도 ACCEPTED를 반환했고 기여는1건이었다. 운영/기존 시험 public 함수 해시와 canonical 점령 상태도 유지됐다. 리허설 SQL/백업/상세 보고서는 Git 밖이다.

보안 진단(기본 최소WARN)은 ERROR0/WARN11이다. 기존9개 외에 native Cron 두 테이블의 PUBLIC RLS 정책 경고2개가 추가됐다. 실제 anon/authenticated의 cron schema USAGE는 false이며 두 역할의 cron.schedule 호출이42501로 거부되고 활성 job0개임을 확인했다. 정책/테이블 소유자는 supabase_admin이다. 정책 변경 시도는 소유권 부족으로 트랜잭션 전체가 롤백됐고 최종 도구는 변경 대신 접근 검사를 한다. 이 managed 정책 경고를 해소했다고 표시하지 않으며 INFO 전체도 재집계하지 않았다. 기존 탐험 정책·운영 권한은 수정하지 않았다.

최종 읽기 조회: 운영은 점령 schema 없음/Cron 미설치/대상 테이블8·함수1, 기존 시험 canonical은 제출 제어 포함 테이블22·함수5/Cron 설치 상태다. 새 리허설 schema들은 공개 API 대상이 아니며 사용자 앱 rollout도 계속 비활성이다. 이번 작업에는 Android/런타임 코드 변경이 없어 Gradle을 재실행하지 않았다.
