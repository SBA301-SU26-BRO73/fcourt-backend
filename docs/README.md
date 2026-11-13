# Tài liệu backend FCourt

Quay lại [README của repository](../README.md). Phần lớn tài liệu được viết bằng tiếng Việt.

## Cấu trúc thư mục

```
docs/
├── README.md                    # mục lục này
├── REPOSITORY_REFERENCE.md      # tham chiếu chức năng toàn repo
├── repository-file-index.md     # danh sách file tài liệu trong repo
├── validation-reference.md      # hợp đồng API, review, kết quả kiểm thử
├── architecture/                # thiết kế kiến trúc và sơ đồ
├── reference/                   # tham chiếu file theo từng service
└── report/                      # báo cáo cuối kỳ MSS301
```

## Tổng quan và tra cứu

| Tài liệu | Nội dung |
|---|---|
| [REPOSITORY_REFERENCE.md](REPOSITORY_REFERENCE.md) | Chức năng hiện tại của từng service, tổng hợp từ controller; đường dẫn API qua gateway |
| [repository-file-index.md](repository-file-index.md) | Danh sách các file tài liệu (README, ghi chú SCRUM) trong toàn repo |
| [validation-reference.md](validation-reference.md) | Chỉ mục hợp đồng API, ghi chú review, bản ghi kiểm thử và mã nguồn test |

## Kiến trúc

| Tài liệu | Nội dung |
|---|---|
| [aws-architecture.md](architecture/aws-architecture.md) | Thiết kế AWS/EKS và các phương án triển khai (SCRUM-100) |
| [code-structure.md](architecture/code-structure.md) | Cấu trúc package chuẩn cho các service Spring Boot |
| [spring-cloud.md](architecture/spring-cloud.md) | Gateway và service discovery tĩnh (không dùng Eureka) |
| [realtime-schedule-notifications.md](architecture/realtime-schedule-notifications.md) | Cập nhật lịch công khai qua STOMP `/ws-fcourt` (SCRUM-108) |
| [images/arch-3-1-microservices.png](architecture/images/arch-3-1-microservices.png) | Sơ đồ kiến trúc microservices (nguồn draw.io: [arch-3-1-microservices.drawio](architecture/images/arch-3-1-microservices.drawio)) |

## Tham chiếu theo service

[reference/](reference/README.md) gom file của từng service theo nhóm: build, resource, package Java, proto, test, script database và tài liệu sẵn có.

[auth](reference/auth-service.md) · [booking](reference/booking-service.md) · [discovery](reference/discovery-service.md) · [facility](reference/facility-service.md) · [gateway](reference/gateway-service.md) · [notification](reference/notification-service.md) · [payment](reference/payment-service.md) · [pos-retail](reference/pos-retail-service.md) · [reporting](reference/reporting-service.md) · [slot-hold-engine](reference/slot-hold-engine.md) · [staff-operations](reference/staff-operations-service.md) · [subscription](reference/subscription-service.md) · [voucher](reference/voucher-service.md)

## Báo cáo

| Tài liệu | Nội dung |
|---|---|
| [report/FCourt_Report_MSS301.docx](report/FCourt_Report_MSS301.docx) | Báo cáo cuối kỳ MSS301 (bản chính thức) |
| [report/README.md](report/README.md) | Quy ước lưu báo cáo; bản nháp và PDF chỉ lưu cục bộ |

## Tài liệu khác

- README của từng service: `<service>/README.md` (auth, booking, discovery, facility, gateway, notification, payment, pos-retail, reporting, slot-hold-engine, staff-operations, subscription, voucher).
- README của công cụ: [tools/demo_seed](../tools/demo_seed/README.md), [tools/production_smoke](../tools/production_smoke/README.md), [tools/system_tests](../tools/system_tests/README.md).
- Script cơ sở dữ liệu: `<service>/database/`; danh sách database ở `docker/postgres/services.txt`.
