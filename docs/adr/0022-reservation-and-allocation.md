# ADR-0022: 예약 생성과 Lot 할당 규칙

## 상태

Accepted (2026-10-07). 1단계(inventory 도메인의 Lot 할당)를 이 ADR과 함께 구현한다. 예약 도메인(`reservation`)의 모델·영속성·application·웹은 이후 PR에서 계층별로 구현한다.

## 배경 (Context)

F-010은 "같은 재고를 여러 채널이 동시에 잡아도 초과 판매가 나지 않는다"는 핵심 약속을 구현한다. 예약은 `reservation` 도메인이 소유하고 수량은 `inventory` 도메인이 바꾸므로, 도메인 간 호출 규칙(다른 도메인은 `application/port/in`으로만 호출)에 맞춰 책임을 나눠야 한다. 서비스명이 아직 확정되지 않아 `reservation.channel` 값을 지금 정할 수 없고, 가맹점의 최적 창고 선택을 누가 하는지도 정해야 했다.

## 결정 (Decision)

1. **창고는 호출 서비스가 확정해서 보낸다.** 예약 요청은 `warehouseId` 하나만 받는다. 후보 창고 목록이나 우선순위로 최적 창고를 고르는 일은 OMS·Store의 책임이며(시나리오 4.1) inventory 서비스는 지원하지 않는다. 지정한 창고에서 안 되면 실패하고 재시도나 다른 창고 선택은 호출 서비스가 한다.
2. **`reservation.channel`은 확정하지 않는다.** `channel`은 요청으로 받는 자유 문자열이다(형식만 검증, DB는 이미 `CHECK` 없는 `VARCHAR(50)`). 채널별 TTL 상한은 설정 `inventory.reservation.max-ttl`의 `채널명 → 시간` 맵과 `default`로 두고, 맵에 없는 채널은 `default`를 적용한다. 서비스명이 확정되면 설정만 바꾼다.
3. **책임 분담**: `inventory`는 `AllocateInventoryUseCase`로 "창고에서 상품별 수량을 유통기한 이른 Lot부터 예약 수량에 잡는" 일만 한다. `reservation`은 예약·항목·할당·이벤트 이력 저장, 멱등, TTL을 맡고 같은 트랜잭션 안에서 `AllocateInventoryUseCase`를 호출한다. 예약은 총 수량을 바꾸지 않으므로 `inventory_history`에는 기록하지 않고 `reservation_event`에 남긴다.
4. **Lot 할당(FEFO)**: 후보는 정상 품질이고 할당 보류가 아니며 가용 수량이 있고 유통기한이 오늘보다 뒤인(또는 없는) Lot의 행이다. 유통기한이 이른 순서이고 유통기한이 없는 Lot이 마지막이며 같으면 `inventory_id` 오름차순이다. 유통기한이 오늘 이하인 Lot은 `EXPIRED`로 보고 제외한다. 모자라면 전체 실패이다(`INV_INSUFFICIENT_AVAILABLE_QUANTITY`, 409). 요청은 주문 단위(한 창고, 여러 상품)이고 상품은 1~100개이며 중복할 수 없다.
5. **계획 후 확정**: 후보 조회(락 없음)로 상품별 할당 계획을 세우고, 계획한 행을 상품과 무관하게 `inventory_id` 오름차순으로 조건부 UPDATE한다(ADR-0003). 하나라도 실패하면 `INV_ALLOCATION_CONFLICT`(409, `AllocationConflictException`)로 알린다.
6. **경합 재시도는 호출자가 새 트랜잭션에서 한다.** MySQL REPEATABLE READ에서는 같은 트랜잭션 안의 후보 재조회가 같은 스냅샷을 다시 읽어 재계획이 소용없다(입고의 Lot 재시도와 같은 이유). 그래서 할당 서비스는 한 번만 시도하고, 예약 서비스가 `AllocationConflictException`일 때 트랜잭션 전체를 다시 실행한다. 실패한 트랜잭션은 롤백되므로 이미 잡은 수량은 되돌릴 필요가 없다.
7. **멱등**: `reservation.idempotency_key` 유니크 제약이 최종 판정이다. 같은 키의 재요청은 저장된 예약에서 처음 응답과 같은 결과를 만들어 반환하고, 같은 키에 다른 내용이 오면 `INV_IDEMPOTENCY_KEY_CONFLICT`(409)이다.

## 결과 (Consequences)

- 얻는 것: 초과 예약이 구조적으로 불가능하고(조건부 UPDATE + CHECK), 데드락 없이(전역 `inventory_id` 순서) 주문 단위 전체 성공·전체 실패가 보장되며, 서비스명이 확정되지 않아도 구현이 막히지 않는다. 최적 창고 선택 로직이 inventory에 들어오지 않는다.
- 감수하는 것: 같은 재고에 경합이 몰리면 요청이 `AllocationConflictException`으로 트랜잭션을 다시 실행해야 해 느려진다(실제 MySQL 20개 동시 요청 테스트로 결과의 정확성을 검증한다). 후보 조회가 스냅샷이라 방금 반환된 수량은 늦게 보일 수 있고 그 경우 요청은 가용 수량 부족으로 실패할 수 있다(재시도는 호출자). 여러 창고에 걸친 주문은 창고별 예약으로 나눠 보내야 한다.
