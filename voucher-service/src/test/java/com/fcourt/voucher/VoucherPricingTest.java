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
}
