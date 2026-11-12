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
