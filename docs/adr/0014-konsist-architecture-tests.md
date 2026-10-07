# ADR-0014: 아키텍처 규칙은 Konsist 테스트로 강제한다

## 상태

Accepted (2026-10-07)

## 배경 (Context)

헥사고날 레이어 규칙, 도메인 간 호출 규칙(ADR-0010), 이름·위치 규칙, Kotlin 컨벤션은 리뷰만으로는 지켜지지 않는다. 도메인이 늘어나기 전에(F-007 재고 모델부터 도메인이 여러 개가 된다) `product`를 기준으로 규칙을 고정한다.
`dozy-auth`가 Konsist 0.17.3을 Kotlin 2.3.21과 함께 쓰고 있어 같은 조합을 그대로 쓴다.

## 결정 (Decision)

1. **Konsist 0.17.3으로 production 소스를 검사한다.** 테스트 코드는 대상이 아니다. 위반은 `./gradlew test`와 `verify.sh`를 실패시키고 메시지에 위반 목록이 모두 나온다.
2. **규칙은 네 가지다.** 레이어 의존 방향, 도메인 간 호출(다른 도메인은 `application/port/in`만), 이름·위치(접미사별 종류와 패키지, 엔티티·컨트롤러·도메인 모델 형태), 컨벤션(`!!`, `@Autowired`, `now()` 직접 호출, `@Transactional` 위치, Reactor 타입 위치, 프로퍼티·반환 타입 명시).
3. **레이어 간 허용 방향**: `domain`→`domain`, `port/in`→`domain`, `port/out`→`port/in`·`domain`, `service`→`port/in`·`port/out`·`domain`, `adapter/in`→`port/in`·`domain`, `adapter/out`→`port/out`·`domain`. `port/out`이 `port/in`을 볼 수 있는 것은 이벤트가 UseCase의 Result를 싣기 때문이다. `domain`은 `global.error` 외의 `global`을 import하지 않는다.
4. **반환 타입 명시는 식 본문과 비Unit 함수에 적용하고 블록 본문의 `Unit`은 생략을 허용한다.** ktlint의 `no-unit-return`이 `: Unit`을 자동으로 지우기 때문이다.
5. **Konsist 0.17은 추론한 타입과 명시한 타입을 구분하지 않으므로 타입 명시 검사는 소스 텍스트로 판단한다.** 

## 결과 (Consequences)

- 얻는 것: 구조 규칙 위반이 PR 단계에서 자동으로 잡힌다. 새 도메인이 `product`와 같은 구조를 갖는다.
- 감수하는 것: 접미사나 패키지를 새로 정할 때 `NamingRuleTest`의 규칙표도 고쳐야 한다. 타입 명시 검사는 텍스트 기반이라 특이한 선언 형태에서 오탐이나 누락이 있을 수 있다. Konsist 릴리스가 느려(0.17.3이 마지막) Kotlin을 올릴 때 호환을 확인해야 한다.
- 대안: ArchUnit(Kotlin 선언 표현이 약해 제외, `dozy-auth`와 같은 판단).
