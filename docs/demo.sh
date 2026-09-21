#!/usr/bin/env bash
# docs/demo.sh — the SabayRide happy path, start to finish.
#
#   docker compose up -d db
#   ./mvnw spring-boot:run          (in another window)
#   bash docs/demo.sh
#
# Run this the night before the presentation, and again an hour before it.
# It is also your smoke test: if this passes, the system works.

set -uo pipefail

API=${API:-http://localhost:8082/api/v1}
DB=${DB:-sabayride-db}
SHOP=11111111-1111-1111-1111-111111111111

BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; RED=$'\033[31m'; OFF=$'\033[0m'

step () { echo; echo "${BOLD}── $* ${OFF}"; }
note () { echo "${DIM}   $*${OFF}"; }
pause() { echo; read -rp "${DIM}   [enter]${OFF}" _; }

# ═══════════════════════════════════════════════════════════════════════
step "0.  Reset to a clean slate"
# ═══════════════════════════════════════════════════════════════════════

docker exec -i $DB psql -U postgres -d sabayride -q <<'SQL'
TRUNCATE rental.booking_item, rental.booking CASCADE;
DELETE FROM rental.motorbike;
DELETE FROM rental.shop;
ALTER SEQUENCE rental.booking_reference_seq RESTART WITH 1;

INSERT INTO rental.shop (id, owner_id, name, phone, address, latitude, longitude,
                         status, offers_delivery, delivery_fee, rating, review_count)
VALUES ('11111111-1111-1111-1111-111111111111',
        '99999999-9999-9999-9999-999999999999',
        'Angkor Moto Rent', '+85512345678', 'Wat Bo Road, Siem Reap',
        13.361000, 103.860000, 'VERIFIED', true, 2.00, 4.6, 38);

INSERT INTO rental.motorbike (shop_id, brand, model, type, plate_number, price_per_day)
VALUES
 ('11111111-1111-1111-1111-111111111111','Honda','Honda Dream 125','SCOOTER','1AB-1001',8.00),
 ('11111111-1111-1111-1111-111111111111','Honda','Honda Dream 125','SCOOTER','1AB-1002',8.00),
 ('11111111-1111-1111-1111-111111111111','Honda','Honda Dream 125','SCOOTER','1AB-1003',8.00),
 ('11111111-1111-1111-1111-111111111111','Honda','Honda Scoopy','SCOOTER','2CD-2001',10.00);
SQL
note "1 verified shop · 3 Honda Dream · 1 Honda Scoopy"

# ═══════════════════════════════════════════════════════════════════════
step "1.  A traveller searches for 5-7 October"
# ═══════════════════════════════════════════════════════════════════════
note "Availability is COMPUTED for these dates. There is no 'available' column."

curl -s "$API/motorbikes/search?startDate=2026-10-05&endDate=2026-10-07" | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "2.  Opens the Honda Dream and sees the price"
# ═══════════════════════════════════════════════════════════════════════

curl -s "$API/motorbikes/models/$SHOP/Honda%20Dream%20125?startDate=2026-10-05&endDate=2026-10-07" \
  | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "3.  Quote before committing"
# ═══════════════════════════════════════════════════════════════════════
note "Commission is 10% of the RENTAL only. The delivery fee reimburses the"
note "shop for petrol and labour, so it is not commissionable."

BODY='{"shopId":"'"$SHOP"'","model":"Honda Dream 125","startDate":"2026-10-05",
       "endDate":"2026-10-07","pickupMethod":"SHOP_PICKUP","paymentOption":"PAY_AT_SHOP"}'

curl -s -X POST "$API/bookings/quote" -H "Content-Type: application/json" \
  -d "$BODY" | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "4.  Three travellers book. The fourth is too late."
# ═══════════════════════════════════════════════════════════════════════

for i in 1 2 3 4; do
  ID=$(printf '%08d-0000-0000-0000-000000000000' $i)
  printf "   customer %d  ->  " "$i"
  curl -s -X POST "$API/bookings" -H "Content-Type: application/json" \
    -H "X-Customer-Id: $ID" -H "X-Customer-Name: Traveller $i" -d "$BODY" \
    | python -c "import sys,json;d=json.load(sys.stdin);print(d.get('reference') or d.get('code'))"
done
note "3 bikes, 3 bookings, then NO_UNITS_AVAILABLE. Nobody got a 500."
pause

# ═══════════════════════════════════════════════════════════════════════
step "5.  Those dates are now sold out"
# ═══════════════════════════════════════════════════════════════════════
note "The Dream disappears; the Scoopy is untouched."

curl -s "$API/motorbikes/search?startDate=2026-10-05&endDate=2026-10-07" | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "6.  The shop sees its requests"
# ═══════════════════════════════════════════════════════════════════════
note "The shop sees assignedPlateNumber — it needs to know which bike."
note "customerPhone is ABSENT while PENDING, so a shop cannot reject the"
note "request and phone the customer directly. (FR-25)"

curl -s "$API/shops/me/bookings?status=PENDING" -H "X-Shop-Id: $SHOP" | python -m json.tool

B1=$(curl -s "$API/shops/me/bookings?status=PENDING" -H "X-Shop-Id: $SHOP" \
     | python -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
B2=$(curl -s "$API/shops/me/bookings?status=PENDING" -H "X-Shop-Id: $SHOP" \
     | python -c "import sys,json;print(json.load(sys.stdin)[1]['id'])")
pause

# ═══════════════════════════════════════════════════════════════════════
step "7.  Shop confirms the first"
# ═══════════════════════════════════════════════════════════════════════
note "holdExpiresAt disappears: the booking is committed, no longer held."

curl -s -X POST "$API/shops/me/bookings/$B1/confirm" -H "X-Shop-Id: $SHOP" \
  | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "8.  Shop rejects the second — and the bike is instantly bookable"
# ═══════════════════════════════════════════════════════════════════════

curl -s -X POST "$API/shops/me/bookings/$B2/reject" -H "X-Shop-Id: $SHOP" \
  -H "Content-Type: application/json" \
  -d '{"reason":"BIKE_UNAVAILABLE","note":"Went in for repair"}' > /dev/null

note "No inventory was released. No flag was reset. REJECTED is simply not one"
note "of the statuses that block, so the next query stops counting it."

curl -s "$API/motorbikes/search?startDate=2026-10-05&endDate=2026-10-07" | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "9.  The state machine refuses an illegal move"
# ═══════════════════════════════════════════════════════════════════════

curl -s -X POST "$API/shops/me/bookings/$B2/confirm" -H "X-Shop-Id: $SHOP" \
  | python -m json.tool
pause

# ═══════════════════════════════════════════════════════════════════════
step "10. The database itself refuses a double booking"
# ═══════════════════════════════════════════════════════════════════════
note "Bypassing the API, the service, and the lock — straight SQL."

UNIT=$(docker exec -i $DB psql -U postgres -d sabayride -tA -c \
  "SELECT assigned_motorbike_id FROM rental.booking WHERE status='CONFIRMED' LIMIT 1;")

OUT=$(docker exec -i $DB psql -U postgres -d sabayride 2>&1 <<SQL
INSERT INTO rental.booking (customer_id, customer_name, shop_id, status,
  start_date, end_date, assigned_motorbike_id, pickup_method, payment_option,
  subtotal, delivery_fee, total, commission_rate, platform_fee, shop_payout)
VALUES ('77777777-7777-7777-7777-777777777777','Hacker','$SHOP','CONFIRMED',
  '2026-10-06','2026-10-08','$UNIT','SHOP_PICKUP','PAY_AT_SHOP',
  16.00, 0, 16.00, 0.1000, 1.60, 14.40);
SQL
)

if echo "$OUT" | grep -q "no_double_booking"; then
  echo "   ${GREEN}REFUSED by the database:${OFF}"
  echo "$OUT" | grep -i "ERROR\|DETAIL" | sed 's/^/     /'
else
  echo "   ${RED}UNEXPECTED — the row was accepted:${OFF}"; echo "$OUT"
fi

note "PostgreSQL EXCLUDE constraint. Double-booking is not 'unlikely' here."
note "It is impossible, even with direct database access."

echo
echo "${BOLD}${GREEN}Demo complete.${OFF}"
echo
