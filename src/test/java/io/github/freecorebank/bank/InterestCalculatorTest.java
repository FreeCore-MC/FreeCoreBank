package io.github.freecorebank.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterestCalculatorTest {
    @Test
    void compoundsOnlineAndOfflineRatesPerCompletedDay() {
        LocalDate firstDay = LocalDate.of(2026, 8, 10);
        Set<LocalDate> online = Set.of(firstDay);

        InterestCalculator.AccrualResult result = InterestCalculator.accrue(
                new BigDecimal("1000.00"), firstDay, firstDay.plusDays(2),
                new BigDecimal("0.01"), new BigDecimal("0.005"), online::contains);

        assertEquals(new BigDecimal("1015.05"), result.balance());
        assertEquals(new BigDecimal("15.05"), result.interest());
        assertEquals(1, result.onlineDays());
        assertEquals(1, result.offlineDays());
    }

    @Test
    void doesNotAccrueForCurrentIncompleteDay() {
        LocalDate today = LocalDate.of(2026, 8, 10);

        InterestCalculator.AccrualResult result = InterestCalculator.accrue(
                new BigDecimal("500.00"), today, today,
                new BigDecimal("0.01"), new BigDecimal("0.005"), ignored -> true);

        assertEquals(new BigDecimal("500.00"), result.balance());
        assertEquals(new BigDecimal("0.00"), result.interest());
        assertEquals(0, result.onlineDays());
        assertEquals(0, result.offlineDays());
    }
}
