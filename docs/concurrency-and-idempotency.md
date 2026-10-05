# 예약 동시성과 멱등성

IMS의 핵심 약속은 "같은 재고를 여러 채널이 동시에 잡아도 초과 판매가 나지 않는다"이다. 결정 배경은 [ADR-0003](adr/0003-reservation-concurrency-conditional-update.md)에 있다.

## 1. 예약 동시성: DB 원자적 조건부 갱신

가용 확인과 갱신을 SQL 한 문장으로 합쳐, 읽고-계산하고-쓰는 사이의 틈을 없앤다.

```sql
-- 예약: 재고 행 1개를 조건부 갱신. 영향 행이 0이면 실패 (원인은 별도 조회)
UPDATE inventory
   SET reserved_quantity = reserved_quantity + :qty
 WHERE inventory_id = :inventoryId
   AND quality_status = 'NORMAL'
   AND allocation_hold = 0
   AND quantity - reserved_quantity >= :qty;

-- 출고 확정: 총 수량과 예약 수량을 함께 차감
UPDATE inventory
   SET quantity = quantity - :qty,
       reserved_quantity = reserved_quantity - :qty
 WHERE inventory_id = :inventoryId
   AND reserved_quantity >= :qty;
```

규칙:

- 주문 단위로 전체 성공 또는 전체 실패한다. 한 트랜잭션에서 행마다 조건부 UPDATE를 실행하고, 하나라도 0건이면 전체 롤백한다.
- 여러 행을 갱신할 때는 항상 `inventory_id` 오름차순으로 갱신한다(데드락 방지).
- Lot 할당은 후보 Lot 조회(락 없음)와 조건부 UPDATE를 섞는 하이브리드다. 후보를 유통기한 오름차순으로 조회하고, 갱신이 0건이면(그 사이 다른 요청이 가져감) 다음 후보로 넘어간다. 모두 모아도 모자라면 전체 롤백한다.
- 갱신 뒤에는 같은 트랜잭션에서 새 `quantity`를 SELECT해 `inventory_history.quantity_after`와 응답에 사용한다(MySQL에는 `RETURNING`이 없다).
- 영향 행이 0이면 원인(재고 부족, 품질 상태, 행 없음, 할당 보류)을 알 수 없으므로 그 행을 한 번 더 조회해 판별한다.
- 가용 규칙은 엔티티가 불변식으로 지키고, DB `CHECK (reserved_quantity <= quantity)`가 마지막 안전망이다. SQL 조건과 엔티티 규칙이 같은지 검증하는 테스트를 둔다.
- 낙관적 락·비관적 락·Redis 락은 쓰지 않는다. 근거는 ADR-0003.

## 2. 멱등성

모든 변경 요청은 멱등 키를 가진다. 키는 ASCII 문자열(UUID 또는 `서비스-문서-ID` 형태, 최대 100자)이며 대소문자를 구분한다([erd.md](erd.md)의 ERD-07). 같은 키의 재요청은 같은 결과를 반환하고 수량을 중복 반영하지 않는다. 별도 멱등 테이블 없이 도메인 테이블의 유니크 제약으로 보장한다([erd.md](erd.md)의 ERD-05).

| 테이블 | 유니크 제약 |
|--------|-------------|
| `reservation` | `idempotency_key` |
| `stock_adjustment` | `idempotency_key` |
| `inventory_history` | `(idempotency_key, inventory_id)` — 한 요청이 여러 재고 행을 건드려도 행마다 한 번 |

- 이전 결과는 도메인 데이터(이력의 `quantity_after`, 예약, 조정)에서 다시 만들어 반환한다.
- 같은 키에 다른 내용이 오는 경우는 저장된 도메인 행과 비교해 거부한다.
- 최종 판정은 DB의 유니크 제약이다. 동시에 같은 키가 들어오면 한쪽은 중복 키 오류로 롤백되고, 롤백된 쪽은 저장된 행을 다시 조회해 같은 결과를 반환한다.

> 🔶 미확정 제안: (1) 처리 시작 시 멱등 키로 먼저 조회해 이미 처리된 요청을 걸러내는 사전 조회 단계. (2) 예약은 `reservation` INSERT(유니크 키)를 재고 갱신보다 먼저 실행해, 중복이면 재고 행을 건드리기 전에 실패하게 한다. 확정되면 이 문서를 고친다.

## 3. 이벤트 발행 (Outbox)

- 수량 변경과 같은 트랜잭션으로 `outbox_event`에 이벤트를 저장하고, 별도 프로세스가 Kafka로 발행한다(최소 한 번 전달).
- 같은 `partition_key`(`창고ID:상품ID`)의 이벤트는 `outbox_event_id` 순서로 발행해 순서를 보장한다.
- 구독자는 중복 전달에 대비해 멱등하게 처리해야 한다.
- 이벤트 발행은 `application/port/out`의 포트로 추상화한다.
