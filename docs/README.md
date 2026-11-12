# FCourt backend documentation

Back to the [repository README](../README.md). Most existing documents are written in Vietnamese.

## Architecture

| Document | Content |
|---|---|
| [aws-architecture.md](architecture/aws-architecture.md) | AWS/EKS design and deployment options (SCRUM-100 design document) |
| [code-structure.md](architecture/code-structure.md) | Standard package layout for Spring Boot services |
| [spring-cloud.md](architecture/spring-cloud.md) | Gateway and static service discovery (no Eureka) |
| [realtime-schedule-notifications.md](architecture/realtime-schedule-notifications.md) | Public schedule updates over STOMP `/ws-fcourt` (SCRUM-108) |

## Operations

| Document | Content |
|---|---|
| [docker-compose.md](operations/docker-compose.md) | Run the whole system locally: profiles, ports, memory, troubleshooting |
| [smoke-test-local.md](operations/smoke-test-local.md) | Recorded local smoke test (Compose + web) |
| [gateway.md](operations/gateway.md) | Gateway routes, public paths, environment variables, rate limiting |
| [storage-s3.md](operations/storage-s3.md) | File storage on S3: key layout, presigned URLs, IAM |
| [ci-image-build.md](operations/ci-image-build.md) | CI image build to ECR and GitOps tag bump |

## Business

| Document | Content |
|---|---|
| [business-gap-a.md](business/business-gap-a.md) | Monolith vs microservices gap analysis, part A (booking, payment, refund) |
| [business-gap-b.md](business/business-gap-b.md) | Gap analysis, part B (PayOS config, dashboard revenue, vouchers) |
| [cancellation-refunds.md](business/cancellation-refunds.md) | Paid cancellation refund flow (A6 / A12) |

## Report

[report/](report/README.md) is where the final project report (DOCX/PDF) is kept locally; the files themselves are git-ignored.

## Other documentation

- Service READMEs: `<service>/README.md` (auth, booking, discovery, facility, notification, payment, pos-retail, reporting, slot-hold-engine, staff-operations, subscription).
- Tool READMEs: [tools/demo_seed](../tools/demo_seed/README.md), [tools/production_smoke](../tools/production_smoke/README.md), [tools/system_tests](../tools/system_tests/README.md).
- Database scripts: `<service>/database/`; database list in `docker/postgres/services.txt`.
