# 점령의 기존 계정 연결

인증 기준은 main 1912d87(d2f6757), 점령 준비 기준은 454994b다. choi에서 두 이력을 병합한 뒤 연결했다. 기존 계약은 [shared-auth-boundary.md](shared-auth-boundary.md)를 따른다.

## 이번 연결 범위

- 앱 조립부 ConquestAccountAssembly가 ExistingAccountAuth를 점령 내부 ConquestAccountAccess로 변환한다. 점령 코드가 탐험 인증 타입·토큰·큐를 import하지 않는다.
- 설정 화면에서 SDK 점령 세션의 기존 계정을 IO dispatcher로 확인한다. 세션 없음/변경/오류를 구분하고 재확인할 수 있다. MOCK 세션은 서버 계정을 조회하지 않는다.
- existingAccount의 성공은 저장된 세션 식별이다. 원격 프로필 최신 확인이나 기여 제출 성공이 아니다. 설정 화면도 현재 게임은 이 기기 기록을 사용한다고 표시한다.
- 단순 조회는 binding 파일을 만들지 않는다. 조회만으로 빈 기기의 계정 복원을 차단하지 않는다.
- 후속 제출 준비용 bindCurrent는 withCurrentAccount로 UUID/전환 세대와 조립부 프로젝트를 확인한 뒤 auth→점령 record→binding store 순서로 귀속 파일을 저장한다. 재시작 시 인증 세대는 기존 암호화 vault를 재사용하며 별도 토큰이나 인증 세대를 디스크에 복제하지 않는다.
- 귀속 경로는 files/conquest/server-accounts/ 아래 프로젝트·계정·설치ID·출처·카탈로그의 해시 파일명이다. 해당 폴더의 내용은 실제 복원 직전 ConquestAccountRestore 검사에서도 차단한다. 알 수 없는 파일/하위 폴더·손상도 허용으로 처리하지 않는다. 비어 있는 정상 폴더는 허용한다.
- 앱의 단순 계정 확인에서는 bindCurrent를 호출하지 않는다. 실제 큐 추가와 최초 binding의 원자성은 P4에서 설계/검증해야 한다.

## 아직 연결하지 않은 부분

팀 선택/기여 제출/스냅샷 RPC와 서버 outbox/부분 ACK/서버 캐시는 아직 없다. 승인되지 않은 RPC 이름이나 wire 형식을 만들어 요청하지 않는다. 이번 점령 포트에는 범용 RPC를 노출하지 않았다. 서버 계약 확정 후 조립부에서 허용한 동작만 rpcForAccount에 매핑하고 응답 검증 뒤 withCurrentAccount의 짧은 로컬 트랜잭션으로 ACK/캐시를 처리한다.

454994b의 프로세스 ticket fence는 내부 준비 코드로 유지한다. 실제 인증 전환 판정은 이번에 전달된 기존 Auth의 generation을 사용한다. token refresh 때 임의로 점령 ticket을 재발급하는 연결은 하지 않았다.

기존 복원의 sampleAccess→auth 네트워크 대기는 이번 변경에서 유지한다. 복원 준비/최종 커밋 분리는 완료되지 않았다. 새 서버 큐가 구현되기 전 이 문제를 해결한 것으로 표시하지 않는다. 이번에는 탐험/프로필/인증/추적 서비스 내부를 새로 수정하지 않았다.

## 검증 경계

ConquestAccountAssemblyTest는 실제 ExistingAccountBoundary와 모의 세션/HTTP 대역으로 계정 부재·갱신·프로젝트/계정/세대 불일치·ABA·귀속 파일 오류·최종 복원 검사·출처 분리·취소를 검증한다. 기존 인증 9개/점령 binding 10개 테스트도 함께 실행한다.

기본 검증 명령은 개인 tools/build-local.ps1의 testDebugUnitTest/lintDebug/assembleDebug다. 실제 계정 로그인·운영 서버 전송·부분 ACK·두 계정/두 기기·실차 검증은 이 단위 검증과 다르다. Supabase URL/Publishable key 설정이 없으면 연결 없음으로 처리하며 기존 로컬 게임은 유지한다. 키 전문을 문서에 기록하지 않는다.

2026-11-13 최종 실행: 전체201개 단위 테스트 실패/오류0(인증9개+binding10개+연결10개 포함), lintDebug/assembleDebug 성공. Lint Warning5/Information1. 에뮬레이터에서 APK 업데이트 후 홈·프로필의 점령0회/0개 및 설정의 서버 미설정 안내를 UI hierarchy로 확인했다. 최근 AndroidRuntime 오류 출력 없음. 위치 권한 안내가 남아 주행 수집은 이번 검증에 포함하지 않았다. 실제 계정 성공/부재/변경 분기는 모의 인증 단위 테스트로 검증했으며 기기에서 실서버 인증을 실행하지 않았다.

## 후속 격리 API 계약 연결

2026-11-14 P4-3: 조립부에 점령 소속 조회 포트를 추가하고, MainActivity에서 SDK 점령 세션의 연결 진입점을 호출하도록 연결했다. 별도 rollout gate=false이므로 현재 앱 동작에서는 소속 RPC·가입·귀속 저장을 실행하지 않는다. 격리 시험에서만 기존 소속 조회→수집기 준비 및 명시적 서버 팀 선택→확정 팀 준비를 검증한다. [conquest-membership.md](conquest-membership.md)를 따른다. 토큰/기존 탐험 인증 구현과 로컬 요약은 유지한다.

2026-11-14 후속: 최소 근거/SQLite 큐/부분 ACK에 이어 ConquestSession의 같은 위치 입력·입력 초기화 및 상태 UI를 선택적 coordinator에 연결했다. 자세한 현재 상태는 [conquest-outbox.md](conquest-outbox.md)를 따른다. 아래의 '실제 큐 없음'은 이전 단계 기록이다. 검증된 서버 팀 응답을 앱 조립부가 전달하는 활성화 흐름은 아직 없고, 앱의 업로드는 계속 비활성이다. 로컬 팀 선택·기존 계정 조회만으로 수집기를 만들지 않는다. 기존 탐험/인증/추적 서비스 내부 변경은 없다.

2026-11-13 ConquestServerAccess의 팀 선택/기여 제출 동작과 조립부의 고정 RPC 매핑, 불변 제출 문자열/해시 및 엄격한 응답 parser를 추가했다. 위의 'RPC 포트 미노출'은 이전 단계 기록이다. 명세는 supabase/conquest/gateway-contract.md다. 전체210개 단위/Lint/APK, 격리 gateway14개·원장17개·근거24개·탐험 서버회귀가 통과했다. 아직 화면/추적 서비스에서 submit/selectTeam을 호출하지 않으며 실제 큐/ACK·서버 캐시는 없다. 설정의 계정 조회와 로컬 게임만 기존처럼 실행한다.
