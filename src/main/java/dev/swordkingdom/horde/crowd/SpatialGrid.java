package dev.swordkingdom.horde.crowd;

import java.util.Arrays;

/**
 * 空间哈希网格（CSR 布局）。
 *
 * 把战场切成固定大小的格子，每 tick 重建一次「格子 -> 单位索引」的映射，
 * 于是邻居查询从 O(n^2) 降到 O(n)。整个结构零对象分配，数组全部复用。
 */
public final class SpatialGrid {

    private static final int MAX_DIM = 512;

    public final float cellSize;

    private float minX;
    private float minZ;
    private int gw;
    private int gh;

    /** 前缀和：格子 ci 的成员位于 items[cellStart[ci] .. cellStart[ci+1]) */
    private int[] cellStart = new int[1];
    /** 复用为计数 / 游标 */
    private int[] counts = new int[1];
    /** 按格子排序后的单位槽位 */
    private int[] items = new int[1];
    /** 每个单位槽位所属格子（-1 表示未参与） */
    private int[] cellOf = new int[1];

    public int itemCount;
    public boolean valid;

    public SpatialGrid(float cellSize) {
        this.cellSize = Math.max(0.25f, cellSize);
    }

    public void build(CrowdData d) {
        float bMinX = Float.MAX_VALUE;
        float bMinZ = Float.MAX_VALUE;
        float bMaxX = -Float.MAX_VALUE;
        float bMaxZ = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                continue;
            }
            n++;
            float x = d.posX[i];
            float z = d.posZ[i];
            if (x < bMinX) bMinX = x;
            if (x > bMaxX) bMaxX = x;
            if (z < bMinZ) bMinZ = z;
            if (z > bMaxZ) bMaxZ = z;
        }

        itemCount = n;
        if (n == 0) {
            valid = false;
            gw = gh = 0;
            return;
        }

        minX = bMinX - cellSize;
        minZ = bMinZ - cellSize;
        gw = (int) Math.ceil((bMaxX + cellSize - minX) / cellSize);
        gh = (int) Math.ceil((bMaxZ + cellSize - minZ) / cellSize);
        if (gw < 1) gw = 1;
        if (gh < 1) gh = 1;
        if (gw > MAX_DIM) gw = MAX_DIM;
        if (gh > MAX_DIM) gh = MAX_DIM;

        int cells = gw * gh;
        ensureCapacity(cells + 1, n, d.capacity);

        Arrays.fill(counts, 0, cells, 0);
        for (int i = 0; i < d.highWater; i++) {
            if (!d.aliveFlag[i]) {
                cellOf[i] = -1;
                continue;
            }
            int ci = cellOf(d.posX[i], d.posZ[i]);
            cellOf[i] = ci;
            counts[ci]++;
        }

        int sum = 0;
        for (int c = 0; c < cells; c++) {
            cellStart[c] = sum;
            sum += counts[c];
        }
        cellStart[cells] = sum;

        // counts 复用为填充游标
        System.arraycopy(cellStart, 0, counts, 0, cells);
        for (int i = 0; i < d.highWater; i++) {
            int ci = cellOf[i];
            if (ci < 0) {
                continue;
            }
            items[counts[ci]++] = i;
        }

        valid = true;
    }

    private void ensureCapacity(int cellsPlusOne, int itemCount, int unitCapacity) {
        if (cellStart.length < cellsPlusOne) {
            cellStart = new int[cellsPlusOne];
        }
        if (counts.length < cellsPlusOne) {
            counts = new int[cellsPlusOne];
        }
        if (items.length < itemCount) {
            items = new int[Math.max(itemCount, 16)];
        }
        if (cellOf.length < unitCapacity) {
            cellOf = new int[unitCapacity];
        }
    }

    /** 世界坐标 -> 格子索引（越界自动夹取）。 */
    public int cellOf(float x, float z) {
        return cellZ(z) * gw + cellX(x);
    }

    public int cellX(float x) {
        int cx = (int) Math.floor((x - minX) / cellSize);
        if (cx < 0) {
            return 0;
        }
        return cx >= gw ? gw - 1 : cx;
    }

    public int cellZ(float z) {
        int cz = (int) Math.floor((z - minZ) / cellSize);
        if (cz < 0) {
            return 0;
        }
        return cz >= gh ? gh - 1 : cz;
    }

    public int start(int cell) {
        return cellStart[cell];
    }

    public int end(int cell) {
        return cellStart[cell + 1];
    }

    public int item(int k) {
        return items[k];
    }

    public int gridWidth() {
        return gw;
    }

    public int gridHeight() {
        return gh;
    }
}
