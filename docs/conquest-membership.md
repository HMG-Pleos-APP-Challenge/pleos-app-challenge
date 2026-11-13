# 점령 서버 소속 조회·팀 선택 연결

2026-11-14. 점령 전용 API 초안과 앱 연결 기록이다. 운영 배포·공동 Notion 계약 승인 기록이 아니다.

## 변경 이유와 범위

기존 select-team RPC를 재시작 복구에 쓰면 소속이 없는 계정에 팀을 생성할 수 있다. 따라서 읽기 전용 conquest_membership_v1을 분리했다. 기존 인증·탐험·프로필 구현과 공통 모델/Route는 유지한다. MainActivity는 기존 conquestAccountAccess 포트를 SDK 점령 세션에 주입하는 호출만 추가한다.

## 소속 조회 초안

public.conquest_membership_v1(p_partition text,p_catalog text)는 JWT auth.uid()에 해당하는 계정의 소속만 반환한다. UID·팀·클라이언트 시각 인자는 없다. 활성 world/catalog를 확인하고 memberships의 (user_id,partition) 키로 읽는다. STABLE 함수로 해당 조회문의 스냅샷을 사용하며 쓰기·소속 생성·가입은 없다. private 테이블을 직접 노출하지 않고 고정 search_path와 authenticated 실행 권한만 사용한다. 기존 팀 선택/제출 함수와 테이블 의미를 바꾸지 않는다.

응답은 protocol=1, userId, partition, catalogVersion, status, teamId, selectedUtcMs다. status=NO_TEAM이면 teamId와 selectedUtcMs는 명시적 JSON null이다. MEMBER이면 서버가 보관한 최초 팀·선택시각이다. 없는 필드·상태 불일치·다른 계정/출처/버전·음수/비정수 시각은 앱에서 거부한다.

## 앱 흐름

ConquestMembershipConnection은 existingAccount로 신규 가입 없이 계정을 확인하고, 예상 계정/세대에 묶인 membership RPC를 실행한다. NO_TEAM은 인증 적용 직전에 다시 계정을 확인하고 팀 선택 대기 상태로 남긴다. 이 단계에서 디스크 귀속/서버 큐를 만들거나 자동으로 첫 팀을 선택하지 않는다.

사용자가 팀을 선택했을 때만 기존 selectTeam을 호출한다. 다른 기기에서 먼저 팀을 선택했다면 TEAM_LOCKED 응답의 실제 팀과 원래 선택시각을 사용한다. MEMBER/유효한 선택 결과는 기존 outbox 준비 함수로 전달하고 withCurrentAccount에서 다시 계정·세대를 검사한 뒤 수집기를 연다. 로컬 기기의 팀·기여·소유 요약을 서버 소속으로 덮어쓰지 않는다. 이전 기기 기록은 서버 기여로 이관하지 않는다.

재연결은 이전 연결을 비활성화하고 worker를 종료한 뒤 새로 조회한다. 이전 연결의 늦은 응답은 적용하지 않는다. 수집기 교체 잠금 안에서도 현재 연결인지 검사하고, 준비가 끝난 뒤 다시 검사한다. 계정 확인에서 세대 변경·세션 부재가 발견되면 이전 연결의 선택 동작과 수집을 중지한다. 이미 전송 중인 서버 선택 요청은 취소 후에도 서버에서 끝날 수 있으므로 다음 조회로 실제 소속을 복구한다.

이 상태는 화면용 스냅샷이다. 표시된 소속을 현재 인증 권한의 증거로 사용하지 않으며 모든 실제 요청·저장·ACK를 기존 인증 경계로 다시 보호한다. 인증 적용 콜백에는 HTTP·suspend·UI 콜백을 넣지 않는다.

## 활성화 상태

ConquestServerRollout.membershipEnabled=false다. 키 존재만으로 활성화하지 않는다. 앱 조립부의 connectServer 기본 호출은 계정 조회·가입·소속 RPC·SQLite 귀속 생성을 실행하지 않는다. MOCK은 명시적으로 켜도 서버 연결을 만들지 않는다. 서버 소속 연결을 켜는 격리 시험에서도 앱의 업로드는 별도로 false다.

지도에는 활성 연결이 있을 때 소속 확인·팀 선택·오류·재확인 UI가 나타난다. 실제 앱은 현재 기존 로컬 게임과 서버 비활성 안내를 유지한다. 기존 운행 중 상세 조작 제한은 바꾸지 않는다.

운영 연결 전에 격리 Supabase의 실제 Auth/PostgREST/권한·두 계정/두 기기·동시 선택을 확인해야 한다. 이 채팅에서는 Supabase 연결 도구와 별도 시험 프로젝트가 확인되지 않았다. PGlite의 JWT subject/role 대역 및 기존 인증 경계의 모의 HTTP/단위 시험을 실제 원격 인증 성공으로 간주하지 않는다. 운영 DB를 시험 대상으로 대신 사용하지 않았다.

## 검증

작업 공간 tools/build-local.ps1과 저장소 node supabase/tests/conquest-gateway.mjs를 실행한다. gateway 시험은 소속 조회의 무변경·계정/출처 격리·최초 선택시각·무인증/anon/잘못된 카탈로그 차단 및 기존 제출 회귀를 포함한다. 앱 시험은 소속 없음·명시적 선택·기존 소속 복구·TEAM_LOCKED·세대 변경·잘못된 응답·지연 응답·취소·저장 실패·실제 세션 준비 및 기본 비활성 동작을 확인한다.

실제 최종 실행: 전체 단위256개 실패/오류0(신규 소속 연결11개·세션2개), lintDebug·assembleDebug 성공(기존 Warning5/Information1). 기존 인증 어댑터 시험에 membership의 고정 RPC/허용 인자/이전 세대 요청 차단도 추가했다. 격리 SQL gateway17개 모두 통과. 실제 운영 DB·Auth/PostgREST·두 기기·실차·UI 조작 검증은 하지 않았다.
