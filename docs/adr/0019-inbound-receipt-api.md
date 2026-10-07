# ADR-0019: 입고 확정 API 형태

## 상태

Accepted (2026-10-08)

## 배경 (Context)

[ADR-0017](0017-inbound-confirmation.md)이 입고 확정의 규칙과 경로·권한을 정했고, 이 ADR은 웹 어댑터(F-008의 마지막 계층)에서 정한 API 계약을 기록한다.

## 결정 (Decision)

1. **`POST /api/v1/inbound-receipts`, 호출은 `inventory:service`만 허용한다.** 본사 관리자와 창고 관리자는 403이고 토큰이 없으면 401이다.
2. **멱등 키는 `Idempotency-Key` 요청 헤더로 받는다.** 수량을 바꾸는 모든 API(예약, 출고, 반품, 폐기, 조정)가 같은 방식을 쓴다. 본문은 업무 내용만 담는다. 헤더가 없으면 400(`VALIDATION_FAILED`), 형식이 잘못되면 400(`INV_INVALID_IDEMPOTENCY_KEY`)이다.
3. **요청 본문**: `warehouseId`, `productId`, `quantity`, `qualityStatus`(`NORMAL`·`DEFECTIVE`), `lotNumber`(최대 50자), `manufactureDate`·`expirationDate`(`yyyy-MM-dd`, 생략 가능), `inboundItemId`(WMS의 입고 상품 ID, 이력의 원인 문서 ID). 확정 종류마다 원인 문서 ID의 이름이 다르다(반품은 `returnItemId`, 폐기는 `disposalItemId`).
4. **응답은 처음 반영과 재요청 모두 `200 OK`이고 같은 본문이다.** 입고 수불을 따로 조회하는 API가 없어 `201`과 `Location`이 의미가 없고, WMS는 재전송 때 처음인지 재요청인지 구분할 필요가 없다. 본문은 `inventoryId`, `warehouseId`, `productId`, `lotId`, `qualityStatus`, `quantityChange`, `quantityAfter`(반영 직후 총 수량), `inventoryHistoryId`이다.
5. **요청 주체(`requester_service`)는 토큰의 `principalId`(UUID)를 그대로 쓴다**(ADR-0011). 컨트롤러가 `CurrentActorProvider`에서 꺼내 Command에 담는다. `local` 프로필에서는 개발 사용자다.
6. **오류 응답**은 서비스의 도메인 예외를 `GlobalExceptionHandler`가 변환한다: 상품 없음 404(`INV_PRODUCT_NOT_FOUND`), Lot 날짜 불일치 409(`INV_LOT_MISMATCH`), 같은 키에 다른 내용 409(`INV_IDEMPOTENCY_KEY_CONFLICT`), 수량·품질 상태 위반 400.

## 결과 (Consequences)

- 얻는 것: 이후 모든 변경 API가 같은 멱등 키 규칙을 쓴다. WMS 재전송에 안전하고 응답 비교(`quantityAfter`)가 쉽다.
- 감수하는 것: 멱등 키가 본문이 아닌 헤더라 호출 측(WMS 클라이언트)이 헤더를 추가해야 한다. 요청 로그에서 본문만 보면 키가 보이지 않는다.
