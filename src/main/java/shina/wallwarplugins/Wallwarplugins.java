package shina.wallwarplugins;

import org.bukkit.plugin.java.JavaPlugin;
import shina.wallwarplugins.noquickswap.NoQuickSwapListener;
import shina.wallwarplugins.noquickswap.NoQuickSwapNms;

public final class Wallwarplugins extends JavaPlugin {

    private TeamMapGlowManager teamMapGlowManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new TntMinecartListener(this), this);
        getServer().getPluginManager().registerEvents(new FireworkCrossbowListener(this), this);
        getServer().getPluginManager().registerEvents(new MobExpListener(this), this);
        getServer().getPluginManager().registerEvents(new FoodCooldownListener(this), this);
        getServer().getPluginManager().registerEvents(new InstantHealUndeadListener(this), this);
        getServer().getPluginManager().registerEvents(new WeavingMechanicListener(this), this);

        if (NoQuickSwapNms.init(this)) {
            getServer().getPluginManager().registerEvents(new NoQuickSwapListener(this), this);
        } else {
            getLogger().warning("NoQuickSwap feature disabled due to NMS initialization failure.");
        }

        if (NmsHelper.init(this)) {
            teamMapGlowManager = new TeamMapGlowManager(this);
            getServer().getPluginManager().registerEvents(new TeamMapGlowListener(this, teamMapGlowManager), this);
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                teamMapGlowManager.trackPlayer(player);
                teamMapGlowManager.updateViewerState(player);
            }
        } else {
            getLogger().warning("Team map glow feature disabled due to NMS initialization failure.");
        }
    }

    @Override
    public void onDisable() {
        if (teamMapGlowManager != null) {
            teamMapGlowManager.shutdown();
        }
    }
}
