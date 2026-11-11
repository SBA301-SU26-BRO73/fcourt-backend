# Chạy cả hệ thống bằng Docker Compose

Một lệnh dựng toàn bộ backend trên máy: PostgreSQL, Redis, Kafka, Eureka, gateway và 12 service. Đây là cách chạy local mặc định và gần như miễn phí (chỉ tốn vài request S3); EKS chỉ dùng để demo trên cloud.

## Chạy nhanh

```bash
cp .env.example .env            # bắt buộc: điền AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY (S3 thật, xem khối S3)
docker compose --profile core up -d --build
```

Lần đầu build image mất khoảng 10 đến 20 phút (Maven tải thư viện trong từng Dockerfile). Máy yếu nên build từng nhóm để không hết RAM:

```bash
COMPOSE_PARALLEL_LIMIT=2 docker compose --profile all build
```

Sau khi các container `healthy`, mọi client chỉ cần gọi gateway: `http://localhost:8080`.

```bash
curl http://localhost:8080/actuator/health        # gateway tự trả 200
curl http://localhost:8080/api/public/branches     # danh sách chi nhánh (facility)
```

Swagger gộp của tất cả service: `http://localhost:8080/swagger-ui.html`.

## Profile

Không đặt profile thì không có container nào chạy. Chọn một trong các profile:

| Profile | Gồm | RAM ước tính |
|---|---|---|
| `infra` | postgres, redis, kafka | khoảng 1,5 GB |
| `core` | `infra` + eureka-server, gateway, auth, facility, booking, slot-hold-engine, payment | khoảng 4,5 GB |
| `all` | `core` + subscription, reporting, discovery, voucher, staff-operations, notification, pos-retail + mailpit | khoảng 7,5 GB |
| `mail` | chỉ mailpit (đã nằm trong `all`) | 64 MB |

```bash
docker compose --profile infra up -d                 # chỉ hạ tầng, chạy service bằng IDE trên máy host
docker compose --profile core up -d --build          # đủ cho đăng nhập, xem sân, đặt sân, thanh toán
docker compose --profile all up -d --build           # đủ 12 service
docker compose --profile all up -d --build booking-service   # build lại và chạy lại một service

docker compose --profile all ps                      # trạng thái và healthy
docker compose --profile all logs -f booking-service
docker compose --profile all stop                    # dừng, giữ dữ liệu
docker compose --profile all down                    # xoá container, giữ volume
docker compose --profile all down -v                 # xoá luôn dữ liệu (Postgres, Kafka)
```

Có thể ghép profile: `--profile infra --profile core`. Mọi lệnh sau khi `up` đều phải lặp lại `--profile` (hoặc đặt `COMPOSE_PROFILES=core` trong `.env`).

## Thứ tự khởi động

`postgres, redis, kafka` (healthy), rồi `eureka-server` (healthy), rồi các service (healthy), cuối cùng `gateway-service`. Gateway chờ auth, facility, booking, slot-hold-engine và payment healthy trước khi lên. Healthcheck của service gọi `/actuator/health` bằng bash, không cần curl trong image.

## Cổng

| Cổng host | Dịch vụ | Ghi chú |
|---|---|---|
| 8080 | gateway-service | cổng duy nhất client cần |
| 8761 | Eureka dashboard | xem service nào đã đăng ký |
| 5432 | PostgreSQL | user/mật khẩu trong `.env` (mặc định `postgres`/`postgres`) |
| 6387 | Redis | |
| 9094 | Kafka (từ máy host) | trong Compose dùng `kafka:9092` |
| 8025 / 1025 | Mailpit web / SMTP | chỉ profile `all` hoặc `mail` |

Các service còn lại không mở cổng ra host (chỉ gateway đi vào được). Cần gọi thẳng một service để debug: `docker compose exec gateway-service ...` hoặc tạm thêm `ports:` vào service đó.

## Cấu hình

Hầu hết giá trị có mặc định trong `docker-compose.yml`; riêng khối S3 (bucket và access key) bắt buộc đặt trong `.env` (xem `.env.example`).

- **Database**: mỗi service một database riêng (`auth_db`, `booking_db`, ...), tạo bởi `docker/postgres/init-databases.sh` ở lần chạy đầu khi volume trống. Schema và dữ liệu mẫu do Flyway của từng service tạo khi service khởi động.
- **Kafka**: `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, một node KRaft, topic tự tạo.
- **Eureka**: `EUREKA_URL=http://eureka-server:8761/eureka`. Service chưa phải Eureka client sẽ bỏ qua biến này; gateway chỉ định tuyến được tới service đã đăng ký.
- **Redis**: gateway dùng cho rate limiting (fail open, `RATE_LIMIT_*`, đặt `RATE_LIMIT_ENABLED=false` để tắt); slot-hold-engine lưu hold; facility, discovery, payment dùng cho cache.
- **JWT/JWKS**: auth-service ký token, các service khác lấy khoá tại `http://auth-service:8081/.well-known/jwks.json`. Mặc định auth dùng khoá ký tạm, restart auth thì token cũ không còn hợp lệ (đăng nhập lại). Muốn cố định, đặt `AUTH_JWT_PRIVATE_KEY` (PKCS8 PEM).
- **S3**: mọi service dùng bucket S3 thật `fcourt-local-media-...` (IAM user `fcourt-local-s3`), không còn MinIO trong Compose. Đặt `STORAGE_S3_BUCKET`, `STORAGE_S3_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` trong `.env` (xin key ở tech lead, không commit); thiếu thì `docker compose` báo lỗi, kể cả profile `infra`. Ảnh public (`avatars/*`, `branches/*`, `reviews/*`) đọc ẩn danh nhờ bucket policy; `receipts/`, `refund-proofs/`, `owner-documents/` dùng presigned URL. Xem [storage-s3.md](storage-s3.md).
- **Token nội bộ** giữa các service (hold, POS, payment, ...) có giá trị DEV cố định trong Compose. Chỉ dùng local, không dùng cho môi trường thật.
- **Tính năng cần tài khoản ngoài** (PayOS, SMTP thật, Firebase/SNS push, đăng nhập Google) để trống thì tắt; phần còn lại chạy bình thường. Mail đi vào Mailpit.

## Bộ nhớ

Mỗi service Java có `mem_limit` 512 MB (Eureka 384 MB), đổi bằng `SERVICE_MEM_LIMIT` trong `.env`. `JAVA_TOOL_OPTIONS` đặt heap bằng 55% giới hạn container, SerialGC, chỉ C1, `-Xss512k`, nên mỗi JVM thường dùng khoảng 300 đến 450 MB. Dùng `docker stats --no-stream` để xem thực tế. Nếu Docker Desktop (WSL2) bị giới hạn RAM, tăng trong `%UserProfile%\.wslconfig` (`memory=...`), rồi `wsl --shutdown`.

## Xử lý sự cố

- **`docker compose up` không chạy gì**: thiếu `--profile`.
- **Container `unhealthy` / restart liên tục**: `docker compose --profile all logs <service>`. Thường do thiếu RAM (exit 137, `OOMKilled`): chạy profile nhỏ hơn hoặc tăng `SERVICE_MEM_LIMIT`.
- **Gateway 503 `Unable to find instance`**: service đó chưa đăng ký Eureka. Mở `http://localhost:8761` kiểm tra, đợi vài chục giây, hoặc service chưa là Eureka client trên nhánh bạn đang build.
- **Flyway báo schema không rỗng / sai checksum**: volume cũ từ cách chạy trước (script SQL cũ). `docker compose down -v` rồi chạy lại.
- **Port đã bị chiếm** (5432, 6387, 8080...): đổi `POSTGRES_PORT`, `REDIS_HOST_PORT`, `GATEWAY_PORT` trong `.env`, hoặc dừng Postgres/Redis cài sẵn trên máy.
- **Container cũ tên `fcourt_postgres` đang chạy**: Compose không còn đặt `container_name`; dừng container cũ để tránh tranh cổng 5432.
- **Build rất chậm hoặc hết RAM**: `COMPOSE_PARALLEL_LIMIT=1 docker compose --profile core build`.
- **Docker Desktop treo**: `wsl --shutdown`, mở lại Docker Desktop.
- **Chạy lại từ đầu**: `docker compose --profile all down -v`.
