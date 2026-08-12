# Professional Wallet Flutter Design

## Goal

Provide every signed-in Flutter user with a Wallet entry under Profile while keeping revenue balances separated by their legal/business owner. Doctors, consultants, and institution legal representatives can inspect their USD balances and ledger entries. Users without an approved revenue-bearing identity see an explanatory empty state. Withdrawal remains unavailable and must not call a backend API.

## Scope

This change includes:

- exact backend API documentation and frontend-facing wallet value objects;
- a shared Flutter wallet page for doctors, consultants, and institution legal representatives;
- an identity selector when one account can access more than one wallet owner;
- USD balance and paginated ledger presentation;
- a Wallet entry for ordinary signed-in users with a non-financial empty state;
- compatibility fixes for the existing consumer order settlement card and its current backend response.

This change excludes:

- withdrawal requests or approval;
- Airwallex or any other payout provider;
- bank accounts, beneficiaries, KYC, FX, CNY, or payout status;
- merging balances belonging to different owner identities;
- creating a consumer wallet for an ordinary paying user.

## Ownership Model

Wallet ownership continues to use the database key `(owner_type, owner_id, currency)`.

| User capacity | `owner_type` | `owner_id` | Ownership meaning |
|---|---|---|---|
| Doctor | `DOCTOR` | Doctor ID | The doctor's personal professional earnings |
| Consultant | `CONSULTANT` | Authenticated user ID | The consultant's personal commission earnings |
| Institution legal representative | `INSTITUTION` | Institution ID | Earnings owned by the institution, not by the representative |

One login account may therefore access several independent wallets. An institution legal representative who manages several institutions receives one selector item per institution. Balances are never added together across owners.

All wallets exposed by this feature use `USD`. Amounts travel across the API as integer minor units and are formatted to two decimal places only at the presentation boundary.

## Navigation and Page Structure

Profile displays a Wallet entry for every signed-in user.

- A user with no approved doctor, consultant, or institution legal-representative scope enters the shared wallet page and sees an explanatory empty state. The backend does not create a `CUSTOMER` wallet.
- A user with one accessible wallet sees that wallet directly and no selector.
- A user with multiple accessible wallets sees an identity selector at the top.
- Selector labels combine wallet type and owner name, for example `医生钱包 · 张医生` or `机构钱包 · 娇颜颂医疗机构`.
- Selecting another identity replaces the complete balance summary and resets ledger pagination to page zero.
- The main balance is the available balance. Pending and frozen balances appear as secondary values.
- A Withdrawal button remains visible. Pressing it shows `提现功能即将开放` and performs no network request.
- Ledger entries appear beneath the balances, support pull-to-refresh and incremental pagination, and use a stable newest-first order.

The same page and components serve all three professional roles. Role-specific differences are limited to selector labels, owner names, authorization scope, and backend-provided ledger copy.

## Backend API and Value Objects

### Wallet overview

`GET /api/wallets/me`

The server derives all wallet scopes from the authenticated actor. The request accepts no owner identifier.

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "currency": "USD",
    "wallets": [
      {
        "walletId": 101,
        "ownerType": "DOCTOR",
        "ownerId": "doctor-id",
        "displayName": "DOCTOR",
        "ownerName": "张医生",
        "pendingMinor": 12000,
        "availableMinor": 85000,
        "frozenMinor": 0
      }
    ]
  }
}
```

`wallets` is empty for an authenticated user without an approved revenue-bearing identity. This is a successful response, not an authorization error.

The server supplies `displayName` as a stable semantic code matching `ownerType`, plus the human owner `ownerName`. Flutter localizes the wallet-type code and never infers institutional or professional names from IDs. Semantic codes are public presentation contract values, not internal `operationKey` values. The response contains no provider, payout, beneficiary, bank, or FX information.

### Wallet ledger

`GET /api/wallets/me/ledger?walletId={walletId}&page={page}&size={size}`

`walletId` is required so a multi-identity account can request one wallet at a time. The server verifies the wallet belongs to the actor-derived scope. An inaccessible wallet returns 403 or 404 without revealing its balances.

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "id": 1001,
        "walletId": 101,
        "entryType": "SETTLEMENT",
        "title": "SETTLEMENT",
        "description": "ORDER:JS202608110001",
        "sourceType": "ORDER",
        "sourceId": "JS202608110001",
        "amountMinor": 12000,
        "pendingAfterMinor": 12000,
        "availableAfterMinor": 85000,
        "frozenAfterMinor": 0,
        "currency": "USD",
        "createdAt": "2026-08-11T10:30:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  }
}
```

`amountMinor` is signed: credits are positive and refund reversals are negative. `RELEASE` is a neutral pending-to-available bucket transfer and always returns `amountMinor: 0`, so it cannot be counted as a second income event. For wire compatibility, `title` equals `entryType` and `description` is the stable `sourceType:sourceId` semantic reference; `sourceType` and `sourceId` are also returned explicitly. Flutter localizes from `entryType`/`sourceType` and displays `sourceId`. These public semantic codes are not raw internal operation keys, and the API never exposes `operationKey`.

The page size is bounded server-side and ordering is deterministic by newest creation time followed by descending ID.

## Flutter State and Data Flow

The implementation follows the existing feature layering and state-management conventions:

1. The Profile wallet entry navigates to the wallet page.
2. A wallet remote data source requests the overview.
3. The controller selects the active wallet. It prefers the currently active professional identity when that mapping is available; otherwise it selects the first returned wallet.
4. The controller requests ledger page zero for the selected wallet.
5. Selector changes cancel or ignore stale responses, replace the balance model, clear previous ledger entries, and load page zero.
6. Pull-to-refresh reloads the overview and selected wallet ledger.
7. Infinite scrolling appends the next page once and stops when `last` is true.

No balance calculation uses `double`. Flutter keeps integer minor units and uses a USD-specific formatter for `$1,234.56` and `-$12.00`.

## User States and Error Handling

The page has explicit states:

- initial loading skeleton;
- professional wallet with balances and ledger;
- professional wallet with an empty ledger;
- no professional identity empty state;
- overview load failure with retry;
- ledger load failure with retry while retaining the current balance card;
- pagination failure with an inline retry action.

Network, parsing, and authorization errors must never be converted into a zero balance or empty ledger. Withdrawal always produces a local informational message and cannot fail as a network operation.

## Existing Consumer Settlement Compatibility

The ordinary user's order detail already contains a settlement card. This work does not redesign or relocate it. It updates Flutter parsing and presentation to the current consumer-safe response:

- `settlementId`
- `orderId`
- `currency`
- `grossTotalPaid`
- `netSettled`
- `state`
- `settlementDueAt`
- `settlementCreatedAt`
- `releasedAt`

The card does not reveal professional allocations or wallet balances. A 409 `SETTLEMENT_NOT_GENERATED` response is rendered as a pending-generation state; parsing and network failures remain visible and retryable instead of being silently discarded.

## Documentation

The implementation updates:

- the user-facing backend API document with exact request, response, authorization, paging, empty-state, and error examples;
- the Flutter API contract with the new wallet overview and ledger VO fields;
- generated/static OpenAPI descriptions when they are maintained by this repository.

Documentation must state that wallet balances are internal USD ledger balances and that the displayed Withdrawal action is not an implemented payout capability.

## Testing

Backend tests cover:

- doctor-only, consultant-only, and institution-only overview responses;
- one account with all three identities;
- one legal representative with multiple institutions;
- ordinary authenticated users receiving an empty overview;
- wallet names and USD minor-unit fields;
- wallet-specific ledger pagination and deterministic ordering;
- inaccessible wallet IDs returning no financial data;
- absence of payout/provider/bank/beneficiary fields in serialized VO types.

Flutter tests cover:

- overview and ledger JSON parsing;
- exact USD formatting without floating-point arithmetic;
- single-identity and multi-identity selector behavior;
- institution selector entries for multiple institutions;
- ordinary-user empty state;
- loading, retry, empty-ledger, refresh, and pagination states;
- local Withdrawal-coming-soon feedback and absence of a withdrawal request;
- compatibility of the existing consumer settlement card with the current backend contract.

## Acceptance Criteria

1. Every signed-in user can open Wallet from Profile.
2. Ordinary users see the approved empty-state copy and no synthetic balance.
3. Doctors, consultants, and institution legal representatives see only actor-derived wallets.
4. A multi-identity account can switch wallets without balances or ledger rows leaking between owners.
5. Every displayed amount is USD and derived from integer minor units.
6. Withdrawal displays a local coming-soon message and does not call the backend.
7. API failures are distinguishable from legitimate empty states.
8. The existing consumer settlement card works with the current consumer settlement VO.
9. No payout-provider or withdrawal implementation is introduced.
