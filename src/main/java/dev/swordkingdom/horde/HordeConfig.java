package dev.swordkingdom.horde;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** 全部可调参数集中在此，方便后期微调。 */
public final class HordeConfig {

    public static final Common COMMON;
    public static final ForgeConfigSpec SPEC;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private HordeConfig() {}

    /**
     * 读一个配置值；配置还没加载时返回兜底值。
     *
     * 为什么需要它：离线 harness 不加载 Forge 配置系统，
     * 而 {@code ForgeConfigSpec.ConfigValue#get()} 在开发环境里会直接抛
     * 「Cannot get config value before config is loaded」。
     * 模拟代码（尤其是虚拟投掷物这条每 tick 都跑的路径）用这个方法读配置，
     * 于是**离线验证与线上跑的是同一份代码**，不用为了测试把配置读取抄一遍。
     */
    public static int safeGet(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static float safeGet(ForgeConfigSpec.DoubleValue value, float fallback) {
        try {
            return value.get().floatValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static boolean safeGet(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static final class Common {

        // ---- 模拟 ----
        public final ForgeConfigSpec.BooleanValue useEntityAttributes;
        public final ForgeConfigSpec.IntValue maxUnitsPerLevel;
        public final ForgeConfigSpec.DoubleValue unitMaxHp;
        public final ForgeConfigSpec.DoubleValue unitDamage;
        public final ForgeConfigSpec.IntValue attackCooldownTicks;
        public final ForgeConfigSpec.DoubleValue attackRange;
        public final ForgeConfigSpec.DoubleValue followRange;
        public final ForgeConfigSpec.DoubleValue speedModifier;
        public final ForgeConfigSpec.DoubleValue pushStrength;
    public final ForgeConfigSpec.IntValue collisionPasses;
    public final ForgeConfigSpec.IntValue actionDurationTicks;
    public final ForgeConfigSpec.IntValue actionIntervalTicks;
    public final ForgeConfigSpec.DoubleValue actionImpactFraction;
    public final ForgeConfigSpec.DoubleValue actionRadiusScale;
    public final ForgeConfigSpec.DoubleValue actionDamageScale;
    public final ForgeConfigSpec.DoubleValue actionKnockback;
    public final ForgeConfigSpec.BooleanValue geoFlipX;
    public final ForgeConfigSpec.BooleanValue geoFlipY;
    public final ForgeConfigSpec.DoubleValue geoYOffset;

    // ---- 虚拟投掷物 ----
    public final ForgeConfigSpec.IntValue maxProjectilesPerLevel;
    public final ForgeConfigSpec.IntValue maxProjectileSpawnsPerTick;
    public final ForgeConfigSpec.BooleanValue projectileHurtRealEntities;
    public final ForgeConfigSpec.BooleanValue projectileStickOnBlock;
    public final ForgeConfigSpec.DoubleValue projectileKnockback;
    public final ForgeConfigSpec.BooleanValue projectileFlipX;
    public final ForgeConfigSpec.BooleanValue projectileFlipY;
    public final ForgeConfigSpec.DoubleValue projectileYOffset;
    public final ForgeConfigSpec.DoubleValue projectileYawOffset;
    public final ForgeConfigSpec.BooleanValue projectileAtlasFallback;
    // 远程攻击模式（解析出投掷物的类型自动进入）
    public final ForgeConfigSpec.DoubleValue projectileRangedDistance;
    public final ForgeConfigSpec.IntValue projectileRangedIntervalTicks;
    public final ForgeConfigSpec.IntValue projectileLogIntervalTicks;
    public final ForgeConfigSpec.BooleanValue projectileRequireLineOfSight;

        // ---- LOD ----
        public final ForgeConfigSpec.DoubleValue lodNearDistance;
        public final ForgeConfigSpec.DoubleValue lodMidDistance;
        public final ForgeConfigSpec.DoubleValue lodFarDistance;

        // ---- 流场 ----
        public final ForgeConfigSpec.IntValue flowIntervalTicks;
        public final ForgeConfigSpec.DoubleValue flowCellSize;

        // ---- 调试 ----
        /** 客户端动画诊断（默认关；开启后每类型每秒最多一行，同类最多重复 3 次） */
        public final ForgeConfigSpec.BooleanValue logAnimationDiagnostics;
        /** 烘焙期一次性采样插桩（每类型每维度最多一行） */
        public final ForgeConfigSpec.BooleanValue logAnimationProbe;
        /** 分层耗时插桩（每 1 秒最多一行：服务端各段 ms + 客户端 poseEval/上传量） */
        public final ForgeConfigSpec.BooleanValue logTickProfile;
        /** 逐发投掷物发射日志（默认关；关着时只有每秒一行汇总） */
        public final ForgeConfigSpec.BooleanValue logLaunchDetail;

        // ---- 网络 ----
        public final ForgeConfigSpec.IntValue snapshotIntervalTicks;
        public final ForgeConfigSpec.DoubleValue snapshotRange;
        public final ForgeConfigSpec.IntValue syncBudgetPerPlayer;

        Common(ForgeConfigSpec.Builder b) {            b.comment("群集模拟参数").push("simulation");
            useEntityAttributes = b.comment("使用实体自身的真实属性（尺寸/移动速度/生命/攻击力/护甲/索敌范围/攻速）。\n"
                            + "开启时下面的 unitMaxHp / unitDamage / followRange / attackRange / attackCooldownTicks "
                            + "仅作为「读不到属性时」的兜底；speedModifier 仍然作为整体倍率。")
                    .define("useEntityAttributes", true);
            maxUnitsPerLevel = b.comment("每个维度允许的最大群集单位数")
                    .defineInRange("maxUnitsPerLevel", 20000, 100, 200000);
            unitMaxHp = b.comment("单位最大生命值").defineInRange("unitMaxHp", 20.0D, 1.0D, 10000.0D);
            unitDamage = b.comment("单位单次攻击伤害").defineInRange("unitDamage", 3.0D, 0.0D, 10000.0D);
            attackCooldownTicks = b.comment("攻击冷却（tick）").defineInRange("attackCooldownTicks", 18, 1, 400);
            attackRange = b.comment("攻击判定距离（格）").defineInRange("attackRange", 1.1D, 0.1D, 8.0D);
            followRange = b.comment("索敌/追击半径（格）")
                    .defineInRange("followRange", 16.0D, 2.0D, 64.0D);
            speedModifier = b.comment("移动速度倍率（原版 MoveControl 的 speedModifier，1.0 = 原版僵尸速度）")
                    .defineInRange("speedModifier", 1.0D, 0.0D, 4.0D);
            pushStrength = b.comment("实体碰撞分离强度（0 = 关闭，单位之间可以重叠）。"
                            + "1.0 = 按各自碰撞盒把重叠推开（大 boss 会挤开小怪）")
                    .defineInRange("collisionStrength", 1.0D, 0.0D, 4.0D);
            collisionPasses = b.comment("碰撞分离的迭代次数。2~3 次足以把挤成一坨的单位拆开")
                    .defineInRange("collisionPasses", 2, 1, 8);
            actionDurationTicks = b.comment("群集单位「动作/技能动画」的播放时长（tick）。"
                            + "有动画状态 API 的实体（如灾变的 Ignis / Ender Guardian）攻击时会像真实实体一样"
                            + "播放自己的动作动画；播完后回到默认姿势")
                    .defineInRange("actionDurationTicks", 24, 4, 200);
            actionIntervalTicks = b.comment("技能施放间隔（tick，带随机抖动）。0 = 只在近战攻击时触发动作。"
                            + "有动画 API 的 boss 会按这个节奏「放招」")
                    .defineInRange("actionIntervalTicks", 120, 0, 2400);
            actionImpactFraction = b.comment("命中帧位置（动作播放进度的百分比）。"
                            + "动作播到这里时结算一次范围伤害+击退")
                    .defineInRange("actionImpactFraction", 0.5D, 0.05D, 0.95D);
            actionRadiusScale = b.comment("技能范围倍率（半径由该实体自己的体型与近战距离推算，再乘这个倍率）")
                    .defineInRange("actionRadiusScale", 1.0D, 0.1D, 8.0D);
            actionDamageScale = b.comment("技能伤害倍率（伤害基数用该实体自己的 ATTACK_DAMAGE）")
                    .defineInRange("actionDamageScale", 1.0D, 0.0D, 20.0D);
            actionKnockback = b.comment("技能击退强度（按目标击退抗性衰减；0 = 不击退）")
                    .defineInRange("actionKnockback", 0.35D, 0.0D, 4.0D);

            b.comment("GeckoLib(geo) 模型的朝向/高度修正。geo 模型的方块顶点已经是 Bedrock 朝向，"
                    + "不需要原版 LivingEntityRenderer 的 scale(-1,-1,1)（否则会倒立）。"
                    + "如果某个 geo 模型上下颠倒/镜像/悬空，改这三项后重启即可，不用重新打包").push("geoModel");
            geoFlipX = b.comment("geo 模型 X 轴是否翻转（默认不翻）").define("geoFlipX", false);
            geoFlipY = b.comment("geo 模型 Y 轴是否翻转（默认不翻；倒立就把它设为 true 试试）")
                    .define("geoFlipY", false);
            geoYOffset = b.comment("geo 模型的额外高度偏移（格）。原版模型固定用 -1.501（脚底对齐），"
                            + "geo 模型若悬空/陷地，调这一项")
                    .defineInRange("geoYOffset", 0.0D, -4.0D, 4.0D);
            b.pop();

            b.comment("虚拟投掷物（技能弹幕）。群集单位在动作命中帧发射的是**虚拟**投掷物："
                    + "服务端只跑纯数据模拟，客户端按该投掷物自己的模型实例化渲染，"
                    + "任何时候都不会生成真实投掷物实体").push("projectile");
            maxProjectilesPerLevel = b.comment("单维度同时存在的虚拟投掷物上限（硬上限 8192）")
                    .defineInRange("maxProjectilesPerLevel", 2048, 64, 8192);
            maxProjectileSpawnsPerTick = b.comment("单个 tick 内全维度最多新生成多少发（0 = 不限制）。"
                            + "防止一次大范围技能在同 tick 刷出成百上千发把服务器卡住")
                    .defineInRange("maxSpawnsPerTick", 64, 0, 8192);
            projectileHurtRealEntities = b.comment("虚拟投掷物是否顺带伤害**真实实体**（用 level.getEntities 命中半径内取实体并 hurt）。"
                            + "注意：只伤害，不生成任何真实投掷物实体")
                    .define("hurtRealEntities", true);
            projectileStickOnBlock = b.comment("撞到方块后是否「卡住」而不是立刻消失（箭类默认卡住，和原版一样）")
                    .define("stickOnBlock", true);
            projectileKnockback = b.comment("投掷物命中时的击退强度（按目标击退抗性衰减）")
                    .defineInRange("knockback", 0.3D, 0.0D, 4.0D);
            b.comment("投掷物模型的朝向/高度修正。投掷物**不是** LivingEntityRenderer，"
                    + "它的顶点是通过「捕获它自己的渲染器画出来的顶点」得到的，姿态是烘焙期定死的，"
                    + "所以发射方向靠这几个 uniform 修正。某个投掷物方向不对时改这里").push("model");
            projectileFlipX = b.comment("X 轴翻转（原版实体渲染器惯例 = true；顶点已经是最终朝向时设 false）")
                    .define("flipX", true);
            projectileFlipY = b.comment("Y 轴翻转（原版实体渲染器惯例 = true）").define("flipY", true);
            projectileYOffset = b.comment("高度偏移（格）。原版模型是 1.501（脚底对齐），投掷物通常需要调小")
                    .defineInRange("yOffset", 0.0D, -4.0D, 4.0D);
            projectileYawOffset = b.comment("整体额外偏航（度）。用于把「模型自己的朝向」对齐到速度方向："
                            + "默认 0 表示按烘焙出来的主轴自动判断；原版箭矢的模型指向 +X 但发射方向是 +Z，"
                            + "所以箭类通常需要 +90")
                    .defineInRange("yawOffset", 0.0D, -180.0D, 180.0D);
            projectileAtlasFallback = b.comment("拿不到渲染器贴图时，是否退化为**方块图集**（物品投掷物就是这样）")
                    .define("atlasFallback", true);

            b.comment("远程攻击模式：某个类型只要解析出了投掷物（玩家在技能页签里指定，"
                    + "或按同命名空间+名字词干自动挑到），就被当成远程单位 —— 索敌/攻击距离用下面的远程距离，"
                    + "而不是只有 1~2 格的近战距离；贴脸时仍然保留原来的近战逻辑。").push("ranged");
            projectileRangedDistance = b.comment("远程攻击距离（格，默认 16）。会被该类型的覆盖项覆盖。"
                            + "原版骷髅在十几格外放箭，所以默认值与它的 followRange 量级一致")
                    .defineInRange("distance", 16.0D, 2.0D, 64.0D);
            projectileRangedIntervalTicks = b.comment("远程单位的两次发射间隔（tick，带错峰抖动）。"
                            + "0 = 走技能施放间隔（上面技能页签的 actionIntervalTicks）。"
                            + "原版骷髅拉弓约 1 秒 = 20 tick，这里默认 40（2 秒）避免箭太多")
                    .defineInRange("intervalTicks", 40, 0, 1200);
            projectileLogIntervalTicks = b.comment("发射/无法发射的日志节流（tick）。"
                            + "200 = 5 秒内同一单位最多打一条，0 = 每次发射都打（排查时用，刷屏）")
                    .defineInRange("logIntervalTicks", 200, 0, 24000);
            projectileRequireLineOfSight = b.comment("远程攻击是否要求视线（用方块碰撞形状做一次走样采样）。"
                            + "开启后隔着墙不会放箭，和真实实体一致；采样拿不到方块世界时会退化为「不要求视线」并记一条日志")
                    .define("requireLineOfSight", true);
            b.pop();
            b.pop();

            b.comment("细节层级（LOD）：单位根据与最近玩家的距离降低更新频率").push("lod");
            lodNearDistance = b.comment("此距离内每 tick 更新").defineInRange("lodNearDistance", 32.0D, 4.0D, 512.0D);
            lodMidDistance = b.comment("此距离内每 2 tick 更新").defineInRange("lodMidDistance", 64.0D, 4.0D, 512.0D);
            lodFarDistance = b.comment("此距离内每 4 tick 更新，更远则每 8 tick").defineInRange("lodFarDistance", 128.0D, 4.0D, 1024.0D);
            b.pop();

            b.comment("流场寻路").push("flow");
            flowIntervalTicks = b.comment("流场重算间隔（tick）").defineInRange("flowIntervalTicks", 10, 1, 200);
            flowCellSize = b.comment("流场网格尺寸（格）").defineInRange("flowCellSize", 4.0D, 1.0D, 32.0D);
            b.pop();

            b.comment("调试：客户端动画诊断（默认关）。开启后每个实体类型每秒最多一行，"
                            + "写明「用了哪张采样表（走表/待机表）、相位/幅度/时间、1 秒内变化最大的骨骼与总变化量」；"
                            + "同类最多重复 3 次，之后只在「姿态由动变静 / 由静变动」时再打一行。"
                            + "排查「群集单位一动不动」时把它打开").push("debug");
            logAnimationDiagnostics = b.comment("是否输出客户端动画诊断（默认关，避免刷屏）")
                    .define("logAnimationDiagnostics", false);
            logAnimationProbe = b.comment("是否输出烘焙期的一次性采样插桩（每类型每维度最多一行，"
                            + "含 AnimationState 四值、控制器当前动画名、驱动前后骨骼旋转）")
                    .define("logAnimationProbe", true);
            logTickProfile = b.comment("是否输出分层耗时插桩（**每 1 秒最多一行**）："
                            + "服务端 crowdTick 拆成 ai/physics/push/combat/skill/projectile/grid/flow 各段 ms，"
                            + "并附单位数、投掷物数、索敌扫描次数与候选数、命中判定次数、分离配对次数；"
                            + "客户端输出 poseEval/秒、每帧峰值、复用跳过数、求值耗时、上传字节/秒。"
                            + "排查「卡在哪一段」时它每秒只有一行，默认开着不吵；不需要时关掉")
                    .define("logTickProfile", true);
            logLaunchDetail = b.comment("是否输出**逐发**投掷物发射日志（默认关）。"
                            + "关着时每秒只有一行汇总（投掷物 id ×N + 平均初速 + 平均目标距离）；"
                            + "排查「某一发为什么射歪/没射」时才打开（会刷屏）")
                    .define("logLaunchDetail", false);
            b.pop();

            b.comment("服务端 -> 客户端 快照同步").push("network");
            snapshotIntervalTicks = b.comment("快照发送间隔（tick）。2 = 10Hz（配客户端插值已足够顺滑，带宽/开销减半）；1 = 20Hz")
                    .defineInRange("snapshotIntervalTicks", 2, 1, 40);
            snapshotRange = b.comment("仅同步该半径内的单位（格）。0 = 不限制（全部同步）")
                    .defineInRange("maxSyncDistance", 0.0D, 0.0D, 4096.0D);
            syncBudgetPerPlayer = b.comment("单个玩家每次快照最多同步的单位数。0 = 不限制"
                            + "（有多少同步多少，超过单包上限时自动拆成多个包）")
                    .defineInRange("maxSyncedUnitsPerPlayer", 0, 0, 4000000);
            b.pop();
        }
    }
}
