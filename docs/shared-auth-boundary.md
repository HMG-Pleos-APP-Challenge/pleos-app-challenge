# 기존 인증을 사용하는 점령 호출 계약

상태는 점령 연결 담당자에게 전달하는 구현 계약이다. 기준은 main abf3a41이다. 원격 choi는 c31db41이며 454994b와 전달 패치는 현재 저장소에서 확인되지 않았다. 해당 준비 코드와의 대조 검토는 후속으로 필요하다.

## 입력과 결과

SupabaseCloud는 ExistingAccountAuth를 구현한다. 기존 account()/rpc()는 기존 탐험의 가입·동기화 흐름을 유지한다. 새 점령 조립부는 아래 경계를 사용한다.

| 호출 | 입력 | 성공 결과 | 실패 결과 |
| --- | --- | --- | --- |
| existingAccount | 없음 | Success(binding, Unit) | NoSession, Failed |
| rpcForAccount | 예상 AccountBinding, RPC 이름, JSON 객체 본문 문자열 | Success(binding, 응답 문자열) | NoSession, AccountChanged, Failed |
| withCurrentAccount | 응답의 AccountBinding, 짧은 로컬 트랜잭션 | Success(binding, 로컬 처리 결과) | NoSession, AccountChanged, Failed |

AccountBinding은 Auth UUID와 설치 내 인증 저장소별 generation이다. 토큰과 비밀번호를 포함하지 않는다. 바인딩은 만든 인증 조립부와 같은 프로젝트·세션 저장소에서만 사용한다. 서버 권한 키와 서버 저장 데이터의 세대로 사용하지 않는다.

모든 호출은 blocking이므로 IO dispatcher에서 실행한다. existingAccount는 저장된 세션이 있을 때만 필요에 따라 갱신한다. 저장된 계정 식별이며 원격 사용자 프로필의 최신 상태를 별도로 조회하는 함수가 아니다. 서버가 인증 요청을 검증한다.

Failed.stage는 READ_SESSION, REFRESH_SESSION, REQUEST, APPLY를 구분한다. 예외 문자열이나 인증 응답 전문을 오류 결과로 노출하지 않는다. CancellationException은 전파한다. REQUEST 단계 실패를 서버의 영구 거부와 동일시하지 말고, 서버 결과 미확정으로 취급한다. 응답 본문의 기여 거부 사유는 점령 어댑터가 별도로 해석한다.

## 전환 세대

- 세션 부재 시 신규 가입을 실행하지 않는다. 갱신 실패로 익명 가입을 대체 실행하지 않는다.
- 기존 세션에 세대 필드가 없으면 0으로 읽는다. 최초 세션 저장은 1이다.
- 같은 계정의 토큰 갱신·이메일 연결·비밀번호 변경은 세대를 유지한다.
- 기존 restore가 성공하면 같은 계정으로 복원해도 세대를 증가시킨다.
- 새로운 세대는 기존 암호화 세션 JSON의 _account_generation에 함께 저장한다. 저장 실패 시 복원 성공으로 보고하지 않는다.
- 갱신 응답의 UUID가 기존 UUID와 다르면 새 응답을 저장하기 전에 거부한다.
- 예상 UUID 또는 세대가 다르면 갱신과 RPC 모두 실행하지 않는다.
- A→B→A 전환 후 첫 A의 응답은 UUID가 같아도 적용하지 않는다.
- 세션 전체 저장소가 삭제되는 앱 데이터 초기화는 프로세스·로컬 큐도 초기화되는 별도의 수명주기다.

## 조립부에서 주입할 동작

점령 모듈에 SupabaseCloud 인스턴스나 토큰 접근을 넘기지 않는다. 조립부가 existingAccount를 호출해 얻은 바인딩을 점령 자체 소유 문맥에 대응시키고, 허용한 팀 선택·기여 제출·스냅샷 조회별 호출만 주입한다. 실제 RPC 이름과 점령 내부 타입은 454994b 대조 후 결정한다.

호출 순서는 다음과 같다.

1. 점령 전용 큐 잠금에서 배치를 읽고 잠금을 해제한다.
2. 조립부의 rpcForAccount(expected, 허용된 RPC 이름, 본문)를 호출한다.
3. Success 응답을 파싱하고 계정·출처·카탈로그·이벤트 ID·본문 해시·항목별 결과를 확인한다.
4. 응답 binding으로 withCurrentAccount를 호출한다. 콜백 안에서는 점령 큐의 짧은 원자적 트랜잭션으로 해당 전송 행 ACK와 같은 계정의 캐시 갱신만 수행한다.
5. NoSession 또는 AccountChanged면 ACK·캐시 갱신을 수행하지 않는다. 이전 계정의 미확정 행은 보존해 해당 계정에서 멱등 재전송할 수 있게 한다.
6. 화면에 내보내는 상태도 바인딩을 포함한다. 비동기 화면 적용 시 현재 문맥과 다른 상태를 표시하지 않도록 점령 조립부에서 확인한다.

withCurrentAccount는 로컬 트랜잭션의 원자성을 대신 제공하지 않는다. 콜백에서 저장 일부가 성공한 뒤 예외가 나면 실제 ACK 여부는 그 트랜잭션의 보장에 따른다. HTTP·suspend 호출·UI 콜백·외부 잠금 재획득을 넣지 않는다.

## 잠금 순서

현재 계정 복원 호출 경로는 다음 순서다.

AccountSettings의 syncAccess → sampleAccess → SupabaseCloud 인증 monitor → ConquestAccountRestore 기록 monitor → 복원 요청 및 암호화 세션 저장

새 인증 경계도 같은 SupabaseCloud 인증 monitor를 사용한다. rpcForAccount의 읽기·갱신·예상 바인딩 확인·HTTP 요청 전체를 같은 monitor에서 처리한다. 복원과 같은 순간에 인증 세션을 교체할 수 없다. HTTP 종료 후에는 복원이 먼저 실행될 수 있으므로 로컬 적용에는 withCurrentAccount가 추가로 필요하다.

점령 큐의 잠금을 잡은 채 인증 함수나 복원을 호출하지 않는다. 응답 적용에서는 인증 monitor → 점령 큐/기록 잠금 순서만 사용한다. 인증 함수 내부에서 syncAccess나 sampleAccess를 역으로 획득하지 않는다.

현재 복원은 sampleAccess를 잡고 네트워크 복원을 기다리는 기존 구조다. 이번 범위에서는 그 순서를 유지한다. 네트워크 준비와 세션 커밋을 나누는 구조 변경은 신규 점령 큐 검사와 함께 별도 검토해야 한다.

점령의 새 귀속 저장소와 서버 큐는 최종 복원 검사에 추가 연결해야 한다. 이번 변경에는 점령 내부 앱 연결이나 실제 ACK가 포함되지 않는다. 버튼 표시용 검사만으로 최종 복원을 허용해서는 안 된다.

## 검증

ExistingAccountBoundaryTest는 세션 부재 시 가입·요청 없음, 만료 갱신, 잘못된 계정·세대 차단, 갱신 실패, 갱신 계정 변경, A→B→A 이전 응답 차단, 로컬 적용 오류, 요청과 복원의 상호 배제, 취소 전파를 검증한다.

ExplorationRequirementsProbe의 모의 인증 부분에는 실제 SessionVault를 사용한 세션 부재 조회, 복원 세대 증가, 이전 응답 적용 거부가 포함된다. 가상 이메일과 HTTP 응답만 사용한다. 실제 인증 서버나 이메일 발송을 호출하지 않는다.

실행 명령은 다음과 같다.

```powershell
./gradlew.bat --offline testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

장치에서는 com.daehyundai.map.ExplorationRequirementsProbe를 실행한다. 점령 준비 패치 연결 후에는 실제 큐의 일부 ACK·동시 enqueue·계정 전환 후 캐시·화면 적용 검증을 추가해야 한다.

## 이번 실행 결과

- 전체 JVM 단위 테스트 181개 통과. 신규 인증 경계 테스트는 9개다.
- 디버그 APK·Android 테스트 APK 빌드와 Lint가 통과했다.
- Pleos 에뮬레이터에서 모의 HTTP와 실제 암호화 세션 저장소를 사용한 인증 검증이 통과했다.
- 세션 부재 조회의 HTTP 호출 0회, 계정 복원 세대 증가, 같은 계정 재복원 세대 증가, 이전 바인딩 적용 차단을 확인했다.
- 실제 점령 준비 커밋 454994b는 원격 fetch 후에도 없어 패치 호환성 검토는 남아 있다.
