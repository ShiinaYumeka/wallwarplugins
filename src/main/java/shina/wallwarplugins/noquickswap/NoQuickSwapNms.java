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
    private static Method setSelectedSlot;
    private static Field selectedField;
    private static Method detectEquipmentUpdates;
    private static Method resetAttackStrengthTicker;
    private static Field itemSwapTickerField;

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
            getInventory = findMethod(serverPlayerClass, "getInventory");
            setSelectedSlot = findMethod(inventoryClass, int.class, "setSelectedSlot");
            selectedField = findField(inventoryClass, int.class, "selected", "selectedSlot");
            detectEquipmentUpdates = findMethod(
                    livingEntityClass,
                    "detectEquipmentUpdatesPublic",
                    "detectEquipmentUpdates",
                    "sendEquipmentChanges"
            );
            resetAttackStrengthTicker = findMethod(
                    serverPlayerClass,
                    "resetAttackStrengthTicker",
                    "resetTicksSince"
            );
            itemSwapTickerField = findField(
                    livingEntityClass,
                    int.class,
                    "itemSwapTicker",
                    "ticksSinceHandEquipping"
            );

            if (setSelectedSlot == null && selectedField == null) {
                throw new NoSuchFieldException("Unable to resolve inventory selected slot");
            }
            if (resetAttackStrengthTicker == null) {
                throw new NoSuchMethodException("Unable to resolve resetAttackStrengthTicker");
            }

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

        if (setSelectedSlot != null) {
            setSelectedSlot.invoke(inventory, newSlot);
        } else {
            selectedField.setInt(inventory, newSlot);
        }

        if (detectEquipmentUpdates != null) {
            detectEquipmentUpdates.invoke(serverPlayer);
        }

        resetAttackStrengthTicker.invoke(serverPlayer);

        if (itemSwapTickerField != null) {
            itemSwapTickerField.setInt(serverPlayer, 0);
        }

        player.resetCooldown();
    }

    static void applyHandSwap(Player player) throws ReflectiveOperationException {
        Object serverPlayer = craftPlayerGetHandle.invoke(player);

        if (detectEquipmentUpdates != null) {
            detectEquipmentUpdates.invoke(serverPlayer);
        }

        resetAttackStrengthTicker.invoke(serverPlayer);

        if (itemSwapTickerField != null) {
            itemSwapTickerField.setInt(serverPlayer, 0);
        }

        player.resetCooldown();
    }

    private static Method findMethod(Class<?> type, String... names) {
        return findMethod(type, new Class<?>[0], names);
    }

    private static Method findMethod(Class<?> type, Class<?> parameterType, String... names) {
        return findMethod(type, new Class<?>[]{parameterType}, names);
    }

    private static Method findMethod(Class<?> type, Class<?>[] parameterTypes, String... names) {
        for (String name : names) {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                try {
                    Method method = current.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, Class<?> fieldType, String... names) {
        for (String name : names) {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (field.getType() == fieldType) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
