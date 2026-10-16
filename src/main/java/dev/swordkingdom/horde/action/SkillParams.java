package dev.swordkingdom.horde.action;

import net.minecraft.world.damagesource.CombatRules;

/**
 * **通用技能结算参数** —— 与模组无关，只依赖实体自己的属性和几项配置。
 *
 * 群集单位不是真实实体，做不到执行 mod 自己的技能代码；这里走的是「**动作 = 技能**」的通用模型：
 *
 *  1. 单位选择自己的第 k 个动作（{@link ActionDriver} 发现的动画），动画照真实实体播放；
 *  2. 动作播到**命中帧**（{@link #impactTick}）时，在**群集模拟内部**结算一次范围效果：
 *     以该实体自己的 {@code ATTACK_DAMAGE} 为伤害、以它的体型/攻击距离推算出范围为半径，
 *     对范围内的敌人造成伤害（按目标的护甲走原版 {@link CombatRules#getDamageAfterAbsorb}）
 *     并施加一次击退（按目标的击退抗性衰减）。
 *
 * 于是「boss 的技能」在群集里就有了实际效果，且完全不需要知道那是哪个 mod 的哪个技能；
 * 想逐个技能微调时用配置里的倍率即可。
 */
public final class SkillParams {

    private SkillParams() {}

    /** 命中帧：动作播到这一 tick 时结算效果 */
    public static int impactTick(int durationTicks, float impactFraction) {
        int d = Math.max(1, durationTicks);
        float f = impactFraction;
        if (f < 0.05F) {
            f = 0.05F;
        }
        if (f > 0.95F) {
            f = 0.95F;
        }
        int t = Math.round(d * f);
        return Math.max(1, Math.min(d, t));
    }

    /**
     * 技能范围半径（格）。取「体型」与「近战距离」里较大的那个再乘倍率 ——
     * 大 boss 自然有更大的范围，不需要为它单独写数值。
     */
    public static float radius(float bbWidth, float attackReach, float scale) {
        float body = Math.max(0.1F, bbWidth) * 2.5F;
        float reach = Math.max(0.1F, attackReach) * 1.5F;
        float r = Math.max(body, reach) * Math.max(0.1F, scale);
        return Math.max(1.0F, r);
    }

    /** 技能伤害 */
    public static float damage(float attackDamage, float scale) {
        return Math.max(0.0F, attackDamage) * Math.max(0.0F, scale);
    }

    /** 目标的护甲减免（与原版近战结算一致） */
    public static float afterArmor(float damage, float armor, float armorToughness) {
        if (armor <= 0.0F && armorToughness <= 0.0F) {
            return damage;
        }
        return CombatRules.getDamageAfterAbsorb(damage, armor, armorToughness);
    }

    /** 击退强度：受击退抗性衰减后，转成本模拟里用的速度增量 */
    public static float knockback(float base, float knockbackResistance) {
        float resist = Math.min(1.0F, Math.max(0.0F, knockbackResistance));
        return Math.max(0.0F, base) * (1.0F - resist);
    }

    // ------------------------------------------------------------------
    // 虚拟投掷物：显式参数版本 + 与原版一致的积分/命中数学
    //
    // 这一节刻意做成**纯函数、无状态、不碰任何 Minecraft 对象**：
    //   - 服务端模拟（ProjectileManager）直接调它们；
    //   - 离线 harness（projectileHarness）用同样的函数跑纯数学验证。
    // 两边跑的是同一份代码，所以 harness 里验证过的积分/命中/寿命语义，
    // 在真实服务端上一定成立（除非方块碰撞或邻居查询本身出错，而那两项是可注入的回调）。
    // ------------------------------------------------------------------

    /** 目标护甲减免；护甲与韧性都为 0 时直接返回原值（省一次原版调用）。 */
    public static float afterArmorFor(float damage, float armor, float armorToughness) {
        if (armor <= 0.0F && armorToughness <= 0.0F) {
            return Math.max(0.0F, damage);
        }
        return afterArmor(damage, armor, armorToughness);
    }

    /**
     * 把技能配置换算成**这一次发射**的伤害。
     *
     * @param casterAttack 施法者自己的 ATTACK_DAMAGE（箭类的默认伤害就是它）
     * @param damageScale  玩家在 GUI 里配的伤害倍率
     */
    public static float projectileDamage(float casterAttack, float damageScale) {
        return Math.max(0.0F, casterAttack) * Math.max(0.0F, damageScale);
    }

    /**
     * 初速向量（沿朝向 + 仰角）。
     *
     * 用的是**和真实实体一样的角度约定**：
     * <pre>
     *   yaw = 0   -> 朝 +Z
     *   yaw = 90  -> 朝 -X        // 即 forward(yaw) = (-sin yaw, ·, cos yaw)
     * </pre>
     * 这与 {@code CrowdManager} 里 {@code aimHead}/{@code rotlerp} 的
     * {@code atan2(-dx, dz)} 是同一套约定，也与原版 {@code Projectile.shootFromRotation} 一致
     * （原版那个公式的负号就来自这里）：
     * <pre>
     *   x = -sin(yaw) * cos(xRot)
     *   y = -sin(xRot)
     *   z =  cos(yaw) * cos(xRot)
     * </pre>
     * 注意原版这里用的是**实体 xRot**（正 = 低头），而 GUI 里玩家填的是「仰角」，
     * 所以这里先用 -pitch 转成原版 xRot 再套同一个公式。
     *
     * @param out 长度 &gt;= 3 的输出缓冲（零分配）
     */
    public static void launchVelocity(float yawDegrees, float pitchDegrees, float speed, float[] out) {
        double yaw = Math.toRadians(yawDegrees);
        double xRot = Math.toRadians(-pitchDegrees);
        double xd = -Math.sin(yaw) * Math.cos(xRot);
        double yd = -Math.sin(xRot);
        double zd = Math.cos(yaw) * Math.cos(xRot);
        // 归一化再乘速度：原版 shootFromRotation 也是先取单位向量
        double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
        if (len < 1.0E-9D) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = (float) speed;
            return;
        }
        out[0] = (float) (xd / len * speed);
        out[1] = (float) (yd / len * speed);
        out[2] = (float) (zd / len * speed);
    }

    /**
     * 一步积分：先施加重力，再乘空气阻力。
     *
     * 对应原版 {@code Projectile.tick} 里对 {@code deltaMovement} 的处理：
     * 箭是 {@code (d - 0.05) * 0.99}（水面下另算），火球是只加速不减速（drag = 1）。
     * 顺序很重要：先重力后阻力，否则重力会被阻力额外衰减一次。
     *
     * @param v   长度 >= 3 的速度（原地修改）
     * @param out 长度 >= 3 的输出缓冲
     */
    public static void integrate(float[] v, float gravity, float drag, float[] out) {
        float d = Math.max(0.0F, Math.min(1.0F, drag));
        out[0] = v[0] * d;
        out[1] = (v[1] - gravity) * d;
        out[2] = v[2] * d;
    }

    /**
     * 命中判定：**线段扫掠**（上一 tick 位置 -> 当前 tick 位置）到球心的最短距离。
     *
     * 为什么不能只用「当前位置到目标的距离」：箭一秒飞 60 格，每 tick 位移 3 格，
     * 而单位宽 0.6 格 —— 只测点的话箭会**穿人而过**。原版也是用
     * {@code ProjectileUtil.getEntityHitResult} 做射线/扫掠检测，这里用等价的点到线段距离。
     *
     * @return 是否命中
     */
    public static boolean sweepHit(float prevX, float prevY, float prevZ,
                                   float curX, float curY, float curZ,
                                   float tx, float ty, float tz, float radiusSq) {
        float dx = curX - prevX;
        float dy = curY - prevY;
        float dz = curZ - prevZ;
        float fx = prevX - tx;
        float fy = prevY - ty;
        float fz = prevZ - tz;
        float len2 = dx * dx + dy * dy + dz * dz;
        float t = 0.0F;
        if (len2 > 1.0E-9F) {
            t = -(fx * dx + fy * dy + fz * dz) / len2;
            if (t < 0.0F) {
                t = 0.0F;
            } else if (t > 1.0F) {
                t = 1.0F;
            }
        }
        float cx = fx + dx * t;
        float cy = fy + dy * t;
        float cz = fz + dz * t;
        return cx * cx + cy * cy + cz * cz <= radiusSq;
    }

    /**
     * AoE 伤害衰减 —— 与原版 {@code Explosion} / {@code ExplosionDamageCalculator} 同形：
     * 距离越远伤害越低，到边缘恰好降到 0（所以边缘不会「差一点也吃满伤害」）。
     *
     * @param radius AoE 半径；<= 0 时返回 0
     */
    public static float blastFalloff(float damage, float distance, float radius) {
        if (radius <= 0.0F) {
            return 0.0F;
        }
        float f = Math.max(0.0F, 1.0F - distance / radius);
        return Math.max(0.0F, damage) * f;
    }

    /** 把 1/16 格的部件空间 UV 归一化 —— 与原版 baked model 的 UV 约定一致。 */
    public static float normalizeUv(float uv) {
        return uv > 1.5F ? uv / 16.0F : uv;
    }
}
