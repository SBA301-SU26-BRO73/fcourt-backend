# FCOURT Backend

Source backend của FCOURT theo hướng microservices. Mỗi service nằm trong một folder riêng ở gốc repo và có database riêng.

Repo này chỉ cung cấp khung: folder từng service, script khởi tạo database và cách chạy database ở máy local. Thư viện, cấu trúc package, quy ước code và cấu hình framework do owner của từng service tự quyết định.

## Cấu trúc

```
fcourt-backend/
├── <service>/                  # một folder cho mỗi service
│   └── database/               # script khởi tạo database của service (nếu có)
│       ├── V1__init_schema.sql
│       └── V2__seed_sample_data.sql
├── docker/postgres/
│   ├── init-databases.sh       # tạo database và chạy script của từng service
│   └── services.txt            # danh sách service và tên database
├── docker-compose.yml          # PostgreSQL local
└── .env.example
```

## Danh sách service

| Folder | Database | Bảng khởi tạo (tách từ bản cũ) | Owner |
|---|---|---|---|
| `auth-service` | `auth_db` | `users`, `user_documents`, `user_account_action_logs` | _chưa điền_ |
| `subscription-service` | `subscription_db` | `subscription_plans`, `plan_features`, `subscriptions`, `subscription_payments`, `court_types` | _chưa điền_ |
| `facility-service` | `facility_db` | `branches`, `courts`, `time_slot_templates` (+ `facility_outbox`, xem `facility-service/README.md`) | Tuấn |
| `reporting-service` | `reporting_db` | read model `booking_facts`, `branch_snapshots`, `processed_events` (Flyway trong service) | Tuấn |
| `discovery-service` | `discovery_db` | chưa có, owner tự thiết kế | _chưa điền_ |
| `voucher-service` | `voucher_db` | `vouchers` | _chưa điền_ |
| `slot-hold-engine` | `slot_hold_db` | `slot_holds` | _chưa điền_ |
| `booking-service` | `booking_db` | `bookings`, `booking_slots` | _chưa điền_ |
| `payment-service` | `payment_db` | `payments`, `refund_requests` | _chưa điền_ |
| `staff-operations-service` | `staff_db` | `staff` | _chưa điền_ |
| `pos-retail-service` | `pos_retail_db` | `products`, `orders`, `order_items` | _chưa điền_ |
| `notification-service` | `notification_db` | chưa có, owner tự thiết kế | _chưa điền_ |

## Chạy database local

Cần Docker.

```bash
cp .env.example .env          # đổi mật khẩu nếu muốn
docker compose up -d
```

Lần chạy đầu tiên sẽ tạo mỗi service một database và chạy các script trong `<service>/database/` theo thứ tự `V1`, `V2`, ... Chạy lại từ đầu (xóa toàn bộ dữ liệu):

```bash
docker compose down -v && docker compose up -d
```

Kết nối: `localhost:${POSTGRES_PORT}` (mặc định 5432), user và mật khẩu lấy từ `.env`.

## Về script database

Script được tách từ bộ Flyway `V1`–`V23` của repo cũ (một PostgreSQL dùng chung). Tên file giữ dạng Flyway để service nào dùng Flyway có thể dùng luôn, còn không thì owner đổi theo cách của mình.

**Khóa ngoại chéo service đã được gỡ.** Khi mỗi service một database, một bảng không thể có khóa ngoại trỏ sang bảng của service khác. Mỗi chỗ đã gỡ được để lại một dòng comment trong `V1__init_schema.sql` ghi rõ cột nào từng trỏ tới đâu, ví dụ `bookings.court_id -> courts.id (facility-service)`. Cột vẫn giữ lại như ID thường; kiểm tra tính hợp lệ qua API hoặc event giữa các service, không truy vấn chéo database.

Dữ liệu mẫu là dữ liệu demo (tài khoản `@demo.com`, số tài khoản ngân hàng giả).

## Thêm service mới

1. Tạo folder `<ten-service>/` ở gốc repo.
2. Thêm một dòng `<ten-service> <ten_database>` vào `docker/postgres/services.txt`.
3. Nếu có script khởi tạo, đặt vào `<ten-service>/database/` với tên `V<số>__<mô-tả>.sql`.
4. Cập nhật bảng danh sách service ở trên.

## Quy ước làm việc

- Nhánh: `main` (production), `dev` (tích hợp), `feature/<tên>-<mô-tả>`, `fix/<tên>-<mô-tả>`.
- Commit theo dạng `type(scope): mô tả`, bắt buộc có scope là tên service hoặc phần bị ảnh hưởng.
  - `type`: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.
  - Ví dụ: `feat(booking): add cancel booking api`, `fix(auth): handle expired refresh token`, `chore(docker): update postgres image`.
  - Không thêm dòng `Co-Authored-By` của AI vào commit.
- Mọi thay đổi vào `main` và `dev` đi qua Pull Request, ít nhất 1 review. Không push trực tiếp.
- Chỉ sửa trong folder service của mình; cần sửa folder service khác thì báo owner đó.
- Không commit secret hoặc file `.env`.
