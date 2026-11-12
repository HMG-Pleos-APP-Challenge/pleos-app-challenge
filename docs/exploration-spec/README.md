# 지도 탐험 요구사항과 시뮬레이션

21개 항목의 기능 구현과 검증 범위는 requirements.csv로 관리한다. 자동 판정 통과는 연결한 테스트의 범위를 뜻한다. 화면 전체·실차·실제 이메일 발송까지 완료됐다는 의미로 사용하지 않는다.

## 적용한 정책

- 신규 설치는 새 탐험 시작과 기존 계정 복원을 먼저 선택한다. 선택 대기를 설치 ID 생성 전에 저장한다. 선택 완료 후 위치 권한을 확인하고 자동 기록한다. 기존 설치는 계속 사용한다.
- 탐험 기록은 화면과 독립적이다. 위치 수신이 30초 넘게 없거나 서비스가 종료되면 원인과 복구 경로를 표시한다.
- 지도는 기기 대기 기록에 사선을, 확정 구역에 점을 사용한다. 상태 문구와 전체 구역 목록도 제공한다.
- 공유 위치 전달의 예외 격리 구현과 회귀 테스트는 origin/choi의 3fbee61에 있는 코드를 재사용했다. 점령 소비자와 점령 규칙은 기존 구현을 사용한다.

## 근거

공통 계약 원문은 브라우저로 열람했다. 공동 승인 대기 상태이며 담당 범위, 안정 ID, UTC 밀리초, 공통 파일 보호와 양측 합의 규칙을 따른다. 브랜치는 사용자의 지시에 따라 기존 로컬 feature/exploration-continuous-recording을 유지한다.

- [공통 계약 v0.1](https://app.notion.com/p/40f99682f1f081528152dfba45f9c99e)
- [11월 5일 회의록](https://app.notion.com/p/40f99682f1f0800c8585c6274da5e64c)의 지역 발견·부분 해금·개인 누적 방향은 [지역 발견 검토](../exploration-stage1-review.md)가 인용한 결정에 근거한다. 회의록의 최종 확정 부분과 직접 대조는 추가 확인 대상이다.
- 랜드마크·서버·계정은 초기 계약의 MVP 이후 사용자 요청으로 추가했다.
- 세부 근거는 [도로 구역](../exploration-stage2-review.md), [명소](../exploration-stage3-review.md), [기록 서비스](../background-tracking-tts-review.md), [계정](../account-recovery.md)을 참고한다.

## JVM 시뮬레이션

저장소 루트에서 실행한다.

```powershell
pwsh -File docs/exploration-spec/Run-ExplorationSimulation.ps1
```

모든 요구사항 ID에 대응하는 JVM 검증 메서드를 실행하고 결과와 커밋, 미커밋 변경 여부를 남긴다. 상세 결과는 app/build/reports/tests/testDebugUnitTest/index.html에 있다.

## 장치 시뮬레이션

등록된 Pleos 에뮬레이터를 부팅한 후 실행한다. 에뮬레이터 ID는 adb devices로 확인한다. SDK 옵션은 에뮬레이터의 시험 GPS를 사용한다.

```powershell
pwsh -File docs/exploration-spec/Run-ExplorationSimulation.ps1 -AndroidSerial emulator-5554 -WithSdk
```

장치 시뮬레이션은 별도 임시 파일과 시험 계정 응답을 사용해 최초 발견, 4개 구역과 140 XP, 2분 체류, 재시작, 35개 구역 저장, 오프라인에서 일부·전체 확정, 계정 선택 대기, 모의 이메일 인증·비밀번호·암호화 세션 복원을 검사한다. 실제 이메일은 발송하지 않는다. 서버 확정 전환 검사는 서버 설정이 있는 빌드에서 실행한다.

SDK 시뮬레이션은 별도 시험 사용자로 실제 위치 SDK 콜백과 서비스 저장·복원·TTS를 검증한다. Mock-location AppOp는 실행 전 값을 보존하고 finally에서 복구한다. SDK 시험의 표준 출력에서 FAIL 또는 FAILED와 성공 종료 문자열을 모두 검사한다.

## 완료 확인과 수동 검증

최종 결과는 app/build/reports/exploration-simulation.csv에 있다. Device가 미실행이면 장치 검증을 실행한 결과가 아니다. 장치 로그와 SDK 로그도 같은 보고서 폴더에 남는다. 누락·실패·건너뛴 JVM 테스트, 실패한 장치 검사는 실패 종료 코드를 반환한다.

별도 확인 대상은 좁은 화면의 실제 가독성·31번째 구역 선택, 첫 실행 계정 화면, 권한 설정 왕복, 지원 차량의 주행 중 조작 제한, 실제 메일 인증과 두 기기 복원이다. 각 증적에 요구사항 ID, 커밋, 장치, 입력 출처, 기대·실제 결과와 실행시각을 기록한다. 토큰과 비밀번호는 증적에 넣지 않는다.

origin/choi 전체를 현재 브랜치에 병합한 결과는 별도의 통합 검증 대상이다.
