package io.github.freecorebank.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Small, serialized SQLite gateway. All methods are called from the plugin's main thread. */
public final class BankDatabase implements BankStore {
    private final Connection connection;

    public BankDatabase(Path file) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            result.next();
        }
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_accounts (" +
                    "uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, balance TEXT NOT NULL, " +
                    "settled_through TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_activity (" +
                    "uuid TEXT NOT NULL, activity_date TEXT NOT NULL, " +
                    "PRIMARY KEY (uuid, activity_date))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bank_transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, type TEXT NOT NULL, " +
                    "amount TEXT NOT NULL, balance_after TEXT NOT NULL, created_at TEXT NOT NULL)");
        }
    }

    public synchronized void markOnline(UUID uuid, String name, LocalDate today) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ensureAccount(uuid, name, today);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE bank_accounts SET player_name = ? WHERE uuid = ?")) {
                update.setString(1, name);
                update.setString(2, uuid.toString());
                update.executeUpdate();
            }
            try (PreparedStatement activity = connection.prepareStatement(
                    "INSERT OR IGNORE INTO bank_activity(uuid, activity_date) VALUES (?, ?)")) {
                activity.setString(1, uuid.toString());
                activity.setString(2, today.toString());
                activity.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            rollbackQuietly();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized Account settleInterest(UUID uuid, String name, BankSettings settings,
                                                LocalDate today) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ensureAccount(uuid, name, today);
            Account account = readAccount(uuid);
            Set<LocalDate> onlineDays = new HashSet<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT activity_date FROM bank_activity WHERE uuid = ? AND activity_date < ?")) {
                query.setString(1, uuid.toString());
                query.setString(2, today.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        onlineDays.add(LocalDate.parse(result.getString(1)));
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
                account = new Account(name, accrual.balance(), accrual.settledThrough(), accrual);
            } else {
                account = new Account(name, account.balance(), account.settledThrough(),
                        new InterestCalculator.AccrualResult(account.balance(), BigDecimal.ZERO,
                                0, 0, account.settledThrough()));
            }
            connection.commit();
            return account;
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly();
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized Account addBalance(UUID uuid, String name, BigDecimal amount,
                                           LocalDate today, String transactionType) throws SQLException {
        return changeBalance(uuid, name, amount, today, transactionType);
    }

    public synchronized Account subtractBalance(UUID uuid, String name, BigDecimal amount,
                                                LocalDate today, String transactionType) throws SQLException {
        return changeBalance(uuid, name, amount.negate(), today, transactionType);
    }

    private Account changeBalance(UUID uuid, String name, BigDecimal delta, LocalDate today,
                                  String transactionType) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ensureAccount(uuid, name, today);
            Account before = readAccount(uuid);
            BigDecimal balance = money(before.balance().add(delta));
            if (balance.signum() < 0) {
                throw new InsufficientBankBalanceException();
            }
            updateAccount(uuid, name, balance, before.settledThrough());
            insertTransaction(uuid, transactionType, delta.abs(), balance);
            connection.commit();
            return new Account(name, balance, before.settledThrough(), before.accrual());
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly();
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public synchronized Map<String, BigDecimal> balanceSnapshot() throws SQLException {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_name, balance FROM bank_accounts");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                String name = result.getString(1);
                if (name != null && !name.isBlank()) {
                    balances.put(name, money(new BigDecimal(result.getString(2))));
                }
            }
        }
        return Map.copyOf(balances);
    }

    private void ensureAccount(UUID uuid, String name, LocalDate today) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO bank_accounts(uuid, player_name, balance, settled_through) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name);
            insert.setString(3, "0.00");
            insert.setString(4, today.toString());
            insert.executeUpdate();
        }
    }

    private Account readAccount(UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_name, balance, settled_through FROM bank_accounts WHERE uuid = ?")) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("银行账户创建失败: " + uuid);
                }
                return new Account(result.getString(1), money(new BigDecimal(result.getString(2))),
                        LocalDate.parse(result.getString(3)), null);
            }
        }
    }

    private void updateAccount(UUID uuid, String name, BigDecimal balance, LocalDate settledThrough)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE bank_accounts SET player_name = ?, balance = ?, settled_through = ? WHERE uuid = ?")) {
            update.setString(1, name);
            update.setString(2, money(balance).toPlainString());
            update.setString(3, settledThrough.toString());
            update.setString(4, uuid.toString());
            update.executeUpdate();
        }
    }

    private void insertTransaction(UUID uuid, String type, BigDecimal amount, BigDecimal balanceAfter)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO bank_transactions(uuid, type, amount, balance_after, created_at) VALUES (?, ?, ?, ?, datetime('now'))")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, type);
            insert.setString(3, money(amount).toPlainString());
            insert.setString(4, money(balanceAfter).toPlainString());
            insert.executeUpdate();
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

    public record Account(String playerName, BigDecimal balance, LocalDate settledThrough,
                          InterestCalculator.AccrualResult accrual) {
    }

    public static final class InsufficientBankBalanceException extends RuntimeException {
    }
}
