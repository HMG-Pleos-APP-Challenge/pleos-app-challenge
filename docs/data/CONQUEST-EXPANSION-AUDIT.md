# 남양읍 전체 도로 조사 — N1

2026-11-12. 이 자료는 전체 확대 준비용이며 앱의 현재 3개 물리 구간/6방향 카탈로그를 교체하지 않는다. 분류는 정책 검토안이지 승인된 게임 대상 목록이 아니다.

## 원본과 재사용 검증

- 친구의 `namyang-osm-roads.geojson` 및 `assets/exploration/namyang.boundary`를 수정 없이 사용했다. 탐험 내부 함수·세션·저장소를 호출하지 않는다.
- 동일 원본: https://download.geofabrik.de/asia/south-korea-261108.osm.pbf
- PBF SHA-256: `2539f6a674b9166acee836af1815558ab6264181372bbf1dbadafebb0accaa97`.
- 기존 643개 way의 ID·버전·좌표열을 모두 대조했다. 누락/버전 차이/좌표 차이 0건.
- 새 원본 `namyang-conquest-topology.json`은 노드 ID와 좌표, way 버전·노드열·방향·접근 제한·입체교차 관련 태그를 보존한다. 경계 주변 250m에 닿는 way를 전체 길이로 보존하며, 행정 경계에서 가짜 교차로를 만들지 않는다.
- 원본·경계·탐험 원본·산출물 해시는 `namyang-conquest-audit.json`에 기록한다. 줄바꿈 차이도 경계 해시에 영향을 줄 수 있으므로 재현 때 동일 바이트 원본을 사용한다.

## 실제 집계

경계에 닿는 highway way 1,772개, 경계 밖 연결 검토 보조 way 367개. 이는 물리 도로 수나 점령 방향 구간 수가 아니다.

| 1차 검토 분류 | way 수 |
| --- | ---: |
| 일반 도로 후보 — 위상·구간 검증 필요 | 602 |
| 자동차 도로 분류 밖: 보행로/공사/농로 등 | 342 |
| 명시적 접근 제한 | 19 |
| 생활·진입도로: living_street/service 별도 검토 | 739 |
| 고속도로/연결로 별도 검토 | 35 |
| 회전교차로 별도 검토 | 9 |
| 터널 별도 검토 | 26 |
| 합계 | 1,772 |

분류는 우선순위에 따른 상호 배타 집계다. 예를 들어 제한된 service는 생활도로 보류가 아니라 접근 제한에 집계될 수 있다. 제외 후보 합계 361, 보류 809, 일반 후보 602다. 전체 85개 way가 경계를 가로지르며, 일반 후보 중 41개가 해당한다. 나머지 일반 후보 561개는 선형 전체가 경계에 포함된다. 경계 횡단 여부는 위 표와 중복되는 별도 속성이다.

기존 643개와 일반 후보 602개의 차이는 친구 데이터의 누락을 뜻하지 않는다. 원본 추출 정책과 새 검토 정책의 차이다. 친구는 일반 자동차 도로를 추출했고, 이번 조사는 경계에 닿는 모든 highway를 먼저 기록한 뒤 터널·회전교차로 등을 별도로 보류했다.

## 다음 단계의 정책 제안

1. 사용자가 1차는 일반 도로부터 적용하고 생활·진입도로는 후속 검토하기로 확정했다. 일반 후보 602개에서 실제 교차로 사이 구간을 검증한다. 모든 후보의 최종 채택을 승인한 것은 아니다.
2. 경계 횡단 way 전체를 버리지 않고 실제 양 끝 교차로 및 선형이 경계 안에 있는 하위 구간이 있는지 조사한다. 경계로 잘린 점은 진입/출구로 사용하지 않는다.
3. 막다른 길·짧은 구간은 N2의 노드 연결 분석으로 실제 구간 개수와 길이를 산출한 뒤 처리 정책을 확정한다. way 전체 길이를 구간 길이라고 보고하지 않는다.
4. 일방통행은 원본 태그와 도로 종류를 확인해 합법 방향만 생성한다. 태그 부재를 모든 도로의 양방향 허용으로 취급하지 않는다.

## 재현

개발용 Python 환경에 `osmium==4.3.0`, `shapely==2.1.2`를 설치한다. Android Gradle 의존성은 아니다. 4.1.1은 이 Windows 환경에서 처리 후 프로세스 종료가 멈췄다. [4.3.0 공식 변경 기록](https://github.com/osmcode/pyosmium/releases/tag/v4.3.0)에 Windows 종료 문제 수정이 명시되어 있다.

저장소에서 실행:

```text
python tools/audit_conquest_roads.py --pbf <동일 원본 PBF 경로> --boundary app/src/main/assets/exploration/namyang.boundary --roads docs/data/namyang-osm-roads.geojson --output docs/data/namyang-conquest-topology.json --report docs/data/namyang-conquest-audit.json
python -m unittest discover -s tools -p test_audit_conquest_roads.py -v
```

PBF 해시 또는 기존 geometry/버전이 다르면 중단한다. 원본으로 산출물을 덮어쓰는 인자도 거부한다. 작은 fixture에서 원본 일치/불일치, 노드·방향 보존, 영역 밖 제거, 접근 제한 우선순위, 미지원 조건 보류를 테스트한다.

## 한계와 출처

실제 검증: 도구 테스트 5개 통과 및 프로세스 종료 코드0, 전체 원본 추출 종료 코드0. 4.3.0 재생성의 topology SHA-256은 최초 산출과 동일한 `d344cb3dae63573bbe5eb7d0e7de1cbe81bd0cdab6a12187ed63707115bce757`이다. 총2,139way/20,268node에 중복 ID·누락 노드 참조가 없고 분류 합계와 보고서 해시가 일치했다. Android 소스/assets 변경이 없어 이번 작업에서 Gradle 빌드·앱 테스트는 재실행하지 않았다.

이번 원본은 노드 위치와 way 태그를 보존한다. 노드 자체의 barrier/access 태그 및 회전 제한 relation은 포함하지 않으므로 완성된 자동차 경로망이나 내비게이션 자료가 아니다. N2에서 선정 구간에 필요한 추가 자료 여부를 검토한다. 공유 노드가 없는 기하학적 교차를 교차로로 취급하지 않는다. 전체 길이 way 보존/250m 보조 범위만으로 모든 지역 밖 연결 그래프가 완전하다고 주장하지 않는다.

© OpenStreetMap contributors, ODbL 1.0. `CONQUEST-DATA-NOTICE.md`, `ROAD-DATA-NOTICE.md`, `LICENSE-CONQUEST-OSM.txt`, `LICENSE-OSM-ODbL-1.0.txt` 고지를 유지한다. 경계는 SGIS / vuski/admdongkor이며 `LICENSE-DATA.txt`를 따른다. 편집자 식별 메타데이터를 추가하지 않았다. 원본 선정·태그 보존·검토 분류를 변경 내역으로 공개한다.

실제 차량 통행 규정·GPS 정확도·전체 지도 화면·기록 전환은 아직 검증하지 않았다. 이번 도구/데이터 작업으로 앱의 기존 빌드 검증을 새로 수행한 것으로 표시하지 않는다.
