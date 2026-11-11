#!/usr/bin/env bash
log "Dang nhap tung tai khoan qua gateway"
for acct in superadmin@demo.com owner0{1..8}@demo.com staff0{1..5}@demo.com customer0{1..5}@demo.com \
            owner.local@fcourt.test staff.local@fcourt.test customer.local@fcourt.test; do
  if t=$(login "$acct" 2>/dev/null) && [ -n "$t" ]; then s="OK"; else s=FAIL; fi
  echo "   $acct  $s"
done
