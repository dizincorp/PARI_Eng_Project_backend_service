package pl.pollub.pari.common.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link Money} as a bigint number of minor units. */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, Long> {

    @Override
    public Long convertToDatabaseColumn(Money money) {
        return money == null ? null : money.amountMinor();
    }

    @Override
    public Money convertToEntityAttribute(Long amountMinor) {
        return amountMinor == null ? null : Money.pln(amountMinor);
    }
}
