# Spring Cloud: Gateway và tìm service

Hệ thống không dùng service registry (đã bỏ Eureka). `gateway-service` là thành phần Spring Cloud duy nhất ở gốc repo, không có database. Chi tiết route và biến môi trường: xem [gateway.md](../operations/gateway.md).

## Tìm service bằng DNS tĩnh

Route của gateway vẫn viết dạng `lb://<tên-service>`. Địa chỉ lấy từ `SimpleDiscoveryClient` của Spring Cloud Commons, cấu hình trong `gateway-service/src/main/resources/application.yml`:

```yaml
spring.cloud.discovery.client.simple.instances.auth-service[0].uri: ${AUTH_SERVICE_URI:http://auth-service:8081}
```

- Mỗi service có đúng một URI mặc định `http://<tên>:<cổng>`. Tên service trong Docker Compose và tên Kubernetes Service giống nhau, nên mặc định chạy được ở cả hai môi trường, không cần profile riêng.
- Cân bằng tải giữa các pod do Kubernetes Service (kube-proxy) đảm nhiệm; gateway chỉ cần một URI.
- Muốn trỏ chỗ khác (ví dụ chạy service từ IDE) đặt biến `<TÊN>_URI`, như `AUTH_SERVICE_URI=http://localhost:8081`. Tên biến là tên service viết hoa, đổi `-` thành `_`.
- Thêm service mới sau gateway: thêm một mục `instances` và route; thêm tên vào `GATEWAY_SWAGGER_SERVICES` nếu muốn có trong Swagger gộp.

Các service nghiệp vụ không hỏi registry: chúng gọi nhau bằng URL cố định qua biến môi trường (`AUTH_URL`, `FACILITY_URL`, `BOOKING_URL`, ...) và gRPC (`HOLD_GRPC_TARGET`, `BOOKING_GRPC_TARGET`). Feign của reporting và voucher dùng `url=` (`FACILITY_URL`).

Test: `StaticDiscoveryTests` trong gateway kiểm tra `DiscoveryClient` trả đúng địa chỉ cho từng service.
