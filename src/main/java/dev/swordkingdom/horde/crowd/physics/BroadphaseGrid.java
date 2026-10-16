package dev.swordkingdom.horde.crowd.physics;

/**
 * 物理层的 broadphase：均匀网格 + CSR（压缩行）索引，**每个子步重建**。
 *
 * <h2>为什么自带一份，而不直接用 {@code SpatialGrid}</h2>
 * {@code SpatialGrid} 是「格子 → 单位」的二维（X/Z）索引，服务对象是 AI 索敌/流场/分离，
 * 它的格子里装的是「单位中心所属的那一格」。物理层需要的是三件它做不到的事：
 * <ol>
 *   <li><b>三维</b>：站在 3 格高墙上的单位和地面单位不该被配成一对；</li>
 *   <li><b>AABB 覆盖格子</b>：半径 0.4 的单位可能横跨两格，只按中心入格会漏掉跨格邻居；</li>
 *   <li><b>稳定的配对顺序</b>：物理层的求解顺序必须**逐位可复现**，
 *       所以配对必须按 (i, j) 升序确定性地产生，而不能依赖遍历顺序。</li>
 * </ol>
 * 反过来，物理层**不修改** {@code SpatialGrid}，两者各自演进而互不影响；
 * 迁移阶段（见 docs/PHYSICS.md 阶段 3）才有必要谈「合并成一份」。
 *
 * <h2>确定性</h2>
 * <ul>
 *   <li>格子尺寸只由「本步所有 body 的包围盒」决定（升序归约 → 同一个输入同一个格尺寸）；</li>
 *   <li>计数排序（counting sort）本身稳定，且填充时按 body 索引升序，
 *       所以同一格子内的成员顺序 = body 索引升序；</li>
 *   <li>配对去重用一个「访问戳」数组：戳 = i+1，于是每个 (i, j) 对**恰好被发射一次**，
 *       顺序 = (i 升序, 该 i 的格子顺序, 格子内成员升序)。</li>
 * </ul>
 * 没有哈希表、没有哈希迭代顺序 —— 这是「同一输入序列两次运行结果逐位一致」的前提之一。
 *
 * <h2>成本</h2>
 * 每步 O(n) 建表 + O(每 body 覆盖的格子数 × 格内成员数) 配对。
 * 格子尺寸有上限保护（{@link #maxCells}），单位铺得再开也不会退化成「几百万个空格子」。
 */
public final class BroadphaseGrid {

    /** 每个 body 最多覆盖多少个格子（超出则夹取；只对「巨大体型」生效）。 */
    public static final int MAX_CELLS_PER_BODY = 27;
    /** 格子总数上限：超过就按 2 的幂放大格子尺寸（确定性的，与输入顺序无关）。 */
    public static final int DEFAULT_MAX_CELLS = 1 << 20;

    private final float baseCellSize;
    private final int maxCells;

    private float cellSize;
    private float minX;
    private float minY;
    private float minZ;
    private int gw;
    private int gy;
    private int gh;
    private int cells;

    // CSR：body -> 它覆盖的格子
    private int[] bodyCellStart = new int[1];
    private int[] bodyCells = new int[1];
    private int cellRefCount;

    // CSR：格 -> 成员 body
    private int[] counts = new int[1];
    private int[] cellStart = new int[1];
    private int[] items = new int[1];

    private int[] stamp = new int[1];

    private int itemCount;

    // ---- 插桩（成本模型用） ----
    private long cellQueries;
    private long pairTests;
    private long skippedBothAsleep;
    private long skippedTrigger;

    public BroadphaseGrid(float baseCellSize) {
        this(baseCellSize, DEFAULT_MAX_CELLS);
    }

    public BroadphaseGrid(float baseCellSize, int maxCells) {
        this.baseCellSize = Math.max(0.25F, baseCellSize);
        this.maxCells = Math.max(1, maxCells);
        this.cellSize = this.baseCellSize;
    }

    public float cellSize() {
        return cellSize;
    }

    public int gridWidth() {
        return gw;
    }

    public int gridDepth() {
        return gy;
    }

    public int gridHeight() {
        return gh;
    }

    /** 参与本步的 body 数。 */
    public int itemCount() {
        return itemCount;
    }

    /** 本步查了多少次格子（每次 = 一次成员区间遍历）。 */
    public long cellQueries() {
        return cellQueries;
    }

    /** 窄相位实际测过的 body 对数（配对去重之后）。 */
    public long pairTests() {
        return pairTests;
    }

    /** 因为「双方都睡着」而直接跳过的候选对数（不产生窄相位测试）。 */
    public long skippedBothAsleep() {
        return skippedBothAsleep;
    }

    public void resetCounters() {
        cellQueries = 0L;
        pairTests = 0L;
        skippedBothAsleep = 0L;
        skippedTrigger = 0L;
    }

    /** 因为「至少有一方是触发体」而跳过的候选对数（触发器不产生接触）。 */
    public long skippedTrigger() {
        return skippedTrigger;
    }

    public int cellCount() {
        return cells;
    }

    // ------------------------------------------------------------------
    // 建表
    // ------------------------------------------------------------------

    /**
     * 按当前 body 状态重建索引。
     *
     * <p><b>触发体也进网格</b>，因为 Unity 的触发器「不产生接触、但参与查询」：
     * 射程球、爆炸半径这类体积必须能被 OverlapSphere 查到。
     * 「不产生接触」由 {@link #forEachPair} 跳过触发体对来保证，而不是靠「不入网格」。
     * 休眠的对象同样入网格 —— 否则一个移动中的单位撞上睡着的单位时，
     * 后者根本不在候选集里，就谈不上「被撞醒」。
     */
    public void build(RigidbodySoA bodies) {
        float bMinX = Float.MAX_VALUE;
        float bMinY = Float.MAX_VALUE;
        float bMinZ = Float.MAX_VALUE;
        float bMaxX = -Float.MAX_VALUE;
        float bMaxY = -Float.MAX_VALUE;
        float bMaxZ = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i]) {
                continue;
            }
            n++;
            float x = bodies.posX[i];
            float y = bodies.posY[i];
            float z = bodies.posZ[i];
            float r = bodies.radius[i];
            float h = bodies.height[i];
            if (x - r < bMinX) bMinX = x - r;
            if (x + r > bMaxX) bMaxX = x + r;
            if (y < bMinY) bMinY = y;
            if (y + h > bMaxY) bMaxY = y + h;
            if (z - r < bMinZ) bMinZ = z - r;
            if (z + r > bMaxZ) bMaxZ = z + r;
        }
        itemCount = n;
        if (n == 0) {
            gw = gy = gh = cells = 0;
            cellRefCount = 0;
            return;
        }

        // 格尺寸：只由包围盒决定 + 按 2 的幂放大直到格子数落在上限内
        cellSize = baseCellSize;
        while (true) {
            gw = spanCount(bMinX, bMaxX, cellSize);
            gy = spanCount(bMinY, bMaxY, cellSize);
            gh = spanCount(bMinZ, bMaxZ, cellSize);
            long total = (long) gw * gy * gh;
            if (total <= maxCells || cellSize > 4096.0F) {
                break;
            }
            cellSize *= 2.0F;
        }
        minX = bMinX - cellSize;
        minY = bMinY - cellSize;
        minZ = bMinZ - cellSize;
        cells = gw * gy * gh;

        ensureCells(cells + 1);
        ensureBodies(bodies.capacity);

        // ---- 1) 每个 body 覆盖多少格子（按 body 升序累加 → 确定性） ----
        int total = 0;
        for (int i = 0; i < bodies.highWater; i++) {
            bodyCellStart[i] = total;
            if (bodies.alive[i]) {
                total += countBodyCells(bodies, i);
            }
        }
        bodyCellStart[bodies.highWater] = total;
        cellRefCount = total;
        if (bodyCells.length < total) {
            bodyCells = new int[Math.max(16, total)];
        }
        // ---- 2) 填格子索引 ----
        for (int i = 0; i < bodies.highWater; i++) {
            if (bodies.alive[i]) {
                fillBodyCells(bodies, i, bodyCellStart[i]);
            }
        }

        // ---- 3) 格 -> 成员（计数排序；填充按 body 升序 → 格内按 body 升序） ----
        java.util.Arrays.fill(counts, 0, cells, 0);
        for (int k = 0; k < cellRefCount; k++) {
            counts[bodyCells[k]]++;
        }
        int sum = 0;
        for (int c = 0; c < cells; c++) {
            cellStart[c] = sum;
            sum += counts[c];
        }
        cellStart[cells] = sum;
        if (items.length < sum) {
            items = new int[Math.max(16, sum)];
        }
        System.arraycopy(cellStart, 0, counts, 0, cells);
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i]) {
                continue;
            }
            for (int k = bodyCellStart[i]; k < bodyCellStart[i + 1]; k++) {
                items[counts[bodyCells[k]]++] = i;
            }
        }
        java.util.Arrays.fill(stamp, 0, bodies.highWater, 0);
        resetCounters();
    }

    private static int spanCount(float min, float max, float cellSize) {
        int c = (int) StrictMath.ceil((max - min) / cellSize) + 2;
        return Math.max(1, c);
    }

    private int countBodyCells(RigidbodySoA bodies, int i) {
        int nx = cellX(bodies.posX[i] + bodies.radius[i]) - cellX(bodies.posX[i] - bodies.radius[i]) + 1;
        int ny = cellY(bodies.posY[i] + bodies.height[i]) - cellY(bodies.posY[i]) + 1;
        int nz = cellZ(bodies.posZ[i] + bodies.radius[i]) - cellZ(bodies.posZ[i] - bodies.radius[i]) + 1;
        long prod = (long) nx * ny * nz;
        return (int) Math.min(prod, MAX_CELLS_PER_BODY);
    }

    /** 把一个 body 覆盖的格子写进 bodyCells（顺序 y→z→x，固定）。 */
    private void fillBodyCells(RigidbodySoA bodies, int i, int at) {
        int x0 = cellX(bodies.posX[i] - bodies.radius[i]);
        int x1 = cellX(bodies.posX[i] + bodies.radius[i]);
        int y0 = cellY(bodies.posY[i]);
        int y1 = cellY(bodies.posY[i] + bodies.height[i]);
        int z0 = cellZ(bodies.posZ[i] - bodies.radius[i]);
        int z1 = cellZ(bodies.posZ[i] + bodies.radius[i]);
        int wrote = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (wrote >= MAX_CELLS_PER_BODY) {
                        return;
                    }
                    bodyCells[at + wrote] = (y * gh + z) * gw + x;
                    wrote++;
                }
            }
        }
    }

    private int cellX(float v) {
        int c = (int) StrictMath.floor((v - minX) / cellSize);
        if (c < 0) return 0;
        return c >= gw ? gw - 1 : c;
    }

    private int cellY(float v) {
        int c = (int) StrictMath.floor((v - minY) / cellSize);
        if (c < 0) return 0;
        return c >= gy ? gy - 1 : c;
    }

    private int cellZ(float v) {
        int c = (int) StrictMath.floor((v - minZ) / cellSize);
        if (c < 0) return 0;
        return c >= gh ? gh - 1 : c;
    }

    private void ensureCells(int needed) {
        if (cellStart.length < needed) {
            cellStart = new int[needed];
        }
        if (counts.length < needed) {
            counts = new int[needed];
        }
    }

    private void ensureBodies(int bodyCapacity) {
        if (bodyCellStart.length < bodyCapacity + 1) {
            bodyCellStart = new int[bodyCapacity + 1];
        }
        if (stamp.length < bodyCapacity) {
            stamp = new int[bodyCapacity];
        }
    }

    // ------------------------------------------------------------------
    // 配对
    // ------------------------------------------------------------------

    /** 窄相位回调：i &lt; j，且保证每个无序对只回调一次。 */
    public interface PairConsumer {
        void accept(int i, int j);
    }

    /**
     * 发射所有候选对（去重、确定性顺序）。
     *
     * <p>三条规则，各自都有理由：
     * <ol>
     *   <li><b>只发 j &gt; i</b>：一对只测一次；</li>
     *   <li><b>访问戳 = i+1</b>：一个 body 可能同时落在 i 覆盖的多个格子里（半径跨格、身高跨 Y 带），
     *       不加戳就会对同一对做多次窄相位测试，轻则浪费，重则**同一个接触被求解两次**
     *       （等于把分离量加倍，看起来像抖动）；</li>
     *   <li><b>双方都睡着则跳过</b>：两个都不动的对象之间不会发生任何事。
     *       但**只要有一方醒着就必须测** —— 否则「醒着的单位撞上睡着的单位」永远不会产生接触，
     *       也就永远不会把它唤醒（Unity 的唤醒传播正是靠接触）。</li>
     * </ol>
     */
    public void forEachPair(RigidbodySoA bodies, PairConsumer out) {
        if (cells == 0) {
            return;
        }
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i]) {
                continue;
            }
            int mark = i + 1;
            for (int k = bodyCellStart[i]; k < bodyCellStart[i + 1]; k++) {
                int c = bodyCells[k];
                cellQueries++;
                int s = cellStart[c];
                int e = cellStart[c + 1];
                for (int m = s; m < e; m++) {
                    int j = items[m];
                    if (j <= i || stamp[j] == mark) {
                        continue;
                    }
                    stamp[j] = mark;
                    if (bodies.isTrigger[i] || bodies.isTrigger[j]) {
                        // 触发器只参与查询、**永远不产生接触**（Unity 的 Collider.isTrigger 语义）。
                        // 它们之所以在网格里，正是为了能被 OverlapSphere 查到。
                        skippedTrigger++;
                        continue;
                    }
                    if (!bodies.awake[i] && !bodies.awake[j]) {
                        skippedBothAsleep++;
                        continue;
                    }
                    pairTests++;
                    out.accept(i, j);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 查询用的空间遍历（OverlapSphere / 扫掠的候选集）
    // ------------------------------------------------------------------

    /** 查询遍历回调。 */
    public interface BodyVisitor {
        void visit(int body);
    }

    /**
     * 遍历与给定 AABB 重叠的**候选** body（去重、升序）。
     *
     * <p>用「访问戳」去重：一个 body 可能落在多个格子里，不去重会让同一个 body 被算两次
     * （范围伤害会打两次，这是最典型的表现）。
     *
     * <p>注意这只是一个**候选**集：格子是按 body 的 AABB 播进去的，所以可能多报
     * （例如 AABB 只擦到格子角落）。真正的判据由调用方（{@link PhysicsQuery}）做精确求交 ——
     * broadphase 的职责就是「宁可多给，不可漏给」。
     */
    public void forEachInAabb(RigidbodySoA bodies,
                              float minX, float minY, float minZ,
                              float maxX, float maxY, float maxZ,
                              int[] visited, int stamp, BodyVisitor out) {
        if (cells == 0) {
            return;
        }
        int x0 = cellX(minX);
        int x1 = cellX(maxX);
        int y0 = cellY(minY);
        int y1 = cellY(maxY);
        int z0 = cellZ(minZ);
        int z1 = cellZ(maxZ);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int c = (y * gh + z) * gw + x;
                    cellQueries++;
                    int s = cellStart[c];
                    int e = cellStart[c + 1];
                    for (int m = s; m < e; m++) {
                        int j = items[m];
                        if (visited[j] == stamp) {
                            continue;
                        }
                        visited[j] = stamp;
                        out.visit(j);
                    }
                }
            }
        }
    }

    /** 查询遍历需要的外部戳数组尺寸。 */
    public int stampCapacity() {
        return stamp.length;
    }

    private long walkCells;
    private long walkTruncated;

    public long lastWalkCells() {
        return walkCells;
    }

    public long lastWalkTruncated() {
        return walkTruncated;
    }

    /**
     * 沿线段遍历候选格子（3D DDA），为扫掠类查询提供候选集。
     *
     * <h2>为什么要 DDA，而不是「把线段的 AABB 全遍历一遍」</h2>
     * 一条 48 格的斜向射线，其 AABB 是 48×48×48 —— 遍历那个盒子是几万个格子。
     * DDA 只走「线段真正穿过的格子」，斜线也就是 48×√3 ≈ 83 个格子，两者差两个数量级。
     *
     * <h2>pad（走廊外扩）</h2>
     * 起点/终点两侧各外扩 {@code pad} 格，做法是把每个被访问格子的邻域也纳入。
     * 这一项不是可选的：CCD 阶段的 broadphase 是按**积分前**的位置建的，
     * 每个 body 最多已经移动了 {@code maxDisplacementPerSubStep}，
     * 所以查询走廊必须带上这个余量，否则「高速投掷物和同样高速的单位对撞」会漏判。
     */
    public void forEachAlongSegment(RigidbodySoA bodies,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float pad, int[] visited, int stamp, int maxCells,
                                    BodyVisitor out) {
        walkCells = 0L;
        walkTruncated = 0L;
        if (cells == 0) {
            return;
        }
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        // 零位移（起终点重合，例如「起点就已经在地形里」）：只查起点所在格子。
        // 不加这个短路的话，DDA 的三个 tMax 都是 INF、步进永不前进，只能靠格子上限兜底 ——
        // 结果一样，但白烧 4096 次循环。
        boolean singleCell = dx == 0.0F && dy == 0.0F && dz == 0.0F;

        // 连续格坐标（不夹取，越界时在取用前夹取 —— 这样步进逻辑保持简单）
        float fgx = (x0 - minX) / cellSize;
        float fgy = (y0 - minY) / cellSize;
        float fgz = (z0 - minZ) / cellSize;
        int ix = (int) StrictMath.floor(fgx);
        int iy = (int) StrictMath.floor(fgy);
        int iz = (int) StrictMath.floor(fgz);

        int stepX = dx > 0.0F ? 1 : (dx < 0.0F ? -1 : 0);
        int stepY = dy > 0.0F ? 1 : (dy < 0.0F ? -1 : 0);
        int stepZ = dz > 0.0F ? 1 : (dz < 0.0F ? -1 : 0);
        float tDeltaX = stepX != 0 ? Math.abs(cellSize / dx) : Float.POSITIVE_INFINITY;
        float tDeltaY = stepY != 0 ? Math.abs(cellSize / dy) : Float.POSITIVE_INFINITY;
        float tDeltaZ = stepZ != 0 ? Math.abs(cellSize / dz) : Float.POSITIVE_INFINITY;
        float tMaxX = stepX > 0 ? ((ix + 1) - fgx) * tDeltaX : (stepX < 0 ? (fgx - ix) * tDeltaX : Float.POSITIVE_INFINITY);
        float tMaxY = stepY > 0 ? ((iy + 1) - fgy) * tDeltaY : (stepY < 0 ? (fgy - iy) * tDeltaY : Float.POSITIVE_INFINITY);
        float tMaxZ = stepZ > 0 ? ((iz + 1) - fgz) * tDeltaZ : (stepZ < 0 ? (fgz - iz) * tDeltaZ : Float.POSITIVE_INFINITY);

        int r = pad > 0.0F ? Math.max(1, (int) StrictMath.ceil(pad / cellSize)) : 0;
        float t = 0.0F;
        while (t <= 1.0F) {
            if (walkCells >= maxCells) {
                walkTruncated++;
                return;
            }
            walkCells++;
            for (int oy = -r; oy <= r; oy++) {
                int cy = clamp(iy + oy, gy);
                for (int oz = -r; oz <= r; oz++) {
                    int cz = clamp(iz + oz, gh);
                    for (int ox = -r; ox <= r; ox++) {
                        int cx = clamp(ix + ox, gw);
                        int c = (cy * gh + cz) * gw + cx;
                        cellQueries++;
                        int s = cellStart[c];
                        int e = cellStart[c + 1];
                        for (int m = s; m < e; m++) {
                            int j = items[m];
                            if (visited[j] == stamp) {
                                continue;
                            }
                            visited[j] = stamp;
                            out.visit(j);
                        }
                    }
                }
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                ix += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                iy += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                iz += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
            if (singleCell) {
                return;
            }
        }
    }

    private static int clamp(int v, int n) {
        if (v < 0) {
            return 0;
        }
        return v >= n ? n - 1 : v;
    }
}
