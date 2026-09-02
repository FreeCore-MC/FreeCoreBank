package io.github.freecorebank.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class BankMenuHolder implements InventoryHolder {
    public enum Page {
        MAIN,
        DEPOSIT,
        WITHDRAW
    }

    private final UUID playerId;
    private final Page page;
    private Inventory inventory;

    public BankMenuHolder(UUID playerId, Page page) {
        this.playerId = playerId;
        this.page = page;
    }

    public UUID playerId() {
        return playerId;
    }

    public Page page() {
        return page;
    }

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("菜单尚未创建");
        }
        return inventory;
    }
}
