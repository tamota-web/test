package dev.swordkingdom.horde.crowd.physics;

/**
 * 接触约束的 SoA 缓冲 —— 对应 PhysX 的 {@code PxContactSet} / Unity 内部的接触流形。
 *
 * <h2>为什么接触也要 SoA</h2>
 * 一坨 100 个单位互相挤在一起时，一个子步能产生几百到上千个接触点。
 * 若每个接触点是一个对象，光分配就把 GC 拖垮。这里的每个字段都是数组，
 * 而 {@link #count} 是「本子步有效约束数」—— 求解器只遍历 {@code 0..count}。
 *
 * <h2>法线方向约定（唯一约定，全包一致）</h2>
 * 法线 {@code n} 从 A 指向 B，即「把 B 沿 +n 推、把 A 沿 -n 推」。
 * A 允许是 {@code -1}（静态几何：体素），此时 A 当无限质量处理。
 * 这样「单位-单位」与「单位-地形」两类接触在求解器里**走同一条代码路径**，
 * 不需要写两份冲量逻辑。
 *
 * <h2>为什么穿透深度只在生成时算一次</h2>
 * 位置修正（split impulse）用的是**生成时刻**的穿透量，而不是迭代中实时的深度 ——
 * 既省掉每个迭代重新求最近点的开销，也避免「迭代中位置一动、深度就变」导致的
 * 反馈振荡（这正是「不抖动」的关键之一）。
 */
public final class Contacts {

    /** A 侧为静态几何时的哨兵值。 */
    public static final int STATIC = -1;

    public int count;

    private int contactCap;

    public int[] a = new int[1];
    public int[] b = new int[1];

    /** 法线：A -> B（单位向量）。 */
    public float[] nx = new float[1];
    public float[] ny = new float[1];
    public float[] nz = new float[1];

    /** 接触点（世界坐标，取两体最近点的中点）。 */
    public float[] px = new float[1];
    public float[] py = new float[1];
    public float[] pz = new float[1];

    /** 生成时刻的穿透深度（&gt; 0）。 */
    public float[] pen = new float[1];
    public float[] friction = new float[1];
    public float[] restitution = new float[1];

    /** 摩擦锥的切平面基（每接触一组，生成时算好，迭代中固定）。 */
    public float[] t1x = new float[1];
    public float[] t1y = new float[1];
    public float[] t1z = new float[1];
    public float[] t2x = new float[1];
    public float[] t2y = new float[1];
    public float[] t2z = new float[1];

    /** 累积冲量（顺序冲量求解器的核心状态：累积 + 夹取，而不是每迭代重算）。 */
    public float[] jn = new float[1];
    public float[] jt1 = new float[1];
    public float[] jt2 = new float[1];

    /** 位置修正的伪速度（split impulse）——按 body 索引，不按接触索引。 */
    public float[] biasVX = new float[1];
    public float[] biasVY = new float[1];
    public float[] biasVZ = new float[1];

    /** 本子步被接触/冲量碰过的 body 列表（只清这些，避免每步 O(n) 清零）。 */
    public int[] touched = new int[1];
    public int touchedCount;

    // ---- 每子步统计（成本模型 / harness） ----
    public long generated;
    public long staticContacts;
    public long skippedSleeping;
    public long skippedLayer;
    public float maxPenetration;

    public void resetStats() {
        generated = 0L;
        staticContacts = 0L;
        skippedSleeping = 0L;
        skippedLayer = 0L;
        maxPenetration = 0.0F;
    }

    /**
     * 接触数硬上限：防止某个 bug 让接触数无界增长（例如所有单位重合在一个点上）。
     * 超出后多余接触被丢弃并计入 {@link #overflowed}（真机上这条路必须打日志告警）。
     */
    public static final int HARD_CONTACT_CAP = 1 << 20;

    private int bodyCapHint = 64;

    /** 本步扩容次数（> 0 说明预估的接触容量偏小，属正常；持续增长要查原因）。 */
    public long grew;
    /** 因为触到硬上限而丢弃的接触数（**必须为 0**，否则物理结果不完整）。 */
    public long overflowed;

    public void ensure(int maxContacts, int bodyCapacity) {
        bodyCapHint = Math.max(bodyCapHint, bodyCapacity);
        if (contactCap < maxContacts) {
            int cap = Math.max(16, maxContacts);
            a = new int[cap];
            b = new int[cap];
            nx = new float[cap];
            ny = new float[cap];
            nz = new float[cap];
            px = new float[cap];
            py = new float[cap];
            pz = new float[cap];
            pen = new float[cap];
            friction = new float[cap];
            restitution = new float[cap];
            t1x = new float[cap];
            t1y = new float[cap];
            t1z = new float[cap];
            t2x = new float[cap];
            t2y = new float[cap];
            t2z = new float[cap];
            jn = new float[cap];
            jt1 = new float[cap];
            jt2 = new float[cap];
            contactCap = cap;
        }
        if (biasVX.length < bodyCapacity) {
            biasVX = new float[bodyCapacity];
            biasVY = new float[bodyCapacity];
            biasVZ = new float[bodyCapacity];
        }
        if (touched.length < bodyCapacity) {
            touched = new int[bodyCapacity];
        }
    }

    /** 开始一个新的子步：只重置计数，**不**清零数组（那才是零分配的意思）。 */
    public void beginSubStep() {
        count = 0;
        touchedCount = 0;
        grew = 0L;
        overflowed = 0L;
        resetStats();
    }

    /** 结束子步：把本子步碰过的 body 的伪速度清回 0。 */
    public void endSubStep() {
        for (int i = 0; i < touchedCount; i++) {
            int k = touched[i];
            biasVX[k] = 0.0F;
            biasVY[k] = 0.0F;
            biasVZ[k] = 0.0F;
        }
        touchedCount = 0;
    }

    /** 登记一个「本子步碰过」的 body（幂等由调用方保证：只在生成接触时登记一次）。 */
    public void touch(int body) {
        if (body < 0) {
            return;
        }
        biasVX[body] = 0.0F;
        biasVY[body] = 0.0F;
        biasVZ[body] = 0.0F;
        if (touchedCount < touched.length) {
            touched[touchedCount++] = body;
        }
    }

    /** 追加一个接触。法线由调用方保证已归一化；穿透 &le; 0 的调用方不该调进来。 */
    public void add(int bodyA, int bodyB,
                    float nX, float nY, float nZ,
                    float pX, float pY, float pZ,
                    float penetration, float fric, float rest) {
        if (count >= contactCap) {
            // 自动扩容：一坨 200 个单位挤在一起时接触数会远超「单位数 × 少量」的直觉，
            // 而接触数没有理论上的常数上界（N 个单位两两接触就是 O(N²)）。
            // 这里按 2 倍增长，并记一次 grew 便于在真机上发现「接触数异常膨胀」。
            if (contactCap >= HARD_CONTACT_CAP) {
                overflowed++;
                return;
            }
            ensure(Math.min(HARD_CONTACT_CAP, Math.max(16, contactCap * 2)), bodyCapHint);
            grew++;
        }
        int k = count;
        a[k] = bodyA;
        b[k] = bodyB;
        nx[k] = nX;
        ny[k] = nY;
        nz[k] = nZ;
        px[k] = pX;
        py[k] = pY;
        pz[k] = pZ;
        pen[k] = penetration;
        friction[k] = fric;
        restitution[k] = rest;
        jn[k] = 0.0F;
        jt1[k] = 0.0F;
        jt2[k] = 0.0F;

        // 切平面基：选与 |n| 最小分量对应的坐标轴，叉积得到 t1，再叉积得到 t2。
        // 一次性算好并**在所有迭代里保持不变**：若每次迭代重算切向，摩擦冲量的累积
        // 就没有一致的方向，表现为「贴着墙蹭动」这种非物理的漂移。
        float ax = Math.abs(nX);
        float ay = Math.abs(nY);
        float az = Math.abs(nZ);
        float ux = 0.0F;
        float uy = 0.0F;
        float uz = 0.0F;
        if (ax <= ay && ax <= az) {
            ux = 1.0F;
        } else if (ay <= az) {
            uy = 1.0F;
        } else {
            uz = 1.0F;
        }
        float x1 = uy * nZ - uz * nY;
        float y1 = uz * nX - ux * nZ;
        float z1 = ux * nY - uy * nX;
        float len = (float) StrictMath.sqrt(x1 * x1 + y1 * y1 + z1 * z1);
        if (len <= 1.0E-6F) {
            // 理论上到不了这里（u 与 n 不平行的构造保证 len > 0），兜底成 +X 方向
            x1 = 1.0F;
            y1 = 0.0F;
            z1 = 0.0F;
            len = 1.0F;
        }
        float inv = 1.0F / len;
        t1x[k] = x1 * inv;
        t1y[k] = y1 * inv;
        t1z[k] = z1 * inv;
        t2x[k] = nY * t1z[k] - nZ * t1y[k];
        t2y[k] = nZ * t1x[k] - nX * t1z[k];
        t2z[k] = nX * t1y[k] - nY * t1x[k];

        generated++;
        if (penetration > maxPenetration) {
            maxPenetration = penetration;
        }
        if (bodyA == STATIC) {
            staticContacts++;
        }
        count++;
    }
}
