package io.github.freecorebank.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.function.Predicate;

/** Applies each completed calendar day between the account's settlement dates. */
public final class InterestCalculator {
    private static final int SCALE = 2;

    private InterestCalculator() {
    }

    public static AccrualResult accrue(BigDecimal balance, LocalDate settledThrough,
                                       LocalDate today, BigDecimal onlineRate,
                                       BigDecimal offlineRate, Predicate<LocalDate> wasOnline) {
        BigDecimal current = money(balance);
        int onlineDays = 0;
        int offlineDays = 0;
        LocalDate date = settledThrough;
        while (date.isBefore(today)) {
            boolean online = wasOnline.test(date);
            BigDecimal rate = online ? onlineRate : offlineRate;
            current = money(current.multiply(BigDecimal.ONE.add(rate)));
            if (online) {
                onlineDays++;
            } else {
                offlineDays++;
            }
            date = date.plusDays(1);
        }
        return new AccrualResult(current, current.subtract(money(balance)), onlineDays, offlineDays, today);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public record AccrualResult(BigDecimal balance, BigDecimal interest, int onlineDays,
                                int offlineDays, LocalDate settledThrough) {
    }
}
