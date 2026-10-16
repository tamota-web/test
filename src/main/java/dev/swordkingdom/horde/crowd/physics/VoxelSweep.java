package dev.swordkingdom.horde.crowd.physics;

/**
 * 体素扫掠：在体素世界里做线段（可带半径）扫掠，返回第一次接触的 t。
 *
 * <h2>为什么用 DDA 而不是「按固定步长采样格子」</h2>
 * 固定步长采样是隧穿的另一个来源：步长大于一格时可能整格跳过。
 * 这里用 Amanatides-Woo 的体素遍历（DDA）：每一步都精确跳到「当前格子边界」，
 * 因此**经过的每个体素都会被访问到恰好一次**，与位移大小无关。
 *
 * <h2>半径（球体扫掠 / 盒体扫掠）怎么处理</h2>
 * 每个被访问体素的盒子外扩 {@code pad} 再与轨迹求交（见 {@link Sweep#sphereCastAabb}），
 * 同时把邻域也纳入测试：因为「球贴着墙掠过」时球心轨迹可能**根本不进入**那面墙所在的体素。
 * 邻域半径 {@code R = 1 + floor(pad)}（pad ≤ 1 时即 3×3×3），并且有明确的正确性论证：
 * 任何能在 t &lt; best 处命中的体素，必然在「t = 该处」的轨迹点所在体素的 R 邻域内，
 * 而那个体素一定已经被访问过（遇到 {@code 入口 t > best} 才提前结束）。
 *
 * <h2>成本</h2>
 * 访问的体素数 ≈ 轨迹长度（格）+ 1；每格再做 {@code (2R+1)³} 个盒子查询。
 * 上限由 {@link #maxVoxelVisits} 兜底（默认 512 格），保证一条超长射线不会吃掉整帧。
 */
public final class VoxelSweep {

    /** 单次查询最多访问多少个体素（超长射线兜底）。 */
    public static final int DEFAULT_MAX_VOXEL_VISITS = 512;

    private final float[] boxBuf;
    private final float[] nrm = new float[3];
    private final float[] bestNrm = new float[3];

    // ---- 插桩 ----
    private long voxelVisits;
    private long boxTests;
    private long truncated;

    /**
     * 命中所在的**体素坐标**。
     *
     * <p>为什么记这个而不是事后用 {@code floor(命中点)} 反推：命中点经常**正好落在方块面上**
     * （塔、台阶、地板顶面都是这样），此时 floor 会把坐标算到隔壁那一格（空气格），
     * 于是「箭插在哪块方块上」「挖掉哪一格」这类结算全错。C++ 里这种 bug 叫 off-by-one-face。
     */
    private int bestVx;
    private int bestVy;
    private int bestVz;

    public int hitVoxelX() {
        return bestVx;
    }

    public int hitVoxelY() {
        return bestVy;
    }

    public int hitVoxelZ() {
        return bestVz;
    }

    public VoxelSweep(VoxelSource source) {
        this.boxBuf = new float[Math.max(6, source.maxBoxes() * 6)];
    }

    public long voxelVisits() {
        return voxelVisits;
    }

    public long boxTests() {
        return boxTests;
    }

    /** 有多少次查询因为触到体素上限而被截断（> 0 说明射线太长或半径太大）。 */
    public long truncated() {
        return truncated;
    }

    public void resetCounters() {
        voxelVisits = 0L;
        boxTests = 0L;
        truncated = 0L;
    }

    /**
     * 线段扫掠（点或球）vs 体素。
     *
     * @param pad 0 = 纯射线；&gt; 0 = 半径 pad 的球体扫掠（投掷物用 0.1~0.5）
     * @return t ∈ [0,1]，或 {@link Sweep#NO_HIT}
     */
    public float raycast(VoxelSource src, float px, float py, float pz,
                         float dx, float dy, float dz,
                         float pad, float[] outNormal) {
        return raycast(src, px, py, pz, dx, dy, dz, pad, outNormal, null, DEFAULT_MAX_VOXEL_VISITS);
    }

    /**
     * @param voxel(x,y,z) 每个被访问体素的回调（可为 null，用于「哪里有墙」的可视化/调试）
     * @param maxVisits    体素访问上限
     */
    public float raycast(VoxelSource src, float px, float py, float pz,
                         float dx, float dy, float dz,
                         float pad, float[] outNormal,
                         VoxelVisitor voxel, int maxVisits) {
        if (dx == 0.0F && dy == 0.0F && dz == 0.0F) {
            return Sweep.NO_HIT;
        }
        int x = floor(px);
        int y = floor(py);
        int z = floor(pz);
        int stepX = dx > 0.0F ? 1 : (dx < 0.0F ? -1 : 0);
        int stepY = dy > 0.0F ? 1 : (dy < 0.0F ? -1 : 0);
        int stepZ = dz > 0.0F ? 1 : (dz < 0.0F ? -1 : 0);
        float tDeltaX = stepX != 0 ? Math.abs(1.0F / dx) : Float.POSITIVE_INFINITY;
        float tDeltaY = stepY != 0 ? Math.abs(1.0F / dy) : Float.POSITIVE_INFINITY;
        float tDeltaZ = stepZ != 0 ? Math.abs(1.0F / dz) : Float.POSITIVE_INFINITY;
        float tMaxX = boundary(pX(px, x, stepX), px, dx, stepX, tDeltaX);
        float tMaxY = boundary(pX(py, y, stepY), py, dy, stepY, tDeltaY);
        float tMaxZ = boundary(pX(pz, z, stepZ), pz, dz, stepZ, tDeltaZ);

        int r = pad > 0.0F ? Math.min(2, 1 + (int) pad) : 0;
        float t = 0.0F;
        float best = Sweep.NO_HIT;
        int visits = 0;
        while (t <= 1.0F) {
            if (visits >= maxVisits) {
                truncated++;
                break;
            }
            visits++;
            voxelVisits++;
            if (voxel != null) {
                voxel.visit(x, y, z);
            }
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r; oy++) {
                    for (int oz = -r; oz <= r; oz++) {
                        int n = src.boxes(x + ox, y + oy, z + oz, boxBuf, 0);
                        for (int k = 0; k < n; k++) {
                            int b = k * 6;
                            boxTests++;
                            float hit = Sweep.sphereCastAabb(px, py, pz, dx, dy, dz, pad,
                                    (x + ox) + boxBuf[b], (y + oy) + boxBuf[b + 1], (z + oz) + boxBuf[b + 2],
                                    (x + ox) + boxBuf[b + 3], (y + oy) + boxBuf[b + 4], (z + oz) + boxBuf[b + 5],
                                    nrm);
                            if (hit != Sweep.NO_HIT && (best == Sweep.NO_HIT || hit < best)) {
                                best = hit;
                                bestNrm[0] = nrm[0];
                                bestNrm[1] = nrm[1];
                                bestNrm[2] = nrm[2];
                                bestVx = x + ox;
                                bestVy = y + oy;
                                bestVz = z + oz;
                            }
                        }
                    }
                }
            }
            if (best != Sweep.NO_HIT && t > best) {
                // 当前体素的入口时刻已经晚于已找到的命中 → 后面的只会更晚（邻域已覆盖，见类注释）
                outNormal[0] = bestNrm[0];
                outNormal[1] = bestNrm[1];
                outNormal[2] = bestNrm[2];
                return best;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        if (best == Sweep.NO_HIT) {
            return Sweep.NO_HIT;
        }
        outNormal[0] = bestNrm[0];
        outNormal[1] = bestNrm[1];
        outNormal[2] = bestNrm[2];
        return best;
    }

    /** 体素访问回调（调试可视化 / harness 统计覆盖率用）。 */
    public interface VoxelVisitor {
        void visit(int x, int y, int z);
    }

    private static int floor(float v) {
        return (int) Math.floor(v);
    }

    private static float pX(float p, int cell, int step) {
        return step > 0 ? cell + 1 : cell;
    }

    private static float boundary(float edge, float p, float d, int step, float tDelta) {
        if (step == 0) {
            return Float.POSITIVE_INFINITY;
        }
        return (edge - p) / d;
    }
}
