package shina.wallwarplugins;

import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class TntMinecartListener implements Listener {

    private final Wallwarplugins plugin;

    public TntMinecartListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerDamageByTntMinecart(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || !(event.getDamager() instanceof ExplosiveMinecart)) {
            return;
        }

        double finalDamage = event.getFinalDamage();
        double maxFinalDamage = plugin.getConfig().getDouble("max-tnt-minecart-damage", 16.0);

        if (finalDamage > maxFinalDamage) {
            double scale = maxFinalDamage / finalDamage;
            event.setDamage(event.getDamage() * scale);
        }
    }
}
