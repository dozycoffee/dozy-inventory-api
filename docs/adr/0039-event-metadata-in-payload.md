# ADR-0039: 이벤트 메타데이터를 값(payload)에만 둔다

## 상태

Accepted (2026-10-13). ADR-0038의 결정 3(`eventId`를 헤더와 payload에 같은 값으로 둔다), 6(헤더 6종), 7(`traceparent` 헤더), 8의 헤더 부분(`aggregate-id`)을 대체한다. 나머지(envelope 구조, UUID v7, `eventVersion`·`source`, 시각·`null` 규칙, 이름 규칙)는 ADR-0038을 그대로 따른다. 항목별 결정은 사용자 확인을 거쳤다. 구현은 F-024로 나눈다: (1) 설계 확정(이 ADR과 `docs/events.md`), (2) inventory 발행기 전환과 테스트 갱신. WMS의 같은 전환은 WMS 프로젝트에서 정한다.

## 배경 (Context)

ADR-0038 이후 이벤트의 메타데이터가 두 곳에 있다. `eventId`, `eventType`, `eventVersion`, `source`는 값(JSON)의 최상위와 Kafka 헤더에 같은 값으로 들어간다. 헤더의 `aggregate-type`, `aggregate-id`, `traceparent`는 헤더에만 있다.

- 같은 정보를 두 번 싣고, 발행하는 쪽은 두 값이 어긋나지 않게 유지해야 한다.
- 구독자는 메타데이터를 읽으려면 헤더와 값을 모두 다뤄야 한다. 헤더를 쓰는 구독자는 아직 없고(inventory의 수신부도 값만 읽는다), 헤더를 읽는 코드는 테스트뿐이다.
- 헤더는 Kafka 클라이언트·도구마다 다루는 방법이 달라(DLT, 재처리 도구, 로그 수집) 값에 있는 정보만 복사하는 쪽이 이식성이 좋다.
- `aggregate-type`은 토픽 이름과 `eventType` 접두사에 이미 있다. `aggregate-id`는 `data`의 집계별 필드(`reservationId`, `inventoryId` 등)와 중복이고 읽는 곳이 없다.

## 결정 (Decision)

1. **메타데이터는 값(JSON)에만 둔다. Kafka 헤더는 쓰지 않는다.** 이벤트 메타데이터와 비즈니스 데이터가 모두 하나의 JSON에 있다.
2. **값의 최상위 필드는 8개다.**

   ```json
   {
     "eventId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
     "eventType": "RESERVATION_CREATED",
     "eventVersion": 1,
     "occurredAt": "2026-10-13T10:00:00+09:00",
     "source": "inventory",
     "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
     "data": { "reservationId": 1 }
   }
   ```

   `eventId`, `eventType`, `eventVersion`, `occurredAt`, `source`, `data`는 필수이고 `traceparent`는 선택이다. 의미는 ADR-0038과 같다.
3. **`traceparent`는 최상위 선택 필드다.** W3C Trace Context 형식(`00-{trace-id 32자}-{span-id 16자}-{flags 2자}`)이다. 요청 안에서 저장한 이벤트에만 있고(스케줄러 등 요청 밖에서 만든 이벤트는 필드를 보내지 않는다) 구독자는 없을 때를 처리해야 한다. `tracestate`는 다루지 않는다.
4. **`aggregate-type`과 `aggregate-id`는 보내지 않는다.** 집계는 토픽 이름과 `eventType`의 접두사로, 집계 ID는 `data`의 필드로 알 수 있다. 읽는 곳이 없는 값을 계약에 넣지 않는다. 범용 소비자가 필요해지면 필드를 추가한다(호환되는 변경이라 `eventVersion`을 올리지 않는다). `outbox_event`의 `aggregate_type`·`aggregate_id` 컬럼은 서비스 내부(토픽 결정, 관리자 API)용으로 그대로 둔다.
5. **메시지 키와 토픽은 그대로다.** 파티션 키(문자열)는 Kafka 파티션과 순서 보장에 쓰이므로 헤더가 아니라 키로 남는다. 토픽은 `dozy.{서비스}.{집계}`다.
6. **구독자는 값의 `eventId`로 중복을 거른다.** 헤더 `event-id`는 없어진다. 값을 파싱하지 않고 중복을 거르던 최적화는 포기한다(구독자는 어차피 값을 파싱한다).
7. **한 번에 전환한다.** 서비스가 첫 배포 전이므로(ADR-0021) 이중 발행이나 호환 계층을 두지 않는다. 구독자(OMS, WMS, Store)가 운영 중이 아님을 전환 전에 확인한다.
8. **Outbox 저장 방식**: 이벤트를 저장할 때 `OutboxService`가 `traceparent`를 포함한 envelope을 완성해 `payload`에 저장한다(지금도 envelope은 이 시점에 만든다). 발행 시점에 헤더로 싣기 위해 두었던 `outbox_event.traceparent`와 `event_version` 컬럼은 쓸 곳이 없어지므로 V1에서 지운다. `event_id`는 유니크 제약과 운영 조회를 위해 남긴다.
9. **다른 서비스**: WMS가 발행하는 `WAREHOUSE_ACCESS_CHANGED`도 같은 형식(헤더 없이 값에만 메타데이터)을 따르는 것을 제안한다. WMS는 지금 헤더도 싣고 있다. inventory의 수신부는 값만 읽으므로 WMS가 전환하기 전에도 동작한다. 전환 시점은 WMS 프로젝트에서 정한다.

## 결과 (Consequences)

- 얻는 것: 한 가지 정보를 한 곳에서만 관리하므로 어긋날 일이 없다. 구독자는 값 하나만 읽으면 메타데이터와 본문을 모두 얻는다. 메시지를 복사하거나 로그로 남겨도 정보가 빠지지 않는다. 발행기는 헤더를 만들 필요가 없어져 단순해지고, `outbox_event`의 컬럼 두 개가 줄어든다.
- 감수하는 것: 구독자가 헤더로 하던 중복 제거를 값에서 `eventId`로 해야 한다(계약 변경). 값을 파싱하지 않는 일반 Kafka 도구는 `eventId`·`eventType`으로 필터링하기 어렵다. 집계 유형·ID가 값에 없으므로 범용 소비자는 토픽과 `eventType`, `data`로 판단한다. 필요해지면 필드를 추가한다.
