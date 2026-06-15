package shina.wallwarplugins;

import org.bukkit.plugin.java.JavaPlugin;

public final class Wallwarplugins extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new TntMinecartListener(this), this);
        getServer().getPluginManager().registerEvents(new FireworkCrossbowListener(this), this);
        getServer().getPluginManager().registerEvents(new MobExpListener(this), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
