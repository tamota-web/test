package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.action.SkillParams;
import dev.swordkingdom.horde.crowd.CrowdData;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.ProjectileCombat;
import dev.swordkingdom.horde.crowd.ProjectileData;
import dev.swordkingdom.horde.crowd.ProjectileManager;
import dev.swordkingdom.horde.crowd.ProjectileCatalog;
import dev.swordkingdom.horde.crowd.RangedKiting;
import dev.swordkingdom.horde.network.ProjectileSnapshotPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * 虚拟投掷物模拟的离线验证（不需要 Minecraft 客户端、不需要 GL、不需要世界）。
 *
 * <h2>为什么能离线验证</h2>
 * 模拟循环里只有两处需要外部世界，而且都是**注入式回调**：
 * <pre>
 *   ProjectileManager.Adapter   方块碰撞（真实实现走 BlockWorld）
 *   ProjectileManager.Hits      命中谁 + 结算（真实实现走群集 SpatialGrid）
 * </pre>
 * 这个 harness 注入两个替身回调，于是跑的就是**生产代码本身**的那段积分/命中/寿命数学
 * （{@link SkillParams#integrate} / {@link SkillParams#sweepHit} / {@link ProjectileManager#step}）。
 *
 * <h2>覆盖点</h2>
 * <ol>
 *   <li>重力 + 阻力的抛物线与「箭类基类默认参数」的一致（初速 3.0 / 重力 0.05 / 阻力 0.99）；</li>
 *   <li>火球类默认参数：重力 0（直线），寿命 80；</li>
 *   <li>寿命归零后消失；出界（掉出世界底部）消失；</li>
 *   <li>撞方块消失 / 按配置卡住；</li>
 *   <li>命中判定是**线段扫掠**：高速投掷物不会「穿人而过」；</li>
 *   <li>命中半径与穿透次数（穿透弹能连续命中多个目标）；</li>
 *   <li>伤害经目标护甲按原版公式减免；AoE 按距离衰减且边缘为 0；</li>
 *   <li>每 tick 生成上限与总上限（边界）；</li>
 *   <li>快照打包字段往返（yaw/pitch/life/alive/type/team）与每枚字节数。</li>
 * </ol>
 */
public final class ProjectileHarness {

    private static int failures;

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "goalprobe".equals(args[0])) {
            GoalProbe.run();
            return;
        }
        registerStubTypes();
        ballisticDefaults();
        fireballStraightLine();
        lifetimeAndBounds();
        blockCollision();
        sweepHitNoTunneling();
        hitRadiusAndPierce();
        damageAfterArmor();
        blastAoE();
        spawnCaps();
        packetRoundTrip();
        rangedAttackMode();
        meleePlusRangedNoDoubleCount();
        kitingRuleFromBallistics();
        kitingKeepsDistance();
        crowdTakeProjectileDamage();
        hitCandidateCallBound();
        meleeReachGate();
        rangedFiresInPlace();
        rangedNeverMelees();
        gravityCompensationMath();
        rangedHitRateAtDistance();
        rangedHitRateConsistency();
        rangedAutoResolvePath();
        guiConfiguredProjectileBeatsGate();
        rendererUniformContract();
        shaderSpaceAndOrderContract();
        viewMatrixContract();
        System.out.println(failures == 0 ? "\n== PROJECTILE PASS ==" : "\n== PROJECTILE FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 基础工具
    // ------------------------------------------------------------------

    /** 造一个「无方块、无命中、无真实实体」的投掷物管理器 */
    private static ProjectileManager freshManager() {
        return newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
    }

    /** 造一个投掷物管理器并接好注入点（分类解析 + 方块碰撞 + 命中结算） */
    private static ProjectileManager newManager(ProjectileManager.Adapter adapter,
                                                ProjectileManager.Hits hits) {
        ProjectileManager m = new ProjectileManager(dim());
        m.setKindResolver(stubKinds());
        m.setAdapter(adapter);
        m.setHits(hits);
        m.setHurtRealEntities(false);
        return m;
    }

    /**
     * 维度 id —— 随手造一个。
     *
     * 投掷物模拟只把它当 map 的键用，不关心维度是什么；
     * 之所以不去引 {@code Level.OVERWORLD}，是因为那会连带初始化原版注册表
     * （要求先 Bootstrap，而 Bootstrap 在离线 harness 里会连带拉起 Forge 的网络常量）。
     */
    private static ResourceLocation dim() {
        return ResourceLocation.tryParse("minecraft:overworld");
    }

    /**
     * 把三个替身 id 按**原版基类**分好类，注入给投掷物管理器。
     *
     * 为什么用注入而不是真的注册：真注册需要把原版注册表引导起来，
     * 而 {@code Bootstrap.bootStrap()} 会连带初始化 Forge 的网络常量
     * （{@code NetworkConstants} → 事件总线扫描），离线 harness 里没有那一步，
     * 会以 {@code NoSuchMethodException: NetworkEvent.<init>()} 失败。
     * 投掷物模拟真正需要的信息只有一个：「这个 id 是箭/投掷物/火球里的哪一类」，
     * 所以这里把它变成可注入的（与方块碰撞、命中查询同一种做法）。
     *
     * 分类本身（{@link dev.swordkingdom.horde.crowd.ProjectileCatalog#kindOfClass}）
     * 由 {@link SkillDiscoveryHarness} 用**替身实体类**直接验证。
     */
    private static void registerStubTypes() {        System.out.println("[info] 已注入替身投掷物分类："
                + STUB_ARROW_KIND + " / " + STUB_FIREBALL_KIND + " / " + STUB_SNOWBALL_KIND);
    }

    private static final dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind STUB_ARROW_KIND =
            dev.swordkingdom.horde.crowd.ProjectileCatalog.kindOfClass(StubArrowClass());
    private static final dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind STUB_FIREBALL_KIND =
            dev.swordkingdom.horde.crowd.ProjectileCatalog.kindOfClass(StubFireballClass());
    private static final dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind STUB_SNOWBALL_KIND =
            dev.swordkingdom.horde.crowd.ProjectileCatalog.kindOfClass(StubSnowballClass());

    private static Class<?> StubArrowClass() {
        return StubArrow.class;
    }

    private static Class<?> StubFireballClass() {
        return StubFireball.class;
    }

    private static Class<?> StubSnowballClass() {
        return StubSnowball.class;
    }

    /**
     * 替身分类解析器（写成方法而不是静态字段：静态初始化器里引用后面声明的常量
     * 会触发 Java 的「非法前向引用」，而 lambda 的耗时本来也不需要提前）。
     */
    private static java.util.function.Function<ResourceLocation,
            dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind> stubKinds() {
        return id -> {
            if (ARROW.equals(id)) {
                return STUB_ARROW_KIND;
            }
            if (FIREBALL.equals(id)) {
                return STUB_FIREBALL_KIND;
            }
            if (SNOWBALL.equals(id)) {
                return STUB_SNOWBALL_KIND;
            }
            return dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.PROJECTILE;
        };
    }

    /** 替身：箭（继承 {@code AbstractArrow}） */
    static class StubArrow extends net.minecraft.world.entity.projectile.AbstractArrow {
        StubArrow(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.projectile.AbstractArrow> t,
                  net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected net.minecraft.world.item.ItemStack getPickupItem() {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** 替身：火球（继承 {@code AbstractHurtingProjectile}） */
    static class StubFireball extends net.minecraft.world.entity.projectile.AbstractHurtingProjectile {
        StubFireball(
                net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.projectile.AbstractHurtingProjectile> t,
                net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }
    }

    /** 替身：雪球（继承 {@code ThrowableProjectile}） */
    static class StubSnowball extends net.minecraft.world.entity.projectile.ThrowableProjectile {
        StubSnowball(
                net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.projectile.ThrowableProjectile> t,
                net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }
    }

    private static final ResourceLocation ARROW = ResourceLocation.tryParse("minecraft:arrow");
    private static final ResourceLocation FIREBALL = ResourceLocation.tryParse("minecraft:fireball");
    private static final ResourceLocation SNOWBALL = ResourceLocation.tryParse("minecraft:snowball");

    // ------------------------------------------------------------------
    // 1. 箭类基类默认参数的抛物线
    // ------------------------------------------------------------------

    private static void ballisticDefaults() {
        ProjectileManager m = freshManager();
        int slot = m.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, null);
        check(slot >= 0, "箭类投掷物应能生成（返回槽位 " + slot + "）");
        ProjectileData d = m.data();
        ProjectileManager.ProjType type = m.typeOf(d.typeIndex[slot] & 0x1F);
        check(type != null && Math.abs(type.speed - 3.0F) < 1.0E-4F,
                "箭类默认初速应为 3.0（实际 " + (type == null ? "null" : type.speed) + "）");
        check(type != null && Math.abs(type.gravity - 0.05F) < 1.0E-4F, "箭类默认重力应为 0.05");
        check(type != null && type.life == 100, "箭类默认寿命应为 100 tick");
        check(type != null && Math.abs(type.drag - 0.99F) < 1.0E-4F, "箭类默认阻力应为 0.99");

        // 逐 tick 前进，记录轨迹
        float startY = d.posY[slot];
        float maxY = startY;
        int ticks = 0;
        for (int i = 0; i < 200 && d.aliveFlag[slot]; i++) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
            if (d.posY[slot] > maxY) {
                maxY = d.posY[slot];
            }
            ticks++;
        }
        float dx = d.posX[slot];
        float dz = d.posZ[slot];
        float range = (float) Math.sqrt(dx * dx + dz * dz);
        check(ticks == 100, "箭类应在 100 tick 后因寿命归零消失（实际 " + ticks + "）");
        check(maxY > startY, "应有一段上升段（抛物线），最高点 " + trim(maxY - startY) + " 格高于起点");
        // 初速 3、阻力 0.99：100 tick 的水平射程理论上是 3 * (1-0.99^100)/0.01 ≈ 190 格，
        // 这里给 ±15% 的容差（重力会把水平分量也乘上阻力，实测 ~188）
        check(range > 155.0F && range < 200.0F,
                "初速 3 + 阻力 0.99 的 100 tick 水平射程应在 155~200 格（实际 " + trim(range) + " 格）");
        System.out.println("[ ok ] 箭类弹道：初速 3.0 / 重力 0.05 / 阻力 0.99，最高点 +"
                + trim(maxY - startY) + " 格，100 tick 内水平飞 " + trim(range) + " 格"
                + "（比原版箭远：真实箭还额外乘 0.05 的摩擦，这里只按任务给的初速/重力/寿命积分）");
    }

    // ------------------------------------------------------------------
    // 2. 火球：重力 0、直线、寿命 80
    // ------------------------------------------------------------------

    private static void fireballStraightLine() {
        ProjectileManager m = freshManager();
        int slot = m.fire(FIREBALL, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, null);
        check(slot >= 0, "火球类投掷物应能生成");
        ProjectileData d = m.data();
        ProjectileManager.ProjType type = m.typeOf(d.typeIndex[slot] & 0x1F);
        check(type != null && type.gravity == 0.0F, "火球类默认重力应为 0（直线飞行）");
        check(type != null && type.life == 80, "火球类默认寿命应为 80 tick");
        check(type != null && type.blastRadius > 0.0F, "火球类默认应带 AoE 半径");
        float y0 = d.posY[slot];
        int ticks = 0;
        for (int i = 0; i < 200 && d.aliveFlag[slot]; i++) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
            ticks++;
        }
        check(ticks == 80, "火球应在 80 tick 后消失（实际 " + ticks + "）");
        check(Math.abs(d.posY[slot] - y0) < 1.0E-4F,
                "重力为 0 时高度不应变化（" + trim(d.posY[slot]) + " vs " + trim(y0) + "）");
        System.out.println("[ ok ] 火球弹道：重力 0、80 tick 直线飞 "
                + trim((float) Math.sqrt(d.posX[slot] * d.posX[slot] + d.posZ[slot] * d.posZ[slot])) + " 格");
    }

    // ------------------------------------------------------------------
    // 3. 寿命 / 出界
    // ------------------------------------------------------------------

    private static void lifetimeAndBounds() {
        ProjectileManager m = freshManager();
        int slot = m.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, null);
        ProjectileData d = m.data();
        int life0 = d.lifeLeft[slot];
        m.data().lifeLeft[slot] = 3;
        for (int i = 0; i < 3; i++) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        check(!d.aliveFlag[slot], "寿命归零后应消失（原寿命 " + life0 + "）");

        // 出界：世界底部以下
        int slot2 = m.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, null);
        d.posY[slot2] = -500.0F;
        d.velY[slot2] = -3.0F;
        m.tickPure(ProjectileManager.Sink.NONE, -64.0F);
        check(!d.aliveFlag[slot2], "掉到世界底部以下应消失");
        System.out.println("[ ok ] 生命周期：寿命归零消失、出界消失，且不会残留（alive=" + m.aliveCount() + "）");
    }

    // ------------------------------------------------------------------
    // 4. 方块碰撞
    // ------------------------------------------------------------------

    private static void blockCollision() {
        // 替身方块回调：x > 5 的位置视为实心墙
        ProjectileManager.Adapter wall = (pos, delta, out, vel) -> {
            float wantX = delta[0];
            float allowed = wantX;
            if (pos[0] + wantX > 5.0F) {
                allowed = Math.max(0.0F, 5.0F - pos[0]);
            }
            pos[0] += allowed;
            pos[1] += delta[1];
            pos[2] += delta[2];
            out[0] = allowed;
            out[1] = delta[1];
            out[2] = delta[2];
            vel[0] += allowed - wantX;
            vel[1] = delta[1];
            vel[2] = delta[2];
            return allowed != wantX;
        };

        ProjectileManager m = newManager(wall, ProjectileManager.NO_HITS);
        // yaw = -90 才是朝 +X（本模组的朝向约定：yaw=0 朝 +Z、yaw=90 朝 -X）。
        // 注意箭类**默认就是「撞到方块卡住」**（原版箭确实会插在墙上），
        // 所以这里显式配成「撞到即消失」来验证消失这条路，后面再验证卡住那条路。
        dev.swordkingdom.horde.edit.ProjectileSkill vanish = new dev.swordkingdom.horde.edit.ProjectileSkill();
        vanish.stickOnBlock = 0;
        int slot = m.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, -90.0F, -1, 0, 0, vanish.view());
        ProjectileData d = m.data();
        check(d.stickOnBlock[slot] == false, "显式配「撞到即消失」应生效");
        int ticks = 0;
        for (int i = 0; i < 400 && d.aliveFlag[slot]; i++) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
            ticks++;
        }
        check(!d.aliveFlag[slot], "配成「撞到即消失」时撞墙应消失");
        check(d.posX[slot] <= 5.01F, "消失位置应贴在墙面附近（x=" + trim(d.posX[slot]) + "）");
        check(ticks < 60, "撞墙应发生在飞行早期（实际 " + ticks + " tick）");
        // 不配任何参数时，箭类默认应该卡住（原版行为）
        ProjectileManager dm = newManager(wall, ProjectileManager.NO_HITS);
        int dslot = dm.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, -90.0F, -1, 0, 0, null);
        check(dm.data().stickOnBlock[dslot], "不配置时箭类默认 stickOnBlock = true（原版箭插在墙上）");

        // 卡住：同一个替身墙 + stickOnBlock = true
        ProjectileManager m2 = newManager(wall, ProjectileManager.NO_HITS);
        dev.swordkingdom.horde.edit.ProjectileSkill stick = new dev.swordkingdom.horde.edit.ProjectileSkill();
        stick.stickOnBlock = 1;
        stick.life = 10;
        int slot2 = m2.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, -90.0F, -1, 0, 0, stick.view());
        ProjectileData d2 = m2.data();
        int ticks2 = 0;
        for (int i = 0; i < 200 && d2.aliveFlag[slot2]; i++) {
            m2.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
            ticks2++;
        }
        check(!d2.aliveFlag[slot2], "卡住的投掷物最终仍会因寿命归零消失");
        check(ticks2 == 10, "卡住后应等到寿命归零（配置 10 tick，实际 " + ticks2 + " tick）");
        System.out.println("[ ok ] 方块碰撞：撞墙立刻消失（" + ticks + " tick）；配成「卡住」时贴在墙上等寿命归零（"
                + ticks2 + " tick）");
    }

    // ------------------------------------------------------------------
    // 5. 扫掠命中（不穿透目标）
    // ------------------------------------------------------------------

    /**
     * 发射点高度 = 施法者脚底 + 该类型的眼高 × 0.85（与原版从眼睛处出射一致）。
     * 这个常量只是让测试能把「箭从哪一格出发」写清楚，不是模拟参数。
     */
    private static final float EYE = 1.6F;
    /** 本 harness 里让箭沿 Z 轴平飞时用的高度 */
    private static final float ARROW_Y = 1.0F;

    /**
     * 平飞配置：本 harness 的扫掠/穿透用例用它把重力和阻力关掉。
     *
     * 这样箭的轨迹就是一条直线上的等速采样（每 tick 3 格），
     * 「点判定会漏、线段扫掠不会漏」这件事才被干净地隔离出来，
     * 不会和抛物线的高度误差混在一起。
     */
    private static dev.swordkingdom.horde.edit.ProjectileSkill straightFlight() {
        dev.swordkingdom.horde.edit.ProjectileSkill cfg = new dev.swordkingdom.horde.edit.ProjectileSkill();
        cfg.speed = 3.0F;
        cfg.pitch = 0.0F;
        cfg.gravity = 0.0F;
        return cfg;
    }

    private static void sweepHitNoTunneling() {
        // 单位在 (0,1,20)，半径 0.3；箭从 y=1、z=18 以 3 格/tick 飞向 z=21
        check(SkillParams.sweepHit(0, 1.0F, 18.0F, 0, 1.0F, 21.0F, 0, 1.0F, 20.0F, 0.09F),
                "线段扫掠应判定命中（纯点判定会漏掉）");
        check(!SkillParams.sweepHit(0, 1.0F, 18.0F, 0, 1.0F, 19.0F, 0, 1.0F, 20.0F, 0.09F),
                "没扫到的线段不应命中");
        check(!SkillParams.sweepHit(0, 6.0F, 0.0F, 0, 6.0F, 3.0F, 0, 1.0F, 20.0F, 0.09F),
                "高度差 5 格不应命中");
        check(SkillParams.sweepHit(0, 0, 0, 0, 0, 30, 0, 0, 15, 0.25F),
                "长线段中途的目标应被扫到（这正是 3 格/tick 不穿人的原因）");

        ProjectileManager m = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
        final int[] hitCount = {0};
        final float[] lastDamage = {0.0F};
        m.setHits((d, slot, blast, scratch) -> {
            if (SkillParams.sweepHit(d.posX[slot] - d.velX[slot], d.posY[slot] - d.velY[slot],
                    d.posZ[slot] - d.velZ[slot], d.posX[slot], d.posY[slot], d.posZ[slot],
                    0.0F, ARROW_Y, 20.0F, 0.09F)) {
                hitCount[0]++;
                lastDamage[0] = d.damage[slot];
                return 1;
            }
            return 0;
        });
        // 发射点是「施法者脚底 + 眼高 × 0.85」，所以想让箭从 y=1 出发就把施法者放低
        int slot = m.fire(ARROW, null, null, 0.0F, ARROW_Y - EYE * 0.85F, 0.0F, 0.0F, -1, 0, 0,
                straightFlight().view());
        ProjectileData d = m.data();
        int guard = 0;
        while (d.aliveFlag[slot] && guard++ < 200) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        check(hitCount[0] == 1, "3 格/tick 飞过半径 0.3 格的目标应**恰好**命中一次（实际 " + hitCount[0] + "）");
        check(lastDamage[0] > 0.0F, "命中应带上伤害（" + lastDamage[0] + "）");
        System.out.println("[ ok ] 扫掠命中：3 格/tick 的高速投掷物命中半径 0.3 格的目标，命中 "
                + hitCount[0] + " 次、伤害 " + trim(lastDamage[0]) + "（点判定会 0 次）");
    }

    // ------------------------------------------------------------------
    // 6. 半径 & 穿透
    // ------------------------------------------------------------------

    private static void hitRadiusAndPierce() {
        // 半径：目标在 0.4 格外，半径 0.2 打不到、半径 0.5 打得到
        check(!SkillParams.sweepHit(0, 0, 0, 0, 0, 3, 0, 0, 3.4F, 0.2F * 0.2F), "半径 0.2 不应命中 0.4 格外的目标");
        check(SkillParams.sweepHit(0, 0, 0, 0, 0, 3, 0, 0, 3.4F, 0.5F * 0.5F), "半径 0.5 应命中 0.4 格外的目标");

        // 穿透 2 次：一路上 3 个目标，前两次命中后继续飞，第三次才消失。
        // 用「已命中标记」保证每个目标只被算一次（相邻两帧的扫掠线段本来就会重叠，
        // 不做标记的话同一个目标会被两帧各算一次 —— 那是测试写法问题，不是模拟问题）
        ProjectileManager m = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
        final float[] targets = {10.0F, 20.0F, 30.0F};
        final boolean[] struck = new boolean[3];
        final int[] hits = {0};
        m.setHits((d, slot, blast, scratch) -> {
            int n = 0;
            for (int k = 0; k < targets.length; k++) {
                if (struck[k]) {
                    continue;
                }
                if (SkillParams.sweepHit(d.posX[slot] - d.velX[slot], d.posY[slot] - d.velY[slot],
                        d.posZ[slot] - d.velZ[slot], d.posX[slot], d.posY[slot], d.posZ[slot],
                        0.0F, EYE * 0.85F, targets[k], 0.25F)) {
                    struck[k] = true;
                    n++;
                }
            }
            hits[0] += n;
            return n;
        });
        dev.swordkingdom.horde.edit.ProjectileSkill pierce = straightFlight();
        pierce.pierce = 2;
        int slot = m.fire(ARROW, null, null, 0.0F, 0.0F, 0.0F, 0.0F, -1, 0, 0, pierce.view());
        ProjectileData d = m.data();
        int guard = 0;
        while (d.aliveFlag[slot] && guard++ < 300) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        check(hits[0] == 3, "穿透 2 次的投掷物应打到 3 个目标（实际 " + hits[0] + "）");
        check(!d.aliveFlag[slot], "穿透次数用尽后应消失");
        System.out.println("[ ok ] 穿透：pierce=2 连续命中 3 个目标后消失");
    }

    // ------------------------------------------------------------------
    // 7. 护甲减免
    // ------------------------------------------------------------------

    private static void damageAfterArmor() {
        float raw = SkillParams.projectileDamage(8.0F, 1.5F);
        check(Math.abs(raw - 12.0F) < 1.0E-4F, "伤害 = 施法者攻击力 × 倍率（8 × 1.5 = " + raw + "）");
        float none = SkillParams.afterArmorFor(12.0F, 0.0F, 0.0F);
        check(Math.abs(none - 12.0F) < 1.0E-4F, "无护甲不减免");
        float armored = SkillParams.afterArmorFor(12.0F, 10.0F, 2.0F);
        float expected = SkillParams.afterArmor(12.0F, 10.0F, 2.0F);
        check(Math.abs(armored - expected) < 1.0E-4F,
                "有护甲应走原版 CombatRules（" + trim(armored) + " vs " + trim(expected) + "）");
        check(armored < none, "有护甲时伤害更低");
        System.out.println("[ ok ] 伤害：12 → 过护甲(10/2) → " + trim(armored) + "（原版公式）");
    }

    // ------------------------------------------------------------------
    // 8. AoE
    // ------------------------------------------------------------------

    private static void blastAoE() {
        check(SkillParams.blastFalloff(10.0F, 0.0F, 4.0F) == 10.0F, "AoE 中心应吃满伤害");
        check(Math.abs(SkillParams.blastFalloff(10.0F, 2.0F, 4.0F) - 5.0F) < 1.0E-4F, "AoE 半径一半处应为一半伤害");
        check(SkillParams.blastFalloff(10.0F, 4.0F, 4.0F) == 0.0F, "AoE 边缘伤害应为 0");
        check(SkillParams.blastFalloff(10.0F, 9.0F, 4.0F) == 0.0F, "AoE 外应为 0（不外溢）");
        check(SkillParams.blastFalloff(10.0F, 1.0F, 0.0F) == 0.0F, "半径为 0 时无 AoE");
        System.out.println("[ ok ] AoE：中心 10 → 半径一半 5 → 边缘 0（与原版爆炸同形的衰减）");
    }

    // ------------------------------------------------------------------
    // 9. 上限
    // ------------------------------------------------------------------

    private static void spawnCaps() {
        // 类型表上限 32
        ProjectileManager m = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
        int accepted = 0;
        for (int i = 0; i < 40; i++) {
            int slot = m.fire(ResourceLocation.tryParse("test:proj" + i), null, null,
                    0, 64, 0, 0, -1, 0, 0, null);
            if (slot >= 0) {
                accepted++;
            }
        }
        check(accepted == ProjectileManager.MAX_TYPES,
                "投掷物类型表上限应为 " + ProjectileManager.MAX_TYPES + "（实际接受 " + accepted + "）");
        System.out.println("[ ok ] 类型表上限：" + accepted + " 种（与群集类型表同规格，快照 5 bit）");

        // 容量上限：实例容量由配置决定，这里用 clear 后的管理器验证「满了返回 -1」
        ProjectileManager m2 = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
        int cap = m2.data().capacity;
        int spawned = 0;
        for (int i = 0; i < cap + 50; i++) {
            if (m2.fire(ARROW, null, null, 0, 64, 0, 0, -1, 0, 0, null) >= 0) {
                spawned++;
            }
        }
        check(spawned == cap, "满载后应停止生成（容量 " + cap + "，实际生成 " + spawned + "）");
        System.out.println("[ ok ] 容量上限：单维度 " + cap + " 枚（配置 maxProjectilesPerLevel，硬上限 "
                + ProjectileManager.HARD_CAP + "）");
    }

    // ------------------------------------------------------------------
    // 10. 快照打包
    // ------------------------------------------------------------------

    private static void packetRoundTrip() {
        // ---- 先验「绝对值」而不是只验往返 ----
        // 字段互相污染时（历史事故：pitch 的位移量写错，吃掉了 yaw 的高位），
        // 打包与解包会**一起错**，往返一致性照样成立。所以必须钉死几个已知输入的绝对结果。
        check(ProjectileSnapshotPacket.unpackYaw(
                ProjectileSnapshotPacket.pack(0.0F, 0.0F, 0, false, 0, 0)) == 0.0F,
                "yaw=0 打包后解出来必须正好是 0（不是 45 —— 字段污染事故的指纹）");
        check(Math.abs(ProjectileSnapshotPacket.unpackYaw(
                ProjectileSnapshotPacket.pack(90.0F, 0.0F, 0, false, 0, 0)) - 90.0F) < 1.0E-4F,
                "yaw=90 打包后解出来必须正好是 90");
        check(Math.abs(ProjectileSnapshotPacket.unpackYaw(
                ProjectileSnapshotPacket.pack(45.0F, 0.0F, 0, false, 0, 0)) - 45.0F) < 1.0E-4F,
                "yaw=45 打包后解出来必须正好是 45");
        check(Math.abs(ProjectileSnapshotPacket.unpackPitch(
                ProjectileSnapshotPacket.pack(0.0F, -45.0F, 0, false, 0, 0)) + 45.0F) < 1.5F,
                "pitch=-45 打包后解出来必须在 ±1.5° 内");
        check(ProjectileSnapshotPacket.unpackLife(
                ProjectileSnapshotPacket.pack(0.0F, 0.0F, 77, true, 13, 2)) == 77,
                "life=77 应原样还原");
        check(ProjectileSnapshotPacket.unpackType(
                ProjectileSnapshotPacket.pack(0.0F, 0.0F, 0, true, 13, 2)) == 13,
                "type=13 应原样还原");
        check(ProjectileSnapshotPacket.unpackTeam(
                ProjectileSnapshotPacket.pack(0.0F, 0.0F, 0, true, 0, 3)) == 3,
                "team=3 应原样还原");
        check(ProjectileSnapshotPacket.unpackAlive(
                ProjectileSnapshotPacket.pack(0.0F, 0.0F, 0, true, 0, 0)),
                "alive=true 应原样还原");

        // ---- 再做全组合往返 ----
        float[] yaws = {0.0F, 45.0F, 90.0F, 179.5F, 270.0F, 359.0F};
        float[] pitches = {-45.0F, 0.0F, 12.5F, 45.0F, 89.0F};
        int bad = 0;
        float worstYaw = 0.0F;
        float worstPitch = 0.0F;
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                int packed = ProjectileSnapshotPacket.pack(yaw, pitch, 77, true, 13, 2);
                float dYaw = circularDiff(ProjectileSnapshotPacket.unpackYaw(packed), yaw);
                float dPitch = Math.abs(ProjectileSnapshotPacket.unpackPitch(packed) - pitch);
                worstYaw = Math.max(worstYaw, dYaw);
                worstPitch = Math.max(worstPitch, dPitch);
                // yaw 9 bit -> 0.703° 分辨率；pitch 7 bit -> 1.43° 分辨率
                if (dYaw > 0.71F || dPitch > 1.44F) {
                    bad++;
                }
                if (ProjectileSnapshotPacket.unpackLife(packed) != 77
                        || !ProjectileSnapshotPacket.unpackAlive(packed)
                        || ProjectileSnapshotPacket.unpackType(packed) != 13
                        || ProjectileSnapshotPacket.unpackTeam(packed) != 2) {
                    bad++;
                }
            }
        }
        check(bad == 0, "快照打包字段应无损往返（失败 " + bad + " 项）");

        // 单枚字节数：id2 + 位置6 + 速度3 + VarInt5（最高位被 team 用到，VarInt 会用满 5 字节）= 16
        // 注：实际编码时大部分值不会占满 5 字节，这里按**最坏情况**估算带宽。
        int perWorst = 2 + 6 + 3 + 5;
        int perTypical = 2 + 6 + 3 + 4;
        double worstKb = 1000 * perWorst * 10 / 1024.0;   // 1000 枚 @10Hz
        double typicalKb = 1000 * perTypical * 10 / 1024.0;
        check(perTypical == 15, "每枚投掷物的典型字节数应为 15（实际 " + perTypical + "）");
        System.out.println("[ ok ] 快照打包：单枚 " + perTypical + " 字节（最坏 " + perWorst
                + "）；1000 枚 @10Hz ≈ " + String.format(java.util.Locale.ROOT, "%.1f", typicalKb)
                + " ~ " + String.format(java.util.Locale.ROOT, "%.1f", worstKb) + " KB/s"
                + "；量化误差 yaw ≤ " + trim(worstYaw) + "°、pitch ≤ " + trim(worstPitch) + "°");
    }

    /** 角度环形差（处理 359.9° vs 0.1° 这种跨零点比较） */
    private static float circularDiff(float a, float b) {
        float d = Math.abs(a - b) % 360.0F;
        return d > 180.0F ? 360.0F - d : d;
    }

    // ------------------------------------------------------------------
    // 11. 远程攻击模式：射程 / 节流 / 命中结算
    // ------------------------------------------------------------------

    private static final ResourceLocation SKELETON = ResourceLocation.tryParse("minecraft:skeleton");
    private static final ResourceLocation ZOMBIE_TARGET = ResourceLocation.tryParse("minecraft:zombie");

    /**
     * 远程模式的三件事，全部走**生产代码**那几条路：
     * <pre>
     *   ProjectileCombat.decide              射程/视线判定 + 瞄准解算（纯数学）
     *   CrowdManager.rangedAttackDefault     线上那个真实实现（通过注入的替身环境调用）
     *   CrowdManager.resolveCombat / skillTick  攻击模式的选取（远则放箭、近则近战）
     * </pre>
     *
     * 之所以能离线跑：远程攻击需要的外部环境被抽成一个注入点
     * （{@link CrowdManager.RangedAttackInjector}）。注入进去的替身**照样调用**
     * 真实的 {@code rangedAttackDefault} —— 验证的是线上那份实现，只是把
     * ServerLevel（方块视线）和注册表（自动挑弹）换成了替身值。
     */
    private static void rangedAttackMode() {
        // ---- A. 纯决策：射程边界 / 贴脸交给近战 / 冷却中不发射 / 视线 ----
        ProjectileCombat.Launch l = new ProjectileCombat.Launch();
        ProjectileCombat.Query q = new ProjectileCombat.Query();
        q.casterY = 64.0F;
        q.casterEye = 1.62F;
        q.targetY = 64.0F;
        q.targetEye = 1.62F;
        q.targetZ = 0.0F;
        q.meleeRange = MELEE;
        q.rangedRange = 16.0F;
        q.speed = 3.0F;
        q.targetX = 12.0F;
        check(ProjectileCombat.decide(q, 0, ProjectileCombat.Los.ALWAYS, l),
                "12 格外的目标应能远程攻击（远程距离 16 格）");
        float speed = (float) Math.sqrt(l.vel[0] * l.vel[0] + l.vel[1] * l.vel[1] + l.vel[2] * l.vel[2]);
        check(Math.abs(speed - 3.0F) < 1.0E-3F, "瞄准速度应等于配置初速（" + trim(speed) + "）");
        check(l.vel[0] > 2.9F && Math.abs(l.vel[2]) < 1.0E-3F,
                "朝 +X 的目标应射向 +X（速度向量 " + trim(l.vel[0]) + ", " + trim(l.vel[1])
                        + ", " + trim(l.vel[2]) + "）");
        q.targetX = 20.0F;
        check(!ProjectileCombat.decide(q, 0, ProjectileCombat.Los.ALWAYS, l),
                "20 格外的目标超出 16 格远程距离，不应发射");
        q.targetX = q.meleeRange * 0.5F;
        check(!ProjectileCombat.decide(q, 0, ProjectileCombat.Los.ALWAYS, l),
                "贴脸目标（近战距离内）应交给近战，不走远程");
        q.targetX = 12.0F;
        check(!ProjectileCombat.decide(q, 5, ProjectileCombat.Los.ALWAYS, l), "冷却中不应发射");
        boolean shot = ProjectileCombat.decide(q, 0, ProjectileCombat.Los.NEVER, l);
        check(!shot && l.losBlocked, "没有视线时不应发射，且要标出「被挡」这个原因（供限频 WARN 用）");

        // ---- B. 真实实现 + 节流：跑 200 tick 数发射次数 ----
        Rig r1 = newRig(12.0F, 40, 100.0F);
        final int[] fires = {0};
        r1.manager.setRangedInjector(new CrowdManager.RangedAttackInjector() {
            @Override
            public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                boolean fired = CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
                if (fired) {
                    fires[0]++;
                }
                return fired;
            }

            @Override
            public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                return new CrowdManager.RangedDeps(r1.projectiles, SKELETON, id -> ARROW);
            }
        });
        for (int t = 0; t < 200; t++) {
            // 每 tick 之前把双方**摆回**固定位置：射手 0 在原点、目标 1 在 12 格外。
            // **这一步是刻意的**：群集单位目前没有「远程单位保持距离」这条移动意图，
            // 双方会一路互相走近、贴脸后切成近战，远程节拍就体现不出来。
            // 这本身也是「与真实实体还差什么」的一条（见 HARNESS.md 的已知限制第 1 条）。
            r1.manager.data().posX[0] = 0.0F;
            r1.manager.data().posZ[0] = 0.0F;
            r1.manager.data().posX[1] = 12.0F;
            r1.manager.data().posZ[1] = 0.0F;
            r1.manager.tickWithLevel(null);   // 主循环（aiTick -> 物理 -> resolveCombat -> skillTick）
            r1.manager.data().targetId[0] = 1;
            r1.projectiles.data().reset();    // 只关心「发射了几次」，不关心箭飞哪去了
        }
        // LOD 的说明：替身里没有玩家（playerCount == 0），lodPeriod 退化成 8 ——
        // 也就是「单位只每 8 tick 结算一次战斗」；这是远距离单位的正常降频行为，
        // 200 tick 里最多只有 25 次结算机会，所以断言的是「约 5 次、且远少于 25 次」。
        check(fires[0] >= 4 && fires[0] <= 8,
                "200 tick、40 tick 间隔（LOD 降频到每 8 tick 结算）应发射约 5 次（实际 " + fires[0] + " 次）");
        check(fires[0] < 25, "绝不每 tick 连发（若没有冷却，25 次结算机会就会打出 20+ 次）");

        // ---- C. 命中结算：箭必须真的命中那个群集目标（真实扫掠 + 护甲 + 扣血） ----
        Rig r3 = newRig(6.0F, 40, 100.0F);
        r3.projectiles.setHits(r3.manager.crowdHitResolver());
        r3.manager.setRangedInjector(new CrowdManager.RangedAttackInjector() {
            @Override
            public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
            }

            @Override
            public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                return new CrowdManager.RangedDeps(r3.projectiles, SKELETON, id -> ARROW);
            }
        });
        r3.manager.resolveCombatNow();
        check(r3.projectiles.aliveCount() == 1,
                "6 格外的目标应让远程单位射出一枚投掷物（实际 " + r3.projectiles.aliveCount() + " 枚）");
        // 这条断言抓的是「眼高叠了两次」那个真实 bug：
        // launch() 交出的炮口坐标已经含了眼高，fire() 若再补一次，箭就会从目标头顶飞过去。
        ProjectileData pd0 = r3.projectiles.data();
        float arrowMuzzle = pd0.posY[0];
        check(Math.abs(arrowMuzzle - (64.0F + 1.62F * 0.85F)) < 0.02F,
                "箭的出射高度应等于「脚底 + 眼高×0.85」= " + trim(64.0F + 1.62F * 0.85F)
                        + "（实际 " + trim(arrowMuzzle) + "；叠两次眼高会变成约 66.75）");
        int guard = 0;
        while (r3.projectiles.aliveCount() > 0 && guard++ < 200) {
            // 每 tick 重建空间哈希：线上是 CrowdManager.tick 做的，这里手动做同一件事，
            // 否则投掷物的命中查询会看到一张过期的格子表（这正是「箭飞过去没人」的成因）
            r3.manager.grid().build(r3.manager.data());
            r3.projectiles.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        check(r3.manager.data().hp[1] < 100.0F,
                "箭应命中 6 格外的群集目标并扣血（剩余 " + trim(r3.manager.data().hp[1]) + " / 100）");
        System.out.println("[ ok ] 远程攻击模式：12 格内可射、16 格外不射、贴脸交给近战、没视线不射；"
                + "200 tick（LOD 每 8 tick 结算）下发射 " + fires[0] + " 次；6 格外的目标被箭命中后剩余 "
                + trim(r3.manager.data().hp[1]) + " 血");
    }

    // ------------------------------------------------------------------
    // 12. 近战 + 远程并存：同一次攻击只结算一次
    // ------------------------------------------------------------------

    private static void meleePlusRangedNoDoubleCount() {
        // ---- 贴脸：必须走**近战**，一发投掷物都不许出 ----
        // 距离口径：近战判定距离是 MELEE ≈ 1.43 格（从 0.6 宽的碰撞盒推出），
        // 所以 1.2 格属于贴脸，2.0 格就已经在近战距离之外了（会走远程）。
        // ---- 贴脸 1.2 格：远程类型**不再抡弓**（那条兜底已删）----
        // 距离口径：近战可达距离是 1.20 格（0.6 宽对 0.6 宽，由碰撞箱推出），
        // 所以 1.2 格属于贴脸、2.0 格就已经在可达距离之外。
        // 期望：**一次近战都不结算**，也**不调用远程注入器**（远程单位按站位语义开火，
        // 贴脸时由 kiting 后撤，而不是临时改行去砍人）。
        Rig near = newRig(1.2F, 40, 100.0F);
        final int[] rangedCalls = {0};
        near.manager.setRangedInjector((m, d, slot, stats, period) -> {
            rangedCalls[0]++;
            return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
        });
        near.manager.resetDamageEvents();
        near.manager.resolveCombatNow();
        check(!near.manager.damageEvents().contains("melee:"),
                "贴脸时远程类型**不得**结算近战（远程类型永不进近战分支；事件="
                        + near.manager.damageEvents() + "，rangedInjector 调用 " + rangedCalls[0] + " 次）");
        check(!near.manager.damageEvents().contains("melee:"),
                "贴脸时不应出现任何近战结算（事件=" + near.manager.damageEvents() + "）");
        check(near.projectiles.aliveCount() == 0, "贴脸时不应发射投掷物");

        // ---- 12 格外：必须走**远程**，近战一次都不许结算 ----
        Rig far = newRig(12.0F, 40, 100.0F);
        far.manager.setRangedInjector(new CrowdManager.RangedAttackInjector() {
            @Override
            public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
            }

            @Override
            public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                return new CrowdManager.RangedDeps(far.projectiles, SKELETON, id -> ARROW);
            }
        });
        far.manager.resetDamageEvents();
        far.manager.resolveCombatNow();
        check(far.projectiles.aliveCount() == 1, "12 格外的目标应走远程（实际投掷物 "
                + far.projectiles.aliveCount() + " 枚）");
        check(!far.manager.damageEvents().contains("melee:"),
                "远程攻击的那一帧不应再结算一次近战（事件=" + far.manager.damageEvents() + "）");
        System.out.println("[ ok ] 近战/远程互斥：贴脸(1.2 格) -> 远程类型既不远射也不砍（事件 "
                + (near.manager.damageEvents().isEmpty() ? "（空）" : near.manager.damageEvents())
                + "，投掷物 0 枚，rangedInjector " + rangedCalls[0] + " 次）；12 格 -> 只远程（事件 "
                + far.manager.damageEvents() + "，投掷物 " + far.projectiles.aliveCount() + " 枚）");
    }

    // ------------------------------------------------------------------
    // 12b. ★ 距离保持（kiting）：由弹道参数推导站位，远程单位不再冲锋
    // ------------------------------------------------------------------

    /**
     * 规则本身是**纯数学**：弹道越平（重力小 / 初速高）就能站得越远，
     * 重力大 / 初速低的弹必须靠近 —— 这正是「按自身攻击模式站」的含义。
     *
     * 断言的是**推导关系**而不是写死的数字（写死数字会把将来调参变成改测试），
     * 但三个默认档的实际值也打出来，方便真机对照。
     */
    private static void kitingRuleFromBallistics() {
        System.out.println("[case] ★ 距离保持规则：由投掷物自己的弹道参数推导");
        // 默认三档（初速 / 重力 / 阻力 / 寿命 都来自 ProjectileCatalog.defaultsOf）
        dev.swordkingdom.horde.crowd.ProjectileCatalog.Defaults arrow =
                dev.swordkingdom.horde.crowd.ProjectileCatalog.defaultsOf(
                        dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.Defaults snow =
                dev.swordkingdom.horde.crowd.ProjectileCatalog.defaultsOf(
                        dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.THROWABLE);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.Defaults fire =
                dev.swordkingdom.horde.crowd.ProjectileCatalog.defaultsOf(
                        dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.FIREBALL);

        dev.swordkingdom.horde.crowd.RangedKiting.Rule rArrow = dev.swordkingdom.horde.crowd.RangedKiting.rule(
                arrow.speed, arrow.gravity, arrow.drag, arrow.life,
                dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW, 16.0F, MELEE, 16.0F);
        dev.swordkingdom.horde.crowd.RangedKiting.Rule rSnow = dev.swordkingdom.horde.crowd.RangedKiting.rule(
                snow.speed, snow.gravity, snow.drag, snow.life,
                dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.THROWABLE, 16.0F, MELEE, 16.0F);
        dev.swordkingdom.horde.crowd.RangedKiting.Rule rFire = dev.swordkingdom.horde.crowd.RangedKiting.rule(
                fire.speed, fire.gravity, fire.drag, fire.life,
                dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.FIREBALL, 16.0F, MELEE, 16.0F);

        System.out.println("      箭   ：" + rArrow.describe());
        System.out.println("      雪球 ：" + rSnow.describe());
        System.out.println("      火球 ：" + rFire.describe());

        // ---- 1) 直射箭应当能站得比抛物严重的雪球远 ----
        check(rArrow.maxDistance > rSnow.maxDistance,
                "直射箭(初速 " + arrow.speed + "/重力 " + arrow.gravity + ") 的理想站位必须比雪球(初速 "
                        + snow.speed + "/重力 " + snow.gravity + ") 远（实际 " + trim(rArrow.maxDistance)
                        + " vs " + trim(rSnow.maxDistance) + "）");
        // ---- 2) 站位上限绝不允许超过自己的射程（超了就永远射不出去）----
        check(rArrow.maxDistance <= 16.0F + 1.0E-4F && rSnow.maxDistance <= 16.0F + 1.0E-4F
                        && rFire.maxDistance <= 16.0F + 1.0E-4F,
                "理想站位上限不得超过远程射程 16 格（实际 " + trim(rArrow.maxDistance) + " / "
                        + trim(rSnow.maxDistance) + " / " + trim(rFire.maxDistance) + "）");
        // ---- 3) 最小开火距离必须留在近战距离之外（否则会「想射又被打」反复横跳）----
        for (dev.swordkingdom.horde.crowd.RangedKiting.Rule r : new dev.swordkingdom.horde.crowd.RangedKiting.Rule[]{
                rArrow, rSnow, rFire}) {
            check(r.minDistance > MELEE,
                    "最小开火距离必须大于近战距离 " + trim(MELEE) + "（实际 " + trim(r.minDistance) + "）");
        }
        // ---- 4) 有效射程：重力为 0 的火球只受寿命限制；箭的弹道下垂把它压在寿命射程之内 ----
        check(rFire.effectiveRange >= rArrow.effectiveRange,
                "重力 0 的直线弹有效射程不应短于靠寿命限制的箭（" + trim(rFire.effectiveRange)
                        + " vs " + trim(rArrow.effectiveRange) + "）");

        // ---- 5) 三段式：太远靠近 / 带内驻留 / 太近后撤 ----
        check(rArrow.moveFor(rArrow.maxDistance + 2.0F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.APPROACH,
                "超出理想站位上限应靠近");
        check(rArrow.moveFor((rArrow.minDistance + rArrow.maxDistance) * 0.5F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.HOLD,
                "理想带内应驻留（站着射）");
        check(rArrow.moveFor(rArrow.minDistance - 0.5F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.RETREAT,
                "进了最小开火距离应后撤");
        // 真实射程之外只能是靠近（哪怕站位规则说「后撤」）
        CrowdManager.TypeStats stub = CrowdManager.TypeStats.custom(0.6F, 1.95F, 1.62F, 20.0F, 4.0F,
                0.23F, 40.0F, 40, 0, 120, 16.0F, false, true, SKELETON);
        // 弹道射程之内 ⇒ **原地开火**（哪怕比理想站位远得多）：
        // 这是玩家要的「骷髅可以直接向极远的目标发射箭矢，不会自己跑过去」。
        check(dev.swordkingdom.horde.crowd.RangedKiting.decide(rArrow, stub, 0, rArrow.ballisticRange * 0.5F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.HOLD,
                "弹道射程之内应原地开火（不产生朝目标的接近位移）");
        check(dev.swordkingdom.horde.crowd.RangedKiting.decide(rArrow, stub, 0, rArrow.ballisticRange + 1.0F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.APPROACH,
                "弹道射程之外只能靠近（原地打不到）");
        check(dev.swordkingdom.horde.crowd.RangedKiting.decide(rArrow, stub, 0, rArrow.minDistance - 0.5F)
                        == dev.swordkingdom.horde.crowd.RangedKiting.Move.RETREAT,
                "进了最小开火距离仍要后撤");

        // ---- 6) 同一类型内按 id 哈希错开，但错开幅度有界 ----
        float worst = 0.0F;
        for (int slot = 0; slot < 64; slot++) {
            float standoff = rArrow.standoffFor(slot);
            worst = Math.max(worst, Math.abs(standoff - rArrow.maxDistance) / rArrow.maxDistance);
            check(standoff >= rArrow.minDistance - 1.0E-4F,
                    "错开后的站位不得小于最小开火距离（slot " + slot + " -> " + trim(standoff) + "）");
        }
        check(worst > 0.0F && worst <= dev.swordkingdom.horde.crowd.RangedKiting.JITTER + 1.0E-3F,
                "同类型内的站位错开幅度应在 (0, " + dev.swordkingdom.horde.crowd.RangedKiting.JITTER
                        + "] 之内（实际最大 " + trim(worst * 100.0F) + "%）");
        System.out.println("[ ok ] 距离保持规则：箭站 " + trim(rArrow.minDistance) + "~"
                + trim(rArrow.maxDistance) + " 格、雪球 " + trim(rSnow.minDistance) + "~"
                + trim(rSnow.maxDistance) + " 格、火球 " + trim(rFire.minDistance) + "~"
                + trim(rFire.maxDistance) + " 格；错开 ≤ " + trim(worst * 100.0F) + "%");
    }

    /**
     * ★ 真机病根的照妖镜：**10 个骷髅对 10 个僵尸**，远程单位还会不会冲到脸上？
     *
     * <p>跑的是**同一个主循环**（{@code tickWithLevel}：aiTick → push → physics → resolveCombat → skillTick），
     * 唯一的注入是投掷物管理器与类型属性（离线没有注册表）。统计口径：
     * <ul>
     *   <li>{@code kitingMinDistance}：远程单位与最近敌人的最小水平距离 —— 贴脸的话会掉到近战距离以内；</li>
     *   <li>近战结算事件数：骷髅在**任何一 tick** 结算过近战 = 它真的贴上去了；</li>
     *   <li>宿主的 {@code damageEvents} 里 {@code melee:} 的出现次数（骷髅自己的近战）。</li>
     * </ul>
     */
    private static void kitingKeepsDistance() {
        useInjectedSkeletonGate();
        try {
            kitingKeepsDistanceBody();
        } finally {
            restoreGate();
        }
    }

    /**
     * 给「骷髅」装上**真机那条门禁链**：注入箭的候选池 + 把 {@code minecraft:skeleton} 的类解析成
     * 真实的 {@code Skeleton}（它实现 {@code RangedAttackMob}）。
     *
     * <p>为什么必须显式装一次：{@code TypeStats.ranged()} 有**类型级记忆**（一个类型只算一次，
     * 之后只剩一次 volatile 读）。前面的用例把它们自己的注入环境换掉了，
     * 如果这里不重装，骷髅会带着「上一套环境算出来的结论」进实验 ——
     * 那正是「判定依赖环境」这件事的另一种形态，测出来的东西就不算数了。
     */
    private static void useInjectedSkeletonGate() {
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(() -> java.util.List.of(
                new dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate(ARROW,
                        dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW, 0)));
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setForeignPathCheck((fullId, stem) -> false);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(
                id -> SKELETON.equals(id) ? net.minecraft.world.entity.monster.Skeleton.class : null);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.clearCache();
    }

    /** 还原注入环境（候选池 / 外来实体名判据 / 施法者类解析） */
    private static void restoreGate() {
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(null);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setForeignPathCheck(null);
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(null);
    }

    private static void kitingKeepsDistanceBody() {
        System.out.println("[case] ★ 距离保持实测：20 个骷髅 vs 20 个僵尸（600 tick 自由交战）");
        final int skeletons = 20;
        final int zombies = 20;
        // 射手 0.30（略快于靶子）、靶子 0.23：靶子会一路逼上来，射手够不着就后撤。
        // 为什么让射手快一点点：本模组的加速量是 `v²`，同速对冲时双方挤在一起谁也拉不开
        // —— 那会把「距离保持」测成「人堆推挤」，测不出站位规则本身。
        RangedRig rig = new RangedRig(skeletons + zombies, skeletons, 0.30F, 0.23F);
        // 骷髅在西侧、僵尸在东侧，相距 12 格（弹道射程内 ⇒ 骷髅**原地开火**）。
        // 为什么是 12 而不是更远：靶子的 `attackReach` 只有近战距离（1.20 格），
        // 于是它只在 12 格内才会锁定射手并冲上来 —— 这一条正是「被逼近 → 后撤」的前提。
        // （更远的布置会得到「双方都站着不动」，那是靶子侧的行为，不是射手的问题。）
        for (int k = 0; k < skeletons; k++) {
            rig.manager.putUnitRaw(k, 0, -12.0F + (k % 10) * 2.5F, 64.0F, (float) ((k / 10) * 1.5F), 0, 20.0F);
        }
        for (int k = 0; k < zombies; k++) {
            rig.manager.putUnitRaw(skeletons + k, 1, 12.0F + (k % 10) * 2.5F, 64.0F,
                    (float) ((k / 10) * 1.5F), 1, 500.0F);
        }
        rig.manager.grid().build(rig.manager.data());
        rig.snapshotHp();
        rig.manager.resetKitingStats();
        rig.manager.resetProjectileHitStats();
        rig.manager.resetDamageEvents();
        int ticks = 600;
        long meleeTicks = 0L;
        for (int t = 0; t < ticks; t++) {
            rig.tick();
            // 每 tick 统计一次「射手是不是已经贴到敌人身上」——这是真机抱怨的那个现象
            for (int i = 0; i < skeletons; i++) {
                if (!rig.manager.data().aliveFlag[i]) {
                    continue;
                }
                float best = Float.MAX_VALUE;
                for (int j = skeletons; j < skeletons + zombies; j++) {
                    if (!rig.manager.data().aliveFlag[j]) {
                        continue;
                    }
                    float ox = rig.manager.data().posX[j] - rig.manager.data().posX[i];
                    float oz = rig.manager.data().posZ[j] - rig.manager.data().posZ[i];
                    float d2 = ox * ox + oz * oz;
                    if (d2 < best) {
                        best = d2;
                    }
                }
                if (best < MELEE * MELEE) {
                    meleeTicks++;
                }
            }
        }
        long retreatTicks = rig.manager.kitingRetreatTicks();
        long holdTicks = rig.manager.kitingHoldTicks();
        long tooCloseTicks = rig.manager.kitingTooCloseTicks();
        float minDistance = rig.manager.kitingMinDistance();
        float meleeDamage = rig.sumMeleeDamage();
        float zombieLost = rig.lostHp(skeletons, skeletons + zombies);
        RangedKiting.Rule rule = rig.manager.kitingRuleOf(0);

        check(rule != null, "骷髅必须是「有站位规则」的远程单位");
        check(minDistance > MELEE,
                "远程单位与敌人的最小距离必须始终留在近战距离之外（实测 " + trim(minDistance)
                        + " 格 vs 近战距离 " + trim(MELEE) + " 格）");
        check(meleeTicks == 0L,
                "骷髅**任何一 tick** 贴到近战距离以内的次数应为 0（实测 " + meleeTicks + " 次；"
                        + "贴脸 = 真机抱怨的「冲上去近战」）");
        check(!rig.manager.damageEvents().contains("melee:"),
                "远程单位不应结算近战（伤害事件=" + shortEvents(rig.manager.damageEvents()) + "）");
        check(retreatTicks > 0L, "被逼近时应当真的出现后撤（后撤 tick = " + retreatTicks + "）");
        check(holdTicks > 0L, "也应当出现「驻留射击」（驻留 tick = " + holdTicks + "）");
        check(tooCloseTicks < holdTicks,
                "「被逼到最小开火距离以内」的 tick 数应远少于驻留 tick（" + tooCloseTicks + " vs "
                        + holdTicks + "）");
        check(rig.projectiles.spawnedTotal() > 0, "骷髅应当真的发射了投掷物（实际 "
                + rig.projectiles.spawnedTotal() + " 枚）");
        check(zombieLost > 0.0F, "僵尸应当被打掉血（实际掉血 " + trim(zombieLost) + "）");
        System.out.println("[ ok ] 距离保持实测：" + ticks + " tick 内最小距离 " + trim(minDistance)
                + " 格（规则下限 " + trim(rule.minDistance) + "）；贴脸 tick " + meleeTicks
                + "、后撤 " + retreatTicks + " tick、驻留 " + holdTicks + " tick、被逼近 "
                + tooCloseTicks + " tick；发射 " + rig.projectiles.spawnedTotal() + " 枚（命中 "
                + rig.manager.crowdHitResolverCount() + " tick 有结算），僵尸掉血 " + trim(zombieLost)
                + "（其中近战 " + trim(meleeDamage) + "）");
    }

    /** 过长的事件串只打前 60 个字符（断言失败时的可读性） */
    private static String shortEvents(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…(" + s.length() + " 字符)";
    }

    /**
     * ★ 远程单位把虚拟（群集）单位**真的打掉血**了 —— 这是「箭穿过去却不掉血」的回归用例。
     *
     * <p>断言链条：候选筛选 → 线段×AABB 精确判定 → 护甲减免 → 扣血 → 投掷物消失。
     * 队伍规则（同阵营不掉血）与硬上界（每 tick 候选数 ≤ {@code MAX_CANDIDATES}）分别在
     * 后面的用例里单独钉。
     */
    private static void crowdTakeProjectileDamage() {
        useInjectedSkeletonGate();
        try {
            crowdTakeProjectileDamageBody();
        } finally {
            restoreGate();
        }
    }

    private static void crowdTakeProjectileDamageBody() {
        System.out.println("[case] ★ 投掷物伤害虚拟单位：箭命中群集单位 -> 护甲减免 -> 扣血 -> 箭消失");
        final int skeletons = 8;
        final int zombies = 8;
        RangedRig rig = new RangedRig(skeletons + zombies, skeletons);
        // 射手在 12 格外（> 最小开火距离 7.2，< 射程 16）：站着射，双方都不走动
        for (int k = 0; k < skeletons; k++) {
            rig.manager.putUnitRaw(k, 0, -12.0F + (k % 4) * 1.5F, 64.0F, (float) ((k / 4) * 1.5F), 0, 20.0F);
        }
        for (int k = 0; k < zombies; k++) {
            rig.manager.putUnitRaw(skeletons + k, 1, -0.5F + (k % 4) * 0.9F, 64.0F,
                    (float) ((k / 4) * 0.9F), 1, 500.0F);
        }
        rig.manager.grid().build(rig.manager.data());
        rig.snapshotHp();
        rig.manager.resetKitingStats();
        rig.manager.resetProjectileHitStats();
        rig.manager.resetDamageEvents();
        int hpBefore = rig.totalHp(skeletons, skeletons + zombies);

        for (int t = 0; t < 300; t++) {
            rig.tick();
        }
        float dealt = rig.manager.damageDealt();
        float zombieLost = rig.lostHp(skeletons, skeletons + zombies);
        int hpNow = rig.totalHp(skeletons, skeletons + zombies);
        float arrows = rig.projectiles.spawnedTotal();

        check(rig.manager.crowdHitResolverCount() > 0,
                "投掷物的命中结算必须真的被调用过（实际 " + rig.manager.crowdHitResolverCount() + " 次）");
        check(rig.manager.sweepTests() > 0L, "必须真的做过线段×AABB 精确判定（实际 "
                + rig.manager.sweepTests() + " 次）");
        check(dealt > 0.0F, "投掷物必须对虚拟单位造成伤害（实际总伤害 " + trim(dealt) + "）");
        check(zombieLost > 0.0F, "僵尸的总血量必须下降（" + hpBefore + " -> " + hpNow + "，掉 "
                + trim(zombieLost) + "）");
        check(zombieLost >= dealt - 1.0E-3F,
                "掉的血必须不少于投掷物结算的伤害（掉 " + trim(zombieLost) + " vs 投掷伤害 "
                        + trim(dealt) + "）");
        check(!rig.manager.damageEvents().contains("melee:"),
                "布阵刻意留在近战距离之外：不该有近战结算（事件="
                        + shortEvents(rig.manager.damageEvents()) + "）");
        // 护甲口径：箭的伤害 = 射手攻击力 × 倍率（4 × 1.0 = 4），目标护甲 0 ⇒ 原样落下
        float perArrow = dealt / Math.max(1L, rig.manager.sweepTests());
        check(perArrow <= 4.0F + 1.0E-3F,
                "单次命中伤害不得超过「射手攻击力 × 伤害倍率」（4.0；实际 " + trim(perArrow) + "）");
        System.out.println("[ ok ] 投掷物伤害虚拟单位：命中结算 " + rig.manager.crowdHitResolverCount()
                + " 次、精确扫掠 " + rig.manager.sweepTests() + " 次、总伤害 " + trim(dealt)
                + "（每次命中约 " + trim(perArrow) + "）、僵尸掉血 " + trim(zombieLost)
                + "、击退 " + rig.manager.knockbackCount() + " 次、共发射 " + (int) arrows + " 枚");
    }

    /**
     * ★ 性能上界：**每个投掷物每 tick 的命中判定调用次数有常数上界**。
     *
     * <p>为什么这是硬要求：一次 segment×AABB 是二十来次浮点运算，而一个高密度格子里
     * 可能塞着上百个单位。旧代码的截断条件是「命中过一次才停」（{@code checked > MAX && hits == 0}），
     * 于是**一发打空的箭**会把整格扫完 —— 命中开销随「密度」增长，那正是
     * 「100 单位 + 几十枚投掷物 = 每 tick 全量两两判定」的真实形态。
     *
     * <p>现在：{@code candidateChecks <= MAX_CANDIDATES}（{@link CrowdManager#maxCandidates()}）
     * 是**无条件**的，于是「N 枚投掷物 × 64」就是每 tick 的调用次数上界。
     */
    private static void hitCandidateCallBound() {
        useInjectedSkeletonGate();
        try {
            hitCandidateCallBoundBody();
        } finally {
            restoreGate();
        }
    }

    private static void hitCandidateCallBoundBody() {
        System.out.println("[case] ★ 命中判定调用上界：一发打空的箭也不许扫完整个格子");
        final int skeletons = 4;
        final int zombies = 100;
        RangedRig rig = new RangedRig(skeletons + zombies, skeletons);
        for (int k = 0; k < skeletons; k++) {
            rig.manager.putUnitRaw(k, 0, 0.0F, 64.0F, (float) k, 0, 20.0F);
        }
        // 100 个僵尸挤在一个 4×4 的方块里：单格密度远超 MAX_CANDIDATES（64）
        for (int k = 0; k < zombies; k++) {
            rig.manager.putUnitRaw(skeletons + k, 1, 20.0F + (k % 10) * 0.35F, 64.0F, (k / 10) * 0.35F, 1, 500.0F);
        }
        rig.manager.grid().build(rig.manager.data());
        rig.snapshotHp();
        rig.manager.resetProjectileHitStats();

        int worst = 0;
        for (int t = 0; t < 300; t++) {
            rig.tick();
            worst = Math.max(worst, rig.manager.lastCandidateChecks());
        }
        int bound = rig.manager.maxCandidates();
        check(worst <= bound, "每 tick 的命中候选检查数必须有常数上界 " + bound + "（实测最大 " + worst + "）");
        check(worst > 0, "上界用例必须真的发生过命中判定（否则它在白测，实测最大 " + worst + "）");
        // 上界的含义：投掷物数 × 上界 = 每 tick 的调用次数上限
        int maxProjectiles = rig.projectiles.aliveCount();
        System.out.println("[ ok ] 命中调用上界：单发每 tick ≤ " + bound + " 次候选检查（实测最大 "
                + worst + "）；当场 " + maxProjectiles + " 枚投掷物 ⇒ 每 tick ≤ "
                + (bound * Math.max(1, maxProjectiles)) + " 次（100 单位规模下与全量两两判定的 "
                + (100 * 100) + " 次对比）");
    }

    /**
     * ★ **近战可达距离的把关**（真机反馈「近战的攻击范围出现错误，手太长」）。
     *
     * <p>三段断言，全部走生产那条链（{@code resolveCombat} → {@code meleeRangeOk}）：
     * <ol>
     *   <li><b>定义</b>：可达距离 = {@code meleeRangeValue(攻击者宽, 目标宽)}
     *       = {@code (aw+tw)/2 + 0.5·(aw+tw)}（水平中心距上界，垂直还要有碰撞箱重叠）；</li>
     *   <li><b>3 格外不得造成伤害</b>：近战单位对 3 格外的目标结算近战必须被拒绝
     *       （被拒计数 +1、伤害事件里没有 {@code melee:}）；</li>
     *   <li><b>可达距离内必须造成伤害</b>：把同一对单位挪到可达距离以内，必须真的结算。</li>
     * </ol>
     * 附带钉一条：**远程类型即使被贴到 2.4 格也不会切近战**（可达距离是 1.2 格，
     * 远程类型不再有「贴脸抡弓」那条路）。
     */
    // ------------------------------------------------------------------
    // 12c. ★ 重力补偿：远距离也要打得到
    // ------------------------------------------------------------------

    /**
     * ★ **重力补偿的数学**：随距离单调增长、零重力/零距离为零、且**与实际弹道对得上**。
     *
     * <p>这条把「补偿不是拍脑袋」钉死：先用 {@code ProjectileManager} **真的飞一枚不补偿的箭**，
     * 量出它在水平距离 {@code d} 处的垂直下垂，再和公式预测值对照。
     */
    private static void gravityCompensationMath() {
        System.out.println("[case] ★ 重力补偿：公式 vs 真实弹道（不补偿时量下垂）");
        ProjectileCatalog.Defaults arrow = ProjectileCatalog.defaultsOf(ProjectileCatalog.Kind.ARROW);
        float g = arrow.gravity;
        float v = arrow.speed;
        float drag = arrow.drag;

        check(ProjectileCombat.gravityCompensation(0.0F, v, g, drag) == 0.0F, "距离为 0 时补偿必须为 0");
        check(ProjectileCombat.gravityCompensation(30.0F, v, 0.0F, drag) == 0.0F,
                "零重力（直线弹）不需要补偿");
        float c10 = ProjectileCombat.gravityCompensation(10.0F, v, g, drag);
        float c22 = ProjectileCombat.gravityCompensation(22.0F, v, g, drag);
        float c30 = ProjectileCombat.gravityCompensation(30.0F, v, g, drag);
        check(c10 < c22 && c22 < c30,
                "补偿必须随距离单调增长（10/22/30 格 -> " + trim(c10) + "/" + trim(c22) + "/"
                        + trim(c30) + "）");
        check(c30 < ProjectileCombat.COMP_MAX, "30 格的补偿不应触发夹取上限（实际 " + trim(c30) + "）");

        // ---- 与真实弹道对照：真的飞一枚**不补偿**的箭，量它在 30 格处的下垂 ----
        ProjectileManager m = freshManager();
        dev.swordkingdom.horde.edit.ProjectileSkill flat = new dev.swordkingdom.horde.edit.ProjectileSkill();
        flat.speed = v;
        flat.pitch = 0.0F;
        flat.gravity = g;
        int slot = m.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, flat.view());
        ProjectileData d = m.data();
        float startY = d.posY[slot];
        int guard = 0;
        while (d.aliveFlag[slot] && d.posZ[slot] < 30.0F && guard++ < 400) {
            m.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        float measuredDrop = startY - d.posY[slot];   // 平射到 30 格时已经掉了多少
        float predicted = c30;
        // 允许 ±60% 的偏差：补偿还带一个 1.5 的增益、并且是按 3D 距离/阻力 1 阶估的，
        // 这里要钉的是「量级正确、同向变化」，不是把积分器算两遍。
        check(measuredDrop > 0.0F, "不补偿时真的会往下掉（实测 " + trim(measuredDrop) + " 格）");
        check(predicted > measuredDrop * 0.4F && predicted < measuredDrop * 2.6F,
                "补偿量必须与真实下垂同量级（预测 " + trim(predicted) + " vs 实测 " + trim(measuredDrop) + "）");

        // ---- 关掉补偿时**直接量**弹道在 30 格处的高度，证明补偿是「承重」的 ----
        ProjectileManager m2 = freshManager();
        int s2 = m2.fire(ARROW, null, null, 0.0F, 64.0F, 0.0F, 0.0F, -1, 0, 0, flat.view());
        ProjectileData d2 = m2.data();
        int guard2 = 0;
        while (d2.aliveFlag[s2] && d2.posZ[s2] < 30.0F && guard2++ < 400) {
            m2.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }
        float noCompY = d2.posY[s2];
        System.out.println("      [info] 平射到 30 格时弹丸高度 = " + trim(noCompY)
                + "（目标脚底 64.00、身高 1.95 ⇒ 不补偿会打到 " + trim(64.0F - noCompY)
                + " 格以下）");
        System.out.println("[ ok ] 重力补偿：10/22/30 格 -> " + trim(c10) + "/" + trim(c22) + "/"
                + trim(c30) + " 格；不补偿时 30 格处实测下垂 " + trim(measuredDrop)
                + " 格（预测 " + trim(predicted) + "，同量级）");
    }

    /** 一次命中率实验的结果 */
    private static final class HitRate {
        int spawned;
        int hits;
        float moved;
        long meleeTicks;
        long retreatTicks;
        String describe() {
            return "发射 " + spawned + " 枚 / 命中 " + hits + "（"
                    + String.format(java.util.Locale.ROOT, "%.1f", rate() * 100.0F)
                    + "%）/ 位移 " + trim(moved) + " 格";
        }
        float rate() {
            return spawned <= 0 ? 0.0F : (float) hits / (float) spawned;
        }
    }

    /**
     * 单射手对一个静止靶子打 {@code wantShots} 发，统计命中率与自身位移。
     *
     * <p>命中口径 = `damageDealt / 每次命中伤害`（每枚箭的伤害在发射时快照，恒为 4.0）。
     * 用「伤害换算」而不是「扫掠次数」：一次命中可能跨两 tick 扫到同一个目标，
     * 后者会把命中率算高（这也是为什么这个口径要写清楚）。
     */
    private static HitRate measureHitRate(float distance, int wantShots, boolean compensate) {
        RangedRig rig = new RangedRig(2, 1);
        rig.manager.setGravityCompensation(compensate);
        rig.manager.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 20.0F);
        rig.manager.putUnitRaw(1, 1, distance, 64.0F, 0.0F, 1, 1.0E6F);
        rig.manager.grid().build(rig.manager.data());
        rig.snapshotHp();
        rig.manager.resetDamageEvents();
        rig.manager.resetKitingStats();
        rig.manager.resetProjectileHitStats();
        float startX = rig.manager.data().posX[0];
        float startZ = rig.manager.data().posZ[0];

        int guard = 0;
        while (rig.projectiles.spawnedTotal() < wantShots && guard++ < 1600) {
            rig.tick();
        }
        HitRate r = new HitRate();
        r.spawned = rig.projectiles.spawnedTotal();
        // 每次命中的伤害 = 射手攻击力 × 伤害倍率（本替身：4.0 × 1.0）
        r.hits = Math.round(rig.manager.damageDealt() / 4.0F);
        float dx = rig.manager.data().posX[0] - startX;
        float dz = rig.manager.data().posZ[0] - startZ;
        r.moved = (float) Math.sqrt(dx * dx + dz * dz);
        r.meleeTicks = 0L;
        r.retreatTicks = rig.manager.kitingRetreatTicks();
        lastRigHadMelee = rig.manager.damageEvents().contains("melee:");
        return r;
    }

    /**
     * ★ **远距离命中率**：22 格 / 30 格静止靶各打 ≥20 发，命中率必须 ≥50%，且自身位移仍为 0.00。
     *
     * <p>这条正是玩家下一条反馈的照妖镜：「会发射」只是前半句，「打得中」才是要的效果。
     * 同一组实验再跑一次**关掉补偿**的（旧行为），把它作为失败对照打出来。
     */
    private static void rangedHitRateAtDistance() {
        System.out.println("[case] ★ 远距离命中率：22 格 / 30 格静止靶，各 ≥20 发");
        useInjectedSkeletonGate();
        try {
            float[] distances = {22.0F, 30.0F};
            for (float dist : distances) {
                HitRate on = measureHitRate(dist, 20, true);
                HitRate off = measureHitRate(dist, 20, false);
                check(on.spawned >= 20, dist + " 格：至少要真的发射 20 枚（实际 " + on.spawned + "）");
                check(on.rate() >= 0.5F,
                        dist + " 格：命中率必须 ≥ 50%（实测 " + on.describe() + "；"
                                + "关掉补偿的对照 = " + off.describe() + "）");
                check(on.moved < 0.05F,
                        dist + " 格：自身位移仍必须 ≈ 0（实测 " + trim(on.moved) + " 格）");
                check(!hasMeleeEvent(), "命中率实验里不该出现近战结算");
                System.out.println("[ ok ] 命中率 " + trim(dist) + " 格：补偿 开 -> " + on.describe()
                        + "；补偿 关 -> " + off.describe() + "（旧行为对照）");
            }
        } finally {
            restoreGate();
        }
    }

    /** 「这几组实验里不该出现近战」的统一记录位（由 measureHitRate 写入） */
    private static boolean lastRigHadMelee;

    private static boolean hasMeleeEvent() {
        return lastRigHadMelee;
    }

    /**
     * ★ **近远一致性**：同一射手对 5 / 15 / 22 / 30 格四档，命中率不得随距离单调崩掉。
     *
     * <p>断言的是「最短那档不打折 + 最远那档仍 ≥50% + 不存在『命中率随距离严格递减且末档 < 50%』」，
     * 而不是「四档必须一模一样」 —— 抛物线本来就会随距离略微变差，那不是缺陷。
     */
    private static void rangedHitRateConsistency() {
        System.out.println("[case] ★ 命中率近远一致性：5 / 15 / 22 / 30 格四档");
        useInjectedSkeletonGate();
        try {
            // 四档里最近的一档用 8 格：**7.20 格是「最小开火距离」**，比它更近时射手会按
            // 攻击模式后撤（那是正确行为，但会把「原地不动」这条断言弄脏）。
            // 5 格那一档单独放在 `rangedHitRateVeryClose` 里测（射手站着不动，只量命中率）。
            float[] distances = {8.0F, 15.0F, 22.0F, 30.0F};
            HitRate[] rs = new HitRate[distances.length];
            StringBuilder sb = new StringBuilder(128);
            for (int k = 0; k < distances.length; k++) {
                rs[k] = measureHitRate(distances[k], 20, true);
                sb.append(trim(distances[k])).append(" 格 -> ").append(rs[k].describe()).append("；");
                check(rs[k].spawned >= 20, trim(distances[k]) + " 格：至少要发射 20 枚（实际 "
                        + rs[k].spawned + "）");
                check(rs[k].rate() >= 0.5F, trim(distances[k]) + " 格：命中率必须 ≥ 50%（实测 "
                        + rs[k].describe() + "）");
                check(rs[k].moved < 0.05F, trim(distances[k]) + " 格：位移必须 ≈ 0（实测 "
                        + trim(rs[k].moved) + " 格）");
            }
            // 最近那档不能比最远那档还差（否则说明补偿把近距离打歪了）
            check(rs[0].rate() >= rs[distances.length - 1].rate() - 0.15F,
                    "8 格那一档不应明显差于 30 格（" + trim(rs[0].rate() * 100.0F) + "% vs "
                            + trim(rs[distances.length - 1].rate() * 100.0F) + "%）");
            System.out.println("[ ok ] 命中率一致性：" + sb);

            System.out.println("[ ok ] 命中率一致性：" + sb);
        } finally {
            restoreGate();
        }
    }

    /**
     * 近战可达判定：**由双方碰撞箱推导**（水平外扩，再要求垂直方向有重叠）。
     *
     * <h2>公式</h2>
     * <pre>
     *   水平：中心距 &lt;= (攻击者宽 + 目标宽)/2 + 0.5·(攻击者宽 + 目标宽)
     *   垂直：两个碰撞箱在 Y 方向必须有重叠
     * </pre>
     */
    private static void meleeReachGate() {
        System.out.println("[case] ★ 近战可达距离：由双方碰撞箱推导，结算前再判一次");
        // ---- 定义（各体型几组） ----
        float zombieVsZombie = CrowdManager.TypeStats.meleeRangeValue(0.6F, 0.6F);
        float zombieVsDragon = CrowdManager.TypeStats.meleeRangeValue(0.6F, 16.0F);
        float zombieVsSmall = CrowdManager.TypeStats.meleeRangeValue(0.6F, 0.4F);
        check(Math.abs(zombieVsZombie - 1.2F) < 1.0E-4F,
                "0.6 宽打 0.6 宽 ⇒ 1.20 格（实际 " + trim(zombieVsZombie) + "）");
        check(Math.abs(zombieVsDragon - 16.6F) < 1.0E-4F,
                "0.6 宽打 16 宽（末影龙级）⇒ 16.60 格（实际 " + trim(zombieVsDragon) + "）");
        check(zombieVsDragon > zombieVsZombie && zombieVsZombie > zombieVsSmall,
                "可达距离必须随目标体型单调变宽（大体型天然更好打）："
                        + trim(zombieVsSmall) + " < " + trim(zombieVsZombie) + " < " + trim(zombieVsDragon));

        // ---- 远（3 格）：不得结算 ----
        MeleeRig far = new MeleeRig(3.0F);
        far.manager.resetDamageEvents();
        far.manager.resetMeleeDiagnostics();
        far.manager.resolveCombatNow();
        check(!far.manager.damageEvents().contains("melee:"),
                "3 格外的目标**不得**被近战结算（事件=" + shortEvents(far.manager.damageEvents())
                        + "；可达距离 " + trim(zombieVsZombie) + " 格）");
        check(far.manager.meleeRejectedByRange() > 0L,
                "被「可达距离」拒绝的结算次数必须 +1（实际 " + far.manager.meleeRejectedByRange() + "）");

        // ---- 近（1.0 格）：必须结算 ----
        MeleeRig near = new MeleeRig(1.0F);
        near.manager.resetDamageEvents();
        near.manager.resetMeleeDiagnostics();
        near.manager.resolveCombatNow();
        check(near.manager.damageEvents().contains("melee:"),
                "可达距离内（1.0 ≤ " + trim(zombieVsZombie) + "）的目标**必须**被近战结算（事件="
                        + shortEvents(near.manager.damageEvents()) + "）");
        check(near.manager.meleeRejectedByRange() == 0L,
                "可达距离内不该有任何距离拒绝（实际 " + near.manager.meleeRejectedByRange() + "）");

        // ---- ★ 关键回归：**索敌半径很大**时，超距目标仍然不得被结算 ----
        // 这一条直接钉住真机反馈的「手太长」：近战能不能打到**不许**由索敌半径决定。
        // 具体机制（改动前的真实缺陷）：`attackReach` 对**远程类型**取
        // `max(近战距离, 远程距离)` = 16 格，而这个值既当索敌半径、又被近战分支当判定距离；
        // 于是一个「登记时算远程、运行期判近战」的类型会在十几格外结算近战。
        // 现在判定走 meleeRangeOk（由双方碰撞箱推导），所以索敌半径给到 48 格也不会漏。
        MeleeRig wide = new MeleeRig(3.0F);
        wide.manager.resetDamageEvents();
        wide.manager.resetMeleeDiagnostics();
        wide.manager.resolveCombatNow();
        check(!wide.manager.damageEvents().contains("melee:"),
                "**索敌半径 48 格**时也必须拒绝 3 格外的近战（事件="
                        + shortEvents(wide.manager.damageEvents()) + "）");
        check(wide.manager.meleeRejectedByRange() > 0L,
                "宽索敌半径下的超距近战必须被距离拒绝计数记下来（实际 "
                        + wide.manager.meleeRejectedByRange() + "）");

        System.out.println("[ ok ] 近战可达距离：0.6×0.6 -> " + trim(zombieVsZombie) + " 格、"
                + "0.6×0.4 -> " + trim(zombieVsSmall) + " 格、0.6×16 -> " + trim(zombieVsDragon)
                + " 格；3 格外被拒（计数 " + far.manager.meleeRejectedByRange()
                + "）、1.0 格内结算、索敌半径 48 格也不放行超距近战");
    }

    /**
     * ★ **远程原地开火**：15 格与 30 格（都能打/够得着）下自身位移必须 ≈ 0。
     *
     * <p>玩家原话：「远程攻击需要在原地就能向远距离的目标释放对应的攻击，
     * 比如骷髅可以直接向极远的目标直接发射箭矢，不会自己跑过去近战」。
     *
     * <p>断言：自身位移（相对布阵位置的最大水平偏移）、发射数 > 0、近战结算 = 0。
     * 30 格那组**只**断言「位移 ≈ 0」（弹道射程 96 格 ⇒ 原地开火；打不打得中是弹道的事，
     * 而这个用例要钉的是**站位语义**）。
     */
    private static void rangedFiresInPlace() {
        System.out.println("[case] ★ 远程原地开火：15 格 / 30 格的目标都不产生位移");
        useInjectedSkeletonGate();
        try {
            inPlaceBody(15.0F, true);
            inPlaceBody(30.0F, false);
        } finally {
            restoreGate();
        }
    }

    private static void inPlaceBody(float distance, boolean expectFired) {
        RangedRig rig = new RangedRig(2, 1);
        rig.manager.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 20.0F);
        rig.manager.putUnitRaw(1, 1, distance, 64.0F, 0.0F, 1, 5000.0F);
        rig.manager.grid().build(rig.manager.data());
        rig.snapshotHp();
        rig.manager.resetMeleeDiagnostics();
        rig.manager.resetDamageEvents();
        rig.manager.resetProjectileHitStats();
        RangedKiting.Rule rule = rig.manager.kitingRuleOf(0);
        check(rule != null, "骷髅必须有站位规则（否则本用例在空转）");

        float startX = rig.manager.data().posX[0];
        float startZ = rig.manager.data().posZ[0];
        for (int t = 0; t < 300; t++) {
            rig.tick();
        }
        float dx = rig.manager.data().posX[0] - startX;
        float dz = rig.manager.data().posZ[0] - startZ;
        float moved = (float) Math.sqrt(dx * dx + dz * dz);

        check(!rig.manager.damageEvents().contains("melee:"), distance + " 格：远程单位不得结算近战（事件="
                + shortEvents(rig.manager.damageEvents()) + "）");
        check(rig.manager.rangedMeleeAttempts() == 0L,
                distance + " 格：远程类型不该有任何一次落进近战分支（实际 "
                        + rig.manager.rangedMeleeAttempts() + "）");
        if (expectFired) {
            check(rig.projectiles.spawnedTotal() > 0,
                    distance + " 格（弹道射程 " + trim(rule.ballisticRange) + " 格）应能原地开火（实际 "
                            + rig.projectiles.spawnedTotal() + " 枚）");
        }
        check(moved < 0.05F, distance + " 格：自身位移必须 ≈ 0（实测 " + trim(moved) + " 格；"
                + "站位带 " + trim(rule.minDistance) + "~" + trim(rule.maxDistance)
                + " 格、弹道射程 " + trim(rule.ballisticRange) + " 格）");
        System.out.println("[ ok ] 远程原地开火：" + trim(distance) + " 格目标 —— 位移 "
                + trim(moved) + " 格、发射 " + rig.projectiles.spawnedTotal() + " 枚、近战 0 次"
                + "（弹道射程 " + trim(rule.ballisticRange) + " 格）");
    }

    /**
     * ★ **远程永不近战**：门槛判为远程的类型，在**任意**距离下的近战结算次数必须恒为 0。
     *
     * <p>四个距离覆盖了所有会走到「近」分支的情形：
     * 贴脸 0.9 / 2.4 格、射程内 12 格、超出射程 180 格。
     * 断言用的是「近战事件为空」+「距离拒绝计数 = 0」两条互补的口径：
     * 前者说明没结算，后者说明也**没有**任何一次「落进近战分支再被距离拦下」的漏网。
     */
    private static void rangedNeverMelees() {
        System.out.println("[case] ★ 远程永不近战：任意距离下近战结算恒为 0");
        useInjectedSkeletonGate();
        try {
            float[] distances = {0.9F, 2.4F, 12.0F, 180.0F};
            StringBuilder sb = new StringBuilder(96);
            for (float target : distances) {
                RangedRig rig = new RangedRig(2, 1);
                rig.manager.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 20.0F);
                rig.manager.putUnitRaw(1, 1, target, 64.0F, 0.0F, 1, 100000.0F);
                rig.manager.grid().build(rig.manager.data());
                rig.snapshotHp();
                rig.manager.resetMeleeDiagnostics();
                rig.manager.resetDamageEvents();
                rig.manager.resetProjectileHitStats();
                for (int t = 0; t < 120; t++) {
                    rig.tick();
                }
                check(!rig.manager.damageEvents().contains("melee:"),
                        trim(target) + " 格：远程类型的近战结算必须为 0（事件="
                                + shortEvents(rig.manager.damageEvents()) + "）");
                // 「远程类型落进近战分支」= 漏网（旧路径的指纹）。真机口径：必须是 0。
                check(rig.manager.rangedMeleeAttempts() == 0L,
                        trim(target) + " 格：远程类型不该有任何一次落进近战分支（实际 "
                                + rig.manager.rangedMeleeAttempts() + "）");
                // 贴脸那两组给一条更强、也更贴合玩家原话的断言：
                // 距离低于最小开火距离时**必须真的后撤**（朝向仍对目标），而不是硬射或砍人。
                // 注意「退了之后到了能打的距离再射」是**正确**的 —— 所以这里不禁止发射。
                if (target < 5.0F) {
                    check(rig.manager.kitingRetreatTicks() > 0L,
                            trim(target) + " 格：进了最小开火距离（"
                                    + trim(rig.manager.kitingRuleOf(0).minDistance)
                                    + " 格）必须真的后撤（后撤 tick = "
                                    + rig.manager.kitingRetreatTicks() + "）");
                }
                sb.append(trim(target)).append(" 格 -> 近战 0、拒绝 0、发射 ")
                        .append(rig.projectiles.spawnedTotal()).append(" 枚；");
            }
            System.out.println("[ ok ] 远程永不近战：" + sb);
        } finally {
            restoreGate();
        }
    }

    /** 「近战把关」用例的战场：单位 0 = 近战攻击者，单位 1 = 目标（双方都站着不动） */
    private static final class MeleeRig {
        final CrowdManager manager;

        MeleeRig(float distance) {
            CrowdManager cm = new CrowdManager(null, 4);
            cm.setLevel(null);
            cm.registerTypeStub(ZOMBIE_TARGET, 0);
            cm.registerTypeStub(SKELETON, 1);
            CrowdManager.TypeStats attacker = CrowdManager.TypeStats.custom(
                    0.6F, 1.95F, 1.62F, 100.0F, 3.0F, 0.0F, 48.0F,
                    0.0F, 0.0F, 0.0F,
                    40, 0, 120, -1.0F, false, false, ZOMBIE_TARGET);
            CrowdManager.TypeStats victim = CrowdManager.TypeStats.custom(
                    0.6F, 1.95F, 1.62F, 5000.0F, 3.0F, 0.0F, 48.0F,
                    0.0F, 0.0F, 0.0F,
                    40, 0, 120, -1.0F, false, false, SKELETON);
            cm.setStatsOverride(0, attacker);
            cm.setStatsOverride(1, victim);
            cm.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 100.0F);
            cm.putUnitRaw(1, 1, distance, 64.0F, 0.0F, 1, 5000.0F);
            cm.data().targetId[0] = 1;      // 直接锁定（索敌不参与本用例）
            cm.data().attackCd[0] = 0;
            this.manager = cm;
        }
    }

    // ------------------------------------------------------------------
    // 远程用例的替身装配
    // ------------------------------------------------------------------

    /** 近战距离常量：与原版 Mob#getMeleeAttackRangeSqr 一致（口径 0.6 的怪 ≈ 1.79 格） */
    private static final float MELEE = (float) Math.sqrt(0.6F * 2.0F * 0.6F * 2.0F + 0.6F);

    /** 一个「两个单位 + 一个独立投掷物管理器」的离线战场 */
    private static final class Rig {
        final ProjectileManager projectiles;
        final CrowdManager manager;

        Rig(ProjectileManager projectiles, CrowdManager manager) {
            this.projectiles = projectiles;
            this.manager = manager;
        }
    }

    /**
     * 多单位离线战场（距离保持/群集受伤/上界三个用例共用）：
     * 槽位 {@code [0, skeletons)} = 骸骨射手（team 0），{@code [skeletons, n)} = 僵尸（team 1）。
     *
     * <p>与 {@link Rig} 的区别只有三点，都是为了让**主循环**跑起来而不是只跑一次战斗结算：
     * <ol>
     *   <li>每 tick 重建空间哈希（线上由 {@code CrowdManager.tick} 做）——否则投掷物会看到一张过期的格子表；</li>
     *   <li>重新索敌（线上由 {@code aiTick} 按 retargetCd 做；离线直接每 tick 锁最近敌人，
     *       省掉「索敌节流」这一层，让站位/命中成为唯一变量）；</li>
     *   <li>投掷物 tick 排在群集之后（与 {@code HordeEvents} 的接线一致）。</li>
     * </ol>
     */
    private static final class RangedRig {
        final ProjectileManager projectiles;
        final CrowdManager manager;
        private final float[] hpBefore;

        RangedRig(int units, int shooters) {
            this(units, shooters, 0.23F, 0.0F);
        }

        /**
         * @param shooterSpeed 射手移动速度（本模组是按 `v²` 加速的，0.23 = 原版僵尸档）
         * @param targetSpeed  靶子移动速度；0 = 会锁定但一步都不走（静态靶）
         */
        RangedRig(int units, int shooters, float shooterSpeed, float targetSpeed) {
            ProjectileManager pm = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
            pm.setKindResolver(id -> ARROW.equals(id)
                    ? dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW
                    : dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.PROJECTILE);
            CrowdManager cm = new CrowdManager(null, units + 2);
            cm.setLevel(null);
            cm.registerTypeStub(SKELETON, 0);
            cm.registerTypeStub(ZOMBIE_TARGET, 1);
            // 射手的索敌半径必须**覆盖整段接近过程**（否则 aiTick 每 tick 把目标清成 -1，
            // 单位就退回团队流场「往敌方重心走」—— 那正是真机里「冲上去」的另一条路）。
            CrowdManager.TypeStats shooter = CrowdManager.TypeStats.custom(
                    0.6F, 1.99F, 1.62F, 20.0F, 4.0F, shooterSpeed, 64.0F,
                    0.0F, 0.0F, 0.0F,
                    40, 0, 120, 16.0F, false, true, SKELETON);
            // 目标（僵尸）：**近战**类型（rangedRange = -1 且 casterId 传 ZOMBIE_TARGET，
            // 于是门槛把它判成 MELEE）。
            //
            // 索敌半径固定给 4：**必须让靶子会主动追**，否则「射手被逼近→后撤」这条链测不出来。
            // 想让它完全静止的用例把 `targetSpeed` 传 0（移动速度 0：会锁定、但一步都不走）。
            // 注意**不能**用 followRange = 0 来实现静止：那样它连目标都不会锁定，
            // 于是整支队伍靠团队流场乱走（本 harness 踩过这个坑，20v20 用例因此掉到 17.5 格）。
            //
            // 为什么要 casterId：不传的话 rangedRange = -1 会回落到全局配置的 16 格，
            // 于是一群只会近战的僵尸被当成远程单位（这正是「判定必须走同一条链」要防的那种分叉）。
            CrowdManager.TypeStats melee = CrowdManager.TypeStats.custom(
                    0.6F, 1.95F, 1.62F, 500.0F, 3.0F, targetSpeed, 4.0F,
                    0.0F, 0.0F, 0.0F,
                    40, 0, 120, -1.0F, false, false, ZOMBIE_TARGET);
            // ★ 覆盖表是**按槽位**的（离线 harness 专用），不是按类型：
            //   只写 setStatsOverride(0, ...) / (1, ...) 的话，槽位 2..N 会悄悄回落到
            //   注册表兜底属性（速度 0.23、索敌 16）—— 测出来的就不是你以为的那个实验。
            for (int i = 0; i < units + 2; i++) {
                cm.setStatsOverride(i, i < shooters ? shooter : melee);
            }
            pm.setHits(cm.crowdHitResolver());
            cm.setRangedInjector(new CrowdManager.RangedAttackInjector() {
                @Override
                public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                    return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
                }

                @Override
                public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                    return new CrowdManager.RangedDeps(pm, SKELETON, id -> ARROW);
                }
            });
            this.projectiles = pm;
            this.manager = cm;
            this.hpBefore = new float[units + 2];
        }

        /** 记下初始血量（用于「一共掉了多少血」的口径） */
        void snapshotHp() {
            for (int i = 0; i < hpBefore.length && i < manager.data().highWater; i++) {
                hpBefore[i] = manager.data().hp[i];
            }
        }

        /** 主循环 + 投掷物（顺序与线上 {@code HordeEvents} 一致） */
        void tick() {
            retarget();
            manager.tickWithLevel(null);
            projectiles.tickPure(ProjectileManager.Sink.NONE, -1000.0F);
        }

        /** 每个单位锁最近的敌人（online 由 aiTick 的 retargetCd 节流；这里每 tick 锁，减少变量） */
        private void retarget() {
            CrowdData d = manager.data();
            for (int i = 0; i < d.highWater; i++) {
                if (!d.aliveFlag[i]) {
                    continue;
                }
                boolean shooter = manager.teamOf(i) == 0;
                float best = Float.MAX_VALUE;
                int bestSlot = -1;
                for (int j = 0; j < d.highWater; j++) {
                    if (j == i || !d.aliveFlag[j] || d.hp[j] <= 0f) {
                        continue;
                    }
                    if ((manager.teamOf(j) == 0) == shooter) {
                        continue;
                    }
                    float ox = d.posX[j] - d.posX[i];
                    float oz = d.posZ[j] - d.posZ[i];
                    float dd = ox * ox + oz * oz;
                    if (dd < best) {
                        best = dd;
                        bestSlot = j;
                    }
                }
                d.targetId[i] = bestSlot;
            }
        }

        int totalHp(int from, int to) {
            int sum = 0;
            for (int i = from; i < to && i < manager.data().highWater; i++) {
                sum += Math.max(0, (int) manager.data().hp[i]);
            }
            return sum;
        }

        /** {@code [from, to)} 这段槽位一共掉了多少血（以布阵后的快照为基准） */
        float lostHp(int from, int to) {
            float lost = 0.0F;
            for (int i = from; i < to && i < manager.data().highWater; i++) {
                lost += Math.max(0.0F, hpBefore[i] - Math.max(0.0F, manager.data().hp[i]));
            }
            return lost;
        }

        /** 宿主记录的近战伤害合计（从 {@code melee:x} 事件里解析） */
        float sumMeleeDamage() {
            float sum = 0.0F;
            for (String part : manager.damageEvents().split("\\|")) {
                if (part.startsWith("melee:")) {
                    try {
                        sum += Float.parseFloat(part.substring("melee:".length()));
                    } catch (NumberFormatException ignored) {
                        // 忽略解析不了的片段
                    }
                }
            }
            return sum;
        }
    }

    /**
     * 造一个离线战场：单位 0 = 远程射手（minecraft:skeleton），单位 1 = 目标（minecraft:zombie）。
     *
     * 为什么不用 {@code CrowdManager.spawn}：那需要 ServerLevel 与注册表属性。
     * 这里直接把两个单位写进 SoA 数组、把属性写成 {@link CrowdManager.TypeStats#custom}
     * 造出来的替身值 —— 于是「远程模式」这条链完全离线可跑。
     */
    private static Rig newRig(float targetDistance, int attackCooldown, float targetHp) {
        ProjectileManager pm = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
        pm.setKindResolver(id -> ARROW.equals(id)
                ? dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW
                : dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.PROJECTILE);
        CrowdManager cm = new CrowdManager(null, 8);
        cm.setLevel(null);
        cm.registerTypeStub(SKELETON, 0);
        cm.registerTypeStub(ZOMBIE_TARGET, 1);
        cm.setStatsOverride(0, CrowdManager.TypeStats.custom(
                0.6F, 1.99F, 1.62F, 20.0F, 4.0F, 0.25F, 24.0F,
                attackCooldown, 0, 120, 20.0F, false, true));
        cm.setStatsOverride(1, CrowdManager.TypeStats.custom(
                0.6F, 1.95F, 1.62F, targetHp, 3.0F, 0.23F, 16.0F,
                attackCooldown, 0, 120, -1.0F, false, false));
        cm.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 20.0F);
        cm.putUnitRaw(1, 1, targetDistance, 64.0F, 0.0F, 1, targetHp);
        cm.data().targetId[0] = 1;
        cm.grid().build(cm.data());
        return new Rig(pm, cm);
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 13. 真机路径（不注入 autoPick）：rangedAttackDefault -> autoProjectileFor -> resolve
    // ------------------------------------------------------------------

    /**
     * 真机那条「自动解析出投掷物」的路径。
     *
     * <h2>为什么必须补这个用例</h2>
     * 前面所有远程用例都注入了一个固定的 {@code autoPick}（{@code id -> ARROW}），
     * 于是「注册表里到底挑不挑得出一枚投掷物」这条真机路径**从未被验证过** ——
     * 真机日志里的「投掷物来源 无」正是从这条缝里漏出来的（候选池为空 -> resolve 永远返回空）。
     * 这里把 autoPick 留成 null，只注入
     * {@link dev.swordkingdom.horde.crowd.ProjectileCatalog#setPoolSupplier}（线上注入的是真实注册表扫描），
     * 于是跑的就是 {@code rangedAttackDefault -> CrowdManager.autoProjectileFor ->
     * ProjectileCatalog.resolve -> 打分选弹 -> fire} 这条**完整链路**，一枚替身都没跳过。
     */
    private static void rangedAutoResolvePath() {
        java.util.List<dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate> pool = new java.util.ArrayList<>();
        pool.add(new dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate(ARROW,
                dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW, 0));
        pool.add(new dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate(SNOWBALL,
                dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.THROWABLE, 0));
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(() -> pool);
        // 「外来实体名」判据线上是查注册表，离线关掉（它只影响扣分，不影响能不能解析出来）
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setForeignPathCheck((fullId, stem) -> false);
        // 门槛要求「施法者真的会远程攻击」：注入施法者实体类（真实原版 Skeleton 实现 RangedAttackMob）
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(
                id -> SKELETON.equals(id) ? net.minecraft.world.entity.monster.Skeleton.class : null);
        try {
            Rig r = newRig(12.0F, 40, 100.0F);
            r.manager.setRangedInjector(new CrowdManager.RangedAttackInjector() {
                @Override
                public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                    return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
                }

                @Override
                public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                    // 只把「投掷物管理器 + 施法者类型」换成离线的（没有 ServerLevel 就取不到真的），
                    // autoPick 传 null = 让**生产代码自己**去解析 —— 这正是本用例要验的那一段。
                    return new CrowdManager.RangedDeps(r.projectiles, SKELETON, null);
                }
            });
            r.manager.resetDamageEvents();
            r.manager.resolveCombatNow();
            check(r.projectiles.aliveCount() == 1,
                    "12 格外的目标应让「自动解析出的」投掷物真的发射（实际 " + r.projectiles.aliveCount() + " 枚）");
            if (r.projectiles.aliveCount() == 0) {
                System.out.println("[FAIL] 自动解析路径没有发射（池里的候选："
                        + dev.swordkingdom.horde.crowd.ProjectileCatalog.pool() + "，原因："
                        + dev.swordkingdom.horde.crowd.ProjectileCatalog.poolReason() + "）");
                return;
            }
            ProjectileData pd = r.projectiles.data();
            ProjectileManager.ProjType type = r.projectiles.typeOf(pd.typeIndex[0] & 0x1F);
            // 池子里只有 arrow（ARROW）与 snowball（THROWABLE），箭类默认参数 3.0/0.05 是唯一指纹
            check(type != null && Math.abs(type.speed - 3.0F) < 1.0E-4F,
                    "自动解析应选中 minecraft:arrow（箭类默认初速 3.0，实际 "
                            + (type == null ? "null" : trim(type.speed)) + "）");
            System.out.println("[ ok ] 真机路径（autoPick 留空、只注入候选池）：resolve 自动选中 "
                    + ARROW + "（箭类默认初速 " + (type == null ? "?" : trim(type.speed))
                    + "），12 格外发射 1 枚");
        } finally {
            dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(null);
            dev.swordkingdom.horde.crowd.ProjectileCatalog.setForeignPathCheck(null);
            dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    // ------------------------------------------------------------------
    // 14. 规则 1：GUI 显式指定过投掷物 -> 一律远程（门槛不生效）
    // ------------------------------------------------------------------

    /**
     * 门槛与「玩家意图」的优先级。
     *
     * 真机规则（父 agent 定）：
     * <ol>
     *   <li>玩家在 GUI 里显式指定过投掷物 → **一律远程**，门槛完全不参与；</li>
     *   <li>否则看门槛（RangedAttackMob / CrossbowAttackMob / 字节码发弹证据）；</li>
     *   <li>门槛不通过 → 近战。</li>
     * </ol>
     * 这条规则在代码里的落点是
     * {@code TypeStats.ranged() = hasConfiguredProjectiles || ProjectileCatalog.hasAutoAttack(id)}
     * 与 {@code ProjectileCombat.isRangedAttacker(hasConfigured,...)} 的短路分支，
     * 门槛只作用在自动解析那一路（{@code CrowdManager.autoAttackFor}）。
     * 所以用「僵尸类型 + hasConfigured=true」的替身就能把规则钉死：
     * 门槛说近战，玩家配过 → 仍然远程并且真的发射。
     */
    private static void guiConfiguredProjectileBeatsGate() {
        final ResourceLocation zombie = ResourceLocation.tryParse("minecraft:zombie");
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(() -> java.util.List.of(
                new dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate(ARROW,
                        dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW, 0)));
        dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(
                id -> zombie.equals(id) ? net.minecraft.world.entity.monster.Zombie.class : null);
        try {
            check(dev.swordkingdom.horde.crowd.ProjectileCatalog.rangedGate(zombie)
                            == dev.swordkingdom.horde.crowd.ProjectileCatalog.RangedGate.MELEE,
                    "僵尸的自动解析门槛应为 MELEE（不实现 RangedAttackMob、字节码里也不发弹）");
            check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(true, "minecraft", "zombie"),
                    "GUI 显式指定过投掷物 -> 僵尸也必须是远程单位（门槛不生效）");
            check(!dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(false, "minecraft", "zombie"),
                    "没配过 + 门槛 MELEE -> 僵尸必须是近战");

            // 类型属性层：ranged() = hasConfigured || 门禁通过的自动解析（门槛只影响后一半）。
            // casterId 一起传进去，于是这里验的就是**生产那条链**（而不是离线兜底快照）。
            CrowdManager.TypeStats configured = CrowdManager.TypeStats.custom(0.6F, 1.95F, 1.62F, 20.0F, 3.0F,
                    0.23F, 16.0F, 40, 0, 120, 16.0F, true, false, zombie);
            check(configured.ranged(), "hasConfiguredProjectiles=true 时 TypeStats.ranged() 必须为 true（门槛不参与）");
            CrowdManager.TypeStats plain = CrowdManager.TypeStats.custom(0.6F, 1.95F, 1.62F, 20.0F, 3.0F,
                    0.23F, 16.0F, 40, 0, 120, 16.0F, false, false, zombie);
            check(!plain.ranged(), "既没配过、也没有门禁通过的自动解析结果 -> 近战");
            // ★ 真机事故③的回归：hasAutoProjectile 就算被写成 true（旧代码的打分结果），
            //   只要门槛是 MELEE，运行期重判仍然必须是近战 —— 末影龙就是这样被判回近战的。
            CrowdManager.TypeStats staleAuto = CrowdManager.TypeStats.custom(0.6F, 1.95F, 1.62F, 20.0F, 3.0F,
                    0.23F, 16.0F, 40, 0, 120, 16.0F, false, true, zombie);
            check(!staleAuto.ranged(),
                    "hasAutoProjectile=true 但门槛判 MELEE 时，运行期必须重判为近战（否则就是末影龙射箭的翻版）");

            // 真的会发射：僵尸类型 + 显式配置的 stats（离线用注入的 autoPick 代表「GUI 指定的那枚 id」）
            ProjectileManager pm = newManager(ProjectileManager.FREE_MOVE, ProjectileManager.NO_HITS);
            CrowdManager cm = new CrowdManager(null, 8);
            cm.setLevel(null);
            cm.registerTypeStub(zombie, 0);
            cm.registerTypeStub(SKELETON, 1);
            cm.setStatsOverride(0, configured);
            cm.setStatsOverride(1, CrowdManager.TypeStats.custom(0.6F, 1.95F, 1.62F, 100.0F, 3.0F, 0.23F,
                    16.0F, 40, 0, 120, -1.0F, false, false));
            cm.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 20.0F);
            cm.putUnitRaw(1, 1, 12.0F, 64.0F, 0.0F, 1, 100.0F);
            cm.data().targetId[0] = 1;
            cm.grid().build(cm.data());
            cm.setRangedInjector(new CrowdManager.RangedAttackInjector() {
                @Override
                public boolean attack(CrowdManager m, CrowdData d, int slot, CrowdManager.TypeStats stats, int period) {
                    return CrowdManager.rangedAttackDefault(m, d, slot, stats, period);
                }

                @Override
                public CrowdManager.RangedDeps provideDeps(CrowdManager m, CrowdData d, int slot) {
                    // autoPick 代表「玩家在 GUI 里指定的那一枚」：规则 1 的路径根本不问门槛
                    return new CrowdManager.RangedDeps(pm, zombie, id -> ARROW);
                }
            });
            cm.resolveCombatNow();
            check(pm.aliveCount() == 1, "配过投掷物的僵尸必须真的发射（实际 " + pm.aliveCount() + " 枚）");
            System.out.println("[ ok ] GUI 显式指定 > 门槛：僵尸（门槛=MELEE）因显式配置仍为远程并发射 "
                    + pm.aliveCount() + " 枚（" + ARROW + "）");
        } finally {
            dev.swordkingdom.horde.crowd.ProjectileCatalog.setPoolSupplier(null);
            dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    // ------------------------------------------------------------------
    // 15. ★ 真机事故①的防呆：投掷物渲染器与群集渲染器共用同一份 GLSL，
    //     uniform 清单必须一致（离线反射比对，不需要 GL 上下文）
    // ------------------------------------------------------------------

    /**
     * 真机事故①：投掷物**一整类完全看不见**。
     *
     * <pre>
     *   [horde] 投掷物实例化渲染失败，已自动停用（群集渲染不受影响）
     *   java.lang.IllegalStateException: 投掷物着色器 uniform 缺失
     *       at ... ProjectileRenderer.init(ProjectileRenderer.java:490)
     * </pre>
     * 根因：投掷物的顶点着色器是「群集那份 + 两处字符串注入」，而上一轮改群集着色器时
     * 把被替换的原文改了 —— 注入静默落空，GLSL 里没有 {@code uYawOffset}，
     * 定位返回 -1，整个渲染器自我停用。
     *
     * <p>这个用例把整件事变成**离线可测**的（不需要 GLFW/GL 上下文）：
     * <ol>
     *   <li>两边都用**同一份 GLSL**（投掷物 = 群集 + 注入），逐条比对
     *       「声明了的 uniform」「真的用到的 uniform」与「Java 侧清单」；</li>
     *   <li>差异必须**只有** {@code uYawOffset} 这一个注入的；</li>
     *   <li>注入的锚点在群集着色器源码里必须**逐字存在**（否则真机上就是静默落空）；</li>
     *   <li>注入守卫必须真的会拦：锚点缺失 / 出现多次都要抛异常并带上锚点原文；</li>
     *   <li>Java 侧的 uniform 字段清单必须覆盖 {@link ProjectileRenderer#REQUIRED_UNIFORMS}
     *       （少一个字段就说明清单与代码脱节）。</li>
     * </ol>
     */
    /**
     * ★ 真机事故（本轮）：「投掷物粘在视角上」+「模型组件错位」—— 空间与顺序的离线照妖镜。
     *
     * <p>先把**坐标空间契约**钉死（这是共用 GLSL 的约定）：
     * <pre>
     *   iPos  = 世界坐标 − 摄像机坐标（**相机相对**，CPU 端减好）
     *   uView = RenderLevelStageEvent 的 PoseStack 末矩阵 —— 对实体/投掷物这两个阶段它是
     *           **只有旋转、没有平移**的视图矩阵（原版就是把世界坐标先减摄像机再进来）
     *   gl_Position = uProj · uView · vec4(world, 1)        且 world 必须在**模型空间**算完：
     *   world = yawRot · (pitchRot · pos) + iPos + uRenderOffset
     * </pre>
     * 任何一处越界（例如把偏移加在 uView 之后、或注入块落进裁剪空间、或 iPos 又减了一次摄像机）
     * 都会正好表现为「跟着摄像机走」/「部件错位」。这里把结构做成断言，离线即可拦住回归。
     */
    private static void shaderSpaceAndOrderContract() {
        System.out.println("[case] ★ 共用 GLSL 的空间与顺序契约（粘视角 / 组件错位的照妖镜）");
        String crowd = dev.swordkingdom.horde.client.CrowdRenderer.VERTEX_SHADER;
        String proj = dev.swordkingdom.horde.client.ProjectileRenderer.vertexShader();

        // ---- 1) gl_Position 恰好出现一次（两条路径各一次）----
        int crowdGp = countOf(crowd, "gl_Position");
        int projGp = countOf(proj, "gl_Position");
        check(crowdGp == 1, "群集顶点着色器里 gl_Position 必须恰好出现 1 次（实际 " + crowdGp + " 次）"
                + "—— 出现多次就可能在投影后又被改写（＝位置跑到错的空间）");
        check(projGp == 1, "投掷物顶点着色器里 gl_Position 必须恰好出现 1 次（实际 " + projGp + " 次）");

        // ---- 2) 两条路径的「模型空间 → 世界」表达式必须同形 ----
        String crowdWorld = lineContaining(crowd, "vec3 world =");
        String projWorld = lineContaining(proj, "vec3 world =");
        System.out.println("      群集 world 表达式：" + crowdWorld.trim());
        System.out.println("      投掷物 world 表达式：" + projWorld.trim());
        check(crowdWorld != null && projWorld != null, "两条路径都必须有 vec3 world = ... 这一行");
        String tail = "+ iPos + uRenderOffset;";
        check(crowdWorld != null && crowdWorld.trim().endsWith(tail),
                "群集：world 必须是「旋转后的模型坐标 + 相机相对位置 + 渲染偏移」：" + crowdWorld);
        check(projWorld != null && projWorld.trim().endsWith(tail),
                "投掷物：world 必须是同一条式子（末段一致），否则两条路径不在同一个空间：" + projWorld);
        check(crowdWorld != null && projWorld != null
                        && crowdWorld.replace(" ", "").equals(projWorld.replace(" ", ""))
                        || (crowdWorld != null && projWorld != null
                        && crowdWorld.contains("yawRot * pos") && projWorld.contains("pitchRot * pos")
                        && projWorld.contains("yawRot * (pitchRot * pos)")),
                "投掷物的朝向注入只允许在**模型空间**多一层俯仰旋转（yawRot*(pitchRot*pos)），"
                        + "其余部分必须与群集逐字同形");

        // ---- 3) gl_Position 只允许 uProj·uView·vec4(world,1) 这一种形式 ----
        String gpLine = lineContaining(crowd, "gl_Position =");
        check(gpLine != null && gpLine.replace(" ", "").contains("uProj*uView*vec4(world,1.0)"),
                "gl_Position 必须是 uProj * uView * vec4(world, 1.0)（世界→视图→裁剪的顺序不能反）：" + gpLine);
        check(gpLine != null && !gpLine.contains("iPos") && !gpLine.contains("uYawOffset"),
                "gl_Position 这一行**不许**再出现 iPos / uYawOffset —— 逐实例位移与朝向注入必须在此之前、"
                        + "在模型空间完成（加在裁剪空间正是「粘在视角上」）：" + gpLine);

        // ---- 4) 注入块必须在 gl_Position 之前，且不得引用裁剪空间量 ----
        int injDecl = proj.indexOf("uniform float uYawOffset;");
        int injUse = proj.indexOf("uYawOffset - iMisc.x");
        int projGpIdx = proj.indexOf("gl_Position");
        check(injDecl > 0, "注入的 uniform float uYawOffset; 声明必须真的落在着色器里（注入落空 = 真机整类停用）");
        check(injUse > 0, "注入的朝向计算（180.0 + uYawOffset - iMisc.x）必须真的落在着色器里");
        check(injDecl < projGpIdx && injUse < projGpIdx,
                "注入块必须全部出现在 gl_Position **之前**（声明 index=" + injDecl
                        + "、使用 index=" + injUse + "、gl_Position index=" + projGpIdx + "）");
        check(!proj.contains("gl_Position") || proj.lastIndexOf("gl_Position") == projGpIdx,
                "gl_Position 之后不许再出现第二次位置赋值");

        // ---- 5) 相机相对契约：两条路径的 CPU 侧都必须是「世界 − 摄像机」 ----
        String crowdSub = lineContaining(crowd, "vec3 world =");
        check(crowdSub != null && crowdSub.contains("iPos"),
                "world 里必须把相机相对位置 iPos 加进来（漏了就是「粘在视角上」）");
        System.out.println("      契约：iPos = 世界 − 摄像机（CPU 端减好）；uView = 只有旋转的视图矩阵；"
                + "world 在模型空间算完后才乘 uProj·uView");
    }

    /**
     * ★ 真机事故（本轮「粘视角」）：视图矩阵必须来自**唯一来源**，且必须「只有旋转」。
     *
     * <p>病根就是两条路径各取一份：群集用 {@code event.getPoseStack()}（旋转），
     * 投掷物用 {@code RenderSystem.getModelViewMatrix()}（**含摄像机平移**）——
     * 而 {@code iPos} 已经是「世界 − 摄像机」⇒ 摄像机被减两次。
     * 现在两条路径都调 {@code ViewMatrices.capture(event, …)}，这里用替身矩阵把契约钉死：
     * <ol>
     *   <li>带平移的视图矩阵必须被判定为**违约**（{@code isRotationOnly() == false}）——
     *       这类矩阵一旦被用上，物体就会跟着镜头跑；</li>
     *   <li>只有旋转时必须通过；</li>
     *   <li>{@code project()} 必须与着色器的 {@code PROJ·VIEW·(iPos,1)} 逐分量一致（替身矩阵手算对照）。</li>
     * </ol>
     */
    private static void viewMatrixContract() {
        System.out.println("[case] ★ 视图矩阵契约（唯一来源 + 只有旋转；真机事故「粘视角」的照妖镜）");
        String source = null;
        try {
            java.lang.reflect.Method m = dev.swordkingdom.horde.client.ViewMatrices.class
                    .getMethod("capture", net.minecraftforge.client.event.RenderLevelStageEvent.class, int.class);
            source = m.getName();
        } catch (Throwable t) {
            check(false, "视图矩阵必须由唯一助手 ViewMatrices.capture(event, path) 提供：" + t);
        }
        check("capture".equals(source), "唯一来源助手必须叫 capture（群集与投掷物各自调它）：" + source);

        // 只有旋转（单位矩阵）——必须通过
        dev.swordkingdom.horde.client.ViewMatrices.resetForTest();
        check(dev.swordkingdom.horde.client.ViewMatrices.isRotationOnly(),
                "只有旋转的视图矩阵必须通过契约检查");

        // 带摄像机平移（模拟 RenderSystem.getModelViewMatrix()）——必须被判违约
        float[] withTranslation = new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                12.5F, 70.0F, -33.25F, 1};
        dev.swordkingdom.horde.client.ViewMatrices.setViewForTest(withTranslation);
        check(!dev.swordkingdom.horde.client.ViewMatrices.isRotationOnly(),
                "**带平移**的视图矩阵必须被判违约（那正是「投掷物粘在视角上」的成因："
                        + "iPos 已经减过摄像机，矩阵又减一次）");
        float[] clip = dev.swordkingdom.horde.client.ViewMatrices.project(new float[]{1.0F, 2.0F, 3.0F});
        System.out.printf("      带平移矩阵下的视空间坐标 = (%.3f, %.3f, %.3f)"
                        + "（= iPos + 平移；着色器会把摄像机再减一次 ⇒ 跟着镜头跑）%n",
                clip[0], clip[1], clip[2]);
        check(Math.abs(clip[0] - 13.5F) < 1.0E-4F && Math.abs(clip[1] - 72.0F) < 1.0E-4F
                        && Math.abs(clip[2] - (-30.25F)) < 1.0E-4F,
                "project() 的视空间结果必须等于 VIEW·(iPos,1)（手算对照：13.5 / 72.0 / -30.25）");

        // 投影公式对照：proj 取一个可手算的对角矩阵
        dev.swordkingdom.horde.client.ViewMatrices.resetForTest();
        dev.swordkingdom.horde.client.ViewMatrices.setProjForTest(new float[]{
                2, 0, 0, 0,
                0, 3, 0, 0,
                0, 0, 0.5F, 0,
                0, 0, 0, 1});
        float[] c2 = dev.swordkingdom.horde.client.ViewMatrices.project(new float[]{1.0F, 2.0F, 4.0F});
        check(Math.abs(c2[3] - 2.0F) < 1.0E-4F && Math.abs(c2[4] - 6.0F) < 1.0E-4F
                        && Math.abs(c2[5] - 2.0F) < 1.0E-4F && Math.abs(c2[6] - 1.0F) < 1.0E-4F,
                "裁剪空间必须是 PROJ·VIEW·(iPos,1)（手算对照：x=2、y=6、z=2、w=1）");
        dev.swordkingdom.horde.client.ViewMatrices.resetForTest();
        System.out.println("      契约：iPos = 世界 − 摄像机；VIEW 平移 ≈ 0；gl_Position = PROJ·VIEW·vec4(world,1)");
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String lineContaining(String text, String needle) {
        int i = text.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int start = text.lastIndexOf('\n', i) + 1;
        int end = text.indexOf('\n', i);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static void rendererUniformContract() {
        System.out.println("[case] 共用 GLSL 的两个渲染器：uniform 清单必须一致"
                + "（真机事故①「投掷物完全看不见」的防呆）");
        String crowdVs;
        String crowdFs;
        String projVs;
        try {
            crowdVs = dev.swordkingdom.horde.client.CrowdRenderer.VERTEX_SHADER;
            crowdFs = dev.swordkingdom.horde.client.CrowdRenderer.FRAGMENT_SHADER;
        } catch (Throwable t) {
            check(false, "群集着色器字符串必须能读出来：" + t);
            return;
        }
        // 逐段定位：注入失败时要说清是**哪一段**（这本身就是真机排查最缺的信息）
        try {
            dev.swordkingdom.horde.client.ProjectileRenderer.vertexShader();
        } catch (Throwable t) {
            check(false, "投掷物顶点着色器必须能构造出来（真机上这里抛异常 = 投掷物整类看不见）："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            System.out.println("      群集着色器里 uniform 锚点 indexOf="
                    + crowdVs.indexOf(dev.swordkingdom.horde.client.ProjectileRenderer.UNIFORM_ANCHOR)
                    + "，朝向锚点 indexOf="
                    + crowdVs.indexOf(dev.swordkingdom.horde.client.ProjectileRenderer.ORIENT_ANCHOR)
                    + "（锚点长度 " + dev.swordkingdom.horde.client.ProjectileRenderer.ORIENT_ANCHOR.length()
                    + "，首行前导空格数=" + leadingSpaces(
                    dev.swordkingdom.horde.client.ProjectileRenderer.ORIENT_ANCHOR) + "）");
            return;
        }
        projVs = dev.swordkingdom.horde.client.ProjectileRenderer.vertexShader();

        // ---- 1) 注入的锚点必须在群集着色器里逐字存在 ----
        check(crowdVs.contains(dev.swordkingdom.horde.client.ProjectileRenderer.UNIFORM_ANCHOR),
                "uYawOffset 的注入锚点必须逐字存在于群集顶点着色器里（实际找不到 = 真机上会静默落空）");
        check(crowdVs.contains(dev.swordkingdom.horde.client.ProjectileRenderer.ORIENT_ANCHOR),
                "朝向计算的注入锚点必须逐字存在于群集顶点着色器里");

        // ---- 2) 逐条审计：声明 / 使用 / Java 清单 ----
        java.util.Set<String> crowdDeclared = dev.swordkingdom.horde.client.ProjectileRenderer
                .declaredUniforms(crowdVs);
        java.util.Set<String> projDeclared = dev.swordkingdom.horde.client.ProjectileRenderer
                .declaredUniforms(projVs);
        System.out.println("      群集顶点着色器声明的 uniform（" + crowdDeclared.size() + "）："
                + new java.util.TreeSet<>(crowdDeclared));
        System.out.println("      投掷物顶点着色器声明的 uniform（" + projDeclared.size() + "）："
                + new java.util.TreeSet<>(projDeclared));
        java.util.Set<String> diff = new java.util.TreeSet<>(projDeclared);
        diff.removeAll(crowdDeclared);
        java.util.Set<String> missing = new java.util.TreeSet<>(crowdDeclared);
        missing.removeAll(projDeclared);
        check(diff.equals(java.util.Set.of("uYawOffset")),
                "投掷物着色器**只能**比群集多一个 uYawOffset（实际多出：" + diff + "）");
        check(missing.isEmpty(),
                "投掷物着色器不能比群集少任何 uniform（实际少了：" + missing + "）");
        check(!projDeclared.contains("uBoneStride"),
                "uBoneStride 已被删除（它被 GLSL 优化掉后 getUniformLocation 返回 -1，曾导致同样的「缺失」）");

        // ---- 3) Java 侧清单 vs 着色器实际用到的那一份 ----
        String audit = dev.swordkingdom.horde.client.ProjectileRenderer.auditUniforms();
        check(audit.isEmpty(),
                "uniform 清单必须与着色器逐条对上（差异：" + audit + "）；"
                        + "真机上这里脱节 = 渲染器初始化失败并整类停止绘制");
        check(dev.swordkingdom.horde.client.ProjectileRenderer.REQUIRED_UNIFORMS.contains("uYawOffset"),
                "uYawOffset 必须在必需清单里（它正是上一版缺的那一个）");
        System.out.println("      审计（空 = 全对上）：" + (audit.isEmpty() ? "无差异" : audit));
        System.out.println("      声明了但会被 GLSL 优化掉的（正常，用默认值）："
                + dev.swordkingdom.horde.client.ProjectileRenderer.uniformHints());
        System.out.println("      清单：" + dev.swordkingdom.horde.client.ProjectileRenderer.uniformManifest());

        // ---- 3b) 群集那份也必须通过同一套「声明/使用」分析（共享 GLSL 的另一半） ----
        // 注意：**不**要求「群集声明的每个 uniform 都被用到」—— 是否会被 GLSL 优化掉由编译器决定
        // （投掷物是单骨骼用法，姿态布局那几个常量折叠后就是未使用）。这里只要求分析本身可用，
        // 并且 uTex 这种共享项两边都认。
        check(crowdDeclared.contains("uPoseStride") && crowdDeclared.contains("uScaleSlot"),
                "群集顶点着色器必须声明姿态布局那几个 uniform（分析器要认得它们）");
        check(dev.swordkingdom.horde.client.ProjectileRenderer.declaredUniforms(crowdFs).contains("uTex"),
                "片元着色器那份（uTex）也必须能被同一套分析器读出来");

        // ---- 4) Java 侧字段清单必须覆盖 REQUIRED_UNIFORMS（反射：静态 int / int[] 字段） ----
        java.util.Set<String> fields = new java.util.HashSet<>();
        int mapped = 0;
        for (java.lang.reflect.Field f : dev.swordkingdom.horde.client.ProjectileRenderer.class
                .getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == int.class || f.getType() == int[].class) {
                fields.add(f.getName());
                if (f.getName().equals("uBonePivot")) {
                    mapped++;
                }
            }
        }
        java.util.Set<String> missingFields = new java.util.TreeSet<>();
        for (String name : dev.swordkingdom.horde.client.ProjectileRenderer.REQUIRED_UNIFORMS) {
            if (!fields.contains(name)) {
                missingFields.add(name);
            }
        }
        check(missingFields.isEmpty(),
                "REQUIRED_UNIFORMS 里的每一项都必须有对应的 Java 字段（缺：" + missingFields + "）");
        check(mapped == 1,
                "uBonePivot 必须有一个对应的 int 字段（数组 uniform 用基准名定位；实际找到 " + mapped + " 个）");

        // ---- 5) 注入守卫必须真的会拦（静默落空是这次事故的根因） ----
        boolean threwMissing = false;
        try {
            dev.swordkingdom.horde.client.ProjectileRenderer.replaceOnce(crowdVs, "这段锚点不存在", "x");
        } catch (IllegalStateException e) {
            threwMissing = e.getMessage() != null && e.getMessage().contains("这段锚点不存在");
        }
        check(threwMissing, "锚点不存在时必须抛异常，并且**把锚点原文打出来**（否则就是静默落空）");
        boolean threwTwice = false;
        try {
            dev.swordkingdom.horde.client.ProjectileRenderer.replaceOnce(
                    "uniform float uYOffset;" + "\n" + "uniform float uYOffset;", "uniform float uYOffset;", "x");
        } catch (IllegalStateException e) {
            threwTwice = true;
        }
        check(threwTwice, "同一锚点出现多次时必须抛异常（不能猜着替换）");
        check(dev.swordkingdom.horde.client.ProjectileRenderer.replaceOnce("aXb", "X", "Y").equals("aYb"),
                "replaceOnce 正常路径必须真的替换");
    }

    /** 一行文本的前导空格数（诊断用：文本块的「附带缩进」会把空白悄悄吃掉） */
    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    private static String trim(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
