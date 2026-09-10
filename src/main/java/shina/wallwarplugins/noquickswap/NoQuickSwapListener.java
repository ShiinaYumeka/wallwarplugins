package shina.wallwarplugins.noquickswap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

public final class NoQuickSwapListener implements Listener {

    private final Plugin plugin;

    public NoQuickSwapListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (!NoQuickSwapNms.isAvailable()) {
            return;
        }

        Player player = event.getPlayer();
        try {
            NoQuickSwapNms.applyHotbarSwap(player, event.getNewSlot());
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "NoQuickSwap failed for " + player.getName() + ": " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHands(PlayerSwapHandItemsEvent event) {
        if (!NoQuickSwapNms.isAvailable()) {
            return;
        }

        Player player = event.getPlayer();
        try {
            NoQuickSwapNms.applyHandSwap(player);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "NoQuickSwap hand swap failed for " + player.getName() + ": " + exception.getMessage());
        }
    }
}
