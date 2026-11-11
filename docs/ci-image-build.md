# CI build và push image

Workflow `.github/workflows/build-push-images.yml` build image cho từng service có thay đổi và đẩy lên Amazon ECR.

## Khi nào chạy

- `push` vào `dev` có file đổi trong folder service. Chỉ build các service có folder đổi, dùng `<service>/Dockerfile`, context là folder service.
- `workflow_dispatch` (chạy tay): nhập `all` hoặc danh sách service cách nhau dấu phẩy.
- Không chạy trên Pull Request, nên PR không bị lỗi khi role AWS chưa tồn tại.

Mỗi service được push hai tag: `fcourt/<service>:<7 ký tự đầu của commit SHA>` và `fcourt/<service>:latest`. Gitops luôn ghim tag SHA (bất biến); `latest` chỉ để kéo tay cho tiện. Platform `linux/amd64`, cache buildx lưu trong GitHub Actions cache (scope theo service).

## Cài đặt một lần (Settings > Secrets and variables > Actions > Variables)

| Variable | Giá trị | Bắt buộc |
|---|---|---|
| `AWS_ECR_PUSH_ROLE_ARN` | Output `github_ecr_push_role_arn` của `fcourt-infra/envs/shared` (dạng `arn:aws:iam::870853685410:role/fcourt-shared-github-ecr-push`) | Có |
| `GITOPS_UPDATE_ENABLED` | `true` để bật bước cập nhật tag trong `fcourt-gitops` | Không (mặc định tắt) |
| `AWS_ACCOUNT_ID` | `870853685410` | Không (mặc định `870853685410`) |

Secret chỉ cần khi bật bước gitops:

| Secret | Ý nghĩa |
|---|---|
| `GITOPS_PUSH_TOKEN` | Fine-grained token, chỉ repo `fcourt-gitops`, quyền Contents: Read and write |

Role AWS chỉ tin cậy `repo:SBA301-SU26-BRO73/fcourt-backend` với nhánh `dev` và `main`, và chỉ được push vào các ECR repo `fcourt/*`. Cần apply `fcourt-infra/envs/shared` trước.

## Bước cập nhật gitops

Job `update-gitops` chạy sau khi mọi build thành công, nếu `GITOPS_UPDATE_ENABLED=true`. Nó chạy `kustomize edit set image` trong `services/<service>` của `fcourt-gitops` với tag SHA (bỏ qua service không có thư mục ở gitops), commit `chore(images): bump <services> to <sha>` và push vào `main`. Argo CD (namespace `fcourt-prod`) tự đồng bộ commit mới.
