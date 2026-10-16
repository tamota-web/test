package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeLog;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.HordeProfiler;
import dev.swordkingdom.horde.action.SkillParams;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群集管理器 —— 一个维度一份，负责该维度所有群集单位的模拟。
 *
 * 物理与动画严格对齐原版（数值取自 1.20.1 反编译源码）：
 *   - 移动：Mob.setSpeed(v) 同时把 zza 设为 v，因此每 tick 加速量 = v²，v = speedModifier × 0.23
 *   - 摩擦：地面 ×(0.6×0.91)，空中 ×0.91；重力 0.08；垂直阻尼 0.98
 *   - 碰撞：Entity.collideWithShapes 的逐轴顺序 + maxUpStep 0.6 台阶
 *   - 跳跃：jumpFromGround 的 0.42 初速
 *   - 动画：WalkAnimationState + AnimationUtils.animateZombieArms
 */
public final class CrowdManager {

    public static final int MAX_TEAMS = 4;
    /** 同时可用的实体类型上限（快照里用 5 bit 编码） */
    public static final int MAX_TYPES = 32;

    /** 原版僵尸 MOVEMENT_SPEED 属性值 */
    private static final float ZOMBIE_ATTRIBUTE_SPEED = 0.23F;
    /** 原版重力 */
    private static final float GRAVITY = 0.08F;
    /** 原版地面方块摩擦（多数方块为 0.6） */
    private static final float BLOCK_FRICTION = 0.6F;
    /** 原版实体宽度 / 高度 */
    private static final float WIDTH = 0.6F;
    private static final float HEIGHT = 1.95F;
    private static final float HALF_W = WIDTH * 0.5F;
    /** 供同步层还原「实体中心」用 */
    public static final float HALF_WIDTH = WIDTH * 0.5F;
    /** 原版 getCurrentSwingDuration 默认值 */
    public static final int SWING_DURATION = 6;
    /** 原版 LivingEntity.hurtDuration 默认值 */
    public static final int HURT_DURATION = 10;
    /** 挥动速度倍率：WalkAnimationState.update(f, 0.4F) */
    private static final float WALK_LERP = 0.4F;
    /** 索敌间隔（tick），错峰执行 */
    private static final int RETARGET_INTERVAL = 10;
    /**
     * 战斗阶段兜底索敌的间隔（tick）。
     *
     * 用同一个数量级（10 tick）而不是每 tick：一个「找不到目标」的单位每 tick 重扫一次
     * 半径 16 格的 17×17 个格子必然是白扫（数据没变、结论也不会变），
     * 而那正是「远程单位一多，服务器就开始 ticks behind」的主要来源之一。
     * 节流后行为不变：aiTick 每 10 tick 已经做过同样的查询并写了 targetId。
     */
    private static final int COMBAT_SCAN_INTERVAL = 10;
    /** 投掷物扫掠命中时每 tick 最多检查多少个候选（防止极端情况下一次扫太多格子） */
    private static final int MAX_CANDIDATES = 64;
    /** 索敌时单方向最多扫多少格（远程距离很大时的兜底上限） */
    private static final int MAX_ENEMY_SEARCH_CELLS = 24;
    /** 行走相位换算：cos(limbSwing * 0.6662F) */
    public static final float WALK_PHASE_SCALE = 0.6662F;
    /** 相位回绕周期，保证 cos 连续 */
    public static final float WALK_WRAP = (float) (Math.PI * 2.0 / WALK_PHASE_SCALE);

    private final ResourceKey<Level> dimension;
    private final CrowdData data;
    private final SpatialGrid grid;
    private final FlowField[] flow = new FlowField[MAX_TEAMS];

    private BlockWorld blockWorld;

    /**
     * 供投掷物共用同一份方块缓存（见 {@link ProjectileManagers#blocks}）。
     *
     * 共用而不是各建一份：两者每 tick 都要反复查同一批方块，
     * 共用同一张哈希表能把「真正 getBlockState 的次数」摊薄到接近被踩到的不同方块数。
     */
    public BlockWorld blockWorld() {
        return blockWorld;
    }

    /**
     * 直接注入方块世界（**只给离线 harness 用**）。
     *
     * <p>为什么需要：视线判定（远程单位「有视线才放箭」）只在有方块世界时才会真的走
     * {@link BlockWorld#hasLineOfSight}，而离线没有 ServerLevel。注入之后，
     * 「每单位每 tick 的射线采样次数」这条真机主因嫌疑就能在离线被计量与对比。
     */
    public void setBlockWorldForHarness(BlockWorld world) {
        this.blockWorld = world;
    }

    /** 维度引用（只用于读取实体注册属性） */
    private ServerLevel level;

    private int teamCount = 1;

    /** 已登记的实体类型表（索引与 CrowdData.typeIndex 对应） */
    private final List<ResourceLocation> typeIds = new ArrayList<>();
    private final Map<ResourceLocation, Integer> typeIndexMap = new HashMap<>();
    /** 与 typeIds 平行的类型属性表 */
    private final List<TypeStats> typeStats = new ArrayList<>();

    /**
     * 逐槽位的类型属性覆盖（**只给离线 harness 用**）。
     *
     * 为什么需要：harness 没有注册表，拿不到真实的实体属性；而这个表正好也能服务
     * 「将来做一个不读注册表的纯数据单位」。为 null 时（线上）一切照旧走 typeStats。
     */
    private TypeStats[] statsOverride;

    /** 最近发生的伤害结算记录（**只给离线 harness 用**：用来证明「同一次攻击只结算一次」） */
    private final StringBuilder damageEvents = new StringBuilder();

    /** 每个实体类型的属性（默认从该实体的注册属性读取，与真实实体一致） */
    public static final class TypeStats {
        public final float width;
        public final float height;
        public final float halfWidth;
        public final float eyeHeight;
        public final float maxHp;
        public final float damage;
        public final float moveSpeed;
        public final float followRange;
        public final float armor;
        public final float armorToughness;
        public final float knockbackResistance;
        public final int attackCooldownTicks;
        /** 近战判定距离：原版 Mob#getMeleeAttackRangeSqr 推出来的值（贴脸才算近战） */
        public final float meleeReach;
        /**
         * 攻击判定距离。
         *
         * <p><b>它只用于「索敌半径 / 目标保持」，绝不用来给近战伤害把关</b> ——
         * 近战能不能打到由 {@link #meleeRangeWith} 按**双方碰撞箱**现算（见那里的注释）。
         *
         * <p>为什么以前会「手太长」：远程类型这里取的是
         * {@code max(meleeReach, rangedDistance)} = 16 格，而这个值既当索敌半径用、
         * 又被近战分支当判定距离用 —— 于是远程类型（以及登记时算远程、运行期判近战的类型）
         * 能在十几格外结算近战。
         */
        public final float attackReach;
        /**
         * **纯弹道射程**（格）：由该类型那枚投掷物的初速/重力/寿命/阻力推（见 {@link RangedKiting}）。
         *
         * <p>远程单位用它回答「站在原地能不能打到」：目标在这个距离内 + 有视线 ⇒ **原地开火**，
         * 不产生任何朝目标的位移。近战类型为 0。
         */
        volatile float ballisticRange;
        /** 该类型的动作/技能动画数量（0 = 该实体没有动画状态 API） */
        public final int actionCount;
        /**
         * 玩家在技能页签里给这个类型配过投掷物（{@code TypeOverride.hasProjectileSkills()}）。
         *
         * 注意这是**覆盖表里是否有条目**，不是「最终能不能发射」：
         * 条目存在但被设成「不发射」时这里仍然是 true，真正发射时会打一条 WARN。
         */
        public final boolean hasConfiguredProjectiles;
        /**
         * 自动发现为这个类型解析出了投掷物 —— <b>但必须同时过门槛</b>。
         *
         * <p>真机事故③：上一版这里是「{@code ProjectileCatalog.resolve(...).found()}」，
         * 也就是**只打分、不问门槛**，于是末影龙（既不实现 {@code RangedAttackMob}/{@code CrossbowAttackMob}，
         * 也没 new 过任何候选弹）被打分挑中了 {@code minecraft:arrow} 并被登记成「远程」。
         * 现在它等于「{@link ProjectileCatalog#autoAttack} 有返回值」＝ 先门槛、后打分。
         *
         * <p>给离线 harness 看的时候注意：这个字段是**登记那一刻**的快照，
         * 「现在算不算远程」要问 {@link #ranged()}（它会按需重判，UNKNOWN 不会被冻成近战）。
         */
        public final boolean hasAutoProjectile;
        /**
         * 该类型的实体 id（门槛重判要用它；离线构造时可能为 null）。
         *
         * 为什么要存它：门槛结论有第三种状态 {@code UNKNOWN}（实体类/候选池/字节码还没就绪）。
         * 类型登记往往发生在注册表就绪之前，那时若把「判不出来」写成「近战」并存进 final 字段，
         * 骷髅就永远不会射箭了。所以远程判定做成 {@link #ranged()} 按需重判。
         */
        public final ResourceLocation casterId;
        /**
         * 登记那一刻算出来的「是不是远程单位」（= 玩家配了投掷物 或 门禁通过的自动解析挑到了弹）。
         *
         * <p><b>运行期请用 {@link #ranged()}</b>，别直接读这个字段：它是快照，
         * 在「登记时门槛还是 UNKNOWN」的情况下会偏保守（false），而 UNKNOWN 之后可能变成 RANGED。
         */
        public final boolean rangedAtRegistration;
        /** 远程攻击距离（格） */
        public final float rangedDistance;
        /** 远程攻击的节流（两次发射之间的 tick） */
        public final int rangedInterval;
        /**
         * **距离保持（kiting）规则** —— 理想站位由该类型那枚投掷物的弹道参数推导
         * （见 {@link RangedKiting}）。非远程单位为 null。
         *
         * <p>刻意不是 final：① 覆盖表（{@code applyOverride}）会改射程，射程一改规则就得重算；
         * ② 门槛结论要等运行期才确定（登记时可能还是 UNKNOWN），那时才补建。
         */
        volatile RangedKiting.Rule kiting;
        // ---- 攻击/技能参数（覆盖表优先，否则用配置）----
        public final float skillDamageScale;
        public final float skillRadiusScale;
        public final float skillKnockback;
        public final float skillImpactFraction;
        public final int skillIntervalTicks;
        /** >= 0 时固定播这个动作（0 = 不播），-1 = 自动轮换 */
        public final int actionOverride;
        /** 是否成功读到了实体属性（否则用的是配置兜底值） */
        public final boolean fromEntity;

        TypeStats(float width, float height, float eyeHeight, float maxHp, float damage,
                  float moveSpeed, float followRange, float armor, float armorToughness,
                  float knockbackResistance, int attackCooldownTicks, boolean fromEntity) {
            this(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange, armor, armorToughness,
                    knockbackResistance, attackCooldownTicks, fromEntity, 0);
        }

        TypeStats(float width, float height, float eyeHeight, float maxHp, float damage,
                  float moveSpeed, float followRange, float armor, float armorToughness,
                  float knockbackResistance, int attackCooldownTicks, boolean fromEntity,
                  int actionCount) {
            this(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange, armor, armorToughness,
                    knockbackResistance, attackCooldownTicks, fromEntity, actionCount,
                    HordeConfig.safeGet(HordeConfig.COMMON.actionDamageScale, 1.0F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionRadiusScale, 1.0F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionKnockback, 0.35F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionImpactFraction, 0.5F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionIntervalTicks, 120), -1, -1.0F, false, false, null);
        }

        TypeStats(float width, float height, float eyeHeight, float maxHp, float damage,
                  float moveSpeed, float followRange, float armor, float armorToughness,
                  float knockbackResistance, int attackCooldownTicks, boolean fromEntity,
                  int actionCount, float skillDamageScale, float skillRadiusScale,
                  float skillKnockback, float skillImpactFraction, int skillIntervalTicks,
                  int actionOverride) {
            this(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange, armor, armorToughness,
                    knockbackResistance, attackCooldownTicks, fromEntity, actionCount,
                    skillDamageScale, skillRadiusScale, skillKnockback, skillImpactFraction,
                    skillIntervalTicks, actionOverride, -1.0F, false, false, null);
        }

        TypeStats(float width, float height, float eyeHeight, float maxHp, float damage,
                  float moveSpeed, float followRange, float armor, float armorToughness,
                  float knockbackResistance, int attackCooldownTicks, boolean fromEntity,
                  int actionCount, float skillDamageScale, float skillRadiusScale,
                  float skillKnockback, float skillImpactFraction, int skillIntervalTicks,
                  int actionOverride, float rangedRange, boolean hasConfiguredProjectiles,
                  boolean hasAutoProjectile, ResourceLocation casterId) {
            this.actionCount = actionCount;
            this.skillDamageScale = skillDamageScale;
            this.skillRadiusScale = skillRadiusScale;
            this.skillKnockback = skillKnockback;
            this.skillImpactFraction = skillImpactFraction;
            this.skillIntervalTicks = skillIntervalTicks;
            this.actionOverride = actionOverride;
            this.width = width;
            this.height = height;
            this.halfWidth = width * 0.5F;
            this.eyeHeight = eyeHeight;
            this.maxHp = maxHp;
            this.damage = damage;
            this.moveSpeed = moveSpeed;
            this.followRange = followRange;
            this.armor = armor;
            this.armorToughness = armorToughness;
            this.knockbackResistance = knockbackResistance;
            this.attackCooldownTicks = attackCooldownTicks;
            // 原版 Mob#getMeleeAttackRangeSqr 的判定距离，换算成半径
            this.meleeReach = (float) Math.sqrt(width * 2.0F * width * 2.0F + width);
            this.fromEntity = fromEntity;
            // ---- 远程攻击模式（见 ProjectileCombat）----
            this.hasConfiguredProjectiles = hasConfiguredProjectiles;
            this.hasAutoProjectile = hasAutoProjectile;
            this.casterId = casterId;
            this.rangedAtRegistration = hasConfiguredProjectiles || hasAutoProjectile;
            float cfgRanged = HordeConfig.safeGet(HordeConfig.COMMON.projectileRangedDistance, 16.0F);
            // -1 = 没配：用全局配置（GUI 的覆盖项正是用 -1 表示「未设置」）
            this.rangedDistance = rangedRange >= 0.0F ? rangedRange : cfgRanged;
            // 攻击判定距离：远程单位要够到远程距离（否则「攻击」这件事永远不触发），
            // 近战单位维持原来的近战距离。
            // ★ 它现在**只用于索敌/目标保持**；近战伤害能不能落下由 meleeRangeWith 现算，
            //   所以这里取多大都不会再变成「手太长」。
            this.attackReach = rangedAtRegistration ? Math.max(this.meleeReach, this.rangedDistance) : this.meleeReach;
            int cfgInterval = HordeConfig.safeGet(HordeConfig.COMMON.projectileRangedIntervalTicks, 40);
            this.rangedInterval = cfgInterval > 0 ? cfgInterval : Math.max(1, skillIntervalTicks);
            // 距离保持规则 + 纯弹道射程：只有「登记时就算远程」的类型才建
            //（近战单位不站桩，一个字段都没变）
            RangedKiting.Rule r = rangedAtRegistration
                    ? buildKiting(casterId, this.rangedDistance, this.meleeReach, this.followRange,
                            this.eyeHeight, this.height)
                    : null;
            this.kiting = r;
            this.ballisticRange = r == null ? 0.0F : r.ballisticRange;
        }

        /**
         * 近战可达判定：**由双方碰撞箱推导**（水平外扩，再要求垂直方向有重叠）。
         *
         * <h2>公式</h2>
         * <pre>
         *   水平：AABB 之间的水平间隙
         *         gapX = max(0, |dx| - (myHalfWidth + targetHalfWidth))
         *         gapZ = max(0, |dz| - (myHalfWidth + targetHalfWidth))
         *         gap  = hypot(gapX, gapZ)
         *   可达：gap &lt;= reachSlack  且  垂直方向两个碰撞箱有重叠
         *         reachSlack = 0.5 · (我的宽度 + 目标宽度)   ← 原版 getMeleeAttackRangeSqr 的口径
         * </pre>
         * 等价地：{@code 水平中心距 <= 半宽和 + reachSlack}。
         * 对原版僵尸（0.6 宽）打原版僵尸：{@code 0.3+0.3 + 0.5·(0.6+0.6) = 1.2 格}；
         * 打末影龙（16 宽）：{@code 0.3+8 + 0.5·(0.6+16) = 16.6 格}（大体型天然更好打）。
         *
         * <p>为什么不再用 {@link #attackReach} 或 {@code getMeleeAttackRangeSqr}：
         * 那两个都把「索敌半径」（followRange 档位）与「能不能打到」搅在一起，
         * 于是远程类型（attackReach = 16）与登记后改判的类型都能在十几格外结算近战。
         * 现在索敌可以很宽，**结算必须过这一关**。
         *
         * @param direct 双方朝向无关的保守近似：碰撞箱按世界轴对齐（群集单位没有精确包围盒旋转）
         */
        /**
         * 近战可达距离的**数值**（格，水平中心距口径）——离线用例断言与日志用。
         *
         * <pre>
         *   近战可达 = (攻击者宽度 + 目标宽度) / 2 + 0.5 · (攻击者宽度 + 目标宽度)
         *            = 水平中心距的上界（垂直方向还要有碰撞箱重叠）
         * </pre>
         * 对原版僵尸（0.6 宽）打原版僵尸：{@code 0.6 + 0.6 = 1.2 格}；
         * 打末影龙（16 宽）：{@code (0.6+16)/2 + 0.5·(0.6+16) = 16.6 格}（大体型天然更好打）。
         */
        public static float meleeRangeValue(float attackerWidth, float targetWidth) {
            float aw = Math.max(0.05F, attackerWidth);
            float tw = Math.max(0.05F, targetWidth);
            return (aw + tw) * 0.5F + 0.5F * (aw + tw);
        }

        /**
         * 距离保持规则的取值（非远程/拿不到弹药参数时返回 null = 不做距离保持）。
         *
         * <p>**只读**：物理步每个单位每 tick 都会问一次，所以规则在类型登记时算好、这里只是取值。
         */
        public RangedKiting.Rule kitingRule() {
            return kiting;
        }

        /**
         * 由「这个类型实际会发射的那枚弹」推导站位规则。
         *
         * <p>弹的来源顺序（与发射链一致）：{@link ProjectileCatalog#autoAttackCached}（先门槛后打分的
         * 唯一入口）→ 拿不到就退回箭类默认值。**不查注册表、不登记投掷物类型**：
         * 这里只读 {@link ProjectileManager#defaultsFor}（纯参数解析）。
         *
         * <p>为什么必须按弹道推：直射箭可以站在 14 格外，雪球抛物严重必须靠近 ——
         * 一个拍死的魔数没法同时照顾这两种攻击模式（见 {@link RangedKiting} 的实测三档）。
         */
        static RangedKiting.Rule buildKiting(ResourceLocation casterId, float rangedRange,
                                             float meleeReach, float followRange,
                                             float shooterEye, float selfHeight) {
            ProjectileCatalog.Kind kind = ProjectileCatalog.Kind.ARROW;
            boolean resolved = false;
            if (casterId != null) {
                try {
                    ProjectileCatalog.Candidate c = ProjectileCatalog.autoAttackCached(casterId);
                    if (c != null) {
                        kind = c.kind;
                        resolved = true;
                    }
                } catch (Throwable ignored) {
                    // 门槛/候选池还没就绪：退回箭类默认值推（不影响「是不是远程」这个判定）
                }
            }
            // 候选的实体类在候选池建立时就抓下来了（ProjectileCatalog.CLASS_CACHE）；拿不到就按基类默认值。
            Class<?> projClass = ProjectileCatalog.entityClassOf(
                    resolved ? casterId : null, kind);
            ProjectileCatalog.Kind byClass = projClass == null ? kind
                    : ProjectileCatalog.kindOfClass(projClass);
            if (byClass != ProjectileCatalog.Kind.NONE) {
                kind = byClass;
            }
            ProjectileCatalog.Defaults def = ProjectileCatalog.defaultsOf(kind);
            // 下垂预算由**双方体型**推。这里没有「对手」这个概念（规则是按类型算的），
            // 于是用「同体型对手」近似（攻击者眼高 + 本类型高度）：
            // 它只影响站位带宽，不影响「够不够得着」那条硬线（后者走 ballisticRange，
            // 在 decide 里完全独立于预算）。
            float budget = RangedKiting.dropBudgetFor(shooterEye, selfHeight);
            return RangedKiting.rule(def.speed, def.gravity, def.drag, def.life,
                    kind, rangedRange, meleeReach, followRange, budget);
        }

        /**
         * 「现在算不算远程单位」—— 每次问都走同一条链（先门槛、后打分）。
         *
         * <p>为什么不是简单返回一个 final 布尔：门槛有 {@code UNKNOWN} 这一档
         * （实体类/候选池/字节码还没就绪），而类型登记常常发生在注册表就绪之前。
         * 把 UNKNOWN 冻成「近战」正是「骷髅不射箭」那种故障的又一次翻版，
         * 所以这里按需重判（{@link ProjectileCatalog#rangedGate} 只缓存**确定**的结论）。
         *
         * <p>玩家在 GUI 里显式配了投掷物 -> 一律远程，且**不看门槛**（玩家意图最高）。
         */
        public boolean ranged() {
            rangedCalls++;
            if (hasConfiguredProjectiles) {
                return true;
            }
            if (casterId == null) {
                // 离线/自定义构造的 TypeStats：没有实体 id，只能用登记时的快照
                return rangedAtRegistration;
            }
            // ★ **类型级记忆**：这个字段一算出来就不再变（实体类/字节码/候选池在运行期不会变），
            //   于是这里只剩一次 volatile 读。
            //
            //   真机病根：本方法**每个单位每 tick 被问 3 次**（aiTick / resolveCombat / skillTick），
            //   而它的实现链是 hasAutoAttack -> autoAttack -> rangedGate -> casterClassOf
            //   -> entityClassOf（注册表查询）+ 遍历整个候选池打分。
            //   100 个单位 ⇒ 每 tick 几百次「扫候选池 + 注册表查询 + 构造实体」，
            //   卡顿行里 ai/combat/skill 三处各约 500ms 就是这么来的。
            //   延迟到**第一次真的被问**时才计算，是为了保住原有语义：
            //   类型登记常发生在注册表就绪之前，那时算出来的「近战」会被永久冻住
            //   （正是「骷髅不射箭」那个老坑）。缓存的是**问的那一刻**的结论，
            //   而 ProjectileCatalog 的门槛/结果缓存本身只写「确定」的结论、UNKNOWN 不写，
            //   所以这个记忆值与逐次重算**完全等价**，只是不再重复做同样的工作。
            Boolean memo = rangedMemo;
            if (memo != null && rangedMemoEnabled) {
                rangedMemoHits++;
                return memo;
            }
            boolean v = ProjectileCatalog.autoAttackCached(casterId) != null;
            rangedMemo = v;
            ensureRangedRuntime(v);
            if (v) {
                logKitingOnce(this);
            }
            return v;
        }

        /**
         * 让「运行期的远程结论」与三个派生量保持一致：
         * {@link #kiting}（站位规则）、{@link #ballisticRange}（弹道射程）、{@link #attackReach}（索敌半径）。
         *
         * <p>为什么必须在这里对上：门槛结论是**按需重判**的（登记时可能还是 UNKNOWN），
         * 而派生量在构造器里只能按登记快照算。上一版就出现过
         * 「登记时算远程 → attackReach = 16 格 → 运行期判成近战 → 仍然拿 16 格当近战距离」
         * 这种自相矛盾（这正是「手太长」的一条真实来源）。
         *
         * <p>现在无论结论怎么翻，都只影响**索敌半径**（可以宽），
         * 近战能不能打到一律由 {@code meleeRangeOk} 按双方碰撞箱现算 —— 所以这里对齐只是为了
         * 「远程类型能被正确发现」，不再有「手有多长」的副作用。
         */
        private void ensureRangedRuntime(boolean ranged) {
            if (ranged) {
                if (kiting == null) {
                    // 「玩家配过投掷物 → 不看门槛」或「登记时还是 UNKNOWN」：现在补建一次
                    kiting = buildKiting(casterId, rangedDistance, meleeReach, followRange,
                            eyeHeight, height);
                }
                ballisticRange = kiting == null ? 0.0F : kiting.ballisticRange;
            } else {
                ballisticRange = 0.0F;
            }
        }

        /**
         * **限频**输出一次「攻击模式：距离保持 …」（每个类型只打一次）。
         *
         * <p>为什么不逐发打：逐发日志在 20 个骷髅时就是每秒 20 条 INFO，在一万单位规模下是
         * 唯一会线性放大的外部 IO（逐发详情已降级到 {@code debug.logLaunchDetail}，默认关）。
         * 这一行是**攻击模式本身**的说明，一个类型只需要一次 —— 真机排查时一眼就能看到
         * 「这个远程单位打算站多远、多久放一次、有没有提前量」。
         */
        private static void logKitingOnce(TypeStats stats) {
            if (stats.casterId == null || stats.kiting == null) {
                return;
            }
            if (!KITING_LOGGED.add(stats.casterId)) {
                return;
            }
            String mode = String.format(java.util.Locale.ROOT,
                    "类型 %s -> %s", stats.casterId, stats.kiting.describe());
            try {
                HordeMod.LOGGER.info("[horde] {}", mode);
            } catch (Throwable ignored) {
                // 日志绝不该影响模拟（离线 harness 里 LogUtils 可能抛异常）
            }
        }

        /** 「已经打过攻击模式那一行」的类型集合（每个类型只打一次） */
        private static final java.util.Set<ResourceLocation> KITING_LOGGED =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * 只给离线对照实验用：关掉「类型级记忆」= 复现「每次询问都重走门槛+打分」的旧行为。
         * 线上永远是 true。
         */
        public static volatile boolean rangedMemoEnabled = true;

        /** 类型级「是不是远程」的记忆（null = 还没算过） */
        private volatile Boolean rangedMemo;

        /**
         * 诊断：{@link #ranged()} 被问过几次（进程内累计）。
         *
         * <p>为什么值得单独计数：它**每个单位每 tick 至少被问一次**（aiTick / resolveCombat /
         * skillTick 三处各一次），而它的真实实现会走到
         * {@code ProjectileCatalog.hasAutoAttack -> rangedGate}。
         * 门槛结论按类型缓存（RANGED/MELEE），但 **UNKNOWN 刻意不缓存** ——
         * 于是「注册表里查不到这个类型」时，每一次询问都会重做一遍
         * 「取实体类 + 读字节码 + 扫候选池」，在 100 单位规模下就是每 tick 几百次重活。
         */
        public static long rangedCalls;
        /** 其中命中「类型级记忆」的次数（命中 = 一次 volatile 读，不再走门槛/打分） */
        public static long rangedMemoHits;

        /**
         * 造一个自定义属性的类型描述（**离线验证专用**的公共构造入口）。
         *
         * 为什么不给测试留一个「测试专用构造器」：{@link TypeStats} 的字段全部是 final 的语义，
         * 与其复制一份构造逻辑，不如把这个入口做成生产代码也能用的
         * 「用一组显式数值描述一个类型」（将来做「模板类型/召唤物」时同样需要它）。
         */
        public static TypeStats custom(float width, float height, float eyeHeight,
                                       float maxHp, float damage, float moveSpeed, float followRange,
                                       int attackCooldownTicks, int actionCount,
                                       int skillIntervalTicks,
                                       float rangedRange, boolean hasConfiguredProjectiles,
                                       boolean hasAutoProjectile) {
            return custom(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange,
                    attackCooldownTicks, actionCount, skillIntervalTicks, rangedRange,
                    hasConfiguredProjectiles, hasAutoProjectile, null);
        }

        /**
         * 同上，并显式给出该类型的实体 id（远程门槛重判要用它）。
         *
         * <p>离线用例要验「先门槛、后打分」时，`casterId` 必须传 —— 不传的话
         * {@link #ranged()} 只能用登记时的快照，那样就验不到运行期重判这条性质了。
         */
        public static TypeStats custom(float width, float height, float eyeHeight,
                                       float maxHp, float damage, float moveSpeed, float followRange,
                                       int attackCooldownTicks, int actionCount,
                                       int skillIntervalTicks,
                                       float rangedRange, boolean hasConfiguredProjectiles,
                                       boolean hasAutoProjectile, ResourceLocation casterId) {
            return new TypeStats(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange,
                    0.0F, 0.0F, 0.0F, attackCooldownTicks, true, actionCount,
                    1.0F, 1.0F, 0.35F, 0.5F, skillIntervalTicks, -1,
                    rangedRange, hasConfiguredProjectiles, hasAutoProjectile, casterId);
        }

        /**
         * 同上，并给出护甲/韧性。
         *
         * <p>为什么需要：投掷物伤害必须走**目标自己的护甲**（{@code CombatRules.getDamageAfterAbsorb}）。
         * 离线用例要验证「箭打有甲单位时伤害变低」就得能摆出一个有甲的目标，
         * 而这个构造入口在生产里同样成立（它就是「用一组显式数值描述一个类型」）。
         */
        public static TypeStats custom(float width, float height, float eyeHeight,
                                       float maxHp, float damage, float moveSpeed, float followRange,
                                       float armor, float armorToughness, float knockbackResistance,
                                       int attackCooldownTicks, int actionCount,
                                       int skillIntervalTicks,
                                       float rangedRange, boolean hasConfiguredProjectiles,
                                       boolean hasAutoProjectile, ResourceLocation casterId) {
            return new TypeStats(width, height, eyeHeight, maxHp, damage, moveSpeed, followRange,
                    armor, armorToughness, knockbackResistance, attackCooldownTicks, true, actionCount,
                    1.0F, 1.0F, 0.35F, 0.5F, skillIntervalTicks, -1,
                    rangedRange, hasConfiguredProjectiles, hasAutoProjectile, casterId);
        }

        /** 从实体类型自身读取真实属性（尺寸/速度/生命/攻击/护甲/索敌/攻速）；读不到就用配置兜底 */
        static TypeStats of(ServerLevel level, ResourceLocation id) {
            float cfgHp = HordeConfig.safeGet(HordeConfig.COMMON.unitMaxHp, 20.0F);
            float cfgDmg = HordeConfig.safeGet(HordeConfig.COMMON.unitDamage, 3.0F);
            float cfgFollow = HordeConfig.safeGet(HordeConfig.COMMON.followRange, 16.0F);
            int cfgCd = HordeConfig.safeGet(HordeConfig.COMMON.attackCooldownTicks, 18);
            int cfgSkillInterval = HordeConfig.safeGet(HordeConfig.COMMON.actionIntervalTicks, 120);
            // 兜底值也走安全读取：离线 harness 里配置没加载，用 safeGet 才能让整条链（含远程）
            // 跑的是**同一份代码**，而不是「一进 harness 就抛异常掉进另一条路」。
            TypeStats fallback = new TypeStats(
                    CrowdManager.WIDTH, CrowdManager.HEIGHT, 1.6F,
                    cfgHp, cfgDmg, CrowdManager.ZOMBIE_ATTRIBUTE_SPEED, cfgFollow,
                    0.0F, 0.0F, 0.0F, cfgCd, false, 0,
                    HordeConfig.safeGet(HordeConfig.COMMON.actionDamageScale, 1.0F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionRadiusScale, 1.0F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionKnockback, 0.35F),
                    HordeConfig.safeGet(HordeConfig.COMMON.actionImpactFraction, 0.5F),
                    cfgSkillInterval, -1,
                    -1.0F, projectileOverrideOf(id), autoAttackFor(id), id);
            try {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
                if (type == null) {
                    return fallback;
                }
                Entity probe = type.create(level);
                if (!(probe instanceof LivingEntity living)) {
                    return fallback;
                }
                float atkSpeed = attr(living, Attributes.ATTACK_SPEED, 4.0F);
                int cd = Math.round(20.0F / Math.max(0.1F, atkSpeed));
                cd = Math.max(2, Math.min(200, cd));
                TypeStats stats = new TypeStats(
                        Math.max(0.05F, living.getBbWidth()),
                        Math.max(0.05F, living.getBbHeight()),
                        Math.max(0.05F, living.getEyeHeight()),
                        attr(living, Attributes.MAX_HEALTH, cfgHp),
                        attr(living, Attributes.ATTACK_DAMAGE, cfgDmg),
                        attr(living, Attributes.MOVEMENT_SPEED, CrowdManager.ZOMBIE_ATTRIBUTE_SPEED),
                        attr(living, Attributes.FOLLOW_RANGE, cfgFollow),
                        attr(living, Attributes.ARMOR, 0.0F),
                        attr(living, Attributes.ARMOR_TOUGHNESS, 0.0F),
                        attr(living, Attributes.KNOCKBACK_RESISTANCE, 0.0F),
                        cd, true,
                        // 动作/技能动画：按「形状」发现动画状态 API（对任何模组通用）
                        // 注意顺序：12=fromEntity(true)、13=actionCount、14..19=技能参数、20=远程距离
                        actionCountOf(living),
                        // 远程单位判定：玩家在技能页签里配过投掷物 -> 必远程；
                        // 否则按「原版基类 + 命名空间/词干」自动挑一枚 ——
                        // 原版骷髅走的就是这条自动路，它一个动画 API 都没有，照样是远程单位。
                        HordeConfig.safeGet(HordeConfig.COMMON.actionDamageScale, 1.0F),
                        HordeConfig.safeGet(HordeConfig.COMMON.actionRadiusScale, 1.0F),
                        HordeConfig.safeGet(HordeConfig.COMMON.actionKnockback, 0.35F),
                        HordeConfig.safeGet(HordeConfig.COMMON.actionImpactFraction, 0.5F),
                        HordeConfig.safeGet(HordeConfig.COMMON.actionIntervalTicks, 120),
                        -1,
                        -1.0F,
                        projectileOverrideOf(id),
                        autoAttackFor(id), id);
                probe.discard();
                return applyOverride(id, stats);
            } catch (Throwable t) {
                // 读不到实体属性（离线 harness / 注册表未就绪）：用配置兜底值 + 覆盖表。
                // 注意这里连「动作数」都不知道，所以兜底 TypeStats 的 actionCount = 0 ——
                // 远程发射**不依赖**它（这正是本次修掉的门槛）。
                return applyOverride(id, fallback);
            }
        }

        /** 玩家在技能页签里给这个类型配过投掷物（不判「最终能不能发射」，那要在发射点才看得到） */
        private static boolean projectileOverrideOf(ResourceLocation id) {
            try {
                dev.swordkingdom.horde.edit.TypeOverride ov =
                        dev.swordkingdom.horde.edit.TypeOverride.get(id);
                return ov != null && ov.hasProjectileSkills();
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 这个类型「有没有被自动配上一枚投掷物」—— <b>先门槛、后打分</b>。
         *
         * <h2>真机事故③：这里曾经只打分、不问门槛</h2>
         * 上一版这里是 {@code ProjectileCatalog.resolve(ns, path).found()}，也就是「候选池里有没有
         * 打分最高的那一枚」。打分对**任何**类型都能挑出一枚（同命名空间 1000 + 基类 350 …），
         * 于是末影龙被判成「远程」并射箭：
         * <pre>
         *   [horde] 为 minecraft:ender_dragon 自动选中的投掷物：minecraft:arrow
         *           （161 个候选里最高 1050 分：同命名空间 1000 + 词干 0 + 基类 350 - 外来实体名 300 = 1050）
         *   [horde] 类型 minecraft:ender_dragon -&gt; 攻击模式 远程（距离 16.00 格，间隔 40 tick）
         * </pre>
         * 而它既不实现 {@code RangedAttackMob}/{@code CrossbowAttackMob}，也没在任何字节码里 new 过候选弹。
         * 现在一律走 {@link ProjectileCatalog#autoAttack}（门槛在前、打分在后，唯一实现），
         * 于是「登记时算远程、发射时算近战」这种自相矛盾不可能再出现。
         */
        private static boolean autoAttackFor(ResourceLocation id) {
            if (id == null) {
                return false;
            }
            try {
                return ProjectileCatalog.hasAutoAttack(id);
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 把玩家在游戏内配的**覆盖**套到该类型的属性/参数上（没配的字段保持实体自己的值）。
         * 于是「改属性/改攻击」立刻生效，不需要重新生成群集单位。
         */
        private static TypeStats applyOverride(ResourceLocation id, TypeStats base) {
            dev.swordkingdom.horde.edit.TypeOverride ov =
                    dev.swordkingdom.horde.edit.TypeOverride.get(id);
            if (ov == null || ov.isEmpty()) {
                return base;
            }
            float width = pick(ov.width, base.width);
            TypeStats overridden = new TypeStats(
                    width,
                    pick(ov.height, base.height),
                    pick(ov.eyeHeight, base.eyeHeight),
                    pick(ov.maxHp, base.maxHp),
                    pick(ov.damage, base.damage),
                    pick(ov.moveSpeed, base.moveSpeed),
                    pick(ov.followRange, base.followRange),
                    pick(ov.armor, base.armor),
                    pick(ov.armorToughness, base.armorToughness),
                    pick(ov.knockbackResistance, base.knockbackResistance),
                    ov.attackCooldownTicks >= 0 ? ov.attackCooldownTicks : base.attackCooldownTicks,
                    base.fromEntity,
                    base.actionCount,
                    pick(ov.damageScale, base.skillDamageScale),
                    pick(ov.radiusScale, base.skillRadiusScale),
                    pick(ov.knockbackScale, base.skillKnockback),
                    clamp01Or(ov.impactFraction, base.skillImpactFraction),
                    ov.intervalTicks >= 0 ? ov.intervalTicks : base.skillIntervalTicks,
                    ov.action,
                    // 远程：距离可被覆盖；「配过/自动挑到」两项取 base 与覆盖表的并集
                    ov.rangedRange,
                    base.hasConfiguredProjectiles || ov.hasProjectileSkills(),
                    base.hasAutoProjectile,
                    base.casterId);
            // 射程/属性被覆盖之后，站位规则必须**一起重算**：
            // 覆盖表把射程从 16 改成 8 时，理想站位要跟着变小，
            // 否则「距离保持」会逼着单位站在自己射程之外（永远射不出去）。
            if (overridden.rangedAtRegistration) {
                overridden.kiting = buildKiting(base.casterId, overridden.rangedDistance,
                        overridden.meleeReach, overridden.followRange,
                        overridden.eyeHeight, overridden.height);
                overridden.ballisticRange = overridden.kiting == null ? 0.0F : overridden.kiting.ballisticRange;
            }
            return overridden;
        }

        private static float pick(float override, float fallback) {
            return override >= 0.0F ? override : fallback;
        }

        private static float clamp01Or(float override, float fallback) {
            if (override < 0.0F) {
                return fallback;
            }
            return Math.max(0.05F, Math.min(0.95F, override));
        }

        /** 该实体的可用动作动画数（没有这套 API 就是 0） */
        private static int actionCountOf(LivingEntity entity) {
            try {
                dev.swordkingdom.horde.action.ActionDriver driver =
                        dev.swordkingdom.horde.action.ActionDriver.of(entity.getClass());
                return driver == null ? 0 : driver.actionCount();
            } catch (Throwable t) {
                return 0;
            }
        }

        private static float attr(LivingEntity entity, Attribute attribute, float fallback) {
            try {
                AttributeInstance instance = entity.getAttribute(attribute);
                return instance == null ? fallback : (float) instance.getValue();
            } catch (Throwable t) {
                return fallback;
            }
        }
    }

    private final float[] sampleOut = new float[2];
    /** 碰撞分离用的每槽位半宽/质量（按需扩容） */
    private float[] sepHalfWidth = new float[0];
    private float[] sepMass = new float[0];
    private final double[] flowSumX = new double[MAX_TEAMS];
    private final double[] flowSumZ = new double[MAX_TEAMS];
    private final int[] flowCnt = new int[MAX_TEAMS];

    private final float[] box = new float[6];
    private final float[] moveDelta = new float[3];
    private final boolean[] moveFlags = new boolean[2];

    private int[] deathScratch;
    private int deathCount;

    // ------------------------------------------------------------------
    // 距离保持（kiting）的观测量 —— **只做统计，不参与任何判定**
    //
    // 为什么要有它们：真机上「远程单位冲上去近战」这件事必须能被量化，
    // 否则改完之后只能说「看起来好像好了」。这里记录的是每一 tick 的实际站位：
    //   * 远程单位与目标的最小距离（贴脸的话这个值会掉到近战距离以内）；
    //   * 有多少 tick 目标已经进了近战距离（真实实体在这一刻会挨打）；
    //   * 有多少 tick 在真正后撤；
    //   * 目标进入「最小开火距离」以内的次数（正常应当有，因为对方也会冲过来）。
    // ------------------------------------------------------------------
    /** 观测：远程单位与最近敌人的最小水平距离（格），未观测到为 {@link Float#MAX_VALUE} */
    private float kitingMinDistance = Float.MAX_VALUE;
    /** 观测：目标进入近战距离以内的 tick 次数 */
    private long kitingMeleeTicks;
    /** 观测：正在执行后撤的 tick 次数 */
    private long kitingRetreatTicks;
    /** 观测：目标进入「最小开火距离」以内的 tick 次数 */
    private long kitingTooCloseTicks;
    /** 观测：处于驻留（HOLD，站着射）的 tick 次数 */
    private long kitingHoldTicks;

    /** 清空距离保持的观测量（离线用例在每段实验前调用） */
    public void resetKitingStats() {
        kitingMinDistance = Float.MAX_VALUE;
        kitingMeleeTicks = 0L;
        kitingRetreatTicks = 0L;
        kitingTooCloseTicks = 0L;
        kitingHoldTicks = 0L;
    }

    public float kitingMinDistance() {
        return kitingMinDistance;
    }

    public long kitingMeleeTicks() {
        return kitingMeleeTicks;
    }

    public long kitingRetreatTicks() {
        return kitingRetreatTicks;
    }

    /** 目标进入「最小开火距离」以内的 tick 次数（后撤的触发次数） */
    public long kitingTooCloseTicks() {
        return kitingTooCloseTicks;
    }

    /** 驻留（HOLD）的 tick 次数 */
    public long kitingHoldTicks() {
        return kitingHoldTicks;
    }

    /** 某个槽位的距离保持规则（离线用例断言「按弹道推导」用；近战单位返回 null） */
    public RangedKiting.Rule kitingRuleOf(int slot) {
        TypeStats s = statsForSlot(slot);
        return s == null ? null : s.kitingRule();
    }

    /** 某个槽位的阵营（离线用例按阵营统计站位用） */
    public int teamOf(int slot) {
        return data.team[slot] & 0xFF;
    }

    /** 某个槽位**此刻**算不算远程单位（离线用例诊断用；走的就是生产那条 ranged() 链） */
    public boolean rangedForHarness(int slot) {
        TypeStats s = statsForSlot(slot);
        return s != null && s.ranged();
    }

    /** 某个槽位生效的移动速度 / 索敌半径（离线用例诊断用：确认覆盖表真的生效了） */
    public String debugStats(int slot) {
        TypeStats s = statsForSlot(slot);
        if (s == null) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "speed=%.4f follow=%.1f ranged=%s dist=%.2f melee=%.2f",
                s.moveSpeed, s.followRange, s.ranged(), s.rangedDistance, s.meleeReach);
    }

    /**
     * 记录一次站位观测（**每 tick 每单位一次**，纯计数，没有分配）。
     *
     * @param distance 本单位与目标的水平距离（格）
     * @param rule     该单位的距离保持规则（null = 近战单位，只统计「贴脸」这一项）
     */
    private void noteKiting(int i, float distance, RangedKiting.Rule rule, boolean retreating) {
        if (rule == null) {
            return;
        }
        if (distance < kitingMinDistance) {
            kitingMinDistance = distance;
        }
        TypeStats s = statsForSlot(i);
        float melee = s != null ? s.meleeReach : 1.5F;
        if (distance <= melee) {
            kitingMeleeTicks++;
        }
        if (distance < rule.minDistance) {
            kitingTooCloseTicks++;
        }
        if (retreating) {
            kitingRetreatTicks++;
        } else if (distance >= rule.minDistance && distance <= rule.maxDistance) {
            kitingHoldTicks++;
        }
    }

    private float[] playerX = new float[0];
    private float[] playerZ = new float[0];
    private int playerCount;

    /** 收集玩家附近单位的下标缓冲；数量不设上限时按需扩容 */
    public int[] outSlots;

    private long tick;
    private int spawnedTotal;
    private int killedTotal;

    public CrowdManager(ResourceKey<Level> dimension, int capacity) {
        this.dimension = dimension;
        this.data = new CrowdData(capacity);
        this.grid = new SpatialGrid(2.0f);
        // 构造函数里一律用 safeGet：离线 harness 会直接 new 这个类，而那时配置还没加载
        float flowCell = HordeConfig.safeGet(HordeConfig.COMMON.flowCellSize, 4.0F);
        for (int i = 0; i < MAX_TEAMS; i++) {
            flow[i] = new FlowField(flowCell);
        }
        this.deathScratch = new int[Math.max(64, capacity / 8)];
        this.outSlots = new int[Math.max(1024,
                HordeConfig.safeGet(HordeConfig.COMMON.syncBudgetPerPlayer, 0))];
        // 远程「侧移找视线」的按槽位状态：默认 HOLD（= 不需要位移）
        this.rangedLosMove = new RangedKiting.Move[capacity];
        Arrays.fill(this.rangedLosMove, RangedKiting.Move.HOLD);
        this.losLogTick = new long[capacity];
    }

    public CrowdData data() {
        return data;
    }

    public SpatialGrid grid() {
        return grid;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public int aliveCount() {
        return data.alive;
    }

    public int spawnedTotal() {
        return spawnedTotal;
    }

    public int killedTotal() {
        return killedTotal;
    }

    // ------------------------------------------------------------------
    // 生成 / 清理
    // ------------------------------------------------------------------

    /** 登记实体类型，返回索引；超过上限返回 -1。 */
    public int typeIndexOf(ResourceLocation id) {
        Integer idx = typeIndexMap.get(id);
        if (idx != null) {
            return idx;
        }
        if (typeIds.size() >= MAX_TYPES) {
            return -1;
        }
        int i = typeIds.size();
        typeIds.add(id);
        typeIndexMap.put(id, i);
        ensureStats(id, i);
        return i;
    }

    /** 该类型的真实属性（尺寸/速度/生命/攻击/护甲/索敌/攻速）。 */
    public TypeStats statsOf(int typeIndex) {
        if (typeIndex < 0 || typeIndex >= typeIds.size()) {
            return null;
        }
        TypeStats stats = typeIndex < typeStats.size() ? typeStats.get(typeIndex) : null;
        if (stats == null) {
            stats = ensureStats(typeIds.get(typeIndex), typeIndex);
        }
        return stats;
    }

    /** 该单位的属性；查不到时退回类型表首项，保证物理/战斗永远有值可用。 */
    private TypeStats statsForSlot(int slot) {
        // 离线 harness 的逐槽位覆盖优先（线上永远为 null）
        if (statsOverride != null && slot < statsOverride.length && statsOverride[slot] != null) {
            return statsOverride[slot];
        }
        TypeStats stats = statsOf(data.typeIndex[slot] & 0x1F);
        if (stats == null) {
            stats = statsOf(0);
        }
        return stats;
    }

    private TypeStats ensureStats(ResourceLocation id, int typeIndex) {
        while (typeStats.size() <= typeIndex) {
            typeStats.add(null);
        }
        TypeStats stats = typeStats.get(typeIndex);
        if (stats != null) {
            return stats;
        }
        ServerLevel lvl = level;
        // ★ 新类型的属性解析可能很贵（要 new 一个傀儡实体、还要走投掷物自动发现的门槛与打分，
        //   后者在候选池第一次建立时会扫一遍实体注册表）。这里单独计量并**标记到本 tick 的事件里**，
        //   于是「生成 20 个骷髅的瞬间服务器掉了 41 tick」这件事能直接在卡顿行里看到归因。
        long tStats = HordeProfiler.begin();
        stats = TypeStats.of(lvl, id);
        TypeStats built = stats;
        boolean announced = lvl != null;
        if (announced) {
            typeStats.set(typeIndex, stats);
            if (!stats.fromEntity) {
                logWarn("[horde] 读不到 {} 的实体属性，改用配置兜底值", id);
            }
            // 登记类型时就把「攻击模式」写清楚：真机排查时第一眼要看的就是这一行 ——
            // 上一轮的病根（骷髅被当成纯近战、且因为 actionCount=0 永不发射）在这里就能看出来。
            logInfo("[horde] 类型 {} -> 攻击模式 {}（动作数 {}，投掷物来源 {}，近战距离 {}，"
                            + "索敌 {}，攻速冷却 {} tick）",
                    id, built.ranged()
                            ? "远程（距离 " + fmt(built.rangedDistance) + " 格，间隔 " + built.rangedInterval + " tick）"
                            : "近战",
                    built.actionCount,
                    built.hasConfiguredProjectiles ? "GUI 配置" : (built.hasAutoProjectile ? "自动解析" : "无"),
                    fmt(built.meleeReach), fmt(built.followRange), built.attackCooldownTicks);
        }
        // 计量放在**最后**：连带上面的日志一起算进去（日志本身也可能是那一下卡顿的一部分）。
        // noteStatsBuild 自己会把耗时累加进 Stage.STATS（唯一累加器），调用方不要再加一遍。
        HordeProfiler.noteStatsBuild(tStats == 0L ? 0L : System.nanoTime() - tStats);
        return stats;
    }

    /** 维度引用：只用来读取实体注册属性，随 spawn/tick 刷新。 */
    public void setLevel(ServerLevel level) {
        this.level = level;
    }

    /** 已登记的实体类型表（索引即 CrowdData.typeIndex 的值）。 */
    public List<ResourceLocation> typeIds() {
        return typeIds;
    }

    /**
     * 生成指定阵营、指定实体类型的单位。
     * 阵营由调用者显式指定，因此可以「分多次、在不同位置」分别生成两军。
     *
     * @return 实际生成数量；类型超出上限返回 -1
     */
    public int spawn(ServerLevel level, ResourceLocation typeId, int count, int team,
                     double cx, double cy, double cz, double spread) {
        // ★ 生成路径单独计时。真机症状是「生成 20 个骷髅」这个瞬间服务器掉了 41 tick，
        //   而生成走的是**命令线程**、不落在 crowdTick 的分段里 —— 不单独量就永远看不到它。
        long spawnStart = HordeProfiler.enabled() ? System.nanoTime() : 0L;
        setLevel(level);
        if (team < 0) {
            team = 0;
        }
        if (team >= MAX_TEAMS) {
            team = MAX_TEAMS - 1;
        }
        if (team + 1 > teamCount) {
            teamCount = team + 1;
        }

        int typeIdx = typeIndexOf(typeId);
        if (typeIdx < 0) {
            return -1;
        }
        TypeStats stats = statsOf(typeIdx);

        RandomSource rnd = RandomSource.create();
        float maxHp = stats == null ? HordeConfig.safeGet(HordeConfig.COMMON.unitMaxHp, 20.0F) : stats.maxHp;
        int cd = stats == null ? HordeConfig.safeGet(HordeConfig.COMMON.attackCooldownTicks, 18) : stats.attackCooldownTicks;
        int added = 0;

        for (int i = 0; i < count; i++) {
            int slot = data.alloc();
            if (slot < 0) {
                break;
            }
            double a = rnd.nextDouble() * Math.PI * 2.0D;
            double r = Math.sqrt(rnd.nextDouble()) * spread;
            data.posX[slot] = (float) (cx + Math.cos(a) * r);
            data.posY[slot] = (float) cy;
            data.posZ[slot] = (float) (cz + Math.sin(a) * r);
            data.prevX[slot] = data.posX[slot];
            data.prevZ[slot] = data.posZ[slot];
            data.yaw[slot] = (float) (rnd.nextDouble() * 360.0D);
            data.velX[slot] = 0f;
            data.velY[slot] = 0f;
            data.velZ[slot] = 0f;
            data.onGround[slot] = false;
            data.horizontalCollision[slot] = false;
            data.maxHp[slot] = maxHp;
            data.hp[slot] = maxHp;
            data.team[slot] = (byte) team;
            data.typeIndex[slot] = (byte) typeIdx;
            data.state[slot] = CrowdData.STATE_IDLE;
            data.walkPos[slot] = (float) rnd.nextDouble() * WALK_WRAP;
            data.walkSpeed[slot] = 0f;
            data.swingTime[slot] = 0;
            data.swinging[slot] = false;
            data.hurtTime[slot] = 0;
            data.headYaw[slot] = 0f;
            data.headPitch[slot] = 0f;
            data.age[slot] = 0;
            data.attackCd[slot] = (int) (rnd.nextDouble() * cd);
            data.targetId[slot] = -1;
            data.retargetCd[slot] = (int) (rnd.nextDouble() * RETARGET_INTERVAL);
            data.aliveFlag[slot] = true;
            added++;
        }

        data.alive += added;
        spawnedTotal += added;
        lastSpawnNanos = spawnStart == 0L ? 0L : System.nanoTime() - spawnStart;
        lastSpawnCount = added;
        return added;
    }

    /** 上一次 {@link #spawn} 的耗时（纳秒）与实际生成数量 —— 真机排查「生成瞬间掉 tick」用。 */
    public long lastSpawnNanos() {
        return lastSpawnNanos;
    }

    public int lastSpawnCount() {
        return lastSpawnCount;
    }

    private long lastSpawnNanos;
    private int lastSpawnCount;

    public void clear() {
        data.reset();
        for (FlowField f : flow) {
            f.clear();
        }
        spawnedTotal = 0;
        killedTotal = 0;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick(ServerLevel level) {
        tickWithLevel(level);
    }

    /**
     * 主循环的**不依赖 ServerLevel 参数**的入口：世界引用由 {@link #setLevel} 预先设好。
     *
     * 为什么需要它：离线 harness 没有 ServerLevel，但模拟本身（AI/物理/战斗/技能）
     * 完全不碰世界 —— 只有方块碰撞（{@link BlockWorld}）需要。把「世界从哪来」拆开后，
     * harness 就能用注入的投掷物管理器 + 替身类型跑**同一个主循环**，
     * 于是「远程攻击模式到底会不会被触发」这件事可以在离线一次跑清楚。
     * 方块世界为 null 时物理步会照常跑（只是没有地形碰撞），这与「测试里没有地形」的假设一致。
     */
    public void tickWithLevel(ServerLevel level) {
        tick++;
        if (data.alive == 0) {
            return;
        }
        HordeProfiler.noteUnits(data.alive);
        if (level != null) {
            if (blockWorld == null) {
                blockWorld = new BlockWorld(level, 1 << 17);
            }
            blockWorld.beginTick();
            cachePlayers(level);
        } else {
            // 没有世界（离线 harness）：不做方块碰撞，也不缓存玩家。
            // 物理步仍然照跑 —— 这正是「没有地形时的自由运动」，与测试的假设一致。
            playerCount = 0;
        }
        long tGrid = HordeProfiler.begin();
        grid.build(data);
        HordeProfiler.end(tGrid, HordeProfiler.Stage.GRID);

        int flowInterval = Math.max(1, HordeConfig.safeGet(HordeConfig.COMMON.flowIntervalTicks, 10));
        if (tick % flowInterval == 0) {
            long tFlow = HordeProfiler.begin();
            recomputeFlow();
            HordeProfiler.end(tFlow, HordeProfiler.Stage.FLOW);
        }

        // ---- 分层计时：每一段单独量，先测量再优化 ----
        // ★ 统一走 HordeProfiler 自己的累加器（Stage 枚举）。上一版把耗时累加到调用方持有的
        //   long[] 槽位上，而汇总行读的是 HordeProfiler 自己的字段 —— 两套累加器，于是
        //   真机日志里服务端所有分段恒为 0.000ms。现在**只有一条路径**：写在 HordeProfiler 里，
        //   读也在 HordeProfiler 里（见 HordeProfiler 的类注释）。
        // 「新类型属性解析（含投掷物自动发现）」不在下面这几段里：它发生在 spawn 的调用方线程，
        // 所以单独走 HordeProfiler.noteStatsBuild，汇总行里单列一项。
        long t = HordeProfiler.begin();
        aiTick();
        HordeProfiler.end(t, HordeProfiler.Stage.AI);
        t = HordeProfiler.begin();
        // 分离放在物理步之前：被挤到墙里时，后面的碰撞解算会兜住
        pushEntities();
        HordeProfiler.end(t, HordeProfiler.Stage.PUSH);
        t = HordeProfiler.begin();
        physicsTick();
        HordeProfiler.end(t, HordeProfiler.Stage.PHYSICS);
        t = HordeProfiler.begin();
        resolveCombat();
        HordeProfiler.end(t, HordeProfiler.Stage.COMBAT);
        t = HordeProfiler.begin();
        skillTick();
        HordeProfiler.end(t, HordeProfiler.Stage.SKILL);
    }

    /**
     * 战斗阶段兜底索敌是否节流（**默认 true = 线上行为**）。
     *
     * <p>存在的理由只有一个：让离线 harness 能在同一份代码上跑「优化前 vs 优化后」的对照实验
     * （关掉它就是旧行为：找不到目标时每 tick 全量半径扫描）。它不改变任何其它语义。
     */
    private boolean combatScanThrottled = true;

    /** 只给离线对照实验用：关掉战斗阶段的索敌节流 = 复现旧行为。 */
    public void setCombatScanThrottled(boolean throttled) {
        this.combatScanThrottled = throttled;
    }

    public boolean combatScanThrottled() {
        return combatScanThrottled;
    }

    /**
     * 动作/技能：推进每个单位正在播放的动作，并在**命中帧**于群集模拟内部结算一次范围效果。
     *
     * 这里完全不依赖具体模组：动作编号来自该实体自己的动画清单（{@link dev.swordkingdom.horde.action.ActionDriver}），
     * 伤害用该实体自己的 ATTACK_DAMAGE、范围由它的体型/近战距离推算，
     * 目标的护甲减免走原版 {@code CombatRules}，击退按目标的击退抗性衰减。
     */
    private void skillTick() {
        CrowdData d = data;
        boolean useAttrs = HordeConfig.safeGet(HordeConfig.COMMON.useEntityAttributes, true);
        int duration = Math.max(4, HordeConfig.safeGet(HordeConfig.COMMON.actionDurationTicks, 24));
        int impactFallback = SkillParams.impactTick(duration,
                HordeConfig.safeGet(HordeConfig.COMMON.actionImpactFraction, 0.5F));
        float radiusScaleFallback = HordeConfig.safeGet(HordeConfig.COMMON.actionRadiusScale, 1.0F);
        float damageScaleFallback = HordeConfig.safeGet(HordeConfig.COMMON.actionDamageScale, 1.0F);
        float knockbackFallback = HordeConfig.safeGet(HordeConfig.COMMON.actionKnockback, 0.35F);
        int skillIntervalFallback = HordeConfig.safeGet(HordeConfig.COMMON.actionIntervalTicks, 120);
        float cfgReach = HordeConfig.safeGet(HordeConfig.COMMON.attackRange, 1.1F);
        float cfgDmg = HordeConfig.safeGet(HordeConfig.COMMON.unitDamage, 3.0F);
        int gw = grid.gridWidth();

        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            int period = lodPeriod(i);
            TypeStats stats = useAttrs ? statsForSlot(i) : null;
            boolean ranged = stats != null && stats.ranged();
            int actionCount = stats != null ? stats.actionCount : 0;
            // 攻击/技能参数：覆盖表优先（stats 里已经解析好），否则用配置
            int impact = stats != null ? SkillParams.impactTick(duration, stats.skillImpactFraction)
                    : impactFallback;
            float radiusScale = stats != null ? stats.skillRadiusScale : radiusScaleFallback;
            float damageScale = stats != null ? stats.skillDamageScale : damageScaleFallback;
            float knockbackBase = stats != null ? stats.skillKnockback : knockbackFallback;
            int skillInterval = stats != null ? stats.skillIntervalTicks : skillIntervalFallback;
            // 动作编号也可以固定（-1 = 自动轮换）
            int fixedAction = stats != null ? stats.actionOverride : -1;

            // ---- 技能/远程节奏 ----
            // 有动画 API 的单位：按施放间隔播自己的动作，命中帧结算（与改动之前一致）。
            // 没有动画 API 的单位（**原版骷髅就是这一类**）：只判「冷却好了没」，
            // 冷却由攻击冷却（resolveCombat 里按攻速算出来的 attackCooldownTicks）统一节流。
            //
            // 上一轮的病根就在这一段之前：条件写的是 actionCount > 0，
            // 而骷髅的 actionCount 恒为 0，于是整段被跳过、永远不发射。
            // 现在远程单位即使一个动作都没有也照样进得来 —— 「有弹」就等于「会放」。
            if (actionCount > 0) {
                if (d.skillCd[i] > 0) {
                    d.skillCd[i] -= period;
                } else if (skillInterval > 0 && d.action[i] == 0 && d.hp[i] > 0f) {
                    d.skillCd[i] = skillInterval / 2 + (i * 7919 % skillInterval);
                    // 有动画：走「播动作 -> 命中帧结算」这条路
                    if (fixedAction > 0 && fixedAction <= actionCount) {
                        d.action[i] = (byte) fixedAction;
                        d.actionTick[i] = 0;
                    } else if (fixedAction < 0) {
                        startAction(d, i, actionCount);
                    }
                }
            } else if (ranged) {
                // 没有动画 API：直接按攻击冷却放箭（不需要「先播动作」这一步）
                attackRanged(d, i, stats, period);
                continue;
            }

            if (d.action[i] == 0) {
                continue;
            }
            int prev = d.actionTick[i];
            int next = prev + period;
            if (next > duration) {
                d.action[i] = 0;
                d.actionTick[i] = 0;
                continue;
            }
            d.actionTick[i] = (short) next;
            // 跨过命中帧：结算一次范围效果 + 发射虚拟投掷物（如果这个动作配了）
            if (prev < impact && next >= impact) {
                if (!ranged) {
                    applySkill(d, i, stats, stats != null ? stats.meleeReach : cfgReach,
                            stats != null ? stats.damage : cfgDmg,
                            radiusScale, damageScale, knockbackBase, gw);
                }
                launchFromAction(d, i, stats);
            }
        }
        flushDeaths();
    }

    /** 开始播放第 k 个动作（k 在 1..actionCount 之间轮换） */
    private void startAction(CrowdData d, int i, int actionCount) {
        d.action[i] = (byte) (1 + (d.action[i] + i) % Math.max(1, actionCount));
        d.actionTick[i] = 0;
    }

    /** 命中帧结算：范围内的敌人受到伤害与击退 */
    private void applySkill(CrowdData d, int i, TypeStats atkStats, float reach, float baseDamage,
                            float radiusScale, float damageScale, float knockback, int gw) {
        float width = atkStats != null ? atkStats.width : WIDTH;
        float radius = SkillParams.radius(width, reach, radiusScale);
        float r2 = radius * radius;
        float damage = SkillParams.damage(baseDamage, damageScale);
        if (damage <= 0.0F && knockback <= 0.0F) {
            return;
        }
        int cx = grid.cellX(d.posX[i]);
        int cz = grid.cellZ(d.posZ[i]);
        // 覆盖半径所需的格数
        int spanX = Math.max(0, grid.cellX(d.posX[i] + radius) - cx);
        int spanZ = Math.max(0, grid.cellZ(d.posZ[i] + radius) - cz);
        int gh = grid.gridHeight();
        for (int ax = cx - spanX; ax <= cx + spanX; ax++) {
            if (ax < 0 || ax >= gw) {
                continue;
            }
            for (int az = cz - spanZ; az <= cz + spanZ; az++) {
                if (az < 0 || az >= gh) {
                    continue;
                }
                int ci = az * gw + ax;
                int s = grid.start(ci);
                int e = grid.end(ci);
                for (int k = s; k < e; k++) {
                    int j = grid.item(k);
                    if (j == i || !d.aliveFlag[j] || d.hp[j] <= 0f || d.team[j] == d.team[i]) {
                        continue;
                    }
                    float dx = d.posX[j] - d.posX[i];
                    float dz = d.posZ[j] - d.posZ[i];
                    float dd = dx * dx + dz * dz;
                    if (dd > r2) {
                        continue;
                    }
                    TypeStats defStats = statsForSlot(j);
                    float applied = damage;
                    if (defStats != null) {
                        applied = SkillParams.afterArmor(damage, defStats.armor, defStats.armorToughness);
                    }
                    if (applied > 0.0F && d.hp[j] > 0f) {
                        float nhp = d.hp[j] - applied;
                        d.hp[j] = nhp;
                        d.hurtTime[j] = HURT_DURATION;
                        if (nhp <= 0f) {
                            queueDeath(j);
                        }
                    }
                    if (knockback > 0.0F && dd > 1.0E-4F) {
                        float dist = (float) Math.sqrt(dd);
                        float kb = SkillParams.knockback(knockback,
                                defStats != null ? defStats.knockbackResistance : 0.0F);
                        float f = kb * (1.0F - dist / radius);
                        if (f > 0.0F) {
                            d.velX[j] += dx / dist * f;
                            d.velZ[j] += dz / dist * f;
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 虚拟投掷物：发射（动作命中帧 / 远程攻击模式两条路）
    // ------------------------------------------------------------------

    /**
     * 远程攻击的**可注入点**：决策本身是生产代码（{@link #rangedAttackDefault}），
     * 注入的是「运行环境」—— 投掷物管理器与施法者类型。
     *
     * 存在的唯一理由是**可验证性**：这些环境要么来自 ServerLevel（离线 harness 起不了），
     * 要么来自注册表自动解析（离线也起不了）。换成回调之后，
     * {@code ProjectileHarness} 能注入两个替身值就完整跑通「射程 / 节流 / 命中结算」，
     * 而且跑的是**同一个** {@code rangedAttackDefault}，不是另写一份逻辑。
     */
    public static final class RangedDeps {
        /** 投掷物管理器（虚拟投掷物的 SoA 数据体） */
        public final ProjectileManager projectiles;
        /** 施法者类型 id（用于 per-caster 上限与日志） */
        public final ResourceLocation caster;
        /** 自动挑投掷物（null = 不自动挑；离线 harness 直接给一个固定 id） */
        public final java.util.function.Function<ResourceLocation, ResourceLocation> autoPick;

        public RangedDeps(ProjectileManager projectiles, ResourceLocation caster,
                          java.util.function.Function<ResourceLocation, ResourceLocation> autoPick) {
            this.projectiles = projectiles;
            this.caster = caster;
            this.autoPick = autoPick;
        }
    }

    @FunctionalInterface
    public interface RangedAttackInjector {
        /** @return 本 tick 是否真的发射了 */
        boolean attack(CrowdManager manager, CrowdData d, int slot, TypeStats stats, int period);

        /**
         * 远程攻击需要的运行环境。
         *
         * 为什么做成接口方法而不是单独一个字段：默认实现必须拿到「本管理器自己的」
         * 投掷物管理器与施法者类型，而这两者在离线 harness 里是替身 ——
         * 把它们放在同一个注入点上，测试就只需要实现一个匿名的注入器。
         */
        default RangedDeps provideDeps(CrowdManager manager, CrowdData d, int slot) {
            ResourceLocation caster = manager.typeIdAt(slot);
            return new RangedDeps(manager.level == null ? null : ProjectileManagers.get(manager.level),
                    caster, caster == null ? null : id -> manager.autoProjectileFor(id));
        }

        /** 默认实现：走真实决策（含方块视线采样） */
        RangedAttackInjector DEFAULT = CrowdManager::rangedAttackDefault;
    }

    private RangedAttackInjector rangedInjector = RangedAttackInjector.DEFAULT;

    /** 换成替身注入器（只给离线 harness 用） */
    public void setRangedInjector(RangedAttackInjector injector) {
        this.rangedInjector = injector == null ? RangedAttackInjector.DEFAULT : injector;
    }

    /** 复用同一个决策对象，避免每 tick 每单位分配（SoA 模拟里这是硬要求） */
    private final ProjectileCombat.Query rangedQuery = new ProjectileCombat.Query();
    private final ProjectileCombat.Launch rangedLaunch = new ProjectileCombat.Launch();

    /**
     * 只给离线对照实验用：关掉**重力补偿** = 复现旧行为（只做水平提前量，远距离全落空）。
     *
     * <p>与 {@link #setCombatScanThrottled} / {@code ProjectileCatalog.setAutoAttackMemoEnabled}
     * 同一种性质：它只用来量出「修前 vs 修后」的远距离命中率差距，线上永远是开的。
     */
    public void setGravityCompensation(boolean on) {
        rangedQuery.gravityCompensation = on;
    }

    public boolean gravityCompensation() {
        return rangedQuery.gravityCompensation;
    }

    /** 该弹在给定水平距离下的**预测重力下垂**（格）——离线用例拿它和实测弹道对照 */
    public static float predictedDrop(float distance, float speed, float gravity, float drag) {
        return ProjectileCombat.gravityCompensation(distance, speed, gravity, drag);
    }

    /**
     * 上一次开火尝试里「没有视线」的结论（按槽位）。
     *
     * <p>为什么要把这件事从战斗阶段带到物理阶段：{@code resolveCombat} 在 {@code physicsTick}
     * **之后**跑，所以「这一帧有没有视线」只能在下一 tick 的移动里消费。
     * 物理阶段看到 {@link RangedKiting.Move#STRAFE_LOS} 就侧移一步找视线，
     * 而不是让单位一路顶上去贴脸 —— 这正是玩家要的「原地开火优先」。
     */
    private RangedKiting.Move[] rangedLosMove = new RangedKiting.Move[0];

    /** 「侧移找视线」日志的限频门（每槽位一个标记，避免每 tick 刷；按槽位是因为排查时一般盯一类单位） */
    private long[] losLogTick = new long[0];

    /**
     * 保证「侧移找视线」的两个按槽位数组覆盖到 slot。
     *
     * <p>为什么需要：离线用例会用 {@code putUnitRaw} 直接写高位槽位（可能超过构造时的 capacity），
     * 而这两个数组是按 capacity 开的。线上路径槽位永远在 capacity 内，所以这里通常一次都不扩。
     */
    private void ensureLosArrays(int slot) {
        if (slot < rangedLosMove.length && slot < losLogTick.length) {
            return;
        }
        int want = Math.max(slot + 1, Math.max(rangedLosMove.length, losLogTick.length) * 2);
        want = Math.max(want, 16);
        rangedLosMove = Arrays.copyOf(rangedLosMove, want);
        losLogTick = Arrays.copyOf(losLogTick, want);
        for (int k = 0; k < rangedLosMove.length; k++) {
            if (rangedLosMove[k] == null) {
                rangedLosMove[k] = RangedKiting.Move.HOLD;
            }
        }
    }

    /**
     * 该槽位这一 tick 允不允许打一条「侧移找视线」日志（限频：同一槽位两秒一次）。
     *
     * <p>为什么限频：没有方块世界/地形遮挡时，这个分支可能每 tick 都命中，
     * 不限频就是第二个刷屏源（真机事故「日志本身成为卡顿来源」的翻版）。
     */
    private boolean claimLosLog(int slot) {
        if (slot >= losLogTick.length) {
            losLogTick = Arrays.copyOf(losLogTick, Math.max(slot + 1, data.capacity));
        }
        long now = tick;
        if (now - losLogTick[slot] < 40L) {
            return false;
        }
        losLogTick[slot] = now;
        return true;
    }

    /**
     * 远程决策入口（真实实现）：目标在远程距离内 + 有视线 + 冷却好了 → 朝目标发射。
     *
     * 判定与数学全在 {@link ProjectileCombat#decide} 里（离线可跑），这里只负责
     * 「把群集数据喂进去」和「把结果落到投掷物管理器上」。
     *
     * 刻意做成 **static 且包级可见**：这样离线 harness 能用注入的替身环境调用**同一个**实现
     * （见 {@code ProjectileHarness}），从而验证线上真正跑的那段代码。
     */
    public static boolean rangedAttackDefault(CrowdManager self, CrowdData d, int i, TypeStats stats, int period) {
        self.ensureLosArrays(i);
        RangedDeps deps = self.rangedInjector.provideDeps(self, d, i);
        if (deps.projectiles == null) {
            self.logThrottled(i, false, null, "本维度还没有 ServerLevel / 投掷物管理器，无法发射");
            return false;
        }
        int target = d.targetId[i];
        if (target < 0 || !d.aliveFlag[target] || d.hp[target] <= 0f || d.team[target] == d.team[i]) {
            return false;
        }
        TypeStats mine = self.statsForSlot(i);
        TypeStats theirs = self.statsForSlot(target);
        // ---- 真正要发射的那枚弹：先解析出来，才能用它自己的弹速做提前量 ----
        // 以前这里把 q.speed 写死成 3.0，于是：
        //   ① 提前量无从谈起（不知道弹有多快）；
        //   ② 「射击距离」与「理想站位」对不上（雪球 1.5 格/tick 与箭 3.0 完全不同的弹道）。
        // 解析顺序与 launch() 完全一致：技能配置里的投掷物 -> 自动解析。解析不出来时，
        // 后面的 decide/fire 会各自报出原因（绝不静默）。
        ResourceLocation projectileId = resolveRangedProjectile(self, d, i, stats, deps);
        float bulletSpeed = 3.0F;
        float bulletGravity = 0.05F;
        float bulletDrag = 0.99F;
        boolean bulletResolved = false;
        if (projectileId != null) {
            ProjectileManager.ProjType pt = deps.projectiles.typeOf(
                    deps.projectiles.typeIndexOf(projectileId));
            if (pt != null) {
                bulletSpeed = pt.speed;
                bulletGravity = pt.gravity;
                bulletDrag = pt.drag;
                bulletResolved = true;
            }
        }
        if (!bulletResolved) {
            // 解析不出投掷物时按**箭类默认弹道**估补偿（真正的失败会在发射点报出原因，绝不静默）
            ProjectileCatalog.Defaults def = ProjectileCatalog.defaultsOf(ProjectileCatalog.Kind.ARROW);
            bulletGravity = def.gravity;
            bulletDrag = def.drag;
        }

        ProjectileCombat.Query q = self.rangedQuery;
        q.casterX = d.posX[i];
        q.casterY = d.posY[i];
        q.casterZ = d.posZ[i];
        q.casterEye = mine != null ? mine.eyeHeight : 1.6F;
        q.targetX = d.posX[target];
        q.targetY = d.posY[target];
        q.targetZ = d.posZ[target];
        q.targetEye = theirs != null ? theirs.eyeHeight : 1.5F;
        q.meleeRange = mine != null ? mine.meleeReach : HordeConfig.safeGet(HordeConfig.COMMON.attackRange, 1.1F);
        // 开火射程上界 = max(玩家配的远程射程, 这枚弹的**纯弹道射程**)
        // —— 玩家要的是「骷髅可以直接向极远的目标发射箭矢」：只要弹道真的够得着就该能射，
        //    而不是被 followRange / rangedDistance 这类配置挡在门外。
        q.rangedRange = mine != null
                ? RangedKiting.fireEnvelope(mine.kitingRule(), mine)
                : HordeConfig.safeGet(HordeConfig.COMMON.projectileRangedDistance, 16.0F);
        q.speed = bulletSpeed;
        // ---- 重力补偿所需的弹道参数（按**这一枚弹自己**的，不是一个通用常数）----
        q.gravity = bulletGravity;
        q.drag = bulletDrag;
        // 目标本 tick 的水平位移（SoA 里 pos - prev 就是它自己的速度），提前量用它外推。
        // 刚生成/离线直接摆放时不衰减到 0 也没关系：那是「上一 tick 没动」的正确读数。
        q.targetVelX = d.posX[target] - d.prevX[target];
        q.targetVelZ = d.posZ[target] - d.prevZ[target];
        q.leadTicks = mine != null && mine.kitingRule() != null
                ? mine.kitingRule().leadTicks
                : Math.max(1.0F, q.rangedRange / Math.max(0.05F, bulletSpeed));

        boolean wantLos = HordeConfig.safeGet(HordeConfig.COMMON.projectileRequireLineOfSight, true);
        boolean canSample = self.blockWorld != null;
        ProjectileCombat.Los los = (wantLos && canSample) ? self::hasLineOfSight
                : ProjectileCombat.Los.ALWAYS;
        // 「拿不到方块世界就退化为不要求视线」这件事必须说出来，否则又是一次静默失效。
        // 注意同时要把 requireLos 关掉：退化路径下 los 恒为 true，再去问它只会得到一个
        // 没有证据的「被挡住」结论（那会刷出一堆假的 WARN）。
        q.requireLos = wantLos && canSample;
        if (wantLos && !canSample) {
            self.logThrottled(i, false, null, "本维度还没有方块世界（群集未 tick 过），"
                    + "视线判定暂时退化为「不要求视线」—— 隔着墙也会放箭，恢复后自动生效");
        }

        // 炮口位置（与 launch() 完全同一套算法）：从眼睛处出射，视线线段也从这里出发
        float eye = mine != null ? mine.eyeHeight : 1.5F;
        float muzzleX = d.posX[i] + (mine != null ? mine.halfWidth : HALF_WIDTH);
        float muzzleY = d.posY[i] + eye * 0.85F;
        float muzzleZ = d.posZ[i] + (mine != null ? mine.halfWidth : HALF_WIDTH);

        if (!ProjectileCombat.decide(q, d.attackCd[i], los, muzzleX, muzzleY, muzzleZ, self.rangedLaunch)) {
            // ★ 有遮挡：**不冲上去**，改为「侧移找视线」。
            //   玩家要的是「站在原地能打就打」；没有视线时侧移一步绕开遮挡，
            //   既不会贴脸，也不会因为一点点遮挡就完全不动（那样永远射不出去）。
            //
            //   注意只有**真的做了视线采样**才算「没有视线」：拿不到方块世界时
            //   requireLos 会被关掉（los 恒为可见），那种情况下决定不了任何事 ——
            //   不能拿一个没有证据的结论去驱动位移（否则会退化成「一直在侧移」）。
            if (self.rangedLaunch.losBlocked && canSample) {
                self.rangedLosMove[i] = RangedKiting.Move.STRAFE_LOS;
                if (self.claimLosLog(i)) {
                    self.logThrottled(i, false, null, "视线被方块挡住（目标 "
                            + fmt(self.rangedLaunch.trueDistance) + " 格）→ 侧移找视线，不冲上去");
                }
            }
            return false;
        }
        self.rangedLosMove[i] = RangedKiting.Move.HOLD;   // 有视线：这一帧原地开火
        // 弹道解不出来时（抬升量被夹到 COMP_MAX）**照原版语义仍然发射**，但必须说清为什么抬这么高。
        // 这是「解不出几何」的唯一可观测信号：静默夹住会让「永远打高/打低」变成第二个查不出的坑。
        if (self.rangedLaunch.compClamped && self.claimLosLog(i)) {
            self.logThrottled(i, false, projectileId, String.format(java.util.Locale.ROOT,
                    "重力补偿解不出（抬升量被夹到 %.0f 格，目标 %.2f 格外）→ 照原版语义仍然发射，"
                            + "可能打高或打低", ProjectileCombat.COMP_MAX, self.rangedLaunch.distance));
        }
        // 冷却立刻盖上：这是「不每 tick 连发」的唯一保证。
        // 三个来源里**取应用节流的那两个的较大值**（不含技能的施放间隔）：
        //   1. period         —— LOD 降频后每个单位实际的结算节拍（不能比它还快）
        //   2. rangedInterval —— 远程单位自己的两次发射间隔（配置项，默认 40 tick = 2 秒）
        //   3. attackCooldown —— 按该实体 ATTACK_SPEED 算出来的攻击冷却（原版骷髅约 20 tick）
        // **不能**把 skillIntervalTicks（技能的施放间隔，默认 120）算进来：
        // 那是「播动作」的节奏，对没有动画 API 的远程单位毫无意义，
        // 一旦算进来就会把骷髅的射速从 2 秒拖成 6 秒（实测症状就是「200 tick 只放了一箭」）。
        d.attackCd[i] = Math.max(period, Math.max(mine != null ? mine.rangedInterval : 0,
                mine != null ? mine.attackCooldownTicks : 0));
        d.state[i] = CrowdData.STATE_ATTACKING;
        self.swing(d, i);
        // ---- 「射击时面向目标」----
        // 身体朝向也转向目标（不只是 headYaw）：后撤时身体本来就朝目标（倒着走），
        // 但「目标横移 + 驻留射击」那一帧身体可能还朝着旧方向，视觉上会像侧着身子放箭。
        // 朝向同步（CrowdSync）读的就是 d.yaw / d.headYaw，所以这里写完客户端立刻一致。
        self.faceYaw(d, i, target, false);
        self.aimHead(d, i, target);
        String source = String.format(java.util.Locale.ROOT,
                "远程攻击模式（目标 #%d，%.2f 格外，提前量 %.1f tick 推出 %.2f 格，yaw %.1f° pitch %.1f°）",
                target, self.rangedLaunch.trueDistance, self.rangedLaunch.leadTicks,
                self.rangedLaunch.leadShift, self.rangedLaunch.yaw, self.rangedLaunch.pitch);
        return self.launch(d, i, stats, 1, source, self.rangedLaunch.vel, null, deps);
    }

    /**
     * 把单位**身体朝向**转向目标（{@code snap = true} 立即、否则按 rotlerp 的每 tick 上限）。
     *
     * <p>与 {@link #aimHead} 的分工：headYaw 是「相对身体的头部偏移」，d.yaw 才是身体。
     * 射击那一帧两者都要对，否则客户端渲染出来的箭会从「侧着身子」的骷髅身上出来。
     */
    private void faceYaw(CrowdData d, int i, int target, boolean snap) {
        float dx = d.posX[target] - d.posX[i];
        float dz = d.posZ[target] - d.posZ[i];
        float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        d.yaw[i] = snap ? worldYaw : rotlerp(d.yaw[i], worldYaw, 90f);
    }

    /**
     * 「这一次远程攻击要发射哪一枚弹」——**解析顺序与 {@link #launch} 完全一致**
     * （技能配置里的投掷物 → 自动解析），只是**不产生任何日志副作用**。
     *
     * <p>为什么需要单独抽出来：真实初速必须在 {@link ProjectileCombat#decide} **之前**拿到
     * （提前量按弹速算），而 {@code launch()} 是发射之后才解析的。
     * 两条路取同一枚弹是硬要求：拿 A 弹的弹速去瞄、最后发射 B 弹，提前量就是错的。
     *
     * @return 投掷物 id；解析不出来返回 null（调用方用箭类默认弹速兜底，并在发射点报出原因）
     */
    private static ResourceLocation resolveRangedProjectile(CrowdManager self, CrowdData d, int i,
                                                            TypeStats stats, RangedDeps deps) {
        ResourceLocation casterId = deps != null ? deps.caster : self.typeIdAt(i);
        if (casterId == null) {
            return null;
        }
        // ① 玩家在技能页签里给这个动作配的投掷物（远程发射没有动作编号，取 0 号动作的配置）
        try {
            dev.swordkingdom.horde.edit.TypeOverride ov =
                    dev.swordkingdom.horde.edit.TypeOverride.get(casterId);
            dev.swordkingdom.horde.edit.ProjectileSkill skill = ov == null ? null : ov.action(d.action[i] & 0xFF);
            if (skill != null && !skill.disabled && skill.projectileId() != null) {
                return skill.projectileId();
            }
        } catch (Throwable ignored) {
            // 覆盖表不可用（离线 harness）：走自动解析
        }
        // ② 自动解析（先门槛、后打分 —— 唯一入口在 ProjectileCatalog.autoAttackCached）
        if (deps != null && deps.autoPick != null) {
            return deps.autoPick.apply(casterId);
        }
        try {
            ProjectileCatalog.Candidate c = ProjectileCatalog.autoAttackCached(casterId);
            return c == null ? null : c.id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方块视线采样（真实实现） */
    private boolean hasLineOfSight(float x0, float y0, float z0, float x1, float y1, float z1) {
        return blockWorld == null || blockWorld.hasLineOfSight(x0, y0, z0, x1, y1, z1);
    }

    /**
     * 远程单位的「放箭」入口（动作命中帧与合成动作共用）。
     *
     * 与动作命中帧那条路的区别只有一个：**瞄准的是目标**（带垂直分量），
     * 而不是「沿自己当前朝向 + 配置仰角」。为此把速度向量算好后交给
     * {@link ProjectileManager#fire} 的显式瞄准重载。
     *
     * 节流：这里**只认攻击冷却**（{@code CrowdData.attackCd}）——
     * 近战、远程、动画技能共用同一个冷却，天然不会出现「一次攻击结算两遍」或「每 tick 连发」。
     */
    private void attackRanged(CrowdData d, int i, TypeStats stats, int period) {
        if (d.attackCd[i] > 0) {
            return;   // 冷却没走完：绝不发射
        }
        rangedInjector.attack(this, d, i, stats, period);
    }

    /**
     * 在**动作命中帧**发射虚拟投掷物（对应原版「骷髅射箭 / 焰魔扔火球」）。
     *
     * 三件事都在这里定：
     *   1. <b>发射什么</b>：玩家在技能页签里选的那个投掷物；没选就按命名空间 + 名字词干
     *      自动为这个施法者挑一枚（{@link ProjectileCatalog#pickFor}），一个模组名都不写；
     *   2. <b>怎么发</b>：参数全部来自该动作的技能配置，未配置的项走按原版基类推导的默认值；
     *   3. <b>发射多少</b>：支持一次多枚扇形散射，但**同一 tick 的总生成数有上限**
     *      （配置项），避免大范围技能在同 tick 刷出成百上千发把服务器卡住。
     *
     * 关键：全程**只往 {@link ProjectileManager} 的 SoA 数组里写数据**，一个真实实体都不生成。
     *
     * 与上一轮的差别：这里**不再**要求该类型「配过投掷物」。
     * 解析不出投掷物的原因会以**限频 WARN** 的形式打出来，而不是安静地什么都不做 ——
     * 「静默失败」正是这次真机排查最大的障碍。
     */
    private void launchFromAction(CrowdData d, int i, TypeStats stats) {
        ResourceLocation casterId = typeIdAt(i);
        if (casterId == null) {
            logThrottled(i, false, null, "施法者的实体类型索引无效，无法解析投掷物");
            return;
        }
        if (level == null) {
            logThrottled(i, false, null, "本维度还没有 ServerLevel，投掷物管理器不可用");
            return;
        }
        dev.swordkingdom.horde.edit.TypeOverride ov =
                dev.swordkingdom.horde.edit.TypeOverride.get(casterId);
        int actionIndex = d.action[i] & 0xFF;
        dev.swordkingdom.horde.edit.ProjectileSkill skill =
                ov == null ? null : ov.action(actionIndex);
        if (skill != null && skill.disabled) {
            // 玩家在 GUI 里明确设成「不发射」：这是配置意图，不该当成错误刷 WARN
            logThrottled(i, false, null, "这个动作在 GUI 里被设成「不发射」（类型 " + casterId + "，动作 " + actionIndex + "）");
            return;
        }
        String why = skill == null
                ? "没有为这个 (类型, 动作) 配置投掷物，也没有可自动解析的候选"
                : "配置里的投掷物 id 解析失败（" + skill.projectile + "）";
        ResourceLocation projectileId = skill == null ? null : skill.projectileId();
        if (projectileId == null) {
            projectileId = autoProjectileFor(casterId, why);
            if (projectileId == null) {
                return;   // 原因已经在 autoProjectileFor / 上面记过日志了
            }
        }
        launch(d, i, stats, skill == null ? 1 : skill.shotCount(),
                "动作命中帧（动作 " + actionIndex + "）", null, skill, null);
    }

    /**
     * 真正把一枚（或一扇形）投掷物写进投掷物管理器，并打一条**限频**的发射日志。
     *
     * @param aim  显式瞄准速度向量；null = 沿单位当前朝向按配置仰角射（动作扇形散射走这条）
     * @param deps 运行环境；null = 走默认实现（本管理器自己的投掷物管理器 + 自动解析）
     * @return 是否至少成功发射了一枚
     */
    private boolean launch(CrowdData d, int i, TypeStats stats, int shots, String source,
                           float[] aim, dev.swordkingdom.horde.edit.ProjectileSkill skill,
                           RangedDeps deps) {
        ResourceLocation casterId = deps != null ? deps.caster : typeIdAt(i);
        ProjectileManager pm = deps != null ? deps.projectiles
                : (level == null ? null : ProjectileManagers.get(level));
        if (pm == null) {
            logThrottled(i, true, null, "投掷物管理器不可用（本维度还没有 ServerLevel）");
            return false;
        }
        ResourceLocation projectileId = skill == null ? null : skill.projectileId();
        if (projectileId == null) {
            projectileId = deps != null && deps.autoPick != null
                    ? deps.autoPick.apply(casterId)
                    : autoProjectileFor(casterId, "自动解析（施法者 " + casterId + "）");
            if (projectileId == null) {
                if (deps == null) {
                    logThrottled(i, true, null, "无法发射：" + lastAutoReason);
                }
                return false;
            }
        }
        ProjectileManager.SkillConfigView view = skill == null ? rangedConfig() : skill.view();
        // 炮口位置：与原版一致，从该类型自己的**眼睛**处出射（不是脚底、也不是身体中心）
        float eye = stats != null ? stats.eyeHeight : 1.5F;
        float cx = d.posX[i] + (stats != null ? stats.halfWidth : HALF_WIDTH);
        // 炮口位置：从该类型自己的**眼睛**处出射（和原版骷髅射箭一样）。
        // 注意这里交出的是「已经算好眼高」的绝对坐标，所以下面调 fire 时必须走
        // 「显式瞄准」那条重载 —— 那个重载不会再加一次眼高（叠两次会让箭从目标头顶飞过）。
        float cy = d.posY[i] + eye * 0.85F;
        float cz = d.posZ[i] + (stats != null ? stats.halfWidth : HALF_WIDTH);
        int fired = 0;
        for (int k = 0; k < Math.max(1, shots); k++) {
            float yaw = d.yaw[i];
            float[] useAim = aim;
            if (aim == null && shots > 1) {
                yaw += (k - (shots - 1) * 0.5F) * (skill == null ? 8.0F : skill.spreadDegrees());
            }
            int slot = pm.fire(projectileId, casterId, stats, cx, cy, cz, yaw, i,
                    d.typeIndex[i] & 0x1F, d.team[i] & 0xFF, view, useAim);
            if (slot < 0) {
                // 上限已满/类型超限：**这不是静默失败**，必须说出来（否则「看不到箭」又要重新查一遍）
                logThrottled(i, true, projectileId, "投掷物写入失败（生成上限已满 / 类型表超过 "
                        + ProjectileManager.MAX_TYPES + " 种）");
                break;
            }
            fired++;
            if (aim != null) {
                break;   // 显式瞄准只发一枚
            }
        }
        if (fired > 0) {
            // ★ 逐发日志改成**每秒汇总一行**（见 HordeLog）。
            //   为什么要改：20 个骷髅每秒约 20 条 INFO，那是在「一万单位」规模下唯一会线性放大的
            //   外部 IO。汇总行保留同样的信息（发了什么、几枚、多快、多远），逐发日志降级为
            //   调试开关（debug.logLaunchDetail，默认关）。
            float speed = aim != null ? length(aim) : -1.0F;
            float targetDist = selfDistanceToTarget(d, i);
            HordeLog.INSTANCE.note(
                    projectileId == null ? null : projectileId.toString(), fired, speed, targetDist);
            if (HordeLog.Summary.detailEnabled()) {
                String detail = aim != null
                        ? String.format(java.util.Locale.ROOT,
                        "瞄准目标，初速 %.2f 格/tick，方向 (%.2f, %.2f, %.2f)",
                        length(aim), aim[0], aim[1], aim[2])
                        : "沿朝向 yaw " + fmt(d.yaw[i]) + "° 发射";
                logThrottled(i, false, projectileId, "发射 ×" + fired + "（" + source + "，"
                        + detail + "，类型 " + casterId + "）");
            }
        }        return fired > 0;
    }

    /**
     * 目标距离（格）；没有有效目标时返回 -1（不参与汇总里的平均，避免把 0 混进去）。
     *
     * 只用于**日志汇总**，不参与任何判定 —— 所以它读的是当前 tick 的位置，够用。
     */
    private static float selfDistanceToTarget(CrowdData d, int i) {
        int t = d.targetId[i];
        if (t < 0 || !d.aliveFlag[t] || d.hp[t] <= 0f) {
            return -1.0F;
        }
        float dx = d.posX[t] - d.posX[i];
        float dz = d.posZ[t] - d.posZ[i];
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** 远程攻击没有 (类型, 动作) 配置时用的视图：一切走投掷物自己的基类默认值 */
    private ProjectileManager.SkillConfigView rangedConfig() {
        return RANGED_DEFAULT_CONFIG;
    }

    /**
     * 远程攻击的默认配置视图：全部返回「未设置」，于是 {@link ProjectileManager#fire}
     * 会逐项回落到按**原版基类**推导的默认值（箭就是 3.0 / 0.05 / 100 tick / 伤害=攻击力）。
     * 玩家一旦在技能页签里给这个动作配了参数，走的就是他自己那份视图。
     */
    private static final ProjectileManager.SkillConfigView RANGED_DEFAULT_CONFIG =
            new ProjectileManager.SkillConfigView() {
                @Override public float speed() { return -1.0F; }
                @Override public float pitch() { return 0.0F; }
                @Override public boolean pitchSet() { return false; }
                @Override public float gravity() { return -1.0F; }
                @Override public int life() { return -1; }
                @Override public float hitRadius() { return -1.0F; }
                @Override public float damageScale() { return -1.0F; }
                @Override public float blastRadius() { return -1.0F; }
                @Override public float blastScale() { return -1.0F; }
                @Override public int pierce() { return -1; }
                @Override public float knockback() {
                    return HordeConfig.safeGet(HordeConfig.COMMON.projectileKnockback, 0.3F);
                }
                @Override public boolean stickOnBlock() {
                    return HordeConfig.safeGet(HordeConfig.COMMON.projectileStickOnBlock, true);
                }
                @Override public boolean stickOnBlockSet() { return false; }
            };

    // ------------------------------------------------------------------
    // 失效可见：限频日志（发射成功 INFO / 无法发射 WARN）
    // ------------------------------------------------------------------

    /** 每个槽位上一次打日志的 tick（未打过 = Integer.MIN_VALUE）。为什么按槽位：排查时往往要盯某一类单位 */
    private long[] logTick;
    /** 最近一次自动解析失败的原因（由 autoProjectileFor 写，日志里带出来） */
    private String lastAutoReason = "未知";

    /**
     * 限频日志：同一单位在 {@code projectileLogIntervalTicks}（默认 200 tick = 5 秒）内只打一条。
     *
     * 为什么必须限频：上万单位同 tick 一起放箭时，不限频会直接把日志打成 IO 瓶颈，
     * 而这次排查真正需要的是「证明它在发射」。限频到 5 秒一条既证明得了，又不会拖慢服务器。
     * 配置里把它设成 0 就变成每次都打（专门用来排查）。
     */
    private void logThrottled(int slot, boolean warn, ResourceLocation projectileId, String message) {
        int interval = HordeConfig.safeGet(HordeConfig.COMMON.projectileLogIntervalTicks, 200);
        if (interval > 0) {
            long now = tick;
            if (logTick == null || slot >= logTick.length) {
                int cap = Math.max(slot + 1, Math.max(1024, data.highWater + 1));
                logTick = Arrays.copyOf(logTick == null ? new long[0] : logTick, cap);
                // 新开的槽位默认「很久以前打过」
                for (int k = 0; k < logTick.length; k++) {
                    if (logTick[k] == 0L) {
                        logTick[k] = Long.MIN_VALUE / 2;
                    }
                }
            }
            if (now - logTick[slot] < interval) {
                return;
            }
            logTick[slot] = now;
        }
        ResourceLocation id = typeIdAt(slot);
        String proj = projectileId == null ? "" : "，投掷物 " + projectileId;
        if (warn) {
            logWarn("{} 的单位 #{}{} 无法发射：{}", id, slot, proj, message);
        } else {
            logInfo("{} 的单位 #{}{}：{}", id, slot, proj, message);
        }
    }

    /**
     * 安全的 INFO 日志。
     *
     * 为什么需要 try/catch：日志本身可能**根本不可用**（离线 harness 里
     * {@code LogUtils.getLogger()} 会抛异常，而 {@link #launch} 这条链是要在 harness 里跑的）。
     * 日志失败绝不该把「发射投掷物」这件事带崩 —— 这是「静默失败」的另一种形态。
     */
    static void logInfo(String format, Object... args) {
        try {
            HordeMod.LOGGER.info(format, args);
        } catch (Throwable ignored) {
            // 没有日志后端：忽略（模拟必须继续）
        }
    }

    /** 安全的 WARN 日志（同上） */
    static void logWarn(String format, Object... args) {
        try {
            HordeMod.LOGGER.warn(format, args);
        } catch (Throwable ignored) {
            // 没有日志后端：忽略
        }
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 该施法者自动挑出来的投掷物（按类缓存，避免每 tick 扫全注册表） */
    private final Map<ResourceLocation, ResourceLocation> autoProjectile = new HashMap<>();
    /** 已经扫过、确实没有候选的类型（免得每次放招都重扫一遍全表） */
    private final java.util.Set<ResourceLocation> autoProjectileMiss = new java.util.HashSet<>();
    // 注意：这里**不再**自己缓存一份候选池。缓存与重试统一交给
    // ProjectileCatalog.pool()：上一版在这里把 scanAll() 的返回值（注册表还没就绪时是空表）
    // 存成字段，之后永远用那张空表，连重试的机会都没有 —— 真机「投掷物来源 无」的最后一环。

    private ResourceLocation autoProjectileFor(ResourceLocation caster) {
        return autoProjectileFor(caster, "自动解析（施法者 " + caster + "）");
    }

    /**
     * 自动挑投掷物，并把「为什么挑不出来」记进 {@link #lastAutoReason}。
     *
     * 走 {@link ProjectileCatalog#resolve}（registry-free）而不是直接扫注册表：
     * 这样「候选池为空」这类原因会以中文原因串的形式带出来，直接进 WARN 日志。
     */
    private ResourceLocation autoProjectileFor(ResourceLocation caster, String when) {
        if (caster == null) {
            lastAutoReason = "施法者的实体类型为空（" + when + "）";
            return null;
        }
        ResourceLocation cached = autoProjectile.get(caster);
        if (cached != null) {
            return cached;
        }
        if (autoProjectileMiss.contains(caster)) {
            // 只有「候选池已经成功建起来」时，之前的否决才算数。
            // 池没建起来（注册表未就绪 / 扫描异常）时的否决必须作废并本次重试 ——
            // 真机上「投掷物来源 无」的最后一环就是：加载早期问过一次、池是空的，
            // 这个否决被永久留在集合里，于是骷髅/烈焰人再也不会尝试远程。
            if (ProjectileCatalog.poolReady()) {
                lastAutoReason = "之前已经确认过：候选池里没有适合它的投掷物（" + when + "）";
                return null;
            }
            autoProjectileMiss.remove(caster);
        }
        // ---- 自动解析的**门槛**：只有「真的会远程攻击」的实体才自动挑弹 ----
        // 优先级（玩家意图最高）：
        //   1) 玩家在 GUI 里显式指定过投掷物 -> 一律远程。那条路**根本不经过这个方法**
        //      （见 TypeStats.ranged = hasConfigured || hasAuto，以及 launch/launchFromAction
        //       都优先用 skill.projectileId()），所以门槛不会影响玩家的显式配置；
        //   2) 否则问 ProjectileCatalog.rangedGate：原版 RangedAttackMob / CrossbowAttackMob
        //      接口，或「施法者自己的字节码里真的 new 过某枚候选投掷物」（烈焰人/恶魂/潜影贝）；
        //   3) 都不成立 -> 保持近战。
        // 注意：**不写 autoProjectileMiss**。
        //   * MELEE 是「确定」的结论，但已由 ProjectileCatalog 按类型缓存，不需要在这里再否决一次；
        //   * UNKNOWN（构造不出实体 / 读不到字节码 / 池未就绪）**绝不能**缓存，否则某模组实体
        //     在特定阶段才实现接口、或这次探测失败时，就再也没机会变成远程了。
        ProjectileCatalog.RangedGate gate = ProjectileCatalog.rangedGate(caster);
        if (gate != ProjectileCatalog.RangedGate.RANGED) {
            lastAutoReason = (gate == ProjectileCatalog.RangedGate.MELEE
                    ? "该实体没有远程攻击接口（RangedAttackMob/CrossbowAttackMob）也没有发弹证据，按近战处理"
                    : "这次判不出该实体是否会远程攻击（实体类/字节码/候选池未就绪），本次按近战处理")
                    + "（" + when + "）";
            return null;
        }
        // 门槛通过：确保候选池建起来（失败可重试 + 成功才缓存 + 成功/失败都有日志）
        ProjectileCatalog.pool();
        ProjectileCatalog.Pick pick = ProjectileCatalog.resolve(caster.getNamespace(), caster.getPath());
        if (!pick.found()) {
            if (ProjectileCatalog.poolReady()) {
                // 池已经建起来了，这次否决才是结论（否则下次还会重试）
                autoProjectileMiss.add(caster);
            }
            lastAutoReason = pick.reason + "（" + when + "）";
            logWarn("[horde] 解析不出投掷物：{} 的 {} —— 原因：{}", caster, when, pick.reason);
            return null;
        }
        autoProjectile.put(caster, pick.candidate.id);
        return pick.candidate.id;
    }

    /** 该单位使用的实体类型 id（查不到返回 null）。包级可见：远程注入器的默认实现需要它 */
    ResourceLocation typeIdAt(int slot) {
        int idx = data.typeIndex[slot] & 0x1F;
        return idx >= 0 && idx < typeIds.size() ? typeIds.get(idx) : null;
    }

    // ------------------------------------------------------------------
    // 投掷物命中结算（注入给 ProjectileManager）
    // ------------------------------------------------------------------

    /**
     * 让群集单位也参与投掷物命中结算：把「谁被打到了」做成
     * {@link ProjectileManager.Hits} 的实现交给它调用。
     *
     * 这样投掷物模拟完全不认识群集（只管回调），而群集这边也不用改模拟循环。
     * 实现里做三件事：扫掠命中、护甲减免后的伤害 + 击退、可选 AoE。
     *
     * 注意是 **public** 的：接线由 {@link ProjectileManagers#tickAll} 完成，
     * 因为投掷物必须在「群集单位全死光」之后还能继续飞完，
     * 所以接线不能挂在 {@link #tick} 的存活分支里（那个分支在没有活单位时会提前返回）。
     */
    public ProjectileManager.Hits crowdHitResolver() {
        return (pd, slot, applyBlast, radiusScratch) -> {
            hitResolverCalls++;
            HordeProfiler.noteHitCall();
            float r2 = radiusScratch[0];
            float radius = (float) Math.sqrt(r2);
            // ★ 扫掠起点 = 上一 tick 的**真实位置**（由 ProjectileManager.step 在积分前写入）。
            //   这里曾经写成 `pos - vel`，而 vel 此刻已经被重力/阻力/方块解算改过：
            //   撞墙卡住的箭 vel = 0 ⇒ 线段退化成一个点；沿墙滑动时线段比真实轨迹短。
            //   两者都会让「明明飞过去了却没命中」或者反过来「隔着一段距离命中」。
            float prevX = pd.prevX[slot];
            float prevY = pd.prevY[slot];
            float prevZ = pd.prevZ[slot];
            int team = pd.team[slot] & 0xFF;
            int hits = 0;
            candidateChecks = 0;

            // ---- 主命中：线段扫掠找**敌方**单位（友军直接跳过 = 同阵营不伤害，与近战/索敌同一口径）----
            if (grid.valid && radius > 0.0F) {
                int gw = grid.gridWidth();
                int gh = grid.gridHeight();
                int cx0 = grid.cellX(Math.min(prevX, pd.posX[slot]) - radius);
                int cx1 = grid.cellX(Math.max(prevX, pd.posX[slot]) + radius);
                int cz0 = grid.cellZ(Math.min(prevZ, pd.posZ[slot]) - radius);
                int cz1 = grid.cellZ(Math.max(prevZ, pd.posZ[slot]) + radius);
                int checked = 0;
                outer:
                for (int az = cz0; az <= cz1; az++) {
                    for (int ax = cx0; ax <= cx1; ax++) {
                        int ci = az * gw + ax;
                        int s = grid.start(ci);
                        int e = grid.end(ci);
                        for (int k = s; k < e; k++) {
                            int j = grid.item(k);
                            if (!data.aliveFlag[j] || data.hp[j] <= 0f || (data.team[j] & 0xFF) == team) {
                                continue;
                            }
                            // ★ **硬上界**：一个投掷物每 tick 最多检查 MAX_CANDIDATES 个单位，
                            //   超过就停（而不是「命中过一次才停」）。
                            //   为什么：一次 segment×AABB 是 20 来次浮点运算，而一个高密度格子里
                            //   可能塞着上百个单位；「命中才停」意味着「一发打空的箭」会把整格扫完，
                            //   于是命中判定的开销随**密度**而不是随**投掷物数**增长 —— 那正是
                            //   「几百发箭 = 每 tick 全量」的真实形态。
                            //   上界的存在让「100 单位 + 数十枚投掷物」的每 tick 调用次数有一个
                            //   可以写进断言的常数（见 projectileHarness 的调用上界用例）。
                            if (++checked > MAX_CANDIDATES) {
                                break outer;
                            }
                            candidateChecks++;
                            HordeProfiler.noteHitCandidates(1);
                            TypeStats defStats = statsForSlot(j);
                            float half = defStats != null ? defStats.halfWidth : HALF_WIDTH;
                            float height = defStats != null ? defStats.height : HEIGHT;
                            // ★ 精确判定用**线段 × 碰撞箱**（slab 法），不是「线段到球心 ≤ 0.3」。
                            //   球判据对 0.6×1.95 的怪只有 0.3 格的竖直容差，会把「近距离的箭还在上升段」
                            //   判成脱靶、而「远距离下垂下来」的箭反而命中 —— 真机表现就是
                            //   「同样是箭，近的打不中、远的反而中」。理由详见 ProjectileCombat.segmentHitsBox。
                            if (!ProjectileCombat.segmentHitsBox(prevX, prevY, prevZ,
                                    pd.posX[slot], pd.posY[slot], pd.posZ[slot],
                                    data.posX[j] + half, data.posY[j] + height * 0.5F, data.posZ[j] + half,
                                    half, height * 0.5F, half, radius)) {
                                continue;
                            }
                            sweepTests++;
                            float before = data.hp[j];
                            damageUnit(j, pd.damage[slot], pd.knockback[slot],
                                    pd.posX[slot], pd.posZ[slot], radius);
                            float dealt = before - data.hp[j];
                            if (dealt > 0.0F) {
                                damageDealt += dealt;
                            }
                            if (pd.knockback[slot] > 0.0F) {
                                knockbacks++;
                            }
                            hits++;
                            if (applyBlast) {
                                hits += blastAt(pd.posX[slot], pd.posY[slot], pd.posZ[slot],
                                        pd.blastRadius[slot], pd.damage[slot] * pd.blastScale[slot], team, j);
                            }
                            if (pd.pierce[slot] <= 0) {
                                break outer;   // 不可穿透：这一发就停在这儿
                            }
                        }
                    }
                }
            }
            if (hits == 0 && applyBlast) {
                // 没直接命中，但落点附近仍可能有敌人：让火球落地也炸
                hits += blastAt(pd.posX[slot], pd.posY[slot], pd.posZ[slot],
                        pd.blastRadius[slot], pd.damage[slot] * pd.blastScale[slot], team, -1);
            }
            return hits;
        };
    }

    // ------------------------------------------------------------------
    // 投掷物命中统计（**只计数，不参与判定**）
    //
    // 「箭到底有没有伤到虚拟单位」必须能被断言，否则只能靠进游戏肉眼看。
    // 口径（全部按 tick 重置 candidateChecks）：

    //   candidateChecks  这一 tick 里做过「单位筛选」的候选数 —— 每个投掷物每 tick ≤ MAX_CANDIDATES
    //   sweepTests       真正做了 segment×AABB 精确判定的次数（≤ candidateChecks）
    //   damageDealt      对虚拟单位造成的总伤害（过护甲之后的值）
    //   knockbacks       真的施加了击退的次数
    // ------------------------------------------------------------------
    /** 上一次 {@link #crowdHitResolver()} 结算里检查过的候选数（每 tick 归零） */
    private int candidateChecks;
    /** 累计的精确扫掠判定次数 */
    private long sweepTests;
    /** 累计对虚拟单位造成的伤害（过护甲后） */
    private float damageDealt;
    /** 累计施加的击退次数 */
    private long knockbacks;

    public void resetProjectileHitStats() {
        candidateChecks = 0;
        sweepTests = 0L;
        damageDealt = 0.0F;
        knockbacks = 0L;
    }

    /** 最近一次命中结算检查过的候选数（**上界断言用**：必须 ≤ MAX_CANDIDATES） */
    public int lastCandidateChecks() {
        return candidateChecks;
    }

    /** 命中判定的硬上界（格子里候选再多也不会超过它） */
    public int maxCandidates() {
        return MAX_CANDIDATES;
    }

    public long sweepTests() {
        return sweepTests;
    }

    public float damageDealt() {
        return damageDealt;
    }

    public long knockbackCount() {
        return knockbacks;
    }

    /** 诊断：命中结算总共被调用了几次（= 投掷物在飞的 tick 数） */
    public int crowdHitResolverCount() {
        return hitResolverCalls;
    }

    /**
     * AoE 结算：范围内的敌人按**与原版爆炸同形的距离衰减**吃伤害。
     *
     * @param skip 主目标（已经吃过直击伤害，不再吃溅射）
     */
    private int blastAt(float x, float y, float z, float radius, float damage, int team, int skip) {
        if (radius <= 0.0F || damage <= 0.0F || !grid.valid) {
            return 0;
        }
        int gw = grid.gridWidth();
        int gh = grid.gridHeight();
        float r2 = radius * radius;
        int cx0 = grid.cellX(x - radius);
        int cx1 = grid.cellX(x + radius);
        int cz0 = grid.cellZ(z - radius);
        int cz1 = grid.cellZ(z + radius);
        int hit = 0;
        for (int az = cz0; az <= cz1; az++) {
            for (int ax = cx0; ax <= cx1; ax++) {
                int ci = az * gw + ax;
                int s = grid.start(ci);
                int e = grid.end(ci);
                for (int k = s; k < e; k++) {
                    int j = grid.item(k);
                    if (j == skip || !data.aliveFlag[j] || data.hp[j] <= 0f || (data.team[j] & 0xFF) == team) {
                        continue;
                    }
                    float ox = data.posX[j] + (statsForSlot(j) != null ? statsForSlot(j).halfWidth : HALF_WIDTH) - x;
                    float oy = data.posY[j] + (statsForSlot(j) != null ? statsForSlot(j).height * 0.5F : HEIGHT * 0.5F) - y;
                    float oz = data.posZ[j] + (statsForSlot(j) != null ? statsForSlot(j).halfWidth : HALF_WIDTH) - z;
                    float dist2 = ox * ox + oy * oy + oz * oz;
                    if (dist2 > r2) {
                        continue;
                    }
                    float applied = SkillParams.blastFalloff(damage, (float) Math.sqrt(dist2), radius);
                    if (applied <= 0.0F) {
                        continue;
                    }
                    damageUnit(j, applied, 0.0F, x, z, radius);
                    hit++;
                }
            }
        }
        return hit;
    }

    /**
     * 对单个群集单位结算伤害（**走目标的护甲**）与击退，并在血量归零时排队死亡。
     *
     * 抽成一个方法是为了让「近战/范围技能」「投掷物直击」「投掷物 AoE」三条路
     * 用**完全相同**的一套护甲/击退/死亡逻辑 —— 不会出现「技能伤害不吃护甲」这种不一致。
     */
    private void damageUnit(int target, float damage, float knockback, float fromX, float fromZ, float radius) {
        if (damage > 0.0F && data.hp[target] > 0f) {
            TypeStats def = statsForSlot(target);
            float applied = def != null
                    ? SkillParams.afterArmorFor(damage, def.armor, def.armorToughness)
                    : damage;
            if (applied > 0.0F) {
                float nhp = data.hp[target] - applied;
                data.hp[target] = nhp;
                data.hurtTime[target] = HURT_DURATION;
                if (nhp <= 0f) {
                    queueDeath(target);
                }
            }
        }
        if (knockback > 0.0F) {
            float dx = data.posX[target] - fromX;
            float dz = data.posZ[target] - fromZ;
            float dd = dx * dx + dz * dz;
            if (dd > 1.0E-4F) {
                float dist = (float) Math.sqrt(dd);
                TypeStats def = statsForSlot(target);
                float kb = SkillParams.knockback(knockback,
                        def != null ? def.knockbackResistance : 0.0F);
                float f = radius > 0.0F ? kb * Math.max(0.0F, 1.0F - dist / radius) : kb;
                if (f > 0.0F) {
                    data.velX[target] += dx / dist * f;
                    data.velZ[target] += dz / dist * f;
                }
            }
        }
    }

    /** 把缓冲的死亡一次性提交 */
    private void flushDeaths() {        for (int k = 0; k < deathCount; k++) {
            data.free(deathScratch[k]);
        }
        killedTotal += deathCount;
        deathCount = 0;
    }

    /**
     * 自主索敌：每个单位维护自己的目标（对应原版 NearestAttackableTargetGoal + MeleeAttackGoal）。
     * 已有目标且仍在追击范围内则保持锁定；否则按错峰计时重新扫描最近的敌人。
     */
    private void aiTick() {
        CrowdData d = data;
        boolean useAttrs = HordeConfig.safeGet(HordeConfig.COMMON.useEntityAttributes, true);
        float cfgFollow = HordeConfig.safeGet(HordeConfig.COMMON.followRange, 16.0F);
        float cfgFollow2 = cfgFollow * cfgFollow;

        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            TypeStats stats = useAttrs ? statsForSlot(i) : null;
            float follow = stats != null ? stats.followRange : cfgFollow;
            // 远程单位的索敌半径至少要覆盖它的射程：否则「射程 16 格、索敌 16 格」看着够，
            // 但一个 followRange 只有 10 的远程怪（很多模组会这样配）会看不见 11 格外的目标，
            // 表现就是「站着不动、从来不射」。
            if (stats != null && stats.ranged() && stats.rangedDistance + 2.0F > follow) {
                follow = stats.rangedDistance + 2.0F;
            }
            float follow2 = follow * follow;
            int t = d.targetId[i];
            if (t >= 0 && d.aliveFlag[t] && d.hp[t] > 0f && d.team[t] != d.team[i]) {
                float ox = d.posX[t] - d.posX[i];
                float oz = d.posZ[t] - d.posZ[i];
                if (ox * ox + oz * oz <= follow2) {
                    continue;   // 目标仍有效
                }
            }
            d.targetId[i] = -1;
            if (!aiScanThrottled) {
                // 只给离线对照实验用：复现「每 tick 都重新索敌」的旧行为
                d.targetId[i] = findNearestEnemy(i, follow2);
                continue;
            }
            if (d.retargetCd[i] > 0) {
                d.retargetCd[i]--;
                continue;
            }
            d.retargetCd[i] = RETARGET_INTERVAL;
            d.targetId[i] = findNearestEnemy(i, follow2);
        }
    }

    /**
     * aiTick 的索敌是否按 {@code retargetCd} 节流（**默认 true = 线上行为**）。
     *
     * <p>只给离线对照实验用：关掉它就是旧行为（每单位每 tick 全量半径扫描），
     * 于是 ProfilerHarness 能给出「优化前 vs 优化后」的真实倍数。
     */
    private boolean aiScanThrottled = true;

    public void setAiScanThrottled(boolean throttled) {
        this.aiScanThrottled = throttled;
    }

    public boolean aiScanThrottled() {
        return aiScanThrottled;
    }

    /** 在 follow 半径内找最近的敌对单位（借助空间哈希，逐格扫描）。 */
    private int findNearestEnemy(int i, float radius2) {
        CrowdData d = data;
        if (!grid.valid) {
            return -1;
        }
        HordeProfiler.noteEnemyScan();
        HordeProfiler.noteAiScan();
        long scanStart = HordeProfiler.begin();
        float radius = (float) Math.sqrt(radius2);
        int gw = grid.gridWidth();
        int gh = grid.gridHeight();
        float bx = d.posX[i];
        float bz = d.posZ[i];
        // ★ 扫描范围必须**以单位自己所在的格子为中心**按半径换算，不能写成
        //   {@code cellX(pos - radius) .. cellX(pos + radius)}：
        //   {@link SpatialGrid#cellX} 会把越界值**夹取**到 0，而网格原点其实是
        //   {@code minPos - cellSize} —— 于是「pos - radius」为负时左侧边界被夹成 0，
        //   而该单位的真实格子是 1~2，扫描窗口整体左移，**右侧的邻居被漏掉**。
        //   真机表现：贴着地图低 x / 低 z 一侧的单位明明身边有人却「看不见敌人、站着不动」。
        //   （同一份代码里 applySkill / blastAt / crowdHitResolver 用的都是「中心格 ± span」，
        //     只有这两个索敌函数写成了边界夹取 —— 这就是不一致的来源。）
        int ccx = grid.cellX(bx);
        int ccz = grid.cellZ(bz);
        int c0x = ccx - Math.max(0, ccx - grid.cellX(bx - radius));
        int c1x = ccx + Math.max(0, grid.cellX(bx + radius) - ccx);
        int c0z = ccz - Math.max(0, ccz - grid.cellZ(bz - radius));
        int c1z = ccz + Math.max(0, grid.cellZ(bz + radius) - ccz);
        int best = -1;
        float bestD = radius2;
        int looked = 0;

        for (int cz = c0z; cz <= c1z; cz++) {
            for (int cx = c0x; cx <= c1x; cx++) {
                int ci = cz * gw + cx;
                int s = grid.start(ci);
                int e = grid.end(ci);
                for (int k = s; k < e; k++) {
                    int j = grid.item(k);
                    if (j == i) {
                        continue;
                    }
                    // ★「候选」= 扫描到的**格子里的单位个数**（含友军）。
                    //   为什么把友军也计进去：这是「这次扫描的空间代价」的直接度量
                    //   （每 tick 白扫的代价就是这个数 × 扫描次数）。
                    //   真机两队混战时，友军也占绝大多数格位，所以这个口径更接近真实开销。
                    looked++;
                    if (!d.aliveFlag[j] || d.hp[j] <= 0f || d.team[j] == d.team[i]) {
                        continue;
                    }
                    float ox = d.posX[j] - bx;
                    float oz = d.posZ[j] - bz;
                    float dist2 = ox * ox + oz * oz;
                    if (dist2 <= bestD) {
                        bestD = dist2;
                        best = j;
                    }
                }
            }
        }
        HordeProfiler.noteEnemyCandidates(looked);
        if (scanStart != 0L) {
            HordeProfiler.noteEnemyScanNanos(System.nanoTime() - scanStart);
        }
        return best;
    }

    private void cachePlayers(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        int n = players.size();
        if (playerX.length < n) {
            playerX = new float[n];
            playerZ = new float[n];
        }
        playerCount = n;
        for (int i = 0; i < n; i++) {
            ServerPlayer p = players.get(i);
            playerX[i] = (float) p.getX();
            playerZ[i] = (float) p.getZ();
        }
    }

    private int lodPeriod(int slot) {
        if (playerCount == 0) {
            return 8;
        }
        float x = data.posX[slot];
        float z = data.posZ[slot];
        float best = Float.MAX_VALUE;
        for (int p = 0; p < playerCount; p++) {
            float dx = x - playerX[p];
            float dz = z - playerZ[p];
            float d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
            }
        }
        float near = HordeConfig.safeGet(HordeConfig.COMMON.lodNearDistance, 32.0F);
        float mid = HordeConfig.safeGet(HordeConfig.COMMON.lodMidDistance, 64.0F);
        float far = HordeConfig.safeGet(HordeConfig.COMMON.lodFarDistance, 128.0F);
        if (best <= near * near) {
            return 1;
        }
        if (best <= mid * mid) {
            return 2;
        }
        if (best <= far * far) {
            return 4;
        }
        return 8;
    }

    // ------------------------------------------------------------------
    // 流场
    // ------------------------------------------------------------------

    private void recomputeFlow() {
        for (int t = 0; t < MAX_TEAMS; t++) {
            flowSumX[t] = 0.0D;
            flowSumZ[t] = 0.0D;
            flowCnt[t] = 0;
        }
        float bMinX = Float.MAX_VALUE;
        float bMaxX = -Float.MAX_VALUE;
        float bMinZ = Float.MAX_VALUE;
        float bMaxZ = -Float.MAX_VALUE;

        for (int i = 0; i < data.highWater; i++) {
            if (!data.aliveFlag[i]) {
                continue;
            }
            int t = data.team[i] & 0xFF;
            if (t < 0 || t >= MAX_TEAMS) {
                t = 0;
            }
            flowSumX[t] += data.posX[i];
            flowSumZ[t] += data.posZ[i];
            flowCnt[t]++;
            if (data.posX[i] < bMinX) bMinX = data.posX[i];
            if (data.posX[i] > bMaxX) bMaxX = data.posX[i];
            if (data.posZ[i] < bMinZ) bMinZ = data.posZ[i];
            if (data.posZ[i] > bMaxZ) bMaxZ = data.posZ[i];
        }

        float cx = (bMinX + bMaxX) * 0.5f;
        float cz = (bMinZ + bMaxZ) * 0.5f;
        float w = Math.max(32.0f, (bMaxX - bMinX) + 32.0f);
        float h = Math.max(32.0f, (bMaxZ - bMinZ) + 32.0f);

        for (int t = 0; t < teamCount; t++) {
            flow[t].configure(cx, cz, w, h);
            double ex = 0.0D;
            double ez = 0.0D;
            int ec = 0;
            for (int o = 0; o < teamCount; o++) {
                if (o == t) {
                    continue;
                }
                ex += flowSumX[o];
                ez += flowSumZ[o];
                ec += flowCnt[o];
            }
            if (ec == 0) {
                flow[t].clear();
                continue;
            }
            flow[t].setGoal((float) (ex / ec), (float) (ez / ec));
            flow[t].compute();
        }
    }

    // ------------------------------------------------------------------
    // 物理：重力 / 摩擦 / 碰撞 / 台阶 / 跳跃（对齐原版 travel）
    // ------------------------------------------------------------------

    private void physicsTick() {
        CrowdData d = data;
        float speedModifier = HordeConfig.safeGet(HordeConfig.COMMON.speedModifier, 1.0F);
        boolean useAttrs = HordeConfig.safeGet(HordeConfig.COMMON.useEntityAttributes, true);

        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            // 原版：moveRelative(速度, (0,0,zza))，其中 zza = speedModifier × MOVEMENT_SPEED 属性值，
            // 因此每次加速量为 v²，方向为当前朝向。速度按每个单位自己的类型取。
            TypeStats moveStats = useAttrs ? statsForSlot(i) : null;
            float unitSpeed = moveStats != null ? moveStats.moveSpeed : ZOMBIE_ATTRIBUTE_SPEED;
            float v = unitSpeed * speedModifier;
            float accel = v * v;
            d.age[i]++;

            // 移动意图：优先直奔自己的目标（原版 MeleeAttackGoal 即使在攻击距离内也会继续寻路），
            // 无目标时退回团队流场。
            //
            // ★ 远程单位例外：它按**自己的攻击模式**决定站位（见 RangedKiting）。
            //   真机病根就在这里 —— 旧的移动意图只有「直奔目标」一条，
            //   于是骷髅一路顶到脸上近战（日志里射击距离 1.98~16 格都有）。
            //   现在：目标在理想带外 -> 靠近到理想距离；带内 -> 驻留（站着射）；
            //   比最小开火距离还近 -> 后撤。**绝不主动贴脸**。
            float dirX = 0f;
            float dirZ = 0f;
            int target = d.targetId[i];
            if (target >= 0 && d.aliveFlag[target] && d.hp[target] > 0f && d.team[target] != d.team[i]) {
                float ox = d.posX[target] - d.posX[i];
                float oz = d.posZ[target] - d.posZ[i];
                float len = (float) Math.sqrt(ox * ox + oz * oz);
                if (len > 1.0E-3f) {
                    float ux = ox / len;
                    float uz = oz / len;
                    boolean retreat = false;
                    RangedKiting.Rule kite = moveStats != null ? moveStats.kiting : null;
                    RangedKiting.Move m;
                    if (kite != null && moveStats.ranged()) {
                        // ---- 远程单位：**原地开火优先** ----
                        // 只有「弹道射程之外」或「有遮挡」才产生位移，其余一律站着射。
                        // 见 RangedKiting.decide（followRange 不参与）。
                        m = RangedKiting.decide(kite, moveStats, i, len);
                    } else {
                        // 近战单位：保持「直奔目标」的旧语义
                        m = RangedKiting.Move.APPROACH;
                    }
                    // 后撤时被方块挡住 = 退路没了，这时才允许迎上去（原版骷髅被堵墙角也会贴脸）
                    if (m == RangedKiting.Move.RETREAT
                            && d.horizontalCollision[i] && d.onGround[i]) {
                        m = RangedKiting.Move.APPROACH;
                    }
                    // 无视线时优先**侧移**找视线（而不是顶上去贴脸）。
                    // 上一帧的开火尝试会把结论写进 rangedLosMove；这里消费它。
                    RangedKiting.Move los = rangedLosMove[i];
                    if (m == RangedKiting.Move.HOLD && los == RangedKiting.Move.STRAFE_LOS) {
                        // 向左/向右按 slot 奇偶固定（确定性，不会每 tick 抖）
                        float side = (i & 1) == 0 ? 1.0F : -1.0F;
                        dirX = -uz * side;
                        dirZ = ux * side;
                    } else if (m != RangedKiting.Move.HOLD) {
                        float s = m == RangedKiting.Move.RETREAT ? -1.0F : 1.0F;
                        dirX = ux * s;
                        dirZ = uz * s;
                        retreat = m == RangedKiting.Move.RETREAT;
                    }
                    if (dirX != 0f || dirZ != 0f) {
                        // 原版 MoveControl.rotlerp：永远**面朝目标**（后撤是倒着走，不是转身跑）。
                        // 这一点很关键：射击时的朝向同步读的就是 d.yaw，转身跑会让箭朝反方向飞。
                        float targetYaw = (float) Math.toDegrees(Math.atan2(-ox, oz));
                        d.yaw[i] = rotlerp(d.yaw[i], targetYaw, 90f);
                    }
                    noteKiting(i, len, kite, retreat);
                } else {
                    // ---- 近战单位：与改动之前完全一致（直奔目标）----
                    if (dirX != 0f || dirZ != 0f) {
                        float targetYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
                        d.yaw[i] = rotlerp(d.yaw[i], targetYaw, 90f);
                    }
                }
            }
            if (dirX == 0f && dirZ == 0f) {
                int team = d.team[i] & 0xFF;
                if (team < teamCount) {
                    FlowField f = flow[team];
                    if (f != null && f.sample(d.posX[i], d.posZ[i], sampleOut)) {
                        dirX = sampleOut[0];
                        dirZ = sampleOut[1];
                    }
                }
            }

            boolean idle = dirX == 0f && dirZ == 0f
                    && Math.abs(d.velX[i]) < 1.0E-4f
                    && Math.abs(d.velZ[i]) < 1.0E-4f
                    && d.onGround[i];
            if (idle) {
                // 站立状态：原版 motionY 恒为 -0.0784000015258789
                d.velX[i] = 0f;
                d.velZ[i] = 0f;
                d.velY[i] = -GRAVITY * 0.98f;
                d.walkSpeed[i] += (0f - d.walkSpeed[i]) * WALK_LERP;
                d.walkPos[i] += d.walkSpeed[i];
                wrapWalk(d, i);
                tickTimers(d, i);
                d.prevX[i] = d.posX[i];
                d.prevZ[i] = d.posZ[i];
                continue;
            }

            // 原版 MoveControl.rotlerp：每 tick 最多转 90°
            if (dirX != 0f || dirZ != 0f) {
                float targetYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
                d.yaw[i] = rotlerp(d.yaw[i], targetYaw, 90f);
            }

            // moveRelative：沿朝向加速
            if (dirX != 0f || dirZ != 0f) {
                d.velX[i] += dirX * accel;
                d.velZ[i] += dirZ * accel;
            }

            // move(SELF, deltaMovement)
            setBox(i);
            float dx = d.velX[i];
            float dy = d.velY[i];
            float dz = d.velZ[i];
            // 远处单位跳过昂贵的台阶尝试（不可见，视觉无差别）
            boolean allowStepUp = lodPeriod(i) <= 2;
            if (blockWorld == null) {
                // 没有方块世界（离线 harness）：直接按速度位移，不做地形碰撞。
                // 线上永远走 else 分支（tick 一开始就会建好 blockWorld），
                // 这一步只是让「同一个主循环」在没有世界的环境里也能跑完。
                moveDelta[0] = dx;
                moveDelta[1] = dy;
                moveDelta[2] = dz;
                box[0] += dx;
                box[1] += dy;
                box[2] += dz;
                box[3] += dx;
                box[4] += dy;
                box[5] += dz;
                moveFlags[0] = false;
                moveFlags[1] = false;
            } else {
                blockWorld.move(box, dx, dy, dz, d.onGround[i], allowStepUp, moveDelta, moveFlags);
            }

            float movedX = moveDelta[0];
            float movedY = moveDelta[1];
            float movedZ = moveDelta[2];
            // posX/posZ 直接存 AABB 最小角，避免「中心 ± 0.3」往返带来的浮点残差
            d.posX[i] = box[0];
            d.posY[i] = box[1];
            d.posZ[i] = box[2];

            boolean blockedX = movedX != dx;
            boolean blockedZ = movedZ != dz;
            boolean blockedDown = movedY != dy && dy < 0f;

            // 原版 Entity.move：被挡住的水平分量清零
            if (blockedX) {
                d.velX[i] = 0f;
            }
            if (blockedZ) {
                d.velZ[i] = 0f;
            }
            // 落地：原版着地后 motionY 归零（表现为 -0.0784000015258789）
            if (blockedDown) {
                d.velY[i] = 0f;
            }

            d.onGround[i] = blockedDown || (d.onGround[i] && movedY == 0f && dy <= 0f);
            d.horizontalCollision[i] = blockedX || blockedZ;

            // 被水平挡住且在地面 -> 原版 MoveControl 的跳跃
            if (d.onGround[i] && (blockedX || blockedZ) && (dirX != 0f || dirZ != 0f)) {
                d.velY[i] = jumpPower();
            }

            // travel 尾段：摩擦 + 重力
            float friction = d.onGround[i] ? BLOCK_FRICTION * 0.91f : 0.91f;
            float d2 = d.velY[i] - GRAVITY;
            d.velX[i] *= friction;
            d.velZ[i] *= friction;
            d.velY[i] = d2 * 0.98f;

            // 原版 calculateEntityAnimation(false)：只用水平位移
            float adx = d.posX[i] - d.prevX[i];
            float adz = d.posZ[i] - d.prevZ[i];
            float moved = (float) Math.sqrt(adx * adx + adz * adz);
            updateWalkAnimation(d, i, moved);
            d.prevX[i] = d.posX[i];
            d.prevZ[i] = d.posZ[i];
            d.state[i] = CrowdData.STATE_MOVING;
            tickTimers(d, i);
        }
    }

    private float jumpPower() {
        return 0.42f;
    }

    /** 碰撞盒按该单位自己类型的真实尺寸（EntityType 的 dimensions） */
    private void setBox(int i) {
        TypeStats stats = statsForSlot(i);
        float w = stats != null ? stats.width : WIDTH;
        float h = stats != null ? stats.height : HEIGHT;
        float x = data.posX[i];
        float y = data.posY[i];
        float z = data.posZ[i];
        box[0] = x;
        box[1] = y;
        box[2] = z;
        box[3] = x + w;
        box[4] = y + h;
        box[5] = z + w;
    }

    /** 原版 WalkAnimationState.update(min(f*4,1), 0.4F) */
    private void updateWalkAnimation(CrowdData d, int i, float horizontalDistance) {
        float target = Math.min(horizontalDistance * 4.0F, 1.0F);
        d.walkSpeed[i] += (target - d.walkSpeed[i]) * WALK_LERP;
        d.walkPos[i] += d.walkSpeed[i];
        wrapWalk(d, i);
    }

    private void wrapWalk(CrowdData d, int i) {
        if (d.walkPos[i] >= WALK_WRAP) {
            d.walkPos[i] -= WALK_WRAP * (float) Math.floor(d.walkPos[i] / WALK_WRAP);
        } else if (d.walkPos[i] < 0f) {
            d.walkPos[i] = 0f;
        }
    }

    /** 原版 hurtTime / swingTime（updateSwingTime）计时 */
    private void tickTimers(CrowdData d, int i) {
        if (d.hurtTime[i] > 0) {
            d.hurtTime[i]--;
        }
        if (d.swinging[i]) {
            d.swingTime[i]++;
            if (d.swingTime[i] >= SWING_DURATION) {
                d.swingTime[i] = 0;
                d.swinging[i] = false;
            }
        } else {
            d.swingTime[i] = 0;
        }
    }

    /** 原版 LivingEntity.swing(InteractionHand.MAIN_HAND) */
    private void swing(CrowdData d, int i) {
        if (!d.swinging[i] || d.swingTime[i] >= SWING_DURATION / 2 || d.swingTime[i] < 0) {
            d.swingTime[i] = -1;
            d.swinging[i] = true;
        }
    }

    private static float rotlerp(float from, float to, float maxDelta) {
        float f = wrapDegrees(to - from);
        if (f > maxDelta) {
            f = maxDelta;
        }
        if (f < -maxDelta) {
            f = -maxDelta;
        }
        float r = from + f;
        if (r < 0f) r += 360f;
        else if (r > 360f) r -= 360f;
        return r;
    }

    private static float wrapDegrees(float v) {
        v = v % 360f;
        if (v >= 180f) v -= 360f;
        if (v < -180f) v += 360f;
        return v;
    }

    // ------------------------------------------------------------------
    // 战斗
    // ------------------------------------------------------------------

    private void resolveCombat() {
        CrowdData d = data;
        boolean useAttrs = HordeConfig.safeGet(HordeConfig.COMMON.useEntityAttributes, true);
        float cfgRange = HordeConfig.safeGet(HordeConfig.COMMON.attackRange, 1.1F);
        float cfgDmg = HordeConfig.safeGet(HordeConfig.COMMON.unitDamage, 3.0F);
        int cfgCd = HordeConfig.safeGet(HordeConfig.COMMON.attackCooldownTicks, 18);
        float cfgFollow = HordeConfig.safeGet(HordeConfig.COMMON.followRange, 16.0F);

        deathCount = 0;
        // 上一 tick 的「侧移找视线」结论已经被 physicsTick 消费过了：先复位（默认原地开火）。
        // 覆盖到 highWater 即可（只有活着的槽位会被开火尝试写）。
        int losReset = Math.min(d.highWater, rangedLosMove.length);
        for (int k = 0; k < losReset; k++) {
            rangedLosMove[k] = RangedKiting.Move.HOLD;
        }
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            int period = lodPeriod(i);
            if ((tick + i) % period != 0L) {
                continue;
            }
            TypeStats atkStats = useAttrs ? statsForSlot(i) : null;
            // ★ 「目标值不值得打」的距离：只用来判断**保持锁定**与**重新锁定**。
            //   它**不**决定近战能不能打到（那由 meleeRangeOk 按双方碰撞箱现算）——
            //   上一版把这两件事绑在同一个 attackReach 上（远程类型 = 16 格），
            //   于是十几格外也能结算近战，正是「手太长」。
            float range = atkStats != null ? atkStats.attackReach : cfgRange;
            float range2 = range * range;
            int cd = atkStats != null ? atkStats.attackCooldownTicks : cfgCd;
            boolean isRanged = atkStats != null && atkStats.ranged();
            if (d.attackCd[i] > 0) {
                d.attackCd[i] -= period;
            }

            // 优先打自己的目标，超出近战范围则看邻格有没有更近的敌人
            int target = -1;
            int owned = d.targetId[i];
            if (owned >= 0 && d.aliveFlag[owned] && d.hp[owned] > 0f && d.team[owned] != d.team[i]) {
                float ox = d.posX[owned] - d.posX[i];
                float oz = d.posZ[owned] - d.posZ[i];
                if (ox * ox + oz * oz <= range2) {
                    target = owned;
                }
            }
            if (target < 0) {
                // ★ 兜底索敌的**搜索半径**：与 aiTick 完全同一个半径（followRange，
                //   远程单位还要覆盖射程），而不是近战判定距离。
                //
                // 为什么：旧代码这里写的是 range2 = attackReach²（僵尸约 0.45 格、1.1 格），
                // 于是「邻格兜底」实际上只能看到 3×3 格以内 —— 紧挨着的敌人都经常扫不到，
                // 「近战怪站着不动、贴着不打」正是从这儿来的。半径对齐之后，
                // aiTick 与这里的查询**逐位等价**（同样的数据、同样的半径），
                // 于是下面的节流在语义上完全等价于旧行为（而不是「换了个查询」）。
                float scan = Math.max(range, atkStats != null ? atkStats.followRange : cfgFollow);
                if (atkStats != null && atkStats.ranged()
                        && atkStats.rangedDistance + 2.0F > scan) {
                    scan = atkStats.rangedDistance + 2.0F;
                }
                float scan2 = scan * scan;
                // ★ 战斗阶段的兜底索敌**必须节流**（用与 aiTick 同一个 retargetCd）。
                //
                // 为什么：没有目标的单位（比如刚生成、或敌人在 followRange 之外）在旧代码里
                // **每 tick** 都会重新扫一遍（16 格半径 / 2 格格子 = 17×17 个格子），
                // 而 aiTick 每 10 tick 才扫一次 —— 多出来的 9 次完全是白扫，
                // 结果必然和 9 tick 前一样。这就是 findEnemy 成为「每帧全量半径扫描」的入口。
                //
                // 节流到 retargetCd（错峰，默认 10 tick）**不改变任何可见行为**：
                // aiTick 本来就在同一 tick 用同一份数据、同一个半径做过同样的查询并写下 targetId，
                // 这里只是把「明明没人却每 tick 重扫」变成「按索敌节奏扫」。
                //
                // {@link #setCombatScanThrottled(boolean)} 可以关掉节流（**只给离线对照实验用**：
                // ProfilerHarness 用它量出「优化前 vs 优化后」的扫描次数与耗时）。
                if (combatScanThrottled) {
                    if (d.retargetCd[i] > 0) {
                        d.retargetCd[i]--;
                    } else {
                        d.retargetCd[i] = COMBAT_SCAN_INTERVAL;
                        target = findEnemy(i, scan2);
                    }
                } else {
                    target = findEnemy(i, scan2);
                }
            }
            if (target >= 0) {
                // 原版 LookControl：头部转向目标
                aimHead(d, i, target);
                if (d.attackCd[i] <= 0) {
                    // ---- 攻击模式：**远程与近战彻底分开** ----
                    //
                    // ★ 门槛判为远程的类型**永远不进近战分支**。
                    //   玩家原话：「远程攻击需要在原地就能向远距离的目标释放对应的攻击，
                    //   比如骷髅可以直接向极远的目标直接发射箭矢，不会自己跑过去近战」。
                    //   所以这里只问一件事：这一帧能不能开火（弹道射程 + 视线 + 冷却），
                    //   能就原地开火；不能就什么都不做（位移由 physicsTick 按 RangedKiting 决定）。
                    //   以前那个「贴脸就切近战」的兜底被删掉了 —— 它的副作用正是
                    //   「远程单位为了攻击而位移过去、最后变成近战」。
                    if (isRanged) {
                        rangedInjector.attack(this, d, i, atkStats, period);
                    } else {
                        // ---- 近战：**结算前再判一次可达距离**（由双方碰撞箱推导）----
                        //
                        // 为什么必须再判一次：上面的索敌半径可以很宽（followRange/远程射程），
                        // 而「目标值得不值得打」与「这一下够不够得着」是两件事。
                        // 上一版把两者绑在同一个 attackReach 上，于是十几格外也能结算近战。
                        TypeStats defStats = statsForSlot(target);
                        if (!meleeRangeOk(d, i, target, atkStats, defStats)) {
                            meleeRejectedByRange++;
                            // 「远程类型落进近战分支」是另一种缺陷（旧路径的指纹），单独计数：
                            // 真机上它必须是 0 —— 门槛判远程的类型根本不该出现在这里。
                            if (isRanged) {
                                rangedMeleeAttempts++;
                            }
                            if (meleeDebug) {
                                logInfo(String.format(java.util.Locale.ROOT,
                                        "[horde][debug] 近战被距离拒绝：slot %d -> %d，isRanged=%s，"
                                                + "水平距 %.2f，可达 %.2f",
                                        i, target, isRanged, horizontalDistance(d, i, target),
                                        meleeRangeOf(i, target)));
                            }
                            continue;
                        }                        logMeleeRangeOnce(atkStats, defStats);
                        d.attackCd[i] = cd;
                        d.state[i] = CrowdData.STATE_ATTACKING;
                        swing(d, i);
                        meleeHit(d, i, target, atkStats, cfgDmg);
                    }
                }
            } else if (d.headYaw[i] != 0f || d.headPitch[i] != 0f) {
                d.headYaw[i] *= 0.6f;
                d.headPitch[i] *= 0.6f;
                if (Math.abs(d.headYaw[i]) < 0.1f) d.headYaw[i] = 0f;
                if (Math.abs(d.headPitch[i]) < 0.1f) d.headPitch[i] = 0f;
            }
        }

        for (int k = 0; k < deathCount; k++) {
            d.free(deathScratch[k]);
        }
        killedTotal += deathCount;
        deathCount = 0;
    }

    /**
     * 近战一次的伤害结算（走目标护甲的原版公式）。
     *
     * 抽成方法不是为了复用，而是为了让 {@link #resolveCombat} 里「远/近二选一」的结构一眼可见：
     * 远程走 {@link #rangedInjector}，近战走这里，两条路互斥 ——
     * 所以同一次攻击**不可能**既砍又射。
     */
    private void meleeHit(CrowdData d, int i, int target, TypeStats atkStats, float cfgDmg) {
        if (d.hp[target] <= 0f) {
            return;
        }
        float dmg = atkStats != null ? atkStats.damage : cfgDmg;
        // 原版伤害结算里的护甲减免（CombatRules.getDamageAfterAbsorb）
        TypeStats defStats = statsForSlot(target);
        if (defStats != null && (defStats.armor > 0.0F || defStats.armorToughness > 0.0F)) {
            dmg = CombatRules.getDamageAfterAbsorb(dmg, defStats.armor, defStats.armorToughness);
        }
        float nhp = d.hp[target] - dmg;
        d.hp[target] = nhp;
        d.hurtTime[target] = HURT_DURATION;
        noteDamage("melee:" + fmt(dmg));
        if (nhp <= 0f) {
            queueDeath(target);
        }
    }

    // ------------------------------------------------------------------
    // 近战可达距离：**由双方碰撞箱推导**，结算前再判一次
    //
    // 真机反馈「近战的攻击范围出现错误，手太长」。
    // 根因：索敌半径（followRange / attackReach，可达 16 格）与「能不能打到」是同一个变量，
    // 于是十几格外也能结算。现在两者彻底分开：
    //   * 索敌可以很宽（用于发现/重新锁定）；
    //   * **结算伤害必须再过一次 meleeRangeOk**（按双方碰撞箱现算）。
    // ------------------------------------------------------------------

    /**
     * 近战可达判定：**由双方碰撞箱推导**（水平外扩 + 垂直重叠）。
     *
     * <pre>
     *   水平中心距 d = hypot(ox, oz)
     *   可达上界     = (我的宽度 + 目标宽度)/2 + 0.5·(我的宽度 + 目标宽度)
     *                = 半宽和 + 0.5·宽和        ← 原版 getMeleeAttackRangeSqr 的口径
     *   垂直         = 两个碰撞箱在 Y 方向必须有重叠（否则站在塔顶也能砍到塔底）
     * </pre>
     *
     * <p>对原版僵尸（0.6 宽）打原版僵尸：{@code 0.6 + 0.6 = 1.2 格}；
     * 打末影龙（16 宽）：{@code (0.6+16)/2 + 0.5·(0.6+16) = 16.6 格}
     * —— 大体型天然更好打，这正是「由碰撞箱推导」与「按 followRange 档位」的区别。
     *
     * @return true = 这一帧允许结算近战伤害
     */
    private boolean meleeRangeOk(CrowdData d, int attacker, int target, TypeStats atkStats, TypeStats defStats) {
        float aw = atkStats != null ? atkStats.halfWidth * 2.0F : WIDTH;
        float tw = defStats != null ? defStats.halfWidth * 2.0F : WIDTH;
        float ah = atkStats != null ? atkStats.height : HEIGHT;
        float th = defStats != null ? defStats.height : HEIGHT;
        float maxCenter = TypeStats.meleeRangeValue(aw, tw);
        // 水平：中心距（**不是** AABB 最小角距 —— 后者会把「站在斜前方」算近 0.3 格）
        float ox = d.posX[target] - d.posX[attacker];
        float oz = d.posZ[target] - d.posZ[attacker];
        float horiz2 = ox * ox + oz * oz;
        if (horiz2 > maxCenter * maxCenter) {
            return false;
        }
        // 垂直：两个碰撞箱必须真的重叠
        float aBot = d.posY[attacker];
        float aTop = aBot + ah;
        float bBot = d.posY[target];
        float bTop = bBot + th;
        return aTop >= bBot && bTop >= aBot;
    }

    /** 该 (攻击者, 目标) 组合的近战可达距离（格）—— 日志与离线断言用 */
    private float meleeRangeOf(int attacker, int target) {
        TypeStats a = statsForSlot(attacker);
        TypeStats b = statsForSlot(target);
        return TypeStats.meleeRangeValue(a != null ? a.width : WIDTH, b != null ? b.width : WIDTH);
    }

    /**
     * 本 tick 被「近战可达距离」拒绝的结算次数 —— **真机上应当是 0**。
     *
     * <p>怎么用：如果这个数一直涨，说明还有别的路径能在可达距离之外结算
     * （或者有单位被索敌半径带着在很远的地方「够」到了目标）。它是这次修复的诊断锚点。
     */
    private long meleeRejectedByRange;

    public long meleeRejectedByRange() {
        return meleeRejectedByRange;
    }

    /**
     * **远程类型**落进近战分支的次数（任意原因）—— 真机上必须恒为 0。
     *
     * <p>它比 {@link #meleeRejectedByRange} 更严格：后者是「近战想打但够不着」，
     * 属于正常的距离把关；这个计数只要非 0，就说明远程单位又走到了砍人那条路上
     * （玩家原话：「不会自己跑过去近战」）。
     */
    private long rangedMeleeAttempts;

    public long rangedMeleeAttempts() {
        return rangedMeleeAttempts;
    }

    public void resetMeleeDiagnostics() {
        meleeRejectedByRange = 0L;
        rangedMeleeAttempts = 0L;
    }

    /** 近战距离拒绝的逐条 DEBUG 日志开关（默认关；排查「谁还在超距结算」时临时打开） */
    private boolean meleeDebug;

    public void setMeleeDebug(boolean on) {
        this.meleeDebug = on;
    }

    /** 两个槽位的水平中心距（诊断用） */
    private static float horizontalDistance(CrowdData d, int a, int b) {
        float ox = d.posX[b] - d.posX[a];
        float oz = d.posZ[b] - d.posZ[a];
        return (float) Math.sqrt(ox * ox + oz * oz);
    }

    /**
     * 近战范围**限频日志**（每个攻击者类型一行）。
     *
     * <p>格式与真机日志一致：
     * {@code 类型 X -> 近战范围 A 格（由碰撞箱推导：攻击者 w×h、目标 w×h）}
     * 说明的 `A` 是**打同体型对手**时的可达距离；打大体型目标会更大（公式见 {@link #meleeRangeOk}）。
     */
    private void logMeleeRangeOnce(TypeStats atk, TypeStats def) {
        if (atk == null || atk.casterId == null) {
            return;
        }
        if (!MELEE_LOGGED.add(atk.casterId)) {
            return;
        }
        float same = TypeStats.meleeRangeValue(atk.width, atk.width);
        float pair = def != null ? TypeStats.meleeRangeValue(atk.width, def.width) : same;
        logInfo("[horde] 类型 %s -> 近战范围 %.2f 格（由碰撞箱推导：攻击者 %.2f×%.2f、目标 %.2f×%.2f；"
                        + "对同体型 %.2f 格、对当前目标 %.2f 格）",
                atk.casterId, pair, atk.width, atk.height,
                def != null ? def.width : atk.width, def != null ? def.height : atk.height, same, pair);
    }

    /** 「已经打过近战范围那一行」的攻击者类型（每个类型一次） */
    private static final java.util.Set<ResourceLocation> MELEE_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // 离线验证的接线口（**public**：src/harness 在另一个包，包级可见会取不到；
    // 与 ProjectileManager.setKindResolver / tickPure 是同一套「把外部环境做成可注入」的做法）
    //
    // 为什么这些口子放在生产类里而不是另写一份测试专用逻辑：
    // 要验证的正是**线上这条链**（远程判定 -> 瞄准 -> 发射 -> 命中），
    // 另写一份就变成了「验证另一份实现」，那就白验了。
    // 这些方法都很小、语义清晰（等于把测试需要的那几个 private 字段暴露出来），
    // 不会给线上路径增加任何分支。
    // ------------------------------------------------------------------

    /** 直接指定某个槽位的类型属性（离线 harness 不想走注册表读属性时用） */
    public void setStatsOverride(int slot, TypeStats stats) {
        if (statsOverride == null || slot >= statsOverride.length) {
            statsOverride = Arrays.copyOf(statsOverride == null ? new TypeStats[0] : statsOverride,
                    Math.max(slot + 1, data.capacity));
        }
        statsOverride[slot] = stats;
    }

    /** 直接登记一个类型（不读注册表属性，用 {@link #setStatsOverride} 给属性） */
    public void registerTypeStub(ResourceLocation id, int typeIndex) {
        ResourceLocation placeholder = ResourceLocation.tryParse("minecraft:air");
        while (typeIds.size() <= typeIndex) {
            typeIds.add(placeholder);
            typeStats.add(null);
        }
        typeIds.set(typeIndex, id);
        typeIndexMap.put(id, typeIndex);
    }

    /** 直接填一个单位（离线 harness 用；不经过 spawn 的随机散布） */
    public void putUnitRaw(int slot, int typeIndex, float x, float y, float z, int team, float hp) {
        CrowdData d = data;
        while (d.highWater <= slot) {
            d.highWater++;
        }
        d.aliveFlag[slot] = true;
        d.alive++;
        d.hp[slot] = hp;
        d.maxHp[slot] = hp;
        d.posX[slot] = x;
        d.posY[slot] = y;
        d.posZ[slot] = z;
        d.prevX[slot] = x;
        d.prevZ[slot] = z;
        d.team[slot] = (byte) team;
        d.typeIndex[slot] = (byte) typeIndex;
        d.targetId[slot] = -1;
        // 与 spawn 一样错峰：否则所有单位会在第 1 tick 一起开火（真机上表现为「一开战就齐射」）
        TypeStats s = statsOverride != null && slot < statsOverride.length ? statsOverride[slot] : null;
        d.attackCd[slot] = s == null ? 0 : (slot * 7) % Math.max(1, s.attackCooldownTicks);
        d.skillCd[slot] = s == null ? 0 : (slot * 13) % Math.max(1, s.skillIntervalTicks);
        d.onGround[slot] = true;
        d.maxHp[slot] = s != null ? s.maxHp : hp;
        d.hp[slot] = d.maxHp[slot];
    }

    /** 直接发动一次远程攻击（离线 harness 用；绕过 aiTick/resolveCombat 的前置判定） */
    public void attackRangedNow(int slot) {
        TypeStats stats = statsForSlot(slot);
        rangedInjector.attack(this, data, slot, stats, 1);
    }

    /** 直接跑一次索敌 + 战斗（离线 harness 用） */
    public void resolveCombatNow() {
        grid.build(data);
        resolveCombat();
    }

    /** 清空伤害事件记录（离线 harness 用） */
    public void resetDamageEvents() {
        damageEvents.setLength(0);
    }

    /** 最近记录下来的伤害结算（"melee:3.00" / "ranged"…），用于证明「只结算一次」 */
    public String damageEvents() {
        return damageEvents.toString();
    }

    private int hitResolverCalls;

    private void noteDamage(String what) {
        if (damageEvents.length() > 0) {
            damageEvents.append('|');
        }
        damageEvents.append(what);
    }

    private void aimHead(CrowdData d, int i, int target) {
        float dx = d.posX[target] - d.posX[i];
        float dz = d.posZ[target] - d.posZ[i];
        float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        d.headYaw[i] = wrapDegrees(worldYaw - d.yaw[i]);
        // 眼睛高度也用各类型自己的真实值（原版视线就是从这里出发的）
        TypeStats mine = statsForSlot(i);
        TypeStats theirs = statsForSlot(target);
        float myEye = mine != null ? mine.eyeHeight : 1.6F;
        float targetEye = theirs != null ? theirs.eyeHeight : 1.5F;
        float dy = (d.posY[target] + targetEye) - (d.posY[i] + myEye);
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        d.headPitch[i] = (float) Math.toDegrees(Math.atan2(-dy, Math.max(horiz, 1.0E-4f)));
    }

    /**
     * 在 range 半径内找最近的敌人。
     *
     * 为什么要覆盖整个半径而不是原来的「邻格 3×3」：远程单位的攻击距离是 16 格量级，
     * 3×3 格（= 6 格）根本扫不到真实射程内的目标，于是「进不了攻击距离」—— 远程模式等于没生效。
     * 覆盖半径所需格数由 {@link SpatialGrid} 的格子尺寸换算，并设一个**格数上限**兜底：
     * 极端配置（雷达范围很大 + 格子很小）时退化成邻格扫描，绝不让一次查询把 TPS 吃掉。
     */
    private int findEnemy(int i, float range2) {
        CrowdData d = data;
        if (!grid.valid) {
            return -1;
        }
        HordeProfiler.noteEnemyScan();
        HordeProfiler.noteCombatScan();
        long scanStart = HordeProfiler.begin();
        float radius = (float) Math.sqrt(range2);
        int gw = grid.gridWidth();
        int gh = grid.gridHeight();
        float bx = d.posX[i];
        float bz = d.posZ[i];
        // 与 findNearestEnemy 同一套写法：以单位自己所在的格子为中心 ± span（**不能**用边界夹取，
        // 理由见 findNearestEnemy 的注释 —— 那会让扫描窗口整体错位、漏掉右侧邻居）。
        int ccx = grid.cellX(bx);
        int ccz = grid.cellZ(bz);
        int c0x = ccx - Math.max(0, ccx - grid.cellX(bx - radius));
        int c1x = ccx + Math.max(0, grid.cellX(bx + radius) - ccx);
        int c0z = ccz - Math.max(0, ccz - grid.cellZ(bz - radius));
        int c1z = ccz + Math.max(0, grid.cellZ(bz + radius) - ccz);
        int best = -1;
        float bestD = range2;
        int looked = 0;

        for (int ax = c0x; ax <= c1x; ax++) {
            if (ax < 0 || ax >= gw) {
                continue;
            }
            for (int az = c0z; az <= c1z; az++) {
                if (az < 0 || az >= gh) {
                    continue;
                }
                int nci = az * gw + ax;
                int s = grid.start(nci);
                int e = grid.end(nci);
                for (int k = s; k < e; k++) {
                    int j = grid.item(k);
                    if (j == i) {
                        continue;
                    }
                    // 与 findNearestEnemy 同一口径：候选 = 扫描到的格子里的单位个数（含友军），
                    // 它是「这次扫描的空间代价」的直接度量。
                    looked++;
                    if (!d.aliveFlag[j] || d.hp[j] <= 0f || d.team[j] == d.team[i]) {
                        continue;
                    }
                    float ox = d.posX[j] - bx;
                    float oz = d.posZ[j] - bz;
                    float d2 = ox * ox + oz * oz;
                    if (d2 <= bestD) {
                        bestD = d2;
                        best = j;
                    }
                }
            }
        }
        HordeProfiler.noteEnemyCandidates(looked);
        if (scanStart != 0L) {
            HordeProfiler.noteEnemyScanNanos(System.nanoTime() - scanStart);
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 原版实体互推（LivingEntity.pushEntities -> Entity.push）
    // ------------------------------------------------------------------

    private void pushEntities() {
        CrowdData d = data;
        float strength = HordeConfig.safeGet(HordeConfig.COMMON.pushStrength, 1.0F);
        if (strength <= 0f) {
            return;
        }
        if (sepHalfWidth.length < d.highWater) {
            int cap = Math.max(64, Math.max(d.highWater, sepHalfWidth.length * 2));
            sepHalfWidth = new float[cap];
            sepMass = new float[cap];
        }
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                sepHalfWidth[i] = 0.0F;
                sepMass[i] = 0.0F;
                continue;
            }
            TypeStats s = statsForSlot(i);
            if (s != null) {
                sepHalfWidth[i] = s.halfWidth;
                // 质量：体型 + 击退抗性 —— 大 boss 能挤开小怪
                sepMass[i] = Math.max(0.1F, s.width * s.height)
                        * (1.0F + 4.0F * Math.min(1.0F, s.knockbackResistance));
            } else {
                sepHalfWidth[i] = WIDTH * 0.5F;
                sepMass[i] = 1.0F;
            }
        }
        int passes = Math.max(1, HordeConfig.safeGet(HordeConfig.COMMON.collisionPasses, 2));
        CrowdSeparation.resolve(d, grid, sepHalfWidth, sepMass, strength, passes);
    }

    /**
     * 覆盖改了以后**立刻**重算该类型的属性并套到现存单位上（改属性不用重新生成）。
     * 模型/动作的覆盖由客户端重新烘焙网格生效。
     */
    public void reapply(ResourceLocation id) {
        Integer idxObj = typeIndexMap.get(id);
        if (idxObj == null || level == null) {
            return;
        }
        int idx = idxObj;
        TypeStats stats = TypeStats.of(level, id);
        while (typeStats.size() <= idx) {
            typeStats.add(null);
        }
        typeStats.set(idx, stats);
        CrowdData d = data;
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i] || (d.typeIndex[i] & 0x1F) != idx) {
                continue;
            }
            d.maxHp[i] = stats.maxHp;
            if (d.hp[i] > stats.maxHp) {
                d.hp[i] = stats.maxHp;
            }
            setBox(i);
        }
        logInfo("[horde] 已应用覆盖 {}：生命 {}、攻击 {}、移速 {}、尺寸 {}×{}、动作 {}、攻击模式 {}",
                id, stats.maxHp, stats.damage, stats.moveSpeed, stats.width, stats.height,
                stats.actionCount == 0 ? "无" : (stats.actionOverride < 0 ? "自动" : String.valueOf(stats.actionOverride)),
                stats.ranged() ? ("远程（" + stats.rangedDistance + " 格，间隔 " + stats.rangedInterval
                        + " tick，来源 " + (stats.hasConfiguredProjectiles ? "GUI 配置"
                        : "自动解析") + "）") : "近战");
    }

    /** 该类型当前解析出来的属性（GUI 用） */
    public TypeStats statsById(ResourceLocation id) {
        Integer idx = typeIndexMap.get(id);
        if (idx == null) {
            return null;
        }
        return statsOf(idx);
    }

    private void queueDeath(int slot) {
        if (deathCount >= deathScratch.length) {
            deathScratch = Arrays.copyOf(deathScratch, deathScratch.length * 2);
        }
        deathScratch[deathCount++] = slot;
    }

    // ------------------------------------------------------------------
    // 同步辅助
    // ------------------------------------------------------------------

    /**
     * 收集玩家附近的单位。
     *
     * @param range  半径（格）；&le; 0 表示**不限制距离**
     * @param budget 最多收集多少个；&le; 0 表示**不限制数量**（需要时会自动扩容缓冲）
     */
    public int collectVisible(float px, float pz, float range, int budget) {
        if (!grid.valid) {
            return 0;
        }
        int gw = grid.gridWidth();
        int gh = grid.gridHeight();
        boolean allRange = range <= 0.0F;
        int cx0 = allRange ? 0 : grid.cellX(px - range);
        int cx1 = allRange ? grid.gridWidth() - 1 : grid.cellX(px + range);
        int cz0 = allRange ? 0 : grid.cellZ(pz - range);
        int cz1 = allRange ? grid.gridHeight() - 1 : grid.cellZ(pz + range);
        float r2 = range * range;
        int count = 0;

        outer:
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int ci = cz * gw + cx;
                int s = grid.start(ci);
                int e = grid.end(ci);
                for (int k = s; k < e; k++) {
                    int j = grid.item(k);
                    if (!allRange) {
                        float ox = data.posX[j] - px;
                        float oz = data.posZ[j] - pz;
                        if (ox * ox + oz * oz > r2) {
                            continue;
                        }
                    }
                    if (budget > 0 && count >= budget) {
                        break outer;
                    }
                    if (count >= outSlots.length) {
                        // 不限制数量：按需扩容，绝不因为缓冲不够而少发
                        outSlots = Arrays.copyOf(outSlots, Math.max(count + 1, outSlots.length * 2));
                    }
                    outSlots[count++] = j;
                }
            }
        }
        return count;
    }
}
