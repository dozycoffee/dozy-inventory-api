# ADR-0003: 예약 동시성은 DB 원자적 조건부 갱신

## 상태

Accepted (2026-10-05)

## 배경 (Context)

가용 수량이 10개인데 OMS와 가맹점이 동시에 8개씩 예약하면 한쪽만 성공해야 한다. 예약은 주문 단위로 전체 성공 또는 전체 실패하고, 한 상품이 여러 Lot에 걸쳐 할당될 수 있어 한 번의 예약이 여러 행을 갱신한다.

고려한 대안: 낙관적 락(경합이 높으면 재시도 폭증), 비관적 락(락 대기가 길어짐), Redis 락(Redis·DB 이중 진실).

## 결정 (Decision)

`UPDATE ... SET reserved_quantity = reserved_quantity + :qty WHERE ... AND quantity - reserved_quantity >= :qty`를 실행하고 영향 행 수로 성공을 판단한다.
여러 행은 한 트랜잭션에서 `inventory_id` 오름차순으로 갱신하고, 하나라도 0건이면 전체 롤백한다. 멱등 키는 유니크 제약으로 보장하고, DB `CHECK (reserved_quantity <= quantity)`를 마지막 안전망으로 둔다.
Lot 할당은 후보 조회(락 없음)와 조건부 UPDATE를 섞고, 갱신이 실패하면 다음 후보로 넘어간다. 상세 규칙은 [concurrency-and-idempotency.md](../concurrency-and-idempotency.md)에 있다.

## 결과 (Consequences)

- 얻는 것: 읽고 계산하는 사이의 틈이 없어 재시도 로직이 필요 없다. 락은 UPDATE 실행 순간에만 짧게 걸린다.
- 감수하는 것:
  - 가용 규칙이 엔티티와 SQL 두 곳에 있어 어긋날 수 있다. 엔티티는 불변식을 유지하고 DB CHECK 제약과, SQL 조건·엔티티 규칙이 같은지 검증하는 테스트로 보완한다.
  - 영향 행이 0이면 원인(재고 부족, 품질 상태, 행 없음, 보류)을 알 수 없어 한 번 더 조회해야 한다.
  - MySQL에 `RETURNING`이 없어 갱신 후 수량을 다시 SELECT해야 한다(같은 트랜잭션 안).
  - 복잡한 판단(Lot 할당)에는 단독으로 쓸 수 없어 읽기와 조건부 갱신을 섞는 하이브리드가 된다.
  - 인기 상품 한 행에 요청이 몰리면 줄을 서는 것은 피할 수 없다(현재 규모에서는 문제가 되지 않을 것으로 본다).
