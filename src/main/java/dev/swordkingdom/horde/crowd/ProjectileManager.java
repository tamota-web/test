package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.action.SkillParams;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟投掷物管理器 —— 与 {@link CrowdManager} 一一对应（每个维度一份）。
 *
 * <h2>为什么要有它</h2>
 * 群集单位不是真实实体，所以「骷髅射箭」「焰魔扔火球」这类技能原本无从表现。
 * 但**投掷物必须是虚拟的**（不能 spawn 真实实体）：真实投掷物会走原版 AI/物理/网络同步，
 * 一发一发地生成实体在上万单位规模下是灾难。
 * 于是这里用 SoA 纯数据模拟投掷物，客户端再按它自己的模型实例化渲染 ——
 * 「箭有箭的样子、火球有火球的样子」，而服务端一个真实实体都不生成。
 *
 * <h2>可注入的两件事</h2>
 * 只有两处需要外部世界：
 *   1. <b>方块碰撞</b>：{@link Adapter#resolveMove}（真实实现走 {@link BlockWorld}）；
 *   2. <b>命中谁</b>：{@link Hits#resolve}（真实实现走群集 {@link SpatialGrid} + 真实实体查询）。
 * 其余全部是纯数组数学（积分在 {@link SkillParams#integrate}），
 * 所以离线 harness 可以只注入两个替身回调就完整验证这段模拟。
 */
public final class ProjectileManager {

    /** 本模组硬性上限：单维度同时存在的投掷物数（防止配置写错导致内存/CPU 爆掉） */
    public static final int HARD_CAP = 8192;
    /** 单维度可用的投掷物类型上限（快照里用 5 bit 编码，与群集类型表同规格） */
    public static final int MAX_TYPES = 32;

    /** 命中判定时每 tick 最多检查多少个候选（防止极端情况下一次扫太多格子） */
    private static final int MAX_CANDIDATES = 64;

    // ------------------------------------------------------------------
    // 可注入接口
    // ------------------------------------------------------------------

    /** 方块碰撞：把「想走的位移」解算成「实际位移」。 */
    public interface Adapter {
        /**
         * @param pos   位置（长度 3，原地修改）：实体中心
         * @param delta 想走的位移（长度 3）
         * @param out   实际位移（长度 3）
         * @param vel   当前速度（长度 3）—— 实现方必须把「被方块吸收掉的分量」扣掉后写回这里，
         *              否则下一 tick 会拿旧速度继续算，重力与阻力等于白做
         * @return 是否撞到了方块
         */
        boolean resolveMove(float[] pos, float[] delta, float[] out, float[] vel);
    }

    /** 命中查询 + 结算：把「谁被打到了」这件事从模拟里摘出来。 */
    public interface Hits {
        /**
         * 扫掠命中判定 + 立即结算。实现方负责：
         *   1. 在 {@code [prev, cur]} 这条线段附近（半径 {@code hitRadius}）找**敌方**群集单位
         *      与真实实体 —— 起点是 {@link ProjectileData#prevX}/{@code prevY}/{@code prevZ}
         *      （由 {@link #step} 在积分前写入），**不要**用 {@code pos - vel} 反推；
         *   2. 对每个被扫到的目标应用伤害（走 {@link SkillParams#afterArmorFor} 的护甲减免）、击退；
         *   3. 返回「这一 tick 实际命中了几个彼此独立的目标」。
         *
         * <p>返回的每个目标记一次穿透消耗：返回 0 表示只有友军/没有目标，投掷物继续飞。</p>
         */
        int resolve(ProjectileData d, int slot, boolean applyBlast, float[] outScratch);
    }

    /** 什么都不命中的实现（离线验证/没有群集单位时） */
    public static final Hits NO_HITS = (d, slot, blast, scratch) -> 0;
    /** 不放行任何位移的实现（相当于空气墙，用于纯数学验证） */
    public static final Adapter FREE_MOVE = (pos, delta, out, vel) -> {
        out[0] = delta[0];
        out[1] = delta[1];
        out[2] = delta[2];
        pos[0] += delta[0];
        pos[1] += delta[1];
        pos[2] += delta[2];
        vel[0] = delta[0];
        vel[1] = delta[1];
        vel[2] = delta[2];
        return false;
    };

    // ------------------------------------------------------------------
    // 投掷物类型表
    // ------------------------------------------------------------------

    /** 一个投掷物类型的全部参数（默认值由**原版基类**推导，见 {@link ProjectileCatalog}） */
    public static final class ProjType {
        public final ResourceLocation id;
        /** 初速（格/tick） */
        public final float speed;
        /** 重力（每 tick；0 = 直线） */
        public final float gravity;
        /** 阻力（每 tick 乘数） */
        public final float drag;
        /** 寿命（tick） */
        public final int life;
        /** 命中半径（格） */
        public final float hitRadius;
        /** 与基本伤害的倍率（箭类 = 1，即施法者攻击力） */
        public final float damageScale;
        /** 命中后 AoE 半径（0 = 无） */
        public final float blastRadius;
        /** AoE 伤害倍率 */
        public final float blastScale;
        /** 可穿透次数（0 = 命中即消失） */
        public final int pierce;
        /** 撞方块后是否卡住 */
        public final boolean stickOnBlock;
        /** 默认发射仰角（度） */
        public final float pitch;
        /** 基类推导出来的分类（火球/箭/投掷物/其它），仅用于日志与 GUI 提示 */
        public final ProjectileCatalog.Kind kind;

        ProjType(ResourceLocation id, float speed, float gravity, float drag, int life,
                 float hitRadius, float damageScale, float blastRadius, float blastScale,
                 int pierce, boolean stickOnBlock, float pitch, ProjectileCatalog.Kind kind) {
            this.id = id;
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
            this.life = life;
            this.hitRadius = hitRadius;
            this.damageScale = damageScale;
            this.blastRadius = blastRadius;
            this.blastScale = blastScale;
            this.pierce = pierce;
            this.stickOnBlock = stickOnBlock;
            this.pitch = pitch;
            this.kind = kind;
        }
    }

    /**
     * 维度 id —— 刻意用 {@link ResourceLocation} 而不是
     * {@code ResourceKey<Level>}。
     *
     * 理由：{@code ResourceKey} 的类初始化会连带拉起 {@code Registries}
     * → {@code BuiltInRegistries}，而后者要求先 {@code Bootstrap.bootStrap()}。
     * 投掷物模拟本身完全不关心「维度对象的类型」，只需要一个能当 map 键的身份，
     * 用 {@code ResourceLocation} 就够，而且顺带让整段模拟能在**无注册表**的
     * 离线 harness 里跑（线上拿到的是同一个 id，行为不变）。
     */
    private final ResourceLocation dimension;
    /** SoA 数据体（全部投掷物字段都在这里，模拟过程零对象分配） */
    private final ProjectileData data;

    public ProjectileManager(ResourceLocation dimension) {
        this.dimension = dimension;
        int cap = Math.min(HARD_CAP,
                Math.max(256, HordeConfig.safeGet(HordeConfig.COMMON.maxProjectilesPerLevel, 2048)));
        this.data = new ProjectileData(cap);
    }

    private final List<ResourceLocation> typeIds = new ArrayList<>();
    private final Map<ResourceLocation, Integer> typeIndexMap = new HashMap<>();
    private final List<ProjType> types = new ArrayList<>();
    /**
     * **施法者**类型（群集单位类型）的索引表。
     *
     * 为什么要一份独立的表：投掷物这一段 SoA 里要记住「这发是谁发的」，
     * 而每个投掷物只用一个 5 bit 字段存施法者类型索引 ——
     * 但快照/客户端并不需要它，所以它只服务服务端的「按类型限量」。
     */
    private final Map<ResourceLocation, Integer> casterTypeIndexMap = new HashMap<>();

    /** 类型表版本号：变了就把映射重新下发给客户端（客户端要按 id 烘焙对应的模型） */
    private int typeVersion = 1;

    private Adapter adapter = FREE_MOVE;
    private Hits hits = NO_HITS;
    /** 分类解析：默认查注册表（按**原版基类**判定）；离线验证可以替换成替身实现 */
    private java.util.function.Function<ResourceLocation, ProjectileCatalog.Kind> kindResolver = null;
    /** 所属维度；用来做真实实体交互与方块适配（懒设，见 {@link ProjectileManagers#get}） */
    private ServerLevel level;
    /** 发射者傀儡（真实实体交互时作为 owner，用来判定敌我与击杀归属） */
    private Entity ownerProbe;
    /** 是否让投掷物顺带伤害真实实体 */
    private boolean hurtReal = HordeConfig.safeGet(HordeConfig.COMMON.projectileHurtRealEntities, true);
    /**
     * 每个**施法者类型**自己的同时在场投掷物上限（覆盖表可配；没有条目 = 用全局配置）。
     *
     * 为什么要按施法者类型分：一个「万箭齐发」型的骷髅和一枚 boss 大招火球，
     * 合理的数量级完全不同；全局一个数只能迁就其中一边。
     */
    private final Map<ResourceLocation, Integer> perTypeCap = new HashMap<>();

    /** 每 tick 生成上限（配置项；0 = 不限制） */
    private int spawnedThisTick;
    private int spawnCapThisTick;

    private long tick;
    private int spawnedTotal;
    private int expiredTotal;

    /** 复用的临时数组（零分配） */
    private final float[] delta = new float[3];
    private final float[] moved = new float[3];
    private final float[] pos = new float[3];
    /** 解算方写回的「本 tick 有效速度」 */
    private final float[] velScratch = new float[3];
    private final float[] scratch = new float[3];
    private final float[] radiusScratch = new float[8];

    /** 收集给某个玩家看的投掷物（与 CrowdManager.outSlots 同样的复用思路） */
    public int[] outSlots = new int[4096];

    public ProjectileData data() {
        return data;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public int aliveCount() {
        return data.alive;
    }

    public int spawnedTotal() {
        return spawnedTotal;
    }

    public int expiredTotal() {
        return expiredTotal;
    }

    public int typeVersion() {
        return typeVersion;
    }

    public List<ResourceLocation> typeIds() {
        return typeIds;
    }

    public ProjType typeOf(int index) {
        return index >= 0 && index < types.size() ? types.get(index) : null;
    }

    /**
     * 某个投掷物 id 的**基类默认弹道参数**（不改注册表、不建类型表）。
     *
     * <h2>为什么需要它</h2>
     * 远程单位的「距离保持」必须按**它自己那枚弹**的弹道来站（见 {@link RangedKiting}）：
     * 直射箭可以站在 14 格外，雪球这种抛物线严重的必须靠近。
     * 但 {@link #typeIndexOf} 会**登记类型**（改类型表、推版本号、给客户端下发模型），
     * 站在类型登记阶段调用它会污染类型表。所以这里只解析分类、只取默认参数。
     *
     * @return 默认参数（分类解析不出来时按 {@link ProjectileCatalog.Kind#NONE} 的兜底值）
     */
    public ProjectileCatalog.Defaults defaultsFor(ResourceLocation projectileId) {
        ProjectileCatalog.Kind kind = resolveKind(projectileId);
        return ProjectileCatalog.defaultsOf(kind);
    }

    /** 分类解析：注入的替身解析器优先，其次查注册表（与 {@link #typeIndexOf} 同一条链） */
    private ProjectileCatalog.Kind resolveKind(ResourceLocation id) {
        ProjectileCatalog.Kind kind = kindResolver != null ? kindResolver.apply(id) : null;
        if (kind != null) {
            return kind;
        }
        kind = ProjectileCatalog.Kind.NONE;
        EntityType<?> type = null;
        try {
            type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        } catch (Throwable ignored) {
            // 注册表还没建起来（离线工具/极早的加载阶段）
        }
        if (type != null) {
            kind = ProjectileCatalog.kindOf(type);
        }
        return kind;
    }

    public void setAdapter(Adapter adapter) {
        this.adapter = adapter == null ? FREE_MOVE : adapter;
    }

    public void setHits(Hits hits) {
        this.hits = hits == null ? NO_HITS : hits;
    }

    /**
     * 替换「这个投掷物 id 属于哪一类（箭/投掷物/火球）」的解析方式。
     *
     * 默认实现查原版注册表（见 {@link ProjectileCatalog#kindOf}）。
     * 离线 harness 里注册表不可用（要引导原版会连带拉起 Forge 的网络常量），
     * 于是用这个方法注入一个替身解析器 —— 与 {@link Adapter}/{@link Hits} 同一种思路：
     * 把「需要外部环境的那一步」变成可注入的，剩下的纯数学就能离线跑。
     */
    public void setKindResolver(java.util.function.Function<ResourceLocation, ProjectileCatalog.Kind> resolver) {
        this.kindResolver = resolver;
    }

    public ServerLevel level() {
        return level;
    }

    public void setLevel(ServerLevel level) {
        this.level = level;
    }

    public void setOwnerProbe(Entity probe) {
        this.ownerProbe = probe;
    }

    public Entity ownerProbe() {
        return ownerProbe;
    }

    public void setHurtRealEntities(boolean hurt) {
        this.hurtReal = hurt;
    }

    /** 当前的「是否伤害真实实体」开关（配置 + 覆盖表共同决定） */
    public boolean hurtRealEntities() {
        return hurtReal;
    }

    /** 覆盖表里的「是否伤害真实实体」：-1 = 用全局配置 */
    public void applyGlobalOverride(int hurtRealOverride) {
        if (hurtRealOverride >= 0) {
            hurtReal = hurtRealOverride != 0;
        } else {
            hurtReal = HordeConfig.safeGet(HordeConfig.COMMON.projectileHurtRealEntities, true);
        }
    }

    public void clear() {
        data.reset();
        spawnedTotal = 0;
        expiredTotal = 0;
    }

    // ------------------------------------------------------------------
    // 类型登记
    // ------------------------------------------------------------------

    /**
     * 登记一个投掷物类型（不存在就按**原版基类**推导默认参数）。
     *
     * @return 类型索引；超过 {@link #MAX_TYPES} 返回 -1
     */
    public int typeIndexOf(ResourceLocation id) {
        Integer idx = typeIndexMap.get(id);
        if (idx != null) {
            return idx;
        }
        if (typeIds.size() >= MAX_TYPES) {
            return -1;
        }
        ProjectileCatalog.Kind kind = resolveKind(id);
        ProjectileCatalog.Defaults def = ProjectileCatalog.defaultsOf(kind);
        int i = typeIds.size();
        typeIds.add(id);
        typeIndexMap.put(id, i);
        types.add(new ProjType(id, def.speed, def.gravity, def.drag, def.life,
                def.hitRadius, def.damageScale, def.blastRadius, def.blastScale,
                def.pierce, def.stickOnBlock, def.pitch, kind));
        typeVersion++;
        HordeMod.LOGGER.info("[horde] 投掷物类型 [{}] {}：类别 {}，初速 {}，重力 {}，寿命 {} tick",
                i, id, kind.name(), def.speed, def.gravity, def.life);
        return i;
    }

    // ------------------------------------------------------------------
    // 发射
    // ------------------------------------------------------------------

    /**
     * 登记/取得某个**施法者类型**在投掷物侧使用的索引（5 bit）。
     *
     * 与投掷物类型表分开：投掷物类型表是「发射的是什么」，这张表是「谁发的」。
     */
    private int casterIndexFor(ResourceLocation casterType, int fallback) {
        if (casterType == null) {
            return fallback & 0x1F;
        }
        Integer idx = casterTypeIndexMap.get(casterType);
        if (idx != null) {
            return idx;
        }
        if (casterTypeIndexMap.size() >= 32) {
            return fallback & 0x1F;
        }
        int i = casterTypeIndexMap.size();
        casterTypeIndexMap.put(casterType, i);
        return i;
    }

    /**
     * 该施法者类型当前在场上还有多少发（用于「每个类型自己的上限」）。
     *
     * 直接在 SoA 数组上数一遍：投掷物总量本来就有硬上限（{@link #HARD_CAP}），
     * 而且这行代码只在**发射时**跑（动作命中帧，一次最多几十发），
     * 不在每 tick 的热路径上，所以不值得再维护一份计数表。
     */
    private int liveForCaster(ResourceLocation caster) {
        if (caster == null || data.alive == 0) {
            return 0;
        }
        int idx = casterTypeIndexMap.getOrDefault(caster, -1);
        if (idx < 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < data.highWater; i++) {
            if (data.aliveFlag[i] && (data.casterType[i] & 0x1F) == idx) {
                n++;
            }
        }
        return n;
    }

    /**
     * 由群集单位在**动作命中帧**发射一枚虚拟投掷物。
     *
     * @param config      该 (类型, 动作) 的技能配置（null = 用基类默认值）
     * @param casterStats 施法者属性（取攻击力/尺寸/眼睛高度）
     * @return 生成的槽位；失败（上限/类型超限）返回 -1
     */
    public int fire(ResourceLocation projectileId, ResourceLocation casterType,
                    CrowdManager.TypeStats casterStats,
                    float casterX, float casterY, float casterZ, float casterYaw,
                    int ownerSlot, int casterTypeIndex, int team, SkillConfigView config) {
        return fire(projectileId, casterType, casterStats, casterX, casterY, casterZ, casterYaw,
                ownerSlot, casterTypeIndex, team, config, null);
    }

    /**
     * 同上，但可以给一个**显式瞄准速度向量**（{@code aimVelocity}，长度 &gt;= 3）。
     *
     * 为什么需要：远程单位瞄的是**目标**（含垂直分量），而不是「沿自己当前朝向按配置仰角射」。
     * 只有水平 yaw 的话，站在高处的骷髅会把箭射到目标脚下。
     * 传 null 时行为与原来完全一致（动作命中帧的扇形散射就是这条老路）。
     */
    public int fire(ResourceLocation projectileId, ResourceLocation casterType,
                    CrowdManager.TypeStats casterStats,
                    float casterX, float casterY, float casterZ, float casterYaw,
                    int ownerSlot, int casterTypeIndex, int team, SkillConfigView config,
                    float[] aimVelocity) {
        if (spawnCapThisTick > 0 && spawnedThisTick >= spawnCapThisTick) {
            return -1;   // 单 tick 生成上限：避免一次动作刷出成百上千发
        }
        // 该施法者类型自己的上限（玩家在技能页签里配；< 0 = 不限制）
        int cap = casterType == null ? -1 : perTypeCap.getOrDefault(casterType, -1);
        if (cap >= 0 && liveForCaster(casterType) >= cap) {
            return -1;
        }
        int typeIdx = typeIndexOf(projectileId);
        if (typeIdx < 0) {
            return -1;
        }
        int slot = data.alloc();
        if (slot < 0) {
            return -1;
        }
        ProjType type = types.get(typeIdx);

        float speed = config != null && config.speed() >= 0.0F ? config.speed() : type.speed;
        float pitch = config != null && config.pitchSet() ? config.pitch() : type.pitch;
        float gravity = config != null && config.gravity() >= 0.0F ? config.gravity() : type.gravity;
        int life = config != null && config.life() > 0 ? config.life() : type.life;
        float hitRadius = config != null && config.hitRadius() > 0.0F ? config.hitRadius() : type.hitRadius;
        float damageScale = config != null && config.damageScale() >= 0.0F ? config.damageScale() : type.damageScale;
        float blastRadius = config != null && config.blastRadius() >= 0.0F ? config.blastRadius() : type.blastRadius;
        float blastScale = config != null && config.blastScale() >= 0.0F ? config.blastScale() : type.blastScale;
        int pierce = config != null && config.pierce() >= 0 ? config.pierce() : type.pierce;
        boolean stick = config != null && config.stickOnBlockSet() ? config.stickOnBlock() : type.stickOnBlock;
        float knockback = config != null ? config.knockback() : 0.0F;

        // 出射点：
        //   - 显式瞄准（远程攻击模式）时，调用方给的就是**炮口位置**（它自己已经把眼高算进去了），
        //     这里原样使用，绝不能再叠一次眼高 —— 否则箭会从头顶飞出去（真机表现：箭永远从
        //     目标上方掠过、永远不命中）。这个「眼高叠了两次」的坑就是靠 projectileHarness 的
        //     「箭要命中 6 格外的目标」用例抓出来的。
        //   - 动作命中帧那条路（扇形散射）沿用原语义：casterY 是**脚底**，这里补眼高。
        float eye = casterStats != null ? casterStats.eyeHeight : 1.5F;
        if (aimVelocity != null && aimVelocity.length >= 3) {
            // 显式瞄准：方向与速度都由调用方（远程攻击决策）算好了
            delta[0] = aimVelocity[0];
            delta[1] = aimVelocity[1];
            delta[2] = aimVelocity[2];
        } else {
            SkillParams.launchVelocity(casterYaw, pitch, speed, delta);
        }

        data.posX[slot] = casterX;
        data.posY[slot] = aimVelocity != null ? casterY : casterY + eye * 0.85F;
        data.posZ[slot] = casterZ;
        // 扫描起点：本 tick 还没飞，起点 = 出射点。这样「刚出生就贴着一个单位」也能被扫到，
        // 而且 step() 的第一 tick 写入的 prev 与这里一致（不会出现未初始化的 0,0,0 线段）。
        data.prevX[slot] = data.posX[slot];
        data.prevY[slot] = data.posY[slot];
        data.prevZ[slot] = data.posZ[slot];
        data.velX[slot] = delta[0];
        data.velY[slot] = delta[1];
        data.velZ[slot] = delta[2];
        data.typeIndex[slot] = (byte) typeIdx;
        data.ownerSlot[slot] = ownerSlot;
        data.team[slot] = (byte) team;
        data.casterType[slot] = (byte) casterIndexFor(casterType, casterTypeIndex);
        data.damage[slot] = SkillParams.projectileDamage(
                casterStats != null ? casterStats.damage : HordeConfig.safeGet(HordeConfig.COMMON.unitDamage, 3.0F),
                damageScale);
        data.hitRadius[slot] = Math.max(0.05F, hitRadius);
        data.blastRadius[slot] = Math.max(0.0F, blastRadius);
        data.blastScale[slot] = Math.max(0.0F, blastScale);
        data.gravity[slot] = gravity;
        data.drag[slot] = type.drag;
        data.knockback[slot] = Math.max(0.0F, knockback);
        data.pierce[slot] = (byte) Math.max(0, Math.min(127, pierce));
        data.stickOnBlock[slot] = stick;
        data.life[slot] = Math.max(1, life);
        data.lifeLeft[slot] = Math.max(1, life);
        spawnedThisTick++;
        spawnedTotal++;
        return slot;
    }

    /** 技能配置的只读视图 —— 让 crowd 包不依赖 edit 包（离线 harness 也能注入自己的实现）。 */
    public interface SkillConfigView {
        /** 初速；< 0 = 用默认 */
        float speed();

        /** 仰角（度） */
        float pitch();

        /** 仰角是否被显式设置过（否则用该类型基类推导的默认仰角） */
        boolean pitchSet();

        /** 重力；< 0 = 用默认 */
        float gravity();

        /** 寿命；<= 0 = 用默认 */
        int life();

        /** 命中半径；<= 0 = 用默认 */
        float hitRadius();

        /** 伤害倍率；< 0 = 用默认 */
        float damageScale();

        /** AoE 半径；< 0 = 用默认 */
        float blastRadius();

        /** AoE 伤害倍率；< 0 = 用默认 */
        float blastScale();

        /** 穿透次数；< 0 = 用默认 */
        int pierce();

        float knockback();

        boolean stickOnBlock();

        boolean stickOnBlockSet();
    }

    // ------------------------------------------------------------------
    // 每 tick 模拟
    // ------------------------------------------------------------------

    /**
     * 真实实体交互的注入点。
     *
     * 抽出接口的目的和 {@link Adapter}/{@link Hits} 一样：让投掷物模拟**不依赖 ServerLevel**，
     * 于是离线 harness 可以只注入两个替身回调就完整跑一遍模拟（含「是否会去伤害真实实体」）。
     * 真实实现见 {@link #realEntitySink}。
     */
    public interface Sink {
        /**
         * 对命中位置附近半径内的真实实体造成伤害。
         *
         * @return 伤到几个
         */
        int hurtReal(float x, float y, float z, float radius, float damage, float knockback);

        /** 什么都不做的实现（默认；也是「不伤害真实实体」这一配置被关掉时的行为） */
        Sink NONE = (x, y, z, r, dmg, kb) -> 0;
    }

    /** 真实实现：用原版 AABB 查询 + {@code hurt}；**绝不 spawn 真实投掷物**。 */
    public static Sink realEntitySink(ServerLevel level, Entity owner, boolean enabled) {
        if (!enabled) {
            return Sink.NONE;
        }
        return (x, y, z, radius, damage, knockback) ->
                hurtRealEntities(level, owner, x, y, z, radius, damage, knockback);
    }

    /** 由维度 tick 调用；{@code level} 只用于方块碰撞适配与真实实体交互。 */
    public void tick(ServerLevel level) {
        tick(level, Sink.NONE);
    }

    /** 由维度 tick 调用（带真实实体交互回调）。 */
    public void tick(ServerLevel level, Sink sink) {
        tick++;
        spawnedThisTick = 0;
        spawnCapThisTick = HordeConfig.safeGet(HordeConfig.COMMON.maxProjectileSpawnsPerTick, 64);
        float minY = level.getMinBuildHeight() - 64.0F;
        Sink use = sink;
        if (hurtReal && use == Sink.NONE) {
            // 没显式给 sink 时，按配置自动接上「伤害真实实体」（仍然不生成任何真实投掷物）
            use = realEntitySink(level, ownerProbe, true);
        }
        stepAll(use, minY);
    }

    /**
     * 与 {@link #tick(ServerLevel, Sink)} 等价的**纯数学**入口（离线验证用）。
     *
     * 没有 ServerLevel 也能跑：方块碰撞由 {@link #setAdapter} 决定，
     * 命中判定由 {@link #setHits} 决定，真实实体伤害由 {@code sink} 决定。
     */
    public void tickPure(Sink sink, float minY) {
        tick++;
        spawnedThisTick = 0;
        spawnCapThisTick = HordeConfig.safeGet(HordeConfig.COMMON.maxProjectileSpawnsPerTick, 64);
        stepAll(sink == null ? Sink.NONE : sink, minY);
    }

    private void stepAll(Sink sink, float minY) {
        if (data.alive == 0) {
            return;
        }
        ProjectileData d = data;
        Sink use = sink == null ? Sink.NONE : sink;
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            if (step(d, i, minY, use)) {
                d.free(i);
                expiredTotal++;
            }
        }
    }

    /**
     * 推进一枚投掷物。**纯数组数学 + 三个可注入回调**，没有 `new`。
     *
     * @param sink 真实实体交互回调（可为 {@link Sink#NONE}）
     * @return 是否消失（寿命归零 / 撞方块 / 出界 / 命中耗尽）
     */
    boolean step(ProjectileData d, int i, float minY, Sink sink) {
        float prevX = d.posX[i];
        float prevY = d.posY[i];
        float prevZ = d.posZ[i];
        // ★ 本 tick 的扫掠起点 = 积分之前的真实位置。
        //   命中结算一律读这三个字段，**绝不**用 `pos - vel` 反推（理由见 ProjectileData.prevX 的注释：
        //   vel 此刻已被重力/阻力/方块解算改过，撞墙卡住时甚至是 0）。
        d.prevX[i] = prevX;
        d.prevY[i] = prevY;
        d.prevZ[i] = prevZ;

        if (d.lifeLeft[i] <= 0) {
            return true;
        }

        // ---- 1. 积分（重力 + 阻力）----
        delta[0] = d.velX[i];
        delta[1] = d.velY[i];
        delta[2] = d.velZ[i];
        SkillParams.integrate(delta, d.gravity[i], d.drag[i], moved);
        float nvx = moved[0];
        float nvy = moved[1];
        float nvz = moved[2];

        // ---- 2. 方块碰撞（逐轴，注入式）----
        pos[0] = prevX;
        pos[1] = prevY;
        pos[2] = prevZ;
        delta[0] = nvx;
        delta[1] = nvy;
        delta[2] = nvz;
        // 速度先按「重力 + 阻力」的积分结果写下去，再由解算方扣掉被方块挡住的分量。
        // 这一步很关键：解算方原样返回的就是我们传进去的位移，
        // 如果速度留在旧值上，阻力与重力等于白做 —— 实测表现为箭一直以满速飞、
        // 射程从 ~160 格虚增到 ~190 格，而且永远不会下落。
        velScratch[0] = nvx;
        velScratch[1] = nvy;
        velScratch[2] = nvz;
        boolean blocked = adapter.resolveMove(pos, delta, moved, velScratch);
        if (blocked) {
            if (d.stickOnBlock[i]) {
                // 卡在方块上：把位置贴到解算后的接触点，速度清零，之后只等寿命归零
                d.posX[i] = pos[0];
                d.posY[i] = pos[1];
                d.posZ[i] = pos[2];
                d.velX[i] = 0.0F;
                d.velY[i] = 0.0F;
                d.velZ[i] = 0.0F;
                d.lifeLeft[i]--;
                return d.lifeLeft[i] <= 0;
            }
            return true;   // 撞到方块即消失（原版箭撞墙也会消失/插住，这里按配置选择）
        }
        d.posX[i] = pos[0];
        d.posY[i] = pos[1];
        d.posZ[i] = pos[2];
        // 速度取「解算后的位移」——它已经含了重力与阻力的效果，
        // 且被方块削掉的分量不会被带进下一 tick（不然会贴着墙一直加速）
        d.velX[i] = velScratch[0];
        d.velY[i] = velScratch[1];
        d.velZ[i] = velScratch[2];

        // ---- 3. 命中判定 + 结算（注入式：内部走群集空间哈希 + 真实实体查询）----
        float r = d.hitRadius[i];
        radiusScratch[0] = r * r;
        // AoE 只在第一次命中时结算（穿透弹后续命中不再爆），避免一箭连环爆炸
        boolean applyBlast = d.blastRadius[i] > 0.0F && d.pierce[i] == 0;
        int hitCount = hits.resolve(d, i, applyBlast, radiusScratch);
        // 真实实体：由 sink 负责（撞到实体也算一次命中，于是箭会插进真怪身上而不是穿过去）
        if (sink != null && sink != Sink.NONE && d.damage[i] > 0.0F) {
            hitCount += sink.hurtReal(d.posX[i], d.posY[i], d.posZ[i], r, d.damage[i], d.knockback[i]);
        }
        if (hitCount > 0) {
            if (d.pierce[i] > 0) {
                d.pierce[i]--;
            } else {
                return true;
            }
        }

        // ---- 4. 寿命 ----
        d.lifeLeft[i]--;
        // ---- 5. 出界（掉到世界底部以下）----
        if (d.posY[i] < minY) {
            return true;
        }
        return d.lifeLeft[i] <= 0;
    }

    // ------------------------------------------------------------------
    // 同步辅助
    // ------------------------------------------------------------------

    /**
     * 收集玩家附近的投掷物。
     *
     * @param range  半径（格）；&le; 0 = 不限制
     * @param budget 最多收集多少；&le; 0 = 不限制
     */
    public int collectVisible(float px, float pz, float range, int budget) {
        ProjectileData d = data;
        boolean allRange = range <= 0.0F;
        float r2 = range * range;
        int count = 0;
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            if (!allRange) {
                float ox = d.posX[i] - px;
                float oz = d.posZ[i] - pz;
                if (ox * ox + oz * oz > r2) {
                    continue;
                }
            }
            if (budget > 0 && count >= budget) {
                break;
            }
            if (count >= outSlots.length) {
                outSlots = java.util.Arrays.copyOf(outSlots, Math.max(count + 1, outSlots.length * 2));
            }
            outSlots[count++] = i;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 真实实体交互（可选，由配置开关控制）
    // ------------------------------------------------------------------

    /**
     * 对命中半径内的**真实实体**造成伤害。
     *
     * 注意：这里**只伤害，不生成**任何真实投掷物实体 —— 虚拟投掷物的原则是不 spawn 实体。
     * 用 {@code getEntitiesOfClass(LivingEntity.class, AABB)} 而不是逐实体遍历，
     * 是为了让原版自己按 AABB 做粗筛。
     *
     * @return 实际伤到几个
     */
    public static int hurtRealEntities(ServerLevel level, Entity owner, double x, double y, double z,
                                       float radius, float damage, float knockback) {
        if (damage <= 0.0F && knockback <= 0.0F) {
            return 0;
        }
        AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box);
        int hurt = 0;
        for (int i = 0; i < targets.size(); i++) {
            LivingEntity e = targets.get(i);
            if (e == owner || !e.isAlive() || e.isSpectator()) {
                continue;
            }
            if (owner != null && owner instanceof LivingEntity livingOwner && !livingOwner.canAttack(e)) {
                continue;
            }
            try {
                e.invulnerableTime = 0;   // 不然连发的投掷物会被无敌帧全吃掉
                if (damage > 0.0F && e.hurt(level.damageSources().generic(), damage)) {
                    hurt++;
                } else if (damage <= 0.0F) {
                    hurt++;
                }
                if (knockback > 0.0F) {
                    double dx = e.getX() - x;
                    double dz = e.getZ() - z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 1.0E-4D) {
                        e.push(dx / len * knockback, 0.15D, dz / len * knockback);
                    }
                }
            } catch (Throwable ignored) {
                // 某些模组实体的 hurt 会抛异常：不该因此中断整个模拟
            }
        }
        return hurt;
    }

    /** 生成一个「随机抖动」的发射仰角（让一群骷髅的箭不至于完全重叠）。 */
    public static float jitterPitch(RandomSource rnd, float base, float spread) {
        if (spread <= 0.0F) {
            return base;
        }
        return base + (rnd.nextFloat() * 2.0F - 1.0F) * spread;
    }

    /**
     * 覆盖表改了以后把这个投掷物类型「重算一遍」。
     *
     * 这里能重算的只有「是否伤害真实实体」这类**全局**开关：初速/重力/寿命/伤害这些
     * 在生成时就快照进了数组（每枚自己的重力与寿命），所以在飞的投掷物不受影响 ——
     * 这是有意的（改配置不该把天上那支箭变成另一种东西），新的配置对之后发射的立刻生效。
     */
    public void reapply(ResourceLocation typeId) {
        dev.swordkingdom.horde.edit.TypeOverride ov =
                dev.swordkingdom.horde.edit.TypeOverride.get(typeId);
        if (ov == null) {
            perTypeCap.remove(typeId);
            hurtReal = HordeConfig.safeGet(HordeConfig.COMMON.projectileHurtRealEntities, true);
            return;
        }
        if (ov.maxProjectiles >= 0) {
            perTypeCap.put(typeId, ov.maxProjectiles);
        } else {
            perTypeCap.remove(typeId);
        }
        applyGlobalOverride(ov.hurtRealEntities);
        HordeMod.LOGGER.info("[horde] 投掷物技能覆盖已应用于 {}：伤害真实实体 = {}，"
                        + "该类型同时在场上限 = {}，已注册投掷物类型 {} 种",
                typeId, hurtReal,
                perTypeCap.getOrDefault(typeId, -1) < 0 ? "不限（用全局）" : perTypeCap.get(typeId),
                typeIds.size());
    }

    /** 诊断用：当前类型表 */
    public String describe() {        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < typeIds.size(); i++) {
            ProjType t = types.get(i);
            sb.append(i).append('=').append(typeIds.get(i)).append('(').append(t.kind).append(") ");
        }
        return sb.length() == 0 ? "（空）" : sb.toString();
    }

    /**
     * 真实方块碰撞适配：把投掷物当一个小立方体交给 {@link BlockWorld} 逐轴解算。
     *
     * 判据用 {@link BlockWorld} 自己算出的 {@code horizontalCollision} / onGround 标志，
     * 而不是「解算位移 != 期望位移」—— 后者在「沿墙滑动」时也会成立（其实没撞），
     * 会让贴着墙飞的箭莫名其妙消失。
     */
    public static Adapter blockAdapter(BlockWorld world, float halfSize) {
        final float hs = Math.max(0.02F, halfSize);
        final float[] box = new float[6];
        final boolean[] flags = new boolean[2];
        final float[] local = new float[3];
        return (p, dl, out, vel) -> {
            box[0] = p[0] - hs;
            box[1] = p[1] - hs;
            box[2] = p[2] - hs;
            box[3] = p[0] + hs;
            box[4] = p[1] + hs;
            box[5] = p[2] + hs;
            world.move(box, dl[0], dl[1], dl[2], false, false, local, flags);
            out[0] = local[0];
            out[1] = local[1];
            out[2] = local[2];
            p[0] += local[0];
            p[1] += local[1];
            p[2] += local[2];
            // 被方块削掉的分量从速度里扣掉（其余分量继续飞，表现为沿墙滑动）
            vel[0] += local[0] - dl[0];
            vel[1] += local[1] - dl[1];
            vel[2] += local[2] - dl[2];
            boolean hitWall = flags[1];
            boolean hitGround = flags[0] && dl[1] < 0f;
            return hitWall || hitGround;
        };
    }

    /** 取得/创建该实体类型的傀儡实体（真实实体交互时作为 owner 用；失败返回 null）。 */
    public static Entity createProbe(ServerLevel level, ResourceLocation typeId) {
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
            return type == null ? null : type.create(level);
        } catch (Throwable t) {
            return null;
        }
    }
}
