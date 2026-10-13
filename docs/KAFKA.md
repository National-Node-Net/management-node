# Kafka Events

**Repository:** `management-node`
**Description:** `How the Management Node publishes events to Kafka, how to run Kafka locally, and how to switch it on and off`
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

The Management Node publishes an event to Kafka when something happens that other services need to act on. Today there is one: a consumer has been subscribed to a product. The service that listens for it (the identity management service) uses it to give the consumer's organisation access to that product in the identity provider.

Publishing is **off by default**. With it off the Node needs no broker and behaves exactly as it did before Kafka was introduced.

## Contents

- [1. What is published](#1-what-is-published)
- [2. Switching it on and off](#2-switching-it-on-and-off)
- [3. Configuration reference](#3-configuration-reference)
- [4. Running Kafka locally](#4-running-kafka-locally)
- [5. Seeing the events](#5-seeing-the-events)
- [6. Delivery: what is and is not guaranteed](#6-delivery-what-is-and-is-not-guaranteed)
- [7. How it is built](#7-how-it-is-built)
- [8. Adding another event](#8-adding-another-event)
- [9. Tests](#9-tests)
- [10. Troubleshooting](#10-troubleshooting)

---

## 1. What is published

| Event | Topic | Raised when | Published by |
|---|---|---|---|
| `EVT_001` `product-subscription.activated` | `product-subscription.activated` | a subscription has been recorded (`POST /api/v1/product/subscribe` succeeded) | `ProductSubscriptionServiceImpl`, sent by `ProductSubscriptionEventListener` |

The event is sent **after** the subscription is committed to the database. A subscription that is refused (`400`, `404`, `409`) or rolled back publishes nothing.

### 1.1 The message

The message is JSON. The **key** is the consumer's identity provider client (`consumerOrganisation.idpClientId`), so everything said about one client reaches a receiver in the order it happened.

```json
{
  "eventId": "7f3c2a1e-0b4d-4d7a-9a52-1c2e8f6d3b10",
  "eventType": "product-subscription.activated",
  "version": 1,
  "occurredAt": "2026-10-07T13:30:00Z",
  "subscriptionId": 15,
  "productId": 1,
  "productName": "BrownfieldLandAvailability",
  "producingOrganisation": { "organisationKey": "HEG", "idpClientId": "FEDERATOR_HEG" },
  "consumerOrganisation": { "organisationKey": "BCC", "consumerId": 2, "idpClientId": "FEDERATOR_BCC" }
}
```

| Field | Meaning |
|---|---|
| `eventId` | Unique to this occurrence. A receiver may use it to recognise a message it has already handled. |
| `eventType` | The event's name. |
| `version` | The version of the message's format. It changes only for a change that would break a receiver. |
| `occurredAt` | When the subscription was recorded (UTC, ISO-8601). |
| `subscriptionId` | The id of the subscription row (`subscriptionId` in the `subscribe` response). Use it to match a log line in another service to the row that caused it. |
| `productId`, `productName` | The subscribed product. |
| `producingOrganisation` | The organisation the product comes from. `organisationKey` is the Node's key; `idpClientId` is the identity provider's client for the producer (`product.producer.idp_client_id`). |
| `consumerOrganisation` | The organisation that subscribed. `consumerId` is the consumer within it that took the subscription (an organisation may have several); `idpClientId` is that consumer's identity provider client. |

Organisations are named twice on purpose: the Node knows them by `organisationKey`, the identity provider by `idpClientId`, and a receiver that talks to the identity provider should not have to look the second up.

> **Not to be confused with `product.topic`.** A product row also has a `topic` column. That is the Kafka topic the *product's data* flows on between producer and consumer federators. The topic this document describes carries the Node's own events about subscriptions.

## 2. Switching it on and off

| Environment variable | Effect |
|---|---|
| `KAFKA_ENABLED=true` | Events are published to Kafka. A broker must be reachable at `KAFKA_BOOTSTRAP_SERVERS`. |
| unset, or `false` | Nothing is published, no broker is needed, no connection is attempted. |

At startup the log says which it is:

```
Kafka event publishing is DISABLED - set application.kafka.enabled=true (KAFKA_ENABLED) to enable
Kafka event publishing is ENABLED - bootstrapServers=localhost:9092 productSubscriptionActivatedTopic=product-subscription.activated
```

## 3. Configuration reference

In `application.yml`. The Node's own settings are under `application.kafka`; the connection is Spring's own `spring.kafka`.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `application.kafka.enabled` | `KAFKA_ENABLED` | `false` | Master switch. |
| `application.kafka.topics.product-subscription-activated` | `KAFKA_TOPIC_PRODUCT_SUBSCRIPTION_ACTIVATED` | `product-subscription.activated` | Topic for `product-subscription.activated`. |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Where the broker is. |
| `spring.kafka.producer.acks` | | `all` | A send counts as done only once the broker has recorded it. |
| `spring.kafka.producer.properties.max.block.ms` | `KAFKA_MAX_BLOCK_MS` | `5000` | How long a send may wait for the broker's metadata before giving up. This matters when the broker is already down at the first send after the Node starts (see [§6](#6-delivery-what-is-and-is-not-guaranteed)). |
| `spring.kafka.producer.properties.enable.idempotence` | | `true` | A send that is retried is never written twice. |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `KAFKA_DELIVERY_TIMEOUT_MS` | `120000` | How long a send is retried before it is reported as failed. |
| `application.kafka.topic-settings.create` | `KAFKA_CREATE_TOPICS` | `false` | Declare the topic at startup. Leave off where topics are created deliberately. |
| `application.kafka.topic-settings.partitions` / `replicas` / `retention-days` | `KAFKA_TOPIC_PARTITIONS` / `KAFKA_TOPIC_REPLICAS` / `KAFKA_TOPIC_RETENTION_DAYS` | `1` / `1` / `7` | Shape of the topic, used only when `create` is `true`. |
| `spring.kafka.consumer.group-id` | | `management-node-group` | The Node's consumer group. The Node does not consume anything yet; the setting is in place for when it does. |

## 4. Running Kafka locally

`docker/kafka/docker-compose.yml` starts a single-node Kafka (KRaft, no ZooKeeper) and Kafka UI. It is for development and testing only.

```bash
docker compose -f docker/kafka/docker-compose.yml up -d
docker compose -f docker/kafka/docker-compose.yml ps     # both should become running / healthy
```

| What | Address |
|---|---|
| Kafka, from the host (the Node running in your IDE) | `localhost:9092` |
| Kafka, from another container | `kafka:29092` |
| Kafka UI | <http://localhost:8085> |

Ports can be changed with `KAFKA_PORT` and `KAFKA_UI_PORT`. The UI is on 8085 because Keycloak already uses 8080.

Then start the Node with Kafka switched on, for example in IntelliJ add to the run configuration's environment variables:

```
KAFKA_ENABLED=true
```

Stop it with `docker compose -f docker/kafka/docker-compose.yml stop`. `down -v` also deletes the stored messages.

Topics are created the first time something is sent to them. In any shared environment topics should be created deliberately, with the right partitions and retention.

## 5. Seeing the events

1. Subscribe to a product (see [`tests/products/subscribe.md`](tests/products/subscribe.md)).
2. Open Kafka UI, **Topics**, `product-subscription.activated`, **Messages**. The message is there, with the key and JSON described in [§1.1](#11-the-message).
3. The Node's log also states it:

```
Event published type=product-subscription.activated eventId=7f3c... topic=product-subscription.activated key=FEDERATOR_BCC partition=0 offset=0
```

## 6. Delivery: what is and is not guaranteed

Publishing is deliberately simple: **fire and forget**. A subscription is recorded and the event is sent; the Node does not wait to learn whether anyone acted on it.

| Situation | What happens |
|---|---|
| The subscription is committed | The event is sent once, after the commit. |
| The subscription is refused or rolled back | No event is sent. |
| Kafka is switched off | Nothing is sent; the subscription is unaffected. |
| The broker cannot be reached | The producer keeps retrying in the background for up to two minutes (Kafka's `delivery.timeout.ms`). If Kafka is back in that time the event is delivered late. Otherwise the failure is logged (`Event not published ...`) at `ERROR`. **The subscription stays recorded** either way. |
| The broker rejects the record | The failure is logged (`Event not published ...`) at `ERROR`. **The subscription stays recorded.** |
| The Node stops between the commit and the send | The event is lost. |

Two things follow, both known and accepted for now:

- **An event can be missed.** There is no outbox, and no retry beyond the producer's own two minutes. The subscription row is the source of truth and can be used to find subscriptions whose event was missed. The usual remedy, if this matters, is the *transactional outbox* pattern: the event is written to a table in the same transaction as the subscription and sent from there.
- **A broker that is down is noticed late.** Once the Node has talked to the broker, a send returns at once and the producer retries in the background. `Event not published ...` is logged only after the two minutes have passed. A short Kafka outage therefore loses nothing; a longer one loses the events sent during it. If the broker is already down when the Node first tries to send (it has no metadata yet), the send waits up to `max.block.ms` (5 seconds) and then fails, so that one response can be that much late.

The Node is also not told whether the receiver managed to act on the event, so a subscription can exist without the access it implies. A reply event from the receiver (and a status on the subscription, such as `PENDING`, `ACTIVE`, `FAILED`) is the natural next step. The event already carries `eventId` and `subscriptionId` so that a reply can be matched to it without changing this message.

## 7. How it is built

```
ProductSubscriptionServiceImpl.subscribe          (in the subscription's transaction)
   saves the subscription
   publishes a Spring event: ProductSubscriptionActivatedEvent
                 │
                 │  transaction commits
                 ▼
ProductSubscriptionEventListener                  (@TransactionalEventListener, AFTER_COMMIT)
   asks the publisher to send it to the configured topic, keyed by the consumer's client
                 │
                 ▼
EventPublisher                                    (one bean, chosen by KafkaEventConfig)
   ├── KafkaEventPublisher   application.kafka.enabled=true:  JSON via KafkaTemplate
   └── NoOpEventPublisher    otherwise:                       does nothing
```

- The event is built **inside** the transaction. The product's producer and the consumer's organisation are loaded lazily and cannot be read once the transaction has ended, so the listener is handed a plain snapshot, not entities.
- `EventPublisher.publish` never throws. What the Node does must not depend on whether anyone heard.
- The event is written as JSON text by the application's own `ObjectMapper` (ISO-8601 timestamps, no Java type headers), then sent with Spring's `KafkaTemplate`. Connection, serialisers and the consumer group are Spring Boot's own `spring.kafka.*`.

Code: `config/KafkaEventConfig`, `config/KafkaEventProperties`, `model/event/*`, `service/providers/event/*`.

## 8. Adding another event

1. Add a record under `model/event` that implements `DomainEvent`.
2. Add its topic to `KafkaEventProperties.Topics` and to `application.yml`.
3. Raise it where it happens with `ApplicationEventPublisher.publishEvent(...)`, built inside the transaction.
4. Add a listener like `ProductSubscriptionEventListener` that sends it with `EventPublisher`.
5. Document it in [§1](#1-what-is-published) and add test cases.

Event names follow *context.resource.action.status* where they can, for example `product.subscription.activated`; the first event keeps the name it was specified with.

## 9. Tests

| Test | What it covers |
|---|---|
| `ProductSubscriptionServiceImplTest` | A successful subscription raises one event with the right content; a refused one raises none. |
| `ProductSubscriptionEventListenerTest` | The event is handed to the publisher on the configured topic, keyed by the consumer's client. |
| `ProductSubscriptionEventTransactionTest` | The event is sent after a commit, and not at all after a rollback. |
| `KafkaEventPublisherTest` | The JSON on the topic; failures (broker rejects, send fails, cannot serialise) never throw. |
| `NoOpEventPublisherTest` | Switched off, publishing is harmless. |
| `KafkaEventConfigTest` | Off unless switched on; exactly one publisher either way; topic name and default. |
| `KafkaEventPublishingIntegrationTest` | The event arrives on a real (embedded) broker, as JSON, with the right key. |

```bash
./mvnw test -Dtest='ProductSubscription*Test,KafkaEvent*Test,NoOpEventPublisherTest'
```

Manual test cases for testers are S17 to S20 in [`tests/products/subscribe.md`](tests/products/subscribe.md).

## 10. Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| No message appears in Kafka UI | `KAFKA_ENABLED` is not `true` (check the startup log line), or the subscription was refused. |
| `Event not published ... TimeoutException: Topic ... not present in metadata` | The broker is not reachable at `KAFKA_BOOTSTRAP_SERVERS`. Check `docker compose -f docker/kafka/docker-compose.yml ps`. |
| The Node cannot connect although the container is running | From the host use `localhost:9092`, not `kafka:29092`; the latter only resolves inside Docker. |
| Kafka UI shows the cluster as offline | Give it a minute after `up -d`, it waits for the broker to be healthy. |
| Port 9092 or 8085 already in use | Set `KAFKA_PORT` or `KAFKA_UI_PORT`. |