# 📋 Billing Service — Task List

> **Готовність:** 95% (33 unit-тести ✅, 7 test-файлів) | **Критичність:** ВИСОКА
> **Java 25 / Spring Boot 4.1.0**
> **Повний roadmap:** [../DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md)

## ✅ Що вже добре

- OpenAPI contract-first: `src/main/resources/contracts/billing.yaml`
  → генерує `BillingApi` interface + DTO
  (`CheckoutRequest`, `CheckoutResponse`)
- **LiqPay** (`client/liqpay/LiqPayClient.java`) — JDK-only
  HMAC-SHA1 (no SDK), 2 ctor'и (production hardcodes
  LiqPay URL; test takes pre-built RestClient for
  MockWebServer)
- **Stripe** (`client/stripe/StripeClient.java`) —
  `com.stripe:stripe-java:32.0.0` для Checkout Session
  + `StripeWebhookVerifier` через
  `Webhook.constructEvent` (raw body, HMAC-SHA256)
- **Webhook dedup** через composite PK insert на
  `BILLING_WEBHOOK_EVENTS` (Phase 4.3 — re-deliveries
  повертають 200 OK без re-processing)
- **PDF invoice** через OpenPDF (UA/EN білінгва,
  ~2-15 KB), `BILLING_INVOICES.PDF_BLOB` =
  `VARBINARY(MAX)`
- **Auto-renewal** scheduler
  (`AutoRenewalScheduler`) — щоденний sweep з
  dunning (3 невдачі → `IS_ACTIVE=false` через
  subscription-service)
- **ShedLock** для distributed lock на N подах
  (V24 migration створює `shedlock` table)
- 33 тести в 7 файлах: `LiqPayClientTest`,
  `LiqPaySignatureServiceTest`, `StripeClientTest`,
  `StripeWebhookVerifierTest`,
  `BillingControllerStubTest`,
  `InvoicePdfGeneratorTest`,
  `PaymentRepositoryTest`, `AutoRenewalServiceTest`
- Валідація JWT через auth-lib
  (`CurrentUser.getUserId()`)
- DB-міграції: V23 (3 таблиці) + V24 (shedlock)
- SpringDoc OpenAPI з ідентичним до auth-lib
  security scheme

---

## 🧪 Тестування (розширити)

**33 тести (8 файлів), всі зелені:**

| Файл | Кількість | Що покриває |
|------|-----------|-------------|
| `LiqPaySignatureServiceTest` | 7 | HMAC-SHA1 (NIST test vector + edge cases) |
| `LiqPayClientTest` | 5 | RestClient wrapper, MockWebServer |
| `StripeClientTest` | 3 | Checkout Session URL, edge cases |
| `StripeWebhookVerifierTest` | 5 | HMAC-SHA256 (valid, bad sig, missing header, expired ts) |
| `BillingControllerStubTest` | 5 | JWT auth, 201/401/403/404 paths |
| `InvoicePdfGeneratorTest` | 1 | OpenPDF magic bytes, size range |
| `PaymentRepositoryTest` | 2 | JPA mapping |
| `AutoRenewalServiceTest` | 5 | Renewal + 3-strike dunning |

Розширити:

- [ ] Integration тести з Testcontainers (MSSQL) — **зараз 0**
- [ ] Code Coverage ≥ 80% (security-relevant для
  webhook signature verification) — зараз ~70%
- [ ] `@SpringBootTest` повний з Eureka client
  (поки що pure-Mockito через Spring Cloud 2025 +
  Boot 4.1 compat issue з
  `SimpleDiscoveryClientAutoConfiguration`)
- [ ] Тести для `SubscriptionServiceClient`
  (REST mock)

---

## 🐛 Відомі обмеження / Tech debt

- `AutoRenewalService.attemptRenewal()` — Phase 4.3
  stub. Phase 5+ потребує stored `customer_id` +
  `payment_method_id` для re-charge
  (Stripe `PaymentIntent.create` / LiqPay re-charge
  API). Наразі використовується
  `billing.renewal.simulate-fail=true` для
  тестування dunning.
- `SubscriptionServiceClient.deactivateSubscription()`
  очікує endpoint
  `POST /rest/ua.fin.api/subscriptions/internal/{id}/deactivate`
  в `subscription-service` — Phase 4.3 цього
  endpoint ще немає, помилки логуються як
  best-effort.
- `BillingControllerStubTest` використовує
  `addFilters = false` (JWT filter вимкнений). Phase
  5+ потребує Spring Security config з
  permitAll для `/webhooks/**` та JWT для
  `/checkout`, `/invoices/**`.
- OpenPDF dependency має LGPL — для комерційного
  SaaS це ОК (тільки notice у credits), але при
  статичному лінкуванні потрібен relinking clause.
- Ліmit на розмір PDF (`VARBINARY(MAX)`) — 2 GB.
  Phase 5+ може перейти на S3 / Azure Blob.

---

## 🚧 Phase 5+ Roadmap

- [ ] WebView для `ui-mobile` (відкриття
  `checkoutUrl`)
- [ ] Stored payment methods (реальне auto-renewal
  через `PaymentIntent.create` / LiqPay re-charge)
- [ ] S3 / Azure Blob для PDF
- [ ] Multi-currency (UAH + USD + EUR)
- [ ] Prometheus метрики
- [ ] Refund endpoint (`POST /refund`)
- [ ] Promocode у checkout flow
- [ ] Kafka `payment.succeeded` topic для
  notification-service
- [ ] i18n на PDF (UA/EN/PL/RU)
- [ ] Tax handling (EU OSS, UA ФОП/ТОВ)
- [ ] Stripe SDK major version upgrade
  (поки що pinned до 32.0.0)
