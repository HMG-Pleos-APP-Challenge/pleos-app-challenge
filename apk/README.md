# 구랩 제출 APK 검증 기록

## 파일

- 팀: 구랩 / 앱: 대현대여지도
- [구랩_대현대여지도_release.apk](구랩_대현대여지도_release.apk)
- 패키지: com.daehyundai.map / versionName: 0.1.0 / versionCode: 1
- 크기: 38,242,103 bytes (약 36.47 MiB)
- SHA-256: `8785516be77162395175957d4649c6abefe1ed241e342a04993c77e7104a419f`
- 앱 소스 기준: `14ddc20f40d3b8da6c082737505825622bc613b8` (main)
- 포함된 choi 원격 기준: `ac1e6da` / 충돌 없이 병합한 커밋: `ece0e3e`
- 산출물 문서 커밋에서는 앱 소스를 변경하지 않았습니다.

## 수행한 검증

| 항목 | 결과 |
| --- | --- |
| JVM 단위 테스트 | 271개 통과, 실패·오류 0 |
| debug / release Lint | 모두 통과 |
| assembleRelease | 성공 |
| zipalign / apksigner verify | 정렬·서명 검증 통과, APK Signature Scheme v3 |
| 최종 APK 속성 | release, DEBUGGABLE 플래그 없음, min SDK 28 / target SDK 34 |
| 기기 설치 | 별도 초기화 시험 AVD에 최종 서명 APK 설치 성공 |
| 최초 실행·홈 | 새 탐험 시작 → 홈 표시 통과 |
| 탐험 화면 | 지역 진입 전 잠금 안내 표시 통과 |
| 점령 화면 | 남양읍 도로 점령 표시 통과 |
| 프로필 화면 | 명소 도감 표시 및 화면 캡처 통과 |
| 저장소 파일 동일성 | 기기에 설치한 ASCII 경로 복사본과 저장소 APK의 SHA-256 일치 |

실행 환경은 Pleos Connect 2.1.0 / Android 14(API 34) / x86_64 / 2560×1440 / 160dpi / RAM 4096MB입니다.
다른 개발 기기와 데이터를 보존하기 위해 별도의 AVD를 만들었으며, 운영 서버에 요청하지 않도록 Wi-Fi와 모바일 데이터를 끄고 검사했습니다.
위치·알림·Pleos FusedLocation 권한을 허용한 상태에서 Pleos 운전자 사용자 10으로 검사했습니다. 첫 실행 권한 팝업이 표시되는 것도 확인했습니다.

## 화면 검사 재현

1. 임시 Pleos AVD에 위 release APK를 설치합니다. 기존 사용자 데이터가 있는 기기의 앱을 초기화하지 마세요.
2. 위치·알림·Pleos FusedLocation 권한을 허용하고 첫 실행 전 상태로 준비합니다.
3. 아래 설정으로 검사 APK를 만듭니다.

~~~powershell
.\gradlew.bat -I tools/submission-probe.init.gradle assembleDebugAndroidTest
~~~

4. 생성된 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk를 **대상 release APK와 같은 키로 서명**한 뒤 시험 기기에 설치합니다. 기본 디버그 서명 그대로는 대상 release를 검사할 수 없습니다.
5. adb shell am get-current-user로 실제 운전자 사용자 ID를 확인하고, 해당 사용자로 실행합니다. 아래 예시의 기기와 사용자 ID는 자신의 시험 환경에 맞게 바꿉니다.

~~~shell
adb -s emulator-5556 shell am instrument --user 10 -w com.daehyundai.map.test/com.daehyundai.map.SubmissionReleaseProbe
~~~

성공하면 `PASS: non-debuggable release installed; first launch, home, exploration lock, conquest and profile navigated`가 출력됩니다.
검사 도구는 제출 앱에 포함되지 않습니다. 서명키와 비밀번호는 저장소 밖의 로컬에 별도로 보관합니다.

## 검증 범위의 한계

- 화면 이동 검사는 오프라인 동작 확인입니다. 실차 주행·실제 계정 복원·운영 서버 동기화의 완료를 의미하지 않습니다.
- 점령 서버 연결 코드는 병합했으나 일반 앱의 서버 rollout은 비활성 상태이며 운영 DB를 배포하거나 변경하지 않았습니다.
- release SDK 정확도 판정은 보수적입니다. 탐험 해금·체류 조건의 화면 시연은 루트 README의 모의 재생 절차를 이용합니다.
- 같은 패키지의 기존 개발 APK와 서명이 다르면 별도의 새 AVD에서 설치합니다.