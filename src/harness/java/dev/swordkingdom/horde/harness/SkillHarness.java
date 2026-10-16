package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.action.SkillParams;
import net.minecraft.world.damagesource.CombatRules;

/**
 * 验证**技能结算**（{@link SkillParams}）：命中帧、范围、伤害与击退都从实体自身属性推出，
 * 不依赖任何具体模组。
 *
 * 关键点：
 *  - 命中帧落在动作进度内，且不会越界（动作太短也不会算不出来）；
 *  - 范围由「体型」与「近战距离」里较大者推算 —— 大 boss 天然范围更大；
 *  - 伤害走该实体自己的 ATTACK_DAMAGE，目标护甲用原版 {@link CombatRules} 减免；
 *  - 击退按目标击退抗性衰减，且只在范围内生效（边缘更弱）。
 */
public final class SkillHarness {

    private static int failures;

    public static void main(String[] args) {
        impactFrame();
        radiusFromBody();
        armorAndDamage();
        knockback();
        System.out.println(failures == 0 ? "\n== SKILL PASS ==" : "\n== SKILL FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void impactFrame() {
        // 24 tick 的动作、命中帧 50% -> 第 12 tick
        check(SkillParams.impactTick(24, 0.5F) == 12, "24 tick / 50% 的命中帧应为 12");
        check(SkillParams.impactTick(20, 0.6F) == 12, "20 tick / 60% 的命中帧应为 12");
        // 极短动作也要有一个合法命中帧
        for (int d = 1; d <= 8; d++) {
            int t = SkillParams.impactTick(d, 0.5F);
            check(t >= 1 && t <= d, d + " tick 的命中帧应在 [1," + d + "] 内（实际 " + t + "）");
        }
        // 比例异常时被夹住
        check(SkillParams.impactTick(40, -1.0F) >= 1, "非法比例应被夹到合法范围");
        check(SkillParams.impactTick(40, 99.0F) <= 40, "非法比例应被夹到合法范围");
        System.out.println("[ ok ] 命中帧：动作进度内的固定比例，且永远合法");
    }

    private static void radiusFromBody() {
        // 小怪（宽 0.6）与 boss（宽 4.0）的范围应随体型放大
        float small = SkillParams.radius(0.6F, 1.0F, 1.0F);
        float boss = SkillParams.radius(4.0F, 3.0F, 1.0F);
        check(small >= 1.0F, "小怪范围至少 1 格（实际 " + small + "）");
        check(boss > small * 2.0F, "boss 范围应明显大于小怪（" + boss + " vs " + small + "）");
        // 倍率线性生效
        float doubled = SkillParams.radius(4.0F, 3.0F, 2.0F);
        check(Math.abs(doubled - boss * 2.0F) < 1.0E-3F, "范围倍率应线性生效（" + doubled + " vs " + boss * 2 + "）");
        // 近战距离很大时以距离为准（长兵器/远程型 boss）
        float reachy = SkillParams.radius(0.5F, 6.0F, 1.0F);
        check(reachy > SkillParams.radius(0.5F, 1.0F, 1.0F), "近战距离更大时范围应更大");
        System.out.println("[ ok ] 范围：由体型/近战距离推算（小怪 " + small + " 格，boss " + boss + " 格）");
    }

    private static void armorAndDamage() {
        float raw = SkillParams.damage(8.0F, 1.0F);
        check(Math.abs(raw - 8.0F) < 1.0E-4F, "伤害基数应等于实体攻击力（实际 " + raw + "）");
        check(Math.abs(SkillParams.damage(8.0F, 1.5F) - 12.0F) < 1.0E-4F, "伤害倍率应线性生效");
        check(SkillParams.damage(-3.0F, 1.0F) == 0.0F, "负攻击力应夹到 0");

        // 无护甲：原样
        check(Math.abs(SkillParams.afterArmor(10.0F, 0.0F, 0.0F) - 10.0F) < 1.0E-4F, "无护甲应不减免");
        // 有护甲：与原版 CombatRules 完全一致，且一定低于原始伤害
        float withArmor = SkillParams.afterArmor(10.0F, 8.0F, 2.0F);
        float expected = CombatRules.getDamageAfterAbsorb(10.0F, 8.0F, 2.0F);
        check(Math.abs(withArmor - expected) < 1.0E-4F,
                "护甲减免应与原版 CombatRules 一致（" + withArmor + " vs " + expected + "）");
        check(withArmor < 10.0F, "有护甲时伤害应更低（" + withArmor + "）");
        System.out.println("[ ok ] 伤害：实体攻击力 × 倍率，目标护甲按原版公式减免（10 → " + withArmor + "）");
    }

    private static void knockback() {
        check(Math.abs(SkillParams.knockback(0.5F, 0.0F) - 0.5F) < 1.0E-4F, "无击退抗性应全额击退");
        check(SkillParams.knockback(0.5F, 1.0F) == 0.0F, "满击退抗性应完全免疫击退");
        check(Math.abs(SkillParams.knockback(0.5F, 0.6F) - 0.2F) < 1.0E-4F, "击退抗性应线性衰减");
        check(SkillParams.knockback(0.5F, 5.0F) == 0.0F, "异常抗性应被夹住");
        System.out.println("[ ok ] 击退：按目标击退抗性衰减，范围边缘更弱（在模拟里按距离线性衰减）");
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
