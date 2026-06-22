package shina.wallwarplugins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class FoodCooldownListener implements Listener {

    private final Wallwarplugins plugin;

    public FoodCooldownListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFoodConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        Integer cooldownTicks = readFoodCooldownTicks(item);
        if (cooldownTicks == null) {
            return;
        }

        Material material = item.getType();
        Player player = event.getPlayer();

        if (player.hasCooldown(material)) {
            event.setCancelled(true);
            String message = plugin.getConfig().getString("food-cooldown.cooldown-message");
            if (message != null && !message.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
            }
            return;
        }

        player.setCooldown(material, cooldownTicks);
    }

    private Integer readFoodCooldownTicks(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasItemName()) {
            return null;
        }

        Component itemName = meta.itemName();
        String name = PlainTextComponentSerializer.plainText().serialize(itemName);
        if (name.isEmpty()) {
            return null;
        }

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("food-cooldown.items");
        if (items == null || !items.contains(name)) {
            return null;
        }

        int cooldownTicks = items.getInt(name);
        return cooldownTicks > 0 ? cooldownTicks : null;
    }
}
