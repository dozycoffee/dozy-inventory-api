# ADR-0038: 공통 이벤트 형식(envelope)

## 상태

Accepted (2026-10-12). ADR-0025의 발행 규칙을 보완하고 `docs/events.md`의 이벤트 계약 형식을 바꾼다. 항목별 결정은 사용자 확인을 거쳤다. 이 형식은 MSA 전체(inventory, WMS, OMS, Store)의 공통 이벤트 형식으로 제안하며, 다른 서비스의 적용은 각 서비스와 협의한다. 구현은 단계로 나눈다: (1) 설계 확정(이 ADR과 `docs/events.md`), (2) Outbox에 `event_id`(UUID)와 추적 컨텍스트 컬럼 추가 및 헤더 확장, (3) envelope 직렬화 공통화, (4) 발행기별 이전과 테스트 갱신.

## 배경 (Context)

지금 이벤트는 서비스 안에서만 일관된다. 구독자 입장에서 아쉬운 점이 있다.

- 헤더의 `event-id`가 `outbox_event_id`(DB 자동 증가 Long)라 서비스 DB 안에서만 유일하다. 서비스가 둘 이상이면 같은 값이 겹쳐 `(서비스, ID)` 조합이 필요하다. WMS가 보내는 이벤트(`WAREHOUSE_ACCESS_CHANGED`)는 payload의 `eventId`가 UUID라 서비스마다 형식이 다르다.
- 공통 메타(`eventType`, `occurredAt`)와 도메인 필드가 한 객체에 섞여 있다. 어디까지가 공통인지 구분되지 않는다.
- 발행 서비스(`source`)와 스키마 버전이 없다. 서비스는 토픽 접두사로만 알 수 있고, 호환되지 않는 변경을 알릴 수단이 없다.
- 분산 추적용 ID가 없어 서비스 사이 흐름을 따라가기 어렵다.
- `null` 처리가 필드마다 다르다(`reason`은 생략, `expirationDate`는 `null`).

## 결정 (Decision)

1. **이벤트 ID는 UUID(v7 권장)다.** 저장 시점에 만든다(`outbox_event.event_id`, 36자 문자열). 서비스 전체에서 유일하고 DB에 의존하지 않는다. `outbox_event_id`(Long)는 내부 PK와 같은 키의 발행 순서 기준으로만 쓰고 밖으로 내보내지 않는다. 격리된 이벤트를 재발행(`requeue`)해도 **같은 `eventId`를 유지**해 구독자가 중복으로 거른다.
2. **envelope과 `data`를 나눈다.** 값(JSON)은 공통 메타를 최상위에 두고 도메인 본문은 `data` 안에 둔다.

   ```json
   {
     "eventId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
     "eventType": "RESERVATION_CREATED",
     "eventVersion": 1,
     "occurredAt": "2026-10-12T10:00:00+09:00",
     "source": "inventory",
     "data": { "reservationId": 1 }
   }
   ```

3. **`eventId`는 헤더와 payload 양쪽에 같은 값으로 둔다.** 헤더는 payload를 파싱하지 않는 중복 제거에, payload는 DLT·로그에 payload만 남았을 때의 추적에 쓴다.
4. **`source`와 `eventVersion`을 추가한다.** `source`는 발행 서비스 이름(`inventory`)이다. `eventVersion`은 정수이며 1부터 시작하고, 필드 추가처럼 호환되는 변경에는 올리지 않고 필드 삭제·이름 변경·의미 변경처럼 호환되지 않는 변경에만 올린다.
5. **`occurredAt`은 이벤트를 트랜잭션 안에서 Outbox에 기록한 시각이다**(오프셋 포함 ISO-8601). 도메인 변경과 같은 트랜잭션이라 도메인 변경 시각과 거의 같다. Kafka 발행 시각이 아니다.
6. **헤더는 6종이다.** 기존 `event-id`, `event-type`, `aggregate-type`, `aggregate-id`에 `event-version`, `source`를 더한다. 값은 모두 UTF-8 문자열이다.
7. **추적은 W3C `traceparent` 헤더로 한다.** 발행기는 별도 스레드이므로 이벤트를 저장하는 시점의 추적 컨텍스트를 `outbox_event`에 함께 저장했다가 발행할 때 헤더로 싣는다. 추적 컨텍스트가 없으면 헤더를 생략한다.
8. **`aggregate-id`와 파티션 키는 문자열로 규정한다.** inventory는 Long 값을 문자열로 보내고 구독자는 숫자로 파싱하지 않는다. 다른 서비스가 UUID를 써도 같은 규칙으로 맞는다.
9. **JSON 규칙**: 필드 이름은 camelCase, 시각은 오프셋 포함 ISO-8601, 날짜는 `yyyy-MM-dd`, **값이 `null`이면 필드를 내보내지 않는다**. 구독자는 필드가 없는 것과 `null`을 같게 처리한다. 지금 `null`로 나가는 필드(예: `expirationDate`)는 없어진다.
10. **이름 규칙은 바꾸지 않는다.** `eventType`은 `{집계}_{과거형 동사}` 대문자 스네이크(예: `INVENTORY_INCREASED`), 토픽은 `dozy.{서비스}.{집계}`이며 집계마다 토픽 하나이고 같은 집계의 이벤트 유형은 같은 토픽에 둔다.
11. **한 번에 전환한다.** 서비스가 첫 배포 전이므로(ADR-0021) 호환 계층이나 이중 발행 없이 envelope 형식으로 바로 바꾼다. 전환 전에 구독자(OMS, WMS, Store)가 아직 운영 중이 아님을 확인한다. `outbox_event` 컬럼은 V1을 직접 고친다.
12. **다른 서비스 적용**: WMS가 발행하는 `WAREHOUSE_ACCESS_CHANGED`(inventory가 구독)도 이 형식을 따르는 것을 제안한다. 지금은 `eventId`가 payload 최상위에 있는 평면 형식이다. WMS 쪽 변경은 WMS 프로젝트에서 정하며 inventory는 그 전까지 지금 형식을 받는다.

## 결과 (Consequences)

- 얻는 것: 서비스 전체에서 유일한 이벤트 ID로 구독자가 중복 제거를 한 키로 한다. 공통 메타와 도메인 본문이 나뉘어 공통 처리(로깅, 중복 제거, 라우팅)를 payload를 모르고 만들 수 있다. 버전과 출처가 생겨 호환성 변경을 알릴 수 있고, 추적 헤더로 서비스 사이 흐름을 따라간다.
- 감수하는 것: 구독자는 본문을 `data` 아래에서 읽도록 바꿔야 한다(계약 변경). `null` 필드가 사라지므로 구독자 파싱도 확인해야 한다. 서비스마다 envelope을 따라야 의미가 있어서 다른 서비스와 협의가 필요하다. 추적 컨텍스트를 `outbox_event`에 저장하므로 컬럼이 늘어난다. 헤더의 `event-id` 값이 숫자에서 UUID로 바뀐다.
