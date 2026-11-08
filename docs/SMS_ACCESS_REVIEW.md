# 자동 SMS 수신 분석 검토 (FR-04)

상태: **미검토 / 미구현**. 아래 항목이 확정되기 전까지 자동 수신 분석은 배포 가능한 기능으로 표시하지 않습니다.

## 현재 코드 상태
- `AndroidManifest.xml`에 `RECEIVE_SMS`, `READ_SMS`를 선언하지 않음
- `UnavailableIncomingSmsSource`가 연결되어 있어 설정/홈 화면에 "사용 불가 + 수동 분석 방법"이 표시됨
- 승인된 방식이 정해지면 `IncomingSmsSource`를 구현하고 `AppContainer.smsSource`에 연결, 수신 콜백에서 `AnalysisRepository.analyzeIncoming` 호출

## 결정해야 할 항목
| 항목 | 상태 |
|---|---|
| 배포 경로 (Google Play / 제한 배포) | 미정 |
| 기존 메시지 앱 유지가 필수인지, 기본 SMS 앱 전환 허용인지 | 미정 |
| Google Play SMS 권한 정책 적합성 (기술적으로 가능해도 배포 허용을 뜻하지 않음) | 미검토 |
| 기본 SMS 앱 요건(문자 송수신 기능 포함) 충족 가능성 | 미검토 |
| 멀티파트 SMS 합치기, 중복 수신 이벤트 방지 테스트 | 미실시 |
| RCS / MMS / 제조사 메시지 앱 동작 | 미검증 (지원 보장 안 함) |

## 구현 시 지켜야 할 것
- 새로 수신된 문자만 처리, 과거 문자 일괄 수집 금지
- 중복 수신 이벤트는 `IncomingSms.deduplicationKey`로 걸러냄 (DB unique index 적용됨)
- 로그에 원문/번호 금지, 지원하지 않는 메시지 유형은 앱에 명시
- 정책 및 심사 요건 재검토 후 활성화
