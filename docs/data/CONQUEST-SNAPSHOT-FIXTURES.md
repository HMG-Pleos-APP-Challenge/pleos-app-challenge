# 서버 스냅샷 합성 fixture

conquest-server-snapshots.json은 supabase/tests/conquest-settlement.mjs가 격리 PGlite에서 생성한 initial/full/gateway 결과를 고정한 시험 자료다. 고정 UUID·시각·득점은 합성이며 키·토큰·실제 사용자 기록/이동 원문이 아니다. full은 기존 남양읍 카탈로그의1,453방향 ID를 사용한다. 기존 ROAD-DATA-NOTICE.md와 OSM 라이선스 고지를 유지한다.

재현은 작업 공간 tools/build-local.ps1 다음 저장소 node supabase/tests/conquest-settlement.mjs다. SQL 시험은 ignored app/build/conquest-proof/server-snapshots.json을 생성하고 이 파일과 모든 JSON 값을 비교한다. 계약을 의도적으로 변경할 때만 그 산출물과 변경 사유를 검토한 뒤 명시적으로 이 fixture를 갱신한다. 시험이 Git 파일을 자동 덮어쓰지 않는다.
