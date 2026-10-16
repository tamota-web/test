package dev.swordkingdom.horde.edit;

import dev.swordkingdom.horde.crowd.ProjectileCatalog;
import dev.swordkingdom.horde.crowd.ProjectileManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个 (实体类型, 动作编号) 的**投掷物技能**配置。
 *
 * 这是「技能也需要玩家可完全修改配置」这条需求的落点：
 * 施法者的第 k 个动作播到命中帧时，按这份配置生成一枚虚拟投掷物。
 *
 * 字段全部可以用「哨兵值」表示未覆盖（数值 -1 / 偏移 OFFSET_UNSET），
 * 于是「玩家没改」和「玩家改成 0」是两件事：
 * 没改就走该投掷物**按原版基类推导出来的默认参数**（见
 * {@link ProjectileCatalog#defaultsOf(ProjectileCatalog.Kind)}）。
 *
 * 用类而不是 Map&lt;String,Object&gt; 是为了让 Gson 直接读写、GUI 直接绑字段。
 */
public final class ProjectileSkill {

    /** 未覆盖的数值哨兵 */
    public static final float UNSET_F = -1.0F;
    /** 仰角/偏移这类允许负值的字段用这个哨兵 */
    public static final float UNSET_SIGNED = -9999.0F;

    /** 该动作对应的**发射器**类型（空 = 自动按命名空间/名字词干给施法者挑一枚） */
    public String projectile = "";
    /** true = 不发射（显式关掉这个动作的投掷物） */
    public boolean disabled = false;

    /** 初速（格/tick；未设 = 按基类默认） */
    public float speed = UNSET_F;
    /** 发射仰角（度，正 = 抬头发射；未设 = 按基类默认） */
    public float pitch = UNSET_SIGNED;
    /** 重力（每 tick；未设 = 按基类默认） */
    public float gravity = UNSET_F;
    /** 寿命（tick；未设 = 按基类默认） */
    public int life = TypeOverride.UNSET_I;
    /** 伤害倍率（伤害基数 = 施法者的 ATTACK_DAMAGE；未设 = 按基类默认） */
    public float damageScale = UNSET_F;
    /** 命中半径（格；未设 = 按基类默认） */
    public float hitRadius = UNSET_F;
    /** 可穿透次数（0 = 命中即消失；未设 = 按基类默认） */
    public int pierce = TypeOverride.UNSET_I;
    /** 命中后 AoE 半径（0 = 无；未设 = 按基类默认） */
    public float blastRadius = UNSET_F;
    /** AoE 伤害倍率（未设 = 按基类默认） */
    public float blastScale = UNSET_F;
    /** 命中击退强度（未设 = 用全局配置） */
    public float knockback = UNSET_F;
    /** 撞到方块是否卡住（-1 = 未设 / 0 = 消失 / 1 = 卡住） */
    public int stickOnBlock = TypeOverride.UNSET_I;
    /** 同一次动作一次发射几枚（扇形散射；<= 0 = 1 枚） */
    public int count = TypeOverride.UNSET_I;
    /** 多发时的散射角（度，未设 = 8） */
    public float spread = UNSET_F;
    /**
     * 发射用的物品（只有「物品投掷物」这种渲染器需要它来画出正确的模型，
     * 例如原版雪球/蛋的渲染器是从投射物身上的 ItemStack 取模型的）。
     * 空串 = 不设置（渲染器拿到的 ItemStack 会是空的，我们就退化成一个方块网格）。
     */
    public String renderItem = "";

    public ProjectileSkill copy() {
        ProjectileSkill s = new ProjectileSkill();
        s.projectile = projectile;
        s.disabled = disabled;
        s.speed = speed;
        s.pitch = pitch;
        s.gravity = gravity;
        s.life = life;
        s.damageScale = damageScale;
        s.hitRadius = hitRadius;
        s.pierce = pierce;
        s.blastRadius = blastRadius;
        s.blastScale = blastScale;
        s.knockback = knockback;
        s.stickOnBlock = stickOnBlock;
        s.count = count;
        s.spread = spread;
        s.renderItem = renderItem;
        return s;
    }

    public boolean isEmpty() {
        return (projectile == null || projectile.isEmpty()) && !disabled
                && speed == UNSET_F && pitch == UNSET_SIGNED && gravity == UNSET_F
                && life == TypeOverride.UNSET_I && damageScale == UNSET_F && hitRadius == UNSET_F
                && pierce == TypeOverride.UNSET_I && blastRadius == UNSET_F && blastScale == UNSET_F
                && knockback == UNSET_F && stickOnBlock == TypeOverride.UNSET_I
                && count == TypeOverride.UNSET_I && spread == UNSET_F
                && (renderItem == null || renderItem.isEmpty());
    }

    // ------------------------------------------------------------------
    // 给模拟层用的只读视图（crowd 包不依赖 edit 包）
    // ------------------------------------------------------------------

    /** 把这份配置包成 {@link ProjectileManager.SkillConfigView}（零分配：字段直接读）。 */
    public ProjectileManager.SkillConfigView view() {
        ProjectileSkill s = this;
        return new ProjectileManager.SkillConfigView() {
            @Override
            public float speed() {
                return s.speed;
            }

            @Override
            public float pitch() {
                return s.pitch;
            }

            @Override
            public boolean pitchSet() {
                return s.pitch != UNSET_SIGNED;
            }

            @Override
            public float gravity() {
                return s.gravity;
            }

            @Override
            public int life() {
                return s.life;
            }

            @Override
            public float hitRadius() {
                return s.hitRadius;
            }

            @Override
            public float damageScale() {
                return s.damageScale;
            }

            @Override
            public float blastRadius() {
                return s.blastRadius;
            }

            @Override
            public float blastScale() {
                return s.blastScale;
            }

            @Override
            public int pierce() {
                return s.pierce;
            }

            @Override
            public float knockback() {
                return s.knockback >= 0.0F ? s.knockback
                        : dev.swordkingdom.horde.HordeConfig.safeGet(
                        dev.swordkingdom.horde.HordeConfig.COMMON.projectileKnockback, 0.3F);
            }

            @Override
            public boolean stickOnBlock() {
                return s.stickOnBlock < 0
                        ? dev.swordkingdom.horde.HordeConfig.safeGet(
                        dev.swordkingdom.horde.HordeConfig.COMMON.projectileStickOnBlock, true)
                        : s.stickOnBlock != 0;
            }

            @Override
            public boolean stickOnBlockSet() {
                return s.stickOnBlock >= 0;
            }
        };
    }

    /** 实际要发射的投掷物 id；返回 null 表示「自动挑」 */
    public ResourceLocation projectileId() {
        if (projectile == null || projectile.isEmpty()) {
            return null;
        }
        return ResourceLocation.tryParse(projectile);
    }

    /** 一次发射几枚（至少 1） */
    public int shotCount() {
        return count <= 0 ? 1 : Math.min(64, count);
    }

    /** 散射角（度） */
    public float spreadDegrees() {
        return spread >= 0.0F ? spread : 8.0F;
    }
}
