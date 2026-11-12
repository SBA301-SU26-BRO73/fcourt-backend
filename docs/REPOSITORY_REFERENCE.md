# FCOURT Backend — Repository Reference
Tài liệu tham chiếu các chức năng hiện tại của repo `fcourt-backend`.
Nội dung được tổng hợp từ các controller trong từng service.
Mọi đường dẫn bên dưới là đường dẫn qua gateway, trừ khi ghi chú khác.

## 1. Tổng quan
FCOURT là hệ thống đặt sân thể thao theo kiến trúc microservices.
Mỗi service nằm trong một folder riêng ở gốc repo.
Mỗi service sở hữu một database PostgreSQL riêng, schema do Flyway tạo.
Các service giao tiếp qua REST nội bộ (`/api/internal/**`) và sự kiện outbox.
Không còn service registry (Eureka): gateway tìm service bằng DNS tĩnh.

## 2. Phân quyền theo tiền tố đường dẫn
Tiền tố `/api/public` là công khai, `/api/auth` là tài khoản, `/api/admin` là chủ sân, `/api/staff` là nhân viên, `/api/super-admin` là quản trị nền tảng, `/api/internal` là gọi nội bộ.

## 3. Danh sách service
