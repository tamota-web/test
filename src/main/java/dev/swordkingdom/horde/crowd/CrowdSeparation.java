package dev.swordkingdom.horde.crowd;

/**
 * 群集单位之间的**硬分离**（去穿透），相当于原版实体的碰撞推挤。
 *
 * 之前只加了「速度冲量」，单位被 AI 往同一个点推时会叠在一起 —— 冲量太小、而且不来解决重叠。
 * 这里改成位置层面的最小平移分量（MTV）分离：
 *
 *  - 只在**同一高度层**（Y 区间相交）的单位之间分离，所以可以踩在别人头上（原版也允许）；
 *  - 沿 X/Z 里重叠较小的那个轴推开（最小平移），比随机方向更稳定，不会抖；
 *  - 推开量按「质量」分配：体型大、击退抗性高的单位被推得少（大 boss 挤开小怪）；
 *  - 每次分离量有上限，且放在物理步**之前**，所以被挤到墙里时后续的碰撞解算会兜住；
 *  - 多跑几轮以便把「一坨」拆开。
 */
public final class CrowdSeparation {

    /** 单次分离的最大位移（格），防止抖动/瞬移 */
    private static final float MAX_STEP = 0.25F;

    private CrowdSeparation() {}

    /**
     * @param halfWidth 每个槽位的半宽（按实体类型）
     * @param mass      每个槽位的「质量」，越大越不容易被推动（建议 1 起步，按体型/击退抗性加权）
     * @param strength  分离强度倍率（0 = 关闭）
     * @param passes    迭代次数（2~3 次足以把一坨拆开）
     */
    public static void resolve(CrowdData d, SpatialGrid grid, float[] halfWidth, float[] mass,
                              float strength, int passes) {
        if (strength <= 0.0F || passes <= 0) {
            return;
        }
        int gw = grid.gridWidth();
        int gh = grid.gridHeight();
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < d.highWater; i++) {
                if (!d.aliveFlag[i] || d.hp[i] <= 0f) {
                    continue;
                }
                float hwI = halfWidth[i];
                float mI = mass[i];
                int ci = grid.cellOf(d.posX[i] + hwI, d.posZ[i] + hwI);
                int ccx = ci % gw;
                int ccz = ci / gw;
                for (int ax = ccx - 1; ax <= ccx + 1; ax++) {
                    if (ax < 0 || ax >= gw) {
                        continue;
                    }
                    for (int az = ccz - 1; az <= ccz + 1; az++) {
                        if (az < 0 || az >= gh) {
                            continue;
                        }
                        int nci = az * gw + ax;
                        int s = grid.start(nci);
                        int e = grid.end(nci);
                        for (int k = s; k < e; k++) {
                            int j = grid.item(k);
                            if (j <= i || !d.aliveFlag[j] || d.hp[j] <= 0f) {
                                continue;
                            }
                            separate(d, i, j, halfWidth[i], halfWidth[j], mass[i], mass[j], strength);
                        }
                    }
                }
            }
        }
    }

    /** 把两个重叠的单位沿最小平移方向分开 */
    private static void separate(CrowdData d, int i, int j,
                                 float hwI, float hwJ, float mI, float mJ, float strength) {
        // 高度层不相交就不算挤在一起（允许踩在头上）
        if (!overlapY(d, i, j, hwI, hwJ)) {
            return;
        }
        // 两单位中心（服务端存的是 AABB 最小角）
        float cxi = d.posX[i] + hwI;
        float czi = d.posZ[i] + hwI;
        float cxj = d.posX[j] + hwJ;
        float czj = d.posZ[j] + hwJ;

        float minDistX = hwI + hwJ;
        float dx = cxj - cxi;
        float dz = czj - czi;
        float overlapX = minDistX - Math.abs(dx);
        float overlapZ = minDistX - Math.abs(dz);
        if (overlapX <= 0.0F || overlapZ <= 0.0F) {
            return;
        }

        float corr;
        float nx;
        float nz;
        if (dx == 0.0F && dz == 0.0F) {
            // 完全重合：按「对编号」散开。
            // 如果这里一律往同一个方向推，整坨单位会一起平移、彼此间距永远不变 —— 永远拆不开。
            corr = minDistX;
            int h = (i * 73856093) ^ (j * 19349663);
            if ((h & 1) == 0) {
                nx = (h & 2) == 0 ? 1.0F : -1.0F;
                nz = 0.0F;
            } else {
                nx = 0.0F;
                nz = (h & 4) == 0 ? 1.0F : -1.0F;
            }
        } else if (overlapX < overlapZ) {
            corr = overlapX;
            nx = dx >= 0.0F ? 1.0F : -1.0F;
            nz = 0.0F;
        } else {
            corr = overlapZ;
            nz = dz >= 0.0F ? 1.0F : -1.0F;
            nx = 0.0F;
        }

        float total = corr * Math.min(1.0F, strength);
        if (total > MAX_STEP) {
            total = MAX_STEP;
        }
        // 按质量反比分配：重的少动
        float sum = mI + mJ;
        float wi = sum <= 0.0F ? 0.5F : mJ / sum;
        float wj = sum <= 0.0F ? 0.5F : mI / sum;

        d.posX[i] -= nx * total * wi;
        d.posZ[i] -= nz * total * wi;
        d.posX[j] += nx * total * wj;
        d.posZ[j] += nz * total * wj;

        // 同时给一点速度，避免下一 tick 又被 AI 推回来（贴在一起时看起来是「挤」而不是「粘」）
        float impulse = total * 0.5F;
        d.velX[i] -= nx * impulse;
        d.velZ[i] -= nz * impulse;
        d.velX[j] += nx * impulse;
        d.velZ[j] += nz * impulse;
    }

    /** Y 区间是否相交（同一层） */
    private static boolean overlapY(CrowdData d, int i, int j, float hwI, float hwJ) {
        float hI = hwI * 2.0F;
        float hJ = hwJ * 2.0F;
        float minYI = d.posY[i];
        float maxYI = d.posY[i] + hI;
        float minYJ = d.posY[j];
        float maxYJ = d.posY[j] + hJ;
        // 允许 1/4 格的容差，避免上下台阶时反复分离
        return maxYI > minYJ + 0.25F && maxYJ > minYI + 0.25F;
    }
}
