# 차량 상태와 자동 기록

AAOS CarUxRestrictionsManager로 차량이 정한 운전자 조작 제한을 구독한다. GPS 속도 0을 주차로 간주하지 않는다. 운행·일시정차 제한 상태에서 자동 기록하고, 제한 해제 상태에서는 대기한다. 이 값은 기어 P 자체를 보증하지 않으며 지역/차량별 안전 정책을 따른다.

사용자가 설정에서 자동 기록을 켜면 위치 권한을 요청하고 위치 foreground service를 유지한다. 주차 상태에서도 알림과 서비스는 대기하며 운행 전환 시 기록한다. 강제 종료·재부팅 후에는 앱을 다시 열어야 한다. 수동 위치 테스트 모드는 유지한다. 차량 서비스 연결 실패/미확인 시 상세 UI는 열지 않고 자동 해금도 중단한다. 운행 -> 대기 전환 시 무효 정확도 표본으로 앱·서버 체류 연속성을 끊는다.

공통 보호 파일 영향: MainActivity는 상세 UI 안전 게이트와 설정 연결, Manifest는 android.car 라이브러리 선언, app/build.gradle.kts는 useLibrary(android.car)만 추가. 홈/점령 기능 구현과 공통 데이터 모델은 변경하지 않음.

공식 근거: https://source.android.com/docs/automotive/driver_distraction/consume
https://developer.android.com/reference/android/car/Car

실제 Pleos_Connect_2_1_0(emulator-5554)에는 android.car 공유 라이브러리가 없어 optional 선언으로 설치한다. 해당 이미지에서는 자동 주행 판정은 미지원이며 개발 빌드+에뮬레이터에서만 수동 테스트 화면을 허용한다. 실차·배포 빌드의 미확인 상태는 계속 잠근다. 지원 차량에서의 실제 상태 전환은 미검증이다. 차량 API를 GPS 속도 추정으로 대체하지 않는다.
