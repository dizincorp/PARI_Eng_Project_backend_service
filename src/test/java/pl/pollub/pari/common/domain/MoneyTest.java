package pl.pollub.pari.common.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void addsAndSubtracts() {
        assertThat(Money.pln(1050).add(Money.pln(250))).isEqualTo(Money.pln(1300));
        assertThat(Money.pln(1050).subtract(Money.pln(250))).isEqualTo(Money.pln(800));
    }

    @Test
    void multiplyRoundsHalfUp() {
        assertThat(Money.pln(1050).multiply(new BigDecimal("0.10"))).isEqualTo(Money.pln(105));
        assertThat(Money.pln(1005).multiply(new BigDecimal("0.5"))).isEqualTo(Money.pln(503));
    }

    @Test
    void convertsToDecimalForTheApi() {
        assertThat(Money.pln(4250).toDecimal()).isEqualByComparingTo("42.50");
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.pln(100).add(new Money(100, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.pln(100)).isLessThan(Money.pln(200));
    }

    @Test
    void converterStoresMinorUnitsOnly() {
        MoneyConverter converter = new MoneyConverter();
        assertThat(converter.convertToDatabaseColumn(Money.pln(4250))).isEqualTo(4250L);
        assertThat(converter.convertToEntityAttribute(4250L)).isEqualTo(Money.pln(4250));
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
