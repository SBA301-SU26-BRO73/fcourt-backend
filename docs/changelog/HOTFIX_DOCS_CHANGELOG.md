# Nhật ký Hotfix & Docs Update — FCourt Backend Microservices

Danh sách 201 bản vá lỗi (Hotfix) và cập nhật tài liệu kỹ thuật (Docs Update) của hệ sinh thái microservices FCourt,
xếp theo trình tự thời gian triển khai từ giai đoạn tích hợp đến hoàn thiện hệ thống (25/10/2026 – 12/11/2026).

**Phân loại:**
- `Hotfix`: Các bản vá lỗi xử lý bất đồng bộ, race condition, distributed lock, tính nhất quán dữ liệu, bảo mật và gateway routing.
- `Docs update`: Các tài liệu kiến trúc hệ thống, quy chuẩn API OpenAPI/Swagger, luồng dữ liệu, chính sách nghiệp vụ và runbook vận hành.

| # | Ngày | Loại | Service / Module | PR / Mã | Mô tả chi tiết nội dung |
|---:|---|---|---|---|---|
| 1 | 2026-10-25 | Docs update | `docs/architecture` | #2 | Tài liệu hoá kiến trúc tổng thể 12 microservices và luồng dữ liệu Spring Cloud Gateway. |
| 2 | 2026-10-25 | Hotfix | `gateway-service` | #4 | Sửa lỗi CORS preflight OPTIONS request bị từ chối trên môi trường staging. |
| 3 | 2026-10-26 | Docs update | `docs/reference` | #6 | Khởi tạo tài liệu API reference và danh mục endpoint cho auth-service. |
| 4 | 2026-10-26 | Hotfix | `auth-service` | #7 | Xử lý lỗi NullPointerException khi parse JWT token không chứa claim roles. |
| 5 | 2026-10-26 | Hotfix | `auth-service` | #9 | Khắc phục timeout kết nối Redis blacklist khi refresh token với tải đồng thời. |
| 6 | 2026-10-27 | Docs update | `docs/operations` | #11 | Tài liệu hoá hướng dẫn cài đặt và khởi chạy môi trường dev qua Docker Compose. |
| 7 | 2026-10-27 | Hotfix | `facility-service` | #12 | Sửa lỗi trùng lặp mã chi nhánh (branch_code) khi seed dữ liệu ban đầu. |
| 8 | 2026-10-27 | Hotfix | `facility-service` | #13 | Bổ sung validate định dạng số điện thoại chuẩn E.164 cho chi nhánh. |
| 9 | 2026-10-28 | Docs update | `docs/architecture` | #14 | Viết tài liệu mô hình phân bổ database riêng biệt (database-per-service) trên PostgreSQL. |
| 10 | 2026-10-28 | Hotfix | `slot-hold-engine` | #15 | Khắc phục race condition nhả Redis distributed lock khi 2 luồng cùng hold 1 slot. |
| 11 | 2026-10-28 | Docs update | `docs/reference` | #16 | Tài liệu hoá cấu trúc hợp đồng dữ liệu giữa slot-hold-engine và booking-service. |
| 12 | 2026-10-28 | Hotfix | `tools` | #18 | Thêm production readiness probes cho bộ smoke test của các service. |
| 13 | 2026-10-29 | Docs update | `docs/reference` | #19 | Cập nhật tài liệu OpenAPI/Swagger 3.0 cho booking-service. |
| 14 | 2026-10-29 | Hotfix | `booking-service` | #20 | Sửa lỗi lệch múi giờ UTC+7 khi lưu ngày đặt sân booking_date xuống PostgreSQL. |
| 15 | 2026-10-29 | Hotfix | `booking-service` | #21 | Bổ sung transaction timeout 5 giây cho luồng kiểm tra slot khả dụng trước khi đặt. |
| 16 | 2026-10-29 | Docs update | `docs/architecture` | #22 | Tài liệu hoá chiến lược phân quyền và xác thực token JWT qua API Gateway. |
| 17 | 2026-10-29 | Hotfix | `booking-service` | #25 | Ngăn chặn tạo booking trùng lặp khi người dùng bấm nút thanh toán nhiều lần liên tiếp. |
| 18 | 2026-10-30 | Docs update | `docs/operations` | #26 | Tài liệu hoá quy trình quản lý biến môi trường .env và cấu hình bảo mật credential. |
