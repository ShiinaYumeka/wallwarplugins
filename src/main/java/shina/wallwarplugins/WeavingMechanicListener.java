package shina.wallwarplugins;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class WeavingMechanicListener implements Listener {

    private final Wallwarplugins plugin;

    public WeavingMechanicListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBrew(BrewEvent event) {
        // Delay 1 tick: BrewEvent fires before slots become finished potions.
        Bukkit.getScheduler().runTask(plugin, () -> {
            BrewerInventory inventory = event.getContents();
            int customDuration = plugin.getConfig().getInt("weaving-buff.duration-ticks", 1200);

            for (int i = 0; i < 3; i++) {
                ItemStack item = inventory.getItem(i);
                if (item == null || !isPotion(item.getType())) {
                    continue;
                }

                PotionMeta meta = (PotionMeta) item.getItemMeta();
                if (meta == null || !isWeavingPotion(meta.getBasePotionType())) {
                    continue;
                }

                meta.addCustomEffect(new PotionEffect(PotionEffectType.WEAVING, customDuration, 0), true);
                item.setItemMeta(meta);
            }
        });
    }

    @EventHandler
    public void onPlayerMoveInCobweb(PlayerMoveEvent event) {
        // 1. Skip look-only moves (cheapest filter).
        if (!event.hasChangedPosition()) {
            return;
        }

        Player player = event.getPlayer();

        // 2. Potion check before any block scan.
        if (!player.hasPotionEffect(PotionEffectType.WEAVING)) {
            return;
        }

        // 3. AABB cobweb check only for weaving players.
        if (!isTouchingCobweb(player)) {
            return;
        }

        Vector intent = event.getTo().toVector().subtract(event.getFrom().toVector());
        Vector velocity = player.getVelocity();
        boolean update = false;

        if (intent.getY() < 0) {
            velocity.setY(plugin.getConfig().getDouble("weaving-buff.fall-speed", -0.4));
            update = true;
        }

        double horizontal = Math.sqrt(intent.getX() * intent.getX() + intent.getZ() * intent.getZ());
        if (horizontal > 0.01 && horizontal < 0.5) {
            Vector dir = new Vector(intent.getX(), 0, intent.getZ()).normalize();
            double boost = plugin.getConfig().getDouble("weaving-buff.horizontal-boost", 0.3);
            velocity.setX(dir.getX() * boost);
            velocity.setZ(dir.getZ() * boost);
            update = true;
        }

        if (update) {
            player.setVelocity(velocity);
        }
    }

    private static boolean isTouchingCobweb(Player player) {
        BoundingBox box = player.getBoundingBox();
        World world = player.getWorld();

        int minX = (int) Math.floor(box.getMinX());
        int minY = (int) Math.floor(box.getMinY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxX = (int) Math.ceil(box.getMaxX());
        int maxY = (int) Math.ceil(box.getMaxY());
        int maxZ = (int) Math.ceil(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWeavingPotion(PotionType type) {
        return type == PotionType.WEAVING;
    }

    private static boolean isPotion(Material type) {
        return type == Material.POTION
                || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION;
    }
}
