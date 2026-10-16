package dev.swordkingdom.horde.crowd.physics;

/**
 * 纯几何（无分配、无状态）—— 胶囊/球/盒之间的最近点与重叠判定。
 *
 * <p>与 {@link Sweep} 的分工：{@code Sweep} 只回答「**什么时候**撞上」（TOI，time of impact），
 * 本类回答「**现在**重叠了多少、往哪个方向分开」。两者都是纯 float 数学，
 * 不持有任何状态，因此可以被求解器、查询、harness 在同一份实现上复用地验证。
 *
 * <p><b>为什么统一用「胶囊」而不是「圆柱」</b>：Unity 的 {@code CapsuleCollider} 两端是半球，
 * 最近点算法是闭式的（线段-线段最近点），没有「圆柱端面 vs 侧面」的分支地狱；
 * 而圆柱的端面在数值上会在「站在别人头上」时产生退化。原版实体本来就是圆角盒子，
 * 用胶囊反而更接近视觉形状。
 */
public final class ShapeMath {

    private ShapeMath() {
    }

    public static final float EPS = 1.0E-9F;

    // ------------------------------------------------------------------
    // 盒
    // ------------------------------------------------------------------

    public static boolean pointInAabb(float x, float y, float z,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public static boolean aabbOverlap(float minX1, float minY1, float minZ1, float maxX1, float maxY1, float maxZ1,
                                      float minX2, float minY2, float minZ2, float maxX2, float maxY2, float maxZ2) {
        return maxX1 > minX2 && minX1 < maxX2
                && maxY1 > minY2 && minY1 < maxY2
                && maxZ1 > minZ2 && minZ1 < maxZ2;
    }

    /**
     * 点在盒内时，求出**最小平移轴**（MTV）离开盒子：把法线写进 out（长度 3），返回穿透深度。
     * 点在盒外返回 {@code -1}。
     *
     * <p>为什么需要它：射线/扫掠的**起点已经在盒子里**时没有「进入面」可言，
     * 必须退化成「沿最浅的一面推出去」。不做这一步的话，起点在墙里的投掷物会永远出不来。
     */
    public static float aabbMinTranslation(float x, float y, float z,
                                           float minX, float minY, float minZ,
                                           float maxX, float maxY, float maxZ,
                                           float[] outNormal) {
        if (!pointInAabb(x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
            return -1.0F;
        }
        float dxMin = x - minX;
        float dxMax = maxX - x;
        float dyMin = y - minY;
        float dyMax = maxY - y;
        float dzMin = z - minZ;
        float dzMax = maxZ - z;

        float best = dxMin;
        int axis = 0;
        float sign = -1.0F;
        if (dxMax < best) {
            best = dxMax;
            axis = 0;
            sign = 1.0F;
        }
        if (dyMin < best) {
            best = dyMin;
            axis = 1;
            sign = -1.0F;
        }
        if (dyMax < best) {
            best = dyMax;
            axis = 1;
            sign = 1.0F;
        }
        if (dzMin < best) {
            best = dzMin;
            axis = 2;
            sign = -1.0F;
        }
        if (dzMax < best) {
            best = dzMax;
            axis = 2;
            sign = 1.0F;
        }
        outNormal[0] = axis == 0 ? sign : 0.0F;
        outNormal[1] = axis == 1 ? sign : 0.0F;
        outNormal[2] = axis == 2 ? sign : 0.0F;
        return best;
    }

    // ------------------------------------------------------------------
    // 线段 / 胶囊
    // ------------------------------------------------------------------

    /**
     * 点到线段的最近点参数 {@code s ∈ [0,1]}（线段 A->B，点 P）。
     *
     * <p>只返回参数而不是点：调用方（TOI、接触生成）几乎总要基于它继续算，
     * 返回 float 可以完全免分配。
     */
    public static float closestParamOnSegment(float px, float py, float pz,
                                             float ax, float ay, float az,
                                             float bx, float by, float bz) {
        float ex = bx - ax;
        float ey = by - ay;
        float ez = bz - az;
        float len2 = ex * ex + ey * ey + ez * ez;
        if (len2 <= EPS) {
            return 0.0F;
        }
        float s = ((px - ax) * ex + (py - ay) * ey + (pz - az) * ez) / len2;
        if (s < 0.0F) {
            return 0.0F;
        }
        return s > 1.0F ? 1.0F : s;
    }

    /** 点到线段的最近点（写进 out，长度 3）。 */
    public static void closestPointOnSegment(float px, float py, float pz,
                                            float ax, float ay, float az,
                                            float bx, float by, float bz,
                                            float[] out) {
        float s = closestParamOnSegment(px, py, pz, ax, ay, az, bx, by, bz);
        out[0] = ax + (bx - ax) * s;
        out[1] = ay + (by - ay) * s;
        out[2] = az + (bz - az) * s;
    }

    /**
     * 两条线段的最近点（Ericson, <i>Real-Time Collision Detection</i> §5.1.9）。
     * 结果写进 {@code out1}（在段 1 上）与 {@code out2}（在段 2 上），各长度 3。
     *
     * <p>退化情形（零长线段/平行且投影越界）都在内部夹取到端点，
     * 因此**永远**给出两个合法的最近点，不会返回 NaN —— 求解器最怕的就是 NaN 传播。
     */
    public static void closestPointsSegments(float p1x, float p1y, float p1z, float q1x, float q1y, float q1z,
                                            float p2x, float p2y, float p2z, float q2x, float q2y, float q2z,
                                            float[] out1, float[] out2) {
        float d1x = q1x - p1x;
        float d1y = q1y - p1y;
        float d1z = q1z - p1z;
        float d2x = q2x - p2x;
        float d2y = q2y - p2y;
        float d2z = q2z - p2z;
        float rx = p1x - p2x;
        float ry = p1y - p2y;
        float rz = p1z - p2z;

        float a = d1x * d1x + d1y * d1y + d1z * d1z;
        float e = d2x * d2x + d2y * d2y + d2z * d2z;
        float f = d2x * rx + d2y * ry + d2z * rz;

        float s;
        float t;
        if (a <= EPS && e <= EPS) {
            s = 0.0F;
            t = 0.0F;
        } else if (a <= EPS) {
            s = 0.0F;
            t = clamp01(f / e);
        } else {
            float c = d1x * rx + d1y * ry + d1z * rz;
            if (e <= EPS) {
                t = 0.0F;
                s = clamp01(-c / a);
            } else {
                float b = d1x * d2x + d1y * d2y + d1z * d2z;
                float denom = a * e - b * b;
                s = denom != 0.0F ? clamp01((b * f - c * e) / denom) : 0.0F;
                t = (b * s + f) / e;
                if (t < 0.0F) {
                    t = 0.0F;
                    s = clamp01(-c / a);
                } else if (t > 1.0F) {
                    t = 1.0F;
                    s = clamp01((b - c) / a);
                }
            }
        }
        out1[0] = p1x + d1x * s;
        out1[1] = p1y + d1y * s;
        out1[2] = p1z + d1z * s;
        out2[0] = p2x + d2x * t;
        out2[1] = p2y + d2y * t;
        out2[2] = p2z + d2z * t;
    }

    public static float clamp01(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    /** 点到胶囊轴线段（A->B，半径 r）的距离平方。 */
    public static float distanceSqToCapsuleAxis(float px, float py, float pz,
                                                float ax, float ay, float az,
                                                float bx, float by, float bz,
                                                float[] scratch) {
        closestPointOnSegment(px, py, pz, ax, ay, az, bx, by, bz, scratch);
        float dx = px - scratch[0];
        float dy = py - scratch[1];
        float dz = pz - scratch[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** 点是否在胶囊内（含表面）。 */
    public static boolean pointInCapsule(float px, float py, float pz,
                                         float ax, float ay, float az,
                                         float bx, float by, float bz,
                                         float radius, float[] scratch) {
        float d2 = distanceSqToCapsuleAxis(px, py, pz, ax, ay, az, bx, by, bz, scratch);
        return d2 <= radius * radius;
    }

    /**
     * 两个胶囊的重叠：写 {@code outNormal}（长度 3，从胶囊 1 指向胶囊 2，单位向量）并返回穿透深度；
     * 不重叠返回 {@code -1}。
     *
     * <p>这是 Unity 的 {@code CapsuleCollider} 之间接触生成的解析解：
     * 两轴最近点连线就是接触法线，穿透 = r1 + r2 - 轴距。
     *
     * @param fallbackSeed 完全重合（轴距≈0）时的**确定性**散开方向的种子。
     *                     传 {@code (i * 73856093) ^ (j * 19349663)} 这类「由两个单位编号推出的整数」，
     *                     于是同样的输入永远给出同样的方向 —— 这是可复现性的前提
     *                     （现有 {@code CrowdSeparation} 也是这么做的，只是没写成通用原语）。
     */
    public static float capsuleCapsulePenetration(float a1x, float a1y, float a1z, float b1x, float b1y, float b1z,
                                                  float r1,
                                                  float a2x, float a2y, float a2z, float b2x, float b2y, float b2z,
                                                  float r2, int fallbackSeed,
                                                  float[] outNormal, float[] scratch1, float[] scratch2) {
        closestPointsSegments(a1x, a1y, a1z, b1x, b1y, b1z, a2x, a2y, a2z, b2x, b2y, b2z, scratch1, scratch2);
        float nx = scratch2[0] - scratch1[0];
        float ny = scratch2[1] - scratch1[1];
        float nz = scratch2[2] - scratch1[2];
        float dist2 = nx * nx + ny * ny + nz * nz;
        float sum = r1 + r2;
        if (dist2 > sum * sum) {
            return -1.0F;
        }
        if (dist2 <= 1.0E-12F) {
            // 完全重合：用确定性种子选一个水平散开方向（绝不随机，否则服务端不同步）
            int h = fallbackSeed * 1103515245 + 12345;
            float ang = (h & 0xFFFF) * (6.2831855F / 65536.0F);
            // 只用 +,-,*,/ 与 StrictMath 的 sqrt：保证跨 JVM 逐位可复现（详见 docs/PHYSICS.md）
            outNormal[0] = (float) StrictMath.cos(ang);
            outNormal[1] = 0.0F;
            outNormal[2] = (float) StrictMath.sin(ang);
            return sum;
        }
        float inv = 1.0F / (float) StrictMath.sqrt(dist2);
        outNormal[0] = nx * inv;
        outNormal[1] = ny * inv;
        outNormal[2] = nz * inv;
        return sum - (float) StrictMath.sqrt(dist2);
    }
}
