# Cấu trúc package chuẩn của backend FCourt

Tài liệu này mô tả cấu trúc package chung cho các service Spring Boot trong repo. Mục tiêu: mở service nào cũng biết ngay class nằm ở đâu, review PR nhanh hơn, người mới vào không phải đoán.

Đây là refactor thuần cấu trúc. Khi chuyển một service sang chuẩn này, không đổi behaviour, API contract, DB schema, Kafka topic hay config key.

## Cây thư mục mẫu

Package gốc ví dụ `com.fcourt.booking`:

```
com.fcourt.booking
├── BookingApplication.java        (file duy nhất được nằm ở root)
├── config/                        Spring config, security, properties class
├── controller/                    REST controller (và gRPC service impl)
├── service/                       business logic
├── repository/                    Spring Data repository / class truy cập DB
├── entity/                        JPA entity (hoặc row model của persistence)
├── dto/
│   ├── request/                   request DTO (record)
│   └── response/                  response DTO (record)
├── enums/                         enum
├── mapper/                        MapStruct mapper
├── exception/                     GlobalExceptionHandler + custom exception
├── client/                        gọi ra ngoài: HTTP/gRPC client tới service khác, bên thứ 3
└── messaging/
    ├── producer/                  Kafka producer, outbox relay
    ├── consumer/                  Kafka listener
    └── event/                     event payload (record)
```

Folder nào service không cần thì bỏ qua, không tạo folder rỗng.

## Quy tắc từng folder

1. **Root**: chỉ có class `<Name>Application`. Không để controller, service hay helper ở root.
2. **config/**: `@Configuration`, cấu hình security, `@ConfigurationProperties`, bean `Clock`, cấu hình Kafka error handler.
3. **controller/**: chỉ nhận request, validate, gọi `service`, trả response. Không viết business logic và không truy cập DB trực tiếp. Nếu service expose gRPC, class gRPC service impl và gRPC server để ở `controller/` (hoặc tách `grpc/` nếu nhiều class). Code sinh từ proto giữ nguyên nơi Maven sinh ra, không di chuyển.
4. **service/**: class `@Service` cụ thể, chứa business logic và transaction. Không tạo cặp interface + impl cho mỗi service. Chỉ dùng interface khi là port có từ 2 implementation trở lên.
5. **repository/**: Spring Data repository, hoặc class truy cập DB bằng JDBC. Chỉ chứa query, không chứa business rule.
6. **entity/**: class ánh xạ bảng DB. Các package cũ tên `model/` hoặc `persistence/` đổi về đây. Entity không đi ra ngoài API.
   Với service dùng JDBC store (không có JPA), `entity/` chứa các row record của store. MapStruct chỉ dùng cho mapping đơn giản field-to-field giữa row record/entity và response DTO; service không có mapping như vậy thì không thêm MapStruct.
7. **dto/request/, dto/response/**: dùng `record`. Request chứa annotation validation. Response không lộ field nội bộ. Mỗi DTO là một file riêng, không gom nhiều record vào một class "Requests"/"Views".
8. **enums/**: mọi enum dùng chung giữa các tầng.
9. **mapper/**: MapStruct, `@Mapper(componentModel = "spring")`, mỗi aggregate một mapper. Chỉ dùng cho mapping field đơn giản entity <-> DTO. Logic mapping phức tạp (tính toán, gọi service khác, điều kiện nghiệp vụ) để trong `service`, không nhét vào mapper.
10. **exception/**: `GlobalExceptionHandler` (`@RestControllerAdvice`) và các custom exception.
11. **client/**: mọi lời gọi đồng bộ ra ngoài (HTTP, gRPC) tới service khác hoặc bên thứ 3. Class nhận interface/port khi cần mock.
12. **messaging/**: mọi thứ liên quan Kafka. `producer/` gồm producer và outbox relay, `consumer/` gồm listener, `event/` gồm payload record. Tên topic và group id giữ nguyên.
13. **Helper nhỏ**: đặt vào folder của thành phần mà nó phục vụ. Chỉ tạo `util/` khi class thật sự dùng chung và không thuộc folder nào.

## Quy tắc chung

- Dependency đi một chiều: `controller` -> `service` -> `repository` / `client` / `messaging`. `service` không gọi ngược `controller`.
- Không trả entity ra khỏi `controller`; luôn qua `dto/response`.
- Tên class kết thúc theo vai trò: `*Controller`, `*Service`, `*Repository`, `*Mapper`, `*Client`, `*Producer`, `*Consumer`, `*Event`, `*Request`, `*Response`.
- Test đặt cùng package với class được test, mirror đúng layout của `src/main/java`.
- Khi thêm class mới, đặt đúng folder ngay từ đầu; không tạo folder ngoài danh sách trên khi chưa thống nhất.

## Mapper (MapStruct)

Thêm dependency `org.mapstruct:mapstruct` và annotation processor `mapstruct-processor` vào `maven-compiler-plugin`. Nếu service dùng Lombok thì thêm `lombok-mapstruct-binding`, và Lombok phải đứng trước MapStruct trong `annotationProcessorPaths`.

```java
@Mapper(componentModel = "spring")
public interface BookingMapper {
    BookingResponse toResponse(Booking booking);
}
```

## Khi migrate service cũ

1. Dùng `git mv` để giữ lịch sử file.
2. Sửa `package` và `import`; kiểm tra `scanBasePackages`, `@EntityScan`, `@EnableJpaRepositories`, `logback`, `application.yml`, Dockerfile, workflow CI nếu có nhắc tên class hoặc package.
3. Chạy `mvn verify` cho service, số test trước và sau phải bằng nhau.
4. Một PR cho một service, không gộp nhiều service.
