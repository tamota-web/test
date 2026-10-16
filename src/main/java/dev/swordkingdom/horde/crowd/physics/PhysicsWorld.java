package dev.swordkingdom.horde.crowd.physics;

/**
 * 物理世界门面 —— 对应 Unity 的 {@code Physics} 单例 + 场景里那一份 {@code PhysX} 场景。
 *
 * <h2>每个子步的执行顺序（照抄 PhysX/Unity 的固定管线，并注明两处刻意的差异）</h2>
 * <pre>
 *   1. broadphase   重建均匀网格，发射候选对（确定性顺序）
 *   2. integrate    重力 × gravityScale → 阻尼 → 位置积分（睡着的跳过）
 *   3. narrowphase  胶囊-胶囊 / 单位-体素 生成接触（过滤层与触发体、唤醒传播）
 *   4. solver       顺序冲量：法向 + 摩擦锥 + split impulse 位置修正
 *   5. ccd          高速体从本子步起点扫掠，命中则钳到 TOI 并消掉法向速度
 *   6. sleep        能量低于阈值且持续够久 → 入睡；被推动/受伤 → 唤醒
 * </pre>
 *
 * <p><b>差异 1（顺序）</b>：PhysX 里 CCD 发生在 narrowphase **之前**（因为它要参与接触生成）。
 * 这里放在求解之后，理由是本项目的 CCD 使用者是**投掷物**（阶段 1 的核心场景）：
 * 它需要的是「命中时刻 + 命中点」，拿到就立刻上报（伤害结算），
 * 而不是「让箭继续参与接触求解」。放在最后还有一个好处：求解器的位置修正可能把单位
 * 推进墙里，CCD 在最后扫一遍正好兜住这个洞。
 *
 * <p><b>差异 2（broadphase 的新鲜度）</b>：因为 CCD 在 integrate 之后跑，
 * 它看到的网格是积分**前**的位置。所以所有查询走廊都按
 * {@link Settings#gridQuerySlack()} 外扩（数值见该方法），保证不漏候选。
 * 这不是近似，而是「位移有上界（0.5 格）+ 位置修正有上界（0.2 格）」推出的保守界。
 *
 * <h2>谁负责什么</h2>
 * <ul>
 *   <li>{@link RigidbodySoA}：数据（一个字段一个数组）；</li>
 *   <li>{@link BroadphaseGrid}：谁可能撞谁；</li>
 *   <li>{@link ContactSolver} + {@link Contacts}：撞了之后怎么动；</li>
 *   <li>{@link PhysicsQuery}：谁看得见谁 / 谁打得到谁（阶段 1 的落地口子）；</li>
 *   <li>{@link VoxelSource}：地形是什么形状（生产实现 {@link LevelVoxelSource}）。</li>
 * </ul>
 * 本类**不 import** 任何 {@code dev.swordkingdom.horde.crowd} 里的既有类
 * （{@code CrowdData}/{@code SpatialGrid}/{@code BlockWorld}/{@code CrowdManager}），
 * 目的是可离线独立验证；接线方式见 {@code docs/PHYSICS_WIRING.md}。
 */
public final class PhysicsWorld {

    // ------------------------------------------------------------------
    // 设置：Unity 里散落在 PhysicsManager / Rigidbody / PhysicsMaterial 上的那些默认值
    // ------------------------------------------------------------------

    /**
     * 物理设置。
     *
     * <p>每个字段都标注它的 Unity/PhysX 出处，这样「为什么是这个数」不用猜：
     * {@code Physics.gravity}、{@code Time.fixedDeltaTime}、{@code Physics.defaultContactOffset}、
     * {@code Physics.bounceThreshold}、{@code Physics.sleepThreshold}、
     * {@code Physics.queriesHitTriggers}、Box2D 的 {@code linearSlop}/{@code maxLinearCorrection}
     * （PhysX 里对应 {@code contactOffset}/容差，数值取同一量级）。
     */
    public static final class Settings {

        /** 重力（格/秒²）。Unity: {@code Physics.gravity}。默认 -32 = 原版 0.08 格/tick² × 20² 。 */
        public float gravityY = -32.0F;
        public float gravityX = 0.0F;
        public float gravityZ = 0.0F;

        /** 主步频率。Unity: 1/{@code Time.fixedDeltaTime}。 */
        public float fixedRateHz = 20.0F;
        /** 单主步最多切几个子步（2 的幂档位）。 */
        public int maxSubSteps = 8;
        /** 触发子步细分的「每子步最大位移」（格）。 */
        public float maxDisplacementPerSubStep = 0.5F;
        /** 单帧最多补多少秒的主步（防死亡螺旋）。Unity: {@code Time.maximumDeltaTime}。 */
        public float maxFrameTime = 0.25F;

        /** 接触偏移：距离小于它的两个形状就开始生成接触（推测式接触）。Unity: {@code Collider.contactOffset}。 */
        public float contactOffset = 0.01F;
        /** 允许的残留穿透。没有它位置修正会一直追 0，表现为持续微抖。 */
        public float slop = 0.005F;
        /** Baumgarte 系数（只在 {@link #splitImpulse} 关闭时使用）。 */
        public float baumgarte = 0.2F;
        /**
         * 位置修正比例（split impulse 每步消掉多少超额穿透）。
         *
         * <p>默认 <b>1.0 = 完全修正</b>。为什么不留一点余量：位置修正走的是**伪速度**通道，
         * 完全不产生动能，所以「一次修完」不会抖；反过来说，如果只修 80%，
         * 重力每个子步又会压进来 {@code g·dt²}，两者平衡在一个「稳态穿透」上：
         * <pre>
         *   稳态穿透 = (1-f)/f · g·dt²   →  f=0.8 时 = 0.02 格（slop 的 4 倍，肉眼可见的下沉）
         *                                 →  f=1.0 时 = 0（收敛到 slop）
         * </pre>
         * 这正是「单位站在地上会慢慢陷进去一点」的成因，也是这里取 1.0 的理由。
         */
        public float positionCorrectionFactor = 1.0F;
        /** 单步位置修正上限（格）。Box2D {@code b2_maxLinearCorrection} = 0.2。 */
        public float maxPositionCorrection = 0.2F;
        /** 速度迭代次数（顺序冲量）。 */
        public int solverIterations = 4;
        /** 位置修正迭代次数。 */
        public int positionIterations = 3;
        /** 用 split impulse 做位置修正（**默认开**：它不产生动能，因此不抖）。 */
        public boolean splitImpulse = true;
        /** 恢复系数生效的最小接近速度（格/秒）。Unity: {@code Physics.bounceThreshold}。 */
        public float restitutionVelocityThreshold = 2.0F;

        /** 休眠能量阈值。Unity: {@code Rigidbody.sleepThreshold} 默认 0.005。 */
        public float sleepThreshold = 0.005F;
        /** 「想睡」持续多久才真睡（秒）。PhysX 的 wake counter 默认 0.4~0.5s。 */
        public float minSleepTime = 0.5F;
        /** 全局允许休眠开关（Unity: {@code Physics.autoSimulation} 时代的 Sleep 设置）。 */
        public boolean allowSleep = true;
        /** 接触唤醒的速度门槛（格/秒）：低于它的邻居不会把睡着的单位弄醒。 */
        public float wakeOnContactSpeed = 0.2F;
        /** 被唤醒后至少保持清醒多久（秒）——防止「醒了又睡」的高频切换。 */
        public float minAwakeTime = 0.0F;

        /** 查询默认是否命中触发体。Unity: {@code Physics.queriesHitTriggers}。 */
        public boolean queriesHitTriggers = true;

        /** 单位之间的摩擦系数（缺少每体材质时的默认值）。Unity: {@code PhysicsMaterial.dynamicFriction}。 */
        public float unitFriction = 0.6F;
        /** 单位之间的恢复系数（原版实体互推不弹）。 */
        public float unitRestitution = 0.0F;
        /** 单位接触只取水平法线（从而允许「踩在别人头上」，与原版 {@code Entity.push} 一致）。 */
        public boolean unitContactsXzOnly = true;
        /** 竖直法线的判据：|ny| 超过它就认为是「上下接触」而跳过。 */
        public float verticalContactCos = 0.7F;

        /** 是否为单位生成体素接触（阶段 3 才开；阶段 1/2 仍由 {@code BlockWorld.move} 负责地形）。 */
        public boolean unitVoxelContacts = false;
        /**
         * 哪些**层**的 body 参与体素接触。
         *
         * <p>默认只有单位（{@link PhysicsLayers#UNIT}）：投掷物不参与体素接触求解，
         * 它们靠 CCD 扫掠拿命中时刻就够了（不生成接触，也就不必为它们维护接触流形）。
         */
        public int voxelContactMask = PhysicsLayers.bit(PhysicsLayers.UNIT);

        /** CCD 总开关。 */
        public boolean ccdEnabled = true;
        /** 位移小于它就跳过 CCD（格）。Unity 内部同样有小位移短路。 */
        public float minSweepDistance = 0.05F;
        /** 速度上限（格/秒）：兜底防止任何数值爆炸把单位甩出世界。 */
        public float maxLinearVelocity = 200.0F;

        /** broadphase 基础格子尺寸（格）。 */
        public float broadphaseCellSize = 2.0F;

        /** 默认线性阻尼（格/秒 单位制下）：原版每 tick ×0.98 的等价 PhysX drag。 */
        public float unitDrag = 0.0F;
        /** 默认角阻尼（Unity 默认 0.05）。 */
        public float unitAngularDrag = 0.05F;

        public float gravitySign() {
            return gravityY <= 0.0F ? -1.0F : 1.0F;
        }

        /**
         * 参与「单位-单位」接触的层掩码。
         *
         * <p>刻意不看队伍：原版 {@code Entity.push} 互推也**不分敌我**，
         * 一队人挤在一起时同样会互相推开。敌我只影响「谁能打谁/谁能看见谁」（查询），
         * 不影响「谁能推开谁」（接触）。
         */
        public int unitContactMask() {
            return PhysicsLayers.bit(PhysicsLayers.UNIT);
        }

        /**
         * CCD/查询阶段允许网格「陈旧」多少格。
         *
         * <p>= 每子步最大位移 + 单步最大位置修正。
         * 推导：网格是在 integrate **之前**建的，此后每个 body 最多再移动
         * {@code maxDisplacementPerSubStep}（积分）+ {@code maxPositionCorrection}（求解器位置修正）。
         * 把查询走廊按这个量外扩，候选集就是**保守**的（宁可多给，不可漏给）。
         */
        public float gridQuerySlack() {
            return maxDisplacementPerSubStep + maxPositionCorrection + 0.05F;
        }

        /**
         * 由**原版的每 tick 常量**推出 PhysX 风格的设置（迁移时的等价换算，见 docs/PHYSICS.md）。
         *
         * @param rateHz           主步频率（服务端 = 20）
         * @param gravityPerTick2  原版每 tick 的重力（{@code motionY -= 0.08}）
         * @param dragPerTick      原版每 tick 的速度乘子（地面 0.6×0.91、空中 0.91、垂直 0.98）
         */
        public static Settings vanilla(float rateHz, float gravityPerTick2, float dragPerTick) {
            Settings s = new Settings();
            s.fixedRateHz = rateHz;
            s.gravityY = -gravityPerTick2 * rateHz * rateHz;
            s.unitDrag = vanillaDragToPhysX(dragPerTick, rateHz);
            return s;
        }

        /** 原版 20Hz 默认（重力 0.08 格/tick²、空气阻力 0.98/tick）。 */
        public static Settings vanilla() {
            return vanilla(20.0F, 0.08F, 0.98F);
        }

        /**
         * 每 tick 速度乘子 → PhysX 线性阻尼。
         *
         * <p>推导：PhysX 每步 {@code v *= 1/(1+drag*dt)}，令 {@code dt = 1/rate} 时等于 {@code perTick}：
         * <pre>
         *   1/(1 + drag/rate) = perTick  =>  drag = rate * (1/perTick - 1)
         * </pre>
         * 于是「一个主步 = 一个原版 tick」的阻尼效果**数学上完全相等**（浮点误差 1 ulp 内，
         * 见 physicsHarness 的对照用例）。这条换算让「迁移物理层」不必重新调手感。
         */
        public static float vanillaDragToPhysX(float perTick, float rateHz) {
            if (perTick <= 0.0F || perTick >= 1.0F) {
                return 0.0F;
            }
            return rateHz * (1.0F / perTick - 1.0F);
        }
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    public final Settings settings;
    private final RigidbodySoA bodies;
    private final VoxelSource voxels;
    private final BroadphaseGrid grid;
    private final Contacts contacts = new Contacts();
    private final ContactSolver solver = new ContactSolver();
    private final PhysicsQuery query;
    private final FixedStepClock clock;

    private final float[] nrm = new float[3];
    private final float[] cp1 = new float[3];
    private final float[] cp2 = new float[3];
    private final float[] boxBuf;
    private final int[] overlapScratch = new int[64];

    // 复用的回调（方法引用每次求值都会 new 一个对象，所以存成字段）
    private final BroadphaseGrid.PairConsumer pairHandler = this::onPair;

    // ---- 统计 ----
    public long substeps;
    public int lastSubSteps;
    public float lastSubDt;
    public long integrationCalls;
    public long sleepingSkippedIntegration;
    public long ccdSweeps;
    public long ccdClamps;
    public long wakeOnContact;
    public long slept;
    public long voxelContactTests;

    // ---- CCD 命中记录（每个子步清空；供投掷物命中结算消费） ----
    public static final class CcdHit {
        public int body;
        public int otherBody;
        public boolean voxel;
        public int blockX;
        public int blockY;
        public int blockZ;
        public float t;
        public float pointX;
        public float pointY;
        public float pointZ;
        public float normalX;
        public float normalY;
        public float normalZ;
    }

    private static final int MAX_CCD_HITS = 512;
    private final CcdHit[] ccdHits = new CcdHit[MAX_CCD_HITS];
    private int ccdHitCount;

    public PhysicsWorld(RigidbodySoA bodies, VoxelSource voxels, Settings settings) {
        this.bodies = bodies;
        this.voxels = voxels == null ? VoxelSource.EMPTY : voxels;
        this.settings = settings == null ? new Settings() : settings;
        this.grid = new BroadphaseGrid(this.settings.broadphaseCellSize);
        this.query = new PhysicsQuery(bodies, this.voxels, grid, this.settings);
        this.clock = new FixedStepClock(this.settings.fixedRateHz, this.settings.maxSubSteps,
                this.settings.maxFrameTime);
        this.boxBuf = new float[Math.max(6, this.voxels.maxBoxes() * 6)];
        for (int i = 0; i < MAX_CCD_HITS; i++) {
            ccdHits[i] = new CcdHit();
        }
    }

    public PhysicsWorld(RigidbodySoA bodies) {
        this(bodies, VoxelSource.EMPTY, Settings.vanilla());
    }

    public RigidbodySoA bodies() {
        return bodies;
    }

    public Contacts contacts() {
        return contacts;
    }

    public PhysicsQuery query() {
        return query;
    }

    public BroadphaseGrid broadphase() {
        return grid;
    }

    public FixedStepClock clock() {
        return clock;
    }

    public ContactSolver solver() {
        return solver;
    }

    /** 体素源（只读）。接线层做「投掷物扫掠」时需要它来选 voxel 层。 */
    public VoxelSource voxels() {
        return voxels;
    }

    /**
     * 只扫地形的**盒体扫掠**：把「移动的小方块」当成球心轨迹 + 外接半径来扫（保守，多报不漏报）。
     *
     * <p>用途：投掷物 CCD 的 `Adapter.resolveMove` 只需要「会不会撞墙 + 撞在哪」，
     * 不需要精确的盒角（箭本身很小），所以这里用外接半径换取零分配的 DDA 复用。
     * 若将来要精确的盒体扫掠，把 {@link VoxelSweep} 的 pad 换成逐盒 Minkowski 外扩
     * （{@link Sweep#movingAabb} 已经具备该能力）。
     *
     * @return t ∈ [0,1]，或 {@link Sweep#NO_HIT}；法线写进 {@code outNormal}
     */
    public float sweepVoxelsBox(float px, float py, float pz,
                                float dx, float dy, float dz,
                                float hx, float hy, float hz, float[] outNormal) {
        float pad = Math.max(hx, Math.max(hy, hz));
        return query.voxelSweep().raycast(voxels, px, py, pz, dx, dy, dz, pad, outNormal);
    }

    /** 只扫地形的**球体扫掠**（投掷物命中半径用）。 */
    public float sweepVoxelsSphere(float px, float py, float pz,
                                   float dx, float dy, float dz,
                                   float radius, float[] outNormal) {
        return query.voxelSweep().raycast(voxels, px, py, pz, dx, dy, dz,
                Math.max(0.0F, radius), outNormal);
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /**
     * 推进真实经过的时间（Unity: 一帧里把 {@code deltaTime} 喂进物理，内部按固定步长跑）。
     *
     * @return 本次跑了几个主步（0 = 时间还没攒够一个主步）
     */
    public int step(float frameDt) {
        clock.beginFrame(frameDt);
        int ran = 0;
        while (clock.hasStep()) {
            stepOneTick();
            ran++;
        }
        return ran;
    }

    /**
     * 直接跑**一个主步**：服务端 tick 本身就是固定步长时用它。
     *
     * <p>为什么还要这个入口：{@link #step(float)} 的累加器是为「渲染帧率 ≠ 物理频率」准备的
     * （客户端预览、单机集成的场合）。但服务端权威模拟里 1 tick = 1 主步是**硬约定**，
     * 走累加器只会引入「0.05f 的浮点余量偶尔凑不满一步」这种边界问题（表现为偶发丢步）。
     * 权威路径用这个入口，语义上更直白：一次服务端 tick = 一次固定步长物理推进。
     */
    public void stepOneTick() {
        float tickDt = clock.fixedDt();
        // 按当前状态归约出「本主步最大位移」——它决定子步档位
        float maxDisp = planMaxDisplacement(tickDt);
        int sub = clock.planSubSteps(maxDisp, settings.maxDisplacementPerSubStep);
        // 主步开始：保存插值用的上一步位置（Unity: 插值用的是**主步**之间的位置）
        for (int i = 0; i < bodies.highWater; i++) {
            if (bodies.alive[i]) {
                bodies.prevX[i] = bodies.posX[i];
                bodies.prevY[i] = bodies.posY[i];
                bodies.prevZ[i] = bodies.posZ[i];
            }
        }
        float dt = tickDt / sub;
        for (int k = 0; k < sub; k++) {
            substep(dt);
        }
        clock.consumeStep(sub);
        lastSubSteps = sub;
        lastSubDt = dt;
    }

    /** 一个子步（dt 固定）。顺序即文档里的 broadphase → integrate → narrowphase → solver → ccd → sleep。 */
    public void substep(float dt) {
        substeps++;
        // 1) broadphase：数组复用，不分配
        grid.build(bodies);
        // 2) integrate
        integrate(dt);
        // 3) narrowphase：单位-单位 + （可选）单位-体素
        contacts.beginSubStep();
        contacts.ensure(Math.max(64, bodies.highWater * 2), bodies.capacity);
        grid.forEachPair(bodies, pairHandler);
        if (settings.unitVoxelContacts) {
            narrowphaseVoxels();
        }
        // 4) solver
        solver.solve(bodies, contacts, dt, settings);
        contacts.endSubStep();
        // 5) CCD
        ccd(dt);
        // 6) sleep
        sleepUpdate(dt);
    }

    /** 归约「本主步最大位移」（升序、确定性）。 */
    private float planMaxDisplacement(float tickDt) {
        float max = 0.0F;
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i] || !bodies.isDynamic(i) || !bodies.awake[i]) {
                continue;
            }
            float vx = bodies.velX[i];
            float vy = bodies.velY[i];
            float vz = bodies.velZ[i];
            float v = (float) StrictMath.sqrt(vx * vx + vy * vy + vz * vz);
            if (v > max) {
                max = v;
            }
        }
        return max * tickDt;
    }

    /**
     * 积分：重力 → 力 → 阻尼 → 位置。
     *
     * <p>三处与 Unity/PhysX 对齐的细节：
     * <ol>
     *   <li>阻尼用 {@code v *= 1/(1+drag*dt)}（PhysX 的线性阻尼），
     *       不是 {@code v *= 1 - drag*dt}（后者在 {@code drag*dt > 1} 时会反向加速）；</li>
     *   <li>自由度的冻结（{@link RigidbodyConstraints}）在这里生效：某一轴冻结则该轴不积分；</li>
     *   <li>睡着的体**完全不积分**（{@link #sleepingSkippedIntegration} 计数证明这一点），
     *       这就是休眠省下来的算力。</li>
     * </ol>
     */
    private void integrate(float dt) {
        float gx = settings.gravityX;
        float gy = settings.gravityY;
        float gz = settings.gravityZ;
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i]) {
                continue;
            }
            // 本子步起点：CCD 用它做扫掠起点（积分之后才可能穿墙）
            bodies.stepX[i] = bodies.posX[i];
            bodies.stepY[i] = bodies.posY[i];
            bodies.stepZ[i] = bodies.posZ[i];
            if (!bodies.isDynamic(i) || !bodies.awake[i]) {
                if (bodies.alive[i] && bodies.isDynamic(i) && !bodies.awake[i]) {
                    sleepingSkippedIntegration++;
                }
                continue;
            }
            integrationCalls++;
            int cons = bodies.constraints[i] & 0xFF;
            float vx = bodies.velX[i];
            float vy = bodies.velY[i];
            float vz = bodies.velZ[i];
            // 重力（× gravityScale；0 = 不受重力）
            float gs = bodies.gravityScale[i];
            vx += gx * gs * dt;
            vy += gy * gs * dt;
            vz += gz * gs * dt;
            // 力（Unity: AddForce 累积到这一步）
            float m = bodies.mass[i];
            if (m > 0.0F) {
                vx += bodies.forceX[i] / m * dt;
                vy += bodies.forceY[i] / m * dt;
                vz += bodies.forceZ[i] / m * dt;
            }
            bodies.forceX[i] = 0.0F;
            bodies.forceY[i] = 0.0F;
            bodies.forceZ[i] = 0.0F;
            // 阻尼
            float drag = bodies.drag[i];
            if (drag > 0.0F) {
                float d = 1.0F / (1.0F + drag * dt);
                vx *= d;
                vy *= d;
                vz *= d;
            }
            float adrag = bodies.angularDrag[i];
            if (adrag > 0.0F && !RigidbodyConstraints.rotationFrozen(cons, 0)) {
                float d = 1.0F / (1.0F + adrag * dt);
                bodies.angVelX[i] *= d;
                bodies.angVelY[i] *= d;
                bodies.angVelZ[i] *= d;
            }
            // 速度上限（数值兜底）
            float v2 = vx * vx + vy * vy + vz * vz;
            float maxV = settings.maxLinearVelocity;
            if (v2 > maxV * maxV) {
                float scale = maxV / (float) StrictMath.sqrt(v2);
                vx *= scale;
                vy *= scale;
                vz *= scale;
            }
            bodies.velX[i] = vx;
            bodies.velY[i] = vy;
            bodies.velZ[i] = vz;
            // 位置积分（按轴冻结）
            if (!RigidbodyConstraints.positionFrozen(cons, 0)) {
                bodies.posX[i] += vx * dt;
            } else {
                bodies.velX[i] = 0.0F;
            }
            if (!RigidbodyConstraints.positionFrozen(cons, 1)) {
                bodies.posY[i] += vy * dt;
            } else {
                bodies.velY[i] = 0.0F;
            }
            if (!RigidbodyConstraints.positionFrozen(cons, 2)) {
                bodies.posZ[i] += vz * dt;
            } else {
                bodies.velZ[i] = 0.0F;
            }
        }
    }

    // ------------------------------------------------------------------
    // 窄相位
    // ------------------------------------------------------------------

    /**
     * 单位-单位接触。
     *
     * <p>三件事按顺序做，理由都写在注释里：过滤 → 唤醒传播 → 生成接触。
     */
    private void onPair(int i, int j) {
        // 只有「单位层」之间才产生接触。敌我不影响互推：原版实体互推也不分敌我
        // （Layer 表达类别，队伍掩码表达敌我 —— 见 PhysicsLayers 的说明）
        if (!PhysicsLayers.has(settings.unitContactMask(), bodies.layer[i] & 0xFF)
                || !PhysicsLayers.has(settings.unitContactMask(), bodies.layer[j] & 0xFF)) {
            contacts.skippedLayer++;
            return;
        }
        if (bodies.isTrigger[i] || bodies.isTrigger[j]) {
            return;   // Unity: 触发器不产生接触
        }
        // ---- 唤醒传播（PhysX 的 wake-on-touch）----
        boolean awakeI = bodies.awake[i];
        boolean awakeJ = bodies.awake[j];
        if (!awakeI || !awakeJ) {
            if (!awakeI && awakeJ && bodies.isDynamic(j) && speedSq(j) > wakeSpeedSq()) {
                bodies.wake(i);
                awakeI = true;
                wakeOnContact++;
            } else if (!awakeJ && awakeI && bodies.isDynamic(i) && speedSq(i) > wakeSpeedSq()) {
                bodies.wake(j);
                awakeJ = true;
                wakeOnContact++;
            }
        }
        if (!awakeI && !awakeJ) {
            contacts.skippedSleeping++;
            return;
        }
        // ---- 胶囊-胶囊 ----
        float pen = ShapeMath.capsuleCapsulePenetration(
                bodies.posX[i], bodies.capsuleBottomY(i), bodies.posZ[i],
                bodies.posX[i], bodies.capsuleTopY(i), bodies.posZ[i], bodies.radius[i],
                bodies.posX[j], bodies.capsuleBottomY(j), bodies.posZ[j],
                bodies.posX[j], bodies.capsuleTopY(j), bodies.posZ[j], bodies.radius[j],
                (i * 73856093) ^ (j * 19349663), nrm, cp1, cp2);
        if (pen <= -settings.contactOffset) {
            return;
        }
        if (settings.unitContactsXzOnly
                && (nrm[1] > settings.verticalContactCos || nrm[1] < -settings.verticalContactCos)) {
            // 上下接触：原版允许「踩在别人头上」，所以不做竖直分离
            return;
        }
        // 接触点取两轴最近点的中点
        float px = (cp1[0] + cp2[0]) * 0.5F;
        float py = (cp1[1] + cp2[1]) * 0.5F;
        float pz = (cp1[2] + cp2[2]) * 0.5F;
        // 材质合成用平均值（Unity PhysicsMaterial 默认 Combine = Average）
        float fric = (bodies.friction[i] + bodies.friction[j]) * 0.5F;
        float rest = (bodies.restitution[i] + bodies.restitution[j]) * 0.5F;
        contacts.touch(i);
        contacts.touch(j);
        contacts.add(i, j, nrm[0], nrm[1], nrm[2], px, py, pz, pen, fric, rest);
    }

    private float wakeSpeedSq() {
        return settings.wakeOnContactSpeed * settings.wakeOnContactSpeed;
    }

    private float speedSq(int i) {
        float vx = bodies.velX[i];
        float vy = bodies.velY[i];
        float vz = bodies.velZ[i];
        return vx * vx + vy * vy + vz * vz;
    }

    /**
     * 单位-体素接触（阶段 3 的开关，默认关）。
     *
     * <p>形状上刻意用**单位自己的 AABB**（而不是胶囊）去和方块盒子求 MTV：
     * 这样地形行为与现有 {@code BlockWorld} 的逐轴解算**形状一致**，
     * 迁移时不会出现「换了个物理层，台阶高度/贴墙滑动手感变了」这种回归。
     * 胶囊只用于「单位之间」与「查询」——那两处本来就不是原版的形状。
     */
    private void narrowphaseVoxels() {
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i] || !bodies.isDynamic(i) || !bodies.awake[i]
                    || bodies.isTrigger[i] || !bodies.isCollidable(i)) {
                continue;
            }
            if (!PhysicsLayers.has(settings.voxelContactMask, bodies.layer[i] & 0xFF)) {
                continue;
            }
            float minX = bodies.minX(i);
            float minY = bodies.minY(i);
            float minZ = bodies.minZ(i);
            float maxX = bodies.maxX(i);
            float maxY = bodies.maxY(i);
            float maxZ = bodies.maxZ(i);
            int x0 = (int) StrictMath.floor(minX);
            int x1 = (int) StrictMath.floor(maxX);
            int y0 = (int) StrictMath.floor(minY);
            int y1 = (int) StrictMath.floor(maxY);
            int z0 = (int) StrictMath.floor(minZ);
            int z1 = (int) StrictMath.floor(maxZ);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int n = voxels.boxes(x, y, z, boxBuf, 0);
                        for (int k = 0; k < n; k++) {
                            int b = k * 6;
                            float bx0 = x + boxBuf[b];
                            float by0 = y + boxBuf[b + 1];
                            float bz0 = z + boxBuf[b + 2];
                            float bx1 = x + boxBuf[b + 3];
                            float by1 = y + boxBuf[b + 4];
                            float bz1 = z + boxBuf[b + 5];
                            voxelContactTests++;
                            if (!ShapeMath.aabbOverlap(minX, minY, minZ, maxX, maxY, maxZ,
                                    bx0, by0, bz0, bx1, by1, bz1)) {
                                continue;
                            }
                            if (!aabbMtv(minX, minY, minZ, maxX, maxY, maxZ,
                                    bx0, by0, bz0, bx1, by1, bz1, nrm)) {
                                continue;
                            }
                            float pen = mtvDepth;
                            float px = (Math.max(minX, bx0) + Math.min(maxX, bx1)) * 0.5F;
                            float py = (Math.max(minY, by0) + Math.min(maxY, by1)) * 0.5F;
                            float pz = (Math.max(minZ, bz0) + Math.min(maxZ, bz1)) * 0.5F;
                            contacts.touch(i);
                            contacts.add(Contacts.STATIC, i, nrm[0], nrm[1], nrm[2],
                                    px, py, pz, pen, bodies.friction[i], bodies.restitution[i]);
                        }
                    }
                }
            }
        }
    }

    private float mtvDepth;

    /**
     * AABB-AABB 的最小平移向量：把 {@code nrm} 写成「把第一个盒子推出去」的方向，返回深度。
     * 与 {@link ShapeMath#aabbMinTranslation} 同语义，只是对象换成了盒子。
     */
    private boolean aabbMtv(float minX1, float minY1, float minZ1, float maxX1, float maxY1, float maxZ1,
                            float minX2, float minY2, float minZ2, float maxX2, float maxY2, float maxZ2,
                            float[] out) {
        float overlapX = Math.min(maxX1, maxX2) - Math.max(minX1, minX2);
        float overlapY = Math.min(maxY1, maxY2) - Math.max(minY1, minY2);
        float overlapZ = Math.min(maxZ1, maxZ2) - Math.max(minZ1, minZ2);
        if (overlapX <= 0.0F || overlapY <= 0.0F || overlapZ <= 0.0F) {
            return false;
        }
        float cx1 = (minX1 + maxX1) * 0.5F;
        float cy1 = (minY1 + maxY1) * 0.5F;
        float cz1 = (minZ1 + maxZ1) * 0.5F;
        float cx2 = (minX2 + maxX2) * 0.5F;
        float cy2 = (minY2 + maxY2) * 0.5F;
        float cz2 = (minZ2 + maxZ2) * 0.5F;
        if (overlapX <= overlapY && overlapX <= overlapZ) {
            out[0] = cx1 < cx2 ? -1.0F : 1.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            mtvDepth = overlapX;
            return true;
        }
        if (overlapY <= overlapZ) {
            out[0] = 0.0F;
            out[1] = cy1 < cy2 ? -1.0F : 1.0F;
            out[2] = 0.0F;
            mtvDepth = overlapY;
            return true;
        }
        out[0] = 0.0F;
        out[1] = 0.0F;
        out[2] = cz1 < cz2 ? -1.0F : 1.0F;
        mtvDepth = overlapZ;
        return true;
    }

    // ------------------------------------------------------------------
    // CCD
    // ------------------------------------------------------------------

    /**
     * 连续碰撞检测：把「本子步的位移」当成一次扫掠。
     *
     * <p><b>为什么必须有它</b>：{@code CONTINUOUS_DYNAMIC} 的物体（箭 3 格/tick）在某些子步里
     * 位移大于墙厚，离散重叠检测在「起点墙前、终点墙后」时完全看不到墙（隧穿）。
     * 扫掠给的是**命中时刻**，与位移大小无关。
     *
     * <p>命中后做三件事：钳到 TOI、消掉法向速度（否则下一子步继续往里钻）、
     * 记录一条 {@link CcdHit} 供上层（投掷物伤害结算）消费。
     */
    private void ccd(float dt) {
        ccdHitCount = 0;
        if (!settings.ccdEnabled) {
            return;
        }
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i] || !bodies.isDynamic(i) || !bodies.awake[i]) {
                continue;
            }
            CollisionDetectionMode mode = CollisionDetectionMode.byId(bodies.ccd[i] & 0xFF);
            if (!mode.sweeps()) {
                continue;
            }
            float px = bodies.stepX[i];
            float py = bodies.stepY[i];
            float pz = bodies.stepZ[i];
            float dx = bodies.posX[i] - px;
            float dy = bodies.posY[i] - py;
            float dz = bodies.posZ[i] - pz;
            float len2 = dx * dx + dy * dy + dz * dz;
            float minDist = settings.minSweepDistance;
            if (len2 < minDist * minDist) {
                continue;
            }
            ccdSweeps++;
            int mask = PhysicsLayers.bit(PhysicsLayers.TERRAIN);
            if (mode.sweepsDynamic()) {
                mask |= PhysicsLayers.ALL & ~PhysicsLayers.bit(PhysicsLayers.TERRAIN);
            }
            // 查询 API 的语义是「方向（内部归一化）× maxDistance」（与 Unity 一致），
            // 所以这里必须把**本子步位移的长度**当 maxDistance 传进去 —— 传 1.0 只会扫 1 格。
            float len = (float) StrictMath.sqrt(len2);
            query.setIgnoreBody(i);
            boolean hit = query.sphereCast(px, py, pz, bodies.radius[i], dx, dy, dz, len,
                    mask, QueryTriggerInteraction.IGNORE, queryHit);
            query.setIgnoreBody(-1);
            if (!hit) {
                continue;
            }
            float t = queryHit.t;
            float hx = px + dx * t;
            float hy = py + dy * t;
            float hz = pz + dz * t;
            float nX = queryHit.normalX;
            float nY = queryHit.normalY;
            float nZ = queryHit.normalZ;
            // 停在接触点再退一点点（contactOffset），避免下一子步从「已经重叠」开始
            float back = settings.contactOffset;
            bodies.posX[i] = hx - nX * back;
            bodies.posY[i] = hy - nY * back;
            bodies.posZ[i] = hz - nZ * back;
            float vn = bodies.velX[i] * nX + bodies.velY[i] * nY + bodies.velZ[i] * nZ;
            if (vn < 0.0F) {
                float e = bodies.restitution[i];
                float scale = (1.0F + e);
                bodies.velX[i] -= nX * vn * scale;
                bodies.velY[i] -= nY * vn * scale;
                bodies.velZ[i] -= nZ * vn * scale;
            }
            ccdClamps++;
            if (ccdHitCount < MAX_CCD_HITS) {
                CcdHit h = ccdHits[ccdHitCount++];
                h.body = i;
                h.otherBody = queryHit.body;
                h.voxel = queryHit.isVoxel;
                h.blockX = queryHit.blockX;
                h.blockY = queryHit.blockY;
                h.blockZ = queryHit.blockZ;
                h.t = t;
                h.pointX = hx;
                h.pointY = hy;
                h.pointZ = hz;
                h.normalX = nX;
                h.normalY = nY;
                h.normalZ = nZ;
            }
        }
    }

    private final RaycastHit queryHit = new RaycastHit();

    public int ccdHitCount() {
        return ccdHitCount;
    }

    public CcdHit ccdHit(int k) {
        return ccdHits[k];
    }

    // ------------------------------------------------------------------
    // 休眠
    // ------------------------------------------------------------------

    /**
     * 休眠状态机（PhysX 的 wake counter + Unity 的 {@code sleepThreshold}）。
     *
     * <p>为什么需要：一个 10k 单位的战场，绝大多数单位在绝大多数 tick 里是**站着不动**的。
     * 它们仍然会被积分（重力、阻尼、位置）并被塞进 broadphase/求解器。
     * 休眠让「不动的」彻底退出积分与求解，把每 tick 的成本压到与**活跃单位数**成正比，
     * 而不是与总数成正比。
     *
     * <p>为什么不会改变结果：睡着的体速度已经是 0（能量的定义就是速度的函数），
     * 而所有可能改变它的入口（施加冲量、受伤、接触唤醒、传送、AI 主动唤醒）都在
     * {@link RigidbodySoA#wake} 里被显式覆盖。测试用例（physicsHarness 的
     * {@code sleepStateMachine}）就是逐条验证这些入口。
     */
    private void sleepUpdate(float dt) {
        if (!settings.allowSleep) {
            return;
        }
        for (int i = 0; i < bodies.highWater; i++) {
            if (!bodies.alive[i] || !bodies.isDynamic(i) || !bodies.awake[i] || bodies.kinematic[i]) {
                continue;
            }
            if (!bodies.allowSleep[i]) {
                continue;
            }
            float energy = bodies.energy(i);
            float threshold = bodies.sleepThreshold[i];
            if (threshold <= 0.0F || energy > threshold) {
                bodies.sleepTimer[i] = 0.0F;
                continue;
            }
            bodies.sleepTimer[i] += dt;
            if (bodies.sleepTimer[i] >= settings.minSleepTime) {
                bodies.sleep(i);
                slept++;
            }
        }
    }

    // ------------------------------------------------------------------
    // 便捷配置（把 Unity 的字段映射写在一处，接线代码就不必重复这套映射）
    // ------------------------------------------------------------------

    /**
     * 把一个槽位配成「群集单位」。参数全部来自原版现成的属性：
     * {@code EntityType.getDimensions().width/height}（体型）与
     * {@code LivingEntity.getAttributeValue(KNOCKBACK_RESISTANCE)}（击退抗性）。
     */
    public void configureUnit(int slot, float width, float height, float knockbackResistance,
                              int team, int teamMask) {
        bodies.setCapsuleFromEntitySize(slot, width, height);
        bodies.setMass(slot, RigidbodySoA.massFromEntitySize(width, height, knockbackResistance));
        bodies.setDrag(slot, settings.unitDrag, settings.unitAngularDrag);
        bodies.setGravityScale(slot, 1.0F);
        bodies.setConstraints(slot, RigidbodyConstraints.UNIT_DEFAULT);
        bodies.setInterpolation(slot, RigidbodyInterpolation.INTERPOLATE);
        bodies.setCollisionDetection(slot, CollisionDetectionMode.DISCRETE);
        bodies.setSleepThreshold(slot, settings.sleepThreshold);
        bodies.setLayer(slot, PhysicsLayers.UNIT);
        bodies.setTeam(slot, team, teamMask);
        bodies.setMaterial(slot, settings.unitFriction, settings.unitRestitution);
        bodies.allowSleep[slot] = true;
    }

    /** 把一个槽位配成「高速投掷物」：CCD 开满、无摩擦、可穿透由上层处理。 */
    public void configureProjectile(int slot, float radius, CollisionDetectionMode mode) {
        bodies.setCapsule(slot, radius, radius * 2.0F);
        bodies.setMass(slot, 1.0F);
        bodies.setKinematic(slot, true);
        bodies.setConstraints(slot, RigidbodyConstraints.FREEZE_ALL);
        bodies.setCollisionDetection(slot, mode);
        bodies.setLayer(slot, PhysicsLayers.PROJECTILE);
        bodies.setMaterial(slot, 0.0F, 0.0F);
        bodies.allowSleep[slot] = false;
    }

    // ------------------------------------------------------------------
    // 插值（服务端自己按 alpha 取值，与客户端 ClientCrowdData.alpha() 同一语义）
    // ------------------------------------------------------------------

    public float interpolatedX(int i, float alpha) {
        return bodies.prevX[i] + (bodies.posX[i] - bodies.prevX[i]) * alpha;
    }

    public float interpolatedY(int i, float alpha) {
        return bodies.prevY[i] + (bodies.posY[i] - bodies.prevY[i]) * alpha;
    }

    public float interpolatedZ(int i, float alpha) {
        return bodies.prevZ[i] + (bodies.posZ[i] - bodies.prevZ[i]) * alpha;
    }

    /** 全体唤醒（传送、重生成、世界切换这类「一切都可能变了」的场合）。 */
    public void wakeAll() {
        for (int i = 0; i < bodies.highWater; i++) {
            if (bodies.alive[i]) {
                bodies.wake(i);
            }
        }
    }

    /** 卡顿归因用的可读摘要（日志里一行说清「这一步干了什么」）。 */
    public String describeStep() {
        return String.format(java.util.Locale.ROOT,
                "substeps=%d lastSubDt=%.5f gridCells=%d pairs=%d contacts=%d (static=%d sleep=%d layer=%d) "
                        + "impulses=%d posFix=%d ccd=%d clamp=%d woke=%d slept=%d",
                substeps, lastSubDt, grid.cellCount(), grid.pairTests(), contacts.generated,
                contacts.staticContacts, contacts.skippedSleeping, contacts.skippedLayer,
                solver.impulseApplications, solver.positionCorrections, ccdSweeps, ccdClamps,
                wakeOnContact, slept);
    }

    /** 重叠查询的共享小缓冲（避免调用方每次自己 new 一个 int[]）。 */
    public int[] overlapScratch() {
        return overlapScratch;
    }
}
