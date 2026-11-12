# Nhật ký Hotfix & Docs Update — FCourt Backend Microservices

Danh sách 201 bản vá lỗi (Hotfix) và cập nhật tài liệu kỹ thuật (Docs Update) của hệ sinh thái microservices FCourt,
xếp theo trình tự thời gian triển khai từ giai đoạn tích hợp đến hoàn thiện hệ thống (25/10/2026 – 12/11/2026).

**Phân loại:**
- `Hotfix`: Các bản vá lỗi xử lý bất đồng bộ, race condition, distributed lock, tính nhất quán dữ liệu, bảo mật và gateway routing.
- `Docs update`: Các tài liệu kiến trúc hệ thống, quy chuẩn API OpenAPI/Swagger, luồng dữ liệu, chính sách nghiệp vụ và runbook vận hành.

| # | Ngày | Loại | Service / Module | PR / Mã | Mô tả chi tiết nội dung |
|---:|---|---|---|---|---|
| 1 | 2026-10-25 | Docs update | `docs/architecture` | #2 | Tài liệu hoá kiến trúc tổng thể 12 microservices và luồng dữ liệu Spring Cloud Gateway. |
| 2 | 2026-10-25 | Hotfix | `gateway-service` | #4 | Sửa lỗi CORS preflight OPTIONS request bị từ chối trên môi trường staging. |
| 3 | 2026-10-26 | Docs update | `docs/reference` | #6 | Khởi tạo tài liệu API reference và danh mục endpoint cho auth-service. |
| 4 | 2026-10-26 | Hotfix | `auth-service` | #7 | Xử lý lỗi NullPointerException khi parse JWT token không chứa claim roles. |
| 5 | 2026-10-26 | Hotfix | `auth-service` | #9 | Khắc phục timeout kết nối Redis blacklist khi refresh token với tải đồng thời. |
| 6 | 2026-10-27 | Docs update | `docs/operations` | #11 | Tài liệu hoá hướng dẫn cài đặt và khởi chạy môi trường dev qua Docker Compose. |
| 7 | 2026-10-27 | Hotfix | `facility-service` | #12 | Sửa lỗi trùng lặp mã chi nhánh (branch_code) khi seed dữ liệu ban đầu. |
| 8 | 2026-10-27 | Hotfix | `facility-service` | #13 | Bổ sung validate định dạng số điện thoại chuẩn E.164 cho chi nhánh. |
| 9 | 2026-10-28 | Docs update | `docs/architecture` | #14 | Viết tài liệu mô hình phân bổ database riêng biệt (database-per-service) trên PostgreSQL. |
| 10 | 2026-10-28 | Hotfix | `slot-hold-engine` | #15 | Khắc phục race condition nhả Redis distributed lock khi 2 luồng cùng hold 1 slot. |
| 11 | 2026-10-28 | Docs update | `docs/reference` | #16 | Tài liệu hoá cấu trúc hợp đồng dữ liệu giữa slot-hold-engine và booking-service. |
| 12 | 2026-10-28 | Hotfix | `tools` | #18 | Thêm production readiness probes cho bộ smoke test của các service. |
| 13 | 2026-10-29 | Docs update | `docs/reference` | #19 | Cập nhật tài liệu OpenAPI/Swagger 3.0 cho booking-service. |
| 14 | 2026-10-29 | Hotfix | `booking-service` | #20 | Sửa lỗi lệch múi giờ UTC+7 khi lưu ngày đặt sân booking_date xuống PostgreSQL. |
| 15 | 2026-10-29 | Hotfix | `booking-service` | #21 | Bổ sung transaction timeout 5 giây cho luồng kiểm tra slot khả dụng trước khi đặt. |
| 16 | 2026-10-29 | Docs update | `docs/architecture` | #22 | Tài liệu hoá chiến lược phân quyền và xác thực token JWT qua API Gateway. |
| 17 | 2026-10-29 | Hotfix | `booking-service` | #25 | Ngăn chặn tạo booking trùng lặp khi người dùng bấm nút thanh toán nhiều lần liên tiếp. |
| 18 | 2026-10-30 | Docs update | `docs/operations` | #26 | Tài liệu hoá quy trình quản lý biến môi trường .env và cấu hình bảo mật credential. |
| 19 | 2026-10-30 | Hotfix | `payment-service` | #27 | Xử lý lỗi timeout khi sinh mã VietQR động với ngân hàng Vietcombank qua PayOS. |
| 20 | 2026-10-30 | Hotfix | `payment-service` | #28 | Sửa lỗi sai lệch định dạng số tiền VND trong payload gửi sang cổng thanh toán. |
| 21 | 2026-10-31 | Docs update | `docs/reference` | #29 | Bổ sung tài liệu hợp đồng tích hợp gRPC giữa voucher-service và payment-service. |
| 22 | 2026-10-31 | Hotfix | `voucher-service` | #30 | Khắc phục lỗi không hoàn lại voucher quota khi giao dịch thanh toán bị huỷ bỏ. |
| 23 | 2026-10-31 | Hotfix | `notification-service` | #31 | Sửa lỗi template email xác nhận đặt sân bị mất logo và sai biến thời gian. |
| 24 | 2026-10-31 | Docs update | `docs/architecture` | #32 | Tài liệu hoá kiến trúc gửi email bất đồng bộ qua hàng đợi AWS SQS / SES. |
| 25 | 2026-11-01 | Docs update | `docs/business` | #33 | Tài liệu hoá chính sách hoàn huỷ sân và quy tắc tính phí huỷ theo thời gian. |
| 26 | 2026-11-01 | Hotfix | `staff-operations-service` | #45 | Từ chối check-in cho ngày đã qua; lịch trong ngày chấp nhận slot qua nửa đêm. |
| 27 | 2026-11-01 | Hotfix | `staff-operations-service` | #46 | Sửa lỗi không hiển thị tên sân trong danh sách check-in của nhân viên quầy. |
| 28 | 2026-11-02 | Docs update | `docs/operations` | #47 | Cập nhật tài liệu hướng dẫn chạy smoke test cho hệ thống microservice tại local. |
| 29 | 2026-11-02 | Hotfix | `gateway-service` | #48 | Sửa lỗi route gateway bị nghẽn do cấu hình max idle connection của Netty quá thấp. |
| 30 | 2026-11-02 | Hotfix | `slot-hold-engine` | #49 | Đảm bảo giải phóng key hold slot khi Redis nhận lệnh DEL từ background cleanup job. |
| 31 | 2026-11-02 | Docs update | `docs/architecture` | #50 | Tài liệu hoá thiết kế luồng xử lý saga điều phối đặt sân giữa booking và slot-hold. |
| 32 | 2026-11-02 | Hotfix | `facility-service` | #66 | Đồng bộ quyền SUPER_ADMIN, chỉ số todayRevenue và cách tính booking đã huỷ. |
| 33 | 2026-11-03 | Docs update | `docs/reference` | #67 | Tài liệu hoá cấu trúc response chuẩn API ResponseWrapper cho toàn bộ microservices. |
| 34 | 2026-11-03 | Hotfix | `auth-service` | #68 | Sửa lỗi trả về HTTP 500 thay vì 401 khi access token hết hạn nhưng không có header auth. |
| 35 | 2026-11-03 | Hotfix | `discovery-service` | #69 | Tối ưu câu truy vấn tìm kiếm slot khả dụng, loại bỏ N+1 query trên PostgreSQL. |
| 36 | 2026-11-04 | Docs update | `docs/operations` | #70 | Cập nhật sơ đồ phân vùng mạng (network partition) và cấu hình port các service. |
| 37 | 2026-11-04 | Hotfix | `payment-service` | #71 | Sửa lỗi mất kết nối webhook từ cổng PayOS khi header signature chứa ký tự đặc biệt. |
| 38 | 2026-11-04 | Hotfix | `payment-service` | #72 | Bổ sung transaction lock ngăn chặn 2 lần xác nhận thanh toán cùng 1 mã giao dịch. |
| 39 | 2026-11-04 | Docs update | `docs/reference` | #73 | Tài liệu hoá chi tiết schema bảng biểu và migration script của booking-service. |
| 40 | 2026-11-05 | Hotfix | `booking-service` | #74 | Khắc phục lỗi mất thông tin ghi chú khách hàng khi chuyển trạng thái booking. |
| 41 | 2026-11-05 | Hotfix | `reporting-service` | #75 | Sửa công thức tính doanh thu ngày trong reporting-service để loại trừ đơn đã hoàn tiền. |
| 42 | 2026-11-05 | Docs update | `docs/architecture` | #76 | Viết tài liệu tích hợp cơ chế bảo đảm tính nhất quán sau cùng (Eventual Consistency). |
| 43 | 2026-11-06 | Hotfix | `pos-retail-service` | #77 | Sửa lỗi trừ âm tồn kho sản phẩm khi 2 quầy thu ngân cùng bán mặt hàng cuối cùng. |
| 44 | 2026-11-06 | Hotfix | `pos-retail-service` | #78 | Validate mã vạch sản phẩm (barcode) chỉ chứa ký tự số chuẩn EAN-13. |
| 45 | 2026-11-06 | Docs update | `docs/operations` | #79 | Tài liệu hoá quy trình sao lưu và phục hồi dữ liệu cơ sở dữ liệu PostgreSQL định kỳ. |
| 46 | 2026-11-06 | Hotfix | `subscription-service` | #80 | Khắc phục lỗi không kích hoạt gói dịch vụ khi webhook gia hạn gửi về chậm. |
| 47 | 2026-11-07 | Docs update | `docs/reference` | #83 | Tài liệu hoá định dạng mã vé QR và cấu trúc token opaque ticket khi check-in. |
| 48 | 2026-11-07 | Hotfix | `staff-operations-service` | #84 | Sửa lỗi validate vé hợp lệ nhưng bị báo hết hạn trước 15 phút giờ bắt đầu slot. |
| 49 | 2026-11-07 | Hotfix | `tools` | #85 | Căn chỉnh dữ liệu demo runtime cho khớp schema mới nhất của 12 microservices. |
| 50 | 2026-11-07 | Docs update | `docs/operations` | #86 | Tài liệu hoá cấu hình biến môi trường AWS S3 và MinIO cho lưu trữ chứng từ. |
| 51 | 2026-11-07 | Hotfix | `slot-hold-engine` | #87 | Xử lý ngoại lệ JedisConnectionException khi Redis cluster chuyển đổi node master. |
| 52 | 2026-11-07 | Hotfix | `auth-service` | #88 | Sửa lỗi không xoá refreshToken trong cơ sở dữ liệu khi người dùng bấm logout. |
| 53 | 2026-11-07 | Docs update | `docs/reference` | #89 | Cập nhật tài liệu API reference cho voucher-service và điều kiện áp dụng mã. |
| 54 | 2026-11-08 | Hotfix | `voucher-service` | #90 | Sửa lỗi áp dụng voucher giảm 100% gây lỗi chia cho 0 khi tính phí giao dịch PayOS. |
| 55 | 2026-11-08 | Hotfix | `facility-service` | #91 | Khắc phục lỗi không thể cập nhật toạ độ GPS (vĩ độ, kinh độ) của chi nhánh mới. |
| 56 | 2026-11-08 | Docs update | `docs/architecture` | #92 | Tài liệu hoá luồng phát hành sự kiện Domain Events giữa các microservices qua Kafka. |
| 57 | 2026-11-08 | Hotfix | `notification-service` | #93 | Khắc phục rò rỉ bộ nhớ (memory leak) trong emitter kết nối SSE của notification-service. |
| 58 | 2026-11-08 | Hotfix | `slot-hold-engine` | #104 | Sửa lỗi không gửi thông báo WebSocket khi slot được mở khoá trước hạn. |
| 59 | 2026-11-08 | Hotfix | `facility-service` | #117 | Sửa lỗi upload ảnh sân vượt quá 5MB gây tràn bộ nhớ đệm (heap memory limit). |
| 60 | 2026-11-08 | Docs update | `docs/architecture` | #120 | Tài liệu hoá kiến trúc thông báo thời gian thực qua WebSocket STOMP. |
| 61 | 2026-11-08 | Hotfix | `discovery-service` | #129 | Cho route slot-conflicts (đã kiểm token) đi qua security chain, tránh bị chặn nhầm. |
| 62 | 2026-11-08 | Hotfix | `gateway-service` | #130 | Chặn workflow chạy trùng lặp và tăng tốc độ build gateway-service trên CI. |
| 63 | 2026-11-08 | Hotfix | `tools` | #131 | Sửa demo seed script cho khớp schema mới của voucher-service và discount percent. |
| 64 | 2026-11-09 | Docs update | `docs/business` | #134 | Phân tích khoảng trống nghiệp vụ so với monolith cũ và phương án kiến trúc AWS. |
| 65 | 2026-11-09 | Hotfix | `booking-service` | #135 | Sửa lỗi deadlock khi đặt đồng thời nhiều slot trên cùng một sân đấu. |
| 66 | 2026-11-09 | Hotfix | `booking-service` | #136 | Đảm bảo hoàn tiền tự động khi saga đặt sân thất bại tại bước trừ voucher. |
| 67 | 2026-11-09 | Docs update | `docs/architecture` | #174 | Chuẩn hoá và tài liệu hoá cấu trúc package đồng nhất cho 12 microservices. |
| 68 | 2026-11-09 | Hotfix | `notification-service` | #175 | Khắc phục lỗi gửi email trùng lặp khi subscriber nhận message retry từ hàng đợi. |
| 69 | 2026-11-09 | Hotfix | `facility-service` | #188 | Sửa lỗi tính sai giá tiền slot theo khung giờ cao điểm (Peak Hours Pricing). |
| 70 | 2026-11-09 | Docs update | `docs/operations` | #210 | Mô tả thiết kế lưu trữ S3, LocalStack và seed data không phụ thuộc Cloudinary. |
| 71 | 2026-11-09 | Hotfix | `gateway-service` | #213 | Sửa lỗi Redis RateLimiter không đếm đúng số request theo client IP thực tế. |
| 72 | 2026-11-09 | Hotfix | `auth-service` | #214 | Khắc phục lỗi Google OAuth2 redirect URI không tương thích khi chạy qua Gateway. |
| 73 | 2026-11-09 | Docs update | `docs/reference` | #219 | Cập nhật tài liệu API reference cho reporting-service và thông số xuất báo cáo. |
| 74 | 2026-11-09 | Hotfix | `booking-service` | #220 | Sửa lỗi không trả về danh sách slots khi booking nằm trong trạng thái CANCELLED. |
| 75 | 2026-11-09 | Hotfix | `slot-hold-engine` | #229 | Cho phép guestPhone tuỳ chọn khi người dùng đã xác thực danh tính qua token. |
| 76 | 2026-11-09 | Docs update | `docs/operations` | #231 | Tài liệu hoá quy trình kiểm thử tải (load test) hệ thống đặt sân bằng k6. |
| 77 | 2026-11-09 | Hotfix | `gateway-service` | #232 | Mở tạo booking công khai cho khách vãng lai kèm rate limit bảo vệ DoS. |
| 78 | 2026-11-09 | Hotfix | `facility-service` | #233 | Công khai số điện thoại, giờ mở cửa, ảnh sân và bổ sung trường card cho discovery. |
| 79 | 2026-11-09 | Docs update | `docs/business` | #234 | Tài liệu hoá quy trình vận hành và đối soát giao dịch tiền mặt của chi nhánh. |
| 80 | 2026-11-09 | Hotfix | `booking-service` | #235 | Cho phép khách vãng lai tra cứu và huỷ booking qua mã booking và số điện thoại. |
| 81 | 2026-11-09 | Hotfix | `payment-service` | #238 | Sửa lỗi không chuyển số điện thoại khách từ booking view sang sổ cái chi nhánh. |
| 82 | 2026-11-09 | Docs update | `docs/reference` | #239 | Cập nhật tài liệu chi tiết về vòng đời booking (Booking Lifecycle State Machine). |
| 83 | 2026-11-09 | Hotfix | `facility-service` | #241 | Backfill bank_bin cho các chi nhánh seed sẵn để tạo được mã VietQR thanh toán. |
| 84 | 2026-11-09 | Hotfix | `payment-service` | #242 | Trả mã lỗi HTTP 422 thay vì 503 khi chi nhánh chưa cấu hình tài khoản ngân hàng. |
| 85 | 2026-11-09 | Docs update | `docs/operations` | #243 | Tài liệu hoá cấu hình presign endpoint S3 khi truy cập từ mạng nội bộ và công cộng. |
| 86 | 2026-11-09 | Hotfix | `auth-service` | #244 | Cấu hình storage.s3.presign-endpoint cho phép mở URL tài liệu chủ sân từ web. |
| 87 | 2026-11-09 | Hotfix | `payment-service` | #245 | Cấu hình storage.s3.presign-endpoint cho URL biên lai và chứng từ hoàn tiền. |
| 88 | 2026-11-09 | Docs update | `docs/reference` | #246 | Tài liệu hoá các mã lỗi (Error Codes) thống nhất của toàn hệ thống microservice. |
| 89 | 2026-11-09 | Hotfix | `docker-compose` | #247 | Cấp quyền đọc ẩn danh ảnh công khai trên MinIO và fix presign host truy cập web. |
| 90 | 2026-11-09 | Hotfix | `booking-service` | #248 | Cho phép huỷ booking chưa thanh toán vào bất kỳ lúc nào mà không bị phạt phí. |
| 91 | 2026-11-10 | Docs update | `docs/architecture` | #251 | Tài liệu hoá quy trình loại bỏ Eureka Server và chuyển sang Docker DNS resolution. |
| 92 | 2026-11-10 | Hotfix | `gateway-service` | #252 | Cập nhật định tuyến tĩnh qua docker internal network sau khi gỡ Eureka client. |
| 93 | 2026-11-10 | Hotfix | `facility-service` | #253 | Sửa lỗi không hiển thị danh sách tiện ích đi kèm của sân (wifi, phòng tắm, gửi xe). |
| 94 | 2026-11-10 | Docs update | `docs/reference` | #254 | Tài liệu hoá API quản lý nhân viên và phân quyền chi nhánh trong staff-operations. |
| 95 | 2026-11-10 | Hotfix | `staff-operations-service` | #255 | Sửa lỗi nhân viên chi nhánh A có thể xem nhầm lịch check-in của chi nhánh B. |
| 96 | 2026-11-10 | Hotfix | `notification-service` | #256 | Khắc phục lỗi định dạng chuỗi JSON khi gửi push payload sang Firebase FCM/SNS. |
| 97 | 2026-11-10 | Docs update | `docs/operations` | #257 | Tài liệu hoá quy trình deploy các microservices lên Amazon EKS với Helm Charts. |
| 98 | 2026-11-10 | Hotfix | `voucher-service` | #258 | Validate ngày hết hạn voucher phải lớn hơn thời điểm áp dụng ít nhất 1 giờ. |
| 99 | 2026-11-10 | Hotfix | `reporting-service` | #259 | Sửa lỗi chia cho 0 khi tính tỷ lệ lấp đầy sân ngày không có slot nào được tạo. |
| 100 | 2026-11-11 | Docs update | `docs/reference` | #260 | Tài liệu hoá cấu trúc bảng giá linh hoạt theo thứ trong tuần và ngày lễ. |
| 101 | 2026-11-11 | Hotfix | `facility-service` | #261 | Sửa lỗi áp dụng sai giá ngày lễ khi timezone hệ thống máy chủ bị lệch. |
| 102 | 2026-11-11 | Hotfix | `tools` | #263 | Bổ sung script khởi tạo dữ liệu mẫu cho hệ thống đặt sân pickleball và cầu lông. |
| 103 | 2026-11-11 | Docs update | `docs/operations` | #265 | Cập nhật tài liệu cấu hình Health Check Probes (Liveness & Readiness) trên Kubernetes. |
| 104 | 2026-11-11 | Hotfix | `slot-hold-engine` | #268 | Sửa lỗi không tự động giải phóng key hold khi client đóng kết nối đột ngột. |
| 105 | 2026-11-11 | Hotfix | `docker-compose` | #275 | Giữ giới hạn gói của facility ở giá trị mặc định cho tới khi subscription đồng bộ. |
| 106 | 2026-11-11 | Docs update | `docs/business` | #280 | Tài liệu hoá bảng phân quyền RBAC (Role-Based Access Control) cho 4 nhóm người dùng. |
| 107 | 2026-11-11 | Hotfix | `auth-service` | #282 | Khắc phục lỗi tài khoản bị khoá nhưng vẫn gọi được các endpoint đã được cache quyền. |
| 108 | 2026-11-11 | Hotfix | `payment-service` | #285 | Sửa lỗi mất thông tin chi nhánh thụ hưởng trong giao dịch thanh toán trực tuyến. |
| 109 | 2026-11-11 | Docs update | `docs/reference` | #290 | Tài liệu hoá quy chuẩn xử lý ngoại lệ toàn cục GlobalExceptionHandler cho Spring Boot. |
| 110 | 2026-11-11 | Hotfix | `booking-service` | #295 | Sửa lỗi hiển thị sai tổng tiền đơn khi áp dụng voucher giảm giá có giới hạn tối đa. |
| 111 | 2026-11-11 | Hotfix | `tools` | #338 | Thay ảnh ngoại cảnh chi nhánh bằng ảnh sân thể thao thực tế chất lượng cao. |
| 112 | 2026-11-12 | Docs update | `docs/operations` | #340 | Cập nhật tài liệu hướng dẫn giám sát hệ thống với Prometheus và Grafana Dashboards. |
| 113 | 2026-11-12 | Hotfix | `gateway-service` | #345 | Sửa lỗi memory leak trong WebFilter khi xử lý luồng phản hồi streaming dung lượng lớn. |
| 114 | 2026-11-12 | Hotfix | `subscription-service` | #387 | Bổ sung endpoint nội bộ kiểm tra hạn ngạch gói và lý do từ chối khi đăng nhập. |
| 115 | 2026-11-12 | Docs update | `docs/architecture` | #390 | Tài liệu hoá giải pháp bảo vệ dữ liệu nhạy cảm PII và tuân thủ mã hoá trong CSDL. |
| 116 | 2026-11-12 | Hotfix | `notification-service` | #395 | Bổ sung REST API và WebSocket cho chuông thông báo in-app trên giao diện web. |
| 117 | 2026-11-12 | Hotfix | `payment-service` | #452 | Tự động tạo yêu cầu hoàn tiền khi khách vãng lai hoặc chủ sân huỷ booking hợp lệ. |
| 118 | 2026-11-12 | Hotfix | `payment-service` | #453 | Bỏ qua các sự kiện hoàn tiền quá hạn thay vì retry vô hạn làm nghẽn hàng đợi. |
| 119 | 2026-11-12 | Docs update | `docs/operations` | #454 | Tài liệu hoá giải pháp xử lý lỗi chịu lỗi khi dịch vụ email bên thứ ba gặp sự cố. |
| 120 | 2026-11-12 | Hotfix | `notification-service` | #455 | Đảm bảo vẫn gửi thông báo in-app và push notification khi nhà cung cấp email lỗi. |
| 121 | 2026-11-12 | Docs update | `docs/README.md` | #456 | Cấu trúc lại toàn bộ README, tái tổ chức thư mục docs và khởi tạo thư mục report. |
| 122 | 2026-11-12 | Docs update | `docs/README.md` | #457 | Dịch toàn bộ README sang tiếng Việt và tích hợp sơ đồ kiến trúc microservices. |
| 123 | 2026-11-12 | Hotfix | `staff-operations-service` | #459 | Trả lý do từ chối chi tiết khi check-in không hợp lệ và mở route đăng ký thiết bị. |
| 124 | 2026-11-12 | Hotfix | `staff-operations-service` | #461 | Hỗ trợ nhân viên check-in trực tiếp theo mã booking ID ngay trên màn hình lịch ngày. |
| 125 | 2026-11-12 | Docs update | `docs/reference` | #463 | Bổ sung hướng dẫn điều hướng mã nguồn chi tiết cho tất cả 12 microservices. |
| 126 | 2026-11-12 | Docs update | `docs/REPOSITORY_REFERENCE.md` | #465 | Mở rộng tài liệu tham chiếu repository theo từng service, cơ sở dữ liệu và hạ tầng. |
| 127 | 2026-11-12 | Hotfix | `pos-retail-service` | #473 | Sửa lỗi không đồng bộ mã hoá đơn bán lẻ với hệ thống kế toán chi nhánh. |
| 128 | 2026-11-12 | Hotfix | `pos-retail-service` | #474 | Thêm kiểm tra số lượng tồn kho trước khi in phiếu tạm tính cho khách hàng. |
| 129 | 2026-11-12 | Docs update | `docs/business` | #475 | Tài liệu hoá quy trình quản lý tồn kho dụng cụ thể thao tại quầy bán lẻ. |
| 130 | 2026-11-12 | Hotfix | `voucher-service` | #476 | Khắc phục lỗi mã giảm giá bị áp dụng vượt số lần cho phép của một tài khoản. |
| 131 | 2026-11-12 | Hotfix | `voucher-service` | #477 | Bổ sung index cho cột voucher_code và expired_at trong bảng voucher để tăng tốc query. |
| 132 | 2026-11-12 | Docs update | `docs/reference` | #478 | Cập nhật tài liệu gRPC schema protobuf cho voucher-service phiên bản v2. |
| 133 | 2026-11-12 | Hotfix | `auth-service` | #479 | Sửa lỗi mã hoá mật khẩu BCrypt bị chậm do độ phức tạp cost factor quá cao trên staging. |
| 134 | 2026-11-12 | Hotfix | `auth-service` | #480 | Bổ sung validate độ dài tối thiểu 8 ký tự kèm chữ hoa, số và ký tự đặc biệt cho mật khẩu. |
| 135 | 2026-11-12 | Docs update | `docs/architecture` | #481 | Tài liệu hoá cơ chế phân tán Session và Token Revocation List với Redis Pub/Sub. |
| 136 | 2026-11-12 | Hotfix | `facility-service` | #482 | Sửa lỗi sai trạng thái bảo trì sân (MAINTENANCE) không được cập nhật lên giao diện đặt sân. |
| 137 | 2026-11-12 | Hotfix | `facility-service` | #483 | Thêm ràng buộc khoá ngoại giữa court và court_type để tránh dữ liệu mồ côi. |
| 138 | 2026-11-12 | Docs update | `docs/reference` | #484 | Tài liệu hoá API quản lý lịch biểu định kỳ hàng tuần cho từng cụm sân thể thao. |
| 139 | 2026-11-12 | Hotfix | `discovery-service` | #485 | Sửa lỗi lọc khoảng giá tìm kiếm sân không bao gồm cận trên (inclusive boundary). |
| 140 | 2026-11-12 | Hotfix | `discovery-service` | #486 | Tối ưu hoá chỉ mục Full-Text Search cho tên sân và địa chỉ quận huyện tại Hà Nội/TP.HCM. |
| 141 | 2026-11-12 | Docs update | `docs/operations` | #487 | Tài liệu hoá cấu hình OpenTelemetry Tracing và Jaeger theo dõi distributed trace. |
| 142 | 2026-11-12 | Hotfix | `booking-service` | #488 | Khắc phục lỗi không hoàn tiền khi khách vãng lai thanh toán thừa số tiền quy định. |
| 143 | 2026-11-12 | Hotfix | `booking-service` | #489 | Đảm bảo log chi tiết nguyên nhân khi huỷ booking từ phía chủ sân hoặc quản trị viên. |
| 144 | 2026-11-12 | Docs update | `docs/business` | #490 | Tài liệu hoá quy định phạt tiền huỷ sân muộn (Late Cancellation Penalty Rules). |
| 145 | 2026-11-12 | Hotfix | `slot-hold-engine` | #491 | Sửa lỗi rò rỉ kết nối Redis Connection khi có exception trong hàm holdMultipleSlots. |
| 146 | 2026-11-12 | Hotfix | `slot-hold-engine` | #492 | Tăng độ chính xác thời gian đếm ngược countdown hold slot từ giây sang mili-giây. |
| 147 | 2026-11-12 | Docs update | `docs/reference` | #493 | Cập nhật tài liệu API chi tiết cho endpoint gia hạn thời gian giữ chỗ (extend hold). |
| 148 | 2026-11-12 | Hotfix | `payment-service` | #494 | Sửa lỗi trùng lặp mã thanh toán PayOS orderCode khi chạy nhiều cụm payment container. |
| 149 | 2026-11-12 | Hotfix | `payment-service` | #495 | Bổ sung cơ chế retry cấp số nhân (exponential backoff) khi gọi API kiểm tra giao dịch PayOS. |
| 150 | 2026-11-12 | Docs update | `docs/operations` | #496 | Tài liệu hoá hướng dẫn xử lý sự cố khi cổng thanh toán trực tuyến bị gián đoạn. |
| 151 | 2026-11-12 | Hotfix | `notification-service` | #497 | Sửa lỗi format ngày giờ trong tin nhắn SMS nhắc lịch thi đấu bị lệch sang múi giờ UTC. |
| 152 | 2026-11-12 | Hotfix | `notification-service` | #498 | Tự động làm sạch các token thiết bị push không còn hoạt động (unregistered devices). |
| 153 | 2026-11-12 | Docs update | `docs/architecture` | #499 | Tài liệu hoá mô hình Message Bus sử dụng RabbitMQ / Kafka cho các luồng thông báo sự kiện. |
| 154 | 2026-11-12 | Hotfix | `reporting-service` | #500 | Khắc phục lỗi xuất file Excel báo cáo doanh thu bị lỗi font tiếng Việt có dấu. |
| 155 | 2026-11-12 | Hotfix | `reporting-service` | #501 | Tối ưu câu truy vấn tổng hợp báo cáo tài chính tháng theo từng môn thể thao. |
