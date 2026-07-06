package shina.wallwarplugins;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeamMapGlowManager {

    private static final String HANDLER_NAME = "wallwar_team_map_glow";

    private final Plugin plugin;
    private final Set<UUID> activeViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ChannelDuplexHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Integer, Player> entityIdToPlayer = new ConcurrentHashMap<>();

    public TeamMapGlowManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return NmsHelper.isAvailable();
    }

    public void trackPlayer(Player player) {
        entityIdToPlayer.put(player.getEntityId(), player);
        injectHandler(player);
    }

    public void untrackPlayer(Player player) {
        activeViewers.remove(player.getUniqueId());
        entityIdToPlayer.remove(player.getEntityId());
        removeHandler(player);
    }

    public void updateViewerState(Player player) {
        if (!NmsHelper.isAvailable()) {
            return;
        }

        if (isMapItem(player.getInventory().getItemInMainHand())
                || isMapItem(player.getInventory().getItemInOffHand())) {
            activate(player);
        } else {
            deactivate(player);
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            untrackPlayer(player);
        }
        activeViewers.clear();
        handlers.clear();
        entityIdToPlayer.clear();
    }

    private void activate(Player viewer) {
        if (!activeViewers.add(viewer.getUniqueId())) {
            return;
        }
        refreshTeammateGlow(viewer, true);
    }

    private void deactivate(Player viewer) {
        if (!activeViewers.remove(viewer.getUniqueId())) {
            return;
        }
        refreshTeammateGlow(viewer, false);
    }

    private void refreshTeammateGlow(Player viewer, boolean glowing) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player teammate : getTeammates(viewer)) {
                try {
                    byte flags = NmsHelper.getEntityFlags(teammate);
                    byte packetFlags = glowing ? NmsHelper.withGlow(flags) : flags;
                    NmsHelper.sendEntityFlags(viewer, teammate.getEntityId(), packetFlags);
                } catch (ReflectiveOperationException exception) {
                    plugin.getLogger().warning("Failed to refresh teammate glow for " + viewer.getName() + ": "
                            + exception.getMessage());
                }
            }
        });
    }

    private void injectHandler(Player player) {
        if (handlers.containsKey(player.getUniqueId())) {
            return;
        }

        ChannelDuplexHandler handler = new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (!activeViewers.contains(player.getUniqueId())) {
                    super.write(context, message, promise);
                    return;
                }

                if (NmsHelper.isEntityDataPacket(message)) {
                    Object modified = modifyEntityDataPacket(player, message);
                    if (modified != null) {
                        NmsHelper.sendPacket(player, modified);
                        return;
                    }
                } else if (NmsHelper.isBundlePacket(message)) {
                    if (handleBundlePacket(player, message)) {
                        super.write(context, message, promise);
                        Bukkit.getScheduler().runTask(plugin, () -> refreshTeammateGlow(player, true));
                        return;
                    }
                }

                super.write(context, message, promise);
            }
        };

        try {
            if (NmsHelper.getChannel(player).pipeline().get(HANDLER_NAME) == null) {
                NmsHelper.getChannel(player).pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
            }
            handlers.put(player.getUniqueId(), handler);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to inject glow handler for " + player.getName() + ": "
                    + exception.getMessage());
        }
    }

    private void removeHandler(Player player) {
        ChannelDuplexHandler handler = handlers.remove(player.getUniqueId());
        if (handler == null) {
            return;
        }

        try {
            if (NmsHelper.getChannel(player).pipeline().get(HANDLER_NAME) != null) {
                NmsHelper.getChannel(player).pipeline().remove(HANDLER_NAME);
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to remove glow handler for " + player.getName() + ": "
                    + exception.getMessage());
        }
    }

    private Object modifyEntityDataPacket(Player viewer, Object packet) throws ReflectiveOperationException {
        int entityId = NmsHelper.getEntityId(packet);
        Player target = entityIdToPlayer.get(entityId);
        if (!isTeammate(viewer, target)) {
            return null;
        }

        List<Object> items = NmsHelper.getMetadataItems(packet);
        if (items == null) {
            return null;
        }

        boolean containsFlags = false;
        List<Object> modifiedItems = null;

        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            if (!NmsHelper.isFlagsDataValue(item)) {
                continue;
            }

            containsFlags = true;
            byte flags = NmsHelper.getFlagsDataValue(item);
            byte glowingFlags = NmsHelper.withGlow(flags);
            if (glowingFlags == flags) {
                break;
            }

            modifiedItems = new ArrayList<>(items);
            modifiedItems.set(index, NmsHelper.createFlagsDataValue(glowingFlags));
            break;
        }

        if (modifiedItems == null && !containsFlags) {
            byte glowingFlags = NmsHelper.withGlow((byte) 0);
            if (glowingFlags != 0) {
                modifiedItems = new ArrayList<>(items);
                modifiedItems.add(NmsHelper.createFlagsDataValue(glowingFlags));
            }
        }

        if (modifiedItems == null) {
            return null;
        }

        return NmsHelper.rebuildMetadataPacket(entityId, modifiedItems);
    }

    private boolean handleBundlePacket(Player viewer, Object bundle) throws ReflectiveOperationException {
        for (Object packet : NmsHelper.getBundleSubPackets(bundle)) {
            if (!NmsHelper.isEntityDataPacket(packet)) {
                continue;
            }

            int entityId = NmsHelper.getEntityId(packet);
            Player target = entityIdToPlayer.get(entityId);
            if (isTeammate(viewer, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMapItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        List<String> configuredMaterials = plugin.getConfig().getStringList("team-map-glow.materials");
        if (configuredMaterials.isEmpty()) {
            return item.getType() == Material.FILLED_MAP || item.getType() == Material.MAP;
        }

        return configuredMaterials.contains(item.getType().name());
    }

    private Collection<Player> getTeammates(Player viewer) {
        Scoreboard scoreboard = viewer.getScoreboard();
        Team viewerTeam = scoreboard.getEntryTeam(viewer.getName());
        if (viewerTeam == null) {
            return Collections.emptyList();
        }

        List<Player> teammates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isTeammate(viewer, online, viewerTeam, scoreboard)) {
                teammates.add(online);
            }
        }
        return teammates;
    }

    private boolean isTeammate(Player viewer, Player target) {
        if (target == null || viewer.equals(target)) {
            return false;
        }

        Scoreboard scoreboard = viewer.getScoreboard();
        Team viewerTeam = scoreboard.getEntryTeam(viewer.getName());
        return isTeammate(viewer, target, viewerTeam, scoreboard);
    }

    private boolean isTeammate(Player viewer, Player target, Team viewerTeam, Scoreboard scoreboard) {
        if (viewerTeam == null || viewer.equals(target)) {
            return false;
        }

        Team targetTeam = scoreboard.getEntryTeam(target.getName());
        return viewerTeam.equals(targetTeam);
    }
}
