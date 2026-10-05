# ADR-0001: WMS와 동일한 기술 스택

## 상태

Accepted (2026-10-05)

## 배경 (Context)

IMS는 새로 시작하는 서비스라 스택을 자유롭게 고를 수 있지만, 같은 팀이 같은 조직의 서비스(WMS, `dozy-auth`)와 함께 운영한다.
IMS는 동시성이 핵심이다. OMS와 가맹점이 같은 재고를 동시에 예약하고, WMS가 확정 호출을 동시에 보낸다.

고려한 대안:
- **Kotlin + Spring MVC + JPA(블로킹)**: 연관 엔티티 처리(cascade)가 편하지만 WMS와 패턴이 갈라진다.

## 결정 (Decision)

WMS와 동일한 스택을 쓴다: Kotlin, Spring Boot WebFlux, Spring Data R2DBC(코루틴), MySQL 8.0 + Flyway, 헥사고날 구조, `dozy-auth` 스타터.
DB는 IMS 전용 스키마(`dozy_inventory`)로 분리하며 WMS DB를 직접 읽지 않는다.
빌드는 Gradle Kotlin DSL, 패키지 루트는 `com.dozycoffee.ims`로 한다.

## 결과 (Consequences)

- 얻는 것: 컨벤션·오류 응답·감사·인증 규약·테스트 픽스처를 그대로 재사용한다. IMS의 도메인 모델은 `창고 × 상품 × Lot × 품질 상태` 행과 예약으로 연관이 단순해 R2DBC의 약점(cascade 미지원)이 크게 문제 되지 않는다.
- 감수하는 것: R2DBC 체인 안에서 블로킹 호출을 하지 않도록 주의해야 한다.
