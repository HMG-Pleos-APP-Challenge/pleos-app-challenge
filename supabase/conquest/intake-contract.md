# 점령 제출 중지·재개

정식 migration 준비와 별도 release 리허설은 [release-guide.md](release-guide.md)를 따른다. release는 검증 gateway를 처음부터 private submit_checked에 생성하고 시험 시간 인자를 없애며, 이 문서의 prototype 설치/이력과 구분한다. 운영에는 아직 적용하지 않았다.

점령 전용 격리 프로토타입이다. 공동 계약 승인/정식 migration/운영 배포가 아니다. `intake-prototype.sql`은 gateway와 settlement 뒤에 적용한다. 새 시험 bundle은14단계이며 두 출처 모두 **기본 중지**다. 전체 시험 bundle을 운영 DB에 적용하지 않는다.

## 경계와 호환성

- 운영자만 private `intake_status(partition)`와 `set_intake(partition, paused, expected_revision, reason)`을 호출한다. 앱 토큰에는 schema/함수/테이블 권한을 주지 않는다.
- 공개 `conquest_submit_v1`의 이름·인자·성공 응답은 유지한다. 기존 검증 gateway는 본문을 복제하지 않고 private schema로 옮기고 클라이언트 execute를 철회한다. 새 공개 경계만 중지 상태를 검사한다.
- 두 출처 상태는 독립적이다. 요청·중지 모두 world→control 순서로 잠근다. 이미 처리 중인 트랜잭션이 끝난 뒤 중지가 완료되고, 이후 대기 요청은 중지를 확인한다. 반대 순서도 같은 잠금을 따른다.
- 중지/제어 행 부재 시 `PT503`을 발생시킨다. PostgREST의 [공식 custom error 규칙](https://docs.postgrest.org/en/stable/references/errors.html#raise-errors-with-http-status-codes)에 따라 HTTP503이다. 성공 응답/이벤트 ACK를 만들지 않고 배치 전체를 보류한다.
- 기존 인정 이벤트의 재전송도 중지 중에는 ACK하지 않는다. 이미 저장한 결과는 유지하며 재개 후 원래 결과를 반환한다. 혼합 배치 일부만 처리하지 않는다.
- 팀 선택·소속/스냅샷 조회와 정산은 이 스위치로 중지하지 않는다. 기여 제출과 정산/Cron 중지는 별개다.
- 중지는 마감 연장이 아니다. 중지 전 인정된 원문은 재개 후 멱등 재전송된다. 재개가 마감 이후이고 최초 제출이면 기존 LATE 규칙을 따른다.

기존 앱 인증 경계는 비2xx를 REQUEST 실패로 전달하고 실제 큐는 원문을 보존한다. 새 공통 모델/Route/오류 status를 추가하지 않는다. 현 UI는 별도 '운영 중지' 사유 대신 기존 서버 연결 실패·기기 보관 상태를 사용한다. 일반 앱 rollout/업로드는 계속 비활성이다.

## 상태 변경과 이력

각 출처의 revision0/중지 상태를 초기 이력으로 저장한다. 운영자는 현재 revision을 읽고 예상 revision과 1~200자 사유를 보낸다. stale revision은 거부하고, 같은 상태의 재요청은 revision/이력을 바꾸지 않는다. 실제 상태 전환과 이력 insert는 같은 트랜잭션이며 이력 저장 실패면 상태도 롤백된다. 이력의 databaseRole은 DB 역할이며 사람의 실명/개별 운영자 신원을 증명하지 않는다. 사유에 비밀키/좌표/개인정보를 넣지 않는다.

```sql
-- 운영자 전용. 대상 프로젝트와 최신 revision을 먼저 확인한다.
select conquest_internal.intake_status('pleos-emulator');
-- 조회한 revision이 0일 때의 명시적 시험 재개 예시:
select conquest_internal.set_intake('pleos-emulator', false, 0, 'Reviewed isolated test');
-- 재개 후 중지할 때는 다시 조회한 최신 revision을 사용한다.
```

CLI의 hosted `--read-only`는 실제 `supabase_read_only_user` 역할을 사용하며 private 함수 EXECUTE가 없다. private 운영 함수는 명시적 운영자 CLI 실행 문맥에서 호출한다. 이를 위해 앱 권한/PUBLIC execute를 열지 않는다. `intake_status` 자체는 STABLE 읽기 함수다. 재실행/업데이트는 함수 이동과 설치 상태를 확인한 정식 migration이 필요하며 prototype을 다시 무조건 적용하지 않는다.

## 검증 방법

`node supabase/tests/conquest-intake.mjs`는 전체 도로 시험 bundle과 제어 시계로 기본 중지·권한·출처 격리·기존 성공/재전송·배치 보류·마감 유지·CAS·제어 행 부재·이력 실패·조회 유지를 검사한다. 별도 `conquest-test-bundle.mjs`도 새 초기 상태를 확인한다.

`hosted-intake.mjs`는 정확한 별도 시험 ref만 허용하고 공개 키/실제 익명 Auth로 HTTP503·조회·재개·멱등/반일 중복을 검사한다. 세션/키는 메모리에서만 쓰며 관리 호출은 CLI 자격증명 저장소를 사용한다. 실제 PostgreSQL 트랜잭션에서 인정된 합성 원문을 재전송하고 잠금을 유지한 뒤, 관찰 연결에서 반대 작업의 Lock 대기를 확인하는 양방향 검사도 포함한다. 이 두 잠금 검사는 관리 SQL의 시험 JWT subject를 사용하며 HTTP 인증 검증과 구분한다. 실제 Auth와 HTTP 오류 변환은 별도 앞 단계에서 확인한다.

시험은 명시적 중지 상태에서 시작하며 자신이 기록한 revision이 그대로일 때만 끝에 다시 중지한다. 다른 운영자의 새 revision을 자동 덮어쓰지 않는다. 시험 새 계정/기여는 삭제하지 않는다. 운영 프로젝트를 대상으로 실행할 수 없으며 시험 SQL/보고서는 Git 밖에 보관한다.

Android `ConquestHostedProbe -e phase paused`는 네트워크가 연결된 상태에서 중지된 서버에 실제 제출하고, 원문 행/ID/JSON과 스냅샷 조회 보존을 확인한다. 운영자가 시험 출처를 재개한 뒤 `sync`로 ACK/정확한 기여 증감을 검사한다. 다시 중지한 뒤 시험 기기를 종료한다. 실제 SDK/실차 시험은 아니다.

## 2026-11-14 실제 결과

- 새 SQL9개/PGlite 준비 bundle/기존 gateway17개 회귀 검사 통과.
- 실제 hosted 검사7개 통과: 익명 Auth·조회/팀 선택, HTTP503/PT503·기여 무변경, 재개 후 최초 인정, 중지 중 재전송 보류·stale 운영자 거부, 재개 후 멱등·반일 중복, 제출 우선/중지 우선의 실제 잠금 경합 두 방향. 두 경합은 관찰 연결의 Lock 대기를 확인했다.
- Android 두 AVD queue/paused/sync6회 통과. 중지 중 pending1/기여1과 원문을 유지하고, 재개 후 DUPLICATE_HALF_DAY·증감0·pending0을 확인했다. 전체1453방향 조회가 계속 성공했다.
- 실제 최종 상태: pleos 중지/revision0, pleos-emulator 중지/revision12, 초기 포함 변경 이력14행. 두 hosted 실행에서 만든 합성 기여까지 포함해 시험 전체 인정6건이며 기존 기록을 삭제하지 않았다. 원래 gateway 본문 해시는 private 이동 전과 같았다.
- APK/AndroidTest/Lint 빌드 성공. 변경 없는 JVM270개는 UP-TO-DATE 결과이며 새 실행으로 표시하지 않는다. 원래 local.properties를 복원했고 일반 APK도 원래 설정으로 재생성해 성공했다.
- 보안 진단(기본 최소WARN) ERROR0/WARN9, 기존 경고 유지. INFO 전체 재집계/전 경고 해결/운영 배포 결과가 아니다. 실제 조회에서 authenticated의 private gateway/관리 함수 execute와 anon의 공개 submit execute는 모두 false였다.

최초 PGlite 준비는 transaction-local JWT subject를 트랜잭션 밖에서 설정해 인증 실패했다. 준비 구간도 트랜잭션에 넣어 바로잡았으며 실제 인증 코드를 수정하지 않았다. 최초 hosted 상태 조회는 읽기 전용 역할의 private 함수 실행 권한 부재로 실패했다. 운영자 문맥으로 고친 뒤 전체 검증이 통과했다. 실패한 실행을 완료 근거로 사용하지 않는다. 설치는 원래 gateway 해시와 별도 시험 marker를 검증한 추가 SQL만 사용했으며 운영 DB에는 적용하지 않았다.
