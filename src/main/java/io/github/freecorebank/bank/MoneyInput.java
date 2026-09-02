package io.github.freecorebank.bank;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

public final class MoneyInput {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");
    private static final BigDecimal MAXIMUM = new BigDecimal("9000000000000000.00");

    private MoneyInput() {
    }

    public static Optional<BigDecimal> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.replace(",", "").trim();
        if (!DECIMAL.matcher(normalized).matches()) {
            return Optional.empty();
        }
        try {
            BigDecimal amount = new BigDecimal(normalized);
            if (amount.signum() <= 0 || amount.compareTo(MAXIMUM) > 0) {
                return Optional.empty();
            }
            return Optional.of(amount);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
