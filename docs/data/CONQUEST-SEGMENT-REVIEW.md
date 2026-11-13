# 남양읍 교차로 구간 분석 — N2 검토 결과

2026-11-12. 일반 도로부터 적용하고 생활·진입도로는 후속 검토한다는 사용자 결정을 따른다. 사용자의 후속 '고고'로 경계 횡단·교차로 아닌 끝점·50m 미만·닫힌 순환 구간을 우선 보류하는 정책도 승인됐다. `namyang-conquest-full-v1.segments`는 이 범위의 **오프라인 카탈로그**이며 앱 카탈로그·기존 기록은 변경하지 않았다. 이는 개인 점령 범위 승인으로, 공통 Notion 계약 전체 승인이나 현장 통행 규정 검증을 뜻하지 않는다.

## 분할 방법

`tools/analyze_conquest_segments.py`는 N1 topology의 공유 노드로 연결을 판단한다. 일반 후보 도로의 중간 원본 way 경계가 이웃2개/후보edge2개라면 그대로 이어간다. 서로 다른 node ID의 선이 화면에서 교차하는 것은 교차로로 보지 않는다.

자동차 도로의 실제 가지를 보존하기 위해 점령 후보가 아닌 service/생활도로 등의 연결도 교차로 차수 계산에 사용한다. 이는 해당 가지를 점령 대상으로 승인하거나 그 방향의 회전을 허용한다는 의미가 아니다. 보행로·area는 교차로 차수에서 제외한다. 노드 barrier/access 및 회전 제한 relation 미포함의 N1 한계는 계속 유지한다.

후보 그래프의 각 edge는 한 번만 소비한다. 닫힌 순환도 무한 반복 없이 보류한다. 원본edge 중복·누락 노드·방향 충돌을 검사한다. 양 끝점이 실제 분기점인지와 **구간 선형 전체**가 경계에 포함되는지 확인한다. 행정 경계를 잘라 새로운 끝점을 만들지 않는다.

여러 way를 잇는 경우 모든 edge에서 허용하는 진행 방향의 교집합을 사용한다. 단일 way 구간은 기존 way/version/from/to 식별자를 유지하고, 여러 way로 구성되면 정규화 노드열·way버전·edge순서의 SHA-256으로 식별한다. 입력 목록 순서를 바꿔도 결과는 동일하다. 이름 없는 도로는 표시명만 부여하며 저장 ID에 표시명을 사용하지 않는다.

## 결과

| 항목 | 수 |
| --- | ---: |
| 경계에 닿는 분할 물리 구간 | 1,292 |
| 제안된 보류 조건에 걸리지 않는 물리 구간 | 801 |
| 위 후보의 방향별 구간 | 1,453 |
| 보류 조건 하나 이상인 물리 구간 | 491 |
| 후보 중 일방통행 물리 구간 | 149 |
| 후보 중 여러 way를 연결한 물리 구간 | 45 |

후보 길이: 약50.15m~1,632.97m. 시제품의100~450m 제한은 사용하지 않았다. 50m는 현재 DirectedRoad가 요구하는 최소 길이이며 실차 정확도 검증을 뜻하지 않는다.

보류 사유별 수는 **서로 중복**된다: 경계 횡단44, 교차로 아닌 끝점162, 50m 미만339, 닫힌 순환1. 합산해 보류 구간 수라고 보고하지 않는다. 교차로 아닌 끝점에는 막다른 길뿐 아니라 일반 후보 정책이 끝나는 지점도 있으므로 모두 막다른 길이라고 부르지 않는다.

`namyang-conquest-segment-review.json`은 모든 구간의 원본edge·노드열·길이·끝점차수·보류사유·방향좌표를 제공한다. 기존 앱의6방향 모두 ID와 전체 좌표열이 동일하며 보류 조건에도 걸리지 않는다. 이 결과는 N3에서 기존 기록을 보존할 근거이며, 기록 이관을 이미 완료했다는 뜻은 아니다.

## 재현과 검증

개발용 shapely2.1.2 환경에서 저장소 기준:

```text
python tools/analyze_conquest_segments.py --source docs/data/namyang-conquest-topology.json --boundary app/src/main/assets/exploration/namyang.boundary --legacy app/src/main/assets/conquest/namyang.segments --output docs/data/namyang-conquest-segment-review.json
python -m unittest discover -s tools -p test_analyze_conquest_segments.py -v
```

위 생성 명령에 `--candidate-catalog docs/data/namyang-conquest-full-v1.segments`를 추가하면 승인된 보류 정책을 적용한 기존 V1 reader 형식의 오프라인 카탈로그가 생성된다. 버전은 `namyang-conquest-osm-20261108-full-v1`, 801개 물리 구간/1,453방향, 파일350,799바이트다. 두 번 생성한 LF 원본 SHA-256이 `1c327f026e965b57c5b28584ed77c44ddf7529eb4914ab500caf5bd543b471d4`로 일치했다. Git CRLF 체크아웃 환경에서는 LF 정규화 후 비교한다. 앱 활성화는 별개이며 N3 기록 보존 검증 전에 assets를 덮어쓰지 않는다.

실제 검증: 전용9개 테스트 통과(종료코드0), 전체 자료 분석 성공, 두 번 생성한 보고서 해시 일치. 입력 순서 독립성, degree2 way연결, 공유노드 분할, 비공유 노드 입체교차, 정/역 일방통행, 방향충돌, 중복edge, 누락노드, 경계·짧은 길이·순환 보류, V1 출력 좌표 순서를 확인했다. 기존6방향 ID/geometry 동일 확인. 이번에는 Android 소스·assets 변경이 없어 Gradle·앱 테스트를 재실행하지 않았다.

## 다음 순서

오프라인 카탈로그 검증 완료: `ExpandedConquestCatalogTest`의3개 테스트로 실제 production reader에서1453방향 읽기, 최소길이/ID유일성, 기존6방향 ID/끝점/전체좌표/길이 보존, 양방향652개·일방통행149개 구분을 확인했다. 전체 단위 테스트149개 실패/오류0, lintDebug·assembleDebug 성공(기존 Lint Warning5). 카탈로그 생성 결과만 검증했으며 전역 실제주행 판정/지도 사용성/저장기록 전환 완료를 뜻하지 않는다.

오프라인 카탈로그의 실제 Kotlin reader 검증 후 N3 기존 기록/pending 보존 설계 및 테스트로 이어간다. 전역 위치 판정 성능·지도 UI·실차 검증은 후속 단계다. 도로 법규를 현장에서 확인한 자료나 내비게이션용 경로망으로 표시하지 않는다. 앱과 APK에는 아직 기존3개 물리 구간/6방향만 사용한다.

자료 출처·ODbL 및 경계 고지는 CONQUEST-EXPANSION-AUDIT.md와 기존 LICENSE 파일을 따른다.
