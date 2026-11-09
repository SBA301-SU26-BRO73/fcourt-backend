# Gateway (Spring Cloud Gateway)

`gateway-service` (cổng 8080) là điểm vào duy nhất của hệ thống. Web (nginx) và app Flutter chỉ gọi gateway; gateway tìm service qua Eureka (`lb://<tên-service>`), kiểm tra JWT rồi chuyển tiếp, giữ nguyên header `Authorization`. Các service vẫn tự kiểm tra JWT (phòng thủ nhiều lớp).

- Spring Boot 4.0.9, Spring Cloud 2025.1.4, WebFlux (reactive), springdoc 3.0.3.
- Health: `GET /actuator/health` (gateway tự trả lời, không chuyển tiếp). Mọi `/actuator/**` khác bị chặn.
- Swagger UI gộp: `http://localhost:8080/swagger-ui.html`.

## Client gọi như thế nào

Chỉ dùng một base URL: `http://<gateway>:8080` (trong cluster: ALB/nginx trỏ vào gateway, cùng origin). Không gọi trực tiếp port của từng service (8081 đến 8092). Đường dẫn giữ nguyên như trước (ví dụ `/api/auth/login`, `/api/bookings/hold-slots`, WebSocket `/ws-fcourt`). Không cần CORS vì cùng origin.

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SERVER_PORT` | `8080` | Cổng HTTP |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Địa chỉ Eureka |
| `EUREKA_ENABLED` | `true` | Tắt/bật Eureka client |
| `EUREKA_INSTANCE_PREFER_IP_ADDRESS` | `true` | Đăng ký bằng IP |
| `AUTH_JWK_SET_URI` | `http://localhost:8081/.well-known/jwks.json` | JWKS của auth-service |
| `AUTH_JWT_ISSUER` | `http://localhost:8081` | Issuer hợp lệ |
| `AUTH_JWT_AUDIENCE` | `fcourt` | Audience hợp lệ |
| `GATEWAY_ALLOWED_ORIGINS` | rỗng | Danh sách origin CORS (phân cách bằng dấu phẩy). Rỗng: không xử lý CORS |
| `GATEWAY_SWAGGER_ENABLED` | `true` | Bật Swagger UI gộp |
| `GATEWAY_SWAGGER_SERVICES` | cả 12 service | Service nào hiện trong Swagger UI |
| `GATEWAY_CONNECT_TIMEOUT_MS` / `GATEWAY_RESPONSE_TIMEOUT` | `3000` / `30s` | Timeout tới service |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6387` / rỗng | Redis dùng chung của nền tảng (chỉ để giới hạn tốc độ) |
| `RATE_LIMIT_ENABLED` | `true` | Tắt/bật giới hạn tốc độ |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `false` | `true` khi sau ALB: lấy IP client từ hop đầu của `X-Forwarded-For` |
| `RATE_LIMIT_REDIS_TIMEOUT_MS` | `300` | Redis chậm hơn mức này coi như sự cố (cho request đi qua) |
| `RATE_LIMIT_{GENERAL,LOOKUP,AUTH}_REQUESTS` | `50` / `5` / `10` | Số request cho phép trong một cửa sổ |
| `RATE_LIMIT_{GENERAL,LOOKUP,AUTH}_WINDOW_SECONDS` | `1` / `60` / `60` | Độ dài cửa sổ (giây) |
| `RATE_LIMIT_{GENERAL,LOOKUP,AUTH}_BURST` | `100` / `5` / `10` | Số request tối đa dồn cùng lúc |

Token hợp lệ: chữ ký RS256 theo JWKS, đúng issuer, audience `fcourt`, `sub` là số dương, còn hạn (cùng luật với các service).

## Bảng route

Khớp theo `order` (nhỏ trước), rồi theo thứ tự khai báo. Cụ thể trước, rộng sau. Nguồn: `fcourt-gitops/README.md`.

| Path | Service | order |
|---|---|---|
| `/api/bookings/hold-slots/**` | slot-hold-engine | 10 |
| `/api/public/branches` (đúng list), `/api/public/branches/*/reviews/**` | discovery-service | 10 |
| `/api/public/courts/**`, `/api/reviews/**`, `/api/admin/reviews/**` | discovery-service | 20 |
| `/api/auth/**`, `/api/super-admin/{court-admins,users,stats/accounts,stats/owner-names}/**` | auth-service | 20 |
| `/api/subscriptions/**`, `/api/subscription-plans/**`, `/api/court-types/**`, `/api/super-admin/stats/saas-revenue/**` | subscription-service | 20 |
| `/api/public/branches/**`, `/api/admin/{branches,courts,time-slot-templates}/**`, `/api/staff/{branches,courts}/**` | facility-service | 20 |
| `/api/admin/dashboard/**`, `/api/super-admin/dashboard/**` | reporting-service | 20 |
| `/api/vouchers/**`, `/api/admin/vouchers/**` | voucher-service | 20 |
| `/api/public/bookings/**` (tra cứu booking của khách) | booking-service | 10 |
| `/api/bookings/**`, `/api/admin/bookings/**` | booking-service | 20 |
| `/ws-fcourt/**` (WebSocket/STOMP, `lb:ws://`) | booking-service | 20 |
| `/api/payments/**`, `/api/refunds/**`, `/api/admin/{payments,refunds}/**` | payment-service | 20 |
| `/api/staff/{schedule,check-in,offline,me,walk-in,bookings}/**`, `/api/admin/staff/**` | staff-operations-service | 20 |
| `/devices/**` | notification-service | 20 |
| `/api/staff/pos/**`, `/api/admin/pos/**` | pos-retail-service | 20 |
| `GET /docs/<service>/v3/api-docs` | service tương ứng (đổi thành `/v3/api-docs`) | 5 |

Không bao giờ chuyển tiếp: `/api/internal/**` và `/actuator/**` (trả 403, kể cả khi có token), `/.well-known/jwks.json` (không có route). Header `X-Internal-Service-Token` do client gửi bị gỡ.

## Danh sách path công khai (không cần token)

Lấy từ cấu hình bảo mật của từng service (`fcourt.gateway.security.public-paths` trong `application.yml`). Mọi path khác cần token hợp lệ.

| Path | Nguồn |
|---|---|
| `/api/auth/register/**`, `login`, `google`, `refresh`, `logout`, `password/forgot`, `password/reset` | auth-service |
| `GET /api/public/**` | facility-service, discovery-service |
| `GET /api/court-types`, `/api/court-types/*`, `/api/subscription-plans/all` | subscription-service |
| `POST /api/subscriptions/payment-webhook` (PayOS, xác thực bằng chữ ký) | subscription-service |
| `/api/bookings/hold-slots/**` (khách vãng lai giữ chỗ) | slot-hold-engine |
| `GET /api/bookings/*` (khách xem booking bằng `X-Hold-Token`) | booking-service |
| `/api/public/bookings/**` (tra cứu booking của khách, POST lookup) | booking-service |
| `/ws-fcourt/**` | booking-service |
| `/api/payments/**` (checkout, trạng thái, biên lai, vé; ủy quyền cho booking) | payment-service |
| `/swagger-ui.html`, `/swagger-ui/**`, `/webjars/**`, `/v3/api-docs/**`, `GET /docs/**` | gateway |
| `/actuator/health` | gateway |

Ngoại lệ vẫn cần token (`authenticated-paths`, kiểm tra trước): `/api/bookings/my`, `/api/payments/my-transactions`, `/api/payments/*/confirm-free`, `/api/refunds/**`. `PUT/DELETE /devices` (đăng ký thiết bị) cần token như mặc định.

## Giới hạn tốc độ (rate limiting)

Dùng `RequestRateLimiter` của Spring Cloud Gateway với token bucket trên Redis (reactive). Mỗi chính sách là "N request mỗi W giây, tối đa B request cùng lúc"; cài trên Redis dưới dạng: nạp N token/giây, mỗi request tốn W token, dung lượng B x W.

| Chính sách | Áp dụng cho | Khóa | Mặc định |
|---|---|---|---|
| `guest-lookup` | `POST /api/public/bookings/lookup` | IP client | 5 request/phút |
| `auth` | `POST /api/auth/login`, `/api/auth/password/forgot`, `/api/auth/register/**` (dùng chung một xô) | IP client | 10 request/phút |
| `general` | mọi route (cả hai route trên cũng tính thêm) | `sub` của JWT nếu đã đăng nhập, ngược lại IP | 50 request/giây, burst 100 |

- Chính sách chặt nằm ở hai route riêng `order: 5` (`booking-guest-lookup-limited`, `auth-service-sensitive-limited`); `OPTIONS` và các method khác không bị tính.
- `general` đặt rộng để chỉ chặn client lạm dụng/vòng lặp lỗi, không ảnh hưởng người dùng bình thường (một người dùng thật hiếm khi vượt 50 request/giây). `RATE_LIMIT_GENERAL_REQUESTS` đặt lớn nếu muốn gần như tắt.
- Khóa IP: mặc định là địa chỉ socket. Chỉ khi `RATE_LIMIT_TRUST_FORWARDED_FOR=true` (sau ALB, ALB luôn ghi `X-Forwarded-For`) mới lấy hop đầu của `X-Forwarded-For`. Không bật khi gateway lộ trực tiếp ra Internet, vì client có thể giả header.
- Bị chặn: `429 Too Many Requests`, `Content-Type: application/problem+json` (cùng dạng `title/status/detail` như 401/403 của gateway) và header `Retry-After` (giây, làm tròn lên của W/N, ví dụ 12 với 5/phút).
- Fail open: nếu Redis lỗi hoặc không trả lời trong `RATE_LIMIT_REDIS_TIMEOUT_MS`, request vẫn được chuyển tiếp và gateway ghi log `WARN`. Redis health indicator tắt (`management.health.redis.enabled=false`) để sự cố Redis không làm `/actuator/health` báo DOWN.
- Khi chạy local không có Redis, giới hạn không có tác dụng (fail open); đặt `RATE_LIMIT_ENABLED=false` để khỏi log cảnh báo.

## Swagger gộp

Gateway liệt kê trong dropdown mỗi service ở `GATEWAY_SWAGGER_SERVICES` và lấy `/v3/api-docs` của service qua `/docs/<service>/v3/api-docs`. Service chưa thêm springdoc sẽ báo lỗi ở mục của nó cho tới khi bổ sung; để ẩn, bỏ nó khỏi `GATEWAY_SWAGGER_SERVICES`.

## Chạy local

```bash
(cd eureka-server && sh mvnw spring-boot:run)     # cổng 8761
(cd gateway-service && sh mvnw spring-boot:run)   # cổng 8080
```
