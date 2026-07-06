package shina.wallwarplugins;

import org.bukkit.plugin.java.JavaPlugin;

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
