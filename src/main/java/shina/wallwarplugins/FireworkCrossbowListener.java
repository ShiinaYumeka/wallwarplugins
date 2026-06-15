package shina.wallwarplugins;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;

public class FireworkCrossbowListener implements Listener {

    private final Wallwarplugins plugin;

    public FireworkCrossbowListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFireworkHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Firework firework)) {
            return;
        }

        if (!firework.isShotAtAngle() || !(firework.getShooter() instanceof Player)) {
            return;
        }

        FireworkMeta meta = firework.getFireworkMeta();
        int starCount = meta.getEffectsSize();

        if (starCount <= 0) {
            return;
        }

        double baseDamage = plugin.getConfig().getDouble("firework.base-damage", 10.0);
        double damagePerExtraStar = plugin.getConfig().getDouble("firework.damage-per-star", 2.0);
        double newDamage = baseDamage + ((starCount - 1) * damagePerExtraStar);

        event.setDamage(newDamage);
    }
}
