# Stripe Hosted Checkout Production Design

## 1. Objective

Make the existing Stripe Hosted Checkout integration production-ready for overseas users while preserving Joysong's two-stage medical order flow.

The supported launch scope is:

- one-time payments only;
- Stripe Hosted Checkout;
- USD only;
- card only;
- consultation fee followed by remaining balance;
- Flutter on Android and iOS;
- complete Chinese and English UI copy.

PayPal, subscriptions, multi-currency pricing, PaymentSheet, wallets, local Chinese payment methods, and Stripe Connect are outside this scope.

## 2. Business Payment Model

One business order can contain two successful payments. A Stripe Checkout Session represents one attempt for one payment stage, not the whole order.

| Order source state | Payment type | Amount snapshot | Success state |
| --- | --- | --- | --- |
| `PENDING_PAYMENT` | `CONSULTATION_FEE` | `consultation_fee_minor` | `CONSULTATION_PAID` |
| `VERIFIED` | `BALANCE` | `remaining_amount_minor` | `BALANCE_PAID` |

Each stage may have multiple attempts but at most one successful attempt. A failed, cancelled, or expired balance attempt must not modify the successful consultation-fee payment.

The authoritative order lifecycle remains `doc/order_dispute_flow.puml`. The detailed consultation-fee payment sequence is documented in `doc/stripe_consultation_fee_payment_flow.puml`.

## 3. Trust Boundaries

- The server derives the order owner, payment stage, amount, and currency from persisted order snapshots.
- The Flutter client sends a payment type and provider selection but cannot set the charged amount or declare success.
- Stripe card data is collected only by Hosted Checkout.
- A success or cancel redirect is a navigation signal, not a payment result.
- Only a verified Stripe webhook or a server-side Stripe query reporting a paid Checkout Session can mark a payment successful.
- A successful result must include matching provider payment ID, local payment ID, stage, USD amount, and currency.
- Secrets remain in server-side production secret storage and must never be logged or shipped in either mobile app.

## 4. Server Design

### 4.1 USD order snapshots

The order pricing path must persist USD snapshots before payment can be created. Existing hard-coded CNY order creation is a production blocker and must be removed for this launch path.

The payment layer must reject any Stripe payment whose persisted order currency is not `USD`. No runtime currency conversion occurs at payment time.

### 4.2 Checkout Session creation

Stripe Session creation must include:

- `mode=payment`;
- `payment_method_types[]=card`;
- `currency=usd`;
- the server-derived minor-unit amount;
- stage-specific English product text for consultation fee or remaining balance;
- `payment_id`, `order_id`, and `payment_type` in Session and PaymentIntent metadata;
- a stable Stripe idempotency key.

The Stripe gateway accepts only the `CARD` payment method for this release.

### 4.3 Production configuration

Production startup must fail when any of these constraints is violated:

- `PAYMENT_MODE` is not `live`;
- Stripe is disabled;
- a test secret key is configured;
- the webhook secret is missing or malformed;
- success or cancel URLs are not public HTTPS URLs;
- the Stripe API version is blank;
- the Stripe API base is not exactly `https://api.stripe.com`;
- webhook tolerance or HTTP timeout values are outside approved ranges.

Test and development environments may use a mock API base, but production may not send the Stripe Authorization header to a configurable host.

### 4.4 Webhook processing

The webhook endpoint verifies the exact raw request bytes before JSON parsing. It validates the signature timestamp, event type, Checkout Session object type, and live/test mode.

Verified events are stored with a provider event ID uniqueness constraint. Repeated events return success without repeating order transitions.

On a paid event, the persistence transaction locks the payment and order, validates all monetary and stage invariants, then atomically:

1. marks the payment `SUCCEEDED`;
2. updates the applicable paid amount;
3. advances the order to the stage-specific state;
4. writes an order status log;
5. marks the event processed.

An invariant mismatch must not advance the order. It enters a retry or manual-review path with correlated identifiers and no sensitive payload data in logs.

### 4.5 Query, retry, and refund safety

Provider refresh requests are throttled server-side so mobile polling cannot produce an unbounded number of Stripe API calls.

Stripe HTTP calls use explicit connection and response timeouts. Unknown create outcomes remain `PROCESSING` and reuse the original idempotency key.

Refunds are executed against the original successful payment records. If both stages were paid, a full order refund creates separate refund items for the consultation fee and balance PaymentIntents. Unknown refund outcomes are reconciled by provider data and stable refund metadata rather than unlimited POST retries.

The runtime control for creating new payments must be separable from webhook/query handling, so an incident can stop new Checkout Sessions without losing callbacks for existing sessions.

## 5. Flutter Design

### 5.1 Order entry points

The order detail page exposes payment only in valid stages:

- `PENDING_PAYMENT`: consultation-fee action;
- `VERIFIED`: balance action;
- all other states: no payment action.

The action opens the shared payment page with the correct `PaymentType`. Android and iOS use the same controller and repository contract.

### 5.2 Payment page

The provider selector remains visible for future expansion. For this launch, only the server/build-enabled Stripe card option is displayed. Unimplemented providers are not shown.

The page displays:

- whether the user is paying the consultation fee or remaining balance;
- a server-backed `$xx.xx USD` amount;
- the selected `Card (Stripe)` provider;
- a stage-specific primary command;
- a clear status area for ready, opening Checkout, awaiting action, processing, succeeded, failed, cancelled, and expired states.

The page must not calculate exchange rates or render a CNY symbol for this flow.

### 5.3 Hosted Checkout and app lifecycle

The mobile launcher accepts only HTTPS redirect URLs on the approved Stripe Checkout host list and opens them in the system browser.

After the external browser is launched, the local page remains pending. When Android or iOS resumes, the controller queries the server. It never infers success from browser launch completion or a success redirect.

State behavior is:

| Server state | Mobile behavior |
| --- | --- |
| `SUCCEEDED` | Show success and return to a refreshed order |
| `PROCESSING` | Show confirmation in progress and allow controlled refresh |
| `REQUIRES_ACTION` | Allow reopening the same Checkout Session |
| `FAILED` | Show a localized error and permit a new attempt when safe |
| `CANCELLED` | Show cancellation without claiming payment |
| `EXPIRED` | Explain expiry and permit a new attempt |

Double taps are blocked while an operation is active. Retries receive a new client idempotency key only after the server confirms that the previous attempt is terminal.

### 5.4 Chinese and English support

Every user-visible payment string must exist in Chinese and English, including:

- stage labels and amounts;
- provider names;
- primary and secondary commands;
- processing, cancellation, expiry, success, and failure messages;
- browser-launch and network errors;
- retry and return-to-order actions.

Raw server or Stripe error text is not displayed directly. Known error codes map to localized copy; unknown codes use a localized generic message.

Text must fit on supported Android and iOS phone widths without overlap or clipped primary actions.

## 6. Failure Handling

- A definitive Stripe create failure marks the attempt failed and presents a retryable localized error when appropriate.
- A transport timeout is an unknown outcome, so the client displays processing and queries the existing attempt.
- Closing or cancelling Checkout never produces local success.
- A delayed webhook is resolved by app refresh or scheduled reconciliation.
- Duplicate and out-of-order events cannot reduce a successful state or add paid amount twice.
- A second success for the same business stage does not advance the order again; it raises a payment anomaly for manual refund review.
- Reconciliation exhaustion moves the record to a visible manual-review condition rather than retrying forever.

## 7. Testing

### 7.1 Server tests

Add or extend tests for:

- USD and card-only Session payloads;
- consultation-fee and balance metadata and product names;
- stage validation and server-derived amounts;
- test key rejection in production;
- production API host and API version validation;
- webhook signature, timestamp, live mode, raw payload, replay, and concurrency behavior;
- missing or mismatched successful-result amount and currency;
- Stripe timeouts, 429 responses, 5xx responses, and invalid JSON;
- two-stage paid amount transitions;
- split refunds and unknown refund outcomes;
- provider refresh throttling.

### 7.2 Flutter tests

Add controller and widget tests for:

- correct consultation-fee and balance entry points;
- USD amount rendering in Chinese and English;
- visible and extensible provider selector;
- double-tap prevention;
- allowed and rejected redirect hosts;
- browser cancellation and unavailable launcher states;
- Android/iOS lifecycle resume behavior through the shared controller;
- processing, continuation, expiry, retry, and success states;
- returning success to the order page and refreshing the order;
- narrow phone layouts in both supported languages.

### 7.3 Sandbox and live acceptance

Sandbox acceptance covers normal success, 3DS success, 3DS decline, user cancellation, expiry, delayed webhook, webhook replay, consultation-fee payment, balance payment, and refunds.

After sandbox acceptance, a controlled live USD order completes consultation-fee and balance payments with a real card, followed by refund verification. Stripe Dashboard data must match local payments, payment events, order status logs, and refund items.

## 8. Documentation Deliverables

Refactor the existing payment guide so the production path is explicit and executable:

- clearly separate current Stripe capability from future providers;
- document the two-stage order model;
- provide test and live configuration tables;
- document Dashboard card and webhook settings;
- include local CLI forwarding and sandbox test cases;
- provide deployment, observability, secret rotation, incident shutdown, webhook replay, refund, reconciliation, rollback, and final acceptance checklists;
- link both PlantUML flow documents.

## 9. Completion Criteria

The work is complete only when:

- all production blockers in this design are resolved or explicitly marked as external Dashboard prerequisites;
- focused server and Flutter payment tests pass;
- broader affected test suites pass;
- Android and iOS payment flows are verified in Chinese and English;
- the updated guide contains no unresolved placeholders or contradictory currency/provider instructions;
- no real Stripe credentials are committed.
