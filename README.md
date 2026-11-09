# 대현대여지도

현대차 Pleos 앱 공모전의 2인 공동 개발용 Android 초기 프로젝트입니다.
현재는 **네 화면 이동과 고정 모의 데이터 연결을 확인하는 개발용 뼈대**입니다. 실제 지도, 위치 SDK, 탐험·점령 규칙, 영구 XP 저장소는 아직 구현하지 않았습니다.

## 공통 문서

- [공통 계약 v0.1](https://app.notion.com/p/40f99682f1f081528152dfba45f9c99e)
- [협업 운영 규칙](https://app.notion.com/p/40299682f1f081f08eabc6ced98314be)
- [개발 환경](https://app.notion.com/p/40299682f1f081ff99d8d7827581e66b)
- [Task & Issue DB](https://app.notion.com/p/556be41b276a4af780e9101bfce86e85)

계약과 namespace는 초기 PR에서 두 개발자가 검토할 제안이며, 공동 승인이 끝났다는 뜻은 아닙니다.

## 실행

1. Android Studio에서 이 저장소 폴더를 엽니다.
2. Android SDK Platform 34, Build Tools 34.0.0을 설치합니다.
3. **Gradle JDK는 17을 선택합니다.** 이 조합에 Studio 내장 JDK 25를 사용하지 않습니다.
4. SDK 경로는 Android Studio가 생성하는 `local.properties`에 둡니다. 이 파일은 커밋하지 않습니다.
5. Pleos Connect 2.1.0 가상 기기를 RAM 4GB, Cold boot로 실행하고 `app`을 Run 합니다.

Windows 검증:

```powershell
$env:JAVA_HOME = '설치한 JDK 17 경로'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS/Linux는 JDK 17을 선택하고 `./gradlew testDebugUnitTest lintDebug assembleDebug`를 실행합니다.

모의 주행 버튼은 3개 가상 도로·2개 가상 지역의 이벤트를 전달합니다. 실제 지도를 나타내지 않습니다. XP는 고정 미리보기 값이고 모의 기록은 앱 종료 시 사라집니다. 실제 기능의 저장·복원은 각 MVP TASK에서 구현합니다. 실주행 사용·주차 판정·실시간 멀티플레이는 지원하지 않습니다.

## 공동 빌드 기준

| 항목 | 초기 기준 |
|---|---|
| 앱 모듈 | `app` 하나 |
| namespace / applicationId | `com.daehyundai.map` (공동 확인 대상) |
| Java / Gradle Wrapper | 17 / 8.7 (공식 SHA-256 검증) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin / Compose Compiler | 1.9.25 / 1.5.15 |
| Compose BOM / Activity | 2024.06.00 / 1.9.0 |
| min / compile / target SDK | 28 / 34 / 34 |
| Pleos Emulator | Connect 2.1.0 / API 34 |

현재 설치된 API 34 환경에서 검증할 최소 기준이며 최신 버전이라는 뜻은 아닙니다. 실제 Pleos 라이브러리를 연결할 때 요구 버전을 확인하고 함께 갱신합니다. 스토어 배포 요건 충족을 의미하지 않습니다.

근거: [Pleos 환경](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator), [AGP 호환표](https://developer.android.com/build/releases/agp-8-5-0-release-notes), [Compose/Kotlin 호환표](https://developer.android.com/jetpack/androidx/releases/compose-kotlin).

## 역할과 폴더

`app/src/main/java/com/daehyundai/map/` 아래에 다음 폴더를 둡니다.

- `core/model`, `core/contract`: 공동 데이터와 호출 규칙
- `core/location`, `core/data`: 모의 입력과 초기 연결 확인용 구현
- `feature/conquest`, `feature/home`: 최 담당
- `feature/exploration`, `feature/profile`: 유 담당
- `navigation`: 공동 route `home`, `conquest/map`, `exploration/map`, `profile`

홈·프로필은 요약값으로 데이터를 받으며 다른 기능의 내부 저장소를 조회하지 않습니다. 팀 수·점령 임계값·36시간 시작점·실제 탐험률 단위·레벨 곡선은 초기 코드에서 확정하지 않습니다.

## 협업

기능 브랜치에서 Commit → PR → 상대 리뷰 → 빌드/테스트 확인 → main 병합을 지킵니다. 초기 프로젝트도 별도 브랜치와 PR로 검토하며 자동 병합하지 않습니다. 공통 파일 변경은 PR에 영향과 공유 내용을 적습니다. API 키·로그인 정보·서명키는 커밋하지 않습니다.

CI는 단위 테스트·Lint·APK 빌드를 수행합니다. Pleos Emulator 구동 검증은 별도이며 실행한 사람만 결과를 기록합니다.
