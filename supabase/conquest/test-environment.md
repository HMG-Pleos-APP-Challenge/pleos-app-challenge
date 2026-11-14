# 별도 Supabase 시험 환경

## 최신 제출 제어 추가

2026-11-14 이후 새 준비 bundle은 intake-prototype을 포함한14단계이며, 두 출처 모두 기본 중지다. 아래13단계 설치/HTTP10개 기록은 중지 경계 추가 전의 실제 이력이다. 기존 hosted 시험 프로젝트에는 기존 gateway 본문 해시와 환경 marker를 확인한 뒤 추가 SQL만 적용했다. 기존 catalog/팀/운영 시작·기여·정산은 다시 초기화하지 않았다.

새 HTTP 제출 시험에는 운영자의 명시적 시험 출처 재개가 필요하다. `hosted-intake.mjs`는 기본 중지에서 시작해 실제503/재개/멱등/잠금 검사를 수행하고 자신이 소유한 revision만 다시 중지한다. 기존 `hosted-smoke.mjs`는 중지된 서버를 자동으로 열지 않으므로 실행 전후 상태를 운영자가 관리해야 한다. 키·세션은 Git 밖에 유지하며 자세한 호출 규칙은 intake-contract.md에 기록한다.

2026-11-14. 사용자 지시로 운영과 분리된 Daehyundai-conquest-test 조직/프로젝트를 서울 리전에 생성했다. 대상 ref·URL·공개 키·CLI 설정·적용 해시/보고서는 Git 밖 개인 작업 공간에 보관한다. 기존 운영 Daehyundai 프로젝트에는 SQL·인증 설정·앱 설정을 적용하지 않았다.

## 현재 확인한 범위

공식 Supabase CLI3.1.0 Windows 배포 파일의 SHA-256을 공식 release metadata와 대조해 개인 .tooling에 설치했다. 브라우저 인증으로 CLI 로그인에 성공했다. Supabase 플러그인은 설치돼 있지만 이 채팅에 실행 도구가 없어 CLI를 사용했다. Docker/Podman/실행 가능한 WSL 환경은 없으므로 현재 시험은 별도 hosted 프로젝트다. 시스템 PATH·Docker/WSL·기존 repo Supabase 설정은 바꾸지 않았다.

새 프로젝트의 명시적 대상 ref로만 PostgreSQL17.11/auth.users 존재와 비어 있는 점령 schema를 확인했다. 프로젝트별 기본 branch를 CLI가 production으로 표시하는 것은 이 새 시험 프로젝트 안의 기본 환경 이름이며, 기존 운영 ref나 Git main을 사용했다는 뜻이 아니다.

기존 sql-editor 파일을 수정하지 않고 그대로 읽어 탐험 backend/카탈로그를 재사용하고, 점령 ledger/proof/gateway/settlement와 앱의 전체1,453방향 geometry·team-red/team-blue를 추가했다. 두 출처의 운영 시작은 적용 순간의 서버 시각으로 동일하게 초기화했다. 첫 상태는 중립/revision0이다. Cron 확장/Job은 켜지 않았고 앱 rollout/업로드도 여전히 false다.

인증 설정은 별도 최소 config.json으로 diff를 검토한 뒤 enable_anonymous_sign_ins 한 항목만 true로 적용했다. 미선언11개 remote 설정은 그대로 남았다. 기존 운영 설정·로컬 Android local.properties를 변경하지 않았다. DB 비밀번호는 임의 생성 후 Git 밖에서 Windows DPAPI로 보관한다. 관리 키/CLI 토큰을 Android나 공유 문서에 복제하지 않는다.

## 재현 가능한 SQL 준비

prepare-test-env.mjs는 네트워크를 호출하지 않고, 명시적인 시험 ref를 받아 적용할 SQL13개와 해시 manifest를 출력한다. 알려진 운영 ref·잘못된 ref를 거부한다. 실제 앱 asset의 catalog/version/1,453개 고유 ID/좌표를 읽고 서버 geometry로 변환하며 새 SDK/데이터 모델을 도입하지 않는다.

첫 SQL은 비어 있는 프로젝트인지 확인하고 test control marker를 만든다. 다음 SQL은 그 target marker를 검사한다. 기존 설치에 재실행하면 실패하며 reset/drop/운영 migration/스케줄을 자동 수행하지 않는다. 실제 적용 전에는 target ref/manifest/file SHA-256을 모두 확인하고 CLI db query --remote=<TEST_REF> --file <stage> --yes를 순서대로 실행한다. 현재 repo의 기존 migrations 경로에 link/push/reset하지 않는다.

```powershell
# 저장소 루트. OUT_DIR는 Git 밖 개인 시험 디렉터리.
node supabase/conquest/prepare-test-env.mjs --project-ref <TEST_REF> --out <OUT_DIR>
node supabase/tests/conquest-test-bundle.mjs
```

conquest-test-bundle.mjs는 새 PGlite에 전체 SQL을 적용해 운영/malformed ref 차단, 도로/geometry1453개, 실제 앱 팀 ID, 두 중립 scope, 소속/스냅샷 및 기존 탐험 함수, 중복 설치 거부를 확인한다. 이 준비 시험을 통과한 동일 파일의 해시를 확인해 새 hosted 시험 DB에 적용했다.

## 실제 Auth/PostgREST 검사

hosted-smoke.mjs는 명시된 시험 ref와 일치하는 HTTPS endpoint/public key만 허용한다. 공개 클라이언트 키와 실제 익명 Auth 두 계정의 세션을 사용하며 service_role 키를 사용하지 않는다. 각 실행은 새 익명 계정 두 개와 합성 기여를 만든다. 이메일/초대/실제 GPS/운영 데이터를 사용하지 않고 세션 토큰을 파일이나 로그에 남기지 않는다. 공개 키 입력 파일은 개인 Git 밖에 둔다.

```powershell
node supabase/conquest/hosted-smoke.mjs --project-ref <TEST_REF> --config <PRIVATE_PUBLIC_CLIENT_JSON> --report <PRIVATE_REPORT_JSON>
```

실제 검사10개가 통과했다. 익명 두 계정·소속 없음의 무생성 조회·미인증/소유자 인자 위조 차단·명시적 팀/변경 금지·전체 도로 스냅샷·최소 근거 인정/불변 해시·유실 응답 재전송/반일 중복 무가산·다른 계정 독립 기여/요약 격리·실제 refresh token·private schema/예약 테이블 직접 쓰기 차단을 확인했다. 서버 시각을 기준으로12초 합성 근거를 만든 뒤 완료 시각까지 기다려 제출했다. 시간 인자를 공개 API에 추가하거나 실제 주행을 주입하지 않았다.

실제 DB 재조회: roads1453/geometries1453/teams2/scopes2, 정산0, Cron 확장 없음. 이번 실제 HTTP 시험은 첫 정산 전 기여/요약까지다. 실제 낮12시 정산·다중 연결의 마감 경합·Cron 누락 복구·Android 두 기기·실차 검증은 아직 별도다. 기존 격리 SQL27개 및 앱270개 단위/Lint/APK 기록과 구분한다. 앱 코드가 바뀌지 않아 이번 환경 준비에서 Gradle을 다시 실행하지 않았다.

추가 실제 조회에서 Auth 사용자2/인정 기여2/receipt3을 확인했고, receipt payload에 samples/latitude/longitude가 없는 것을 확인했다. 원시 최소 근거를 보관하는 새 테이블은 만들지 않았다. 두 scope의 첫 정산 시각은 2026-11-15 KST12시로 계산됐으며 자동 스케줄을 실행했다는 의미는 아니다.

## 보안 진단과 남은 점

실제 security advisors는 ERROR0/WARN9/INFO18이었다. INFO18은 닫힌 테이블의 RLS/no-policy다. WARN은 authenticated SECURITY DEFINER 함수5개, 기존 탐험의 익명 Auth 사용자 허용 정책3개, leaked-password protection 비활성1개다. 모든 경고가 해결됐다고 표시하지 않는다.

점령 공개 함수4개는 실제 권한 조회에서 anon execute=false/authenticated=true/search_path=""였다. 신뢰된 JWT UID·엄격한 입력 검사·내부 테이블 권한 철회와 실제 HTTP 접근 차단을 확인했다. 기존 탐험의 자기 계정/공개 catalog 정책을 경고 제거 목적으로 수정하지 않았다. 이메일/비밀번호 연동 시험과 운영 공개 전에는 password protection/요금제 지원 여부와 인증 정책을 별도로 검토해야 한다.

다음은 시험 프로젝트를 대상으로 Android의 명시적 시험 연결을 구성하고 두 기기/계정 전환/네트워크 단절/실제 요청 경합을 검증하는 것이다. 이번 준비를 운영 배포나 모든 보안·통합 검증 완료로 간주하지 않는다. 생성한 시험 계정/기여는 그대로 두며 자동 삭제·reset하지 않는다. 정식 운영 migration과 스케줄/감시·복구는 후속 운영 절차로 관리한다.
