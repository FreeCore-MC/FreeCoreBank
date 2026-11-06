package io.github.freecorebank.bank;

import io.github.freecorebank.lang.Lang;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class BankService {
    private final org.bukkit.plugin.Plugin plugin;
    private final BankStore database;
    private final Economy economy;
    private final Lang lang;
    private volatile BankSettings settings;
    private volatile Map<String, BigDecimal> balanceSnapshot;

    public BankService(org.bukkit.plugin.Plugin plugin, BankStore database, BankSettings settings, Lang lang)
            throws SQLException {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
        this.lang = lang;
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager()
                .getRegistration(Economy.class);
        this.economy = Objects.requireNonNull(registration, "Vault 经济服务未注册").getProvider();
        this.balanceSnapshot = database.balanceSnapshot();
    }

    public void reloadSettings() {
        plugin.reloadConfig();
        BankSettings loaded = BankSettings.from(plugin.getConfig());
        lang.reload();
        this.settings = loaded;
    }

    public BankSettings settings() {
        return settings;
    }

    public BankDatabase.Account open(Player player) throws SQLException {
        LocalDate today = LocalDate.now(settings.zoneId());
        database.markOnline(player.getUniqueId(), player.getName(), today);
        BankDatabase.Account account = database.settleInterest(player.getUniqueId(), player.getName(), settings, today);
        updateSnapshot(account.playerName(), account.balance());
        return account;
    }

    public TransactionResult deposit(Player player, BigDecimal amount) {
        amount = money(amount);
        if (amount.signum() <= 0) {
            return TransactionResult.failure(lang.message("messages.amount-positive"));
        }
        try {
            BankDatabase.Account account = open(player);
            EconomyResponse withdrawal = economy.withdrawPlayer(player, amount.doubleValue());
            if (!withdrawal.transactionSuccess()) {
                return TransactionResult.failure(lang.message("messages.wallet-insufficient",
                        "balance", format(walletBalance(player))));
            }
            try {
                BankDatabase.Account updated = database.addBalance(player.getUniqueId(), player.getName(), amount,
                        LocalDate.now(settings.zoneId()), "DEPOSIT");
                updateSnapshot(updated.playerName(), updated.balance());
                return TransactionResult.success(updated, lang.message("messages.deposit-success",
                        "amount", format(amount), "balance", format(updated.balance())));
            } catch (SQLException exception) {
                economy.depositPlayer(player, amount.doubleValue());
                plugin.getLogger().warning("存款写入失败，已尝试退回钱包: " + exception.getMessage());
                return TransactionResult.failure(lang.message("messages.deposit-refunded"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("读取银行账户失败: " + exception.getMessage());
            return TransactionResult.failure(lang.message("messages.bank-unavailable"));
        }
    }

    public TransactionResult withdraw(Player player, BigDecimal amount) {
        amount = money(amount);
        if (amount.signum() <= 0) {
            return TransactionResult.failure(lang.message("messages.amount-positive"));
        }
        try {
            BankDatabase.Account account = open(player);
            if (account.balance().compareTo(amount) < 0) {
                return TransactionResult.failure(lang.message("messages.bank-insufficient",
                        "balance", format(account.balance())));
            }
            BankDatabase.Account updated = database.subtractBalance(player.getUniqueId(), player.getName(), amount,
                    LocalDate.now(settings.zoneId()), "WITHDRAW");
            updateSnapshot(updated.playerName(), updated.balance());
            EconomyResponse deposit = economy.depositPlayer(player, amount.doubleValue());
            if (!deposit.transactionSuccess()) {
                BankDatabase.Account restored = database.addBalance(player.getUniqueId(), player.getName(), amount,
                        LocalDate.now(settings.zoneId()), "ROLLBACK");
                updateSnapshot(restored.playerName(), restored.balance());
                return TransactionResult.failure(lang.message("messages.withdraw-restored"));
            }
            return TransactionResult.success(updated, lang.message("messages.withdraw-success",
                    "amount", format(amount), "balance", format(updated.balance())));
        } catch (SQLException exception) {
            plugin.getLogger().warning("取款写入失败: " + exception.getMessage());
            return TransactionResult.failure(lang.message("messages.bank-unavailable"));
        }
    }

    public BigDecimal walletBalance(Player player) {
        return money(BigDecimal.valueOf(Math.max(0, economy.getBalance(player))));
    }

    public Map<String, BigDecimal> balanceSnapshot() {
        return balanceSnapshot;
    }

    private void updateSnapshot(String playerName, BigDecimal balance) {
        Map<String, BigDecimal> updated = new LinkedHashMap<>(balanceSnapshot);
        updated.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(playerName));
        updated.put(playerName, money(balance));
        balanceSnapshot = Map.copyOf(updated);
    }

    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN);
    }

    public static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record TransactionResult(boolean success, Component message, BankDatabase.Account account) {
        public static TransactionResult success(BankDatabase.Account account, Component message) {
            return new TransactionResult(true, message, account);
        }

        public static TransactionResult failure(Component message) {
            return new TransactionResult(false, message, null);
        }

        public void send(Player player) {
            player.sendMessage(message);
        }
    }
}
