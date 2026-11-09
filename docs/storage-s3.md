# Lưu trữ file và ảnh trên AWS S3

Tài liệu này mô tả cách các service lưu file/ảnh sau khi chuyển từ Cloudinary sang S3: thiết kế, key layout, public và presigned, biến môi trường, chạy local bằng LocalStack, và hạ tầng cần chuẩn bị (bucket, IAM, CloudFront). Hạ tầng thật do repo `fcourt-infra` quản lý, tài liệu này chỉ nêu yêu cầu.

## 1. Thiết kế

- Một bucket private cho mỗi môi trường (ví dụ `fcourt-prod-media`), bật Block Public Access, mã hóa SSE-S3 mặc định. Không object nào public trực tiếp trên S3.
- Mỗi service dùng AWS SDK for Java v2 (`software.amazon.awssdk:s3`, version quản lý bằng BOM `2.57.5`) và **default credential provider chain**: EKS Pod Identity trong cluster, AWS profile hoặc biến `AWS_*` ở máy local. Không có access key trong config, `application.yml` hay biến môi trường của service.
- Mỗi service giữ nguyên port cũ (`DocumentStorage` ở auth, `ImageStorage` ở discovery và facility, `ReceiptStorage` ở payment) nên code gọi gần như không đổi. Chỉ adapter đổi từ Cloudinary sang S3.
- Database lưu **object key** khi dữ liệu là private hoặc cần đổi domain về sau; response trả URL được tạo từ key đó:
  - File **public** (avatar, ảnh chi nhánh, ảnh review): URL là `${storage.public-base-url}/${key}`. Trên môi trường thật `public-base-url` là domain CloudFront, local là URL của LocalStack.
  - File **nhạy cảm** (giấy tờ chủ sân ở auth, biên lai và chứng từ hoàn tiền ở payment): không bao giờ public. Response trả **presigned GET URL** sống 10 phút (`storage.s3.presign-ttl`), tạo lúc đọc cho người đã được phân quyền.
- Content type được đọc từ byte đầu của file (magic bytes), không tin header của client. Mỗi lần put đều set `Content-Type` và `Cache-Control`: file public dùng `public, max-age=31536000, immutable` (key chứa UUID, không ghi đè), file private dùng `private, no-store`.
- Xóa theo key. Giá trị cũ là URL tuyệt đối (Cloudinary, ảnh Google) thì **không xóa từ xa**, chỉ ghi log và vẫn đọc nguyên giá trị đó nên dữ liệu cũ vẫn hiển thị.
- Không có service nào cần S3 để khởi động. Thiếu `STORAGE_S3_BUCKET` thì service vẫn chạy, các API upload trả 503.

## 2. Key layout

| Service | Prefix | Mẫu key | Truy cập |
|---|---|---|---|
| auth | `avatars/` | `avatars/{userId}/{uuid}.{jpg,png,webp}` | public (CloudFront) |
| auth | `owner-documents/` | `owner-documents/legal/{userId}/{uuid}.{jpg,png,webp,pdf}`, `owner-documents/court/{userId}/{uuid}.{...}` | private, presigned |
| facility | `branches/` | `branches/{branchId}/{uuid}.{jpg,png,webp}` | public (CloudFront) |
| discovery | `reviews/` | `reviews/{userId}/{uuid}.{jpg,png,webp}` | public (CloudFront) |
| payment | `receipts/` | `receipts/{uuid}.{jpg,png}` | private, presigned |
| payment | `refund-proofs/` | `refund-proofs/{uuid}.{jpg,png}` | private, presigned |

Khác biệt cần nhớ giữa các service:

- **auth**: cột `users.avatar_url` và `user_documents.url` giờ lưu key. `GET /api/auth/profile` trả `avatarUrl` là `${public-base-url}/${key}`; API super admin trả `documents[].url` là presigned URL. Giá trị bắt đầu bằng `http://` hoặc `https://` (Google, Cloudinary cũ) được trả nguyên.
- **facility**: bảng `branch_images` lưu `url` (đã ghép sẵn với public base) và `public_id` (là key). Hàng cũ có `public_id` dạng `fcourt/branches/...` là dữ liệu Cloudinary, bị bỏ qua khi xóa.
- **discovery**: `reviews.image_urls` lưu URL đầy đủ và được kiểm tra bằng allow-list (`ReviewImagePolicy`) với `public-base-url` và tiền tố `reviews/{userId}/`. Job dọn ảnh mồ côi liệt kê theo prefix `reviews/` trong S3.
- **payment**: `payments.bill_image_url` và `refund_requests.refund_proof_image_url` lưu key; event Kafka (`PaymentSubmittedEvent`, `RefundCompletedEvent`) cũng mang key. Mọi field `receiptImageUrl` và `transferProofUrl` trong API trả presigned URL.
- **booking**: nhận `receiptImageUrl` (key) từ event của payment và lưu lại; khi trả cho client thì đổi thành presigned URL (cần quyền `s3:GetObject` trên `receipts/*`).

## 3. Thay đổi hợp đồng API

Tên field và kiểu dữ liệu giữ nguyên, Flutter và web không cần sửa. Chỉ **giá trị** đổi:

- URL không còn là `res.cloudinary.com` mà là domain CloudFront (public) hoặc URL presigned (private).
- URL presigned **hết hạn sau 10 phút**. Client không được lưu cache lâu hoặc chia sẻ URL này; cần hiển thị thì gọi lại API để lấy URL mới. Response của payment đã là `Cache-Control: no-store`.
- Upload giấy tờ chủ sân: server từ chối nội dung không phải JPEG, PNG, WebP, PDF (nhận diện bằng byte) với 400.

## 4. Biến môi trường

Áp dụng cho auth, discovery, facility, payment (booking chỉ cần các biến kết nối S3 và bucket).

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `STORAGE_S3_BUCKET` | rỗng | Tên bucket. Rỗng thì tắt upload (503) |
| `STORAGE_S3_REGION` | `ap-southeast-1` | Region của bucket |
| `STORAGE_S3_ENDPOINT` | rỗng | Chỉ dùng cho LocalStack hoặc MinIO, ví dụ `http://localhost:4566`. Đặt biến này sẽ tự bật path-style |
| `STORAGE_S3_PATH_STYLE` | `false` | Ép path-style addressing |
| `STORAGE_PUBLIC_BASE_URL` | rỗng | Base URL của file public (domain CloudFront). Rỗng thì dùng `${endpoint}/${bucket}` hoặc host của bucket |
| `STORAGE_S3_PRESIGN_TTL` | `10m` | Thời gian sống của presigned URL (auth, payment, booking) |

Các biến cũ bị xóa: `AUTH_CLOUDINARY_URL`, `CLOUDINARY_URL`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `STORAGE_PROVIDER`. Cần gỡ chúng khỏi manifest/secret của môi trường.

Credential: không đặt access key. Trong cluster dùng EKS Pod Identity gắn role cho service account của từng service. Local dùng `aws configure`/`AWS_PROFILE`, hoặc `AWS_ACCESS_KEY_ID=test` với LocalStack.

## 5. Chạy local với LocalStack

```bash
docker compose up -d localstack        # S3 ở http://localhost:4566, tự tạo bucket private fcourt-local
```

Image được ghim `localstack/localstack:3.8.1` vì các bản mới yêu cầu auth token. Chạy một service trên máy host:

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
export STORAGE_S3_BUCKET=fcourt-local
export STORAGE_S3_ENDPOINT=http://localhost:4566
export STORAGE_PUBLIC_BASE_URL=http://localhost:4566/fcourt-local
mvn -f facility-service/pom.xml spring-boot:run
```

Kiểm tra object: `aws --endpoint-url http://localhost:4566 s3 ls s3://fcourt-local --recursive`.

Lưu ý khi chạy local:

- discovery chấp nhận base `http://localhost:...` (chỉ loopback) để upload ảnh review thử được. Môi trường thật vẫn bắt buộc HTTPS.
- LocalStack không ép Block Public Access, nên URL public trỏ thẳng vào LocalStack đọc được không cần ký. Presigned URL vẫn được tạo và dùng được.
- Ảnh đại diện lưu vào review snapshot bị bỏ nếu không phải HTTPS (`ReviewerProfileClient`), nên local sẽ không thấy avatar trong review.

Test tự động dùng Testcontainers LocalStack (`org.testcontainers:localstack`), không cần tài khoản AWS. Có Docker là chạy được `mvn -f <service>/pom.xml verify`.

## 6. Hạ tầng cần chuẩn bị

### 6.1 Bucket

- Tên theo môi trường, region `ap-southeast-1`.
- **Block Public Access**: bật cả 4 tùy chọn. **Object Ownership**: Bucket owner enforced (tắt ACL).
- Mã hóa mặc định SSE-S3 (hoặc SSE-KMS nếu cần, khi đó thêm quyền `kms:Decrypt`/`kms:GenerateDataKey` cho role).
- Versioning: tùy chọn. Nếu bật thì kèm lifecycle xóa phiên bản cũ sau 30 ngày.
- **Bắt buộc bật TLS-only** bằng bucket policy từ chối `aws:SecureTransport = false`.
- **CORS**: không cần, vì client không upload thẳng lên S3 (file đi qua service) và ảnh được tải qua `<img>` từ CloudFront. Chỉ thêm CORS nếu web admin cần `fetch` ảnh sang origin khác (cho `GET` từ origin của web).

### 6.2 Lifecycle

| Prefix | Rule |
|---|---|
| tất cả | Abort incomplete multipart upload sau 7 ngày |
| `receipts/`, `refund-proofs/` | Biên lai upload nhưng không gắn vào giao dịch (lỗi giữa chừng, thua race) được payment xóa best-effort; thêm rule chuyển `STANDARD_IA` sau 90 ngày. Không tự xóa vì là chứng từ tài chính, thời hạn lưu theo quy định kế toán |
| `owner-documents/` | Giữ theo chính sách lưu hồ sơ; chuyển `STANDARD_IA` sau 90 ngày |
| `reviews/` | Ảnh review chưa gắn review được discovery dọn (job `ReviewImageCleanup`, mặc định dry-run, bật bằng `discovery.reviews.images.cleanup.dry-run=false`) |

### 6.3 CloudFront (tùy chọn nhưng khuyến nghị cho public)

- Một distribution với origin là bucket qua **Origin Access Control (OAC)**, bucket policy cho phép `cloudfront.amazonaws.com` với điều kiện `AWS:SourceArn` là distribution.
- Chỉ cho phép path public bằng cache behavior: `avatars/*`, `branches/*`, `reviews/*`. Các prefix `owner-documents/*`, `receipts/*`, `refund-proofs/*` **không** được route qua CloudFront (trả 403/404 ở distribution), để file nhạy cảm chỉ đọc qua presigned URL.
- Cache policy `CachingOptimized` phù hợp vì key chứa UUID và không bao giờ ghi đè. Đặt domain này vào `STORAGE_PUBLIC_BASE_URL` của auth, discovery, facility (ví dụ `https://media.fcourt.example`).
- Chưa có CloudFront thì đặt tạm `STORAGE_PUBLIC_BASE_URL` về host của bucket sẽ **không** đọc được (bucket private), nên cần CloudFront trước khi dùng ảnh public trên môi trường thật.

### 6.4 IAM theo service (EKS Pod Identity)

Mỗi service một role riêng, gắn với service account qua Pod Identity association. Quyền tối thiểu, ghi chú `BUCKET` là tên bucket:

| Service | Quyền | Resource |
|---|---|---|
| auth | `s3:PutObject`, `s3:DeleteObject`, `s3:GetObject` | `arn:aws:s3:::BUCKET/avatars/*`, `arn:aws:s3:::BUCKET/owner-documents/*` |
| facility | `s3:PutObject`, `s3:DeleteObject` | `arn:aws:s3:::BUCKET/branches/*` |
| discovery | `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket` (có điều kiện prefix) | `arn:aws:s3:::BUCKET/reviews/*` và `arn:aws:s3:::BUCKET` với `s3:prefix = reviews/*` |
| payment | `s3:PutObject`, `s3:DeleteObject`, `s3:GetObject` | `arn:aws:s3:::BUCKET/receipts/*`, `arn:aws:s3:::BUCKET/refund-proofs/*` |
| booking | `s3:GetObject` | `arn:aws:s3:::BUCKET/receipts/*` |

`s3:GetObject` ở auth, payment, booking cần vì presigned URL được ký bằng quyền của role: URL chỉ dùng được nếu role đó đọc được object. Ví dụ policy cho facility:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::BUCKET/branches/*"
    }
  ]
}
```

Ví dụ discovery (có `ListBucket` cho job dọn ảnh):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::BUCKET/reviews/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::BUCKET",
      "Condition": { "StringLike": { "s3:prefix": ["reviews/*"] } }
    }
  ]
}
```

Role cần trust policy cho `pods.eks.amazonaws.com` (`sts:AssumeRole`, `sts:TagSession`). Thời hạn presigned URL không vượt quá thời hạn credential của role (Pod Identity xoay credential khoảng mỗi vài giờ, đủ cho TTL 10 phút).

## 7. Di chuyển dữ liệu cũ

- Hàng đang giữ URL Cloudinary vẫn hiển thị đúng vì được trả nguyên. Seed Flyway đã chạy (auth V4, payment V2) không bị sửa.
- Muốn bỏ hẳn Cloudinary thì cần job riêng tải từng file về, ghi lên S3 theo key layout ở trên rồi cập nhật cột; việc này chưa nằm trong phạm vi thay đổi hiện tại và cần quyết định của owner (xem câu hỏi mở trong PR).
- Dữ liệu seed mới (tools/demo_seed) dùng URL HTTPS cho ảnh public và key `receipts/...` cho biên lai.
