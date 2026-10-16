package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.crowd.CrowdData;
import dev.swordkingdom.horde.crowd.CrowdSeparation;
import dev.swordkingdom.horde.crowd.SpatialGrid;

/**
 * 验证**群集单位的碰撞分离**：一堆单位挤在同一个点上，经过若干次分离后必须互相不穿透。
 *
 * 之前只有「速度冲量」，没有位置层面的去穿透，所以 10000 只被 AI 往同一处推时会叠在一起。
 * 这里直接用生产的 {@link CrowdSeparation}：
 *  - 100 只重合在一起的单位，跑 30 次分离后，最近一对的中心距应接近「半宽之和」；
 *  - 分离不能把单位推到无穷远（位移有上限、且是相互的）；
 *  - 体型差距要体现出来：大质量单位被推开的距离应明显小于小质量单位；
 *  - strength = 0 时必须完全不分离（保留「关闭」这个开关）。
 */
public final class CollisionHarness {

    private static final float HALF = 0.3F;   // 僵尸宽度 0.6

    private static int failures;

    public static void main(String[] args) {
        separatedFromOnePoint();
        massMatters();
        disabledSwitch();
        System.out.println(failures == 0 ? "\n== COLLISION PASS ==" : "\n== COLLISION FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void separatedFromOnePoint() {
        int n = 100;
        CrowdData d = new CrowdData(n);
        for (int i = 0; i < n; i++) {
            int s = d.alloc();
            d.aliveFlag[s] = true;
            d.alive++;
            d.hp[s] = 20.0F;
            d.maxHp[s] = 20.0F;
            d.posX[s] = 0.0F;
            d.posY[s] = 64.0F;
            d.posZ[s] = 0.0F;
        }
        SpatialGrid grid = new SpatialGrid(2.0F);
        float[] hw = new float[n];
        float[] mass = new float[n];
        for (int i = 0; i < n; i++) {
            hw[i] = HALF;
            mass[i] = 1.0F;
        }

        float minBefore = minCenterDistance(d, n, HALF);
        check(minBefore < 1.0E-4F, "初始状态应完全重合（最近中心距 " + minBefore + "）");

        for (int tick = 0; tick < 30; tick++) {
            grid.build(d);
            CrowdSeparation.resolve(d, grid, hw, mass, 1.0F, 2);
        }

        float minAfter = minCenterDistance(d, n, HALF);
        float want = HALF * 2.0F;   // 完全不重叠 = 中心距 >= 半宽之和
        System.out.printf("[ ok ] 100 只从同一点出发：30 次分离后最近中心距 %.3f（完全不重叠需要 %.3f）%n",
                minAfter, want);
        check(minAfter > want * 0.7F, "分离后最近中心距应接近半宽之和（实际 " + minAfter + "，目标 > " + want * 0.7F + "）");
        check(minAfter > want * 0.7F, "分离后最近中心距应接近半宽之和（实际 " + minAfter + "，目标 > " + want * 0.7F + "）");
        check(minAfter < want * 4.0F, "分离不能过度（实际 " + minAfter + "）");
        // 不能把整堆推到天边
        float spread = 0.0F;
        for (int i = 0; i < n; i++) {
            spread = Math.max(spread, (float) Math.hypot(d.posX[i], d.posZ[i]));
        }
        check(spread < 40.0F, "整体扩散半径应合理（实际 " + spread + "）");
    }

    private static void massMatters() {
        CrowdData d = new CrowdData(2);
        int a = d.alloc();
        int b = d.alloc();
        d.aliveFlag[a] = true;
        d.aliveFlag[b] = true;
        d.hp[a] = 20.0F;
        d.hp[b] = 20.0F;
        d.posY[a] = 64.0F;
        d.posY[b] = 64.0F;
        d.posX[a] = 0.0F;
        d.posZ[a] = 0.0F;
        // 轻微错开，避免完全重合
        d.posX[b] = 0.1F;
        d.posZ[b] = 0.0F;

        float[] hw = {HALF, HALF};
        float[] mass = {20.0F, 1.0F};   // a 是「大 boss」
        SpatialGrid grid = new SpatialGrid(2.0F);
        grid.build(d);
        float startA = d.posX[a];
        float startB = d.posX[b];
        CrowdSeparation.resolve(d, grid, hw, mass, 1.0F, 1);
        float movedA = Math.abs(d.posX[a] - startA);
        float movedB = Math.abs(d.posX[b] - startB);
        System.out.printf("[ ok ] 质量差异：重的一方移动 %.4f，轻的一方移动 %.4f%n", movedA, movedB);
        check(movedB > movedA * 3.0F, "轻的一方应被推得明显更多（重 " + movedA + " / 轻 " + movedB + "）");
    }

    private static void disabledSwitch() {
        CrowdData d = new CrowdData(2);
        int a = d.alloc();
        int b = d.alloc();
        d.aliveFlag[a] = true;
        d.aliveFlag[b] = true;
        d.hp[a] = 20.0F;
        d.hp[b] = 20.0F;
        d.posY[a] = 64.0F;
        d.posY[b] = 64.0F;
        float[] hw = {HALF, HALF};
        float[] mass = {1.0F, 1.0F};
        SpatialGrid grid = new SpatialGrid(2.0F);
        grid.build(d);
        CrowdSeparation.resolve(d, grid, hw, mass, 0.0F, 4);
        check(d.posX[a] == 0.0F && d.posX[b] == 0.0F && d.posZ[a] == 0.0F && d.posZ[b] == 0.0F,
                "strength = 0 时必须完全不分离");
        System.out.println("[ ok ] strength = 0 时关闭分离（单位可以重叠）");
    }

    private static float minCenterDistance(CrowdData d, int n, float half) {
        float min = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                float dx = (d.posX[j] + half) - (d.posX[i] + half);
                float dz = (d.posZ[j] + half) - (d.posZ[i] + half);
                min = Math.min(min, (float) Math.hypot(dx, dz));
            }
        }
        return min;
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
