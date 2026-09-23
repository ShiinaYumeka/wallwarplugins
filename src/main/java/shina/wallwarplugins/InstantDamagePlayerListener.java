package shina.wallwarplugins;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.papermc.paper.event.entity.EntityEffectTickEvent;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

public class InstantDamagePlayerListener implements Listener {

    private final Wallwarplugins plugin;
    private final Map<UUID, ArrowHit> harmingArrowHits = new HashMap<>();

    public InstantDamagePlayerListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    private static final class ArrowHit {
        private final int tick;
        private final Entity arrow;
        private final Entity shooter;

        private ArrowHit(int tick, Entity arrow, Entity shooter) {
            this.tick = tick;
            this.arrow = arrow;
            this.shooter = shooter;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        PotionMeta meta = potion.getPotionMeta();
        Integer level = getInstantDamageLevel(meta);
        if (level == null) {
            return;
        }

        DamageSource damageSource = magicSource(potion, potion.getShooter() instanceof Entity shooter ? shooter : null);
        double baseDamage = configuredDamage(level);

        for (LivingEntity entity : event.getAffectedEntities()) {
            if (!(entity instanceof Player)) {
                continue;
            }

            double intensity = event.getIntensity(entity);
            if (intensity <= 0.0) {
                continue;
            }

            event.setIntensity(entity, 0.0);
            double damage = baseDamage * intensity;
            if (damage > 0.0) {
                entity.damage(damage, damageSource);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        Integer level = getInstantDamageLevel(cloud.getBasePotionType());
        if (level == null) {
            for (PotionEffect effect : cloud.getCustomEffects()) {
                if (effect.getType() == PotionEffectType.INSTANT_DAMAGE) {
                    level = effect.getAmplifier() + 1;
                    break;
                }
            }
        }
        if (level == null) {
            return;
        }

        DamageSource damageSource = magicSource(cloud, (Entity) cloud.getSource());
        double damage = configuredDamage(level);

        event.getAffectedEntities().removeIf(entity -> {
            if (!(entity instanceof Player player)) {
                return false;
            }
            if (damage > 0.0) {
                player.damage(damage, damageSource);
            }
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarmingArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }

        Arrow arrow = findArrow(event);
        if (arrow == null || getInstantDamageLevel(arrow) == null) {
            return;
        }

        UUID id = player.getUniqueId();
        Entity shooter = arrow.getShooter() instanceof Entity entity ? entity : null;
        ArrowHit hit = new ArrowHit(Bukkit.getCurrentTick(), arrow, shooter);
        harmingArrowHits.put(id, hit);
        Bukkit.getScheduler().runTask(plugin, () -> harmingArrowHits.remove(id, hit));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInstantDamageTick(EntityEffectTickEvent event) {
        if (event.getType() != PotionEffectType.INSTANT_DAMAGE
                || !(event.getEntity() instanceof Player player)) {
            return;
        }

        ArrowHit hit = harmingArrowHits.get(player.getUniqueId());
        if (hit == null || hit.tick != Bukkit.getCurrentTick()) {
            return;
        }

        event.setCancelled(true);
        double damage = configuredDamage(event.getAmplifier() + 1);
        if (damage > 0.0) {
            player.damage(damage, magicSource(hit.arrow, hit.shooter));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrinkEffect(EntityPotionEffectEvent event) {
        if (event.getCause() != EntityPotionEffectEvent.Cause.POTION_DRINK
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        PotionEffect effect = event.getNewEffect();
        if (effect == null || effect.getType() != PotionEffectType.INSTANT_DAMAGE) {
            return;
        }

        event.setCancelled(true);
        double damage = configuredDamage(effect.getAmplifier() + 1);
        if (damage > 0.0) {
            player.damage(damage, magicSource(player, player));
        }
    }

    private double configuredDamage(int level) {
        return level >= 2
                ? plugin.getConfig().getDouble("instant-damage-player.damage-level-2", 7.0)
                : plugin.getConfig().getDouble("instant-damage-player.damage-level-1", 4.0);
    }

    private static Arrow findArrow(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Arrow arrow) {
            return arrow;
        }
        if (event.getDamageSource().getDirectEntity() instanceof Arrow arrow) {
            return arrow;
        }
        return null;
    }

    private static Integer getInstantDamageLevel(Arrow arrow) {
        Integer level = getInstantDamageLevel(arrow.getBasePotionType());
        if (level != null) {
            return level;
        }
        if (!arrow.hasCustomEffects()) {
            return null;
        }
        return arrow.getCustomEffects().stream()
                .filter(effect -> effect.getType() == PotionEffectType.INSTANT_DAMAGE)
                .map(effect -> effect.getAmplifier() + 1)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private static DamageSource magicSource(Entity direct, Entity causing) {
        DamageSource.Builder builder = DamageSource.builder(DamageType.MAGIC).withDirectEntity(direct);
        if (causing != null) {
            builder.withCausingEntity(causing);
        }
        return builder.build();
    }

    private static Integer getInstantDamageLevel(PotionMeta meta) {
        Integer level = getInstantDamageLevel(meta.getBasePotionType());
        if (level != null) {
            return level;
        }
        if (!meta.hasCustomEffects()) {
            return null;
        }
        return meta.getCustomEffects().stream()
                .filter(effect -> effect.getType() == PotionEffectType.INSTANT_DAMAGE)
                .map(effect -> effect.getAmplifier() + 1)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private static Integer getInstantDamageLevel(PotionType type) {
        if (type == PotionType.HARMING) {
            return 1;
        }
        if (type == PotionType.STRONG_HARMING) {
            return 2;
        }
        return null;
    }
}
