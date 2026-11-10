# 셀 점령 v1 — 공동 작업자 연결 기준

공통 단위: 탐험과 같은 grid500-v2 cellId. 기존 road_segment_id 테이블은 보존하고 셀을 도로 구간으로 가장하지 않는다. 기존 core 인터페이스와 ConquestMapScreen은 유지하고 새 CellConquestScreen을 route에 연결했다.

기본 규칙: 자유 선택 3팀(A/B/C), 한국 시간 월요일부터 주간 집계, 사용자·셀·날짜·실제/테스트별 하루 1회 기여. 서버가 위치 정확도·도로 근접·3초·8m·지역 발견을 검증한 경우만 기여. 앱에서 점수를 직접 쓰지 않는다. 같은 주 기여 후 팀 변경 금지, 최초 팀 선택 전 위치 표본은 소급하지 않음. 최다 기여 팀이 점령하며 동률이면 기존 소유 팀 유지(기존 팀이 최다 득표군에 없으면 팀 ID 순). 다음 주는 새 시즌으로 집계된다. 이는 이번 구현의 기본 규칙이며 제품 정책 변경 시 별도 버전으로 수정한다.

API: choose_conquest_team_v1(p_team), conquest_snapshot_v1(p_partition). sync_exploration_v1 내부 검증이 성공하면 비공개 record_cell_conquest_v1가 하루 기여를 원자적으로 반영한다. 상세 테이블 직접 접근은 금지. 외부 공개 결과는 팀별 점수와 셀 소유 팀뿐이며 다른 사용자의 위치/ID를 노출하지 않는다. 행 잠금과 주간 advisory lock으로 동시 기여를 직렬화한다.

원격 적용: Supabase SQL Editor에서 conquest-upgrade.sql을 한 번 실행. 이미 설치된 migration 001~003을 재실행하지 않는다. 새 설치는 prepare_supabase_editor.py로 생성하는 전체 schema를 사용한다. 테스트·실제 경쟁은 partition으로 분리된다.

담당 분리 후 상태: 점령 서버 배포는 보류. 현재 route는 공동 작업자의 기존 ConquestMapScreen을 유지한다. CellConquestScreen과 conquest-upgrade.sql은 전달용 구현이며, 최 담당이 규칙 검토·서버 적용 후 route에 연결한다. 탐험/프로필 API는 이 추가 SQL 없이도 동작한다. 실제 원격 점령은 검증 완료로 표시하지 않는다.
