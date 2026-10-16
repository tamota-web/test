package dev.swordkingdom.horde.crowd.physics;

/**
 * 扫掠求交（TOI, time of impact）—— 全部是解析解，**没有采样**。
 *
 * <h2>为什么必须解析算，而不是多采几个点</h2>
 * 「箭 3 格/tick = 60 格/秒」这件事的物理后果：一个 50ms 的子步里位移 3 格，
 * 而墙只有 1 格厚、单位宽度只有 0.6 格。逐帧点采样（起点、中点、终点各判一次）在这三个尺度上
 * **必然漏检** —— 起点在墙前、终点在墙后、中点正好落在墙的空隙里，整面墙就白建了。
 * 采样加密只是把「漏检概率」降低，永远消不掉；解析 TOI 直接给出「第一次接触发生在 t 的哪个值」，
 * 与位移大小无关，所以是「不得隧穿」这条硬要求的唯一正解。
 *
 * <h2>返回值约定（全局统一）</h2>
 * <ul>
 *   <li>返回 {@code t ∈ [0,1]}：把位移 {@code d} 按 {@code p + t·d} 走，就是接触点；</li>
 *   <li>返回 {@link #NO_HIT}（-1）：整段都没碰到；</li>
 *   <li>{@code t == 0}：**起点已经在里面**（Unity 也返回 0），此时法线退化为最小平移轴；</li>
 *   <li>法线永远是从**被撞物**指向**移动物**（即把移动物推出去的方向），单位向量。</li>
 * </ul>
 *
 * <h2>确定性</h2>
 * 只用 {@code + - * /} 与 {@link StrictMath#sqrt}（IEEE 精确舍入的平方根，fdlibm 实现，跨平台逐位一致），
 * 不用 {@code Math.sin/cos/atan2}（多数 JDK 走平台 intrinsics，不保证逐位一致）。
 * 于是「同一输入 → 同一 TOI」不只是在同一台机器上成立。
 *
 * <h2>零分配</h2>
 * 本类**没有**任何静态可变状态，也不 new 任何对象：输出走调用方给的 {@code outNormal}。
 * 唯一一处需要方向的地方（起点在物体内部）用闭式夹取/开方推出，不需要随机数。
 */
public final class Sweep {

    private Sweep() {
    }

    /** 没有命中（与 {@code t = 0} 的「起点即接触」区分开）。 */
    public static final float NO_HIT = -1.0F;

    // ------------------------------------------------------------------
    // 线段 vs AABB（slab 法）
    // ------------------------------------------------------------------

    /**
     * 线段 vs 轴对齐盒（slab 法；Unity 的 {@code Physics.Raycast} 对 BoxCollider 的等价解）。
     *
     * <p>起点在盒内时返回 0，法线取最小平移轴（否则「箭停在墙里」时无解）。
     */
    public static float segmentAabb(float px, float py, float pz,
                                    float dx, float dy, float dz,
                                    float minX, float minY, float minZ,
                                    float maxX, float maxY, float maxZ,
                                    float[] outNormal) {
        float t0 = 0.0F;
        float t1 = 1.0F;
        int hitAxis = -1;
        float hitSign = 0.0F;

        // ---- X 轴 ----
        if (dx == 0.0F) {
            if (px < minX || px > maxX) {
                return NO_HIT;
            }
        } else {
            float inv = 1.0F / dx;
            float ta = (minX - px) * inv;
            float tb = (maxX - px) * inv;
            float sign = -1.0F;
            if (ta > tb) {
                float sw = ta;
                ta = tb;
                tb = sw;
                sign = 1.0F;
            }
            if (ta > t0) {
                t0 = ta;
                hitAxis = 0;
                hitSign = sign;
            }
            if (tb < t1) {
                t1 = tb;
            }
            if (t0 > t1) {
                return NO_HIT;
            }
        }
        // ---- Y 轴 ----
        if (dy == 0.0F) {
            if (py < minY || py > maxY) {
                return NO_HIT;
            }
        } else {
            float inv = 1.0F / dy;
            float ta = (minY - py) * inv;
            float tb = (maxY - py) * inv;
            float sign = -1.0F;
            if (ta > tb) {
                float sw = ta;
                ta = tb;
                tb = sw;
                sign = 1.0F;
            }
            if (ta > t0) {
                t0 = ta;
                hitAxis = 1;
                hitSign = sign;
            }
            if (tb < t1) {
                t1 = tb;
            }
            if (t0 > t1) {
                return NO_HIT;
            }
        }
        // ---- Z 轴 ----
        if (dz == 0.0F) {
            if (pz < minZ || pz > maxZ) {
                return NO_HIT;
            }
        } else {
            float inv = 1.0F / dz;
            float ta = (minZ - pz) * inv;
            float tb = (maxZ - pz) * inv;
            float sign = -1.0F;
            if (ta > tb) {
                float sw = ta;
                ta = tb;
                tb = sw;
                sign = 1.0F;
            }
            if (ta > t0) {
                t0 = ta;
                hitAxis = 2;
                hitSign = sign;
            }
            if (tb < t1) {
                t1 = tb;
            }
            if (t0 > t1) {
                return NO_HIT;
            }
        }

        if (hitAxis >= 0) {
            // 有明确的进入面：贴面起步（t0 == 0）与正常进入（t0 > 0）用同一组法线
            outNormal[0] = hitAxis == 0 ? hitSign : 0.0F;
            outNormal[1] = hitAxis == 1 ? hitSign : 0.0F;
            outNormal[2] = hitAxis == 2 ? hitSign : 0.0F;
            return t0;
        }
        // 起点在盒内：退化为最小平移轴
        float pen = ShapeMath.aabbMinTranslation(px, py, pz, minX, minY, minZ, maxX, maxY, maxZ, outNormal);
        return pen < 0.0F ? NO_HIT : 0.0F;
    }

    /**
     * **移动的 AABB**（半宽 hx,hy,hz，中心沿 d 平移）vs 静态 AABB。
     *
     * <p>做法是 Minkowski 和：把静态盒各方向外扩 (hx,hy,hz)，再让**中心点**做线段求交。
     * 对「盒 vs 盒」这是**精确**的（不是近似），也是投掷物（小方块）撞方块的正确解法。
     */
    public static float movingAabb(float px, float py, float pz,
                                   float dx, float dy, float dz,
                                   float hx, float hy, float hz,
                                   float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ,
                                   float[] outNormal) {
        return segmentAabb(px, py, pz, dx, dy, dz,
                minX - hx, minY - hy, minZ - hz,
                maxX + hx, maxY + hy, maxZ + hz,
                outNormal);
    }

    // ------------------------------------------------------------------
    // 线段 vs 球 / 胶囊
    // ------------------------------------------------------------------

    /**
     * 线段 vs 球，只求「最早接触时刻」t（法线由 {@link #sphereNormal} 事后补算）。
     *
     * <p>解 {@code |p + t·d - c|² = r²}：{@code a t² + 2b t + c0 = 0}。
     * 起点在球内直接返回 0（这是「已经重叠」，不是「将要接触」）。
     */
    public static float sphereHitT(float px, float py, float pz,
                                   float dx, float dy, float dz,
                                   float cx, float cy, float cz,
                                   float radius) {
        float mx = px - cx;
        float my = py - cy;
        float mz = pz - cz;
        float c0 = mx * mx + my * my + mz * mz - radius * radius;
        if (c0 <= 0.0F) {
            return 0.0F;
        }
        float a = dx * dx + dy * dy + dz * dz;
        if (a <= ShapeMath.EPS) {
            return NO_HIT;   // 不动且在外面
        }
        float b = mx * dx + my * dy + mz * dz;
        if (b >= 0.0F) {
            return NO_HIT;   // 朝远离球心的方向走
        }
        float disc = b * b - a * c0;
        if (disc < 0.0F) {
            return NO_HIT;
        }
        float t = (-b - (float) StrictMath.sqrt(disc)) / a;
        if (t < 0.0F) {
            t = 0.0F;
        }
        return t > 1.0F ? NO_HIT : t;
    }

    /** 在 {@code t} 时刻把「球心 - 球心」方向的法线写进 outNormal（球心方向为零时给 +Y）。 */
    public static void sphereNormal(float px, float py, float pz,
                                    float dx, float dy, float dz,
                                    float cx, float cy, float cz,
                                    float t, float[] outNormal) {
        float hx = px + dx * t - cx;
        float hy = py + dy * t - cy;
        float hz = pz + dz * t - cz;
        float d2 = hx * hx + hy * hy + hz * hz;
        if (d2 <= 1.0E-12F) {
            outNormal[0] = 0.0F;
            outNormal[1] = 1.0F;
            outNormal[2] = 0.0F;
            return;
        }
        float inv = 1.0F / (float) StrictMath.sqrt(d2);
        outNormal[0] = hx * inv;
        outNormal[1] = hy * inv;
        outNormal[2] = hz * inv;
    }

    /** 线段 vs 球（法线直接算好）。 */
    public static float segmentSphere(float px, float py, float pz,
                                      float dx, float dy, float dz,
                                      float cx, float cy, float cz,
                                      float radius, float[] outNormal) {
        float t = sphereHitT(px, py, pz, dx, dy, dz, cx, cy, cz, radius);
        if (t != NO_HIT) {
            sphereNormal(px, py, pz, dx, dy, dz, cx, cy, cz, t, outNormal);
        }
        return t;
    }

    /**
     * 线段 vs 胶囊（轴 A->B，半径 r）。
     *
     * <p>胶囊是「线段 + 半径」，所以把空间按投影参数 s 分成三段闭式求解：
     * <ol>
     *   <li>侧柱面（s ∈ [0,1]）：把 p、d 都投影到垂直于轴的平面上，退化成二维圆求交；</li>
     *   <li>端球 A、端球 B：退化成球求交；</li>
     * </ol>
     * 取三段里合法解中 t 最小的那个，再按「赢的是哪一段」算对应法线。
     * 三段的判据都是**闭式**的，没有迭代、没有采样。
     */
    public static float segmentCapsule(float px, float py, float pz,
                                       float dx, float dy, float dz,
                                       float ax, float ay, float az,
                                       float bx, float by, float bz,
                                       float radius, float[] outNormal) {
        float ex = bx - ax;
        float ey = by - ay;
        float ez = bz - az;
        float len2 = ex * ex + ey * ey + ez * ez;
        float mx = px - ax;
        float my = py - ay;
        float mz = pz - az;

        if (len2 <= ShapeMath.EPS) {
            // 退化成一个球
            return segmentSphere(px, py, pz, dx, dy, dz, ax, ay, az, radius, outNormal);
        }

        // 起点已在胶囊内 → t = 0（法线 = 从轴上的最近点指向起点）
        float s0 = (mx * ex + my * ey + mz * ez) / len2;
        float cs = s0 < 0.0F ? 0.0F : (s0 > 1.0F ? 1.0F : s0);
        float qx = px - (ax + ex * cs);
        float qy = py - (ay + ey * cs);
        float qz = pz - (az + ez * cs);
        float q2 = qx * qx + qy * qy + qz * qz;
        if (q2 <= radius * radius) {
            if (q2 <= 1.0E-12F) {
                outNormal[0] = 0.0F;
                outNormal[1] = 1.0F;
                outNormal[2] = 0.0F;
            } else {
                float inv = 1.0F / (float) StrictMath.sqrt(q2);
                outNormal[0] = qx * inv;
                outNormal[1] = qy * inv;
                outNormal[2] = qz * inv;
            }
            return 0.0F;
        }

        // ---- 1) 侧柱面 ----
        float sideT = NO_HIT;
        float de = (dx * ex + dy * ey + dz * ez) / len2;
        float dpx = dx - ex * de;
        float dpy = dy - ey * de;
        float dpz = dz - ez * de;
        float upx = mx - ex * s0;
        float upy = my - ey * s0;
        float upz = mz - ez * s0;
        float a2 = dpx * dpx + dpy * dpy + dpz * dpz;
        if (a2 > ShapeMath.EPS) {
            float b2 = upx * dpx + upy * dpy + upz * dpz;
            float c2 = upx * upx + upy * upy + upz * upz - radius * radius;
            float disc = b2 * b2 - a2 * c2;
            if (disc >= 0.0F) {
                float t = (-b2 - (float) StrictMath.sqrt(disc)) / a2;
                if (t >= 0.0F && t <= 1.0F) {
                    // 接触点的投影必须落在 [0,1]，否则这一段的活其实归端球
                    float sx = mx + dx * t;
                    float sy = my + dy * t;
                    float sz = mz + dz * t;
                    float s = (sx * ex + sy * ey + sz * ez) / len2;
                    if (s >= 0.0F && s <= 1.0F) {
                        sideT = t;
                    }
                }
            }
        }

        // ---- 2) 两端球 ----
        float tA = sphereHitT(px, py, pz, dx, dy, dz, ax, ay, az, radius);
        float tB = sphereHitT(px, py, pz, dx, dy, dz, bx, by, bz, radius);

        float best = NO_HIT;
        int cap = -1;   // 0 = 端球 A, 1 = 端球 B, 2 = 侧柱面
        if (tA != NO_HIT) {
            best = tA;
            cap = 0;
        }
        if (tB != NO_HIT && (best == NO_HIT || tB < best)) {
            best = tB;
            cap = 1;
        }
        if (sideT != NO_HIT && (best == NO_HIT || sideT < best)) {
            best = sideT;
            cap = 2;
        }
        if (best == NO_HIT) {
            return NO_HIT;
        }

        if (cap == 2) {
            // 侧柱面赢：法线 = 接触点到轴的垂线方向
            float hx = px + dx * best;
            float hy = py + dy * best;
            float hz = pz + dz * best;
            float ss = ((hx - ax) * ex + (hy - ay) * ey + (hz - az) * ez) / len2;
            ss = ss < 0.0F ? 0.0F : (ss > 1.0F ? 1.0F : ss);
            float nx = hx - (ax + ex * ss);
            float ny = hy - (ay + ey * ss);
            float nz = hz - (az + ez * ss);
            float n2 = nx * nx + ny * ny + nz * nz;
            if (n2 <= 1.0E-12F) {
                outNormal[0] = 0.0F;
                outNormal[1] = 1.0F;
                outNormal[2] = 0.0F;
            } else {
                float inv = 1.0F / (float) StrictMath.sqrt(n2);
                outNormal[0] = nx * inv;
                outNormal[1] = ny * inv;
                outNormal[2] = nz * inv;
            }
        } else if (cap == 0) {
            sphereNormal(px, py, pz, dx, dy, dz, ax, ay, az, best, outNormal);
        } else {
            sphereNormal(px, py, pz, dx, dy, dz, bx, by, bz, best, outNormal);
        }
        return best;
    }

    /**
     * 球体扫掠 vs 胶囊（Unity: {@code Physics.SphereCast}）。
     * 半径叠加：半径 R 的球沿 d 移动，撞上半径 r 的胶囊，等价于**点**沿 d 移动撞上半径 R+r 的胶囊。
     * 这条等价关系是 CCS 里最省事的一条：一次胶囊 TOI 同时解决了「零半径射线」与「有半径扫掠」。
     */
    public static float sphereCastCapsule(float px, float py, float pz,
                                          float dx, float dy, float dz,
                                          float castRadius,
                                          float ax, float ay, float az,
                                          float bx, float by, float bz,
                                          float capsuleRadius, float[] outNormal) {
        return segmentCapsule(px, py, pz, dx, dy, dz, ax, ay, az, bx, by, bz,
                castRadius + capsuleRadius, outNormal);
    }

    /**
     * 球体扫掠 vs AABB。
     *
     * <p>做法：把盒子外扩 castRadius 再与**球心轨迹**求交（与 {@link #movingAabb} 同思路）。
     * 注意这对「盒角」是**保守**的：外扩盒在角上是方的、真实球在角上是圆的，
     * 所以角附近可能提前一点点报命中（多报不漏报）。
     * 对体素地形（满格盒子）来说这个偏差只在「贴着墙角掠过」时出现，
     * 而它带来的是「宁可早停一格，也不穿墙」——正是我们要的方向。
     */
    public static float sphereCastAabb(float px, float py, float pz,
                                       float dx, float dy, float dz,
                                       float castRadius,
                                       float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ,
                                       float[] outNormal) {
        return segmentAabb(px, py, pz, dx, dy, dz,
                minX - castRadius, minY - castRadius, minZ - castRadius,
                maxX + castRadius, maxY + castRadius, maxZ + castRadius,
                outNormal);
    }
}
