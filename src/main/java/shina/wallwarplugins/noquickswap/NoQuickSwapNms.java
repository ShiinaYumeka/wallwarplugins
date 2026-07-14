package shina.wallwarplugins.noquickswap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class NoQuickSwapNms {

    private static boolean initialized;
    private static boolean available;

    private static Method craftPlayerGetHandle;
    private static Method getInventory;
    private static Field selectedField;
    private static Method detectEquipmentUpdatesPublic;
    private static Method resetAttackStrengthTicker;

    private NoQuickSwapNms() {
    }

    public static boolean init(Plugin plugin) {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity");
            Class<?> inventoryClass = Class.forName("net.minecraft.world.entity.player.Inventory");

            craftPlayerGetHandle = craftPlayerClass.getMethod("getHandle");
            getInventory = serverPlayerClass.getMethod("getInventory");
            selectedField = inventoryClass.getField("selected");
            detectEquipmentUpdatesPublic = livingEntityClass.getMethod("detectEquipmentUpdatesPublic");
            resetAttackStrengthTicker = serverPlayerClass.getMethod("resetAttackStrengthTicker");

            available = true;
        } catch (ReflectiveOperationException exception) {
            available = false;
            plugin.getLogger().severe("NoQuickSwap NMS init failed: " + exception.getMessage());
            exception.printStackTrace();
        }
        return available;
    }

    static boolean isAvailable() {
        return available;
    }

    static void applyHotbarSwap(Player player, int newSlot) throws ReflectiveOperationException {
        Object serverPlayer = craftPlayerGetHandle.invoke(player);
        Object inventory = getInventory.invoke(serverPlayer);
        selectedField.setInt(inventory, newSlot);
        detectEquipmentUpdatesPublic.invoke(serverPlayer);
        resetAttackStrengthTicker.invoke(serverPlayer);
    }
}
