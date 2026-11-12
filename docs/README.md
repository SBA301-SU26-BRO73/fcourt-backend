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

## Vận hành

| Tài liệu | Nội dung |
|---|---|
| [docker-compose.md](operations/docker-compose.md) | Chạy toàn bộ hệ thống trên máy local: profile, port, bộ nhớ, xử lý sự cố |
| [smoke-test-local.md](operations/smoke-test-local.md) | Kết quả smoke test local đã ghi lại (Compose + web) |
| [gateway.md](operations/gateway.md) | Route của gateway, đường dẫn công khai, biến môi trường, giới hạn tốc độ |
| [storage-s3.md](operations/storage-s3.md) | Lưu file trên S3: cấu trúc key, presigned URL, IAM |
| [ci-image-build.md](operations/ci-image-build.md) | CI build image lên ECR và cập nhật tag GitOps |

## Nghiệp vụ

| Tài liệu | Nội dung |
|---|---|
| [business-gap-a.md](business/business-gap-a.md) | Phân tích chênh lệch monolith và microservices, phần A (đặt sân, thanh toán, hoàn tiền) |
| [business-gap-b.md](business/business-gap-b.md) | Phân tích chênh lệch, phần B (cấu hình PayOS, doanh thu dashboard, voucher) |
| [cancellation-refunds.md](business/cancellation-refunds.md) | Luồng hoàn tiền khi hủy đơn đã thanh toán (A6 / A12) |

## Báo cáo

[report/](report/README.md) là nơi lưu cục bộ báo cáo cuối kỳ của dự án (DOCX/PDF); bản thân các file này không được đưa vào git.

## Tài liệu khác

- README của từng service: `<service>/README.md` (auth, booking, discovery, facility, notification, payment, pos-retail, reporting, slot-hold-engine, staff-operations, subscription).
- README của công cụ: [tools/demo_seed](../tools/demo_seed/README.md), [tools/production_smoke](../tools/production_smoke/README.md), [tools/system_tests](../tools/system_tests/README.md).
- Script cơ sở dữ liệu: `<service>/database/`; danh sách database ở `docker/postgres/services.txt`.
