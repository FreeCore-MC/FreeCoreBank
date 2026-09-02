package io.github.freecorebank.menu;

import io.github.freecorebank.bank.BankService;
import io.github.freecorebank.bank.MoneyInput;
import io.github.freecorebank.lang.Lang;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CustomAmountInput implements Listener {
    private final Plugin plugin;
    private final BankService service;
    private final BankMenu menu;
    private final Lang lang;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public CustomAmountInput(Plugin plugin, BankService service, BankMenu menu, Lang lang) {
        this.plugin = plugin;
        this.service = service;
        this.menu = menu;
        this.lang = lang;
    }

    public void open(Player player, BankMenuHolder.Page page) {
        cleanup(player.getUniqueId(), false);
        Block block = findInputBlock(player);
        if (block == null) {
            lang.send(player, "messages.sign-no-space");
            return;
        }

        BlockData original = block.getBlockData().clone();
        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(original, false);
            lang.send(player, "messages.sign-no-space");
            return;
        }
        sign.setLine(0, lang.plain("sign.line-1"));
        sign.setLine(1, lang.plain("sign.line-2"));
        sign.setLine(2, lang.plain("sign.line-3"));
        sign.setLine(3, lang.plain("sign.line-4"));
        sign.update(true, false);

        Session session = new Session(player.getUniqueId(), page, block, original);
        sessions.put(player.getUniqueId(), session);
        long timeoutTicks = Math.max(20L, plugin.getConfig().getLong("sign-input.timeout-seconds", 60L) * 20L);
        session.timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) == session) {
                cleanup(player.getUniqueId(), false);
                if (player.isOnline()) {
                    lang.send(player, "messages.sign-timeout");
                    menu.openAmounts(player, page);
                }
            }
        }, timeoutTicks);
        player.closeInventory();
        player.openSign(sign, Side.FRONT);
        lang.send(player, "messages.sign-opened");
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || !sameBlock(session.block, event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        sessions.remove(session.playerId);
        cancelTimeout(session);
        Player player = event.getPlayer();
        var parsed = MoneyInput.parse(event.getLine(0));
        if (parsed.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                restore(session);
                lang.send(player, "messages.sign-invalid");
                menu.openAmounts(player, session.page);
            });
            return;
        }
        var amount = parsed.get();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            restore(session);
            BankService.TransactionResult result = session.page == BankMenuHolder.Page.DEPOSIT
                    ? service.deposit(player, amount)
                    : service.withdraw(player, amount);
            result.send(player);
            menu.openAmounts(player, session.page);
        });
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Session session = sessionAt(event.getBlock());
        if (session != null) {
            event.setCancelled(true);
            cleanup(session.playerId, true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId(), false);
    }

    public void closeAll() {
        for (UUID playerId : sessions.keySet().toArray(UUID[]::new)) {
            cleanup(playerId, false);
        }
    }

    private Block findInputBlock(Player player) {
        Block origin = player.getLocation().getBlock();
        for (int y = 0; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = origin.getRelative(x, y, z);
                    Material type = block.getType();
                    if (!isAir(type) || !block.getRelative(BlockFace.DOWN).getType().isSolid()) {
                        continue;
                    }
                    return block;
                }
            }
        }
        return null;
    }

    private void cleanup(UUID playerId, boolean notify) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        cancelTimeout(session);
        restore(session);
        if (notify) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                lang.send(player, "messages.sign-cancelled");
            }
        }
    }

    private static void cancelTimeout(Session session) {
        if (session.timeout != null) {
            session.timeout.cancel();
            session.timeout = null;
        }
    }

    private static void restore(Session session) {
        if (session.block.getType() == Material.OAK_SIGN || session.block.getType() == Material.OAK_WALL_SIGN) {
            session.block.setBlockData(session.original, false);
        }
    }

    private static boolean sameBlock(Block first, Block second) {
        return first.getWorld().equals(second.getWorld()) && first.getX() == second.getX()
                && first.getY() == second.getY() && first.getZ() == second.getZ();
    }

    private Session sessionAt(Block block) {
        for (Session session : sessions.values()) {
            if (sameBlock(session.block, block)) {
                return session;
            }
        }
        return null;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static final class Session {
        private final UUID playerId;
        private final BankMenuHolder.Page page;
        private final Block block;
        private final BlockData original;
        private BukkitTask timeout;

        private Session(UUID playerId, BankMenuHolder.Page page, Block block, BlockData original) {
            this.playerId = playerId;
            this.page = page;
            this.block = block;
            this.original = original;
        }
    }
}
