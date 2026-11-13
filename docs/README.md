# Tài liệu backend FCourt

Quay lại [README của repository](../README.md). Phần lớn tài liệu hiện có được viết bằng tiếng Việt.

## Kiến trúc

| Tài liệu | Nội dung |
|---|---|
| [aws-architecture.md](architecture/aws-architecture.md) | Thiết kế AWS/EKS và các phương án triển khai (tài liệu thiết kế SCRUM-100) |
| [code-structure.md](architecture/code-structure.md) | Cấu trúc package chuẩn cho các service Spring Boot |
| [spring-cloud.md](architecture/spring-cloud.md) | Gateway và service discovery tĩnh (không dùng Eureka) |
| [realtime-schedule-notifications.md](architecture/realtime-schedule-notifications.md) | Cập nhật lịch công khai qua STOMP `/ws-fcourt` (SCRUM-108) |
| [images/arch-3-1-microservices.png](architecture/images/arch-3-1-microservices.png) | Sơ đồ kiến trúc microservices (nguồn draw.io: [arch-3-1-microservices.drawio](architecture/images/arch-3-1-microservices.drawio)) |

## Báo cáo

[report/](report/README.md) chứa báo cáo cuối kỳ của dự án: bản chính thức `FCourt_Report_MSS301.docx` được đưa vào git; bản nháp và PDF chỉ lưu cục bộ.

## Tài liệu khác

- README của từng service: `<service>/README.md` (auth, booking, discovery, facility, notification, payment, pos-retail, reporting, slot-hold-engine, staff-operations, subscription).
- README của công cụ: [tools/demo_seed](../tools/demo_seed/README.md), [tools/production_smoke](../tools/production_smoke/README.md), [tools/system_tests](../tools/system_tests/README.md).
- Script cơ sở dữ liệu: `<service>/database/`; danh sách database ở `docker/postgres/services.txt`.
