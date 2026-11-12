# FCOURT Backend — Repository Reference
Tài liệu tham chiếu các chức năng hiện tại của repo `fcourt-backend`.
Nội dung được tổng hợp từ các controller trong từng service.
Mọi đường dẫn bên dưới là đường dẫn qua gateway, trừ khi ghi chú khác.

## 1. Tổng quan
FCOURT là hệ thống đặt sân thể thao theo kiến trúc microservices.
Mỗi service nằm trong một folder riêng ở gốc repo.
Mỗi service sở hữu một database PostgreSQL riêng, schema do Flyway tạo.
Các service giao tiếp qua REST nội bộ (`/api/internal/**`) và sự kiện outbox.
Không còn service registry (Eureka): gateway tìm service bằng DNS tĩnh.

## 2. Phân quyền theo tiền tố đường dẫn
Tiền tố `/api/public` là công khai, `/api/auth` là tài khoản, `/api/admin` là chủ sân, `/api/staff` là nhân viên, `/api/super-admin` là quản trị nền tảng, `/api/internal` là gọi nội bộ.

## 3. Danh sách service
| Service | Database | Vai trò chính |
|---|---|---|
| `gateway-service` | không có | Cổng vào, định tuyến và xác thực JWT |
| `auth-service` | `auth_db` | Tài khoản, đăng nhập, JWKS |
| `subscription-service` | `subscription_db` | Gói dịch vụ SaaS cho chủ sân |
| `facility-service` | `facility_db` | Chi nhánh, sân, khung giờ, giá |
| `discovery-service` | `discovery_db` | Tìm kiếm công khai và đánh giá |
| `slot-hold-engine` | `slot_hold_db` | Giữ chỗ tạm thời cho khung giờ |
| `booking-service` | `booking_db` | Đặt sân, hủy sân, check-in |
| `payment-service` | `payment_db` | Thanh toán, hoàn tiền, vé |
| `voucher-service` | `voucher_db` | Mã giảm giá |
| `staff-operations-service` | `staff_db` | Nhân viên, lịch làm việc, check-in |
| `pos-retail-service` | `pos_retail_db` | Bán lẻ tại quầy |
| `reporting-service` | `reporting_db` | Báo cáo và thống kê |
| `notification-service` | `notification_db` | Thông báo đẩy tới thiết bị |

## 4. auth-service
- `POST /api/auth/register/customer` đăng ký khách hàng.
- `POST /api/auth/register/court-owner` đăng ký chủ sân (multipart, kèm giấy tờ).
- `POST /api/auth/login` đăng nhập bằng email và mật khẩu.
- `POST /api/auth/google` đăng nhập bằng Google.
- `POST /api/auth/refresh` làm mới token.
- `POST /api/auth/logout` đăng xuất.
- `GET /api/auth/profile` xem hồ sơ, `PATCH /api/auth/profile` cập nhật.
- `PUT /api/auth/profile/avatar` đổi ảnh đại diện, `DELETE` để xóa.
- `POST /api/auth/password/forgot` yêu cầu đặt lại mật khẩu.
- `POST /api/auth/password/reset` đặt lại mật khẩu bằng mã.
- `PATCH /api/auth/password` đổi mật khẩu khi đã đăng nhập.
- `GET /.well-known/jwks.json` công bố khóa để các service kiểm tra JWT.
- `GET /api/auth/me` thông tin tài khoản hiện tại.
- `/api/internal/staff-accounts` tạo, sửa, xóa, kích hoạt lại tài khoản nhân viên.
- `GET /api/super-admin/users` danh sách người dùng.
- `GET /api/super-admin/court-admins/pending` danh sách chủ sân chờ duyệt.
- `GET /api/super-admin/court-admins/{id}` chi tiết hồ sơ chủ sân.
- `PUT /api/super-admin/court-admins/{id}/approve` duyệt chủ sân.
- `PUT /api/super-admin/court-admins/{id}/reject` từ chối chủ sân.
- `PUT /api/super-admin/users/{id}/toggle-status` khóa hoặc mở khóa tài khoản.
- `GET /api/super-admin/stats/accounts` thống kê tài khoản.
- `GET /api/super-admin/stats/owner-names` tra tên chủ sân theo id.

## 5. subscription-service
- `GET /api/subscription-plans` danh sách gói đang bán.
- `GET /api/subscription-plans/all` toàn bộ gói, kể cả gói đã ẩn.
- `GET /api/subscription-plans/{id}` chi tiết một gói.
