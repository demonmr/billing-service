# billing-service

Мікросервіс обробки реальних платежів по підписках через
**LiqPay (UA)** та **Stripe (EU/US)**. Єдине джерело
правди для таблиць:

- `SUBSCRIPTION_PAYMENTS` — спроби checkout (PENDING →
  SUCCEEDED / FAILED / REFUNDED)
- `BILLING_INVOICES` — 1:1 з успішним `SUBSCRIPTION_PAYMENTS`,
  PDF-інвойс у `VARBINARY(MAX)`
- `BILLING_WEBHOOK_EVENTS` — dedup-таблиця webhook-ів
  (PK = `(provider, event_id)`)

Phase 4.3 of `DEVELOPMENT_ROADMAP.md`.

## 🔗 Інтеграція

- **auth-lib** v1.0.3 — JWT-валідація на захищених
  ендпоінтах через `CurrentUser.getUserId()`
- **Eureka** — реєструється як `billing-service`
- **api-gateway** — `lb://billing-service` для
  `/rest/ua.fin.api/billing/**`
- **subscription-service** — service-to-service call
  через `X-Internal-Token` для dunning
  (deactivation of `IS_ACTIVE=false` after 3 dunning
  failures)
- **db-migration-service** — V23
  (`SUBSCRIPTION_PAYMENTS`, `BILLING_INVOICES`,
  `BILLING_WEBHOOK_EVENTS`), V24 (`shedlock`)

## Swagger / OpenAPI

`src/main/resources/contracts/billing.yaml` експонується як:

- **Swagger UI:** `GET /swagger-ui.html`
- **OpenAPI JSON:** `GET /v3/api-docs`

## 🚀 Responsibilities

- Створення hosted-checkout URL для обох провайдерів
  (`POST /rest/ua.fin.api/billing/checkout`).
- Верифікація та обробка webhook-ів:
  - **LiqPay:** HMAC-SHA1 over
    `privateKey + base64Data + privateKey`
  - **Stripe:** HMAC-SHA256 over
    `{timestamp}.{rawBody}` (через SDK
    `Webhook.constructEvent`)
- Генерація PDF-інвойсів (OpenPDF, UA/EN білінгва).
- Завантаження PDF (`GET /rest/ua.fin.api/billing/invoices/{paymentId}/pdf`).
- Щоденне авто-поновлення підписок з dunning
  (3 невдачі → деактивація батьківського
  `SUBSCRIPTIONS.IS_ACTIVE`).
- Distributed locking через ShedLock (запобігає
  подвійному спрацюванню на N подах).

## 🛠 Tech Stack

- **Java 25**, Spring Boot 4.1.0
- **MS SQL Server** (prod), **H2** (dev/test)
- **Spring Data JPA** + Hibernate
- **Spring Security (через auth-lib v1.0.3)**
- **SpringDoc OpenAPI** (Swagger)
- **Eureka client** (Spring Cloud 2025.0.0)
- **openapi-generator-maven-plugin 7.23.0** — генерує
  `BillingApi` interface + DTO
- **com.stripe:stripe-java 32.0.0** — Checkout
  Session + Webhook verification
- **com.github.librepdf:openpdf 2.0.2** — invoice
  generation (LGPL/MPL, free for commercial SaaS)
- **net.javacrumbs.shedlock 5.16.0** — distributed
  scheduler lock

## 🗄 Database Schema

Таблиці створюються міграціями в
[`db-migration-service`](../db-migration-service):

- **V23** — `SUBSCRIPTION_PAYMENTS`,
  `BILLING_INVOICES`, `BILLING_WEBHOOK_EVENTS`
- **V24** — `shedlock` (для ShedLock JDBC provider)

## 🛡 REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/rest/ua.fin.api/billing/checkout` | JWT | Створює `Payment` (PENDING) + повертає hosted-checkout URL |
| `POST` | `/rest/ua.fin.api/billing/webhooks/liqpay` | HMAC-SHA1 | Webhook від LiqPay (form-encoded `data` + `signature`) |
| `POST` | `/rest/ua.fin.api/billing/webhooks/stripe` | HMAC-SHA256 | Webhook від Stripe (raw body + `Stripe-Signature` header) |
| `GET`  | `/rest/ua.fin.api/billing/invoices/{paymentId}/pdf` | JWT | Завантаження PDF-інвойсу (403 якщо `userId` не збігається) |

## 🔧 Environment Variables

| Var | Default | Required | Description |
|-----|---------|----------|-------------|
| `DB_URL` | — | ✅ (prod) | JDBC URL SQL Server |
| `DB_USER_NAME` | — | ✅ (prod) | DB user |
| `DB_PASSWORD` | — | ✅ (prod) | DB password |
| `AUTH_SIGNING_KEY` | dev placeholder | ✅ (prod) | Base64 JWT signing key (must match auth-service) |
| `BILLING_LIQPAY_PUBLIC_KEY` | `sandbox_public_key` | ✅ (prod) | LiqPay merchant public key |
| `BILLING_LIQPAY_PRIVATE_KEY` | `sandbox_private_key` | ✅ (prod) | LiqPay merchant private key (server-side only) |
| `BILLING_LIQPAY_SANDBOX` | `true` | no | `true` for sandbox, `false` for prod |
| `BILLING_STRIPE_API_KEY` | `sk_test_placeholder` | ✅ (prod) | Stripe secret key |
| `BILLING_STRIPE_WEBHOOK_SECRET` | `whsec_placeholder` | ✅ (prod) | Stripe webhook signing secret |
| `BILLING_STRIPE_API_VERSION` | `2025-04-30.basil` | no | Pinned Stripe API version |
| `BILLING_SUBSCRIPTION_SERVICE_URL` | `http://subscription-service:8083` | no | subscription-service base URL |
| `BILLING_SUBSCRIPTION_SERVICE_INTERNAL_TOKEN` | `dev-internal-token` | ✅ (prod) | X-Internal-Token value |
| `BILLING_AUTO_RENEWAL_CRON` | `0 0 3 * * *` | no | 6-field cron (default: 03:00 daily) |
| `BILLING_RENEWAL_SIMULATE_FAIL` | `false` | no | `true` for dunning testing |

## 🏃 Local Run

```bash
# Requires: JDK 25, Maven 3.9+, H2 (in-memory, default profile)

cd E:/WORK/fin_app/billing-service
./mvnw spring-boot:run
```

Service starts on `http://localhost:8089`. H2 is the
default DB (no MSSQL needed for local dev).

## 🐳 Docker

`fin-app-docker/docker-compose.build.yml` includes
`billing-service` — port 8089, depends on
`db-migration`, `discovery-service`, `subscription-service`.

```bash
cd E:/WORK/fin_app/fin-app-docker
docker compose -f docker-compose.build.yml up billing-service
```

## 🧪 Tests

```bash
./mvnw test
```

33/33 tests green. Coverage spans:
- LiqPay signature (HMAC-SHA1) + client
- Stripe Checkout Session + webhook signature
  (HMAC-SHA256, manual construction)
- Webhook dedup
- Payment state transitions
- Invoice PDF rendering (magic-bytes + size)
- Auto-renewal orchestration + 3-strike dunning
- Controller auth (401/403/404 paths)

## 🧾 Open Source License Notice

`billing-service` uses **OpenPDF 2.0.2** (LGPL + MPL).
Per the LGPL, this product includes software developed
at <https://github.com/librepdf/openpdf>. No static
linking — OpenPDF is consumed as a Maven dependency.

## 📦 Next Steps (Phase 5+)

- Mobile WebView для hosted checkout
  (`ui-mobile` відкриває `checkoutUrl`)
- Реальне auto-renewal через stored
  `customer_id` + `payment_method_id`
  (Stripe `PaymentIntent.create` / LiqPay
  re-charge API)
- S3 / Azure Blob для PDF (якщо обсяг перевищить
  ~50 MB/день)
- Multi-currency (зараз UAH для LiqPay, USD для
  Stripe)
- Prometheus метрики
  (`billing_payment_total{provider,status}`,
  `billing_dunning_failure_count`)
- Промо-коди в checkout flow
- Refund endpoint
