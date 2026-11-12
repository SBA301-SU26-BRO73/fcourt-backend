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
