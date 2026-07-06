package shina.wallwarplugins;

import io.netty.channel.Channel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class NmsHelper {

    private static final byte GLOWING_FLAG = 0x40;
    private static final int FLAGS_METADATA_INDEX = 0;

    private static boolean initialized;
    private static boolean available;

    private static Method craftPlayerGetHandle;
    private static Field serverPlayerConnection;
    private static Field packetListenerConnection;
    private static Field connectionChannel;
    private static Method sendPacket;
    private static Method entityGetEntityData;
    private static Method dataWatcherGet;
    private static Object sharedFlagsAccessor;

    private static Class<?> clientboundSetEntityDataPacketClass;
    private static Class<?> bundlePacketClass;
    private static Field metadataEntityIdField;
    private static Field metadataItemsField;
    private static Constructor<?> metadataPacketConstructor;
    private static Method bundleSubPacketsMethod;
    private static Method dataValueCreate;
    private static Method dataValueId;
    private static Method dataValueValue;
    private static Method serializerCreateAccessor;

    private NmsHelper() {
    }

    static boolean init(Plugin plugin) {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> packetListenerClass = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
            Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> synchedEntityDataClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            Class<?> dataValueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
            Class<?> entityDataSerializerClass = Class.forName("net.minecraft.network.syncher.EntityDataSerializer");
            Class<?> entityDataSerializersClass = Class.forName("net.minecraft.network.syncher.EntityDataSerializers");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> entityDataAccessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");

            craftPlayerGetHandle = craftPlayerClass.getMethod("getHandle");
            serverPlayerConnection = serverPlayerClass.getField("connection");
            packetListenerConnection = packetListenerClass.getField("connection");
            connectionChannel = connectionClass.getField("channel");
            sendPacket = packetListenerClass.getMethod("send", packetClass);
            entityGetEntityData = entityClass.getMethod("getEntityData");
            dataWatcherGet = synchedEntityDataClass.getMethod("get", entityDataAccessorClass);

            serializerCreateAccessor = entityDataSerializerClass.getMethod("createAccessor", int.class);
            sharedFlagsAccessor = resolveSharedFlagsAccessor(entityClass, entityDataSerializersClass);

            clientboundSetEntityDataPacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            metadataEntityIdField = clientboundSetEntityDataPacketClass.getDeclaredField("id");
            metadataEntityIdField.setAccessible(true);
            metadataItemsField = clientboundSetEntityDataPacketClass.getDeclaredField("packedItems");
            metadataItemsField.setAccessible(true);
            metadataPacketConstructor = clientboundSetEntityDataPacketClass.getConstructor(int.class, List.class);

            dataValueCreate = dataValueClass.getMethod("create", entityDataAccessorClass, Object.class);
            dataValueId = dataValueClass.getMethod("id");
            dataValueValue = dataValueClass.getMethod("value");

            try {
                bundlePacketClass = Class.forName("net.minecraft.network.protocol.BundlePacket");
                bundleSubPacketsMethod = bundlePacketClass.getMethod("subPackets");
            } catch (ReflectiveOperationException ignored) {
                bundlePacketClass = null;
                bundleSubPacketsMethod = null;
            }

            available = true;
        } catch (ReflectiveOperationException exception) {
            available = false;
            plugin.getLogger().severe("Team map glow NMS init failed: " + exception.getMessage());
            exception.printStackTrace();
        }
        return available;
    }

    static boolean isAvailable() {
        return available;
    }

    static Channel getChannel(Player player) throws ReflectiveOperationException {
        Object serverPlayer = craftPlayerGetHandle.invoke(player);
        Object packetListener = serverPlayerConnection.get(serverPlayer);
        Object connection = packetListenerConnection.get(packetListener);
        return (Channel) connectionChannel.get(connection);
    }

    static void sendPacket(Player player, Object packet) throws ReflectiveOperationException {
        Object serverPlayer = craftPlayerGetHandle.invoke(player);
        Object packetListener = serverPlayerConnection.get(serverPlayer);
        sendPacket.invoke(packetListener, packet);
    }

    static byte getEntityFlags(Player player) throws ReflectiveOperationException {
        Object nmsEntity = craftPlayerGetHandle.invoke(player);
        Object dataWatcher = entityGetEntityData.invoke(nmsEntity);
        return (byte) dataWatcherGet.invoke(dataWatcher, sharedFlagsAccessor);
    }

    static Object createMetadataPacket(int entityId, byte flags) throws ReflectiveOperationException {
        List<Object> items = new ArrayList<>(1);
        items.add(dataValueCreate.invoke(null, sharedFlagsAccessor, flags));
        return metadataPacketConstructor.newInstance(entityId, items);
    }

    static void sendEntityFlags(Player receiver, int entityId, byte flags) throws ReflectiveOperationException {
        sendPacket(receiver, createMetadataPacket(entityId, flags));
    }

    static boolean isEntityDataPacket(Object packet) {
        return clientboundSetEntityDataPacketClass.isInstance(packet);
    }

    static boolean isBundlePacket(Object packet) {
        return bundlePacketClass != null && bundlePacketClass.isInstance(packet);
    }

    static int getEntityId(Object packet) throws ReflectiveOperationException {
        return metadataEntityIdField.getInt(packet);
    }

    @SuppressWarnings("unchecked")
    static List<Object> getMetadataItems(Object packet) throws ReflectiveOperationException {
        return (List<Object>) metadataItemsField.get(packet);
    }

    static Object createFlagsDataValue(byte flags) throws ReflectiveOperationException {
        return dataValueCreate.invoke(null, sharedFlagsAccessor, flags);
    }

    static boolean isFlagsDataValue(Object dataValue) throws ReflectiveOperationException {
        return ((int) dataValueId.invoke(dataValue)) == FLAGS_METADATA_INDEX;
    }

    static byte getFlagsDataValue(Object dataValue) throws ReflectiveOperationException {
        return (byte) dataValueValue.invoke(dataValue);
    }

    static Object rebuildMetadataPacket(int entityId, List<Object> items) throws ReflectiveOperationException {
        return metadataPacketConstructor.newInstance(entityId, items);
    }

    static byte withGlow(byte flags) {
        return (byte) (flags | GLOWING_FLAG);
    }

    private static Object resolveSharedFlagsAccessor(Class<?> entityClass, Class<?> entityDataSerializersClass)
            throws ReflectiveOperationException {
        ReflectiveOperationException lastFailure = null;

        for (String serializerFieldName : new String[]{"BYTE", "f_135034_"}) {
            try {
                Object byteSerializer = entityDataSerializersClass.getField(serializerFieldName).get(null);
                return serializerCreateAccessor.invoke(byteSerializer, FLAGS_METADATA_INDEX);
            } catch (ReflectiveOperationException exception) {
                lastFailure = exception;
            }
        }

        for (String flagsFieldName : new String[]{"DATA_SHARED_FLAGS_ID", "FLAGS"}) {
            try {
                return entityClass.getField(flagsFieldName).get(null);
            } catch (ReflectiveOperationException exception) {
                lastFailure = exception;
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new NoSuchFieldException("Unable to resolve entity shared flags accessor");
    }

    @SuppressWarnings("unchecked")
    static Iterable<Object> getBundleSubPackets(Object bundle) throws ReflectiveOperationException {
        return (Iterable<Object>) bundleSubPacketsMethod.invoke(bundle);
    }
}
