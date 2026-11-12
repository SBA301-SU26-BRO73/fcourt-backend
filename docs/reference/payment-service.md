# payment-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`payment-service/`](../../payment-service/). Its build descriptor is [`pom.xml`](../../payment-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../payment-service/Dockerfile)
- [compose.yml](../../payment-service/compose.yml)
- [mvnw](../../payment-service/mvnw)
- [mvnw.cmd](../../payment-service/mvnw.cmd)
- [pom.xml](../../payment-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../payment-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/payment`

- [AdminRefundController.java](../../payment-service/src/main/java/com/fcourt/payment/AdminRefundController.java)
- [AdminRefundService.java](../../payment-service/src/main/java/com/fcourt/payment/AdminRefundService.java)
- [BookingExpiryController.java](../../payment-service/src/main/java/com/fcourt/payment/BookingExpiryController.java)
- [BookingRefundConsumer.java](../../payment-service/src/main/java/com/fcourt/payment/BookingRefundConsumer.java)
- [BranchLedger.java](../../payment-service/src/main/java/com/fcourt/payment/BranchLedger.java)
- [BranchLedgerController.java](../../payment-service/src/main/java/com/fcourt/payment/BranchLedgerController.java)
- [BranchOwnership.java](../../payment-service/src/main/java/com/fcourt/payment/BranchOwnership.java)
- [CashLedgerConsumer.java](../../payment-service/src/main/java/com/fcourt/payment/CashLedgerConsumer.java)
- [CheckoutController.java](../../payment-service/src/main/java/com/fcourt/payment/CheckoutController.java)
- [CheckoutInfo.java](../../payment-service/src/main/java/com/fcourt/payment/CheckoutInfo.java)
- [CheckoutService.java](../../payment-service/src/main/java/com/fcourt/payment/CheckoutService.java)
- [CompactPaymentStatusController.java](../../payment-service/src/main/java/com/fcourt/payment/CompactPaymentStatusController.java)
- [CustomerPayments.java](../../payment-service/src/main/java/com/fcourt/payment/CustomerPayments.java)
- [CustomerPaymentsController.java](../../payment-service/src/main/java/com/fcourt/payment/CustomerPaymentsController.java)
- [FreeVoucherPayment.java](../../payment-service/src/main/java/com/fcourt/payment/FreeVoucherPayment.java)
- [FreeVoucherPaymentController.java](../../payment-service/src/main/java/com/fcourt/payment/FreeVoucherPaymentController.java)
- [OutboxRelay.java](../../payment-service/src/main/java/com/fcourt/payment/OutboxRelay.java)
- [PaymentApplication.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentApplication.java)
- [PaymentApprovalController.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentApprovalController.java)
- [PaymentApprovalService.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentApprovalService.java)
- [PaymentConfiguration.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentConfiguration.java)
- [PaymentErrors.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentErrors.java)
- [PaymentKafkaErrors.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentKafkaErrors.java)
- [PaymentRecord.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentRecord.java)
- [PaymentSecurity.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentSecurity.java)
- [PaymentSessions.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentSessions.java)
- [PaymentStatusCache.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentStatusCache.java)
- [PaymentStatusChanged.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentStatusChanged.java)
- [PaymentStatusInvalidation.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentStatusInvalidation.java)
- [PaymentStatusService.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentStatusService.java)
- [PaymentStore.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentStore.java)
- [PaymentUpstreams.java](../../payment-service/src/main/java/com/fcourt/payment/PaymentUpstreams.java)
- [PublicRefundController.java](../../payment-service/src/main/java/com/fcourt/payment/PublicRefundController.java)
- [ReceiptController.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptController.java)
- [ReceiptService.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptService.java)
- [ReceiptStorage.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptStorage.java)
- [ReceiptStorageConfiguration.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptStorageConfiguration.java)
- [ReceiptUrls.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptUrls.java)
- [ReceiptValidator.java](../../payment-service/src/main/java/com/fcourt/payment/ReceiptValidator.java)
- [RefundController.java](../../payment-service/src/main/java/com/fcourt/payment/RefundController.java)
- [RefundService.java](../../payment-service/src/main/java/com/fcourt/payment/RefundService.java)
- [RefundView.java](../../payment-service/src/main/java/com/fcourt/payment/RefundView.java)
- [S3ReceiptStorage.java](../../payment-service/src/main/java/com/fcourt/payment/S3ReceiptStorage.java)
- [TicketController.java](../../payment-service/src/main/java/com/fcourt/payment/TicketController.java)
- [TicketQr.java](../../payment-service/src/main/java/com/fcourt/payment/TicketQr.java)
- [UnifiedLedger.java](../../payment-service/src/main/java/com/fcourt/payment/UnifiedLedger.java)
- [UnifiedLedgerController.java](../../payment-service/src/main/java/com/fcourt/payment/UnifiedLedgerController.java)

### `com/fcourt/payment/config`

- [OpenApiConfig.java](../../payment-service/src/main/java/com/fcourt/payment/config/OpenApiConfig.java)
