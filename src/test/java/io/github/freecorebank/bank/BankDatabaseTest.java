package io.github.freecorebank.bank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankDatabaseTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsMoneyAndUsesLoginActivityForInterest() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 10);
        UUID playerId = UUID.randomUUID();
        BankSettings settings = new BankSettings(new BigDecimal("0.01"), new BigDecimal("0.005"),
                ZoneId.of("Asia/Shanghai"), List.of(new BigDecimal("100.00")));

        try (BankDatabase database = new BankDatabase(tempDirectory.resolve("bank.db"))) {
            database.markOnline(playerId, "Tester", day);
            database.settleInterest(playerId, "Tester", settings, day);
            database.addBalance(playerId, "Tester", new BigDecimal("100.00"), day, "DEPOSIT");

            BankDatabase.Account account = database.settleInterest(playerId, "Tester", settings, day.plusDays(2));

            assertEquals(new BigDecimal("101.51"), account.balance());
            assertEquals(new BigDecimal("1.51"), account.accrual().interest());
            assertEquals(1, account.accrual().onlineDays());
            assertEquals(1, account.accrual().offlineDays());
        }
    }
}
