package io.github.freecorebank.menu;

import io.github.freecorebank.bank.BankService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.math.BigDecimal;

public final class BankMenuListener implements Listener {
    private final BankService service;
    private final BankMenu menu;
    private final CustomAmountInput customAmountInput;

    public BankMenuListener(BankService service, BankMenu menu, CustomAmountInput customAmountInput) {
        this.service = service;
        this.menu = menu;
        this.customAmountInput = customAmountInput;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BankMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId().equals(player.getUniqueId())
                || event.getClickedInventory() != top) {
            return;
        }

        String action = menu.action(event.getCurrentItem());
        if (action == null) {
            return;
        }
        switch (action) {
            case "deposit-menu" -> menu.openAmounts(player, BankMenuHolder.Page.DEPOSIT);
            case "withdraw-menu" -> menu.openAmounts(player, BankMenuHolder.Page.WITHDRAW);
            case "back" -> menu.openMain(player);
            case "custom" -> customAmountInput.open(player, holder.page());
            case "amount", "all" -> transact(player, holder, menu.amount(event.getCurrentItem()));
            default -> {
            }
        }
    }

    private void transact(Player player, BankMenuHolder holder, BigDecimal amount) {
        BankService.TransactionResult result = holder.page() == BankMenuHolder.Page.DEPOSIT
                ? service.deposit(player, amount)
                : service.withdraw(player, amount);
        result.send(player);
        if (result.success()) {
            menu.openAmounts(player, holder.page());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BankMenuHolder) {
            event.setCancelled(true);
        }
    }
}
