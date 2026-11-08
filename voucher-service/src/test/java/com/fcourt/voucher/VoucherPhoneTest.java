package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VoucherPhoneTest {
    @Test void normalizesFormattingAndCountryCode() {
        assertThat(VoucherPhone.digits("0912 345-678")).isEqualTo("0912345678");
        assertThat(VoucherPhone.digits("+84 912 345 678")).isEqualTo("0912345678");
        assertThat(VoucherPhone.digits("84912345678")).isEqualTo("0912345678");
        assertThat(VoucherPhone.digits("091")).isEqualTo("091");
    }
    @Test void rejectsEmptyAndOverlongInput() {
        assertThat(VoucherPhone.digits(null)).isNull();
        assertThat(VoucherPhone.digits("abc")).isNull();
        assertThat(VoucherPhone.digits("1234567890123456")).isNull();
        assertThat(VoucherPhone.digits("%' OR 1=1 --")).isEqualTo("11");
    }
    @Test void storesOnlyPlausibleFullNumbers() {
        assertThat(VoucherPhone.storable("0912345678")).isEqualTo("0912345678");
        assertThat(VoucherPhone.storable("12345")).isNull();
        assertThat(VoucherPhone.storable("  ")).isNull();
    }
}
