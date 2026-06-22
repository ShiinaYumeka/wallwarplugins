package shina.wallwarplugins;

import org.bukkit.Tag;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

public class InstantHealUndeadListener implements Listener {

    private final Wallwarplugins plugin;

    public InstantHealUndeadListener(Wallwarplugins plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        PotionMeta meta = potion.getPotionMeta();

        // 1. 判断是否为瞬间治疗药水
        if (!isInstantHealPotion(meta)) {
            return;
        }

        // 2. 获取基础伤害与配置的效果参数
        int healLevel = getInstantHealLevel(meta);
        double baseDamage = healLevel >= 2
                ? plugin.getConfig().getDouble("instant-heal-undead.damage-level-2", 12.0)
                : plugin.getConfig().getDouble("instant-heal-undead.damage-level-1", 6.0);

        int slownessDuration = plugin.getConfig().getInt("instant-heal-undead.slowness.duration-ticks", 100);
        int slownessAmplifier = plugin.getConfig().getInt("instant-heal-undead.slowness.amplifier", 3);
        int weaknessDuration = plugin.getConfig().getInt("instant-heal-undead.weakness.duration-ticks", 100);
        int weaknessAmplifier = plugin.getConfig().getInt("instant-heal-undead.weakness.amplifier", 1);

        // 3. 构建伤害来源（获取投掷者用于击杀统计）
        Entity causingEntity = potion.getShooter() instanceof Entity shooter ? shooter : null;
        DamageSource.Builder damageSourceBuilder = DamageSource.builder(DamageType.MAGIC)
                .withDirectEntity(potion);
        if (causingEntity != null) {
            damageSourceBuilder.withCausingEntity(causingEntity);
        }
        DamageSource damageSource = damageSourceBuilder.build();

        // 4. 遍历受影响的实体
        for (LivingEntity entity : event.getAffectedEntities()) {
            // 仅对亡灵生物生效
            if (!Tag.ENTITY_TYPES_INVERTED_HEALING_AND_HARM.isTagged(entity.getType())) {
                continue;
            }

            double intensity = event.getIntensity(entity);
            if (intensity <= 0.0) {
                continue;
            }

            // 5. 取消原版瞬间治疗对亡灵的伤害
            event.setIntensity(entity, 0.0);

            // 6. 造成自定义魔法伤害（天然无视保护附魔）
            double damage = baseDamage * intensity;
            if (damage > 0.0) {
                entity.damage(damage, damageSource);
            }

            // 7. 施加缓慢和虚弱效果（根据距离/强度缩放持续时间）
            int actualSlownessDuration = Math.max(1, (int) Math.round(slownessDuration * intensity));
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    actualSlownessDuration,
                    slownessAmplifier
            ));

            int actualWeaknessDuration = Math.max(1, (int) Math.round(weaknessDuration * intensity));
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.WEAKNESS,
                    actualWeaknessDuration,
                    weaknessAmplifier
            ));
        }
    }

    /**
     * 判断药水是否为瞬间治疗
     */
    private boolean isInstantHealPotion(PotionMeta meta) {
        PotionType type = meta.getBasePotionType();
        if (type == PotionType.HEALING || type == PotionType.STRONG_HEALING) {
            return true;
        }
        if (!meta.hasCustomEffects()) {
            return false;
        }
        return meta.getCustomEffects().stream()
                .anyMatch(effect -> effect.getType() == PotionEffectType.INSTANT_HEALTH);
    }

    /**
     * 获取瞬间治疗等级
     */
    private int getInstantHealLevel(PotionMeta meta) {
        PotionType type = meta.getBasePotionType();
        if (type == PotionType.STRONG_HEALING) {
            return 2;
        }
        if (type == PotionType.HEALING) {
            return 1;
        }
        if (!meta.hasCustomEffects()) {
            return 1;
        }
        return meta.getCustomEffects().stream()
                .filter(effect -> effect.getType() == PotionEffectType.INSTANT_HEALTH)
                .mapToInt(effect -> effect.getAmplifier() + 1)
                .max()
                .orElse(1);
    }
}