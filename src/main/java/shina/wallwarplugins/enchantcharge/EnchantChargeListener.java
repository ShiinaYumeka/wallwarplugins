package shina.wallwarplugins.enchantcharge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import shina.wallwarplugins.Wallwarplugins;

public final class EnchantChargeListener implements Listener {

    private static final String ENCHANTS_PATH = "enchant-charge.enchants";

    private final Wallwarplugins plugin;
    private final Map<UUID, Attack> attacks = new HashMap<>();
    private final Map<Enchantment, Float> enchantMinCharge = new HashMap<>();
    private boolean swordsOnly;

    private static final class Attack {
        final int tick;
        final UUID primaryTarget;
        final float charge;
        final Map<Enchantment, Float> gatedEnchants;
        final Set<UUID> damagedTargets = new HashSet<>();
        final Map<UUID, Integer> freezeTicksBefore = new HashMap<>();

        Attack(int tick, UUID primaryTarget, float charge, Map<Enchantment, Float> gatedEnchants) {
            this.tick = tick;
            this.primaryTarget = primaryTarget;
            this.charge = charge;
            this.gatedEnchants = gatedEnchants;
        }
    }

    public EnchantChargeListener(Wallwarplugins plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    public void shutdown() {
        attacks.clear();
    }

    private void loadSettings() {
        enchantMinCharge.clear();
        swordsOnly = plugin.getConfig().getBoolean("enchant-charge.swords-only", true);

        ConfigurationSection section = plugin.getConfig().getConfigurationSection(ENCHANTS_PATH);
        if (section == null || section.getKeys(false).isEmpty()) {
            enchantMinCharge.put(Enchantment.FIRE_ASPECT, 0.60f);
        } else {
            loadEnchantSection(section);
            if (enchantMinCharge.isEmpty()) {
                plugin.getLogger().warning("No valid enchant-charge enchants configured; defaulting to FIRE_ASPECT.");
                enchantMinCharge.put(Enchantment.FIRE_ASPECT, 0.60f);
            }
        }
        attacks.clear();
    }

    private void loadEnchantSection(ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                loadEnchantSection(child);
                continue;
            }

            String currentPath = section.getCurrentPath();
            String enchantName;
            if (ENCHANTS_PATH.equals(currentPath)) {
                enchantName = key;
            } else {
                String nested = currentPath.substring(ENCHANTS_PATH.length() + 1).replace('.', ':');
                enchantName = nested + ":" + key;
            }

            Enchantment enchantment = resolveEnchantment(enchantName);
            if (enchantment == null) {
                plugin.getLogger().warning("Unknown enchant-charge enchantment: " + enchantName
                        + " (datapack id is namespace:name, e.g. mypack:freeze)");
                continue;
            }
            float minCharge = threshold(currentPath + "." + key, 0.60f);
            enchantMinCharge.put(enchantment, minCharge);
            plugin.getLogger().info("Enchant-charge watching " + enchantment.getKey() + " (min-charge " + minCharge + ")");
        }
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("enchant-charge.enabled", true);
    }

    private float threshold(String path, float fallback) {
        double value = plugin.getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            plugin.getLogger().warning(path + " must be between 0.0 and 1.0; using default.");
            return fallback;
        }
        return (float) value;
    }

    private Enchantment resolveEnchantment(String name) {
        String raw = name.toLowerCase(Locale.ROOT).trim();
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);

        Key key = parseKey(raw);
        if (key != null) {
            Enchantment direct = registry.get(RegistryKey.ENCHANTMENT.typedKey(key));
            if (direct != null) {
                return direct;
            }
        }
        if (raw.contains(":")) {
            return null;
        }

        Enchantment match = null;
        for (Enchantment enchantment : registry) {
            if (enchantment.getKey().getKey().equals(raw)) {
                if (match != null && !match.getKey().equals(enchantment.getKey())) {
                    plugin.getLogger().warning("Multiple enchantments named '" + raw
                            + "'; specify the full datapack key such as " + enchantment.getKey());
                    return null;
                }
                match = enchantment;
            }
        }
        return match;
    }

    private static Key parseKey(String raw) {
        try {
            return raw.contains(":") ? Key.key(raw) : Key.key("minecraft", raw);
        } catch (InvalidKeyException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int enchantmentLevel(ItemStack item, Enchantment enchantment) {
        Integer level = item.getEnchantments().get(enchantment);
        if (level != null && level > 0) {
            return level;
        }
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            if (sameEnchantment(entry.getKey(), enchantment)) {
                return entry.getValue();
            }
        }

        ItemEnchantments data = item.getData(DataComponentTypes.ENCHANTMENTS);
        if (data == null) {
            return 0;
        }
        for (Map.Entry<Enchantment, Integer> entry : data.enchantments().entrySet()) {
            if (sameEnchantment(entry.getKey(), enchantment)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private static boolean sameEnchantment(Enchantment left, Enchantment right) {
        return left.equals(right) || left.getKey().equals(right.getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!isEnabled() || !event.willAttack()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack weapon = player.getInventory().getItemInMainHand();
        boolean isSword = weapon.getType().name().endsWith("_SWORD");
        Map<Enchantment, Float> gated = new HashMap<>();
        if (!swordsOnly || isSword) {
            for (Map.Entry<Enchantment, Float> entry : enchantMinCharge.entrySet()) {
                if (enchantmentLevel(weapon, entry.getKey()) > 0) {
                    gated.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Attack attack = new Attack(
                Bukkit.getCurrentTick(),
                event.getAttacked().getUniqueId(),
                player.getAttackCooldown(),
                gated
        );
        snapshotFreeze(attack, event.getAttacked());
        UUID id = player.getUniqueId();
        attacks.put(id, attack);
        Bukkit.getScheduler().runTask(plugin, () -> attacks.remove(id, attack));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageSnapshot(EntityDamageByEntityEvent event) {
        if (!isEnabled() || !(event.getDamager() instanceof Player player)) {
            return;
        }
        Attack attack = currentAttack(player);
        if (attack == null) {
            return;
        }
        snapshotFreeze(attack, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled() || !(event.getDamager() instanceof Player player)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        Attack attack = currentAttack(player);
        if (attack == null) {
            return;
        }
        UUID target = event.getEntity().getUniqueId();
        if (cause == DamageCause.ENTITY_ATTACK && !target.equals(attack.primaryTarget)) {
            return;
        }
        attack.damagedTargets.add(target);

        if (shouldBlockNonFireEffects(attack)) {
            Entity entity = event.getEntity();
            Bukkit.getScheduler().runTask(plugin, () -> revertFreeze(attack, entity));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (!isEnabled() || !(event.getCombuster() instanceof Player player)) {
            return;
        }
        Attack attack = currentAttack(player);
        if (attack == null || !attack.damagedTargets.contains(event.getEntity().getUniqueId())) {
            return;
        }

        Float minCharge = attack.gatedEnchants.get(Enchantment.FIRE_ASPECT);
        if (minCharge == null) {
            return;
        }
        if (!(attack.charge > minCharge)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (!isEnabled() || event.getCause() != EntityPotionEffectEvent.Cause.ATTACK) {
            return;
        }
        Attack attack = attackAffecting(event.getEntity().getUniqueId());
        if (attack != null && shouldBlockNonFireEffects(attack)) {
            event.setCancelled(true);
        }
    }

    private void snapshotFreeze(Attack attack, Entity entity) {
        attack.freezeTicksBefore.putIfAbsent(entity.getUniqueId(), entity.getFreezeTicks());
    }

    private void revertFreeze(Attack attack, Entity entity) {
        Integer before = attack.freezeTicksBefore.get(entity.getUniqueId());
        if (before != null && entity.getFreezeTicks() > before) {
            entity.setFreezeTicks(before);
        }
    }

    private boolean shouldBlockNonFireEffects(Attack attack) {
        for (Map.Entry<Enchantment, Float> entry : attack.gatedEnchants.entrySet()) {
            if (entry.getKey().equals(Enchantment.FIRE_ASPECT)) {
                continue;
            }
            if (!(attack.charge > entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private Attack currentAttack(Player player) {
        Attack attack = attacks.get(player.getUniqueId());
        return attack != null && attack.tick == Bukkit.getCurrentTick() ? attack : null;
    }

    private Attack attackAffecting(UUID targetId) {
        int tick = Bukkit.getCurrentTick();
        for (Attack attack : attacks.values()) {
            if (attack.tick == tick && attack.damagedTargets.contains(targetId)) {
                return attack;
            }
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attacks.remove(event.getPlayer().getUniqueId());
    }
}
