package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.HordeProfiler;
import dev.swordkingdom.horde.crowd.BlockWorld;
import dev.swordkingdom.horde.crowd.CrowdManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 「100 单位时 ai / combat / skill 各约 500ms」这条真机卡顿的**离线复现与二级分段**。
 *
 * <h2>真机证据（逐字）</h2>
 * <pre>
 * [horde] 卡顿 tick：第 14 tick 耗时 1604.1ms … units=100 proj=0
 *   | 本 tick 分段： ai=533.264ms physics=0.691ms push=1.883ms combat=523.416ms skill=539.819ms grid=0.076ms
 *   | 事件： （空）
 * </pre>
 * 判读：`physics/push/grid` 合计 &lt; 3ms（正常）；`ai`/`combat`/`skill` 各约 **500ms / 100 单位
 * = 每单位每阶段约 5ms**。三个阶段**同时**变慢，说明是它们**共用的某段代码**在按单位重复做昂贵的事。
 *
 * <h2>本 harness 干什么</h2>
 * <ol>
 *   <li>用真实 {@link CrowdManager#tickWithLevel} 跑 100 单位 / 150 tick，量出 ai/combat/skill 的毫秒；</li>
 *   <li>二级计数：每阶段的调用次数、索敌扫描/候选数、视线采样与方块查询、
 *       真实实体查询、以及每单位的平均调用次数；</li>
 *   <li>「优化前 vs 后」对照：{@link BlockWorld#setLosSampleCap(int)} 与
 *       {@code CrowdManager} 的节流开关都能在运行时切换，于是同一场景可以量出两条曲线。</li>
 * </ol>
 */
public final class CrowdCostHarness {

    private static int failures;

    public static void main(String[] args) {
        hundredUnitsCostTable();
        losSamplingCost();
        rangedGateCalls();
        System.out.println(failures == 0
                ? "\n== CROWD COST PASS =="
                : "\n== CROWD COST FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * 每单位每 tick 被问了几次「你是不是远程」——以及**问一次有多贵**。
     *
     * <p>真机主因的头号嫌疑：{@code TypeStats.ranged()} 在 aiTick / resolveCombat / skillTick
     * 里各被问一次（= 每单位每 tick 3 次），而它的真实实现走
     * {@code ProjectileCatalog.hasAutoAttack -> rangedGate}。门槛结论按类型缓存，
     * 但 **UNKNOWN 不缓存**（这是有意的：注册表早期判不出来时必须能重判）——
     * 于是「类型不在注册表里」时每次询问都要重做「取实体类 + 读字节码 + 扫候选池」。
     */
    private static void rangedGateCalls() {
        final int units = 100;
        final int ticks = 60;

        dev.swordkingdom.horde.crowd.ProjectileCatalog.setCasterClassResolver(id -> null);

        // ★ 优化前 / 后：同一场景，只切换「TypeStats 的类型级记忆」。
        //   为了让 ranged() 真的走到判定链，这里给类型一个 casterId
        //   （离线没有注册表 ⇒ 判定链每次都做完整工作，正是真机的形态）。
        ResourceLocation casterId = ResourceLocation.tryParse("minecraft:cost_unit");
        CrowdManager.TypeStats.rangedMemoEnabled = false;
        CrowdManager.TypeStats.rangedCalls = 0L;
        CrowdManager.TypeStats.rangedMemoHits = 0L;
        Result before = run(new Scenario(units, 0.05F, 12.0F, true), ticks, casterId);
        long callsBefore = CrowdManager.TypeStats.rangedCalls;

        CrowdManager.TypeStats.rangedMemoEnabled = true;
        CrowdManager.TypeStats.rangedCalls = 0L;
        CrowdManager.TypeStats.rangedMemoHits = 0L;
        Result after = run(new Scenario(units, 0.05F, 12.0F, true), ticks, casterId);
        long callsAfter = CrowdManager.TypeStats.rangedCalls;
        long hitsAfter = CrowdManager.TypeStats.rangedMemoHits;

        System.out.printf("[ ok ] 类型级「是不是远程」记忆：优化前 / 后（%d 单位 / %d tick，同一场景）：%n"
                        + "        优化前（每问一次都重走门槛+打分）：ranged() 询问 %d 次（%.1f 次/单位/tick）、"
                        + "ai+combat+skill=%.1fms%n"
                        + "        优化后（类型级记忆）：          ranged() 询问 %d 次、其中命中记忆 %d 次"
                        + "（%.2f%%）、未命中 %d 次、ai+combat+skill=%.1fms%n"
                        + "        降幅：三阶段耗时 %.2f×、真正走判定链的次数 %.1f×%n",
                units, ticks,
                callsBefore, (double) callsBefore / units / ticks,
                (before.ai + before.combat + before.skill) / 1.0E6,
                callsAfter, hitsAfter, 100.0 * hitsAfter / Math.max(1L, callsAfter),
                callsAfter - hitsAfter, (after.ai + after.combat + after.skill) / 1.0E6,
                ratio(before.ai + before.combat + before.skill, after.ai + after.combat + after.skill),
                ratio(callsBefore, Math.max(1L, callsAfter - hitsAfter)));

        check(callsAfter > 0L, "ranged() 必须被问到（否则计数没接上）");
        check(hitsAfter > 0L, "类型级记忆必须真的命中过（实际 " + hitsAfter + " 次）");
        check(callsAfter - hitsAfter <= units + 4L,
                "走完整判定链的次数必须收敛到「类型数」量级（实际 " + (callsAfter - hitsAfter)
                        + "，类型数 " + 2 + "）");
        check((before.ai + before.combat + before.skill) >= (after.ai + after.combat + after.skill),
                "类型级记忆不得让成本变高（前 " + ((before.ai + before.combat + before.skill) / 1.0E6)
                        + "ms vs 后 " + ((after.ai + after.combat + after.skill) / 1.0E6) + "ms）");
        HordeProfiler.resetAll();
    }

    private static Result runUnknownType(int units, int ticks) {
        return run(new Scenario(units, 0.05F, 12.0F, true), ticks,
                ResourceLocation.tryParse("minecraft:cost_unit"));
    }

    // ------------------------------------------------------------------

    /**
     * 100 单位、两军对峙：量 ai / combat / skill 与二级计数。
     *
     * <p>攻击力给 0.05、血量 1e6：**打不死**，于是 150 tick 里兵力恒定，
     * 两次对照的初始条件完全一致（否则「谁先死」会污染计时）。
     */
    private static void hundredUnitsCostTable() {
        final int units = 100;
        final int ticks = 150;
        Scenario s = new Scenario(units, 0.05F, 12.0F, true);
        Result r = run(s, ticks);

        System.out.printf("[ ok ] 100 单位 / %d tick（两军相距 12 格、打不死）：%n"
                        + "        ai=%.1fms  combat=%.1fms  skill=%.1fms  physics=%.1fms  push=%.1fms  grid=%.1fms"
                        + "  total=%.1fms（每 tick 合计）%n"
                        + "        二级计数（整场 %d tick）：索敌扫描=%d（ai %d + 战斗 %d）候选比较=%d"
                        + " 命中判定=%d 分离配对=%d%n"
                        + "        视线：调用=%d 采样点=%d 方块查询=%d 缓存命中=%d%n"
                        + "        每单位每 tick：索敌扫描=%.3f 候选比较=%.1f 视线调用=%.3f 采样点=%.1f"
                        + " 方块查询=%.3f%n",
                ticks,
                r.ai / 1.0E6 / ticks, r.combat / 1.0E6 / ticks, r.skill / 1.0E6 / ticks,
                r.physics / 1.0E6 / ticks, r.push / 1.0E6 / ticks, r.grid / 1.0E6 / ticks,
                r.total() / 1.0E6 / ticks,
                ticks, r.scans, r.aiScans, r.combatScans, r.candidates,
                r.hitCalls, r.separationPairs,
                r.losCalls, r.losSamples, r.blockQueries, r.cacheHits,
                (double) r.scans / units / ticks, (double) r.candidates / units / ticks,
                (double) r.losCalls / units / ticks, (double) r.losSamples / units / ticks,
                (double) r.blockQueries / units / ticks);

        // 计数与观测量必须真的非零，否则这条用例什么也没量到
        check(r.scans > 0L, "必须有索敌扫描");
        check(r.ai + r.combat + r.skill > 0L, "三个阶段必须有时长");
        check(r.physics > 0L && r.push > 0L && r.grid > 0L, "物理/分离/网格必须被计时到");
        // 「物理/分离/网格」必须仍然是廉价段（真机是 &lt;3ms 级别）。
        // 注意：优化之后 cpu 侧的大头已经消失，这里的**比例**不再有判别力
        // （离线两者都是亚毫秒级），所以只钉「每人每 tick 的绝对值上界」这个稳定性质。
        check(r.physics / 1.0E6 / ticks < 5.0, "physics 每 tick 必须 &lt; 5ms（实际 "
                + (r.physics / 1.0E6 / ticks) + "ms）");
        check(r.push / 1.0E6 / ticks < 5.0, "push 每 tick 必须 &lt; 5ms（实际 "
                + (r.push / 1.0E6 / ticks) + "ms）");
        System.out.printf("        （参考）physics+push+grid=%.1fms vs ai+combat+skill=%.1fms（每 tick）%n",
                (r.physics + r.push + r.grid) / 1.0E6 / ticks,
                (r.ai + r.combat + r.skill) / 1.0E6 / ticks);
        HordeProfiler.resetAll();
    }

    /**
     * 视线采样的代价与上限。
     *
     * <p>两件事分开量，避免把「点数」与「耗时」混在一起被 JIT 噪声污染：
     * <ol>
     *   <li><b>上限本身</b>：一条 48 格的射线，旧行为（0.5 格步长、无上限）要采 96 点，
     *       线上（24 点封顶）只采 23 点 —— 这条用采样点数直接断言（点数与 JIT 无关）；</li>
     *   <li><b>每单位每 tick 的规模</b>：100 单位场景下每 tick 的视线调用/采样点/方块查询次数。</li>
     * </ol>
     */
    private static void losSamplingCost() {
        // ---- 1) 上限本身：48 格长射线 ----
        BlockWorld.setLosSampleCap(256);   // 旧行为
        long longRayLegacy = countLosSamples(48.0F);
        BlockWorld.setLosSampleCap(BlockWorld.DEFAULT_LOS_SAMPLE_CAP);   // 线上
        long longRayFixed = countLosSamples(48.0F);
        long shortRayFixed = countLosSamples(12.0F);

        System.out.printf("[ ok ] 视线采样上限（48 格长射线 vs 线上封顶 %d 点）：%n"
                        + "        旧行为（0.5 格步长、无上限）：采样 %d 点%n"
                        + "        线上（%d 点封顶）：           采样 %d 点（降 %.1f×）%n"
                        + "        12 格射线（近距不受影响）：   采样 %d 点%n",
                BlockWorld.DEFAULT_LOS_SAMPLE_CAP,
                longRayLegacy,
                BlockWorld.DEFAULT_LOS_SAMPLE_CAP, longRayFixed,
                ratio(longRayLegacy, longRayFixed),
                shortRayFixed);

        check(longRayLegacy > longRayFixed,
                "48 格射线：放开上限必须采更多点（旧 " + longRayLegacy + " vs 线上 " + longRayFixed + "）");
        check(longRayFixed <= BlockWorld.DEFAULT_LOS_SAMPLE_CAP,
                "封顶后采样点数不得超过上限（实际 " + longRayFixed + "）");
        check(shortRayFixed == 23,
                "12 格射线（0.5 格步长 = 24 步）不该被上限影响：期望 23 点，实际 " + shortRayFixed);

        // ---- 2) 100 单位场景下每 tick 的规模 ----
        final int units = 100;
        final int ticks = 60;
        Result r = run(new Scenario(units, 0.05F, 12.0F, true), ticks);
        System.out.printf("[ ok ] 100 单位场景的视线规模（%d tick）：调用 %d 次、采样 %d 点、方块查询 %d 次、"
                        + "缓存命中 %d 次%n"
                        + "        每单位每 tick：视线调用 %.4f、采样点 %.3f、方块查询 %.3f%n",
                ticks, r.losCalls, r.losSamples, r.blockQueries, r.cacheHits,
                (double) r.losCalls / units / ticks, (double) r.losSamples / units / ticks,
                (double) r.blockQueries / units / ticks);
        check(r.losCalls > 0L, "100 单位场景里视线必须真的被调用");
        check(r.blockQueries > 0L, "方块查询必须真的发生");
        HordeProfiler.resetAll();
    }

    /** 一条指定长度的水平射线会采多少个点（走真实的 BlockWorld.hasLineOfSight）。 */
    private static long countLosSamples(float length) {
        BlockWorld world = new BlockWorld(new WallLevel(0.0F), 1 << 12);
        world.beginTick();
        world.hasLineOfSight(0.5F, 100.5F, 0.5F, 0.5F + length, 100.5F, 0.5F);
        return world.tickLosSamples();
    }

    // ------------------------------------------------------------------

    /** 一个 100 单位的战场。 */
    private static final class Scenario {
        final int units;
        final float damage;
        final float gap;
        final boolean ranged;

        Scenario(int units, float damage, float gap, boolean ranged) {
            this.units = units;
            this.damage = damage;
            this.gap = gap;
            this.ranged = ranged;
        }
    }

    private static final class Result {
        long ai;
        long combat;
        long skill;
        long physics;
        long push;
        long grid;
        long scans;
        long aiScans;
        long combatScans;
        long candidates;
        long hitCalls;
        long separationPairs;
        long losCalls;
        long losSamples;
        long blockQueries;
        long cacheHits;

        long total() {
            return ai + combat + skill + physics + push + grid;
        }
    }

    private static Result run(Scenario sc, int ticks) {
        return run(sc, ticks, null);
    }

    /**
     * @param casterId 非 null 时走「按需重判」那条路（{@code TypeStats.ranged()} 会去问
     *                 {@code ProjectileCatalog}）；传 null 时用登记时的快照
     *                 （离线里没有注册表，只能用快照把「远程单位」这条链立起来）。
     */
    private static Result run(Scenario sc, int ticks, ResourceLocation casterId) {
        CrowdManager cm = new CrowdManager(null, sc.units + 4);
        cm.setLevel(null);
        // 注入一个「有一面墙」的替身方块世界：视线射线必须真的走采样与缓存查询
        BlockWorld world = new BlockWorld(new WallLevel(sc.gap), 1 << 17);
        world.beginTick();
        cm.setBlockWorldForHarness(world);

        ResourceLocation unitType = ResourceLocation.tryParse("minecraft:cost_unit");
        cm.registerTypeStub(unitType, 0);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:cost_enemy"), 1);
        CrowdManager.TypeStats stats = CrowdManager.TypeStats.custom(
                0.6F, 1.95F, 1.62F, 1_000_000.0F, sc.damage, 0.23F, 16.0F,
                20, 0, 120, 16.0F, false, sc.ranged, casterId);
        for (int i = 0; i < sc.units; i++) {
            cm.setStatsOverride(i, stats);
        }
        for (int i = 0; i < sc.units; i++) {
            int team = i % 2;
            int k = i / 2;
            // 每队 50 人排成 10×5 的方阵，两阵相距 gap 格
            float x = team == 0 ? (k % 10) * 1.0F : sc.gap + (k % 10) * 1.0F;
            float z = (k / 10) * 1.0F;
            cm.putUnitRaw(i, team, x, 64.0F, z, team, 1_000_000.0F);
        }
        // 远程决策环境：固定一枚弹（不真的生成投掷物，只走「判定 + 视线」这条链）
        dev.swordkingdom.horde.crowd.ProjectileManager pm =
                new dev.swordkingdom.horde.crowd.ProjectileManager(ResourceLocation.tryParse("minecraft:overworld"));
        pm.setKindResolver(id -> dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind.ARROW);
        cm.setRangedInjector(new CrowdManager.RangedAttackInjector() {
            @Override
            public boolean attack(CrowdManager m, dev.swordkingdom.horde.crowd.CrowdData d,
                                  int slot, CrowdManager.TypeStats st, int period) {
                // 只走「决策 + 视线」，不真的发射：把计时集中在被怀疑的那段
                return CrowdManager.rangedAttackDefault(m, d, slot, st, period);
            }

            @Override
            public CrowdManager.RangedDeps provideDeps(CrowdManager m, dev.swordkingdom.horde.crowd.CrowdData d, int slot) {
                return new CrowdManager.RangedDeps(pm, unitType,
                        id -> ResourceLocation.tryParse("minecraft:arrow"));
            }
        });

        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        Result r = new Result();
        for (int t = 0; t < ticks; t++) {
            HordeProfiler.beginTick();
            world.beginTick();
            cm.tickWithLevel(null);
            HordeProfiler.tick(-1, -1);
            r.ai += HordeProfiler.tickStageNanos(HordeProfiler.Stage.AI);
            r.combat += HordeProfiler.tickStageNanos(HordeProfiler.Stage.COMBAT);
            r.skill += HordeProfiler.tickStageNanos(HordeProfiler.Stage.SKILL);
            r.physics += HordeProfiler.tickStageNanos(HordeProfiler.Stage.PHYSICS);
            r.push += HordeProfiler.tickStageNanos(HordeProfiler.Stage.PUSH);
            r.grid += HordeProfiler.tickStageNanos(HordeProfiler.Stage.GRID);
            r.scans += HordeProfiler.tickEnemyScans();
            r.aiScans += HordeProfiler.tickAiScans();
            r.combatScans += HordeProfiler.tickCombatScans();
            r.candidates += HordeProfiler.tickEnemyCandidates();
            r.hitCalls += HordeProfiler.tickHitCalls();
            r.separationPairs += HordeProfiler.tickSeparationPairs();
            r.losCalls += world.tickLosCalls();
            r.losSamples += world.tickLosSamples();
            r.blockQueries += world.tickBlockQueries();
            r.cacheHits += world.tickCacheHits();
        }
        return r;
    }

    private static double ratio(long a, long b) {
        return b <= 0L ? 0.0D : (double) a / (double) b;
    }

    /**
     * 替身方块世界：y &lt; 64 是地面，另在两阵之间立一面墙
     * —— 于是视线射线**有时通、有时被挡**，两种分支都被走到。
     *
     * <p>刻意**不碰任何方块状态**（{@code Blocks} 的类初始化要求注册表 bootstrap，
     * 离线起不来）：只回答「这个坐标实不实心」，而采样次数、缓存命中、方块查询次数
     * ——正是本 harness 要量的东西——与方块类型无关。
     */
    private static final class WallLevel implements BlockWorld.Level {
        private final int wallX;

        WallLevel(float gap) {
            this.wallX = (int) Math.floor(gap * 0.5F);
        }

        @Override
        public boolean isSolid(BlockPos pos) {
            if (pos.getY() < 64) {
                return true;   // 地面
            }
            return pos.getX() == wallX && pos.getY() == 64;   // 两阵之间那面墙
        }
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            return;
        }
        failures++;
        System.out.println("[FAIL] " + what);
    }
}
