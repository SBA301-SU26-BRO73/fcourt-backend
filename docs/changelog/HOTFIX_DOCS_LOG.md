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
