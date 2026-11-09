# Smoke test local (Docker Compose + fcourt-web)

Kết quả chạy thật ngày 2026-11-09 trên `dev` (sau #239, web #26): 12 service + hạ tầng bằng Compose, web chạy `npm run dev`.

## Khởi động

```bash
# Backend: lần đầu build 10-20 phút (Maven trong từng Dockerfile)
COMPOSE_PARALLEL_LIMIT=2 docker compose --profile all build
docker compose --profile all up -d --no-build
bash tools/compose-seed.sh      # mật khẩu dev + staff + tài khoản tạo bằng API
bash tools/compose-smoke.sh     # in bảng dưới đây

# Web (repo fcourt-web, nhánh dev)
echo "VITE_API_URL=http://localhost:8080/api" > .env.local
npm ci && npm run dev           # http://localhost:5173, gọi thẳng gateway (CORS đã mở cho 5173 và 3000)
```

Gateway cần 30 đến 60 giây sau khi lên mới thấy các service trong Eureka (lúc đó trả 503). `compose-seed.sh` tự chờ.

## Tài khoản (chỉ dùng local, mật khẩu chung `Fcourt@123456`)

| Role | Tài khoản | Ghi chú |
|---|---|---|
| Guest | không cần | dùng số điện thoại khi giữ chỗ |
| CUSTOMER | `customer01@demo.com` đến `customer05@demo.com`, `customer.local@fcourt.test` | `customer.local` đăng ký bằng API |
| STAFF | `staff01@demo.com` (chi nhánh 1), `staff02` (2), `staff03` (9), `staff04` (15), `staff05` (16), `staff.local@fcourt.test` | `staff.local` tạo bằng API `POST /api/admin/staff` (chi nhánh 1); staff01 đến 05 gắn chi nhánh bằng SQL vì seed Flyway của staff-operations rỗng |
| ADMIN (chủ sân) | `owner01@demo.com` (chi nhánh 1 và 6), `owner02` đến `owner05`, `owner06` đến `owner08` (được duyệt bằng API), `owner.local@fcourt.test` | `owner.local` đăng ký multipart rồi super admin duyệt bằng API; có sẵn chi nhánh, sân, khung giờ, giá và gói đăng ký từ seed Flyway |
| SUPER_ADMIN | `superadmin@demo.com` | từ seed Flyway của auth |

## Kết quả qua gateway `http://localhost:8080`

| Role | Request | HTTP |
|---|---|---|
| guest | GET /api/public/branches, /api/public/branches/1, /branches/1/courts, /branches/1/reviews | 200 |
| guest | GET /api/public/courts/21/slots?date=... | 200 |
| guest | GET /api/court-types, /api/subscription-plans/all | 200 |
| guest | GET /actuator/health, /docs/auth-service/v3/api-docs | 200 |
| guest | GET /api/auth/profile (không token) | 401 (đúng) |
| guest | POST /api/bookings/hold-slots | 201 |
| guest | POST /api/bookings (header X-Hold-Token, Idempotency-Key) | 201 |
| guest | POST /api/public/bookings/lookup (code + phone) | 200 |
| guest | POST /api/public/bookings/cancel | 200 |
| guest | POST /api/public/bookings/lookup sau khi huỷ | 200 |
| customer | GET /api/auth/profile, /api/bookings/my, /api/payments/my-transactions | 200 |
| owner | GET /api/auth/profile, /api/admin/branches, /admin/branches/1/courts | 200 |
| owner | GET /api/admin/courts/21/time-slot-templates, /api/admin/bookings, /api/admin/dashboard/stats | 200 |
| owner | GET /api/admin/payments/pending, /payments/transactions, /refunds/pending (`?branchId=1`) | 200 |
| owner | GET /api/admin/staff, /admin/pos/products (`?branchId=1`), /admin/vouchers | 200 |
| owner | GET /api/subscriptions/my-subscription | 200 |
| owner | POST /api/admin/courts/21/image (multipart `file`), DELETE cùng đường dẫn | 200 / 200 |
| owner mới | GET /api/auth/profile, /api/admin/branches | 200 |
| staff | GET /api/auth/profile, /api/staff/me, /staff/branches/1, /staff/branches/1/courts | 200 |
| staff | GET /api/staff/schedule/today, /api/staff/pos/products?branchId=1 | 200 |
| staff mới | GET /api/staff/me | 200 |
| superadmin | GET /api/auth/profile, /super-admin/users, /court-admins/pending | 200 |
| superadmin | GET /api/super-admin/dashboard/overview, /dashboard/details, /pos/products | 200 |
| (CORS) | OPTIONS /api/auth/login, Origin `http://localhost:5173` | 200, `Access-Control-Allow-Origin: http://localhost:5173` |

Web (Vite dev, port 5173): `/`, `/lookup`, `/auth/login`, `/branches` trả 200. Các lời gọi API của từng trang được gọi lại bằng curl với token của role tương ứng như bảng trên; chưa có trình duyệt tự động nên chưa bấm thử giao diện.

## Điều đã gặp và cách xử lý

- 400 ở `/admin/payments/transactions`, `/admin/refunds/pending`, `/admin/staff`, `/admin/pos/products`: các API này bắt buộc `branchId` (web luôn gửi). Không phải lỗi backend.
- 403 `No active staff assignment` ở `/api/staff/me`: seed Flyway của staff-operations không có dòng staff. `compose-seed.sh` thêm bằng SQL.
- Tạo staff bằng API: mật khẩu tối thiểu 12 ký tự, `Idempotency-Key` phải là UUID. Vì vậy mật khẩu chung là `Fcourt@123456`.
- Gateway giới hạn đăng nhập 10 lần/phút/IP, quá chặt khi thử nhiều role từ một máy. Compose đặt `RATE_LIMIT_AUTH_REQUESTS=120`.
- LocalStack 3.8.1 không chạy được trên Docker Desktop của máy dev này (`File name too long`), nên Compose dùng MinIO (cùng ảnh với Testcontainers của backend).
- booking-service cần `SPRING_DATA_REDIS_HOST/PORT` (không đọc `REDIS_HOST`).

## Chưa kiểm tra

Thanh toán thật (PayOS, upload biên lai), gửi mail qua Mailpit, đăng nhập Google, push, STOMP realtime, ảnh public và presigned từ S3 (đã có bucket policy local, xem docs/docker-compose.md).
