package dev.swordkingdom.horde.crowd;

import java.util.Arrays;

/**
 * 流场寻路（Flow Field）。
 *
 * 同阵营的单位共享同一张「方向场」：从目标格做一次 BFS，得到每个格子到目标
 * 的步数，再把步数梯度转成归一化方向。于是一万个单位寻路只需算一次 BFS，
 * 而不是一万次 A*。
 */
public final class FlowField {

    private static final int MAX_DIM = 256;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private final float cellSize;

    private float minX;
    private float minZ;
    private int gw;
    private int gh;

    private int[] dist = new int[1];
    private float[] dirX = new float[1];
    private float[] dirZ = new float[1];
    private int[] queue = new int[1];

    private int goalCx = -1;
    private int goalCz = -1;

    /** 是否已经算出有效方向场 */
    public boolean active;

    public FlowField(float cellSize) {
        this.cellSize = Math.max(1.0f, cellSize);
    }

    /** 以 (x,z) 为中心，覆盖 w*h 的矩形区域重建网格（仅在尺寸变化时重新分配）。 */
    public void configure(float centerX, float centerZ, float width, float height) {
        minX = centerX - width * 0.5f;
        minZ = centerZ - height * 0.5f;
        gw = clampDim((int) Math.ceil(width / cellSize));
        gh = clampDim((int) Math.ceil(height / cellSize));
        int cells = gw * gh;
        if (dist.length < cells) {
            dist = new int[cells];
            dirX = new float[cells];
            dirZ = new float[cells];
            queue = new int[cells];
        }
    }

    private static int clampDim(int v) {
        if (v < 1) return 1;
        if (v > MAX_DIM) return MAX_DIM;
        return v;
    }

    public void setGoal(float x, float z) {
        goalCx = clamp((int) Math.floor((x - minX) / cellSize), 0, gw - 1);
        goalCz = clamp((int) Math.floor((z - minZ) / cellSize), 0, gh - 1);
    }

    public void clear() {
        active = false;
    }

    /** 从目标格做多源/单源 BFS，生成方向场。 */
    public void compute() {
        if (gw <= 0 || gh <= 0 || goalCx < 0) {
            active = false;
            return;
        }
        int cells = gw * gh;
        Arrays.fill(dist, 0, cells, -1);
        int head = 0;
        int tail = 0;
        int goal = goalCz * gw + goalCx;
        dist[goal] = 0;
        queue[tail++] = goal;

        while (head < tail) {
            int cur = queue[head++];
            int cx = cur % gw;
            int cz = cur / gw;
            int nd = dist[cur] + 1;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DX[k];
                int nz = cz + DZ[k];
                if (nx < 0 || nx >= gw || nz < 0 || nz >= gh) {
                    continue;
                }
                int ni = nz * gw + nx;
                if (dist[ni] != -1) {
                    continue;
                }
                dist[ni] = nd;
                queue[tail++] = ni;
            }
        }

        // 梯度 -> 方向
        for (int cz = 0; cz < gh; cz++) {
            for (int cx = 0; cx < gw; cx++) {
                int ci = cz * gw + cx;
                int d = dist[ci];
                if (d <= 0) {
                    dirX[ci] = 0f;
                    dirZ[ci] = 0f;
                    continue;
                }
                int bestD = d;
                int bestX = 0;
                int bestZ = 0;
                for (int k = 0; k < 4; k++) {
                    int nx = cx + DX[k];
                    int nz = cz + DZ[k];
                    if (nx < 0 || nx >= gw || nz < 0 || nz >= gh) {
                        continue;
                    }
                    int nd = dist[nz * gw + nx];
                    if (nd >= 0 && nd < bestD) {
                        bestD = nd;
                        bestX = DX[k];
                        bestZ = DZ[k];
                    }
                }
                float len = (float) Math.sqrt(bestX * bestX + bestZ * bestZ);
                if (len > 0f) {
                    dirX[ci] = bestX / len;
                    dirZ[ci] = bestZ / len;
                } else {
                    dirX[ci] = 0f;
                    dirZ[ci] = 0f;
                }
            }
        }
        active = true;
    }

    /** 采样某世界坐标处的方向，写入 out[0]=dx, out[1]=dz。返回 false 表示无有效方向。 */
    public boolean sample(float x, float z, float[] out) {
        if (!active) {
            out[0] = 0f;
            out[1] = 0f;
            return false;
        }
        int cx = clamp((int) Math.floor((x - minX) / cellSize), 0, gw - 1);
        int cz = clamp((int) Math.floor((z - minZ) / cellSize), 0, gh - 1);
        int ci = cz * gw + cx;
        float dx = dirX[ci];
        float dz = dirZ[ci];
        out[0] = dx;
        out[1] = dz;
        return dx != 0f || dz != 0f;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
