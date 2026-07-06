package shina.wallwarplugins;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public class TeamMapGlowListener implements Listener {

    private final Wallwarplugins plugin;
    private final TeamMapGlowManager manager;

    public TeamMapGlowListener(Wallwarplugins plugin, TeamMapGlowManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            manager.trackPlayer(event.getPlayer());
            manager.updateViewerState(event.getPlayer());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.untrackPlayer(event.getPlayer());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> manager.updateViewerState(event.getPlayer()));
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> manager.updateViewerState(event.getPlayer()));
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> manager.updateViewerState(event.getPlayer()));
    }
}
