# ADR-0016: 재고 영속성 규칙

## 상태

Accepted (2026-10-07)

## 배경 (Context)

F-007의 영속성 어댑터를 만들며 [ADR-0003](0003-reservation-concurrency-conditional-update.md)과 [ADR-0015](0015-inventory-domain-model.md)의 결정을 코드로 옮긴다. 출력 포트의 모양, 중복 이력 처리, 감사 컬럼 기반 클래스, 동시성 검증 방식을 정한다.

## 결정 (Decision)

1. **수량을 바꾸는 포트 메서드는 갱신된 `Inventory`를 반환하고, 조건을 만족하지 못하면 도메인 예외를 던진다.** 어댑터는 조건부 UPDATE 한 문장을 실행하고, 영향 행이 0이면 행을 다시 조회해 `Inventory`의 규칙으로 원인(행 없음, 품질 상태, 보류, 수량·예약 부족)을 판별한다. 조회 시점에 조건을 만족하는 상태라면 그 사이 다른 요청이 바꾼 것이므로 부족 예외로 알려 호출자가 다시 시도하게 한다.
2. **입고는 `INSERT ... ON DUPLICATE KEY UPDATE`로 한 문장에 처리한다.** 같은 키(창고 × Lot × 품질 상태)의 행이 있으면 수량을 더하고 없으면 만든다. 별칭(`AS new_row`)을 쓰고 `quantity`는 `inventory.quantity`로 한정한다. 직접 쓰는 SQL이라 `updated_at`, `updated_by`는 `Clock`과 `CurrentActorProvider`로 SQL에 넣는다.
3. **이력 저장이 같은 (멱등 키, 재고 행)과 겹치면 `DuplicateIdempotencyKeyException`(409)을 던진다.** 이력은 수량 갱신 뒤에 저장해야 `quantity_after`를 알 수 있으므로, 예외가 호출한 트랜잭션을 롤백해 수량 갱신도 함께 되돌린다. 서비스(F-008)는 트랜잭션 밖에서 이 예외를 받아 `findByIdempotencyKey`로 저장된 이력을 조회해 같은 결과를 반환한다.
4. **감사 컬럼 기반 클래스는 한 줄기로 상속한다.** `CreatedAuditEntity`(생성) ← `BaseEntity`(+수정) ← `SoftDeletableEntity`(+삭제). 변경하지 않는 원장(`inventory_history`, `reservation_event`)은 `CreatedAuditEntity`를 상속한다. 이 줄기에 맞지 않는 `outbox_event`(`created_by` 없음)는 기반 클래스 없이 `@CreatedDate` 필드를 직접 둔다.
5. **동시성은 실제 MySQL에서 코루틴 50개를 병렬 실행해 검증한다.** 가용 10에 50개 예약(정확히 10개만 성공), 같은 키에 50개 동시 입고(행 1개, 합계 정확), 같은 멱등 키 이력 50개 동시 저장(1건만 성공). SQL 조건과 도메인 모델 규칙은 상태 조합 전체에서 결과가 같은지 비교한다.

## 결과 (Consequences)

- 얻는 것: 서비스는 한 줄로 수량을 바꾸고 실패 원인은 도메인 예외로 받는다. 동시 요청의 정확성은 DB가 보장하고 테스트가 이를 확인한다. 이력이 변경 불가임이 타입(생성 컬럼만)으로 드러난다.
- 감수하는 것: 같은 규칙이 모델과 SQL에 중복되어 일치 테스트가 필요하다. 갱신 직후 행을 다시 조회하는 SELECT가 한 번 더 나간다(MySQL에는 `RETURNING`이 없다). `utf8mb4_bin`은 PAD SPACE라 끝 공백만 다른 Lot 번호는 같은 Lot으로 취급된다(ERD-08).
