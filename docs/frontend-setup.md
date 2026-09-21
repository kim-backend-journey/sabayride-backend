# Frontend setup — Flutter & Dashboard

**You do not need the backend running. You are never blocked by it.**

Everything here works from the API contract alone:

```
https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml
```

Always read it from that URL. Never copy the file into your own repo — it goes
stale within days and you end up building against fields that no longer exist.

---

## Who builds what

| | Platform | Users | Builds |
|---|---|---|---|
| **Flutter app** | Android | Customers **and** shops | Search, booking, my bookings · Shop requests, accept, decline |
| **Web dashboard** | Browser | **Our team only** | Verify shops, suspend, all bookings, customers, metrics |

**Shops use the phone app, not the dashboard.** A shop owner adds bikes,
answers booking requests and hands over keys from their phone.

**The dashboard is our internal admin tool.** No customer and no shop ever sees
it.

---

# Part 1 — Setup (both teams)

## Step 0 — Install Node.js (once)

Everything below uses `npx`, which ships with Node.

<https://nodejs.org> → LTS version → install → **reopen your terminal**.

```bash
node -v      # v20 or higher
```

## Step 1 — Start the fake backend

```bash
npx @stoplight/prism-cli mock https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml
```

First run downloads Prism (a minute). Then it prints every route it serves:

```
[CLI] …  Prism is listening on http://127.0.0.1:4010
[CLI] ›  GET   http://127.0.0.1:4010/api/v1/motorbikes/search
[CLI] ›  POST  http://127.0.0.1:4010/api/v1/bookings
…
```

**Read that list** — it shows the exact URLs to call. Copy from there rather
than guessing.

This runs on **your** computer. It is not something anyone has to start for you.
Leave the terminal open; it's your server.

## Step 2 — Check it responds

Paste into a browser:

```
http://127.0.0.1:4010/api/v1/motorbikes/search?startDate=2026-10-05&endDate=2026-10-07
```

You should get a Honda Dream from "Angkor Moto Rent".

## Step 3 — Put the base URL in ONE place

You will change this at least three times. Do not scatter it through your code.

**Flutter**
```dart
// lib/config/api_config.dart
class ApiConfig {
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:4010/api/v1',
  );
}
```

**Dashboard**
```ts
// src/config.ts
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://127.0.0.1:4010/api/v1";
```

## Step 4 — Read the contract like documentation

<https://editor.swagger.io> → **File → Import URL** → paste the contract URL.

All 87 endpoints, every field, every example, every error code. Keep the tab
open while you build; it answers most questions faster than asking.

## Step 5 — Postman (optional but recommended)

**Import → Link →** paste the contract URL.

You get a ready-made collection of all 87 requests. Set a `baseUrl` variable:

| Environment | Value |
|---|---|
| Mock | `http://127.0.0.1:4010/api/v1` |
| Real backend | `http://localhost:8082/api/v1` |

One dropdown switches between fake and real.

## Step 6 — Generate a typed client (optional)

Needs Java installed. Skip it if that's a hassle — hand-written models are fine,
just keep field names exact.

```bash
# Flutter
npx @openapitools/openapi-generator-cli generate \
  -i https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml \
  -g dart-dio -o lib/api

# Dashboard
npx @openapitools/openapi-generator-cli generate \
  -i https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml \
  -g typescript-axios -o src/api
```

---

# Part 2 — Flutter: what to build

These **already work on the real backend**. When you switch over, you get live
data immediately.

### Customer

| Screen | Endpoint |
|---|---|
| Register | `POST /auth/register` |
| Verify phone | `POST /auth/verify-phone` |
| Login | `POST /auth/login` |
| Search results | `GET /motorbikes/search` |
| Model detail | `GET /motorbikes/models/{shopId}/{model}` |
| Booking summary | `POST /bookings/quote` |
| Confirm booking | `POST /bookings` |
| My bookings | `GET /bookings` |

### Shop

| Screen | Endpoint |
|---|---|
| Booking requests | `GET /shops/me/bookings?status=PENDING` |
| Accept | `POST /shops/me/bookings/{id}/confirm` |
| Decline | `POST /shops/me/bookings/{id}/reject` |

**Not yet:** payment, handover, return, reviews, Explore. Later slices.

### Two screens worth extra care

**Search results.** `startDate` and `endDate` are **required**. A model with
nothing free is simply absent from the results — don't expect a row with
`availableUnits: 0`. Show "No bikes available 5–7 Oct" when `content` is empty.

**Booking confirmation.** This is where `409 NO_UNITS_AVAILABLE` happens — the
bike was taken while the user was deciding. Don't show a generic error; take
them back to the date picker with a clear message.

---

# Part 3 — Dashboard: what to build

**These are not implemented on the backend yet. That does not block you.** The
mock returns realistic data for every one of them, so build the full UI now. It
will work the day the backend catches up.

| Screen | Endpoint |
|---|---|
| Shop applications → approve / reject | `GET /admin/shops` · `POST /admin/shops/{id}/verify` |
| Suspend a shop | `POST /admin/shops/{id}/suspend` |
| All bookings | `GET /admin/bookings` |
| Customers + suspend | `GET /admin/customers` · `POST /admin/customers/{id}/suspend` |
| Platform metrics | `GET /admin/metrics` |
| Failed payouts + retry | `GET /admin/payouts/failed` · `POST /admin/payouts/{id}/retry` |

**Start with shop verification.** It's the screen that matters most — no shop can
receive a booking until an admin approves it, so nothing else on the platform
works without it.

---

# Part 4 — Four rules

### 1. Money is a **string**, not a number

```json
{ "subtotal": "16.00", "platformFee": "1.60" }
```

JSON numbers are floating point and lose cents — `18.00` can come back as
`18.000000000000004`.

```dart
Decimal.parse(json['subtotal']);   // ✅  add `decimal` to pubspec.yaml
double.parse(json['subtotal']);    // ❌  never
```
```ts
new Decimal(data.subtotal);        // ✅  decimal.js
Number(data.subtotal);             // ❌
```

### 2. Switch on `error.code`, never `error.message`

```json
{ "code": "NO_UNITS_AVAILABLE", "message": "That bike was just booked..." }
```

Messages get reworded and translated. Codes are a contract.

```dart
switch (error.code) {
  case 'NO_UNITS_AVAILABLE': backToDatePicker();  break;
  case 'PHONE_NOT_VERIFIED': goToVerifyScreen();  break;
  default:                   showMessage(error.message);
}
```

Always keep a `default` — new codes get added.

### 3. `endDate` is **exclusive**

`startDate: 2026-10-05` + `endDate: 2026-10-07` is a **2-day** rental, returning
on the 7th.

Send exactly what the user picked. Don't add a day.

### 4. Missing a field? Ask — don't work around it

If a screen needs data no endpoint returns, that's a gap in the contract, not
something to patch locally. Message the group; the field gets added and everyone
benefits.

---

# Part 5 — Switching to the real backend

Change **only** the base URL:

| Where you are running | Base URL |
|---|---|
| Dashboard, browser on the same laptop as the backend | `http://localhost:8082/api/v1` |
| Flutter on an **Android emulator** | `http://10.0.2.2:8082/api/v1` |
| Flutter on a **real phone**, same wifi | `http://<backend-ip>:8082/api/v1` |
| Deployed (from ~week 3) | announced in the group |

```bash
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8082/api/v1
```
```bash
# dashboard .env.local
VITE_API_BASE_URL=http://localhost:8082/api/v1
```

**`10.0.2.2` is not a typo.** On an Android emulator, `localhost` means the
emulator itself, not your computer. `10.0.2.2` is Android's alias for the host
machine. This costs people an afternoon roughly once per project.

### Four things that change when you switch

**Screens may look empty.** The mock always returns the same example. The real
database returns what is actually in it. If search comes back `[]`, that is
probably correct — ask in the group for test data.

**Real errors appear.** The mock returns 200 for almost everything. The real
server returns `409` when a bike is gone, `400` on bad dates, `404` on an
unknown shop. Your error handling gets exercised for the first time. This finds
bugs you already had.

**Auth becomes real.** The mock ignores tokens. Once identity-service is live,
protected endpoints need a real JWT and expired tokens need refreshing.

**Data persists.** Book a bike on the mock and nothing happens. Book on the real
server and that bike is gone until it is cancelled.

**Stay on the mock for daily work even after the real server exists.** It is
faster, always available, and never depends on someone else's laptop being
awake. Switch to real when you specifically want to test real behaviour.

---

# Part 6 — Things that will confuse you

**Prism returns the same example every time.** Deliberate — predictable data is
easier to build against. For varied data: `npx @stoplight/prism-cli mock --dynamic <url>`

**Prism validates your requests.** A missing required field returns a real
validation error instead of pretending it worked. That is a feature; it catches
your bugs before the real backend does.

**CORS errors are the backend's problem.** If the browser console says "blocked
by CORS policy", say so in the group and move on. You cannot fix it from the
frontend.

**A `401` from the mock is normal** on endpoints that need a token. The mock does
not do real authentication. Build the screen; wire auth when login lands.

**`npx` says "command not found"** → Node.js isn't installed, or you didn't
reopen your terminal after installing it.
