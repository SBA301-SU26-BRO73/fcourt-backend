# Spring Cloud: Eureka và Gateway

Các thành phần hạ tầng bắt buộc theo yêu cầu môn MSS301. Chúng là folder riêng ở gốc repo, không phải service nghiệp vụ, và không có database.

| Folder | Vai trò | Port | Spring Boot / Spring Cloud |
|---|---|---|---|
| `eureka-server` | Service registry (Netflix Eureka), chạy standalone | 8761 | 4.0.9 / 2025.1.4 |

Các service nghiệp vụ hiện vẫn dùng Spring Boot 3.5.x; client Eureka của chúng dùng Spring Cloud 2025.0.x (train tương ứng với Boot 3.5).

## Eureka Server

- Dashboard: `http://localhost:8761`. Health: `http://localhost:8761/actuator/health`.
- Standalone: `register-with-eureka=false`, `fetch-registry=false`. Không chạy cụm nhiều node.
- Biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SERVER_PORT` | `8761` | Cổng HTTP |
| `EUREKA_HOSTNAME` | `localhost` | Hostname của registry |
| `EUREKA_SELF_PRESERVATION` | `false` | Bật self-preservation (nên bật khi chạy nhiều node/prod) |

Chạy local:

```bash
cd eureka-server
sh mvnw spring-boot:run
```

## Service đăng ký với Eureka (client)

Mỗi service nghiệp vụ thêm `spring-cloud-starter-netflix-eureka-client` và cấu hình qua biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `EUREKA_URL` | `http://localhost:8761/eureka` | `eureka.client.service-url.defaultZone` |
| `EUREKA_ENABLED` | `true` | `eureka.client.enabled`; đặt `false` để tắt (test, hoặc chạy không có registry) |

Eureka bị tắt trong test của từng service (`eureka.client.enabled=false`), nên test không cần registry. Tên đăng ký là `spring.application.name` (ví dụ `booking-service`, `slot-hold-engine`).
