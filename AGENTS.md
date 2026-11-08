# 공동 개발 기준

- 원본 계약: https://app.notion.com/p/40f99682f1f081528152dfba45f9c99e
- 운영 규칙: https://app.notion.com/p/40299682f1f081f08eabc6ced98314be
- 작업 DB: https://app.notion.com/p/556be41b276a4af780e9101bfce86e85
- 시작 전 최신 Notion 문서와 해당 브랜치 코드를 확인한다. 접근하지 못한 문서를 확인했다고 말하지 않는다.
- 계약 v0.1과 namespace는 초기 PR의 공동 검토 대상이다. 공동 승인 대기를 임의로 완료 처리하지 않는다.
- 최: `feature/conquest`, `feature/home`. 유: `feature/exploration`, `feature/profile`.
- `core/**`, `navigation/**`, `app/build.gradle.kts`, Manifest는 공통 보호 파일이다. 영향과 상대 공유 사항을 PR에 기록한다.
- 공통 모델·인터페이스는 한 곳에서 정의한다. 필드·route 변경은 양측 합의 후 문서와 코드를 함께 수정한다.
- 단일 `app` 모듈 유지. 새 모듈·DI 프레임워크·DB·서버·지도 SDK를 임의로 추가하지 않는다.
- 기능 브랜치에서 작은 단위로 커밋하고 PR → 상대 리뷰 → 빌드/관련 테스트 확인 → main 병합을 지킨다.
- 초기화: `codex/bootstrap-android`. 이후 권장: `feature/conquest-home`, `feature/exploration-profile`.
- 운영 규칙의 `(로직변경x)` 접두어는 실행 로직이 바뀌지 않는 커밋에만 사용한다. 초기 앱의 실행 코드 추가에는 사용하지 않는다.
- 완료·검증은 실제 실행 결과로만 표기한다. Mock·미구현·제안·실제 SDK 연동을 구분한다.
- 유 담당은 기능별 TASK 하나로 관리한다. 제목에 담당자·번호를 붙이지 않고 MVP 할 일과 완료 기준을 쉬운 말로 쓴다.
