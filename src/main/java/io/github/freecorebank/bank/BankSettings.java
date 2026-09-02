package io.github.freecorebank.bank;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.List;

/** Immutable runtime settings so a reload cannot partially update a transaction. */
public record BankSettings(BigDecimal onlineRate, BigDecimal offlineRate, ZoneId zoneId,
                           List<BigDecimal> menuAmounts) {

    public static BankSettings from(FileConfiguration config) {
        BigDecimal online = percent(config.getDouble("interest.online-rate-percent", 1.0));
        BigDecimal offline = percent(config.getDouble("interest.offline-rate-percent", 0.5));
        String timezone = config.getString("interest.timezone", "Asia/Shanghai");
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (ZoneRulesException exception) {
            throw new IllegalArgumentException("无效的 interest.timezone: " + timezone, exception);
        }

        List<BigDecimal> amounts = new ArrayList<>();
        List<?> configured = config.getList("menu.amounts", List.of(100, 1000, 10000, 100000));
        if (configured != null) {
            for (Object value : configured) {
                try {
                    BigDecimal amount = new BigDecimal(String.valueOf(value));
                    if (amount.signum() > 0 && amount.scale() <= 2) {
                        amounts.add(amount.setScale(2));
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid individual entries are ignored; defaults are used below if needed.
                }
            }
        }
        if (amounts.isEmpty()) {
            amounts = List.of(new BigDecimal("100.00"), new BigDecimal("1000.00"),
                    new BigDecimal("10000.00"), new BigDecimal("100000.00"));
        }
        return new BankSettings(online, offline, zone, List.copyOf(amounts));
    }

    private static BigDecimal percent(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException("利率必须在 0 到 100 之间");
        }
        return BigDecimal.valueOf(value).movePointLeft(2);
    }
}
