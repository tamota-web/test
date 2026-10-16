package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.crowd.physics.BroadphaseGrid;
import dev.swordkingdom.horde.crowd.physics.CollisionDetectionMode;
import dev.swordkingdom.horde.crowd.physics.FixedStepClock;
import dev.swordkingdom.horde.crowd.physics.PhysicsLayers;
import dev.swordkingdom.horde.crowd.physics.PhysicsQuery;
import dev.swordkingdom.horde.crowd.physics.PhysicsWorld;
import dev.swordkingdom.horde.crowd.physics.QueryTriggerInteraction;
import dev.swordkingdom.horde.crowd.physics.RaycastHit;
import dev.swordkingdom.horde.crowd.physics.RigidbodyConstraints;
import dev.swordkingdom.horde.crowd.physics.RigidbodySoA;
import dev.swordkingdom.horde.crowd.physics.ShapeMath;
import dev.swordkingdom.horde.crowd.physics.Sweep;
import dev.swordkingdom.horde.crowd.physics.VoxelSource;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 物理层（{@code crowd/physics}）的离线对照验证 —— 不需要 Minecraft、不需要世界、不需要 GL。
 *
 * <h2>验证策略：能用「手算的二进制精确值」就不用容差</h2>
 * 所有 TOI 用例都刻意选**二进制可精确表示**的数（0.5 / 0.375 / 0.4375 / 0.625 / 0.9375 …），
 * 于是断言可以是「逐位相等」而不是「误差小于 1e-6」。理由：扫掠求交是纯代数，
 * 一旦某处引入除法顺序变化或多余的舍入，容差断言会照样通过 ——
 * 手算精确值才能钉住「公式没被改坏」。
 *
 * <h2>覆盖点（对应用户要求逐条）</h2>
 * <ol>
 *   <li>射线 / 球扫掠 / AABB 扫掠 / 胶囊 TOI 与手算结果逐位一致；</li>
 *   <li>60 格/秒的箭在 1 格厚的墙前**不得隧穿**（并与「离散检测会隧穿」形成对照）；</li>
 *   <li>固定步长下同输入两次运行状态**逐位一致**；</li>
 *   <li>接触求解在给定初始穿透下收敛到容差内，且收敛后不抖；</li>
 *   <li>休眠在静置 N 个 tick 后触发、被推动后唤醒、睡着的不积分；</li>
 *   <li>层掩码 / 触发体 / 队伍掩码的过滤语义；</li>
 *   <li>10k 单位 broadphase 的每步调用次数上界与耗时量级；</li>
 *   <li>固定步长档位只取离散值 + 累加器/alpha 语义；</li>
 *   <li>与原版每 tick 重力/阻力的等价换算（逐 tick 语义对照）。</li>
 * </ol>
 */
public final class PhysicsHarness {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        System.out.println("== 物理层离线对照（Unity3D 参照 / PhysX 风格） ==");
        raycastExact();
        capsuleToiExact();
        sphereAndBoxCastExact();
        voxelDdaExact();
        ccdNoTunneling();
        ccdEndToEnd();
        determinismBitExact();
        solverConvergenceAndNoJitter();
        solverRestOnVoxel();
        sleepStateMachine();
        layerMaskTeamAndTrigger();
        overlapQueries();
        fixedStepTiers();
        vanillaTickParity();
        broadphaseCostBound();

        System.out.println();
        System.out.println("检查项 " + checks + " 个，失败 " + failures + " 个");
        System.out.println(failures == 0 ? "== PHYSICS PASS ==" : "== PHYSICS FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // 1) 手算对照：线段 vs AABB
    // ==================================================================

    private static void raycastExact() {
        System.out.println("\n[1] 线段 vs AABB（slab 法）手算逐位对照");
        float[] n = new float[3];

        // 盒子 [2,3]x[0,1]x[0,1]；射线 (0,0.5,0.5) 方向 (1,0,0) 长度 4
        // 手算：进入面 x=2，t = (2-0)/4 = 0.5（二进制精确）
        float t = Sweep.segmentAabb(0.0F, 0.5F, 0.5F, 4.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        checkEq(t, 0.5F, "射线从 (0,0.5,0.5) 撞 [2,3] 面：t 必须逐位等于 0.5");
        checkEq(n[0], -1.0F, "法线必须指向 -X（把射线推回去）");
        checkEq(n[1], 0.0F, "法线 Y 分量必须为 0");
        checkEq(n[2], 0.0F, "法线 Z 分量必须为 0");

        // 起点在盒子的「正前方 0.5 格」：t = 1.5/4 = 0.375（二进制精确）
        float t2 = Sweep.segmentAabb(0.5F, 0.5F, 0.5F, 4.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        checkEq(t2, 0.375F, "起点 (0.5,0.5,0.5)：t 必须逐位等于 0.375");

        // Y 轴方向：从上往下撞盒顶
        float t3 = Sweep.segmentAabb(2.5F, 3.0F, 0.5F, 0.0F, -4.0F, 0.0F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        checkEq(t3, 0.5F, "从上方垂直下落撞盒顶：t = (3-1)/4 = 0.5");
        checkEq(n[1], 1.0F, "撞盒顶的法线必须是 +Y");

        // 未命中（y 在盒子之外）
        float miss = Sweep.segmentAabb(0.0F, 1.5F, 0.5F, 4.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        check(miss == Sweep.NO_HIT, "y=1.5 的射线必须判为未命中（盒子只到 y=1）");

        // 起点在盒内：t = 0 且法线取最小平移轴（x 两侧都是 0.5，取第一轴 -X）
        float inside = Sweep.segmentAabb(2.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        checkEq(inside, 0.0F, "起点在盒内：t 必须为 0");
        checkEq(n[0], -1.0F, "起点在盒内：法线取最小平移轴（这里是 -X）");

        // 移动 AABB（Minkowski 外扩）vs 静态 AABB：半宽 0.25 的方块从 (-2,0.5,0.5) 走 4 格
        // 手算：(2-0.25-(-2))/4 = 3.75/4 = 0.9375
        float tBox = Sweep.movingAabb(-2.0F, 0.5F, 0.5F, 4.0F, 0.0F, 0.0F, 0.25F, 0.25F, 0.25F,
                2.0F, 0.0F, 0.0F, 3.0F, 1.0F, 1.0F, n);
        checkEq(tBox, 0.9375F, "移动 AABB（半宽 0.25）撞静态盒：t 必须逐位等于 0.9375");
        checkEq(n[0], -1.0F, "移动 AABB 撞面法线为 -X");
    }

    // ==================================================================
    // 2) 手算对照：线段 vs 胶囊
    // ==================================================================

    private static void capsuleToiExact() {
        System.out.println("\n[2] 线段 vs 胶囊（解析三段求解）手算逐位对照");
        float[] n = new float[3];

        // 胶囊轴 (0,0,0)->(0,2,0)，半径 0.5
        // 射线 (-4,1,0) 方向 (8,0,0)：侧面命中，手算 t = (4-0.5)/8 = 0.4375
        float t = Sweep.segmentCapsule(-4.0F, 1.0F, 0.0F, 8.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.5F, n);
        checkEq(t, 0.4375F, "侧面命中：t 必须逐位等于 0.4375");
        checkEq(n[0], -1.0F, "侧面命中的法线为 -X");

        // 端球命中：从上往下，(0,5,0) 方向 (0,-4,0)：手算 t = (5-2.5)/4 = 0.625
        float tCap = Sweep.segmentCapsule(0.0F, 5.0F, 0.0F, 0.0F, -4.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.5F, n);
        checkEq(tCap, 0.625F, "端球命中：t 必须逐位等于 0.625");
        checkEq(n[1], 1.0F, "端球命中的法线为 +Y");

        // 与轴平行、恰好擦过端球：(0.5,3,0) 方向 (0,-4,0)：手算 t = (3-2)/4 = 0.25
        float tGraze = Sweep.segmentCapsule(0.5F, 3.0F, 0.0F, 0.0F, -4.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.5F, n);
        checkEq(tGraze, 0.25F, "平行擦过端球：t 必须逐位等于 0.25");
        checkEq(n[0], 1.0F, "擦过端球的法线为 +X");

        // 未命中：平行但横向偏移 1.0 格 > 半径 0.5（沿 z 偏，不能沿 y 偏 —— 那会正好穿过轴心）
        float miss = Sweep.segmentCapsule(-1.0F, 1.0F, 1.0F, 8.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.5F, n);
        check(miss == Sweep.NO_HIT, "横向偏移 1.0 格（半径 0.5）的平行射线必须未命中");

        // 球体扫掠 vs 胶囊：半径 0.25 的球 → 等效半径 0.75，手算 t = (4-0.75)/8 = 0.40625
        float tCast = Sweep.sphereCastCapsule(-4.0F, 1.0F, 0.0F, 8.0F, 0.0F, 0.0F, 0.25F,
                0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.5F, n);
        checkEq(tCast, 0.40625F, "球体扫掠 vs 胶囊（半径叠加）：t 必须逐位等于 0.40625");

        // 线段-线段最近点（接触生成的基础）：两条互相垂直的线段，最近距离恰好 1
        float[] p1 = new float[3];
        float[] p2 = new float[3];
        ShapeMath.closestPointsSegments(0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 1.0F, 3.0F, 0.0F, p1, p2);
        checkEq(p1[0], 1.0F, "最近点：线段 1 上的最近点必须是 (1,0,0)");
        checkEq(p1[1], 0.0F, "最近点：线段 1 上的最近点 y = 0");
        checkEq(p2[0], 1.0F, "最近点：线段 2 上的最近点 x = 1");
        checkEq(p2[1], 1.0F, "最近点：线段 2 上的最近点 y = 1（距离恰好 1）");
        checkEq(p2[2], 0.0F, "最近点：线段 2 上的最近点 z = 0");
    }

    // ==================================================================
    // 3) 球扫掠 / 盒扫掠（世界级 API）
    // ==================================================================

    private static void sphereAndBoxCastExact() {
        System.out.println("\n[3] SphereCast / BoxCast（世界级查询原语）");
        StubWorld world = new StubWorld();
        world.add(2, 0, 0);   // 一个整格方块
        RigidbodySoA bodies = new RigidbodySoA(4);
        PhysicsWorld pw = new PhysicsWorld(bodies, world, PhysicsWorld.Settings.vanilla());
        PhysicsQuery q = pw.query();
        RaycastHit hit = new RaycastHit();

        // 半径 0.5 的球从 (0,0.5,0.5) 走 4 格：手算 t = (2-0.5-0)/4 = 0.375
        boolean ok = q.sphereCast(0.0F, 0.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F, 4.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        check(ok, "球扫掠必须命中方块");
        checkEq(hit.t, 0.375F, "球扫掠 t 必须逐位等于 0.375");
        checkEq(hit.pointX, 1.5F, "球扫掠命中点 x 必须逐位等于 1.5（球面贴到方块面）");
        checkEq(hit.distance, 1.5F, "球扫掠世界距离必须等于 1.5");

        // 盒扫掠（半宽 0.25）：t = (2-0.25-(-2))/4 = 0.9375
        boolean okBox = q.boxCast(-2.0F, 0.5F, 0.5F, 0.25F, 0.25F, 0.25F, 1.0F, 0.0F, 0.0F, 4.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        check(okBox, "盒扫掠必须命中方块");
        checkEq(hit.t, 0.9375F, "盒扫掠 t 必须逐位等于 0.9375");

        // 纯射线不撞（y 抬高到方块之上）
        boolean noHit = q.raycast(0.0F, 1.5F, 0.5F, 1.0F, 0.0F, 0.0F, 4.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        check(!noHit, "y=1.5 的射线不能命中 y∈[0,1] 的方块");

        // 视线：薄墙也必须挡住（旧的 0.5 格采样实现会漏判这一类）
        StubWorld thin = new StubWorld();
        thin.add(20, 0, 0);
        RigidbodySoA b2 = new RigidbodySoA(1);
        PhysicsWorld pw2 = new PhysicsWorld(b2, thin, PhysicsWorld.Settings.vanilla());
        check(!pw2.query().hasLineOfSight(0.5F, 0.5F, 0.5F, 40.0F, 0.5F, 0.5F),
                "40 格外的 1 格厚墙必须挡住视线（DDA + 精确求交，不是采样）");
        check(pw2.query().hasLineOfSight(0.5F, 0.5F, 2.5F, 40.0F, 0.5F, 2.5F),
                "错开一格的同样射线必须通畅");
    }

    // ==================================================================
    // 4) 体素 DDA：访问次数与命中位置
    // ==================================================================

    private static void voxelDdaExact() {
        System.out.println("\n[4] 体素 DDA 遍历（访问的方格数与命中）");
        StubWorld world = new StubWorld();
        for (int x = 0; x < 40; x++) {
            world.add(x, 0, 0);   // 一条 40 格长的地板
        }
        RigidbodySoA bodies = new RigidbodySoA(1);
        PhysicsWorld pw = new PhysicsWorld(bodies, world, PhysicsWorld.Settings.vanilla());
        PhysicsQuery q = pw.query();
        RaycastHit hit = new RaycastHit();
        q.resetCounters();
        boolean ok = q.raycast(0.5F, 2.5F, 0.5F, 0.0F, -1.0F, 0.0F, 4.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        check(ok, "从上方 2.5 格下落的射线必须打到地板");
        checkEq(hit.t, 0.375F, "地板命中 t 必须逐位等于 (2.5-1)/4 = 0.375");
        checkEq(hit.blockY, 0, "命中方块必须是 y=0 那一格（命中点正好在顶面上，不能算到隔壁空气格）");
        System.out.println("      体素访问次数 = " + q.voxelSweep().voxelVisits()
                + "，盒测试次数 = " + q.voxelSweep().boxTests()
                + "（垂直 4 格 → 访问 4~5 格）");
        check(q.voxelSweep().voxelVisits() <= 6,
                "垂直 4 格的射线访问的体素数必须 ≤ 6（DDA 每格恰好访问一次，实测 "
                        + q.voxelSweep().voxelVisits() + "）");

        // 长射线（48 格，在地板上方 1.5 格飞过）访问次数 ≈ 格数，不应爆炸
        q.resetCounters();
        q.raycast(0.5F, 1.5F, 0.5F, 1.0F, 0.0F, 0.0F, 48.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        System.out.println("      48 格水平射线的体素访问次数 = " + q.voxelSweep().voxelVisits()
                + "（旧的 0.5 格采样实现要 96 次方块查询，且上限 24 点时会更粗）");
        check(q.voxelSweep().voxelVisits() <= 50,
                "48 格射线的体素访问次数必须 ≤ 50（每条射线与距离成正比，而不是固定采样）");
    }

    // ==================================================================
    // 5) CCD：不得隧穿（含与「离散必然隧穿」的对照）
    // ==================================================================

    private static void ccdNoTunneling() {
        System.out.println("\n[5] CCD：高速投掷物不得隧穿 1 格厚的墙");

        // 场景：1 格厚的墙（x=10），箭从小柱前 1.5 格出发，一步走 4 格（= 80 格/秒 @20Hz）
        // 起点 8.0，终点 12.0 —— 离散检测只看终点（12.0 在墙外）→ 必然隧穿
        float startX = 8.0F;
        float stepLen = 4.0F;

        // --- 5a) 纯数学对照：离散点检 vs 扫掠 ---
        boolean discreteSeesWall = pointInsideWall(startX + stepLen);
        check(!discreteSeesWall,
                "对照：终点 (" + (startX + stepLen) + ") 不在墙内 —— 离散检测**看不到**这面墙（这正是隧穿的成因）");
        float[] n = new float[3];
        float tWall = Sweep.movingAabb(startX, 0.9F, 0.5F, stepLen, 0.0F, 0.0F, 0.125F, 0.125F, 0.125F,
                10.0F, 0.0F, 0.0F, 11.0F, 1.0F, 1.0F, n);
        checkEq(tWall, 0.46875F, "扫掠 TOI 必须逐位等于 (10-0.125-8.0)/4 = 0.46875（全二进制精确值）");

        // --- 5b) 关掉子步保护（maxDisplacementPerSubStep 放大）+ 离散检测 → 隧穿（复现问题）---
        PhysicsWorld discrete = ccdScene(CollisionDetectionMode.DISCRETE, 8.0F);
        discrete.stepOneTick();
        int arrow = 0;
        float discreteX = discrete.bodies().posX[arrow];
        check(discreteX > 11.0F,
                "离散检测（无 CCD、单子步）必然隧穿：实测终点 x = " + f(discreteX) + " > 11（已穿到墙后）");

        // --- 5c) 同样的位移 + CCD（CONTINUOUS_DYNAMIC）→ 停在墙前 ---
        PhysicsWorld ccd = ccdScene(CollisionDetectionMode.CONTINUOUS_DYNAMIC, 8.0F);
        ccd.stepOneTick();
        float ccdX = ccd.bodies().posX[arrow];
        check(ccdX <= 10.0F, "开了 CCD 之后绝不越过墙面 x=10：实测 x = " + f(ccdX));
        checkNear(ccdX, 9.5F, 0.02F,
                "CCD 落点 ≈ 9.5（8.0 + 4×0.375，自己的半径已经贴到墙面，再退一个 contactOffset）");
        check(ccd.ccdClamps == 1, "必须正好发生 1 次 CCD 钳制（实测 " + ccd.ccdClamps + "）");
        check(ccd.ccdHitCount() == 1, "必须上报 1 条 CCD 命中（供投掷物结算消费）");
        checkEq(ccd.ccdHit(0).t, 0.375F, "上报的 TOI t 必须逐位等于 0.375");
        checkEq(ccd.ccdHit(0).blockX, 10, "上报的命中方块 x 必须是 10");
        checkEq(ccd.ccdHit(0).normalX, -1.0F, "上报的命中法线必须是 -X");
        checkEq(ccd.bodies().velX[arrow], 0.0F, "撞墙后沿法线的速度分量必须被清零");

        // --- 5d) 60 格/秒（3 格/tick）的箭：默认子步策略与 CCD 双保险 ---
        PhysicsWorld fast = ccdScene(CollisionDetectionMode.CONTINUOUS_DYNAMIC, 0.5F);
        for (int i = 0; i < 6; i++) {
            fast.stepOneTick();
        }
        float fastX = fast.bodies().posX[arrow];
        check(fastX <= 10.0F,
                "60 格/秒的箭连跑 6 个 tick 也不得出现在墙后：实测 x = " + f(fastX));
        System.out.println("      60 格/秒 × 6 tick：末位置 x = " + f(fastX)
                + "（起点 8.0，墙在 x=10，最后停在墙面）");

        // --- 5e) 子步细分的作用：让**离散**重叠检测也能看见墙 ---
        // 注意这不是「子步=碰撞响应」：子步只保证「某一步里两者的包围盒真的重叠了」，
        // 从而让离散重叠检测/接触求解有机会看到障碍物。CCD 与接触求解才是响应。
        int seenLarge = substepsSeeingWall(8.0F);
        int seenFine = substepsSeeingWall(0.5F);
        checkEq(seenLarge, 0, "单子步走 4 格时：没有任何一个子步里箭的包围盒与墙重叠（离散检测必然看不见 = 隧穿）");
        check(seenFine >= 1, "8 个子步（每步 0.375 格）时：至少有一个子步里包围盒与墙重叠（实测 "
                + seenFine + " 个子步可见）→ 接触求解/离散检测有机会响应");
        System.out.println("      子步细分可见性：大步长可见 " + seenLarge + " 次，细子步可见 " + seenFine + " 次");
    }

    /** 在一个 tick 内逐个子步统计「箭的包围盒和墙重叠」出现了几次。 */
    private static int substepsSeeingWall(float maxDispPerSubStep) {
        PhysicsWorld pw = ccdScene(CollisionDetectionMode.DISCRETE, maxDispPerSubStep);
        RigidbodySoA b = pw.bodies();
        int a = 0;
        float tickDt = pw.clock().fixedDt();
        float disp = (float) StrictMath.sqrt(b.velX[a] * b.velX[a] + b.velZ[a] * b.velZ[a]) * tickDt;
        int sub = pw.clock().planSubSteps(disp, maxDispPerSubStep);
        float dt = tickDt / sub;
        int seen = 0;
        for (int k = 0; k < sub; k++) {
            pw.substep(dt);
            // 墙：x ∈ [10,11]，y ∈ [1,4]，z ∈ [-1,2]
            boolean overlap = b.maxX(a) > 10.0F && b.minX(a) < 11.0F
                    && b.maxY(a) > 1.0F && b.minY(a) < 4.0F
                    && b.maxZ(a) > -1.0F && b.minZ(a) < 2.0F;
            if (overlap) {
                seen++;
            }
        }
        return seen;
    }

    private static boolean pointInsideWall(float x) {
        return x >= 10.0F && x <= 11.0F;
    }

    /** 造一个 CCD 场景：地板 + 1 格厚的墙 + 一枚高速「箭」。 */
    private static PhysicsWorld ccdScene(CollisionDetectionMode mode, float maxDispPerSubStep) {
        StubWorld world = new StubWorld();
        for (int x = 0; x <= 14; x++) {
            for (int z = -1; z <= 2; z++) {
                world.add(x, 0, z);   // 地板
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int z = -1; z <= 2; z++) {
                world.add(10, y, z);  // 1 格厚的墙
            }
        }
        RigidbodySoA bodies = new RigidbodySoA(4);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        s.maxDisplacementPerSubStep = maxDispPerSubStep;
        PhysicsWorld pw = new PhysicsWorld(bodies, world, s);
        int a = bodies.alloc();
        pw.configureProjectile(a, 0.5F, mode);
        // 「箭」：自由飞行的动力学体（不吃重力，靠速度直线飞行）
        bodies.setKinematic(a, false);
        bodies.setMass(a, 1.0F);
        bodies.setGravityScale(a, 0.0F);
        bodies.setDrag(a, 0.0F, 0.0F);
        bodies.setCollisionDetection(a, mode);
        bodies.setConstraints(a, RigidbodyConstraints.FREEZE_ROTATION);
        bodies.setCapsule(a, 0.5F, 1.0F);
        // 注意 y：地板顶面在 y=1，箭的胶囊半径 0.5，所以中心必须 > 1.5 ——
        // 否则一开始就嵌在地板里，CCD 会在 t=0 处命中地板（那是「起始重叠」，不是隧穿）
        bodies.setPose(a, 8.0F, 1.75F, 0.5F);
        bodies.setVelocity(a, 80.0F, 0.0F, 0.0F);   // 80 格/秒 = 4 格/tick
        return pw;
    }

    // ==================================================================
    // 6) 端到端：一整个 tick 序列不隧穿
    // ==================================================================

    private static void ccdEndToEnd() {
        System.out.println("\n[6] 端到端：60 格/秒的箭飞行 20 tick 后必须停在墙前");
        StubWorld world = new StubWorld();
        for (int x = 0; x <= 30; x++) {
            for (int z = -2; z <= 2; z++) {
                world.add(x, 0, z);
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int z = -2; z <= 2; z++) {
                world.add(20, y, z);   // 墙
            }
        }
        RigidbodySoA bodies = new RigidbodySoA(2);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        PhysicsWorld pw = new PhysicsWorld(bodies, world, s);
        int a = bodies.alloc();
        pw.configureProjectile(a, 0.125F, CollisionDetectionMode.CONTINUOUS_DYNAMIC);
        bodies.setKinematic(a, false);
        bodies.setMass(a, 1.0F);
        bodies.setGravityScale(a, 0.0F);
        bodies.setDrag(a, PhysicsWorld.Settings.vanillaDragToPhysX(0.99F, 20.0F), 0.0F);
        bodies.setCollisionDetection(a, CollisionDetectionMode.CONTINUOUS_DYNAMIC);
        bodies.setConstraints(a, RigidbodyConstraints.FREEZE_ROTATION);
        bodies.setCapsule(a, 0.125F, 0.25F);
        bodies.setPose(a, 2.0F, 1.5F, 0.5F);   // 地板顶面 y=1，半径 0.125 → 中心 1.5 安全悬空
        bodies.setVelocity(a, 60.0F, 0.0F, 0.0F);   // 3 格/tick
        int clamps = 0;
        int firstTickSubSteps = -1;
        for (int tick = 0; tick < 20; tick++) {
            pw.stepOneTick();
            if (firstTickSubSteps < 0) {
                firstTickSubSteps = pw.lastSubSteps;
            }
            clamps += pw.ccdClamps;
        }
        float x = bodies.posX[a];
        check(x <= 20.0F, "20 tick 后不得穿到墙后：实测 x = " + f(x));
        System.out.println("      20 tick 后 x = " + f(x) + "（墙在 x=20，起点 2.0，共 " + clamps + " 次 CCD 钳制）");
        // 逐 tick 位移上界：60 格/秒 = 3 格/tick
        check(firstTickSubSteps >= 4, "60 格/秒 → 每 tick 3 格 → 首 tick 子步数应 ≥ 4（实测 "
                + firstTickSubSteps + "）");
        check(pw.lastSubDt > 0.0F, "子步 dt 必须为正");
        check(pw.contacts().overflowed == 0, "接触数不得触到硬上限（丢弃接触 = 物理结果不完整）");
    }

    // ==================================================================
    // 7) 确定性：同输入两次运行逐位一致
    // ==================================================================

    private static void determinismBitExact() {
        System.out.println("\n[7] 确定性：同输入序列两次运行必须逐位一致");
        Digest d1 = runDeterministicScene();
        Digest d2 = runDeterministicScene();
        check(d1.hash == d2.hash, "两次运行的状态摘要必须逐位一致："
                + Long.toHexString(d1.hash) + " vs " + Long.toHexString(d2.hash));
        check(d1.pairs == d2.pairs, "两次运行的 broadphase 配对数必须一致：" + d1.pairs + " vs " + d2.pairs);
        check(d1.contacts == d2.contacts, "两次运行的接触数必须一致：" + d1.contacts + " vs " + d2.contacts);
        check(d1.order == d2.order, "两次运行的配对**顺序**也必须一致（顺序变了求解结果就会变）");
        System.out.println("      200 单位 × 100 子步：状态摘要 " + Long.toHexString(d1.hash)
                + "，累计配对 " + d1.pairs + "，累计接触 " + d1.contacts);

        // 顺序敏感性自证：故意打乱参数化输入顺序，摘要必须不同（否则上面的断言没有意义）
        Digest shuffled = runDeterministicSceneShuffled();
        check(shuffled.hash != d1.hash, "换一种输入顺序后结果必须不同（证明摘要真的对顺序敏感）");
    }

    private static final class Digest {
        long hash;
        long pairs;
        long contacts;
        long order;
    }

    private static Digest runDeterministicScene() {
        return runScene(false);
    }

    private static Digest runDeterministicSceneShuffled() {
        return runScene(true);
    }

    private static Digest runScene(boolean shuffle) {
        int n = 200;
        RigidbodySoA bodies = new RigidbodySoA(n + 1);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        PhysicsWorld pw = new PhysicsWorld(bodies, VoxelSource.EMPTY, s);
        long seed = 12345L;
        for (int i = 0; i < n; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float rx = ((seed >>> 40) & 0xFFFF) / 65536.0F;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float rz = ((seed >>> 40) & 0xFFFF) / 65536.0F;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float vx = ((seed >>> 40) & 0xFFFF) / 65536.0F - 0.5F;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float vz = ((seed >>> 40) & 0xFFFF) / 65536.0F - 0.5F;
            int slot = shuffle ? (n - 1 - i) : i;   // 打乱「谁占哪个槽位」= 打乱配对顺序
            bodies.alloc();                          // 分配槽位（highWater 递增）
            pw.configureUnit(slot, 0.6F, 1.8F, 0.0F, slot & 1, PhysicsLayers.ALL);
            bodies.setPose(slot, rx * 4.0F, 0.9F, rz * 4.0F);
            bodies.setVelocity(slot, vx, 0.0F, vz);
            bodies.setGravityScale(slot, 0.0F);
        }
        Digest d = new Digest();
        for (int step = 0; step < 100; step++) {
            pw.substep(0.05F);
            d.contacts += pw.contacts().generated;
            d.pairs += pw.broadphase().pairTests();
        }
        // 状态摘要：把所有字段按「索引升序 + 字段固定顺序」折进一个 64 位哈希
        long h = 1469598103934665603L;
        for (int i = 0; i < bodies.highWater; i++) {
            h = mix(h, Float.floatToIntBits(bodies.posX[i]));
            h = mix(h, Float.floatToIntBits(bodies.posY[i]));
            h = mix(h, Float.floatToIntBits(bodies.posZ[i]));
            h = mix(h, Float.floatToIntBits(bodies.velX[i]));
            h = mix(h, Float.floatToIntBits(bodies.velY[i]));
            h = mix(h, Float.floatToIntBits(bodies.velZ[i]));
            h = mix(h, bodies.awake[i] ? 1 : 0);
        }
        d.hash = h;
        // 配对**顺序**摘要：最后再建一次表并按顺序折叠 (i,j)
        pw.broadphase().build(bodies);
        final long[] oh = {1469598103934665603L};
        pw.broadphase().forEachPair(bodies, (i, j) -> {
            oh[0] = mix(oh[0], i * 100003 + j);
        });
        d.order = oh[0];
        return d;
    }

    private static long mix(long h, int v) {
        h ^= v;
        h *= 1099511628211L;
        return h;
    }

    // ==================================================================
    // 8) 接触求解：收敛到容差内且不抖动
    // ==================================================================

    private static void solverConvergenceAndNoJitter() {
        System.out.println("\n[8] 顺序冲量求解：给定初始穿透的收敛性与稳定性");
        RigidbodySoA bodies = new RigidbodySoA(4);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        PhysicsWorld pw = new PhysicsWorld(bodies, VoxelSource.EMPTY, s);
        int a = bodies.alloc();
        int b = bodies.alloc();
        // 两个质量相同的单位：半径 0.3、高 1.8，中心相距 0.2 → 初始穿透 = 0.6 - 0.2 = 0.4
        pw.configureUnit(a, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        pw.configureUnit(b, 0.6F, 1.8F, 0.0F, 1, PhysicsLayers.ALL);
        bodies.setPose(a, 0.0F, 0.9F, 0.0F);
        bodies.setPose(b, 0.2F, 0.9F, 0.0F);
        bodies.setGravityScale(a, 0.0F);
        bodies.setGravityScale(b, 0.0F);
        bodies.setDrag(a, 0.0F, 0.0F);
        bodies.setDrag(b, 0.0F, 0.0F);
        bodies.setMaterial(a, 0.0F, 0.0F);
        bodies.setMaterial(b, 0.0F, 0.0F);

        float initial = penetration(bodies, a, b);
        System.out.println("      初始穿透 = " + f(initial) + "，slop = " + f(s.slop)
                + "，单步位置修正上限 = " + f(s.maxPositionCorrection));
        int stepsToTolerance = -1;
        float tol = s.slop + 1.0E-4F;
        float pen = initial;
        for (int i = 1; i <= 20; i++) {
            pw.substep(0.05F);
            pen = penetration(bodies, a, b);
            if (stepsToTolerance < 0 && pen <= tol) {
                stepsToTolerance = i;
            }
        }
        check(stepsToTolerance > 0, "初始穿透 " + f(initial) + " 必须在 20 步内收敛到 ≤ " + f(tol)
                + "（实测第 " + stepsToTolerance + " 步收敛，残留 " + f(pen) + "）");
        check(pen <= tol, "最终残留穿透必须 ≤ 容差：实测 " + f(pen));
        System.out.println("      收敛步数 = " + stepsToTolerance + "，残留穿透 = " + f(pen)
                + "（每步最多修正 " + f(s.maxPositionCorrection) + " 格 → 理论下界 "
                + (int) Math.ceil((initial - s.slop) / s.maxPositionCorrection) + " 步）");

        // 不抖动：收敛后继续跑 40 步，位置漂移必须为 0（速度被约束成 0，且位置修正不再触发）
        float x0 = bodies.posX[a];
        float x1 = bodies.posX[b];
        float maxV = 0.0F;
        for (int i = 0; i < 40; i++) {
            pw.substep(0.05F);
            maxV = Math.max(maxV, Math.abs(bodies.velX[a]));
            maxV = Math.max(maxV, Math.abs(bodies.velX[b]));
        }
        float driftA = Math.abs(bodies.posX[a] - x0);
        float driftB = Math.abs(bodies.posX[b] - x1);
        check(maxV <= 1.0E-4F, "收敛后速度必须为 0（不抖动）：实测最大 |vx| = " + maxV);
        check(driftA <= 1.0E-5F && driftB <= 1.0E-5F,
                "收敛后位置必须不再漂移：实测 " + f(driftA) + " / " + f(driftB));
        check(penetration(bodies, a, b) <= tol, "40 步之后残留穿透仍然 ≤ 容差（不会重新陷进去）");
        System.out.println("      收敛后 40 步：最大 |vx| = " + maxV + "，位置漂移 = "
                + f(driftA) + " / " + f(driftB));
    }

    /** 两个胶囊沿 X 轴的实际重叠量（几何复算，不信任求解器的内部计数）。 */
    private static float penetration(RigidbodySoA b, int i, int j) {
        float dx = b.posX[j] - b.posX[i];
        float dz = b.posZ[j] - b.posZ[i];
        // 直立胶囊：轴在各自中心的竖直线段上，Y 区间相同 → 最近点在 XZ 平面上
        float dist = (float) StrictMath.sqrt(dx * dx + dz * dz);
        return (b.radius[i] + b.radius[j]) - dist;
    }

    // ==================================================================
    // 9) 地形接触（阶段 3 的开关）：站在方块上不陷进去
    // ==================================================================

    private static void solverRestOnVoxel() {
        System.out.println("\n[9] 体素接触（阶段 3 开关）：单位站在方块地板上");
        StubWorld world = new StubWorld();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.add(x, 0, z);   // y=0 一层地板（顶面 y=1）
            }
        }
        RigidbodySoA bodies = new RigidbodySoA(2);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        s.unitVoxelContacts = true;
        PhysicsWorld pw = new PhysicsWorld(bodies, world, s);
        int a = bodies.alloc();
        pw.configureUnit(a, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        // 中心 1.5 → AABB y ∈ [0.6, 2.4]，与地板（y ≤ 1）重叠 0.4
        bodies.setPose(a, 0.5F, 1.5F, 0.5F);
        bodies.setVelocity(a, 0.0F, 0.0F, 0.0F);
        for (int i = 0; i < 10; i++) {
            pw.substep(0.05F);
        }
        float minY = bodies.minY(a);
        check(minY >= 1.0F - s.slop - 1.0E-4F,
                "单位不得陷进地板：脚底 minY = " + f(minY) + " 必须 ≥ 1 - slop");
        check(minY <= 1.0F + s.slop + 1.0E-4F,
                "单位不得被弹离地板：脚底 minY = " + f(minY) + " 必须 ≤ 1 + slop");
        System.out.println("      落在方块上：脚底 y = " + f(minY) + "（地板顶面 y = 1.0，slop = " + f(s.slop) + "）"
                + "，体素盒测试 " + pw.voxelContactTests + " 次");
        // 再跑 40 步确认不抖
        float y0 = bodies.posY[a];
        float maxVy = 0.0F;
        for (int i = 0; i < 40; i++) {
            pw.substep(0.05F);
            maxVy = Math.max(maxVy, Math.abs(bodies.velY[a]));
        }
        check(Math.abs(bodies.posY[a] - y0) <= 1.0E-5F,
                "站在地板上不得持续下沉/上浮：40 步位置变化 = " + f(Math.abs(bodies.posY[a] - y0)));
        System.out.println("      站定后 40 步：最大 |vy| = " + maxVy + "，位置变化 = "
                + f(Math.abs(bodies.posY[a] - y0)));
    }

    // ==================================================================
    // 10) 休眠状态机
    // ==================================================================

    private static void sleepStateMachine() {
        System.out.println("\n[10] 休眠状态机：静置 N tick 后入睡、被推动后唤醒");
        RigidbodySoA bodies = new RigidbodySoA(4);
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        PhysicsWorld pw = new PhysicsWorld(bodies, VoxelSource.EMPTY, s);
        int a = bodies.alloc();
        pw.configureUnit(a, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        bodies.setPose(a, 1.0F, 0.9F, 1.0F);
        bodies.setVelocity(a, 0.0F, 0.0F, 0.0F);
        bodies.setGravityScale(a, 0.0F);

        int expected = 0;
        float acc = 0.0F;
        for (int i = 1; i <= 60; i++) {
            acc += 0.05F;
            if (acc >= s.minSleepTime) {
                expected = i;
                break;
            }
        }
        check(expected > 0, "minSleepTime 必须能在合理步数内达到");
        check(expected == (int) StrictMath.ceil(s.minSleepTime / 0.05F),
                "入睡步数必须等于 minSleepTime/dt 的向上取整（" + expected + " vs "
                        + (int) StrictMath.ceil(s.minSleepTime / 0.05F) + "）");
        int sleptAt = -1;
        for (int i = 1; i <= expected + 3; i++) {
            pw.substep(0.05F);
            if (!bodies.awake[a] && sleptAt < 0) {
                sleptAt = i;
            }
        }
        check(sleptAt == expected,
                "静置必须在第 " + expected + " 个子步（= " + s.minSleepTime + "s @20Hz）入睡，实测第 " + sleptAt + " 步");
        check(!bodies.awake[a], "静置单位最终必须处于睡眠状态");
        System.out.println("      minSleepTime = " + f(s.minSleepTime) + "s → 第 " + sleptAt
                + " 个子步入睡（20Hz 下 = " + sleptAt + " tick）");

        // 睡着之后：不积分、位置不动
        long before = pw.integrationCalls;
        float x0 = bodies.posX[a];
        for (int i = 0; i < 50; i++) {
            pw.substep(0.05F);
        }
        check(pw.integrationCalls == before, "睡着的单位不得被积分（实测新增积分次数 "
                + (pw.integrationCalls - before) + "）");
        check(pw.sleepingSkippedIntegration > 0, "必须记录到「因休眠而跳过积分」的次数（实测 "
                + pw.sleepingSkippedIntegration + "）");
        check(bodies.posX[a] == x0, "睡着的单位位置必须逐位不变");
        System.out.println("      入睡后 50 子步：新增积分次数 = " + (pw.integrationCalls - before)
                + "，跳过积分次数 = " + pw.sleepingSkippedIntegration);

        // 被推动 → 唤醒
        bodies.addImpulse(a, 2.0F, 0.0F, 0.0F);
        check(bodies.awake[a], "施加冲量必须立刻唤醒（否则出现「有速度但不动」的幽灵状态）");
        checkEq(bodies.velX[a], 2.0F, "冲量必须逐位写入速度");
        // 停下来并回到原位（模拟 AI 停止移动）→ 必须能重新入睡：休眠不是一次性的
        bodies.setVelocity(a, 0.0F, 0.0F, 0.0F);
        bodies.setPose(a, 1.0F, 0.9F, 1.0F);
        for (int i = 0; i < expected + 2; i++) {
            pw.substep(0.05F);
        }
        check(!bodies.awake[a], "被推动后又静置，必须能重新入睡（休眠不是一次性的）");

        // 接触唤醒：另一个单位以 1 格/秒撞过来
        int b = bodies.alloc();
        pw.configureUnit(b, 0.6F, 1.8F, 0.0F, 1, PhysicsLayers.ALL);
        bodies.setPose(b, 1.0F + 0.59F, 0.9F, 1.0F);
        bodies.setVelocity(b, 1.0F, 0.0F, 0.0F);
        bodies.setGravityScale(b, 0.0F);
        long woke = pw.wakeOnContact;
        pw.substep(0.05F);
        check(bodies.awake[a], "被一个 1 格/秒的醒着单位挤到时必须被唤醒"
                + "（这就是 broadphase 里「睡着的也要入网格」的原因）");
        check(pw.wakeOnContact > woke, "必须记录到接触唤醒（实测新增 " + (pw.wakeOnContact - woke) + "）");
        System.out.println("      接触唤醒：新增唤醒 " + (pw.wakeOnContact - woke) + " 次");

        // 全局开关：关掉休眠后不再入睡
        PhysicsWorld.Settings s2 = PhysicsWorld.Settings.vanilla();
        s2.allowSleep = false;
        PhysicsWorld pw2 = new PhysicsWorld(new RigidbodySoA(2), VoxelSource.EMPTY, s2);
        int c = pw2.bodies().alloc();
        pw2.configureUnit(c, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        pw2.bodies().setGravityScale(c, 0.0F);
        for (int i = 0; i < 40; i++) {
            pw2.substep(0.05F);
        }
        check(pw2.bodies().awake[c], "allowSleep=false 时绝不能入睡");
    }

    // ==================================================================
    // 11) 层掩码 / 队伍掩码 / 触发体
    // ==================================================================

    private static void layerMaskTeamAndTrigger() {
        System.out.println("\n[11] 层掩码 + 队伍掩码 + 触发体（查询过滤）");
        StubWorld world = new StubWorld();
        for (int y = 0; y <= 2; y++) {
            world.add(5, y, 0);   // x=5 的墙（占 3 格高）
        }
        RigidbodySoA bodies = new RigidbodySoA(4);
        PhysicsWorld pw = new PhysicsWorld(bodies, world, PhysicsWorld.Settings.vanilla());
        int unit = bodies.alloc();
        pw.configureUnit(unit, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        bodies.setPose(unit, 3.0F, 0.9F, 0.5F);
        int trigger = bodies.alloc();
        bodies.applyDefaults(trigger);
        bodies.setCapsule(trigger, 0.5F, 1.0F);
        bodies.setLayer(trigger, PhysicsLayers.TRIGGER);
        bodies.isTrigger[trigger] = true;
        bodies.setTeam(trigger, 0, PhysicsLayers.ALL);
        bodies.setPose(trigger, 2.0F, 0.9F, 0.5F);

        PhysicsQuery q = pw.query();
        pw.broadphase().build(bodies);
        RaycastHit hit = new RaycastHit();

        // 地形层：只看到墙（不看单位/触发器）
        boolean okTerrain = q.raycast(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, hit);
        check(okTerrain && hit.isVoxel, "TERRAIN 掩码必须命中方块");
        checkEq(hit.t, 0.5F, "墙在 x=5、射线长 10 → t 逐位等于 0.5");
        checkEq(hit.blockX, 5, "命中的方块 x 必须是 5");

        // 单位层：命中单位（x=3-0.3=2.7 → t=0.27），不是墙
        boolean okUnit = q.raycast(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), QueryTriggerInteraction.IGNORE, hit);
        check(okUnit && hit.body == unit, "UNIT 掩码必须命中单位（而不是墙）");
        checkNear(hit.pointX, 2.7F, 1.0E-5F, "单位命中点 x ≈ 2.7（中心 3.0 − 半径 0.3）");

        // 组合掩码：单位在前 → 命中单位
        boolean okBoth = q.raycast(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.mask(PhysicsLayers.TERRAIN, PhysicsLayers.UNIT),
                QueryTriggerInteraction.IGNORE, hit);
        check(okBoth && hit.body == unit, "TERRAIN|UNIT：更近的单位优先（t 取最小）");

        // 触发器：默认（USE_GLOBAL）命中；显式 IGNORE 不命中
        boolean trigGlobal = q.raycast(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.TRIGGER), QueryTriggerInteraction.USE_GLOBAL, hit);
        check(trigGlobal && hit.isTrigger, "触发器在 queriesHitTriggers=true 时必须被命中");
        boolean trigIgnored = q.raycast(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.TRIGGER), QueryTriggerInteraction.IGNORE, hit);
        check(!trigIgnored, "QueryTriggerInteraction.IGNORE 时必须跳过触发器");

        // 队伍掩码：只打敌人（掩码 = 「除自己队伍之外的所有队伍」）
        int enemiesOf0 = PhysicsQuery.teamMaskExcluding(0);
        boolean friendly = q.raycastTeam(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), 0, enemiesOf0, QueryTriggerInteraction.IGNORE, hit);
        check(!friendly, "队伍 0 的查询用「除 0 之外」掩码时必须打不到同为队伍 0 的单位");
        boolean hostile = q.raycastTeam(0.0F, 0.9F, 0.5F, 1.0F, 0.0F, 0.0F, 10.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), 1, PhysicsQuery.teamMaskExcluding(1),
                QueryTriggerInteraction.IGNORE, hit);
        check(hostile && hit.body == unit, "队伍 1 的查询用「除 1 之外」掩码必须能打到队伍 0 的单位");
        System.out.println("      teamMaskExcluding(0) = 0x" + Integer.toHexString(enemiesOf0)
                + "，单位层 = " + PhysicsLayers.describe(PhysicsLayers.bit(PhysicsLayers.UNIT))
                + "，地形层 = " + PhysicsLayers.describe(PhysicsLayers.bit(PhysicsLayers.TERRAIN)));
    }

    // ==================================================================
    // 12) 重叠查询
    // ==================================================================

    private static void overlapQueries() {
        System.out.println("\n[12] OverlapSphere / OverlapBox");
        RigidbodySoA bodies = new RigidbodySoA(8);
        PhysicsWorld pw = new PhysicsWorld(bodies, VoxelSource.EMPTY, PhysicsWorld.Settings.vanilla());
        for (int i = 0; i < 4; i++) {
            int a = bodies.alloc();
            pw.configureUnit(a, 0.6F, 1.8F, 0.0F, i & 1, PhysicsLayers.ALL);
            bodies.setPose(a, 1.0F + i, 0.9F, 0.0F);   // 中心 x = 1,2,3,4
        }
        pw.broadphase().build(bodies);
        PhysicsQuery q = pw.query();
        int[] out = new int[8];

        int n = q.overlapSphere(1.0F, 0.9F, 0.0F, 1.5F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), QueryTriggerInteraction.IGNORE, out, 8);
        checkEq(n, 2, "球心 (1,0.9,0) 半径 1.5：命中 x=1,2 两个单位（x=3 距离 2.0 > 1.5）");
        checkEq(out[0], 0, "重叠结果必须按 body 索引升序（确定性）");
        checkEq(out[1], 1, "重叠结果的第二项必须是索引 1");

        // 边界语义（闭区间）：半径 0.5 的查询球 + 半径 0.5 的单位、中心距恰好 1.0 → 必须命中
        RigidbodySoA edge = new RigidbodySoA(2);
        PhysicsWorld pwEdge = new PhysicsWorld(edge, VoxelSource.EMPTY, PhysicsWorld.Settings.vanilla());
        int e0 = edge.alloc();
        pwEdge.configureUnit(e0, 1.0F, 2.0F, 0.0F, 0, PhysicsLayers.ALL);
        edge.setPose(e0, 1.0F, 1.0F, 0.0F);
        pwEdge.broadphase().build(edge);
        int nEdge = pwEdge.query().overlapSphere(0.0F, 1.0F, 0.0F, 0.5F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), QueryTriggerInteraction.IGNORE, out, 8);
        checkEq(nEdge, 1, "半径 0.5 + 单位半径 0.5、中心距恰好 1.0 → 必须命中（≤ 而不是 <）");

        // 队伍过滤：只要队伍 1
        int nt = q.overlapSphereTeam(1.0F, 0.9F, 0.0F, 5.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), 1, PhysicsQuery.teamMaskExcluding(0),
                QueryTriggerInteraction.IGNORE, out, 8);
        checkEq(nt, 2, "队伍轴过滤：4 个单位里只有 2 个是队伍 1");

        // 盒重叠
        int nbox = q.overlapBox(0.0F, 0.0F, -1.0F, 2.5F, 2.0F, 1.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), QueryTriggerInteraction.IGNORE, out, 8);
        checkEq(nbox, 2, "盒 [0,2.5] 横跨 x=1,2（x=3 的中心 3.0 在盒外，半径 0.3 也不够）");

        // 输出缓冲不足时的语义：返回值 = 写入数，lastOverlapFound = 实际数
        int small = q.overlapSphere(1.0F, 0.9F, 0.0F, 5.0F,
                PhysicsLayers.bit(PhysicsLayers.UNIT), QueryTriggerInteraction.IGNORE, out, 2);
        checkEq(small, 2, "输出缓冲只有 2 个槽位时，返回值必须是写入数（2）");
        checkEq(q.lastOverlapFound, 4, "lastOverlapFound 必须报告实际重叠数（4），便于发现上限截断");
        System.out.println("      半径 5 的重叠：写入 " + small + " 个，实际 " + q.lastOverlapFound + " 个");
    }

    // ==================================================================
    // 13) 固定步长档位 / 累加器 / alpha
    // ==================================================================

    private static void fixedStepTiers() {
        System.out.println("\n[13] 固定步长：档位只取离散值 + 累加器 + alpha");
        FixedStepClock clock = new FixedStepClock(20.0F, 8, 0.25F);
        checkEq(clock.fixedDt(), 0.05F, "20Hz 的固定步长必须逐位等于 0.05");

        checkEq(clock.planSubSteps(3.0F, 0.5F), 8, "位移 3.0 格 / 上限 0.5 → 8 个子步");
        checkEq(clock.planSubSteps(0.4F, 0.5F), 1, "位移 0.4 格 → 1 个子步");
        checkEq(clock.planSubSteps(0.9F, 0.5F), 2, "位移 0.9 格 → 2 个子步");
        checkEq(clock.planSubSteps(1.9F, 0.5F), 4, "位移 1.9 格 → 4 个子步");

        // 子步 dt 只能取 0.05 / 0.025 / 0.0125 / 0.00625
        float[] allowed = {0.05F, 0.025F, 0.0125F, 0.00625F};
        boolean allAllowed = true;
        for (int sub : new int[]{1, 2, 4, 8}) {
            float dt = 0.05F / sub;
            boolean ok = false;
            for (float a : allowed) {
                if (a == dt) {
                    ok = true;
                }
            }
            allAllowed &= ok;
        }
        check(allAllowed, "子步 dt 必须只可能是 {0.05, 0.025, 0.0125, 0.00625} 之一（离散档位 = 固定步长的本质）");

        // 累加器：0.025 + 0.025 = 0.05 → 正好一步（用二进制精确值，避免 0.02+0.03 的浮点欠账）
        FixedStepClock c2 = new FixedStepClock(20.0F, 8, 0.25F);
        c2.beginFrame(0.025F);
        check(!c2.hasStep(), "0.025s 攒不满一个 0.05s 主步");
        c2.beginFrame(0.025F);
        check(c2.hasStep(), "0.025 + 0.025 = 0.05s 必须攒满一个主步");
        c2.consumeStep(1);
        check(!c2.hasStep(), "消费之后必须没有待跑主步");
        check(c2.alpha() <= 1.0E-6F, "消费之后 alpha 必须回到 0（实测 " + c2.alpha() + "）");

        // 浮点欠账的诚实说明：0.02 + 0.03 在 float 下是 0.049999997 < 0.05，这一步会被推迟。
        // 这正是「服务端权威路径用 stepOneTick() 绕开累加器」的理由（见 FixedStepClock 注释）。
        FixedStepClock c2b = new FixedStepClock(20.0F, 8, 0.25F);
        c2b.beginFrame(0.02F);
        c2b.beginFrame(0.03F);
        System.out.println("      浮点欠账示例：0.02f + 0.03f = " + (0.02F + 0.03F)
                + " （< 0.05 → 累加器路径会推迟一步；权威路径用 stepOneTick() 不存在这个问题）");

        // 死亡螺旋保护：一次 2 秒的卡顿最多补 maxFrameTime/fixedDt = 5 步
        FixedStepClock c3 = new FixedStepClock(20.0F, 8, 0.25F);
        c3.beginFrame(2.0F);
        int steps = 0;
        while (c3.hasStep() && steps < 100) {
            c3.consumeStep(1);
            steps++;
        }
        checkEq(steps, 5, "2 秒卡顿必须被夹到 5 个主步（maxFrameTime 0.25s），防死亡螺旋");

        // alpha 语义与客户端 ClientCrowdData.alpha() 一致：距上一帧经过时间 / 间隔
        FixedStepClock c4 = new FixedStepClock(20.0F, 8, 0.25F);
        c4.beginFrame(0.0125F);
        checkNear(c4.alpha(), 0.25F, 1.0E-6F, "攒了 0.0125s（= 0.05 的 1/4）时 alpha ≈ 0.25");

        // 世界级：80 格/秒的体 → 每 tick 4 格 → 8 子步，且每子步 dt 固定
        RigidbodySoA bodies = new RigidbodySoA(2);
        PhysicsWorld pw = new PhysicsWorld(bodies, VoxelSource.EMPTY, PhysicsWorld.Settings.vanilla());
        int a = bodies.alloc();
        pw.configureUnit(a, 0.6F, 1.8F, 0.0F, 0, PhysicsLayers.ALL);
        bodies.setGravityScale(a, 0.0F);
        bodies.setVelocity(a, 80.0F, 0.0F, 0.0F);
        pw.stepOneTick();
        checkEq(pw.lastSubSteps, 8, "80 格/秒 → 每 tick 4 格 → 8 个子步");
        checkEq(pw.lastSubDt, 0.00625F, "8 子步时每子步 dt 必须逐位等于 0.00625");
        System.out.println("      80 格/秒：子步数 = " + pw.lastSubSteps + "，子步 dt = " + pw.lastSubDt);
    }

    // ==================================================================
    // 14) 与原版每 tick 重力/阻力的等价换算
    // ==================================================================

    private static void vanillaTickParity() {
        System.out.println("\n[14] 与原版逐 tick 语义的等价换算（重力 / 阻力）");
        PhysicsWorld.Settings s = PhysicsWorld.Settings.vanilla();
        // 这里刻意用「与原实现同形的表达式」做逐位断言，再用容差断言说明它等价于 -32：
        // 0.08f 本身不是精确值，0.08f×400 = 31.999998（1 ulp 级差异），所以「逐位等于 -32」是错的期望。
        checkEq(s.gravityY, -0.08F * 20.0F * 20.0F, "重力必须逐位等于原版 0.08×rate² 的同一表达式");
        checkNear(s.gravityY, -32.0F, 1.0E-5F, "重力数值上必须等于 -32 格/秒²（原版 0.08 格/tick² 的换算）");
        float expectDrag = 20.0F * (1.0F / 0.98F - 1.0F);
        checkEq(s.unitDrag, expectDrag, "原版每 tick ×0.98 的等价 PhysX drag 必须逐位等于 20×(1/0.98−1)");

        // 单个主步：原版 v1 = (v0 - 0.08) * 0.98（格/tick）
        // 物理层 v1 = (v0*20 + (-32)*0.05) * 1/(1+drag*0.05)（格/秒）
        float maxAbsErr = 0.0F;
        float maxUlp = 0.0F;
        float worstV0 = 0.0F;
        for (int k = -40; k <= 40; k++) {
            float v0Tick = k * 0.05F;                 // 覆盖 -2 ~ +2 格/tick
            float v0PerSec = v0Tick * 20.0F;
            float vanilla = (v0Tick - 0.08F) * 0.98F;
            float phys = (v0PerSec + s.gravityY * 0.05F) * (1.0F / (1.0F + s.unitDrag * 0.05F));
            float physTick = phys / 20.0F;
            float err = Math.abs(physTick - vanilla);
            if (err > maxAbsErr) {
                maxAbsErr = err;
                worstV0 = v0Tick;
            }
            int ulp = Math.abs(Float.floatToIntBits(physTick) - Float.floatToIntBits(vanilla));
            if (ulp > maxUlp) {
                maxUlp = ulp;
            }
        }
        check(maxAbsErr < 1.0E-6F,
                "一个主步内与原版逐 tick 语义的差必须 < 1e-6 格/tick（实测最大 " + maxAbsErr
                        + " @ v0=" + worstV0 + "）");
        System.out.println("      逐 tick 对照（81 个速度采样点）：最大绝对误差 = " + maxAbsErr
                + " 格/tick，最大 ULP 差 = " + maxUlp);

        // 位置：物理层 p1 = p0 + v1*dt，原版 p1 = p0 + v1_tick → v1*0.05 = v1_tick（20 倍关系）
        float vTick = 0.25F;
        float vPerSec = vTick * 20.0F;
        checkEq(vPerSec * 0.05F, vTick, "格/秒 × dt 必须逐位还原格/tick（20× 与 0.05 互为倒数）");

        // 阻尼单独对照：v *= 1/(1+drag*dt) 与 v *= 0.98
        float maxDragErr = 0.0F;
        for (int k = -100; k <= 100; k++) {
            float v = k * 0.037F;
            float a = v * (1.0F / (1.0F + s.unitDrag * 0.05F));
            float b = v * 0.98F;
            maxDragErr = Math.max(maxDragErr, Math.abs(a - b));
        }
        check(maxDragErr < 1.0E-6F, "PhysX 线性阻尼与 ×0.98 的每步差值必须 < 1e-6（实测 " + maxDragErr + "）");
        System.out.println("      阻尼对照：最大差值 = " + maxDragErr);
    }

    // ==================================================================
    // 15) 10k 单位 broadphase 的调用次数上界与耗时
    // ==================================================================

    private static void broadphaseCostBound() {
        System.out.println("\n[15] 10k 单位 broadphase：每步调用次数上界与耗时量级");
        int n = 10000;
        RigidbodySoA bodies = new RigidbodySoA(n);
        for (int i = 0; i < n; i++) {
            int a = bodies.alloc();
            bodies.applyDefaults(a);
            bodies.setCapsule(a, 0.3F, 1.8F);
            bodies.setMass(a, 1.0F);
            // 100 × 100 的密集阵（间距 1.0 格）—— 最坏情况：人人都有邻居
            bodies.setPose(a, (i % 100) * 1.0F, 0.9F, (i / 100) * 1.0F);
        }
        BroadphaseGrid grid = new BroadphaseGrid(2.0F);
        long pairs = 0;
        int[] counter = new int[1];
        BroadphaseGrid.PairConsumer count = (i, j) -> counter[0]++;
        // 预热
        for (int w = 0; w < 3; w++) {
            grid.build(bodies);
            counter[0] = 0;
            grid.forEachPair(bodies, count);
        }
        int reps = 20;
        long t0 = System.nanoTime();
        long cellQueries = 0;
        long pairTests = 0;
        for (int r = 0; r < reps; r++) {
            grid.build(bodies);
            counter[0] = 0;
            grid.forEachPair(bodies, count);
            pairs += counter[0];
            cellQueries += grid.cellQueries();
            pairTests += grid.pairTests();
        }
        long t1 = System.nanoTime();
        double usPerStep = (t1 - t0) / 1000.0 / reps;
        double cellsPerBody = (double) cellQueries / reps / n;
        double pairsPerBody = (double) pairTests / reps / n;
        System.out.println("      密集阵（100×100，间距 1.0）：格子 " + grid.cellCount()
                + " 个，每步格子查询 " + f((float) cellsPerBody) + " 次/单位，"
                + "窄相位测试 " + f((float) pairsPerBody) + " 对/单位，"
                + "耗时 " + f((float) usPerStep) + " µs/步（含建表）");
        check(cellsPerBody <= 27.0, "每单位的格子查询次数必须 ≤ 27（身体最多跨 3×3×3 个格子），实测 "
                + f((float) cellsPerBody));
        check(pairsPerBody <= 32.0, "密集阵下每单位的窄相位测试次数必须 ≤ 32（3×3×3 格子里最多 27 个邻居，"
                + "且双方都睡着会跳过），实测 " + f((float) pairsPerBody));
        check(usPerStep < 20000.0, "10k 单位的 broadphase 每步必须 < 20ms，实测 " + f((float) usPerStep) + " µs");

        // 稀疏铺开：格子数必须被 maxCells 兜住（自动放大格子尺寸），而不是爆内存
        RigidbodySoA sparse = new RigidbodySoA(n);
        for (int i = 0; i < n; i++) {
            int a = sparse.alloc();
            sparse.applyDefaults(a);
            sparse.setCapsule(a, 0.3F, 1.8F);
            sparse.setMass(a, 1.0F);
            sparse.setPose(a, (i % 100) * 40.0F, 0.9F, (i / 100) * 40.0F);
        }
        BroadphaseGrid g2 = new BroadphaseGrid(2.0F);
        g2.build(sparse);
        System.out.println("      稀疏铺开（4000×4000 格）：格子尺寸被放大到 " + g2.cellSize()
                + "，格子数 " + g2.cellCount() + "（上限 " + BroadphaseGrid.DEFAULT_MAX_CELLS + "）");
        check(g2.cellCount() <= BroadphaseGrid.DEFAULT_MAX_CELLS,
                "稀疏场景的格子数必须被上限兜住，实测 " + g2.cellCount());
        check(g2.cellSize() > 2.0F, "稀疏场景必须自动放大格子尺寸（实测 " + g2.cellSize() + "）");

        // 配对顺序的确定性：两次建表 + 遍历必须完全一致
        StringBuilder sig1 = new StringBuilder();
        StringBuilder sig2 = new StringBuilder();
        grid.build(bodies);
        grid.forEachPair(bodies, (i, j) -> sig1.append(i).append(':').append(j).append(';'));
        grid.build(bodies);
        grid.forEachPair(bodies, (i, j) -> sig2.append(i).append(':').append(j).append(';'));
        check(sig1.toString().equals(sig2.toString()), "两次建表的配对序列（含顺序）必须逐字相同");
        check(sig1.length() > 0, "配对序列不能为空（否则上面的断言没有意义）");
        check(pairs > 0, "累计配对数必须为正");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 「哪些方块是实心的」替身世界：harness 用它注入地形，不碰任何注册表。 */
    private static final class StubWorld implements VoxelSource {
        private final Set<Long> solid = new HashSet<>();

        void add(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        }

        @Override
        public String toString() {
            return "StubWorld(" + solid.size() + " blocks)";
        }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    private static void checkEq(float actual, float expected, String message) {
        checks++;
        if (actual == expected) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message + "（实际 " + actual + "，期望 " + expected + "）");
        }
    }

    private static void checkEq(int actual, int expected, String message) {
        checks++;
        if (actual == expected) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message + "（实际 " + actual + "，期望 " + expected + "）");
        }
    }

    private static void checkNear(float actual, float expected, float tol, String message) {
        checks++;
        if (Math.abs(actual - expected) <= tol) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message + "（实际 " + actual + "，期望 " + expected
                    + " ± " + tol + "）");
        }
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
