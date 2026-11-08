# Docker stack local gọn cho FCourt

Stack này chạy backend thật để Flutter Android emulator gọi qua host. Các API Flutter hiện có gồm đăng nhập/tài khoản dưới `/api/auth` và danh mục `/api/court-types`, `/api/subscription-plans` qua `API_BASE_URL` (xem `fcourt-flutter/lib/features/auth/data/datasources/auth_remote_data_source.dart:14` và `fcourt-flutter/lib/features/catalog/data/datasources/catalog_remote_data_source.dart:12`). Các route quản trị, đặt sân, thanh toán, nhân viên và dashboard được lấy từ controller/service và bản gateway tham khảo `deploy/nginx.conf` trên nhánh production draft; cấu hình này không cần nhánh đó khi chạy.

Port backend được khai báo trong `application.yml`: auth 8081 (`auth-service/src/main/resources/application.yml:2`), subscription 8082 (`subscription-service/src/main/resources/application.yml:2`), facility 8083 (`facility-service/src/main/resources/application.yml:2`), reporting 8084 (`reporting-service/src/main/resources/application.yml:2`), discovery 8085 (`discovery-service/src/main/resources/application.yml:2`), hold 8087 (`slot-hold-engine/src/main/resources/application.yml:2`), booking 8088 (`booking-service/src/main/resources/application.yml:2`), payment 8089 (`payment-service/src/main/resources/application.yml:2`), staff 8090 (`staff-operations-service/src/main/resources/application.yml:2`). Voucher 8086 và POS 8091 được đọc từ cấu hình dịch vụ tương ứng. Các port không dùng trực tiếp từ emulator vẫn nằm trong mạng Compose.

## Yêu cầu

- Docker Desktop với Docker Compose v2.24 trở lên; bật WSL2 và cấp cho Docker tối đa khoảng 5.5 GiB RAM.
- Java 21 và Maven không cần cài trên máy host; Docker build dùng Maven wrapper của từng service.
- Cho phép Docker build truy cập Maven repository để tải dependency lần đầu; BuildKit giữ cache Maven ở `~/.m2`.
- Tối thiểu khoảng 10 GiB dung lượng trống cho image, Maven cache và volume dữ liệu.
- Tạo `.env` từ `.env.example` trong thư mục này bằng `Copy-Item tools/local_stack/.env.example tools/local_stack/.env`. Giá trị mẫu là giả cho phát triển local. `.gitignore` ở gốc repo đã bỏ qua `.env` và `.env.*`; không commit file local.

## Ngân sách RAM

Các giới hạn container được đặt trong Compose. Mỗi JVM có `MaxRAMPercentage=65`, `UseSerialGC`, `Xss512k` và `TieredStopAtLevel=1`; với giới hạn 320 MiB, heap trần xấp xỉ 208 MiB. Kafka dùng heap 512 MiB; job tạo topic dùng heap tối đa 96 MiB và chạy xong trước khi service Kafka-backed khởi động.

| Thành phần | Container | Giới hạn RAM |
|---|---|---:|
| PostgreSQL 16 | 1 | 512 MiB |
| Redis 7 (`maxmemory 96mb`) | 1 | 192 MiB |
| Kafka 3.9 KRaft (broker/controller, không ZooKeeper) | 1 | 768 MiB |
| Job khởi tạo Kafka topics | 1 (chạy xong) | 128 MiB |
| Auth, subscription, facility, reporting, discovery, voucher, hold, booking, payment, staff | 10 × 320 MiB | 3200 MiB |
| Gateway Nginx | 1 | 64 MiB |
| **Mặc định** | **15 containers** | **4864 MiB (4.75 GiB)** |
| POS và notification nếu bật cả hai profile | thêm 2 × 320 MiB | +640 MiB |
| **Tối đa kể cả profile tùy chọn** | **17 containers** | **5504 MiB (5.38 GiB)** |

Đây là trần cấu hình, không phải dự đoán chính xác RSS. Hãy giữ cả hai profile tùy chọn tắt trên máy ít RAM; các service JVM có thể hết bộ nhớ nếu workload hoặc native memory vượt giả định.

## Khởi động

Chạy lệnh từ thư mục gốc `fcourt-backend`. Để tránh tăng RAM đột ngột khi Maven build nhiều service đồng thời, build từng image một:

```powershell
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d postgres redis kafka
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build auth-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d --no-build auth-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build subscription-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build facility-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build reporting-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build discovery-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build voucher-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build slot-hold-engine
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build booking-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build payment-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env build staff-operations-service
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d --no-build
```

Lệnh build nhóm có thể chiếm nhiều RAM đồng thời; với máy yếu, chạy **mỗi service riêng** theo cùng mẫu `docker compose ... build <service>`, sau đó chạy `up -d --no-build`. Thao tác build ở giai đoạn này không khởi động container Java.

Sau khi đã có image, lệnh khởi động đầy đủ theo yêu cầu là:

```powershell
docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env up -d --build
```

Nó có thể build song song theo mặc định. Nếu cần build lại trên máy yếu, dùng quy trình từng service ở trên. Để bật POS hoặc notification, build từng service riêng rồi chạy `docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env --profile pos --profile notifications up -d --no-build pos-retail-service notification-service`.

## Demo data

Khởi động backend trước để mọi migration/Flyway schema được tạo. Sau đó chuẩn bị manifest demo theo `tools/demo_seed/README.md` (địa chỉ/court assets và ngày fixture phải được kiểm tra), đặt `FCOURT_DEMO_PASSWORD_HASH` trong process environment, rồi chạy từ repo root:

```powershell
python tools/demo_seed/seed.py --manifest tools/demo_seed/manifest.local.json --container fcourt-local-postgres-1 --apply
```

Chỉ dùng fixture giả khi đã hiểu các giới hạn trong README của seeder; ví dụ manifest cần `--allow-fixture-assets`. Seeder yêu cầu bcrypt hash cho mật khẩu demo, nhưng không lưu mật khẩu đó trong repo. Trên Windows PowerShell, đặt biến cho process trước khi gọi Python bằng `$env:FCOURT_DEMO_PASSWORD_HASH = '<bcrypt hash đã tạo riêng>'`.

## Kết nối emulator, logs và dừng stack

- `fcourt-flutter/env/dev.json` đặt `API_BASE_URL` thành `http://10.0.2.2:8080`; Android emulator dùng `10.0.2.2` để tới host. Port 8080 là Nginx gateway. Port 8082–8085 cũng được publish trực tiếp theo cấu hình dev; port 8088 chuyển tiếp đường dẫn `/ws-fcourt` qua Nginx.
- Log toàn stack: `docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env logs -f`; log riêng: thêm tên service, ví dụ `logs -f auth-service`.
- Dừng nhưng giữ dữ liệu: `docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env down`.
- Xóa **chỉ volume của project Compose `fcourt-local` này** để reset DB/Redis/Kafka local: `docker compose -p fcourt-local -f tools/local_stack/compose.yml --env-file tools/local_stack/.env down --volumes`. Không chạy `docker volume prune`; lệnh đó tác động volume ngoài stack.

## Giới hạn đã biết

- Flutter `dev.json` trong checkout hiện chỉ khai báo `API_BASE_URL`; các địa chỉ discovery/facility/subscription riêng nêu trong yêu cầu không có trong file hiện tại. Vì vậy gateway API được phục vụ qua 8080; các port 8082–8085 được publish để hỗ trợ cấu hình/dev tool khác.
- Không tìm thấy handler WebSocket `/ws-fcourt` trong backend sources đã đọc. Nginx chuyển path đó tới booking-service port 8088 theo cấu hình port của service; người chạy cần xác nhận endpoint socket tồn tại trong build thực tế.
- Subscription checkout chỉ hoạt động khi cung cấp thông tin PayOS sandbox. Compose tham chiếu `../../subscription-service/.env` dưới dạng tùy chọn; file này không được đọc/chép hay đưa vào repo. Các khoản VietQR/payment còn cần cấu hình upload Cloudinary hợp lệ nếu muốn lưu bill ảnh.
- `AUTH_JWT_PRIVATE_KEY` để trống dùng khóa ký tạm thời cho local dev theo `auth-service/src/main/resources/application.yml:51`; restart auth có thể làm token cũ mất hiệu lực.
- Stack không tự seed demo, không cung cấp SMTP, không chạy các dịch vụ Cloudinary/PayOS ngoài, và không có TLS. Voucher chưa được xác nhận áp vào checkout; demo seeder ghi voucher unused.
- Init database chỉ chạy trên volume PostgreSQL mới, tạo database và role riêng theo service. Xóa volume làm mất dữ liệu của stack này. Các service tự chạy migration khi khởi động.
- `slot-hold-engine` không có database PostgreSQL; giữ hold trong Redis theo `slot-hold-engine/README.md`.
- `kafka-init` tạo các topic/partition cục bộ theo `tools/system_tests` trước các consumer; job kết thúc nhưng Compose container vẫn tồn tại.
- Staff và POS không có `mvnw`/`.mvn` trong worktree được đọc; Dockerfile dùng Maven 3.9.11 đã cài trong build image cho hai service này và dùng wrapper của service cho các service còn lại.
- Không thể xác nhận `docker compose config`, build image, health URL, migration, login hay socket ở môi trường này vì không có Docker/network; xem lệnh xác nhận trong `CHECKLIST.md`.

## Bản tối thiểu đã kiểm chứng (máy 16 GB, có emulator)

Đã chạy thật: PostgreSQL, Redis, Kafka (+ `kafka-init`), `auth-service`, `facility-service`, `discovery-service` và gateway. Đủ cho đăng nhập, tìm sân, xem chi nhánh, hồ sơ và quản lý sân. Các service còn lại (booking, slot-hold, payment, subscription, reporting, voucher, staff) chưa được build/chạy: gateway trả 502 cho các đường dẫn của chúng.

```powershell
$C = "docker compose -f tools/local_stack/compose.yml --env-file tools/local_stack/.env"
iex "$C build auth-service facility-service discovery-service"   # từng image một, mỗi image cần RAM trống ~2 GB
iex "$C up -d --no-deps postgres redis kafka"
iex "$C up -d --no-deps kafka-init auth-service facility-service discovery-service gateway"
```

- `discovery-service` **cần Kafka** (consumer của read model); không có Kafka nó thoát rồi khởi động lại liên tục. `auth` và `facility` thì không cần.
- Nginx phân giải upstream theo từng yêu cầu (`resolver 127.0.0.11`), nên gateway khởi động được khi chỉ một phần stack chạy.
- Số đo: tổng RAM container khoảng 1,3 GB khi chạy bản tối thiểu (auth ~240 MB, facility ~220 MB, discovery ~200 MB, Postgres ~130 MB, Kafka ~500 MB), thấp hơn nhiều so với trần.
- Build image dùng Maven trong Docker và có đỉnh RAM; đừng build khi emulator đang chạy. Trên máy chỉ còn ~1 GB trống sau khi bật emulator (tiến trình emulator chiếm khoảng 3,5 GB dù cấu hình 1,5 GB RAM).
- Docker Desktop trên máy này từng tự mất kết nối (`no route to host 192.168.65.7:2375`) sau khoảng 30 phút rảnh; `docker desktop restart` khắc phục.

Tài khoản demo trong `auth-service` (migration V2/V4) dùng chung một hash mà repo không ghi mật khẩu. Để thử cục bộ, đặt mật khẩu riêng trong CSDL cục bộ (không đụng dữ liệu thật), ví dụ `UPDATE users SET password_hash = '<bcrypt>' WHERE email = 'customer01@demo.com';`. Không dùng mật khẩu thử này ở bất kỳ môi trường nào khác.
