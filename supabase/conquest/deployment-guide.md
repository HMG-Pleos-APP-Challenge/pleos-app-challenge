# 운영 스키마 대조와 닫힌 초기화 묶음

2026-11-15 KST: **운영에는 읽기만 수행했다.** 이 문서는 운영 배포·공동 계약 승인·앱 활성화 완료 기록이 아니다.

## 기존 설치 확인

`audit-deployment.mjs`는 CLI `db query --read-only`로 public 테이블의 컬럼·NOT NULL·기본값·제약·인덱스·RLS·정책, 앱 역할의 쓰기 권한, 기존 `sync_exploration_v1`/`activate_grid500_v2` 본문 해시·설정·실행 권한을 비교한다. 사용자/위치 행은 읽지 않는다. Auth/Storage 전체, 모든 역할·트리거·뷰를 포괄하는 완전한 DB 덤프 비교는 아니다.

운영의 비교 대상 메타데이터는 기존 migration 3개 및 SQL Editor `00-schema.sql`과 일치했다. PGlite의 PostgreSQL18은 NOT NULL을 `pg_constraint`에도 표시하므로 이 항목은 양쪽 공통 `pg_attribute.attnotnull`로 비교한다. 나머지 제약은 그대로 비교한다. SQL Editor 나머지 파일의 실제 카탈로그 데이터 일치까지 주장하지 않는다.

운영에는 `supabase_migrations.schema_migrations`와 `conquest_internal`이 없다. 기존 탐험/예약 테이블이 존재하므로 **전체 db push/reset/자동 migration repair를 실행하지 않는다.** 설치된 것으로 확인된 범위와 이력 기록은 별개다. 기존 migration 파일도 바꾸지 않는다. 이력 기준선 반영은 담당자와 별도 계획하며, 이번 묶음은 이전 SQL을 재실행하지 않고 새 점령 DDL만 검토할 수 있게 한다.

```powershell
node supabase/conquest/audit-deployment.mjs --project-ref <OPERATING_REF> --cli <CLI_EXE> --workdir <PRIVATE_ENV> --out <PRIVATE_AUDIT_DIR>
node supabase/conquest/build-deployment-bundle.mjs --audit <PRIVATE_AUDIT_JSON> --out <PRIVATE_BUNDLE_DIR>
```

생성기는 24시간 이내 읽기 전용 조사, 이력/schema 부재, 차이 없음, 기존 SQL 해시 유지와 정식 migration 일치를 요구한다. migration·bootstrap·verify SQL·조사 결과·파일별 SHA256 manifest를 **파일로만** 만든다. 실행/업로드 기능은 없다. 적용 직전 다시 조사해야 하며, 오래된 fingerprint는 배포 권한이 아니다.

## 초기 데이터와 S

`build-bootstrap.mjs`는 앱의 기존 `namyang.segments`를 읽어 동일한 1,453방향 도로/순서/좌표를 만든다. 친구 탐험 런타임에 직접 의존하지 않는다. 기본 팀은 승인된 `team-red`, `team-blue`, 출처는 `pleos`, `pleos-emulator`다. 생성기 자체는 확장을 지원하지만 기본 앱 팀 수를 바꾸지 않는다.

- 정식 release 함수와 빈 게임 상태, 기본 paused/revision0 제어를 요구한다. 기존 데이터가 있으면 중지한다.
- 한 트랜잭션으로 팀/catalog/도로/geometry/world를 넣은 뒤 서버 시각을 **한 번** 읽어 두 출처의 S로 쓴다. 첫 정산은 S보다 엄격히 뒤인 KST12시다.
- 초기 중립/revision0, 기여/소속 없음, 두 제출 경로 paused를 유지한다. Auth/탐험/예약 테이블/이력/Cron/앱 설정을 쓰지 않는다.
- 실패하면 전체 롤백한다. 반복 실행은 거부하며 S를 덮어쓰거나 기존 상태를 reset하지 않는다.
- 검증 SQL은 방향 순서까지 보존한 EWKB·ordinal·ID·팀·world·중립·동일 S·다음 정오·중지를 검사한다. 무방향 `ST_Equals`만으로 검사하지 않는다.

S는 파일 생성 시각이 아니라 실제 bootstrap 시각이다. **운영 시작과 멀리 떨어진 날에 미리 실행하면 안 된다.** 사전 설치와 실제 시작을 나눌 필요가 있으면 bootstrap을 실행하기 전에 시작 절차를 조정한다. 이미 기록된 S를 임의로 고치지 않는다.

## 실제 검증

`conquest-bootstrap.mjs` 7개: 전체 데이터/시각/중립/중지, 재실행 거부와 S 보존, 반대 방향 및 ordinal 변조 탐지, 시험 3팀 확장, 잘못된 입력 거부, 삽입 실패 전체 롤백. 기존 fresh test bundle 및 release 7개 회귀도 통과했다.

`hosted-bootstrap.mjs`는 정확한 별도 시험 프로젝트와 marker만 허용한다. 새 비공개 `conquest_release_aadb88dfdd07`에서 4개 검증을 통과했다. 실제 S=1794683664882, 첫 정산=1794711600000(2026-11-15 KST12시), 두 출처 동일, 1,453방향/기여0/paused. 기존 공개 함수 해시·canonical 점령 상태·활성 job 수는 전후 일치했다. 운영 namespace에 설치한 시험은 아니며 새 시험 schema는 보존했다. 실제 Auth/HTTP 경쟁은 이전 단계 결과와 구분한다.

```powershell
node supabase/tests/conquest-bootstrap.mjs
node supabase/tests/conquest-test-bundle.mjs
node supabase/tests/conquest-release.mjs
node supabase/conquest/hosted-bootstrap.mjs --run --project-ref <ISOLATED_REF> --cli <CLI_EXE> --workdir <PRIVATE_ENV> --out <PRIVATE_REPORT_DIR>
```

시험 서버 security advisors 최소WARN 기준 ERROR0/WARN11로 이전과 같았다. 기존 공개 SECURITY DEFINER·탐험 정책·관리 Cron 정책 등 경고를 해결했다고 표시하지 않는다. 개인 보고서/SQL/배포 묶음에는 Git 밖 경로를 사용한다. Android 변경이 없으므로 이번 Gradle/기기 검증은 재실행하지 않았다.

## 운영 적용 순서와 남은 경계

1. 친구와 신규 점령 RPC/팀/출처/시작 시점·적용 담당을 확인하고, 현재 운영 백업과 전체 DB/Auth 복구 가용성을 확인한다. 공동 승인 대기를 임의 해제하지 않는다.
2. 적용 직전 운영 메타데이터/충돌을 다시 확인하고 파일 SHA와 대상 ref를 점검한다. migration 이력 기준선 처리 방식을 별도로 기록한다.
3. 검토된 **새 점령 release 파일 하나**를 적용한다. 기존 migration 3개 전체 재실행은 하지 않는다. 실패 시 트랜잭션 결과를 조사하고 무조건 재시도하지 않는다.
4. 시작 시점에 bootstrap을 한 번 적용하고 모든 verify 항목이 true인지 확인한다. 이때 제출은 paused이며, 기존 탐험 API 회귀와 보안 진단을 확인한다.
5. 운영 Cron UTC03시 설치·권한·실행 및 누락 복구를 별도 검증한다. 앱 rollout/시험 계정 smoke/접수 재개는 각각 상태를 확인하며 진행한다. 시작 지연이 생겨도 S를 고치거나 마감을 우회하지 않는다.
6. 장애 시 먼저 점령 접수를 중지하고 증거/백업을 보존한다. 이미 배포된 schema를 DROP하거나 기존 탐험/계정까지 덮어쓰는 자동 복구는 제공하지 않는다.

이번에는 1~6의 운영 쓰기를 실행하지 않았다. 다음 단위는 실제 운영 백업·이력/담당 확인 후 닫힌 운영 설치 판단이며, 물리기기/Pleos SDK와 최종 시연은 별도 남아 있다.
