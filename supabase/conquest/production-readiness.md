# 점령 운영 적용 준비와 복구 순서

상태: 2026-11-14 운영 메타데이터를 실제 **읽기 전용**으로 확인했다. 이 문서는 적용 계획이며 migration/Cron/운영 시작/앱 활성화 완료 기록이 아니다. 공통 계약 v0.1은 여전히 공동 승인 대기다. 운영 ref와 비공개 조회 결과는 개인 작업 공간에 보관한다.

2026-11-14~15 후속: CLI 생성 정식 migration 준비, 실제 시험 Cron 실행/중지·누락 복구와 점령 논리 백업·빈 스키마 복원이 통과했다. 상세/제한은 [release-guide.md](release-guide.md)에 있다. 운영 migration/Cron은 적용하지 않았다. 전체 DB/Auth/Storage 복원과 운영 부하·실물기기/Pleos 시연은 별도다.

## 실제 운영 상태

| 항목 | 확인 결과 |
| --- | --- |
| DB | PostgreSQL17.11, DB TimeZone UTC |
| 기존 점령 테이블 | public.conquest_road_catalog / conquest_ownership |
| 기존 소유자 의미 | conquest_ownership.owner_user_id UUID(개인), 팀 소유자가 아님 |
| 기존 공개 동기화 | sync_exploration_v1(text,jsonb), SECURITY DEFINER, 고정 빈 search_path |
| 신규 점령 서버 | conquest_internal 없음 / conquest_ 공개 함수 없음 |
| 스케줄 | pg_cron 미설치, 점령 Cron 미생성 |
| 클라이언트 | 일반 앱 rollout/업로드 비활성 |

예약 테이블을 팀 점령 저장소로 바꾸거나 탐험 테이블/함수를 재작성하지 않는다. `production-preflight.sql`은 메타데이터·RLS·권한·함수 본문 해시·예약 열만 읽는다. 사용자 이동/계정 행·토큰은 조회하지 않는다. 같은 파일을 적용 전후에 명시적 대상과 `--read-only`로 실행해 차이를 기록한다. DB TimeZone UTC만으로 아직 없는 Cron의 실행 시간대까지 검증됐다고 간주하지 않는다.

이 파일을 실제 운영/별도 시험 프로젝트에서 각각 실행해 성공했다. 운영은 public 함수1개/대상 테이블8개, 시험은 함수5개/테이블20개였고 두 DB 모두 Cron 미설치다. 숫자는 이 쿼리의 namespace/함수 필터 범위이며 DB 전체 객체 수가 아니다. CLI의 `--file` 상대 경로는 `--workdir` 기준으로 해석되므로 `(Resolve-Path ...).Path`로 만든 절대 경로를 전달한다. 최초 상대 경로 시도는 SQL 파일을 찾기 전에 실패했으며 DB 쿼리를 실행한 결과로 기록하지 않는다.

## 적용할 작업 순서

1. 점령 계약/운영 담당과 Auth 재사용·팀·지연 전송 정책의 적용 범위를 확인한다. 개인 개발 승인과 공동 계약 승인은 구분한다. 실제 운영 시작 S와 허용 catalog/팀 목록을 기록한다. 기존 기기 기록의 자동 이관은 하지 않는다.
2. 현 스키마·함수 해시·권한·migration 이력과 실제 백업/복원 가능 상태를 확인한다. 기존 탐험 SQL 재적용이나 repo 전체 db reset/push를 적용 방식으로 사용하지 않는다.
3. 검증된 점령 SQL만 정식 migration으로 준비한다. 파일명은 CLI `migration new`로 생성한다. 현재 `*-prototype.sql`, 시험 환경13단계 bundle, `conquest_race_*` 복사본은 운영 적용 파일이 아니다. 시험 clock/시간 override·fixture/marker·시험 actor는 운영 경계에 포함하지 않는다.
4. 닫힌 private ledger→도로 geometry/판정→최소 권한 gateway→정산/스냅샷 순으로 준비한다. 고정 search_path, JWT auth.uid, private 접근 철회, 필요한 authenticated execute만 유지한다. 기존 예약 테이블·탐험 모델·namespace/모듈은 유지한다.
5. 실제 asset의 catalog hash/1453방향 고유 ID/geometry와 서버 목록을 대조하고 팀 목록을 설정한다. 팀 수를2로 고정하는 DB 제약을 추가하지 않는다. 출처별 S를 한 번만 초기화하고 T₀=엄격히 다음 KST12시, 초기 중립/revision0을 확인한다.
6. 앱 비활성 상태에서 실제 JWT·권한·명시적 팀 선택·기여 멱등·초기 스냅샷을 운영 적용 절차의 범위에서 확인한다. 서버 전체1453방향 처리 시간/잠금 대기와 statement timeout은 아직 운영 측정이 필요하다.
7. 서버 제출 중지/재개와 미전송 보존을 검증한다. 앱 빌드 flag만 끄는 것으로 이미 배포된 앱의 요청을 멈출 수 있다고 가정하지 않는다. 별도 시험 서버의 `intake-prototype.sql`과 실제 HTTP/잠금/Android 검증은 준비됐으며, 운영에는 정식 migration으로 적용해야 한다. 기본 중지·운영자 전용 CAS/이력과 HTTP503 배치 보류를 사용한다. 세부 규칙은 intake-contract.md를 따른다.
8. 정산 실행 주체·실패 알림/누락 감시·재실행 책임자를 정하고 별도 시험에서 Cron을 실제 실행/중지/복구한다. 그 뒤 운영 스케줄과 앱 rollout을 단계적으로 활성화한다. 이 문서 작성 중에는 실행하지 않았다.

## Cron과 감시 제안

실행 주체는 서버 운영 경계다. 앱에 정산/과거 시각 인자를 제공하지 않는다. 운영 Pleos scope를 대상으로 KST12시를 UTC03시로 환산하되, 활성화 전 실제 scheduler 시간대와 권한을 확인한다. 시험 emulator scope의 정산을 운영 주행 점수에 섞지 않는다.

최종 migration/권한 검토 후 사용할 명령의 형태는 `select conquest_internal.settle_due('pleos')`다. 서버 실제 시각을 사용하며 시험 override를 넣지 않는다. 같은 이름의 job을 무심코 upsert하지 않도록 기존 job의 ID/command/schedule/active를 먼저 확인한다. 신규 job은 비활성으로 준비하고 확인 후 활성화한다. [공식 Cron quickstart](https://supabase.com/docs/guides/cron/quickstart)는 job 활성/비활성 전환과 실행 이력 조회를 설명한다.

감시는 Cron 성공 문자열만 보지 않고 scope의 nextSettlementUtcMs가 서버 현재 시각보다 과거인지 확인한다. 정산 run/revision/모든 방향 결과 수, job 실패·실행시간·잠금 대기, 미전송/반복 거부/카탈로그 불일치도 확인한다. `cron.job_run_details` 보관/정리 기간은 별도 운영 기준으로 정하며 점령 receipt/기여 이력을 함께 지우지 않는다.

## 실패·누락 복구

- 정산 호출 실패: 같은 트랜잭션의 결과/포인터가 롤백됐는지 확인한다. 마지막 커밋 revision을 보존하고 동일 함수를 재호출한다. 이력을 삭제하거나 미래 revision으로 직접 맞추지 않는다.
- 누락: 시간순 정산이 필요하다. 기본7회/최대31회 단위 결과의 hasMore와 다음 시각을 확인하고 남은 호출을 이어간다. 마지막 하루만 계산하지 않는다.
- 카탈로그/이력 손상: 자동 초기화하지 않고 신규 제출/정산을 중지하고 원본과 증거를 보존한다. 같은 catalog 버전의 좌표/ID를 바꾸지 않는다. 별도 migration/재현 결과를 검토한다.
- 앱/서버 연결 문제: 앱은 마지막 확인본을 이전 확인본으로 표시하고 미전송 원문을 유지한다. 운영 차단/재개 시에도 ACK되지 않은 원문을 삭제하거나 새 이벤트 ID로 바꾸지 않는다.
- 배포 후 되돌림: 신규 유입/스케줄 중지→상태 보존/조사→검증된 이전 동작 또는 전진 수정 순서로 진행한다. 이미 인정한 receipt/기여를 가진 테이블을 DROP하는 rollback을 만들지 않는다.
- 백업: 실제 요금제의 백업/PITR 가용성과 복원 지점을 확인하고, 운영과 분리된 대상에서 실제 복원 후 계정 귀속·멱등 receipt·팀·정산 이력/포인터·catalog hash를 대조한다. 자동 백업이 있다고 추정하거나 다른 개발자의 전체 DB를 되돌리지 않는다. [공식 백업 문서](https://supabase.com/docs/guides/platform/backups)를 기준으로 적용한다. 현재 백업 실복원은 미검증이다.

## 현재 확보한 근거와 남은 범위

별도 시험 서버의 실제 Auth/API10개, Android 두 AVD의 큐/재전송10회 및 실제 단절·복원 차단12회, 제어된 시간의 실제 PostgreSQL 경합6개가 통과했다. 자세한 범위는 test-environment.md, docs/conquest-android-hosted-test.md, concurrency-test.md를 참조한다. 실제 물리 기기/Pleos 주행, 같은 계정 두 기기·허용된 복원의 A→B→A, 실제 정오/Cron 전체 경로·운영 부하·백업 실복원은 완료되지 않았다.

다음은 운영 적용 범위/기존 migration 이력 대조, 실제 catalog·팀·S bootstrap과 운영 권한/백업·스케줄 담당 검토다. 준비된 migration은 세계/카탈로그를 자동 시작하지 않으며 앱 활성화도 하지 않는다. 서버 중지·정식 migration·시험 Cron/논리 복구 검증 결과를 근거로 적용 순서를 검토한다. 프로필 서버 표시 변경과 기존 계정 복원의 위치 잠금 중 HTTP 대기는 친구 담당자와 별도 조율한다.
