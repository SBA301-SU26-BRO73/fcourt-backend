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
- `POST`, `PUT`, `DELETE` trên `/api/subscription-plans` để quản lý gói.
- `GET /api/court-types` và `GET /api/court-types/{id}` xem loại sân.
- `POST`, `PUT`, `DELETE` trên `/api/court-types` để quản lý loại sân.
- `GET /api/subscriptions/my-subscription` gói hiện tại của chủ sân.
- `POST /api/subscriptions/checkout` tạo đơn mua gói.
- `GET /api/subscriptions/payments/{orderCode}` tra trạng thái đơn.
- `POST /api/subscriptions/payment-webhook` nhận webhook từ cổng thanh toán.
- `GET /api/super-admin/stats/saas-revenue` doanh thu SaaS.
- `GET /api/super-admin/stats/subscriptions` thống kê đăng ký gói.

## 6. facility-service
- `GET /api/admin/branches` danh sách chi nhánh của chủ sân.
- `POST /api/admin/branches` tạo chi nhánh.
- `GET`, `PUT`, `DELETE` trên `/api/admin/branches/{id}`.
- `GET /api/public/branches/{id}` chi tiết công khai.
- `GET /api/staff/branches/{id}` chi tiết cho nhân viên.
- Ảnh chi nhánh: `GET`, `POST` trên `/api/admin/branches/{id}/images`.
- Xóa ảnh: `DELETE /api/admin/branches/images/{imageId}`.
- Ảnh công khai: `GET /api/public/branches/{id}/images`.
- `GET /api/public/branches/{branchId}/courts` sân của chi nhánh (công khai).
- `GET /api/staff/branches/{branchId}/courts` sân của chi nhánh (nhân viên).
- `GET`, `POST` trên `/api/admin/branches/{branchId}/courts`.
- `GET`, `PUT`, `DELETE` trên `/api/admin/courts/{id}`.
- `PATCH /api/admin/courts/{id}/status` đổi trạng thái sân.
- `POST`, `DELETE` trên `/api/admin/courts/{id}/image` quản lý ảnh sân.
- `POST /api/admin/courts/lock-slots` khóa khung giờ.
- `GET /api/admin/courts/lock-slots` xem các khung đang khóa.
- `DELETE /api/admin/courts/lock-slots/{id}` mở khóa.
- `POST /api/admin/courts/out-of-service` đặt sân ngừng hoạt động.
- `DELETE /api/admin/courts/out-of-service/{groupId}` hủy ngừng hoạt động.
- `GET`, `POST` trên `/api/admin/courts/{courtId}/time-slot-templates`.
- `PUT`, `DELETE` trên `/api/admin/time-slot-templates/{id}`.
- `POST /api/admin/courts/{courtId}/time-slot-templates/preview` xem trước mẫu.
- `POST /api/admin/courts/{courtId}/apply-template` áp dụng mẫu cho một sân.
- `POST /api/admin/courts/bulk-apply-template/preview` xem trước áp dụng hàng loạt.
- `POST /api/admin/courts/bulk-apply-template` áp dụng mẫu cho nhiều sân.
- `GET`, `POST` trên `/api/admin/courts/{courtId}/pricing-rules`.
- `PUT`, `DELETE` trên `/api/admin/courts/{courtId}/pricing-rules/{ruleId}`.
- `POST .../pricing-rules/copy-weekday-to-weekend` sao chép giá ngày thường sang cuối tuần.
- `GET /api/internal/courts/{id}/booking-context` ngữ cảnh phục vụ đặt sân.
- `GET /api/internal/courts/{courtId}/price-quote` báo giá theo khung giờ.
- `GET /api/internal/courts/{courtId}/slot-schedule` lịch khung giờ của sân.
- `/api/internal/court-reservations` tạo, commit, rollback, xem đặt chỗ sân.
- Facility ghi sự kiện ra bảng `facility_outbox` để đồng bộ sang service khác.

## 7. discovery-service
- `GET /api/public/branches` tìm kiếm chi nhánh công khai.
- `GET /api/public/courts/{courtId}/slots` xem khung giờ còn trống của sân.
- `GET /api/internal/courts/{courtId}/slot-conflicts` kiểm tra xung đột khung giờ.
- `POST /api/reviews` khách gửi đánh giá.
- `POST /api/reviews/images` tải ảnh kèm đánh giá.
- `GET /api/public/branches/{id}/reviews` xem đánh giá của chi nhánh.
- `POST /api/admin/reviews/{id}/reply` chủ sân phản hồi đánh giá.

## 8. slot-hold-engine
- `POST /api/bookings/hold-slots` giữ chỗ tạm thời và trả về `holdToken`.
- `DELETE /api/bookings/hold-slots/{holdToken}` nhả chỗ đã giữ.

## 9. booking-service
- `GET /api/bookings/my` danh sách booking của tôi.
- `GET /api/bookings/{id}` chi tiết booking.
- `GET /api/bookings/{id}/cancellation-policy` xem chính sách hủy.
- `POST /api/bookings/{id}/cancel` hủy booking.
- `POST /api/public/bookings/lookup` tra cứu booking bằng thông tin liên hệ.
- `POST /api/public/bookings/cancel` hủy booking không cần tài khoản.
- `GET /api/admin/bookings` danh sách booking của các chi nhánh.
- `GET /api/internal/bookings/{id}/voucher-snapshot` ảnh chụp voucher đã áp dụng.
- `POST`, `DELETE` trên `/api/internal/bookings/{id}/voucher` gắn hoặc gỡ voucher.
- `POST /api/internal/bookings/{id}/payment-freeze` đóng băng giá để thanh toán.
- `GET /api/internal/courts/{court}/slot-occupancy` tình trạng chiếm chỗ.
- `GET /api/internal/branches/{branch}/schedule` lịch của chi nhánh.
- `POST /api/internal/bookings/walk-in` tạo booking tại quầy.
- `POST /api/internal/bookings/walk-in/reconcile` đối soát booking offline.
- `GET /api/internal/bookings/{id}/checkout-preview` xem trước trả sân.
- `PUT /api/internal/bookings/{id}/checkout` trả sân.
- `POST /api/internal/bookings/check-in/preview` xem trước check-in.
- `POST /api/internal/bookings/check-in` check-in.
- `POST /api/internal/bookings/offline-check-in` check-in offline.
- `GET /api/internal/branches/{branch}/offline-tickets` vé phục vụ chế độ offline.

## 10. payment-service
- `GET /api/payments/checkout-info/{bookingId}` thông tin thanh toán.
- `POST /api/payments/{bookingId}/upload-receipt` tải biên lai chuyển khoản.
- `GET /api/payments/{bookingId}/status` trạng thái thanh toán.
- `GET /api/payments/{bookingId}/status?compact=true` trạng thái rút gọn.
- `POST /api/payments/{bookingId}/confirm-free` xác nhận booking miễn phí nhờ voucher.
- `GET /api/payments/my-transactions` lịch sử giao dịch của tôi.
- `GET /api/payments/{bookingId}/ticket` vé điện tử, `.../ticket/qr` ảnh QR.
