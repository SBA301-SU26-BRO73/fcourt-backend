# Nhật ký task Jira — Minh

Danh sách 100 task Jira (dự án SCRUM) được giao cho Minh trong dự án FCourt, xếp theo mã task.
Trạng thái lấy theo snapshot Jira ngày 07/11/2026. Cột "PR / Task cha" ghi pull request đã merge
vào `dev` với task triển khai, và ghi task gốc với ticket review.

**Loại:** `Task` — task triển khai · `Review approach` — duyệt hướng tiếp cận · `Code review` — duyệt code ·
`Request review` / `Request code review` — yêu cầu review cho task của thành viên khác.

| # | Jira | Loại | Phạm vi | Trạng thái (07/11) | PR / Task cha | Nội dung |
|---:|---|---|---|---|---|---|
| 1 | [SCRUM-24](https://bro73.atlassian.net/browse/SCRUM-24) | Task | BE: slot-hold-engine | Done | [backend#2](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/2) | Booking — Redis Distributed Lock giữ chỗ 5 phút (Port 8087) |
| 2 | [SCRUM-25](https://bro73.atlassian.net/browse/SCRUM-25) | Task | Flutter: Customer | Done | [flutter#1](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/1) | Booking — Chọn slot, nhập SĐT guest, tóm tắt đơn đặt sân |
| 3 | [SCRUM-32](https://bro73.atlassian.net/browse/SCRUM-32) | Task | BE: payment-service | Done | [backend#3](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/3) | Payment — Booking checkout summary & VietQR config (Port 8089) |
| 4 | [SCRUM-33](https://bro73.atlassian.net/browse/SCRUM-33) | Task | Flutter: Customer | Done | [flutter#2](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/2) | Payment — Màn hình tóm tắt đơn, mã VietQR & countdown 5 phút |
| 5 | [SCRUM-34](https://bro73.atlassian.net/browse/SCRUM-34) | Task | BE: payment-service | Done | [backend#5](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/5) | Payment — Upload biên lai Cloudinary & cập nhật Awaiting Confirm |
| 6 | [SCRUM-35](https://bro73.atlassian.net/browse/SCRUM-35) | Task | Flutter: Customer | Done | [flutter#3](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/3) | Payment — Image Picker upload bill & Polling trạng thái duyệt |
| 7 | [SCRUM-36](https://bro73.atlassian.net/browse/SCRUM-36) | Task | BE: payment-service | Done | [backend#7](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/7) | Payment — Admin confirm / reject payment API |
| 8 | [SCRUM-37](https://bro73.atlassian.net/browse/SCRUM-37) | Task | Flutter: Admin | Done | [flutter#4](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/4) | Payment — Màn hình danh sách duyệt biên lai & Modal đối soát bill |
| 9 | [SCRUM-38](https://bro73.atlassian.net/browse/SCRUM-38) | Task | BE: payment-service | Done | [backend#8](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/8) | Payment — Generate QR code vé khi confirmed & auto-cancel 5m |
| 10 | [SCRUM-39](https://bro73.atlassian.net/browse/SCRUM-39) | Task | Flutter: Customer | Done | [flutter#5](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/5) | Payment — Màn hình hiển thị QR code vé điện tử sắc nét |
| 11 | [SCRUM-53](https://bro73.atlassian.net/browse/SCRUM-53) | Task | BE: booking-service | Done | [backend#21](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/21) | Booking — Booking history & detail API (Port 8088) |
| 12 | [SCRUM-54](https://bro73.atlassian.net/browse/SCRUM-54) | Task | Flutter: Customer | Code Review | [flutter#10](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/10) | Booking — Lịch sử đặt sân, chi tiết vé QR & trạng thái |
| 13 | [SCRUM-55](https://bro73.atlassian.net/browse/SCRUM-55) | Task | BE: booking-service | Done | [backend#22](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/22) | Booking — Cancel booking API & Validate chính sách hủy sân |
| 14 | [SCRUM-56](https://bro73.atlassian.net/browse/SCRUM-56) | Task | Flutter: Customer | Done | [flutter#11](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/11) | Booking — Hộp thoại hủy đặt sân, hiển thị chính sách hoàn tiền |
| 15 | [SCRUM-67](https://bro73.atlassian.net/browse/SCRUM-67) | Task | Flutter: Customer | Done | [flutter#12](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/12) | Ticket — QR code vé đầy đủ kèm chuỗi mã vé số để đọc tại quầy |
| 16 | [SCRUM-68](https://bro73.atlassian.net/browse/SCRUM-68) | Task | BE: payment-service | Done | [backend#23](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/23) | Payment — Lịch sử giao dịch của khách hàng API |
| 17 | [SCRUM-69](https://bro73.atlassian.net/browse/SCRUM-69) | Task | Flutter: Customer | Done | [flutter#13](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/13) | Payment — Màn hình Lịch sử giao dịch thanh toán cá nhân |
| 18 | [SCRUM-70](https://bro73.atlassian.net/browse/SCRUM-70) | Task | BE: payment-service | Done | [backend#24](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/24) | Payment — Sổ cái giao dịch tài chính của chi nhánh API |
| 19 | [SCRUM-71](https://bro73.atlassian.net/browse/SCRUM-71) | Task | Flutter: Admin | Done | [flutter#14](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/14) | Payment — Màn hình Đối soát tài chính & doanh thu chi nhánh |
| 20 | [SCRUM-72](https://bro73.atlassian.net/browse/SCRUM-72) | Task | BE: payment-service | Done | [backend#25](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/25) | Payment — Polling endpoint siêu nhẹ tối ưu Redis Cache |
| 21 | [SCRUM-73](https://bro73.atlassian.net/browse/SCRUM-73) | Task | Flutter: Customer | Done | [flutter#15](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/15) | Payment — Trải nghiệm Polling realtime trạng thái duyệt bill |
| 22 | [SCRUM-94](https://bro73.atlassian.net/browse/SCRUM-94) | Task | BE: payment-service | Done | [backend#26](https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/26) | Refund — Customer gửi yêu cầu hoàn tiền sau khi hủy sân API |
| 23 | [SCRUM-95](https://bro73.atlassian.net/browse/SCRUM-95) | Task | Flutter: Customer | Done | [flutter#16](https://github.com/SBA301-SU26-BRO73/fcourt-flutter/pull/16) | Refund — Màn hình gửi yêu cầu hoàn tiền & theo dõi tiến độ |
