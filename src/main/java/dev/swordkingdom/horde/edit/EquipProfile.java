package dev.swordkingdom.horde.edit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import java.util.Locale;

/**
 * 「这件物品意味着怎么打」—— 用**原版物品类**判定，一个模组名都不写。
 *
 * <h2>为什么按类判</h2>
 * {@code BowItem / CrossbowItem / SwordItem / AxeItem / TridentItem / ShieldItem}
 * 都是**原版**类。任何模组想做一把能射的弓、一把能砍的剑，都得继承它们
 * （或者至少在 {@code getAttributeModifiers} 上给出攻击力），所以这条判据天生通用。
 * 反过来，按「名字里有没有 bow」猜会漏掉 {@code cataclysm:ignis_bow} 之外的怪名字。
 *
 * <h2>输出是什么</h2>
 * {@link Kind}（枚举）+ {@link Params}（参数）。模拟层只看这两个东西：
 * <pre>
 *   RANGED   射：投掷物用 {@code minecraft:arrow}（可由 GUI/配置覆盖），
 *            初速/寿命沿用投掷物管理器的默认值
 *   MELEE    砍：用该物品自己的攻击力（{@code getAttributeModifiers(MAINHAND)} 里的
 *            {@code ATTACK_DAMAGE}，与原版玩家/怪物完全同一份数据）
 *   SHIELD   挡：减伤 + 举盾期间不再攻击（能做的先做这两件，见交付说明里的近似清单）
 *   NONE     只加属性，不改行为
 * </pre>
 *
 * <h2>关于附魔与弩的连发</h2>
 * 群集模拟**不跑原版 {@code Item#use}**（单位不是真实实体），所以：
 *   - 附魔（力量、多重射击、快速装填……）不参与弹道；
 *   - 弩与弓的差别只体现在「攻击间隔」上（弩更慢、单发更重）。
 * 这两条都写进了交付说明，不是遗漏。
 */
public final class EquipProfile {

    public enum Kind {
        /** 不改变行为，只加属性 */
        NONE,
        /** 远程：弓 / 弩 */
        RANGED,
        /** 近战：剑 / 斧 / 三叉戟，以及任何带攻击力加成的物品 */
        MELEE,
        /** 盾：减伤 + 不再攻击 */
        SHIELD;

        public int priority() {
            return switch (this) {
                case RANGED -> 400;
                case MELEE -> 300;
                case SHIELD -> 200;
                case NONE -> 0;
            };
        }
    }

    /** 判定结果 */
    public static final class Params {
        public final Kind kind;
        /** 远程投掷物 id（{@link Kind#RANGED} 才有意义） */
        public final ResourceLocation projectile;
        /** 该物品自己的近战攻击力（{@link Kind#MELEE} 才有意义；0 = 物品没给数据） */
        public final float meleeDamage;
        /** 攻击间隔（tick）。弓 20、弩 32、近战 由攻速属性决定时填 0 = 沿用类型属性 */
        public final int intervalTicks;
        /** 是否「双手持用」（三叉戟/弩这类需要两只手的武器，副手盾牌时不能同时用） */
        public final boolean twoHanded;
        /** 人类可读的原因（日志/报告用） */
        public final String reason;

        Params(Kind kind, ResourceLocation projectile, float meleeDamage, int intervalTicks,
               boolean twoHanded, String reason) {
            this.kind = kind;
            this.projectile = projectile;
            this.meleeDamage = meleeDamage;
            this.intervalTicks = intervalTicks;
            this.twoHanded = twoHanded;
            this.reason = reason;
        }

        public boolean blocking() {
            return kind == Kind.SHIELD;
        }

        @Override
        public String toString() {
            return kind + (reason.isEmpty() ? "" : "（" + reason + "）");
        }
    }

    /** 什么都没装 */
    public static final Params NONE = new Params(Kind.NONE, null, 0.0F, 0, false, "");

    /** 默认远程弹（可由 GUI/配置覆盖；原版箭是最通用的「射出去的东西」） */
    public static final ResourceLocation DEFAULT_ARROW = ResourceLocation.tryParse("minecraft:arrow");

    private EquipProfile() {}

    /**
     * 判定某个槽里的物品意味着怎么打。
     *
     * @param stack     该槽的物品（空 -> {@link #NONE}）
     * @param slotName  槽位名（只有 {@code mainhand}/{@code offhand} 参与行为判定）
     * @param override  投掷物覆盖（GUI/配置里指定的「这件远程武器射什么」；null/空 = 用默认箭）
     */
    public static Params of(ItemStack stack, String slotName, String override) {
        if (stack == null || stack.isEmpty()) {
            return NONE;
        }
        if (slotName != null && !slotName.equalsIgnoreCase("mainhand") && !slotName.equalsIgnoreCase("offhand")) {
            // 护甲/其它槽不决定攻击方式
            return NONE;
        }
        Item item = stack.getItem();
        ResourceLocation projectile = parse(override);
        if (projectile == null) {
            projectile = DEFAULT_ARROW;
        }
        try {
            // ---- 远程：弓 / 弩（原版类，任何模组的弓都继承它们）----
            if (item instanceof net.minecraft.world.item.BowItem) {
                return new Params(Kind.RANGED, projectile, 0.0F, 20, true, "BowItem");
            }
            if (item instanceof net.minecraft.world.item.CrossbowItem) {
                return new Params(Kind.RANGED, projectile, 0.0F, 32, true, "CrossbowItem");
            }
            // ---- 近战 ----
            // 注意：这里读攻击力的方式**按原版 API 各取所需**，不抄数字：
            //   SwordItem   -> TieredItem.getDamage()（int，剑自己的攻击力）
            //   DiggerItem  -> getAttackDamage()（float，斧头/锹/镐）
            //   TridentItem -> 没有 getter，走它自己的属性表（同一份被玩家用的数据）
            if (item instanceof SwordItem sword) {
                return new Params(Kind.MELEE, null, sword.getDamage(), 0, false, "SwordItem");
            }
            if (item instanceof AxeItem axe) {
                return new Params(Kind.MELEE, null, axe.getAttackDamage(), 0, false, "AxeItem");
            }
            if (item instanceof TridentItem) {
                float dmg = EquipStats.attackDamageOf(stack,
                        net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                return new Params(Kind.MELEE, null, dmg, 0, true, "TridentItem");
            }
            // ---- 盾 ----
            if (item instanceof ShieldItem) {
                return new Params(Kind.SHIELD, null, 0.0F, 0, false, "ShieldItem");
            }
        } catch (Throwable t) {
            // 物品类构造/取值异常（极端情况下模组物品会抛）：退化成「只加属性」
            return new Params(Kind.NONE, null, 0.0F, 0, false, "判定异常 " + t.getClass().getSimpleName());
        }
        // ---- 其它物品：看它自己有没有给攻击力加成 ----
        float dmg = EquipStats.attackDamageOf(stack, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (dmg > 0.0F) {
            return new Params(Kind.MELEE, null, dmg, 0, false, "带攻击力加成");
        }
        return new Params(Kind.NONE, null, 0.0F, 0, false, "");
    }

    /** 便捷重载：不带覆盖 */
    public static Params of(ItemStack stack, String slotName) {
        return of(stack, slotName, null);
    }

    private static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return ResourceLocation.tryParse(id.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在一组装备里挑「决定战斗方式」的那一件。
     *
     * 优先级：主手 > 副手。主手是空的而副手是盾时，结果是 SHIELD（举盾不攻击）。
     * 这就是「右手拿弓、左手拿盾」这类组合的落点 —— 报告里写明了这条映射。
     */
    public static Params dominant(Params mainHand, Params offHand) {
        if (mainHand != null && mainHand.kind != Kind.NONE) {
            return mainHand;
        }
        if (offHand != null && offHand.kind != Kind.NONE) {
            return offHand;
        }
        return NONE;
    }
}
