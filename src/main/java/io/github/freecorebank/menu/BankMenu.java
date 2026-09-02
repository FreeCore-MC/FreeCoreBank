package io.github.freecorebank.menu;

import io.github.freecorebank.bank.BankDatabase;
import io.github.freecorebank.bank.BankService;
import io.github.freecorebank.bank.BankSettings;
import io.github.freecorebank.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class BankMenu {
    private static final int[] AMOUNT_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private final BankService service;
    private final Lang lang;
    private final NamespacedKey actionKey;
    private final NamespacedKey amountKey;

    public BankMenu(Plugin plugin, BankService service, Lang lang) {
        this.service = service;
        this.lang = lang;
        this.actionKey = new NamespacedKey(plugin, "menu_action");
        this.amountKey = new NamespacedKey(plugin, "menu_amount");
    }

    public void openMain(Player player) {
        try {
            BankDatabase.Account account = service.open(player);
            BankSettings settings = service.settings();
            BankMenuHolder holder = new BankMenuHolder(player.getUniqueId(), BankMenuHolder.Page.MAIN);
            Inventory inventory = Bukkit.createInventory(holder, 27, lang.text("gui.main.title"));
            holder.attach(inventory);
            fill(inventory);

            List<Component> balanceLore = new ArrayList<>();
            balanceLore.add(lang.text("gui.main.account.bank-balance",
                    "balance", BankService.format(account.balance())));
            balanceLore.add(lang.text("gui.main.account.wallet-balance",
                    "balance", BankService.format(service.walletBalance(player))));
            if (account.accrual() != null && account.accrual().interest().signum() > 0) {
                balanceLore.add(Component.empty());
                balanceLore.add(lang.text("gui.main.account.settled-interest",
                        "interest", BankService.format(account.accrual().interest())));
            }
            inventory.setItem(4, item(Material.SUNFLOWER, lang.text("gui.main.account.name"),
                    balanceLore, null, null));
            inventory.setItem(11, item(Material.CHEST, lang.text("gui.main.deposit.name"),
                    lang.list("gui.main.deposit.lore"), "deposit-menu", null));
            inventory.setItem(15, item(Material.HOPPER, lang.text("gui.main.withdraw.name"),
                    lang.list("gui.main.withdraw.lore"), "withdraw-menu", null));

            String onlinePercent = settings.onlineRate().movePointRight(2).stripTrailingZeros().toPlainString();
            String offlinePercent = settings.offlineRate().movePointRight(2).stripTrailingZeros().toPlainString();
            inventory.setItem(22, item(Material.BOOK, lang.text("gui.main.interest.name"),
                    List.of(lang.text("gui.main.interest.online", "rate", onlinePercent),
                            lang.text("gui.main.interest.offline", "rate", offlinePercent),
                            lang.text("gui.main.interest.description")), null, null));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            lang.send(player, "messages.account-error");
        }
    }

    public void openAmounts(Player player, BankMenuHolder.Page page) {
        try {
            BankDatabase.Account account = service.open(player);
            boolean deposit = page == BankMenuHolder.Page.DEPOSIT;
            BankMenuHolder holder = new BankMenuHolder(player.getUniqueId(), page);
            Inventory inventory = Bukkit.createInventory(holder, 36,
                    lang.text(deposit ? "gui.amount.deposit-title" : "gui.amount.withdraw-title"));
            holder.attach(inventory);
            fill(inventory);

            List<BigDecimal> amounts = service.settings().menuAmounts();
            int count = Math.min(amounts.size(), AMOUNT_SLOTS.length);
            for (int index = 0; index < count; index++) {
                BigDecimal amount = amounts.get(index);
                Material material = deposit ? Material.GOLD_INGOT : Material.EMERALD;
                inventory.setItem(AMOUNT_SLOTS[index], item(material, lang.text(
                                deposit ? "gui.amount.deposit-name" : "gui.amount.withdraw-name",
                                "amount", BankService.format(amount)),
                        List.of(lang.text("gui.amount.confirm-lore")), "amount", amount.toPlainString()));
            }

            BigDecimal all = deposit ? service.walletBalance(player) : account.balance();
            inventory.setItem(22, item(Material.NETHER_STAR, lang.text(
                            deposit ? "gui.amount.deposit-all-name" : "gui.amount.withdraw-all-name"),
                    List.of(lang.text("gui.amount.available", "amount", BankService.format(all))),
                    "all", all.toPlainString()));
            inventory.setItem(25, item(Material.OAK_SIGN, lang.text("gui.amount.custom-name"),
                    lang.list("gui.amount.custom-lore"), "custom", null));
            inventory.setItem(31, item(Material.ARROW, lang.text("gui.amount.back-name"),
                    List.of(lang.text("gui.amount.back-lore")), "back", null));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            lang.send(player, "messages.account-error");
        }
    }

    public String action(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public BigDecimal amount(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return BigDecimal.ZERO;
        }
        String value = stack.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.STRING);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private ItemStack item(Material material, Component name, List<Component> lore,
                           String action, String amount) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        if (action != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }
        if (amount != null) {
            meta.getPersistentDataContainer().set(amountKey, PersistentDataType.STRING, amount);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

}
