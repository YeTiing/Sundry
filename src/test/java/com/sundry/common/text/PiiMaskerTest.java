package com.sundry.common.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @Test
    void masksPhoneNumbers() {
        assertThat(PiiMasker.mask("联系 13800138000 处理"))
                .isEqualTo("联系 138****8000 处理");
    }

    @Test
    void masksLongDigitSequences() {
        assertThat(PiiMasker.mask("订单号 123456789012345 查询"))
                .isEqualTo("订单号 1234****2345 查询");
        assertThat(PiiMasker.mask("订单号123456789012345查询"))
                .isEqualTo("订单号1234****2345查询");
    }

    @Test
    void keepsShortNumbersAndNull() {
        assertThat(PiiMasker.mask("30天 12 单"))
                .isEqualTo("30天 12 单");
        assertThat(PiiMasker.mask(null)).isNull();
    }
}
