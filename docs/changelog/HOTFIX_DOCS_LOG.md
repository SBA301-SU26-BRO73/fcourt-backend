# Nhật ký Hotfix & Docs Update — fcourt-backend

Danh sách các thay đổi đã merge vào nhánh `dev` của hệ thống microservice FCourt,
xếp theo thứ tự thời gian. Mỗi dòng gồm ngày merge, loại thay đổi, service bị ảnh hưởng,
pull request tham chiếu và mô tả ngắn nội dung.

**Loại thay đổi:** `Hotfix` · `Docs update` · `Feature` · `Refactor` · `CI/CD` · `Test` · `Chore`

| # | Ngày | Loại | Service | PR | Mô tả |
|---:|---|---|---|---|---|
| 1 | 2026-11-01 | Hotfix | `staff-operations-service` | #45 | Từ chối check-in cho ngày đã qua; lịch trong ngày chấp nhận slot qua nửa đêm và slot đã huỷ. |
| 2 | 2026-11-02 | Test | `gateway-service` | #17 | Bổ sung workflow e2e đi qua gateway và kiểm thử saga đặt sân. |
| 3 | 2026-11-02 | Chore | `tools` | #16 | Thêm bộ dữ liệu demo có thể chạy lại (resumable) cho môi trường phát triển. |
| 4 | 2026-11-02 | Test | `tools` | #18 | Thêm production readiness probes cho bộ smoke test. |
| 5 | 2026-11-02 | Hotfix | `facility-service` | #66 | Đồng bộ quyền SUPER_ADMIN, chỉ số todayRevenue và cách tính booking đã huỷ với monolith cũ. |
| 6 | 2026-11-07 | Test | `staff-operations-service` | #81 | Kiểm thử hợp đồng opaque ticket khi nhân viên check-in. |
| 7 | 2026-11-07 | Hotfix | `tools` | #82 | Căn chỉnh dữ liệu demo runtime cho khớp schema hiện tại của các service. |
| 8 | 2026-11-08 | Feature | `slot-hold-engine` | #103 | Broadcast thay đổi lịch sân theo thời gian thực (SCRUM-108). |
| 9 | 2026-11-08 | Chore | `facility-service` | #116 | Nạp file `facility-service/.env` tuỳ chọn cho cấu hình local (SCRUM-47). |
| 10 | 2026-11-08 | Hotfix | `discovery-service` | #129 | Cho route slot-conflicts (đã kiểm token) đi qua security chain, tránh bị chặn nhầm (SCRUM-246). |
| 11 | 2026-11-08 | CI/CD | `.github` | #130 | Chặn workflow chạy trùng và tăng tốc build gateway-service (SCRUM-247). |
| 12 | 2026-11-08 | Hotfix | `tools` | #131 | Sửa demo seed cho khớp schema voucher-service (SCRUM-247). |
| 13 | 2026-11-09 | Feature | `booking-service` | #126 | Saga đặt nguyên sân (full-court) nguyên tử, lấy facility làm nguồn quyết định (SCRUM-242). |
| 14 | 2026-11-09 | Feature | `slot-hold-engine` | #125 | Phát sự kiện realtime khi khoá slot và khi hold hết hạn (SCRUM-245). |
| 15 | 2026-11-09 | Test | `system-tests` | #132 | E2E trên cluster thật cho luồng đặt nguyên sân (SCRUM-114). |
| 16 | 2026-11-09 | Feature | `facility-service` | #133 | Chuyển tiếp thay đổi khoá đặt sân sang sự kiện lịch realtime (SCRUM-241, SCRUM-108). |
| 17 | 2026-11-09 | Docs update | `docs/business` | #134 | Phân tích khoảng trống nghiệp vụ so với monolith cũ và các phương án kiến trúc AWS. |
| 18 | 2026-11-09 | Feature | `facility-service` | #172 | API công khai danh sách sân đang hoạt động của một chi nhánh (SCRUM-248). |
| 19 | 2026-11-09 | Feature | `notification-service` | #173 | Registry thiết bị, push SNS theo từng thiết bị và nhắc lịch đặt sân. |
| 20 | 2026-11-09 | Docs update | `docs/architecture` | #174 | Chuẩn hoá và tài liệu hoá cấu trúc package cho các service backend. |
| 21 | 2026-11-09 | CI/CD | `.github` | #175 | Chỉ build và push image của service có thay đổi lên Amazon ECR. |
| 22 | 2026-11-09 | Feature | `eureka-server` | #176 | Eureka registry độc lập trên Spring Boot 4 (về sau được gỡ ở #249). |
| 23 | 2026-11-09 | CI/CD | `eureka-server` | #177 | Build image eureka-server khi push; gateway-service đã khớp pattern `*-service`. |
| 24 | 2026-11-09 | Feature | `gateway-service` | #178 | Spring Cloud Gateway kiểm tra JWT và gộp Swagger của tất cả service. |
| 25 | 2026-11-09 | Feature | `all services` | #179–#186, #215–#218 | Đăng ký 12 service với Eureka để service discovery qua registry. |
| 26 | 2026-11-09 | Feature | `booking-service` | #187 | Tra cứu booking cho khách vãng lai: `POST /api/public/bookings/lookup`. |
| 27 | 2026-11-09 | Feature | `booking-service` | #190 | Admin huỷ booking: `POST /api/admin/bookings/{id}/cancel`. |
| 28 | 2026-11-09 | Feature | `pos-retail-service` | #191 | Danh sách sản phẩm POS cho chủ sân: `GET /api/admin/pos/products`. |
| 29 | 2026-11-09 | Feature | `gateway-service` | #212 | Giới hạn tần suất request tại gateway bằng Redis. |
| 30 | 2026-11-09 | Docs update | `docs/operations` | #210 | Mô tả thiết kế lưu trữ S3, LocalStack và seed data không phụ thuộc Cloudinary. |
| 31 | 2026-11-09 | Feature | `auth-service` | #208 | Chuyển lưu trữ file của auth-service từ Cloudinary sang AWS S3. |
| 32 | 2026-11-09 | Feature | `facility-service` | #209 | Chuyển lưu trữ ảnh chi nhánh/sân từ Cloudinary sang AWS S3. |
| 33 | 2026-11-09 | Feature | `payment-service` | #211 | Biên lai thanh toán và chứng từ hoàn tiền lưu trên S3, truy cập qua presigned URL. |
| 34 | 2026-11-09 | Feature | `discovery-service` | #214 | Chuyển ảnh đánh giá (review) từ Cloudinary sang AWS S3. |
| 35 | 2026-11-09 | Docs update | `discovery-service` | #189 | Bổ sung Swagger/OpenAPI (springdoc) cho discovery-service. |
| 36 | 2026-11-09 | Docs update | `booking-service` | #192 | Bổ sung Swagger/OpenAPI (springdoc) cho booking-service. |
| 37 | 2026-11-09 | Docs update | `voucher-service` | #194 | Bổ sung Swagger/OpenAPI (springdoc) cho voucher-service. |
| 38 | 2026-11-09 | Docs update | `notification-service` | #195 | Bổ sung Swagger/OpenAPI (springdoc) cho notification-service. |
| 39 | 2026-11-09 | Docs update | `reporting-service` | #198 | Bổ sung Swagger/OpenAPI (springdoc) cho reporting-service. |
| 40 | 2026-11-09 | Docs update | `subscription-service` | #202 | Bổ sung Swagger/OpenAPI (springdoc) cho subscription-service. |
| 41 | 2026-11-09 | Docs update | `slot-hold-engine` | #203 | Bổ sung Swagger/OpenAPI (springdoc) cho slot-hold-engine. |
| 42 | 2026-11-09 | Feature | `voucher-service` | #205 | Thêm `@RestControllerAdvice` trả lỗi chuẩn RFC 7807 ProblemDetail. |
| 43 | 2026-11-09 | Feature | `notification-service` | #206 | Thêm `@RestControllerAdvice` trả lỗi chuẩn RFC 7807 ProblemDetail. |
| 44 | 2026-11-09 | Feature | `slot-hold-engine` | #207 | Thêm `@RestControllerAdvice` trả lỗi chuẩn RFC 7807 ProblemDetail. |
| 45 | 2026-11-09 | Feature | `booking-service` | #213 | Trả presigned URL cho biên lai thanh toán lưu trên S3. |
| 46 | 2026-11-09 | Feature | `booking-service` | #204 | Thêm `@RestControllerAdvice` trả lỗi chuẩn RFC 7807 ProblemDetail. |
| 47 | 2026-11-09 | Docs update | `payment-service` | #193 | Bổ sung Swagger/OpenAPI (springdoc) cho payment-service. |
| 48 | 2026-11-09 | Docs update | `staff-operations-service` | #199 | Bổ sung Swagger/OpenAPI (springdoc) cho staff-operations-service. |
| 49 | 2026-11-09 | Docs update | `pos-retail-service` | #201 | Bổ sung Swagger/OpenAPI (springdoc) cho pos-retail-service. |
| 50 | 2026-11-09 | Feature | `voucher-service` | #197 | Gọi facility-service qua OpenFeign kèm Resilience4J (circuit breaker, retry). |
| 51 | 2026-11-09 | Docs update | `facility-service` | #188 | Bổ sung Swagger/OpenAPI (springdoc) cho facility-service. |
| 52 | 2026-11-09 | Feature | `reporting-service` | #196 | Gọi facility-service qua OpenFeign kèm Resilience4J (circuit breaker, retry). |
| 53 | 2026-11-09 | Docs update | `auth-service` | #200 | Bổ sung Swagger/OpenAPI (springdoc) cho auth-service. |
| 54 | 2026-11-09 | Docs update | `docs/operations` | #220 | Public read theo prefix thay cho CloudFront; ghi rõ không migrate dữ liệu Cloudinary. |
| 55 | 2026-11-09 | Test | `shared storage` | #219 | Pin image MinIO Testcontainers theo digest để test ổn định, tái lập được. |
| 56 | 2026-11-09 | Feature | `auth-service` | #221 | Danh sách người dùng phân trang cho super admin: `GET /api/super-admin/users`. |
| 57 | 2026-11-09 | Feature | `facility-service` | #222 | Cập nhật mẫu khung giờ: `PUT /api/admin/time-slot-templates/{id}`. |
| 58 | 2026-11-09 | Refactor | `booking-service` | #223 | Bỏ rate limiter tra cứu khách trong service, chuyển trách nhiệm lên gateway. |
| 59 | 2026-11-09 | Feature | `gateway-service` | #226 | Định tuyến danh sách sản phẩm POS của super admin tới pos-retail-service. |
| 60 | 2026-11-09 | Feature | `pos-retail-service` | #225 | Danh sách sản phẩm POS của tất cả chi nhánh cho super admin. |
| 61 | 2026-11-09 | Feature | `subscription-service` | #232 | Endpoint thống kê danh sách gói đăng ký cho super admin. |
| 62 | 2026-11-09 | Feature | `reporting-service` | #231 | Các endpoint chi tiết cho dashboard super admin. |
| 63 | 2026-11-09 | Feature | `subscription-service` | #224 | Trả số chi nhánh/sân đã dùng trong `my-subscription`. |
| 64 | 2026-11-09 | Feature | `voucher-service` | #227 | Endpoint kiểm tra voucher và gỡ voucher đã áp dụng. |
| 65 | 2026-11-09 | Feature | `booking-service` | #228 | Cho khách vãng lai tạo booking online bằng hold token. |
| 66 | 2026-11-09 | Feature | `slot-hold-engine` | #229 | `guestPhone` không bắt buộc khi khách hàng đã đăng nhập. |
| 67 | 2026-11-09 | Feature | `gateway-service` | #230 | Mở tạo booking công khai cho khách, giới hạn tần suất theo từng client. |
| 68 | 2026-11-09 | Feature | `facility-service` | #233 | Công khai số điện thoại, giờ mở cửa, ảnh sân và bổ sung trường card cho discovery. |
| 69 | 2026-11-09 | Feature | `booking-service` | #234 | API nội bộ gỡ voucher khỏi booking chưa thanh toán. |
| 70 | 2026-11-09 | Feature | `booking-service` | #235 | Khách vãng lai huỷ booking bằng mã booking và số điện thoại. |
| 71 | 2026-11-09 | Feature | `gateway-service` | #236 | Áp chính sách rate limit của lookup cho API huỷ booking công khai. |
| 72 | 2026-11-09 | Feature | `booking-service` | #237 | Trả số điện thoại khách trong booking view để payment-service sử dụng. |
| 73 | 2026-11-09 | Test | `payment-service` | #238 | Kiểm thử truyền số điện thoại khách từ booking view sang sổ cái (ledger). |
| 74 | 2026-11-09 | Feature | `facility-service` | #239 | Tải lên, thay thế và xoá ảnh sân. |
| 75 | 2026-11-09 | Feature | `docker-compose` | #240 | Chạy toàn bộ hệ thống bằng Docker Compose profiles. |
| 76 | 2026-11-09 | Hotfix | `facility-service` | #241 | Backfill `bank_bin` cho các chi nhánh seed sẵn để tạo được mã VietQR. |
| 77 | 2026-11-09 | Hotfix | `payment-service` | #242 | Trả 422 thay vì 503 khi thông tin ngân hàng của chi nhánh chưa đầy đủ. |
| 78 | 2026-11-09 | Feature | `auth-service` | #244 | Cấu hình `storage.s3.presign-endpoint` để URL tài liệu chủ sân mở được từ trình duyệt. |
| 79 | 2026-11-09 | Feature | `payment-service` | #245 | Cấu hình `storage.s3.presign-endpoint` cho URL biên lai và chứng từ hoàn tiền. |
| 80 | 2026-11-09 | Feature | `booking-service` | #246 | Cấu hình `storage.s3.presign-endpoint` cho URL biên lai thanh toán. |
| 81 | 2026-11-09 | Hotfix | `docker-compose` | #247 | Cho đọc ẩn danh các prefix ảnh công khai trên MinIO; presigned host truy cập được từ trình duyệt. |
| 82 | 2026-11-09 | Hotfix | `docker-compose` | #243 | Bật profile realtime của booking để handshake STOMP `/ws-fcourt` chạy được ở local. |
| 83 | 2026-11-09 | Hotfix | `booking-service` | #248 | Cho phép huỷ booking chưa thanh toán vào bất kỳ thời điểm nào. |
| 84 | 2026-11-11 | Refactor | `docker-compose` | #249 | Gỡ Eureka, dùng AWS S3 thật trong Docker Compose. |
| 85 | 2026-11-11 | Hotfix | `.github` | #250 | Gắn tag image `latest` thay vì `dev` và trỏ đúng cấu trúc gitops dạng phẳng. |
| 86 | 2026-11-11 | Feature | `tools` | #262 | Script seed dữ liệu demo cho EKS kèm bộ ảnh mẫu. |
| 87 | 2026-11-11 | Hotfix | `docker-compose` | #275 | Giữ giới hạn gói của facility ở mặc định cho tới khi subscription cung cấp giá trị. |
| 88 | 2026-11-11 | Hotfix | `tools` | #338 | Thay ảnh ngoại cảnh chi nhánh bằng ảnh sân đúng môn thể thao. |
| 89 | 2026-11-12 | Feature | `subscription-service` | #387 | Endpoint nội bộ trả giới hạn gói; trả lý do từ chối khi đăng nhập. |
| 90 | 2026-11-12 | Feature | `notification-service` | #375 | Thông báo in-app và REST API cho chuông thông báo trên web. |
| 91 | 2026-11-12 | Chore | `tools` | #429 | Thay ảnh card chất lượng thấp của chi nhánh 3 và 10 trong seed. |
| 92 | 2026-11-12 | Hotfix | `payment-service` | #452 | Tạo yêu cầu hoàn tiền khi khách vãng lai hoặc chủ sân huỷ booking. |
| 93 | 2026-11-12 | Hotfix | `payment-service` | #453 | Bỏ qua sự kiện hoàn tiền đã cũ thay vì retry vô hạn. |
| 94 | 2026-11-12 | Hotfix | `notification-service` | #454 | Vẫn gửi thông báo in-app và push khi gửi email thất bại. |
| 95 | 2026-11-12 | Docs update | `README.md` | #456 | Viết lại README, sắp xếp lại thư mục docs và thêm thư mục report. |
| 96 | 2026-11-12 | Docs update | `README.md` | #457 | Dịch README sang tiếng Việt và dùng ảnh sơ đồ kiến trúc. |
| 97 | 2026-11-12 | Hotfix | `staff-operations-service` | #459 | Trả lý do khi check-in bị từ chối và mở route đăng ký thiết bị nhận thông báo. |
| 98 | 2026-11-12 | Feature | `staff-operations-service` | #461 | Check-in booking theo id ngay từ lịch hôm nay. |
| 99 | 2026-11-12 | Docs update | `docs/reference` | #463 | Thêm hướng dẫn điều hướng file cho tất cả service backend. |
