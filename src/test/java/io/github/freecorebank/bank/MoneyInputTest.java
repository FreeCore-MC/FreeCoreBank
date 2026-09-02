package io.github.freecorebank.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyInputTest {
    @Test
    void acceptsWholeNumbersDecimalsAndThousandsSeparators() {
        assertEquals(new BigDecimal("1234"), MoneyInput.parse("1234").orElseThrow());
        assertEquals(new BigDecimal("12.50"), MoneyInput.parse(" 12.50 ").orElseThrow());
        assertEquals(new BigDecimal("1000.25"), MoneyInput.parse("1,000.25").orElseThrow());
    }

    @Test
    void rejectsInvalidOrUnsafeAmounts() {
        assertTrue(MoneyInput.parse("0").isEmpty());
        assertTrue(MoneyInput.parse("-1").isEmpty());
        assertTrue(MoneyInput.parse("1.234").isEmpty());
        assertTrue(MoneyInput.parse("1e3").isEmpty());
        assertTrue(MoneyInput.parse("abc").isEmpty());
        assertTrue(MoneyInput.parse("99999999999999999999").isEmpty());
    }
}
