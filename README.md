# 대현대여지도

팀 **구랩**

운전한 지역을 나만의 탐험 지도로 기록하고, 도로를 방향별로 완주하며 팀과 함께 점령하는 Pleos Connect 차량용 앱입니다.

## 목차

- [APK](#apk)
- [실행 방법](#실행-방법)
  - [에뮬레이터 기능 시연](#에뮬레이터-기능-시연)
- [현재 구현 범위](#현재-구현-범위)
- [테스트 환경 및 필요한 설정](#테스트-환경-및-필요한-설정)
  - [APK 실행 환경](#apk-실행-환경)
  - [소스 빌드](#소스-빌드)
- [검증과 개발 문서](#검증과-개발-문서)


## APK

- **서명된 release APK** — [구랩_대현대여지도_release.apk](apk/구랩_대현대여지도_release.apk)
- 패키지 com.daehyundai.map · 버전 0.1.0 · 최소 Android 9(API 28) · 대상 API 34
- 저장소 main의 소스에서 빌드했습니다. 파일 무결성과 실제 검증 내용은 [APK 검증 기록](apk/README.md)을 확인해 주세요.

## 실행 방법

1. Pleos Connect 2.1.0 에뮬레이터를 실행합니다.
2. 위 APK를 내려받아 에뮬레이터 창에 끌어다 놓거나 아래 명령으로 설치합니다.
3. 앱 목록에서 **대현대여지도**를 실행합니다. 첫 화면에서 **새 탐험 시작** 또는 **기존 계정 복원**을 선택합니다.
4. 위치·알림 권한 요청을 확인합니다. 탐험 기록은 화면과 독립적인 서비스에서 처리합니다.
5. 홈의 **탐험하기**, **점령 보기**, **내 프로필**에서 각 기능을 확인합니다.

~~~shell
adb install "구랩_대현대여지도_release.apk"
adb shell am start -n com.daehyundai.map/.MainActivity
~~~

제출 APK는 기존 개발용 APK와 서명이 다릅니다. 서명 불일치가 발생하면 별도의 새 AVD에서 설치해 주세요.

### 에뮬레이터 기능 시연

검증한 Pleos 2.1.0 에뮬레이터에서는 release APK에도 시연 도구를 제공합니다.

1. **내 프로필 → 설정 → 개발자 도구**로 이동합니다.
2. **모의 재생 기록**을 선택합니다.
3. **지역 진입 재생**으로 지역 발견을, **도로 이동 재생 · 셀 해금**으로 지나간 4개 구역의 해금을 확인합니다. 반복 재생 시 중복 보상을 지급하지 않습니다.
4. **짧은 방문 재생**, **이탈 후 복귀 재생**, **2분 모의 체류 재생**으로 명소 체류 조건을 비교합니다. 입력 시각을 재생하므로 2분을 실제로 기다리는 시연은 아닙니다.
5. 점령 화면의 경로 시연과 정산 시연으로 방향별 완주·기여·소유팀 변화를 확인합니다. 통합 시연은 화면에 표시하며 메모리 기록으로 처리합니다.

차량 상태 API가 없는 검증 에뮬레이터에서도 지도·시연을 볼 수 있도록 처리했습니다. 실제 차량은 차량의 조작 제한을 따릅니다.

## 현재 구현 범위

- **지도 탐험** — 남양읍 실제 행정경계, 500m 도로 기반 구역 243개, 최초 지역 발견, 부분 해금, 명소 연속 체류 도감, 경험치·레벨
- **도로 점령** — 남양읍 일반 도로 801개 물리 구간·1,453방향, 방향별 완주, 같은 계정·방향·KST 반일 중복 제한, 팀 선택, 직전 36시간을 기준으로 한 낮 12시 정산, 동점·만료 처리
- **프로필과 저장** — 누적 진행도, 탐험 기록 보존, 게스트 이메일 연결·계정 복원, 기존 인증 재사용 경계
- **동기화** — 탐험 서버 확정 기록과 기기의 전송 대기를 구별합니다. 점령 계정 귀속·큐·기여·정산·스냅샷 코드는 포함돼 있으며 제출 앱의 일반 점령 서버 rollout은 비활성 상태입니다.

지도는 번들된 경계·도로 데이터를 직접 그립니다. 탐험률은 수집한 도로 카탈로그의 대상 구역으로 계산합니다.

## 테스트 환경 및 필요한 설정

### APK 실행 환경

| 항목 | 개발·검증 환경 |
| --- | --- |
| Pleos Connect 시스템 이미지 | **2.1.0** |
| Android | **14 / API 34**, x86_64 |
| 화면 규격 | **2560 × 1440 px**, 가로 화면 |
| 화면 밀도 | **160 dpi** |
| 가상 기기 RAM | **4096 MB** |
| 실행 권한 | 위치, Pleos 위치 서비스, 알림 |
| 네트워크 | 오프라인 모의 시연 가능. 이메일·탐험 서버 동기화에는 인터넷 필요 |

실차 주행, 실제 이메일을 통한 두 기기 복원, 모든 화면 크기에서의 사용성은 추가 검증 대상입니다.

### 소스 빌드

| 항목 | 버전 |
| --- | --- |
| JDK / Gradle Wrapper | 17 / 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin / Compose Compiler | 1.9.25 / 1.5.15 |
| compile / target SDK | 34 / 34 |
| Android Build Tools | 34.0.0 |
| Pleos FusedLocation / TTS | 1.1.3 / 3.3.1 |

Android Studio에서 저장소를 열고 JDK 17 및 SDK 34를 선택합니다. SDK 경로는 로컬 local.properties에 설정합니다. 서버 연결이 필요한 개발 빌드는 다음 두 속성도 로컬에서 설정합니다.

~~~properties
sdk.dir=로컬 Android SDK 경로
supabase.url=사용할 Supabase 프로젝트 URL
supabase.publishableKey=해당 프로젝트의 공개 클라이언트 키
~~~

서명키·비밀번호·서버 관리자 키는 저장소에 포함하지 않습니다. 공개 클라이언트 키만 앱 빌드 설정으로 사용합니다. 서버 설정 없이도 번들 지도와 모의 시연을 개발할 수 있습니다.

~~~powershell
# Windows
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleRelease
~~~

~~~shell
# macOS / Linux
./gradlew testDebugUnitTest lintDebug lintRelease assembleRelease
~~~

assembleRelease 기본 결과는 app/build/outputs/apk/release/app-release-unsigned.apk입니다. 제공 APK는 정렬·서명을 마친 파일입니다.

## 검증과 개발 문서

- JVM 회귀 테스트, debug/release Lint, release APK 빌드와 서명 검사
- 별도 Pleos 에뮬레이터의 최종 release APK 설치·화면 이동 결과는 [검증 기록](apk/README.md)에 기록합니다.
- [탐험 요구사항·개발자 시뮬레이션](docs/exploration-spec/README.md)
- [인증 호출·전환 세대·잠금 계약](docs/shared-auth-boundary.md)
- [탐험·점령 통합 검토](docs/integration-exploration-choi-review.md)
- [점령 서버 적용 계획](supabase/conquest/release-guide.md)
- [이전 단계별 개발 기록](docs/integration-review.md)
- [도로 데이터](docs/data/ROAD-DATA-NOTICE.md) · [명소 데이터](docs/data/LANDMARK-DATA-NOTICE.md) · [점령 데이터](docs/data/CONQUEST-DATA-NOTICE.md)
