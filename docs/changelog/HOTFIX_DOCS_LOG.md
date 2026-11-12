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
