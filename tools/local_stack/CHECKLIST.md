# Xác nhận stack local

Chạy từ repo root sau khi copy `.env.example` thành `tools/local_stack/.env`. Các lệnh dưới đây chưa được chạy trong môi trường soạn file này.

```powershell
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env config -q
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build --no-cache auth-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build --no-cache staff-operations-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d postgres redis kafka
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d --build
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env ps
curl.exe -fsS http://localhost:8082/actuator/health
curl.exe -fsS http://localhost:8083/actuator/health
curl.exe -fsS http://localhost:8084/actuator/health
curl.exe -fsS http://localhost:8085/actuator/health
curl.exe -fsS http://localhost:8080/_gateway_health
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env exec auth-service curl -fsS http://localhost:8081/actuator/health
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env exec slot-hold-engine curl -fsS http://localhost:8087/actuator/health
curl.exe -i -H "Content-Type: application/json" -d '{"email":"unknown@example.invalid","password":"not-a-password"}' http://localhost:8080/api/auth/login
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env logs --tail 100 gateway auth-service discovery-service slot-hold-engine booking-service payment-service
```

Gateway login cuối cần trả phản hồi HTTP từ auth-service (đăng nhập fixture chưa tồn tại thì lỗi xác thực là bình thường), không phải Nginx 404/502. Nếu WebSocket được triển khai trong bản backend đang dùng, kiểm tra handshake `/ws-fcourt` trên port 8088 bằng client WebSocket của app hoặc một công cụ WS; `curl` thường không xác nhận được handshake đầy đủ.

Để thử profile tùy chọn sau khi xác nhận mặc định, build từng image rồi chạy:

```powershell
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build pos-retail-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build notification-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env --profile pos --profile notifications up -d --no-build pos-retail-service notification-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env exec pos-retail-service curl -fsS http://localhost:8091/actuator/health
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env exec notification-service curl -fsS http://localhost:8092/actuator/health
```
