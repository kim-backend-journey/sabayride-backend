# Frontend setup — Flutter & Dashboard

**You do not need the backend running. You are never blocked by it.**

Everything below works from the API contract alone, which is published here:

```
https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml
```

Always read it from that URL. Never copy the file into your own repo — it goes
stale within days and you end up building against fields that no longer exist.

---

## Step 0 — Install Node.js (once)

Everything here uses `npx`, which ships with Node.

https://nodejs.org → LTS version → install → **reopen your terminal**.

Check it worked:

```bash
node -v      # v20 or higher
npx -v
```

---

## Step 1 — Read the contract like documentation

Open <https://editor.swagger.io>, then **File → Import URL**, and paste the
contract URL.

You get a browsable list of all 87 endpoints with every field, every example and
every error code. Keep this tab open while you build — it answers most questions
faster than asking.

---

## Step 2 — Start the fake backend

```bash
npx @stoplight/prism-cli mock https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml
```

First run downloads Prism (a minute or so). Then it prints every route it is
serving:

```
[CLI] …  Prism is listening on http://127.0.0.1:4010
[CLI] ›  GET   http://127.0.0.1:4010/api/v1/motorbikes/search
[CLI] ›  POST  http://127.0.0.1:4010/api/v1/bookings
…
```

**Read that list.** It shows the exact URLs to call — copy them from there
rather than guessing.

Leave this terminal open. It's your server now.

---

## Step 3 — Check it responds

In a second terminal, or just paste into a browser:

```
http://127.0.0.1:4010/api/v1/motorbikes/search?startDate=2026-10-05&endDate=2026-10-07
```

You should get back a Honda Dream from "Angkor Moto Rent" — the example from the
contract. Same field names, same shapes, same types as the real server.

---

## Step 4 — Point your app at it

### Flutter

```dart
// lib/config/api_config.dart
class ApiConfig {
  // ONE place. You will change this at least three times.
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:4010/api/v1',
  );
}
```

Then switch environments without editing code:

```bash
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8082/api/v1
```

### Dashboard

```ts
// src/config.ts
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://127.0.0.1:4010/api/v1";
```

```bash
# .env.local
VITE_API_BASE_URL=http://127.0.0.1:4010/api/v1
```

**Do not scatter the base URL through your code.** One constant, read from an
environment variable, with the mock as the default.

---

## Step 5 (optional) — Generate a typed client

Saves writing model classes by hand, and they stay correct when the contract
changes.

**Flutter:**
```bash
npx @openapitools/openapi-generator-cli generate \
  -i https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml \
  -g dart-dio -o lib/api
```

**Dashboard:**
```bash
npx @openapitools/openapi-generator-cli generate \
  -i https://raw.githubusercontent.com/kim-backend-journey/sabayride-backend/main/docs/api/sabayride-api-v1.yaml \
  -g typescript-axios -o src/api
```

Needs Java installed. If that's a problem, skip it — hand-written models against
the contract are perfectly fine, just keep the field names exact.

---

## Switching to the real backend

When the backend is running (ask in the group first), change only the base URL:

| Where you are running | Base URL |
|---|---|
| Dashboard, browser on the same laptop | `http://localhost:8082/api/v1` |
| Flutter on an **Android emulator** | `http://10.0.2.2:8082/api/v1` |
| Flutter on a **real phone**, same wifi | `http://<backend-laptop-ip>:8082/api/v1` |
| Deployed (later) | announced in the group |

**`10.0.2.2` is not a typo.** On an Android emulator, `localhost` means the
emulator itself, not your computer. `10.0.2.2` is the special alias Android uses
for the host machine. This costs people an afternoon roughly once per project.

---

## Build these screens first (Slice 1)

The backend has these working for real. Everything else is a later slice —
please don't build payment, handover or admin screens yet.

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
| Shop: requests | `GET /shops/me/bookings?status=PENDING` |
| Shop: accept | `POST /shops/me/bookings/{id}/confirm` |
| Shop: decline | `POST /shops/me/bookings/{id}/reject` |

---

## Four rules

### 1. Money is a **string**, not a number

```json
{ "subtotal": "16.00", "platformFee": "1.60" }
```

JSON numbers are floating point and lose cents. `18.00` can come back as
`18.000000000000004`.

```dart
// Flutter — add `decimal` to pubspec.yaml
final subtotal = Decimal.parse(json['subtotal']);   // ✅
final subtotal = double.parse(json['subtotal']);    // ❌ never
```

```ts
// Dashboard
const subtotal = new Decimal(data.subtotal);        // decimal.js
```

### 2. Switch on `error.code`, never `error.message`

```json
{ "code": "NO_UNITS_AVAILABLE", "message": "That bike was just booked..." }
```

Messages get reworded and translated. Codes are a contract.

```dart
switch (error.code) {
  case 'NO_UNITS_AVAILABLE': showDatePickerAgain(); break;
  case 'PHONE_NOT_VERIFIED': goToVerifyScreen();    break;
  default:                   showMessage(error.message);
}
```

Always have a `default` branch — new codes get added.

### 3. `endDate` is **exclusive**

`startDate: 2026-10-05`, `endDate: 2026-10-07` is a **2-day** rental, returning
on the 7th.

When the user picks "5 Oct to 7 Oct" in a date picker, send exactly that. Don't
add a day.

### 4. Missing a field? Ask — don't work around it

If a screen needs data no endpoint returns, that's a gap in the contract, not
something to patch locally. Message the group; the field gets added and everyone
benefits.

---

## Things that will confuse you

**Prism returns the same example every time.** That's deliberate — predictable
data is easier to build against. For varied data, add `--dynamic`:

```bash
npx @stoplight/prism-cli mock --dynamic <url>
```

**Prism validates your requests.** A missing required field returns a real
validation error rather than pretending it worked. That's a feature: it catches
your bugs before the real backend does.

**CORS errors are the backend's problem, not yours.** If the browser console
says "blocked by CORS policy", say so in the group. Don't waste time on it — you
can't fix it from the frontend.

**A `401` from the mock is normal** on endpoints that need a token. The mock
doesn't do real authentication. Build the screen; wire real auth when login
lands.
