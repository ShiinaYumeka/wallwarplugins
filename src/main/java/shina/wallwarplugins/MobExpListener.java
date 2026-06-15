package shina.wallwarplugins;

import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class MobExpListener implements Listener {

    private final Wallwarplugins plugin;

    public MobExpListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMonsterDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }

        int originalExp = event.getDroppedExp();
        if (originalExp <= 0) {
            return;
        }

        double multiplier = plugin.getConfig().getDouble("exp.monster-multiplier", 2.0);
        int newExp = (int) Math.round(originalExp * multiplier);

        event.setDroppedExp(newExp);
    }
}
