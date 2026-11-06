package io.github.freecorebank;

import io.github.freecorebank.bank.BankDatabase;
import io.github.freecorebank.bank.BankStore;
import io.github.freecorebank.bank.BankService;
import io.github.freecorebank.bank.BankSettings;
import io.github.freecorebank.bank.MySqlBankDatabase;
import io.github.freecorebank.command.BankCommand;
import io.github.freecorebank.lang.Lang;
import io.github.freecorebank.menu.BankMenu;
import io.github.freecorebank.menu.BankMenuListener;
import io.github.freecorebank.menu.CustomAmountInput;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Map;

public final class FreeCoreBankPlugin extends JavaPlugin implements Listener {
    private BankStore database;
    private BankService service;
    private CustomAmountInput customAmountInput;

    /** Read-only snapshot used by FreeCoreEssentials wealth leaderboards. */
    public Map<String, BigDecimal> getBalanceSnapshot() {
        return service == null ? Map.of() : service.balanceSnapshot();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        try {
            BankSettings settings = BankSettings.from(getConfig());
            Lang lang = new Lang(this);
            Files.createDirectories(getDataFolder().toPath());
            database = createDatabase();
            service = new BankService(this, database, settings, lang);

            BankMenu menu = new BankMenu(this, service, lang);
            customAmountInput = new CustomAmountInput(this, service, menu, lang);
            BankCommand executor = new BankCommand(service, menu, lang);
            PluginCommand bankCommand = getCommand("bank");
            if (bankCommand == null) {
                throw new IllegalStateException("plugin.yml 中缺少 bank 指令");
            }
            bankCommand.setExecutor(executor);
            bankCommand.setTabCompleter(executor);
            getServer().getPluginManager().registerEvents(customAmountInput, this);
            getServer().getPluginManager().registerEvents(
                    new BankMenuListener(service, menu, customAmountInput), this);
            getServer().getPluginManager().registerEvents(this, this);

            for (Player player : getServer().getOnlinePlayers()) {
                recordOnline(player);
            }
            getLogger().info("FreeCoreBank 已启用，在线利率 "
                    + settings.onlineRate().movePointRight(2).stripTrailingZeros().toPlainString()
                    + "% ，离线利率 "
                    + settings.offlineRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%");
        } catch (Exception exception) {
            getLogger().severe("FreeCoreBank 启动失败: " + exception.getMessage());
            closeDatabase();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        recordOnline(event.getPlayer());
    }

    private void recordOnline(Player player) {
        try {
            service.open(player);
        } catch (SQLException exception) {
            getLogger().warning("无法记录玩家 " + player.getName() + " 的上线日期: " + exception.getMessage());
        }
    }

    private BankStore createDatabase() throws SQLException {
        String type = getConfig().getString("storage.type", "sqlite").trim();
        if (type.equalsIgnoreCase("sqlite")) {
            return new BankDatabase(getDataFolder().toPath().resolve("bank.db"));
        }
        if (!type.equalsIgnoreCase("mysql")) {
            throw new IllegalArgumentException("storage.type must be sqlite or mysql");
        }
        String host = getConfig().getString("storage.mysql.host", "127.0.0.1").trim();
        int port = getConfig().getInt("storage.mysql.port", 3306);
        String databaseName = getConfig().getString("storage.mysql.database", "").trim();
        String username = getConfig().getString("storage.mysql.username", "").trim();
        String password = getConfig().getString("storage.mysql.password", "");
        if (host.isEmpty() || databaseName.isEmpty() || username.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("storage.mysql configuration is incomplete");
        }
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia%2FShanghai";
        return new MySqlBankDatabase(jdbcUrl, username, password);
    }

    @Override
    public void onDisable() {
        if (customAmountInput != null) {
            customAmountInput.closeAll();
            customAmountInput = null;
        }
        closeDatabase();
    }

    private void closeDatabase() {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (SQLException exception) {
            getLogger().warning("关闭银行数据库失败: " + exception.getMessage());
        } finally {
            database = null;
        }
    }
}
