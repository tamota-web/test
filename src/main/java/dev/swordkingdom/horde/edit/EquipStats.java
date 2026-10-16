package dev.swordkingdom.horde.edit;

import dev.swordkingdom.horde.crowd.CrowdManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.Map;

/**
 * 「穿上装备之后这个单位有多少属性」—— 全部来自**原版 API**，一个模组名都不写。
 *
 * <h2>数据来源</h2>
 * {@code ItemStack.getAttributeModifiers(EquipmentSlot)}（原版玩家/怪物算属性用的就是它）：
 * <pre>
 *   头盔/胸甲/护腿/靴子 -> ARMOR、ARMOR_TOUGHNESS、KNOCKBACK_RESISTANCE，
 *                          以及模组物品自己加的任意属性
 *   主手/副手武器       -> ATTACK_DAMAGE、ATTACK_SPEED、ATTACK_KNOCKBACK …
 * </pre>
 * 于是「铁的 2 点护甲、钻石的 3 点韧性、下界合金剑的 8 点攻击」这些**不需要我们抄一遍**：
 * 物品自己就会报出来，模组物品也一样。
 *
 * <h2>设计成纯函数</h2>
 * {@link #accumulate} / {@link #fromStacks} / {@link Adjust#apply} 全都是
 * 「输入数组 -> 输出数值」的纯函数，不碰世界、不碰实体：
 * 所以离线 harness 里能直接构造 ItemStack 验证「护甲加了几点、攻击力加了几点」。
 *
 * <h2>叠加规则</h2>
 * 按原版语义分三种 ({@link AttributeModifier.Operation})：
 * <pre>
 *   ADDITION        先全部求和
 *   MULTIPLY_BASE   base = (base + addition) * (1 + ΣmultiplyBase)
 *   MULTIPLY_TOTAL  base = base * Π(1 + each)
 * </pre>
 * 我们的「基础值」是类型的实体属性（{@code TypeStats}），所以这三种都能算对。
 */
public final class EquipStats {

    /** 6 个槽一次算完的成果（最终数值，不是增量） */
    public static final class Adjust {
        public final float armor;
        public final float armorToughness;
        public final float knockbackResistance;
        public final float attackDamage;
        public final float attackSpeed;
        /** 攻击冷却（tick），由攻击速度换算，和类型属性用同一个公式 */
        public final int attackCooldownTicks;
        /** 移动速度倍率（装备上的 MULTIPLY_* 全部折进这一个数） */
        public final float moveSpeedScale;
        /** 附加的最大生命（装备给的生命加成） */
        public final float maxHealthBonus;
        /** 该单位「用什么打」 */
        public final EquipProfile.Kind weaponKind;
        /** 主手物品自己的攻击力（没有就是 0） */
        public final float weaponDamage;
        /** 是否有任何装备 */
        public final boolean anyEquipped;

        Adjust(float armor, float armorToughness, float knockbackResistance, float attackDamage,
               float attackSpeed, int attackCooldownTicks, float moveSpeedScale, float maxHealthBonus,
               EquipProfile.Kind weaponKind, float weaponDamage, boolean anyEquipped) {
            this.armor = armor;
            this.armorToughness = armorToughness;
            this.knockbackResistance = knockbackResistance;
            this.attackDamage = attackDamage;
            this.attackSpeed = attackSpeed;
            this.attackCooldownTicks = attackCooldownTicks;
            this.moveSpeedScale = moveSpeedScale;
            this.maxHealthBonus = maxHealthBonus;
            this.weaponKind = weaponKind;
            this.weaponDamage = weaponDamage;
            this.anyEquipped = anyEquipped;
        }

        /**
         * 把这份修正套到该类型的**基础**属性上，产出一份新的 {@code TypeStats}（25 参数那份构造器）。
         *
         * <h2>为什么用反射</h2>
         * {@code TypeStats} 的构造器是**包私有**的（它在 {@code crowd} 包里），而
         * {@code EquipStats} 在 {@code edit} 包里 —— 本任务的硬性契约要求
         * **不能修改 {@code CrowdManager.java}**，否则会和并行开发撞车。
         * 所以这里按「参数类型序列」反射找唯一的那个构造器并调用。
         * 一旦接线时在 {@code CrowdManager} 里加一个公开工厂
         * （见报告「接线说明」里给的那一行），这一段就可以换成直接构造，性能更好。
         *
         * 任何一步失败都**返回 base**（装备失效但不崩），并只记一次日志。
         */
        public CrowdManager.TypeStats apply(CrowdManager.TypeStats base, boolean ranged,
                                            float rangedDistance, float rangedRange,
                                            boolean hasConfiguredProjectiles, boolean hasAutoProjectile) {
            if (base == null) {
                return null;
            }
            java.lang.reflect.Constructor<CrowdManager.TypeStats> ctor = typeStatsCtor();
            if (ctor == null) {
                return base;
            }
            float width = base.width;
            float height = base.height;
            float meleeReach = (float) Math.sqrt(width * 2.0F * width * 2.0F + width);
            boolean isRanged = ranged || base.ranged();
            float distance = rangedRange >= 0.0F ? rangedRange
                    : Math.max(meleeReach, base.rangedDistance);
            try {
                Object stats = ctor.newInstance(
                        width, height, base.eyeHeight,
                        Math.max(1.0F, base.maxHp + maxHealthBonus),
                        attackDamage,
                        base.moveSpeed * moveSpeedScale,
                        base.followRange,
                        armor,
                        armorToughness,
                        Math.max(0.0F, Math.min(1.0F, knockbackResistance)),
                        attackCooldownTicks,
                        base.fromEntity,
                        base.actionCount,
                        base.skillDamageScale,
                        base.skillRadiusScale,
                        base.skillKnockback,
                        base.skillImpactFraction,
                        base.skillIntervalTicks,
                        base.actionOverride,
                        distance,
                        hasConfiguredProjectiles || base.hasConfiguredProjectiles,
                        hasAutoProjectile || base.hasAutoProjectile,
                        // 类型 id 必须一起带过去：TypeStats.ranged() 会用它按需重判门槛
                        // （UNKNOWN 不能被冻成近战，见 CrowdManager.TypeStats.ranged() 的注释）
                        base.casterId);
                return (CrowdManager.TypeStats) stats;
            } catch (Throwable t) {
                if (!warnedApply) {
                    warnedApply = true;
                    dev.swordkingdom.horde.HordeMod.LOGGER.warn(
                            "[horde] 无法构造带装备修正的属性（{}），该单位沿用类型基础属性："
                                    + "装备的数值加成不生效（渲染与行为判定不受影响）", t);
                }
                return base;
            }
        }

        private boolean warnedApply;

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "armor=%.2f tough=%.2f kb=%.3f dmg=%.2f atkSpeed=%.2f cd=%d moveScale=%.3f hp+%.1f weapon=%s",
                    armor, armorToughness, knockbackResistance, attackDamage, attackSpeed,
                    attackCooldownTicks, moveSpeedScale, maxHealthBonus, weaponKind);
        }
    }

    /**
     * {@code CrowdManager.TypeStats} 的「全参数」构造器（按**参数类型序列**找，不按名字 —— 线上是 SRG 名）。
     *
     * <p>两种形状都认：末位带 {@code ResourceLocation}（类型 id，用于运行期重判远程门槛）的那一版，
     * 以及老的不带 id 的 23 参版本。找到后缓存；找不到返回 null（调用方退回基础属性）。
     */
    private static java.lang.reflect.Constructor<CrowdManager.TypeStats> typeStatsCtorCache;
    private static boolean typeStatsCtorTried;

    private static java.lang.reflect.Constructor<CrowdManager.TypeStats> typeStatsCtor() {
        if (typeStatsCtorTried) {
            return typeStatsCtorCache;
        }
        typeStatsCtorTried = true;
        try {
            for (java.lang.reflect.Constructor<?> c : CrowdManager.TypeStats.class.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                boolean full = (p.length == 24 && p[23] == net.minecraft.resources.ResourceLocation.class)
                        || p.length == 23;
                if (!full
                        || p[0] != float.class || p[10] != int.class || p[11] != boolean.class
                        || p[12] != int.class || p[17] != int.class || p[18] != int.class
                        || p[21] != boolean.class || p[22] != boolean.class) {
                    continue;
                }
                c.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.lang.reflect.Constructor<CrowdManager.TypeStats> typed =
                        (java.lang.reflect.Constructor<CrowdManager.TypeStats>) c;
                typeStatsCtorCache = typed;
                return typed;
            }
        } catch (Throwable t) {
            typeStatsCtorCache = null;
        }
        return typeStatsCtorCache;
    }

    /** 累加器：把多个槽的 modifier 按三种操作折算到最终值上（零对象、可复用） */
    public static final class Accumulator {
        public double addition;
        public double multiplyBase;
        public double multiplyTotal = 1.0D;

        public void reset() {
            addition = 0.0D;
            multiplyBase = 0.0D;
            multiplyTotal = 1.0D;
        }

        /** 按原版语义把一次 modifier 折进来 */
        public void accept(AttributeModifier m) {
            if (m == null) {
                return;
            }
            double amount = m.getAmount();
            switch (m.getOperation()) {
                case ADDITION -> addition += amount;
                case MULTIPLY_BASE -> multiplyBase += amount;
                case MULTIPLY_TOTAL -> multiplyTotal *= (1.0D + amount);
                default -> addition += amount;
            }
        }

        /** 给定基础值，算出最终值 */
        public float value(float base) {
            return (float) (((base + addition) * (1.0D + multiplyBase)) * multiplyTotal);
        }
    }

    private EquipStats() {}

    /**
     * 离线可验证的入口：把修正套到基础属性上。
     *
     * <p>线上真正的接线点是 {@code CrowdManager}（逐单位重算属性），
     * 但那条路要改被冻结的文件。为了让本功能**在离线 harness 里也能端到端验证**
     * （「铁胸甲真的加了 6 点护甲」这种绝对值断言，而不是只看增量），
     * 这里提供一个公开的静态包装。
     *
     * <p><b>注意</b>：它依赖 {@code CrowdManager.TypeStats} 的包私有构造器
     * （通过反射调用），所以只有在类型表已经建立的前提下才有意义。
     * 拿不到构造器时返回 {@code base}（装备数值不生效，但绝不崩）。
     */
    public static CrowdManager.TypeStats applyForTest(CrowdManager.TypeStats base, Adjust adjust,
                                                      boolean ranged, float rangedDistance,
                                                      float rangedRange) {
        if (adjust == null) {
            return base;
        }
        return adjust.apply(base, ranged, rangedDistance, rangedRange, false, false);
    }

    /**
     * 从 6 个 {@link ItemStack} 算出全部修正。
     *
     * @param stacks        长度 6 的数组，顺序同 {@link EquipPalette#SLOT_NAMES}
     * @param attackSpeedOf 该类型的基础攻速（用来把装备的攻击速度折算成冷却）；&le; 0 时用 4.0
     */
    public static Adjust fromStacks(ItemStack[] stacks, float baseAttackDamage, float baseArmor,
                                    float baseArmorToughness, float baseKnockbackResistance,
                                    float baseMoveSpeed, float baseMaxHp, float attackSpeedOf,
                                    String projectileOverride) {
        Accumulator armor = new Accumulator();
        Accumulator toughness = new Accumulator();
        Accumulator knockback = new Accumulator();
        Accumulator damage = new Accumulator();
        Accumulator atkSpeed = new Accumulator();
        Accumulator moveSpeed = new Accumulator();
        Accumulator maxHp = new Accumulator();

        boolean any = false;
        if (stacks != null) {
            for (int s = 0; s < Math.min(stacks.length, EquipPalette.SLOT_COUNT); s++) {
                ItemStack stack = stacks[s];
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                any = true;
                EquipmentSlot slot = javaSlot(s);
                // 逐个槽取该槽的 modifier。模组物品的 Multimap 实现可能抛异常，
                // 所以整段包起来：单个物品算不出来不影响别的槽，也不会把整条链带崩。
                try {
                    Map<Attribute, Collection<AttributeModifier>> byAttr =
                            stack.getAttributeModifiers(slot).asMap();
                    for (Map.Entry<Attribute, Collection<AttributeModifier>> e : byAttr.entrySet()) {
                        accept(e.getKey(), e.getValue(), armor, toughness, knockback, damage,
                                atkSpeed, moveSpeed, maxHp);
                    }
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }

        // ---- 武器种类 + 主手自己的攻击力 ----
        EquipProfile.Params main = EquipProfile.of(slotStack(stacks, EquipPalette.SLOT_MAINHAND),
                "mainhand", projectileOverride);
        EquipProfile.Params off = EquipProfile.of(slotStack(stacks, EquipPalette.SLOT_OFFHAND),
                "offhand", projectileOverride);
        EquipProfile.Params dominant = EquipProfile.dominant(main, off);

        float attackSpeed = atkSpeed.value(attackSpeedOf > 0.0F ? attackSpeedOf : 4.0F);
        int cd = attackCooldownOf(attackSpeed);
        return new Adjust(
                Math.max(0.0F, armor.value(baseArmor)),
                Math.max(0.0F, toughness.value(baseArmorToughness)),
                Math.max(0.0F, knockback.value(baseKnockbackResistance)),
                Math.max(0.0F, damage.value(baseAttackDamage)),
                attackSpeed,
                cd,
                Math.max(0.05F, moveSpeed.value(baseMoveSpeed) / Math.max(0.05F, baseMoveSpeed)),
                Math.max(0.0F, maxHp.value(baseMaxHp) - baseMaxHp),
                dominant.kind,
                dominant.meleeDamage > 0.0F ? dominant.meleeDamage
                        : Math.max(0.0F, damage.value(baseAttackDamage)),
                any);
    }

    /** 单槽攻击力（{@link EquipProfile} 判定「普通物品也有攻击力」时用） */
    public static float attackDamageOf(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) {
            return 0.0F;
        }
        try {
            Map<Attribute, Collection<AttributeModifier>> byAttr =
                    stack.getAttributeModifiers(slot).asMap();
            Collection<AttributeModifier> mods = byAttr.get(Attributes.ATTACK_DAMAGE);
            if (mods == null) {
                return 0.0F;
            }
            Accumulator acc = new Accumulator();
            for (AttributeModifier m : mods) {
                acc.accept(m);
            }
            return Math.max(0.0F, acc.value(0.0F));
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /** 原版公式：冷却 = clamp(20 / 攻速, 2, 200)（与 {@code CrowdManager.TypeStats.of} 同一份换算） */
    public static int attackCooldownOf(float attackSpeed) {
        int cd = Math.round(20.0F / Math.max(0.1F, attackSpeed));
        return Math.max(2, Math.min(200, cd));
    }

    // ------------------------------------------------------------------

    private static void accept(Attribute attribute, Collection<AttributeModifier> mods,
                               Accumulator armor, Accumulator toughness, Accumulator knockback,
                               Accumulator damage, Accumulator atkSpeed, Accumulator moveSpeed,
                               Accumulator maxHp) {
        if (attribute == null || mods == null) {
            return;
        }
        for (AttributeModifier m : mods) {
            apply(attribute, m, armor, toughness, knockback, damage, atkSpeed, moveSpeed, maxHp);
        }
    }

    /**
     * 把一个 modifier 归类。
     *
     * <b>为什么按「attribute 是不是 {@code Attributes.X}」逐个比</b>：
     * 属性对象是注册表里的单例，{@code ==} 比较就是最快也最安全的做法
     * （不用 id 字符串，也不用碰可能没加载的注册表）。
     */
    private static void apply(Attribute attribute, AttributeModifier m, Accumulator armor,
                              Accumulator toughness, Accumulator knockback, Accumulator damage,
                              Accumulator atkSpeed, Accumulator moveSpeed, Accumulator maxHp) {
        if (m == null) {
            return;
        }
        Attribute a = attribute;
        if (a == null) {
            // 没有属性信息时按「名字像不像」兜底（有些模组物品的 Multimap 不带 key）
            String n = m.getName() == null ? "" : m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("armor_toughness")) {
                toughness.accept(m);
            } else if (n.contains("armor")) {
                armor.accept(m);
            } else if (n.contains("knockback_resistance")) {
                knockback.accept(m);
            } else if (n.contains("attack_damage")) {
                damage.accept(m);
            } else if (n.contains("attack_speed")) {
                atkSpeed.accept(m);
            } else if (n.contains("movement_speed")) {
                moveSpeed.accept(m);
            } else if (n.contains("max_health")) {
                maxHp.accept(m);
            }
            return;
        }
        if (a == Attributes.ARMOR) {
            armor.accept(m);
        } else if (a == Attributes.ARMOR_TOUGHNESS) {
            toughness.accept(m);
        } else if (a == Attributes.KNOCKBACK_RESISTANCE) {
            knockback.accept(m);
        } else if (a == Attributes.ATTACK_DAMAGE) {
            damage.accept(m);
        } else if (a == Attributes.ATTACK_SPEED) {
            atkSpeed.accept(m);
        } else if (a == Attributes.MOVEMENT_SPEED) {
            moveSpeed.accept(m);
        } else if (a == Attributes.MAX_HEALTH) {
            maxHp.accept(m);
        }
        // 其它属性（模组自定义的）暂不参与群集模拟，但也不报错
    }

    private static ItemStack slotStack(ItemStack[] stacks, int slot) {
        return stacks == null || slot >= stacks.length ? null : stacks[slot];
    }

    /** 槽下标 -> 原版 {@link EquipmentSlot}（顺序与 {@link EquipPalette#SLOT_NAMES} 一致） */
    public static EquipmentSlot javaSlot(int slot) {
        return switch (slot) {
            case EquipPalette.SLOT_HEAD -> EquipmentSlot.HEAD;
            case EquipPalette.SLOT_CHEST -> EquipmentSlot.CHEST;
            case EquipPalette.SLOT_LEGS -> EquipmentSlot.LEGS;
            case EquipPalette.SLOT_FEET -> EquipmentSlot.FEET;
            case EquipPalette.SLOT_OFFHAND -> EquipmentSlot.OFFHAND;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    /** 该物品 id（诊断输出用；注册表没就绪返回 "?"） */
    public static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        try {
            var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return rl == null ? "?" : rl.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
