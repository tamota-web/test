package dev.swordkingdom.horde.crowd;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.List;

/**
 * 原版方块碰撞世界。
 *
 * 严格复刻 {@code Entity.collide} / {@code Entity.collideBoundingBox} /
 * {@code Entity.collideWithShapes} 的逐轴解算顺序与台阶逻辑：
 *   1. 先解 Y 轴（下落/上升）
 *   2. 再按 |dx| 与 |dz| 的大小决定 X/Z 的先后
 *   3. 若在地面且被水平挡住，尝试抬升 maxUpStep 再走（原版 step-up）
 *
 * 为了支撑上万单位，方块碰撞盒按「每 tick 清空」的哈希缓存复用：
 * 同一 tick 内大量单位挤在相同的方块上，命中率极高，
 * 因此真正的 getBlockState 调用次数被摊薄到接近于「被踩到的不同方块数」。
 */
public final class BlockWorld {

    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final float EPS = 1.0E-7F;
    /** 原版 LivingEntity 默认最大台阶高度 */
    public static final float STEP_HEIGHT = 0.6F;
    /** 视线采样的步长（格）：越小越精确、越贵 */
    public static final float LOS_STEP = 0.5F;

    /**
     * 方块世界的最小接口。
     *
     * <p>抽出它的唯一理由是**可离线验证**：真实现走 {@code ServerLevel}，
     * 离线 harness 注入一个「全是空气 / 指定坐标是墙」的替身，
     * 于是 {@code hasLineOfSight} 的采样次数、方块查询次数、缓存命中率都能在离线被断言与计量。
     *
     * <p><b>注意：{@link ServerLevel} 本身并不实现这个接口</b>（它只是恰好有 {@code getBlockState}），
     * 所以生产路径必须经过 {@link VanillaLevel} 适配器 —— 早先这里直接强转，
     * 真机一进 tick 就 {@code ClassCastException: ServerLevel cannot be cast to BlockWorld$Level}。
     *
     * <p>{@link #bridge()} 返回一个真正的 {@link BlockGetter}（生产环境就是 ServerLevel 自己）。
     * 离线替身可以返回 {@code null}：那时只判「空/实心」两态
     * —— 碰撞形状的细节不是本类要验的东西，采样与查询次数才是。
     */
    public interface Level {
        /**
         * 该坐标有没有碰撞（离线替身走这条路）。
         *
         * <p>生产实现由 {@code ServerLevel.getBlockState(...).getCollisionShape(...)} 推出，
         * 这里作为**独立方法**暴露，是为了让离线 harness 能在完全没有注册表（不能 new BlockState）
         * 的环境里给出「空 / 实心」两态。
         */
        default boolean isSolid(BlockPos pos) {
            BlockGetter b = bridge();
            if (b == null) {
                return false;
            }
            return !b.getBlockState(pos).getCollisionShape(b, pos).isEmpty();
        }

        /** 用于 {@code getCollisionShape} 的方块读取器；返回 null 表示离线替身（走 {@link #isSolid}） */
        default BlockGetter bridge() {
            return null;
        }
    }

    /**
     * 生产适配器：把真实的方块世界（{@link ServerLevel}）接成 {@link Level}。
     *
     * <p>它就是 {@code bridge()} 返回自己 —— 于是 {@code isSolid} 走接口的默认实现
     * （{@code getBlockState(...).getCollisionShape(...)}），与改造前的逐行行为完全一致。
     */
    private static final class VanillaLevel implements Level {
        private final BlockGetter bridge;

        VanillaLevel(BlockGetter bridge) {
            this.bridge = bridge;
        }

        @Override
        public BlockGetter bridge() {
            return bridge;
        }

        @Override
        public String toString() {
            return "VanillaLevel(" + bridge.getClass().getSimpleName() + ")";
        }
    }

    private final Level level;
    private final BlockGetter bridge;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    // ---- 插桩：这一 tick 的真实方块查询次数 / 缓存命中次数（卡顿归因用） ----
    private long tickBlockQueries;
    private long tickCacheHits;
    private long tickCacheMisses;
    private long tickLosSamples;
    private long tickLosCalls;
    private long tickCollideCells;

    public BlockWorld(ServerLevel level, int cacheSizePow2) {
        // 必须经适配器：ServerLevel 并不实现 Level 接口（直接强转会 ClassCastException）
        this(level == null ? null : new VanillaLevel(level), cacheSizePow2);
    }

    public BlockWorld(Level level, int cacheSizePow2) {
        this.level = level;
        this.bridge = level == null ? null : level.bridge();
        int size = Math.max(1024, Integer.highestOneBit(cacheSizePow2));
        this.keys = new long[size];
        this.vals = new int[size];
        this.mask = size - 1;
        this.maxEntries = size / 2;
        clearCache();
    }

    private final long[] keys;
    private final int[] vals;
    private final int mask;
    /** 已缓存的方块数；接近容量时清空，保证探测一定能终止 */
    private int entryCount;
    private final int maxEntries;
    /** 碰撞盒池：每条记录 = [数量, minX,minY,minZ,maxX,maxY,maxZ, ...] */
    private float[] pool = new float[4096];
    private int poolCount;

    // 复用的临时盒（6 floats）与位移（3 floats）
    private final float[] work = new float[6];
    private final float[] primary = new float[6];
    private final float[] boxA = new float[6];
    private final float[] boxB = new float[6];
    private final float[] boxC = new float[6];
    private final float[] boxD = new float[6];
    private final float[] deltaA = new float[3];
    private final float[] deltaB = new float[3];
    private final float[] deltaC = new float[3];

    public void beginTick() {
        clearCache();
        tickBlockQueries = 0L;
        tickCacheHits = 0L;
        tickCacheMisses = 0L;
        tickLosSamples = 0L;
        tickLosCalls = 0L;
        tickCollideCells = 0L;
    }

    // ---- 插桩只读访问（卡顿归因 / harness） ----

    /** 本 tick 真正调用 {@code level.getBlockState} 的次数（= 缓存未命中数） */
    public long tickBlockQueries() {
        return tickBlockQueries;
    }

    public long tickCacheHits() {
        return tickCacheHits;
    }

    public long tickCacheMisses() {
        return tickCacheMisses;
    }

    /** 本 tick 视线采样的采样点数（每个点一次缓存查询） */
    public long tickLosSamples() {
        return tickLosSamples;
    }

    public long tickLosCalls() {
        return tickLosCalls;
    }

    /** 本 tick 碰撞解算里查过的格子数 */
    public long tickCollideCells() {
        return tickCollideCells;
    }

    private void clearCache() {
        Arrays.fill(keys, EMPTY_KEY);
        poolCount = 0;
        entryCount = 0;
    }

    // ------------------------------------------------------------------
    // 缓存查询
    // ------------------------------------------------------------------

    private int lookup(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        // 装载因子过半就清空：既避免探测退化成线性扫描，也保证不会填满
        if (entryCount >= maxEntries) {
            clearCache();
        }
        int h = (int) (key ^ (key >>> 32)) & mask;
        int probes = 0;
        while (true) {
            long k = keys[h];
            if (k == EMPTY_KEY) {
                int v = compute(x, y, z);
                keys[h] = key;
                vals[h] = v;
                entryCount++;
                tickCacheMisses++;
                return v;
            }
            if (k == key) {
                tickCacheHits++;
                return vals[h];
            }
            if (++probes > mask) {
                // 兜底：绝不允许空转（正常路径上不会走到这里）
                return compute(x, y, z);
            }
            h = (h + 1) & mask;
        }
    }

    private int compute(int x, int y, int z) {
        mutablePos.set(x, y, z);
        tickBlockQueries++;
        VoxelShape shape;
        if (bridge == null) {
            // 离线替身：只判「空 / 实心」两态（不碰任何需要注册表的方块状态）
            shape = level.isSolid(mutablePos) ? Shapes.block() : Shapes.empty();
        } else {
            BlockState state = bridge.getBlockState(mutablePos);
            shape = state.getCollisionShape(bridge, mutablePos);
        }
        if (shape.isEmpty()) {
            return 0;
        }
        int base = poolCount;
        if (shape == Shapes.block()) {
            ensurePool(base + 7);
            pool[base] = 1f;
            pool[base + 1] = 0f;
            pool[base + 2] = 0f;
            pool[base + 3] = 0f;
            pool[base + 4] = 1f;
            pool[base + 5] = 1f;
            pool[base + 6] = 1f;
            poolCount = base + 7;
        } else {
            List<AABB> boxes = shape.toAabbs();
            int n = boxes.size();
            if (n == 0) {
                return 0;
            }
            ensurePool(base + 1 + n * 6);
            pool[base] = n;
            int p = base + 1;
            for (int i = 0; i < n; i++) {
                AABB b = boxes.get(i);
                pool[p] = (float) b.minX;
                pool[p + 1] = (float) b.minY;
                pool[p + 2] = (float) b.minZ;
                pool[p + 3] = (float) b.maxX;
                pool[p + 4] = (float) b.maxY;
                pool[p + 5] = (float) b.maxZ;
                p += 6;
            }
            poolCount = base + 1 + n * 6;
        }
        return base + 1;
    }

    private void ensurePool(int need) {
        if (pool.length < need) {
            int cap = pool.length;
            while (cap < need && cap > 0) {
                cap <<= 1;
            }
            if (cap <= 0) {
                cap = need;
            }
            pool = Arrays.copyOf(pool, cap);
        }
    }

    // ------------------------------------------------------------------
    // 逐轴碰撞
    // ------------------------------------------------------------------

    /** 在某一轴上求允许的最大位移。box 为当前位置。 */
    private float collideAxis(float[] b, int axis, float delta) {
        if (delta == 0f) {
            return 0f;
        }
        float minX = b[0], minY = b[1], minZ = b[2], maxX = b[3], maxY = b[4], maxZ = b[5];
        float sMinX = minX, sMinY = minY, sMinZ = minZ, sMaxX = maxX, sMaxY = maxY, sMaxZ = maxZ;
        if (axis == 0) {
            if (delta > 0) sMaxX += delta; else sMinX += delta;
        } else if (axis == 1) {
            if (delta > 0) sMaxY += delta; else sMinY += delta;
        } else {
            if (delta > 0) sMaxZ += delta; else sMinZ += delta;
        }

        int bx0 = (int) Math.floor(sMinX - EPS);
        int bx1 = (int) Math.floor(sMaxX + EPS);
        int by0 = (int) Math.floor(sMinY - EPS);
        int by1 = (int) Math.floor(sMaxY + EPS);
        int bz0 = (int) Math.floor(sMinZ - EPS);
        int bz1 = (int) Math.floor(sMaxZ + EPS);

        float result = delta;
        float face = Float.NaN;
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    int idx = lookup(bx, by, bz);
                    tickCollideCells++;
                    if (idx == 0) {
                        continue;
                    }
                    int base = idx - 1;
                    int n = (int) pool[base];
                    int p = base + 1;
                    for (int k = 0; k < n; k++, p += 6) {
                        float ox0 = bx + pool[p];
                        float oy0 = by + pool[p + 1];
                        float oz0 = bz + pool[p + 2];
                        float ox1 = bx + pool[p + 3];
                        float oy1 = by + pool[p + 4];
                        float oz1 = bz + pool[p + 5];
                        if (axis != 0 && (maxX <= ox0 + EPS || minX >= ox1 - EPS)) continue;
                        if (axis != 1 && (maxY <= oy0 + EPS || minY >= oy1 - EPS)) continue;
                        if (axis != 2 && (maxZ <= oz0 + EPS || minZ >= oz1 - EPS)) continue;

                        if (axis == 0) {
                            if (delta > 0) {
                                float cand = ox0 - maxX;
                                if (cand < result) { result = cand; face = ox0; }
                            } else {
                                float cand = ox1 - minX;
                                if (cand > result) { result = cand; face = ox1; }
                            }
                        } else if (axis == 1) {
                            if (delta > 0) {
                                float cand = oy0 - maxY;
                                if (cand < result) { result = cand; face = oy0; }
                            } else {
                                float cand = oy1 - minY;
                                if (cand > result) { result = cand; face = oy1; }
                            }
                        } else {
                            if (delta > 0) {
                                float cand = oz0 - maxZ;
                                if (cand < result) { result = cand; face = oz0; }
                            } else {
                                float cand = oz1 - minZ;
                                if (cand > result) { result = cand; face = oz1; }
                            }
                        }
                    }
                }
            }
        }

        float raw = result;
        if (delta > 0) {
            if (result < 0f) result = 0f;
            else if (result > delta) result = delta;
        } else {
            if (result > 0f) result = 0f;
            else if (result < delta) result = delta;
        }

        shift(b, axis, result);

        // 关键：把坐标**精确吸附到方块接触面**。
        // 只做浮点加减会留下 1 ulp 残差，使 AABB 略微嵌进地面方块；
        // 下一 tick 水平移动时该方块会被判为 X/Z 重叠，导致落地后水平方向永久卡死。
        if (!Float.isNaN(face) && result == raw) {
            if (axis == 0) {
                if (delta > 0) b[3] = face; else b[0] = face;
            } else if (axis == 1) {
                if (delta > 0) b[4] = face; else b[1] = face;
            } else {
                if (delta > 0) b[5] = face; else b[2] = face;
            }
        }
        return result;
    }

    private static void shift(float[] b, int axis, float v) {
        if (axis == 0) {
            b[0] += v;
            b[3] += v;
        } else if (axis == 1) {
            b[1] += v;
            b[4] += v;
        } else {
            b[2] += v;
            b[5] += v;
        }
    }

    private static void copy(float[] src, float[] dst) {
        System.arraycopy(src, 0, dst, 0, 6);
    }

    /** 复刻 Entity.collideWithShapes：可选原版轴序。b 会被移动到最终位置。 */
    private void collideVec(float[] b, float dx, float dy, float dz, float[] out) {
        float ry = 0f;
        float rz = 0f;
        float rx = 0f;
        if (dy != 0f) {
            ry = collideAxis(b, 1, dy);
        }
        boolean flag = Math.abs(dx) < Math.abs(dz);
        if (flag && dz != 0f) {
            rz = collideAxis(b, 2, dz);
        }
        if (dx != 0f) {
            rx = collideAxis(b, 0, dx);
        }
        if (!flag && dz != 0f) {
            rz = collideAxis(b, 2, dz);
        }
        out[0] = rx;
        out[1] = ry;
        out[2] = rz;
    }

    private static float sq(float x, float z) {
        return x * x + z * z;
    }

    /**
     * 原版 {@code Entity.collide} + 台阶逻辑。
     *
     * @param box          输入/输出 AABB（minX,minY,minZ,maxX,maxY,maxZ），会被移动到新位置
     * @param prevOnGround 上一 tick 是否在地面
     * @param outDelta     输出实际位移 (dx,dy,dz)
     * @param flags        [0]=onGround, [1]=horizontalCollision
     */
    public void move(float[] box, float dx, float dy, float dz, boolean prevOnGround, boolean allowStepUp,
                     float[] outDelta, boolean[] flags) {
        copy(box, work);
        collideVec(work, dx, dy, dz, deltaA);
        float rx = deltaA[0];
        float ry = deltaA[1];
        float rz = deltaA[2];
        // 常规解算结果（已精确吸附到接触面），仅在没有更优台阶路径时使用
        copy(work, primary);

        boolean xChanged = rx != dx;
        boolean yChanged = ry != dy;
        boolean zChanged = rz != dz;
        boolean grounded = prevOnGround || (yChanged && dy < 0f);
        boolean stepped = false;

        if (allowStepUp && STEP_HEIGHT > 0f && grounded && (xChanged || zChanged)) {
            copy(box, boxB);
            collideVec(boxB, dx, STEP_HEIGHT, dz, deltaB);

            copy(box, boxC);
            // expandTowards(dx, 0, dz)
            if (dx > 0) boxC[3] += dx; else if (dx < 0) boxC[0] += dx;
            if (dz > 0) boxC[5] += dz; else if (dz < 0) boxC[2] += dz;
            collideVec(boxC, 0f, STEP_HEIGHT, 0f, deltaC);

            float bestX = deltaB[0];
            float bestY = deltaB[1];
            float bestZ = deltaB[2];

            if (deltaC[1] < STEP_HEIGHT) {
                copy(box, boxD);
                shift(boxD, 0, deltaC[0]);
                shift(boxD, 1, deltaC[1]);
                shift(boxD, 2, deltaC[2]);
                collideVec(boxD, dx, 0f, dz, deltaA);
                float cx = deltaA[0] + deltaC[0];
                float cy = deltaA[1] + deltaC[1];
                float cz = deltaA[2] + deltaC[2];
                if (sq(cx, cz) > sq(bestX, bestZ)) {
                    bestX = cx;
                    bestY = cy;
                    bestZ = cz;
                }
            }

            if (sq(bestX, bestZ) > sq(rx, rz)) {
                copy(box, boxD);
                shift(boxD, 0, bestX);
                shift(boxD, 1, bestY);
                shift(boxD, 2, bestZ);
                collideVec(boxD, 0f, -bestY + dy, 0f, deltaA);
                rx = bestX + deltaA[0];
                ry = bestY + deltaA[1];
                rz = bestZ + deltaA[2];
                stepped = true;
            }
        }

        if (stepped) {
            copy(box, work);
            shift(work, 0, rx);
            shift(work, 1, ry);
            shift(work, 2, rz);
            copy(work, box);
        } else {
            copy(primary, box);
        }

        outDelta[0] = rx;
        outDelta[1] = ry;
        outDelta[2] = rz;
        flags[0] = (yChanged && dy < 0f) || (prevOnGround && ry == 0f && dy <= 0f);
        flags[1] = (rx != dx) || (rz != dz);
    }

    // ------------------------------------------------------------------
    // 视线（远程单位「有视线才放箭」用）
    // ------------------------------------------------------------------

    /**
     * 走样式的**视线采样**：把「两点之间有没有方块挡着」这件事做成一次固定长度的粗采样。
     *
     * 为什么用走样而不是原版 {@code ClipContext} 的体素射线：这里每 tick 被远程单位调用
     * （一旦有几十个骷髅同时索敌），而走样只吃 {@link #lookup} 的缓存 —— 同一 tick 内
     * 大量单位朝同一方向看时几乎全命中缓存，原版射线则每次都要新建 {@code BlockHitResult}
     * 对象并逐体素裁形状。对「能不能放箭」这个用途来说，0.5 格的采样步长远够精确；
     * 万一某支箭被薄墙挡住，下一 tick 的采样会换一个步长相位再判一次。
     *
     * 判据用**碰撞形状**（{@code getCollisionShape}）而不是视觉形状：
     * 原版箭的飞行判定也吃碰撞形状，所以「草丛/非碰撞方块后面照样能射」与实际一致。
     *
     * @return true = 采样到实心方块（视线被挡）；false = 一路畅通
     */
    public boolean hasLineOfSight(float x0, float y0, float z0, float x1, float y1, float z1) {
        tickLosCalls++;
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4F) {
            return true;
        }
        // 采样步长：近距细、远距粗 —— 但**上限固定为 N 个采样点**（默认 24）。
        //
        // 为什么必须封顶：原来的写法是「固定 0.5 格步长、上限 256 步」，
        // 于是一条 16 格的射线要查 32 次缓存、一条 48 格的要查 96 次。
        // 100 个骷髅 × 每个多候选索敌时，光视线采样就能到每秒数十万次方块查询 ——
        // 真机卡顿行里 ai/combat/skill 各 500ms 的主因就是这一类「每单位每 tick 的固定开销」。
        // 封顶到 24 点后：16 格射线仍是 24 点（精确度几乎不变），48 格射线从 96 点降到 24 点。
        // 判定语义不变（仍是「采样到实心方块就返回 false」），只是采样密度随距离自适应。
        int cap = losSampleCap;
        int steps = (int) (dist / LOS_STEP) + 1;
        if (steps > cap) {
            steps = cap;
        }
        tickLosSamples += Math.max(0, steps - 1);
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            int bx = (int) Math.floor(x0 + dx * t);
            int by = (int) Math.floor(y0 + dy * t);
            int bz = (int) Math.floor(z0 + dz * t);
            if (lookup(bx, by, bz) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 单次视线采样的采样点上限（远距自适应变粗，避免长射线吃掉整帧） */
    public static final int DEFAULT_LOS_SAMPLE_CAP = 24;
    private static volatile int losSampleCap = DEFAULT_LOS_SAMPLE_CAP;

    /**
     * 临时改采样上限（**只给离线对照实验用**）：设成 256 就复现「0.5 格步长、无上限」的旧行为，
     * 于是 harness 能在同一场景上量出「优化前 vs 优化后」。
     */
    public static void setLosSampleCap(int cap) {
        losSampleCap = Math.max(2, cap);
    }

    public static int losSampleCap() {
        return losSampleCap;
    }

    /** 兼容原版 onGround 判定：向下运动被挡住即视为着地。 */
    public static boolean isGrounded(float dy, float resolvedDy, boolean prev) {
        if (dy < 0f && resolvedDy > dy) {
            return true;
        }
        return prev && dy <= 0f && resolvedDy != dy;
    }
}
