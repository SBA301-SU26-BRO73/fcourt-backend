package com.fcourt.voucher;

import com.fcourt.voucher.grpc.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
class VoucherGrpcService extends VoucherValidationGrpc.VoucherValidationImplBase {
    private final VoucherPricing pricing;
    VoucherGrpcService(VoucherPricing pricing) { this.pricing = pricing; }

    @Override
    public void validate(ValidateVoucherRequest request, StreamObserver<ValidateVoucherResponse> observer) {
        try {
            var quote = pricing.validate(request.getCode(), request.getBranchId(), request.getOrderAmount());
            observer.onNext(ValidateVoucherResponse.newBuilder().setValid(quote.valid()).setReason(quote.reason())
                    .setVoucherId(quote.voucherId()).setDiscountAmount(quote.discountAmount().toPlainString())
                    .setFinalAmount(quote.finalAmount().toPlainString()).build());
            observer.onCompleted();
        } catch (IllegalArgumentException error) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Invalid voucher quote input").asRuntimeException());
        } catch (RuntimeException error) {
            observer.onError(Status.UNAVAILABLE.withDescription("Voucher validation unavailable").asRuntimeException());
        }
    }
}
