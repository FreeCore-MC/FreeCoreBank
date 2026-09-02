package io.github.freecorebank.command;

import io.github.freecorebank.bank.BankService;
import io.github.freecorebank.lang.Lang;
import io.github.freecorebank.menu.BankMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class BankCommand implements CommandExecutor, TabCompleter {
    private final BankService service;
    private final BankMenu menu;
    private final Lang lang;

    public BankCommand(BankService service, BankMenu menu, Lang lang) {
        this.service = service;
        this.menu = menu;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("freecorebank.reload")) {
                lang.send(sender, "messages.no-permission");
                return true;
            }
            try {
                service.reloadSettings();
                lang.send(sender, "messages.reload-success",
                        "online_rate", service.settings().onlineRate().movePointRight(2)
                                .stripTrailingZeros().toPlainString(),
                        "offline_rate", service.settings().offlineRate().movePointRight(2)
                                .stripTrailingZeros().toPlainString());
            } catch (RuntimeException exception) {
                lang.send(sender, "messages.reload-failed", "error", exception.getMessage());
            }
            return true;
        }
        if (args.length != 0) {
            lang.send(sender, "messages.usage");
            return true;
        }
        if (!(sender instanceof Player player)) {
            lang.send(sender, "messages.console-only");
            return true;
        }
        menu.openMain(player);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("freecorebank.reload")
                && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }
}
