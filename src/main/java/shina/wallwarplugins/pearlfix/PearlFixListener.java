package shina.wallwarplugins.pearlfix;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import shina.wallwarplugins.Wallwarplugins;

public final class PearlFixListener implements Listener {

    private static final double GAP = 0.002;
    private static final double EPS = 1.0e-7;
    private static final int MAX_BLOCKS = 16384;

    private final Wallwarplugins plugin;
    private final Map<UUID, Impact> impacts = new HashMap<>();
    private final Set<Material> protectedBlocks = EnumSet.noneOf(Material.class);
    private Set<String> worlds = Set.of();
    private double maxDistance;
    private double step;

    private record Impact(int tick, UUID pearlId, Location pearlLocation,
                          Vector point, Vector backwards, BlockFace face,
                          double maxMatchDistance) {
    }

    public PearlFixListener(Wallwarplugins plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    public void shutdown() {
        impacts.clear();
    }

    private void loadSettings() {
        protectedBlocks.clear();
        for (String name : plugin.getConfig().getStringList("pearl-fix.protected-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isBlock()) {
                protectedBlocks.add(material);
            } else {
                plugin.getLogger().warning("Invalid pearl-fix protected block: " + name);
            }
        }
        if (protectedBlocks.isEmpty()) {
            protectedBlocks.add(Material.BEDROCK);
            protectedBlocks.add(Material.REINFORCED_DEEPSLATE);
        }
        worlds = new HashSet<>(plugin.getConfig().getStringList("pearl-fix.worlds"));
        maxDistance = setting("pearl-fix.max-correction-distance", 3.0, 0.5, 6.0);
        step = setting("pearl-fix.search-step", 0.025, 0.01, 0.25);
        impacts.clear();
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("pearl-fix.enabled", true);
    }

    private double setting(String key, double fallback, double min, double max) {
        double value = plugin.getConfig().getDouble(key, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player player)) {
            return;
        }

        UUID owner = player.getUniqueId();
        impacts.remove(owner);
        if (!worlds.isEmpty() && !worlds.contains(pearl.getWorld().getName())) {
            return;
        }

        Block block = event.getHitBlock();
        BlockFace face = event.getHitBlockFace();
        if (block == null || face == null || !face.isCartesian()) {
            return;
        }

        Vector velocity = pearl.getVelocity();
        double speed = velocity.length();
        if (!Double.isFinite(speed) || speed < EPS || speed > 16.0) {
            return;
        }

        Vector direction = velocity.clone().normalize();
        Location position = pearl.getLocation().clone();
        double reach = speed + 2.0;

        Location start = position.clone().subtract(direction.clone().multiply(reach));
        RayTraceResult trace = block.rayTrace(start, direction, reach * 2.0, FluidCollisionMode.NEVER);
        if (trace == null || trace.getHitBlockFace() != face) {
            return;
        }

        Vector backwards = direction.clone().multiply(-1);
        if (backwards.dot(face.getDirection()) <= EPS) {
            return;
        }

        Impact impact = new Impact(Bukkit.getCurrentTick(), pearl.getUniqueId(),
                position, trace.getHitPosition().clone(), backwards, face, reach);
        impacts.put(owner, impact);
        Bukkit.getScheduler().runTask(plugin, () -> impacts.remove(owner, impact));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!isEnabled()) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }

        Player player = event.getPlayer();
        Impact impact = impacts.remove(player.getUniqueId());
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        World world = to.getWorld();
        if (!worlds.isEmpty() && !worlds.contains(world.getName())) {
            return;
        }

        BoundingBox relative = player.getBoundingBox().clone()
                .shift(player.getLocation().toVector().multiply(-1));
        BoundingBox destinationBox = relative.clone().shift(to.toVector());
        List<BoundingBox> protectedShapes = shapes(world, destinationBox, true);

        if (protectedShapes == null) {
            event.setCancelled(true);
            return;
        }
        if (!overlaps(destinationBox, protectedShapes)) {
            return;
        }

        if (impact == null || impact.tick() != Bukkit.getCurrentTick()
                || !world.equals(impact.pearlLocation().getWorld())
                || to.distanceSquared(impact.pearlLocation())
                > impact.maxMatchDistance() * impact.maxMatchDistance()) {
            event.setCancelled(true);
            return;
        }

        Location corrected = resolve(world, relative, impact, to);
        if (corrected == null) {
            event.setCancelled(true);
        } else {
            event.setTo(corrected);
        }
    }

    private Location resolve(World world, BoundingBox relative, Impact hit, Location view) {
        Vector p = hit.point();
        double r = maxDistance;
        BoundingBox region = new BoundingBox(
                p.getX() - r + relative.getMinX(), p.getY() - r + relative.getMinY(),
                p.getZ() - r + relative.getMinZ(), p.getX() + r + relative.getMaxX(),
                p.getY() + r + relative.getMaxY(), p.getZ() + r + relative.getMaxZ());

        List<BoundingBox> obstacles = shapes(world, region, false);
        if (obstacles == null) {
            return null;
        }

        Vector approach = p.clone().add(hit.backwards().clone().multiply(GAP));
        Vector horizontalBack = hit.backwards().clone().setY(0);
        boolean horizontal = hit.face().getModY() == 0
                && horizontalBack.lengthSquared() > EPS;
        if (horizontal) {
            horizontalBack.normalize();
        }

        int count = (int) Math.ceil(maxDistance / step);
        for (int i = 0; i <= count; i++) {
            double distance = Math.min(maxDistance, i * step);
            int variants = horizontal && i > 0 ? 2 : 1;
            for (int variant = 0; variant < variants; variant++) {
                Vector back = variant == 0 ? hit.backwards() : horizontalBack;
                Vector candidate = p.clone().add(back.clone().multiply(distance));
                moveOutsideFace(candidate, relative, hit);
                if (candidate.distanceSquared(p) > r * r) {
                    continue;
                }

                BoundingBox box = relative.clone().shift(candidate);
                if (!withinBounds(world, box) || overlaps(box, obstacles)) {
                    continue;
                }

                Vector anchor = candidate.clone().add(new Vector(0, relative.getMinY() + GAP, 0));
                if (!clearSegment(approach, anchor, obstacles)) {
                    continue;
                }

                return new Location(world, candidate.getX(), candidate.getY(),
                        candidate.getZ(), view.getYaw(), view.getPitch());
            }
        }
        return null;
    }

    private static void moveOutsideFace(Vector position, BoundingBox box, Impact hit) {
        Vector p = hit.point();
        switch (hit.face()) {
            case WEST -> position.setX(Math.min(position.getX(), p.getX() - box.getMaxX() - GAP));
            case EAST -> position.setX(Math.max(position.getX(), p.getX() - box.getMinX() + GAP));
            case NORTH -> position.setZ(Math.min(position.getZ(), p.getZ() - box.getMaxZ() - GAP));
            case SOUTH -> position.setZ(Math.max(position.getZ(), p.getZ() - box.getMinZ() + GAP));
            case DOWN -> position.setY(Math.min(position.getY(), p.getY() - box.getMaxY() - GAP));
            case UP -> position.setY(Math.max(position.getY(), p.getY() - box.getMinY() + GAP));
            default -> throw new IllegalArgumentException("Unsupported hit face: " + hit.face());
        }
    }

    private static boolean withinBounds(World world, BoundingBox box) {
        if (box.getMinY() < world.getMinHeight()
                || box.getMaxY() > world.getMaxHeight()) {
            return false;
        }
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2;
        return box.getMinX() >= center.getX() - half
                && box.getMaxX() <= center.getX() + half
                && box.getMinZ() >= center.getZ() - half
                && box.getMaxZ() <= center.getZ() + half;
    }

    private static boolean overlaps(BoundingBox box, List<BoundingBox> shapes) {
        BoundingBox query = box.clone().expand(-EPS);
        for (BoundingBox shape : shapes) {
            if (query.overlaps(shape)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clearSegment(Vector from, Vector to, List<BoundingBox> obstacles) {
        Vector delta = to.clone().subtract(from);
        double length = delta.length();
        if (length < EPS) {
            return true;
        }
        Vector direction = delta.multiply(1.0 / length);
        for (BoundingBox box : obstacles) {
            if (box.contains(from) || box.rayTrace(from, direction, length) != null) {
                return false;
            }
        }
        return true;
    }

    private List<BoundingBox> shapes(World world, BoundingBox area, boolean onlyProtected) {
        int minX = (int) Math.floor(area.getMinX()) - 1;
        int maxX = (int) Math.floor(area.getMaxX()) + 1;
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(area.getMinY()) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(area.getMaxY()) + 1);
        int minZ = (int) Math.floor(area.getMinZ()) - 1;
        int maxZ = (int) Math.floor(area.getMaxZ()) + 1;
        long volume = ((long) maxX - minX + 1) * ((long) maxY - minY + 1)
                * ((long) maxZ - minZ + 1);
        if (minY > maxY) {
            return List.of();
        }
        if (volume <= 0 || volume > MAX_BLOCKS) {
            return null;
        }

        List<BoundingBox> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return null;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (onlyProtected && !protectedBlocks.contains(block.getType())) {
                        continue;
                    }
                    for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
                        result.add(local.clone().shift(x, y, z));
                    }
                }
            }
        }
        return result;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        impacts.remove(event.getPlayer().getUniqueId());
    }
}
