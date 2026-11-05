package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VoucherPricingTest {
    private final Instant now = Instant.parse("2030-01-10T00:00:00Z");
    private final VoucherRepository repository = mock(VoucherRepository.class);
    private final VoucherPricing pricing = new VoucherPricing(repository, Clock.fixed(now, ZoneOffset.UTC));

    private Voucher voucher(Voucher.DiscountType type, String value, String cap, String minimum,
            Instant start, Instant end, int used, Voucher.Status status) {
        return new Voucher(1, "SAVE", 7, type, new BigDecimal(value), cap == null ? null : new BigDecimal(cap),
                new BigDecimal(minimum), start, end, 10, used, status);
    }

    @Test
    void percentageRoundsHalfUpThenRespectsCapAndFixedDiscountCannotExceedOrder() {
        var percent = voucher(Voucher.DiscountType.PERCENTAGE, "12.50", null, "0", now, now.plusSeconds(1), 0, Voucher.Status.ACTIVE);
        var quote = VoucherPricing.calculate(percent, 7, new BigDecimal("10.04"), now);
        assertThat(quote.valid()).isTrue();
        assertThat(quote.discountAmount()).isEqualByComparingTo("1.26");
        assertThat(quote.finalAmount()).isEqualByComparingTo("8.78");
        var capped = voucher(Voucher.DiscountType.PERCENTAGE, "50", "30", "0", now, now.plusSeconds(1), 0, Voucher.Status.ACTIVE);
        assertThat(VoucherPricing.calculate(capped, 7, new BigDecimal("100"), now).discountAmount()).isEqualByComparingTo("30");
        var fixed = voucher(Voucher.DiscountType.FIXED_AMOUNT, "200", null, "0", now, now.plusSeconds(1), 0, Voucher.Status.ACTIVE);
        var free = VoucherPricing.calculate(fixed, 7, new BigDecimal("100"), now);
        assertThat(free.discountAmount()).isEqualByComparingTo("100");
        assertThat(free.finalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void inclusiveStartExclusiveEndMinimumAndQuotaControlEligibility() {
        var active = voucher(Voucher.DiscountType.PERCENTAGE, "10", null, "100", now, now.plusSeconds(60), 9, Voucher.Status.ACTIVE);
        assertThat(VoucherPricing.calculate(active, 7, new BigDecimal("100"), now).valid()).isTrue();
        assertThat(VoucherPricing.calculate(active, 7, new BigDecimal("100"), now.minusNanos(1)).reason()).isEqualTo("NOT_STARTED");
        assertThat(VoucherPricing.calculate(active, 7, new BigDecimal("100"), now.plusSeconds(60)).reason()).isEqualTo("EXPIRED");
        assertThat(active.at(now.plusSeconds(60)).status()).isEqualTo(Voucher.Status.EXPIRED);
        assertThat(VoucherPricing.calculate(active, 8, new BigDecimal("100"), now).reason()).isEqualTo("WRONG_BRANCH");
        assertThat(VoucherPricing.calculate(active, 7, new BigDecimal("99.99"), now).reason()).isEqualTo("MIN_ORDER_NOT_MET");
        var exhausted = voucher(Voucher.DiscountType.FIXED_AMOUNT, "10", null, "0", now, now.plusSeconds(1), 10, Voucher.Status.ACTIVE);
        assertThat(VoucherPricing.calculate(exhausted, 7, new BigDecimal("100"), now).reason()).isEqualTo("USAGE_LIMIT_REACHED");
        var disabled = voucher(Voucher.DiscountType.FIXED_AMOUNT, "10", null, "0", now, now.plusSeconds(1), 0, Voucher.Status.EXPIRED);
        assertThat(VoucherPricing.calculate(disabled, 7, new BigDecimal("100"), now).reason()).isEqualTo("EXPIRED");
    }
}
