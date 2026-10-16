package dev.swordkingdom.horde.crowd.physics;

/**
 * 顺序冲量求解器（sequential impulse）—— 对应 PhysX 的 <b>接触求解器</b>，
 * 也就是 Unity 在 {@code FixedUpdate} 之后、你完全看不见的那一段。
 *
 * <h2>为什么是「顺序冲量」而不是「位置 MTV」</h2>
 * 现有 {@code CrowdSeparation} 是**位置层面**的最小平移分量：直接把两个单位沿最小重叠轴掰开。
 * 它能解决「叠在一起」，但解决不了四件事，而它们恰好是长跑后暴露出来的问题：
 * <ol>
 *   <li><b>没有速度交换</b>：掰开之后速度不变，下一 tick AI 又把它们推回去 → 反复拉扯，
 *       看起来是「挤着抖」；冲量求解器让两者在**法向相对速度**上达成一致（不再互相接近），
 *       位置修正只负责补掉剩下的穿透，因此不会来回振荡；</li>
 *   <li><b>没有摩擦</b>：沿墙滑动、挤成一排时的横向稳定全靠它；</li>
 *   <li><b>没有质量分配的一致性</b>：MTV 是按质量比例直接掰位置，而冲量求解器里
 *       质量体现在 {@code invMass} 上，重的东西自然少动，轻的被推着走 —— 这是同一件事的
 *       **物理正确写法**，而且与「冲量/击退」共用同一套质量语义；</li>
 *   <li><b>无法与地形共用一条路径</b>：本求解器把「静态几何」当成 {@code a = -1} 的接触，
 *       于是「单位踩在地上」与「单位挤着单位」是同一段代码。</li>
 * </ol>
 *
 * <h2>为什么迭代次数固定、顺序固定</h2>
 * 顺序冲量是**迭代**解法：每轮按固定顺序扫一遍接触、各自施加一个冲量，
 * 收敛速度取决于顺序。因此「同一输入两次运行结果逐位一致」要求：
 * 接触顺序固定（{@link BroadphaseGrid} 保证 (i,j) 升序）、迭代次数固定、
 * 每次迭代内不引入任何与顺序无关的浮点求和（例如并行累加）。
 * 这也是**不做并行求解**的原因：并行归约会改变浮点求和顺序，
 * 于是同一份存档在两个核心数不同的机器上会走出不同结果。
 *
 * <h2>穿透容差与位置修正</h2>
 * <ul>
 *   <li>{@code slop}（PhysX 的 restart tolerance / Box2D 的 {@code linearSlop}）：
 *       允许的**残留**穿透。没有它，位置修正会每步都去追一个永远追不到的 0，
 *       表现为持续的微抖；</li>
 *   <li>{@code split impulse}：位置修正走**独立的伪速度**通道，不污染真实速度。
 *       于是「把单位推出地面」这件事不产生任何动能 —— 这是「不抖动」的根本原因。
 *       关掉它就只能用 Baumgarte 把穿透塞进速度里，那会凭空给物体加速（能量不守恒）；</li>
 *   <li>{@code maxPositionCorrection}（Box2D {@code b2_maxLinearCorrection} = 0.2）：
 *       单步最多修正多少位置，防止「一坨重叠」被一次性弹飞。</li>
 * </ul>
 */
public final class ContactSolver {

    private float[] target = new float[1];

    // ---- 每步统计 ----
    public long impulseApplications;
    public long positionCorrections;
    /** 位置修正后估计的最大残留穿透（只用于诊断/harness 断言，不参与求解）。 */
    public float maxResidualPenetration;

    public void resetStats() {
        impulseApplications = 0L;
        positionCorrections = 0L;
        maxResidualPenetration = 0.0F;
    }

    /**
     * 求解一个子步的全部接触。
     *
     * @param dt 本子步的**固定** dt（秒）
     */
    public void solve(RigidbodySoA bodies, Contacts c, float dt, PhysicsWorld.Settings s) {
        resetStats();
        if (c.count == 0) {
            return;
        }
        if (target.length < c.count) {
            target = new float[c.count * 2];
        }
        float invDt = 1.0F / dt;

        // ---- 0) 预备：每个接触的「目标法向速度」 ----
        // 恢复系数只在接近速度超过阈值时生效（PhysX/Unity 的 restitutionVelocityThreshold）：
        // 否则一个静止的球放在地上会永远以极小的速度弹个不停（经典的「静置抖动」来源）。
        for (int k = 0; k < c.count; k++) {
            int ia = c.a[k];
            int ib = c.b[k];
            float nX = c.nx[k];
            float nY = c.ny[k];
            float nZ = c.nz[k];
            float vax = ia >= 0 ? bodies.velX[ia] : 0.0F;
            float vay = ia >= 0 ? bodies.velY[ia] : 0.0F;
            float vaz = ia >= 0 ? bodies.velZ[ia] : 0.0F;
            float vn = (bodies.velX[ib] - vax) * nX
                    + (bodies.velY[ib] - vay) * nY
                    + (bodies.velZ[ib] - vaz) * nZ;
            float e = 0.0F;
            float rest = c.restitution[k];
            if (rest > 0.0F && vn < -s.restitutionVelocityThreshold) {
                e = -rest * vn;
            }
            float b = 0.0F;
            if (!s.splitImpulse) {
                // Baumgarte：把穿透塞进速度里（会引入能量，所以默认不用）
                b = s.baumgarte * Math.max(0.0F, c.pen[k] - s.slop) * invDt;
            }
            target[k] = e > b ? e : b;
        }

        // ---- 1) 速度迭代：法向冲量 + 摩擦锥 ----
        for (int it = 0; it < s.solverIterations; it++) {
            for (int k = 0; k < c.count; k++) {
                solveNormal(bodies, c, k);
                solveFriction(bodies, c, k);
            }
        }

        // ---- 2) 位置修正（split impulse） ----
        if (s.splitImpulse) {
            for (int it = 0; it < s.positionIterations; it++) {
                for (int k = 0; k < c.count; k++) {
                    solvePosition(bodies, c, k, dt, invDt, s);
                }
            }
            // 伪速度积分成位置：**只动位置，不动速度** —— 不产生动能，所以不抖
            for (int t = 0; t < c.touchedCount; t++) {
                int i = c.touched[t];
                if (!bodies.isDynamic(i)) {
                    continue;
                }
                bodies.posX[i] += c.biasVX[i] * dt;
                bodies.posY[i] += c.biasVY[i] * dt;
                bodies.posZ[i] += c.biasVZ[i] * dt;
            }
            // 估计残留穿透（诊断用）
            for (int k = 0; k < c.count; k++) {
                int ia = c.a[k];
                int ib = c.b[k];
                float sep = (c.biasVX[ib] - (ia >= 0 ? c.biasVX[ia] : 0.0F)) * c.nx[k]
                        + (c.biasVY[ib] - (ia >= 0 ? c.biasVY[ia] : 0.0F)) * c.ny[k]
                        + (c.biasVZ[ib] - (ia >= 0 ? c.biasVZ[ia] : 0.0F)) * c.nz[k];
                float residual = c.pen[k] - sep * dt;
                if (residual > maxResidualPenetration) {
                    maxResidualPenetration = residual;
                }
            }
        }
    }

    /** 法向：累积冲量 + 夹取到 &ge; 0（只有「推」没有「拉」，这是接触约束的本质）。 */
    private void solveNormal(RigidbodySoA bodies, Contacts c, int k) {
        int ia = c.a[k];
        int ib = c.b[k];
        float invA = effectiveInvMass(bodies, ia);
        float invB = effectiveInvMass(bodies, ib);
        float invSum = invA + invB;
        if (invSum <= 0.0F) {
            return;
        }
        float nX = c.nx[k];
        float nY = c.ny[k];
        float nZ = c.nz[k];
        float vax = ia >= 0 ? bodies.velX[ia] : 0.0F;
        float vay = ia >= 0 ? bodies.velY[ia] : 0.0F;
        float vaz = ia >= 0 ? bodies.velZ[ia] : 0.0F;
        float vn = (bodies.velX[ib] - vax) * nX
                + (bodies.velY[ib] - vay) * nY
                + (bodies.velZ[ib] - vaz) * nZ;

        float want = (target[k] - vn) / invSum;
        float old = c.jn[k];
        float acc = old + want;
        if (acc < 0.0F) {
            acc = 0.0F;
        }
        float delta = acc - old;
        c.jn[k] = acc;
        if (delta == 0.0F) {
            return;
        }
        applyImpulse(bodies, ia, -delta * invA, nX, nY, nZ);
        applyImpulse(bodies, ib, delta * invB, nX, nY, nZ);
        impulseApplications++;
    }

    /**
     * 摩擦：切平面两个方向的冲量，合成矢量被夹在**摩擦锥** {@code |jt| ≤ μ·jn} 内。
     *
     * <p>用锥（圆）而不是「每个切向各自夹取」：后者允许 |jt| 达到 √2·μ·jn，
     * 也就是实际摩擦系数比配置的大 41%，表现为「贴墙时莫名其妙蹭不动」。
     */
    private void solveFriction(RigidbodySoA bodies, Contacts c, int k) {
        int ia = c.a[k];
        int ib = c.b[k];
        float invA = effectiveInvMass(bodies, ia);
        float invB = effectiveInvMass(bodies, ib);
        float invSum = invA + invB;
        if (invSum <= 0.0F || c.friction[k] <= 0.0F || c.jn[k] <= 0.0F) {
            return;
        }
        float vax = ia >= 0 ? bodies.velX[ia] : 0.0F;
        float vay = ia >= 0 ? bodies.velY[ia] : 0.0F;
        float vaz = ia >= 0 ? bodies.velZ[ia] : 0.0F;
        float rvx = bodies.velX[ib] - vax;
        float rvy = bodies.velY[ib] - vay;
        float rvz = bodies.velZ[ib] - vaz;

        float vt1 = rvx * c.t1x[k] + rvy * c.t1y[k] + rvz * c.t1z[k];
        float vt2 = rvx * c.t2x[k] + rvy * c.t2y[k] + rvz * c.t2z[k];
        float n1 = c.jt1[k] - vt1 / invSum;
        float n2 = c.jt2[k] - vt2 / invSum;
        float max = c.friction[k] * c.jn[k];
        float mag2 = n1 * n1 + n2 * n2;
        if (mag2 > max * max) {
            float scale = max / (float) StrictMath.sqrt(mag2);
            n1 *= scale;
            n2 *= scale;
        }
        float d1 = n1 - c.jt1[k];
        float d2 = n2 - c.jt2[k];
        c.jt1[k] = n1;
        c.jt2[k] = n2;
        if (d1 == 0.0F && d2 == 0.0F) {
            return;
        }
        float ix = c.t1x[k] * d1 + c.t2x[k] * d2;
        float iy = c.t1y[k] * d1 + c.t2y[k] * d2;
        float iz = c.t1z[k] * d1 + c.t2z[k] * d2;
        applyImpulse(bodies, ia, -invA, ix, iy, iz);
        applyImpulse(bodies, ib, invB, ix, iy, iz);
        impulseApplications++;
    }

    /**
     * 位置修正（split impulse）：用**伪速度**表达「需要分离多少」，钳到 [0, cap]，
     * 其中 {@code cap = positionCorrectionFactor * max(0, pen - slop) / dt}。
     * 因为每次都从当前伪速度重新算目标（而不是累积），所以不会过冲。
     */
    private void solvePosition(RigidbodySoA bodies, Contacts c, int k, float dt, float invDt,
                               PhysicsWorld.Settings s) {
        float excess = c.pen[k] - s.slop;
        if (excess <= 0.0F) {
            return;
        }
        int ia = c.a[k];
        int ib = c.b[k];
        float invA = effectiveInvMass(bodies, ia);
        float invB = effectiveInvMass(bodies, ib);
        float invSum = invA + invB;
        if (invSum <= 0.0F) {
            return;
        }
        float nX = c.nx[k];
        float nY = c.ny[k];
        float nZ = c.nz[k];
        float pvn = (c.biasVX[ib] - (ia >= 0 ? c.biasVX[ia] : 0.0F)) * nX
                + (c.biasVY[ib] - (ia >= 0 ? c.biasVY[ia] : 0.0F)) * nY
                + (c.biasVZ[ib] - (ia >= 0 ? c.biasVZ[ia] : 0.0F)) * nZ;
        float cap = s.positionCorrectionFactor * excess * invDt;
        if (pvn >= cap) {
            return;
        }
        // 单步位置修正上限（Box2D b2_maxLinearCorrection）：把「这一步最多分离多少格」
        // 折算成伪速度上限。防止「一坨重叠」被一次性弹飞（那看起来就是瞬移）。
        float maxDeltaV = s.maxPositionCorrection * invDt;
        float targetPvn = pvn + maxDeltaV;
        if (targetPvn > cap) {
            targetPvn = cap;
        }
        if (targetPvn <= pvn) {
            return;
        }
        float j = (targetPvn - pvn) / invSum;
        c.biasVX[ib] += nX * j * invB;
        c.biasVY[ib] += nY * j * invB;
        c.biasVZ[ib] += nZ * j * invB;
        if (ia >= 0) {
            c.biasVX[ia] -= nX * j * invA;
            c.biasVY[ia] -= nY * j * invA;
            c.biasVZ[ia] -= nZ * j * invA;
        }
        positionCorrections++;
    }

    /**
     * 有效逆质量：睡着的动力学体在**求解期间**当静态处理（{@code invMass = 0}），
     * 除非窄相位已经把它唤醒。
     *
     * <p>为什么这样处理才对：睡眠是一个「优化开关」，不允许出现
     * 「被推着走但状态还是睡着」——那种状态一旦出现，下一 tick 它既不动（休眠跳过积分）
     * 又有速度（残留），表现为「偶尔有单位瞬移一下」。要么醒着正常求解，要么完全不动。
     */
    private static float effectiveInvMass(RigidbodySoA bodies, int i) {
        if (i < 0) {
            return 0.0F;
        }
        return bodies.awake[i] ? bodies.invMass[i] : 0.0F;
    }

    private static void applyImpulse(RigidbodySoA bodies, int i, float scale, float x, float y, float z) {
        if (i < 0 || scale == 0.0F) {
            return;
        }
        bodies.velX[i] += x * scale;
        bodies.velY[i] += y * scale;
        bodies.velZ[i] += z * scale;
    }
}
