# Lưu trữ file và ảnh trên AWS S3

Tài liệu này mô tả cách các service lưu file/ảnh sau khi chuyển từ Cloudinary sang S3: thiết kế, key layout, public và presigned, biến môi trường, chạy local bằng S3 thật, và hạ tầng cần chuẩn bị (bucket, bucket policy, IAM). Hạ tầng thật do repo `fcourt-infra` quản lý, tài liệu này chỉ nêu yêu cầu.

## 1. Thiết kế

- Một bucket cho mỗi môi trường (ví dụ `fcourt-prod-media-<account-id>`), mã hóa SSE-S3 mặc định. Không dùng CloudFront. Chỉ ba prefix ảnh public `avatars/*`, `branches/*`, `reviews/*` được đọc ẩn danh (`s3:GetObject`) qua bucket policy; mọi prefix khác vẫn private.
- Mỗi service dùng AWS SDK for Java v2 (`software.amazon.awssdk:s3`, version quản lý bằng BOM `2.57.5`) và **default credential provider chain**: EKS Pod Identity trong cluster, AWS profile hoặc biến `AWS_*` ở máy local. Không có access key trong config, `application.yml` hay biến môi trường của service.
- Mỗi service giữ nguyên port cũ (`DocumentStorage` ở auth, `ImageStorage` ở discovery và facility, `ReceiptStorage` ở payment) nên code gọi gần như không đổi. Chỉ adapter đổi từ Cloudinary sang S3.
- Database lưu **object key** khi dữ liệu là private hoặc cần đổi domain về sau; response trả URL được tạo từ key đó:
  - File **public** (avatar, ảnh chi nhánh, ảnh review): URL là `${storage.public-base-url}/${key}`. Trên môi trường thật `public-base-url` là URL virtual-hosted theo region của bucket (`https://<bucket>.s3.ap-southeast-1.amazonaws.com`), local cũng vậy (bucket local riêng).
  - File **nhạy cảm** (giấy tờ chủ sân ở auth, biên lai và chứng từ hoàn tiền ở payment): không bao giờ public. Response trả **presigned GET URL** sống 10 phút (`storage.s3.presign-ttl`), tạo lúc đọc cho người đã được phân quyền.
- Content type được đọc từ byte đầu của file (magic bytes), không tin header của client. Mỗi lần put đều set `Content-Type` và `Cache-Control`: file public dùng `public, max-age=31536000, immutable` (key chứa UUID, không ghi đè), file private dùng `private, no-store`.
- Xóa theo key. Giá trị cũ là URL tuyệt đối (Cloudinary, ảnh Google) thì **không xóa từ xa**, chỉ ghi log và vẫn đọc nguyên giá trị đó nên dữ liệu cũ vẫn hiển thị.
- Không có service nào cần S3 để khởi động. Thiếu `STORAGE_S3_BUCKET` thì service vẫn chạy, các API upload trả 503.

## 2. Key layout

| Service | Prefix | Mẫu key | Truy cập |
|---|---|---|---|
| auth | `avatars/` | `avatars/{userId}/{uuid}.{jpg,png,webp}` | public (bucket policy) |
| auth | `owner-documents/` | `owner-documents/legal/{userId}/{uuid}.{jpg,png,webp,pdf}`, `owner-documents/court/{userId}/{uuid}.{...}` | private, presigned |
| facility | `branches/` | `branches/{branchId}/{uuid}.{jpg,png,webp}` | public (bucket policy) |
| discovery | `reviews/` | `reviews/{userId}/{uuid}.{jpg,png,webp}` | public (bucket policy) |
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

- URL không còn là `res.cloudinary.com` mà là URL S3 của bucket (public, `https://<bucket>.s3.ap-southeast-1.amazonaws.com/<key>`) hoặc URL presigned (private).
- URL presigned **hết hạn sau 10 phút**. Client không được lưu cache lâu hoặc chia sẻ URL này; cần hiển thị thì gọi lại API để lấy URL mới. Response của payment đã là `Cache-Control: no-store`.
- Upload giấy tờ chủ sân: server từ chối nội dung không phải JPEG, PNG, WebP, PDF (nhận diện bằng byte) với 400.

## 4. Biến môi trường

Áp dụng cho auth, discovery, facility, payment (booking chỉ cần các biến kết nối S3 và bucket).

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `STORAGE_S3_BUCKET` | rỗng | Tên bucket. Rỗng thì tắt upload (503) |
| `STORAGE_S3_REGION` | `ap-southeast-1` | Region của bucket |
| `STORAGE_S3_ENDPOINT` | rỗng | Chỉ dùng cho kho tương thích S3 như MinIO (test), để trống với AWS S3. Đặt biến này sẽ tự bật path-style |
| `STORAGE_S3_PATH_STYLE` | `false` | Ép path-style addressing |
| `STORAGE_PUBLIC_BASE_URL` | rỗng | Base URL của file public: URL virtual-hosted theo region của bucket, ví dụ `https://<bucket>.s3.ap-southeast-1.amazonaws.com` (không có dấu `/` cuối). Rỗng thì dùng `${endpoint}/${bucket}` hoặc host của bucket |
| `STORAGE_S3_PRESIGN_TTL` | `10m` | Thời gian sống của presigned URL (auth, payment, booking) |

Các biến cũ bị xóa: `AUTH_CLOUDINARY_URL`, `CLOUDINARY_URL`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `STORAGE_PROVIDER`. Cần gỡ chúng khỏi manifest/secret của môi trường.

Credential: không đặt access key. Trong cluster dùng EKS Pod Identity gắn role cho service account của từng service. Local dùng `aws configure`/`AWS_PROFILE`, hoặc `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` của IAM user `fcourt-local-s3` (Compose, xem mục 5).

## 5. Chạy local với S3 thật

Docker Compose và service chạy trên máy host đều dùng **bucket S3 thật** dành riêng cho local, không có MinIO hay LocalStack trong Compose.

- Bucket: `fcourt-local-media-870853685410`, region `ap-southeast-1`, nằm ở `envs/shared` của `fcourt-infra` nên không bị xoá khi hạ cluster EKS. Không dùng chung với bucket của EKS: job dọn ảnh mồ côi của discovery xoá object không có trong DB của nó, nên hai môi trường dùng chung bucket sẽ xoá ảnh của nhau.
- Credential: IAM user `fcourt-local-s3`, chỉ có quyền Get/Put/Delete/List trên bucket đó. Access key xin tech lead, đặt vào `.env` (đã gitignore, **không commit**). Xem khối S3 trong `.env.example`.
- Compose tự đặt `STORAGE_PUBLIC_BASE_URL=https://<bucket>.s3.<region>.amazonaws.com`, `AWS_REGION`, và không đặt endpoint/path-style/presign-endpoint (mặc định của app đã đúng cho S3 thật). Bucket mặc định là `fcourt-local-media-870853685410`. Thiếu key thì service vẫn khởi động, chỉ lời gọi S3 lỗi (không tìm được credential).

```bash
# .env: STORAGE_S3_BUCKET, STORAGE_S3_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
docker compose --profile core up -d --build
```

Chạy một service trên máy host:

```bash
export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...   # key của fcourt-local-s3
export STORAGE_S3_BUCKET=fcourt-local-media-870853685410
export STORAGE_PUBLIC_BASE_URL=https://fcourt-local-media-870853685410.s3.ap-southeast-1.amazonaws.com
mvn -f facility-service/pom.xml spring-boot:run
```

Kiểm tra object: `aws s3 ls s3://fcourt-local-media-870853685410 --recursive` (cần credential có `s3:ListBucket`).

Lưu ý khi chạy local:

- Dữ liệu local nằm trên AWS thật nên tốn dung lượng và request nhỏ; dọn bucket khi cần.
- Presigned URL ký offline bằng key của IAM user nên mở được từ trình duyệt, không cần cấu hình thêm.
- Ảnh public mở được ẩn danh nhờ bucket policy ở 6.3, giống môi trường thật.

Test tự động **không** gọi AWS: dùng Testcontainers với MinIO (S3 tương thích, `org.testcontainers:minio`, image `cgr.dev/chainguard/minio` ghim theo digest `sha256`, vì bản miễn phí của Chainguard chỉ có tag `latest`), không cần tài khoản AWS. Adapter chỉ dùng API S3 chuẩn nên chạy giống nhau trên MinIO và S3 thật. Có Docker là chạy được `mvn -f <service>/pom.xml verify`.

## 6. Hạ tầng cần chuẩn bị

### 6.1 Bucket

- Tên theo môi trường, region `ap-southeast-1`.
- **Block Public Access**: bật `BlockPublicAcls` và `IgnorePublicAcls` (chặn mọi đường public qua ACL). Tắt `BlockPublicPolicy` và `RestrictPublicBuckets` để bucket policy ở 6.3 có hiệu lực; nếu BPA cấp account đang bật thì phải nới hai tùy chọn này ở cấp account. **Object Ownership**: Bucket owner enforced (tắt ACL).
- Mã hóa mặc định SSE-S3 (hoặc SSE-KMS nếu cần, khi đó thêm quyền `kms:Decrypt`/`kms:GenerateDataKey` cho role).
- Versioning: tùy chọn. Nếu bật thì kèm lifecycle xóa phiên bản cũ sau 30 ngày.
- **Bắt buộc bật TLS-only** bằng bucket policy từ chối `aws:SecureTransport = false`.
- **CORS**: không cần, vì client không upload thẳng lên S3 (file đi qua service) và ảnh được tải qua `<img>` từ URL của bucket. Chỉ thêm CORS nếu web admin cần `fetch` ảnh sang origin khác (cho `GET` từ origin của web).

### 6.2 Lifecycle

| Prefix | Rule |
|---|---|
| tất cả | Abort incomplete multipart upload sau 7 ngày |
| `receipts/`, `refund-proofs/` | Biên lai upload nhưng không gắn vào giao dịch (lỗi giữa chừng, thua race) được payment xóa best-effort; thêm rule chuyển `STANDARD_IA` sau 90 ngày. Không tự xóa vì là chứng từ tài chính, thời hạn lưu theo quy định kế toán |
| `owner-documents/` | Giữ theo chính sách lưu hồ sơ; chuyển `STANDARD_IA` sau 90 ngày |
| `reviews/` | Ảnh review chưa gắn review được discovery dọn (job `ReviewImageCleanup`, mặc định dry-run, bật bằng `discovery.reviews.images.cleanup.dry-run=false`) |

### 6.3 Bucket policy: public read theo prefix

Không dùng CloudFront. Bucket policy có hai statement:

1. `Deny` mọi hành động `s3:*` khi `aws:SecureTransport = false` (TLS-only).
2. `Allow` `Principal: "*"`, `s3:GetObject`, chỉ trên ba prefix public:

```json
{
  "Sid": "PublicReadImagePrefixes",
  "Effect": "Allow",
  "Principal": "*",
  "Action": "s3:GetObject",
  "Resource": [
    "arn:aws:s3:::BUCKET/avatars/*",
    "arn:aws:s3:::BUCKET/branches/*",
    "arn:aws:s3:::BUCKET/reviews/*"
  ]
}
```

- `owner-documents/*`, `receipts/*`, `refund-proofs/*` không nằm trong statement này nên truy cập ẩn danh bị 403; chỉ đọc được bằng presigned URL (đã làm trong code).
- Không cấp `s3:ListBucket` ẩn danh nên không liệt kê được key. Key chứa UUID nên không đoán được.
- Đặt `STORAGE_PUBLIC_BASE_URL` của auth, discovery, facility (và booking, payment cho đồng nhất) là `https://<bucket>.s3.ap-southeast-1.amazonaws.com`. Terraform xuất giá trị này ở output `media_public_base_url` (`fcourt-infra`, module `media`).
- Đánh đổi so với CloudFront: không có cache edge, không có domain riêng, mỗi lượt xem ảnh tính vào request S3. Muốn thêm CDN sau này chỉ cần đổi `STORAGE_PUBLIC_BASE_URL` và lưu ý các URL đầy đủ đã ghi trong DB (facility `branch_images.url`, discovery `reviews.image_urls`).

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

Quyền đọc ẩn danh trên ba prefix public nằm ở bucket policy (6.3), nên role không cần `s3:GetObject` ở các prefix đó. Role cần trust policy cho `pods.eks.amazonaws.com` (`sts:AssumeRole`, `sts:TagSession`). Thời hạn presigned URL không vượt quá thời hạn credential của role (Pod Identity xoay credential khoảng mỗi vài giờ, đủ cho TTL 10 phút).

## 7. Dữ liệu cũ (không di chuyển)

Owner đã quyết định (2026-11-09) **không di chuyển** file Cloudinary cũ sang S3.

- Hàng đang giữ URL Cloudinary vẫn hiển thị đúng vì được trả nguyên, chừng nào tài khoản Cloudinary còn giữ file. File mất ở đó thì ảnh cũ hỏng; upload mới đều vào S3.
- Seed Flyway đã chạy (auth V4, payment V2) không bị sửa.
- Dữ liệu seed mới (tools/demo_seed) dùng URL HTTPS cho ảnh public và key `receipts/...` cho biên lai.
