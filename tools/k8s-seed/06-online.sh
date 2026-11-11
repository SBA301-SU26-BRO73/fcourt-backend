#!/usr/bin/env bash
# Phase 6: kiem tra luong dat san qua API (hold -> POST /api/bookings). Neu chay duoc thi bao de bo sung dat san that qua API.
# Hien booking-service/payment/slot-hold tren EKS thieu token service-to-service (BOOKING_HOLD_SERVICE_TOKEN, PAYMENT_BOOKING_SERVICE_TOKEN, ...)
# nen POST /api/bookings tra 503 "Online booking dependency unavailable"; du lieu dat san duoc seed bang SQL o phase 5.
log "6. Probe luong dat san qua API"
tok=$(login customer02@demo.com); D=$(date -d "+9 days" +%F)
SL=$(api GET "/api/public/courts/26/slots?date=$D" - | jq -c '[.[]|select(.status=="AVAILABLE" and (.startTime=="12:00:00" or .startTime=="12:30:00"))|.slotId]')
H=$(api POST /api/bookings/hold-slots "$tok" "{\"courtId\":26,\"date\":\"$D\",\"slotIds\":$SL}"); HT=$(echo "$H" | jq -r '.holdToken // empty')
if [ -n "$HT" ]; then
  api POST /api/bookings "$tok" "{\"holdToken\":\"$HT\",\"courtId\":26,\"date\":\"$D\",\"slotIds\":$SL}" -H "Idempotency-Key: $(uuid)" -H "X-Hold-Token: $HT" >/dev/null
  echo "   POST /api/bookings -> HTTP $(code)$([ "$(code)" = 503 ] && echo ' (thieu token service-to-service, xem ghi chu dau file)')"
  api DELETE "/api/bookings/hold-slots/$HT" "$tok" >/dev/null 2>&1 || true
else echo "   hold-slots khong tao duoc hold (HTTP $(code))"; fi
