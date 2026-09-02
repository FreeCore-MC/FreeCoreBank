package io.github.freecorebank.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * MySQL storage for shared bank accounts. Every account mutation locks its row with
 * {@code SELECT ... FOR UPDATE}; this is required because two Paper processes can use this store.
 */
public final class MySqlBankDatabase implements BankStore {
    private final Connection connection;

    public MySqlBankDatabase(String jdbcUrl, String username, String password) throws SQLException {
        this.connection = DriverManager.getConnection(jdbcUrl, username, password);
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_accounts ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY, player_name VARCHAR(16) NOT NULL, "
                    + "balance DECIMAL(20,2) NOT NULL, settled_through DATE NOT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_activity ("
                    + "uuid CHAR(36) NOT NULL, activity_date DATE NOT NULL, "
                    + "PRIMARY KEY (uuid, activity_date), "
                    + "CONSTRAINT bank_activity_account_fk FOREIGN KEY (uuid) REFERENCES bank_accounts(uuid) "
                    + "ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_transactions ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36) NOT NULL, "
                    + "type VARCHAR(32) NOT NULL, amount DECIMAL(20,2) NOT NULL, "
                    + "balance_after DECIMAL(20,2) NOT NULL, "
                    + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "INDEX bank_transactions_uuid_created_at (uuid, created_at), "
                    + "CONSTRAINT bank_transactions_account_fk FOREIGN KEY (uuid) REFERENCES bank_accounts(uuid) "
                    + "ON DELETE RESTRICT"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    @Override
    public synchronized void markOnline(UUID uuid, String name, LocalDate today) throws SQLException {
        inTransaction(() -> {
            ensureAccount(uuid, name, today);
            lockAccount(uuid);
            try (PreparedStatement activity = connection.prepareStatement(
                    "INSERT IGNORE INTO bank_activity(uuid, activity_date) VALUES (?, ?)")) {
                activity.setString(1, uuid.toString());
                activity.setString(2, today.toString());
                activity.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized BankDatabase.Account settleInterest(UUID uuid, String name, BankSettings settings,
                                                              LocalDate today) throws SQLException {
        return inTransaction(() -> {
            ensureAccount(uuid, name, today);
            BankDatabase.Account account = lockAccount(uuid);
            Set<LocalDate> onlineDays = new HashSet<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT activity_date FROM bank_activity WHERE uuid = ? AND activity_date < ?")) {
                query.setString(1, uuid.toString());
                query.setString(2, today.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        onlineDays.add(result.getDate(1).toLocalDate());
                    }
                }
            }
            InterestCalculator.AccrualResult accrual = InterestCalculator.accrue(
                    account.balance(), account.settledThrough(), today, settings.onlineRate(),
                    settings.offlineRate(), onlineDays::contains);
            if (accrual.settledThrough().isAfter(account.settledThrough())) {
                updateAccount(uuid, name, accrual.balance(), accrual.settledThrough());
                if (accrual.interest().signum() != 0) {
                    insertTransaction(uuid, "INTEREST", accrual.interest(), accrual.balance());
                }
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM bank_activity WHERE uuid = ? AND activity_date < ?")) {
                    cleanup.setString(1, uuid.toString());
                    cleanup.setString(2, today.toString());
                    cleanup.executeUpdate();
                }
                return new BankDatabase.Account(name, accrual.balance(), accrual.settledThrough(), accrual);
            }
            return new BankDatabase.Account(name, account.balance(), account.settledThrough(),
                    new InterestCalculator.AccrualResult(account.balance(), BigDecimal.ZERO, 0, 0,
                            account.settledThrough()));
        });
    }

    @Override
    public synchronized BankDatabase.Account addBalance(UUID uuid, String name, BigDecimal amount,
                                                          LocalDate today, String transactionType) throws SQLException {
        return changeBalance(uuid, name, amount, today, transactionType);
    }

    @Override
    public synchronized BankDatabase.Account subtractBalance(UUID uuid, String name, BigDecimal amount,
                                                               LocalDate today, String transactionType) throws SQLException {
        return changeBalance(uuid, name, amount.negate(), today, transactionType);
    }

    private BankDatabase.Account changeBalance(UUID uuid, String name, BigDecimal delta, LocalDate today,
                                               String transactionType) throws SQLException {
        return inTransaction(() -> {
            ensureAccount(uuid, name, today);
            BankDatabase.Account before = lockAccount(uuid);
            BigDecimal balance = money(before.balance().add(delta));
            if (balance.signum() < 0) {
                throw new BankDatabase.InsufficientBankBalanceException();
            }
            updateAccount(uuid, name, balance, before.settledThrough());
            insertTransaction(uuid, transactionType, delta.abs(), balance);
            return new BankDatabase.Account(name, balance, before.settledThrough(), before.accrual());
        });
    }

    private void ensureAccount(UUID uuid, String name, LocalDate today) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO bank_accounts(uuid, player_name, balance, settled_through) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name);
            insert.setBigDecimal(3, BigDecimal.ZERO.setScale(2));
            insert.setString(4, today.toString());
            insert.executeUpdate();
        }
    }

    private BankDatabase.Account lockAccount(UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_name, balance, settled_through FROM bank_accounts WHERE uuid = ? FOR UPDATE")) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Bank account creation failed: " + uuid);
                }
                return new BankDatabase.Account(result.getString(1), money(result.getBigDecimal(2)),
                        result.getDate(3).toLocalDate(), null);
            }
        }
    }

    private void updateAccount(UUID uuid, String name, BigDecimal balance, LocalDate settledThrough)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE bank_accounts SET player_name = ?, balance = ?, settled_through = ? WHERE uuid = ?")) {
            update.setString(1, name);
            update.setBigDecimal(2, money(balance));
            update.setString(3, settledThrough.toString());
            update.setString(4, uuid.toString());
            if (update.executeUpdate() != 1) {
                throw new SQLException("Bank account update affected an unexpected number of rows");
            }
        }
    }

    private void insertTransaction(UUID uuid, String type, BigDecimal amount, BigDecimal balanceAfter)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO bank_transactions(uuid, type, amount, balance_after) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, type);
            insert.setBigDecimal(3, money(amount));
            insert.setBigDecimal(4, money(balanceAfter));
            insert.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T value = work.run();
            connection.commit();
            return value;
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
