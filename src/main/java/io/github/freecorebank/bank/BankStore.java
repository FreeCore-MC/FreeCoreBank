package io.github.freecorebank.bank;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Storage contract shared by the local SQLite and cross-server MySQL backends. */
public interface BankStore extends AutoCloseable {
    void markOnline(UUID uuid, String name, LocalDate today) throws SQLException;

    BankDatabase.Account settleInterest(UUID uuid, String name, BankSettings settings,
                                        LocalDate today) throws SQLException;

    BankDatabase.Account addBalance(UUID uuid, String name, BigDecimal amount,
                                    LocalDate today, String transactionType) throws SQLException;

    BankDatabase.Account subtractBalance(UUID uuid, String name, BigDecimal amount,
                                         LocalDate today, String transactionType) throws SQLException;

    Map<String, BigDecimal> balanceSnapshot() throws SQLException;

    @Override
    void close() throws SQLException;
}
