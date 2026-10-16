package dev.swordkingdom.horde.crowd.physics;

/**
 * 刚体数据的 SoA（结构数组）容器 —— 对应 Unity 的 <b>Rigidbody</b> 组件。
 *
 * <h2>为什么还是 SoA，而不是「每个单位一个 Rigidbody 对象」</h2>
 * Unity 里 {@code Rigidbody} 是挂在 GameObject 上的对象。本项目的规模是**上万**单位，
 * 对象化的代价是：一次 tick 里上万个对象头 + 指针追逐 + 缓存全崩。
 * 所以这里把 {@code Rigidbody} 的每个字段摊平成数组：
 * 「第 i 个单位的 mass」= {@code mass[i]}。字段顺序与命名刻意跟 Unity 的
 * {@code Rigidbody} 一一对齐（{@code mass/drag/angularDrag/gravityScale/isKinematic/
 * constraints/interpolation/collisionDetectionMode/sleepThreshold}），
 * 于是「Unity 里怎么写，这里就怎么读」这条映射是**可以逐字段查表**的，
 * 而不是靠注释维系的口头约定。
 *
 * <h2>坐标约定（最容易错的地方）</h2>
 * {@link #posX}/{@link #posY}/{@link #posZ} 是**碰撞体中心**（Unity 的 {@code transform.position}
 * 对居中的 Collider 就是这个位置）。而现有 {@code CrowdData.posX/posY/posZ} 存的是
 * **AABB 最小角**（{@code BlockWorld} 的原版解算习惯）。接线时两条换算必须显式写出：
 * <pre>
 *   center.x = crowd.posX + halfWidth
 *   center.y = crowd.posY + height * 0.5
 *   crowd.posX = center.x - halfWidth
 * </pre>
 * 混用会表现为「单位整体偏移半个身位」并有 1 ulp 级的贴地抖动 —— 这是迁移时第一个要盯的坑。
 *
 * <h2>单位制（第二个最容易错的地方）</h2>
 * 这里的时间单位是**秒**、速度是**格/秒**（与 Unity/PhysX 一致），
 * 而现有 {@code CrowdData.velX/velY/velZ} 是**格/tick**。换算见
 * {@link PhysicsWorld.Settings#vanilla}（它把原版每 tick 的重力/阻力换算成
 * PhysX 的 gravity/drag，并在 harness 里逐位对照）。
 */
public final class RigidbodySoA {

    public int capacity;
    /** 已分配过的最高槽位（与 {@code CrowdData.highWater} 同义）。 */
    public int highWater;

    public boolean[] alive;

    // ---- 位姿（中心点） ----
    public float[] posX;
    public float[] posY;
    public float[] posZ;
    /** 上一步（上一主步）位置：渲染/网络插值用（Unity: RigidbodyInterpolation）。 */
    public float[] prevX;
    public float[] prevY;
    public float[] prevZ;
    /** 本子步开始前的位置：CCD 扫掠的起点（积分之后才可能穿墙）。 */
    public float[] stepX;
    public float[] stepY;
    public float[] stepZ;

    // ---- 运动 ----
    public float[] velX;
    public float[] velY;
    public float[] velZ;
    public float[] angVelX;
    public float[] angVelY;
    public float[] angVelZ;
    /** 累积力（Unity: {@code AddForce} 在 FixedUpdate 之间累加，积分后清零）。 */
    public float[] forceX;
    public float[] forceY;
    public float[] forceZ;

    // ---- 质量与阻尼（Unity Rigidbody 的同名字段） ----
    public float[] mass;
    public float[] invMass;
    /** 线性阻尼（Unity {@code drag}）。PhysX 形式：每步 {@code v *= 1/(1+drag*dt)}。 */
    public float[] drag;
    /** 角阻尼（Unity {@code angularDrag}）。 */
    public float[] angularDrag;
    /** 重力缩放（Unity {@code gravityScale}）；0 = 不受重力（等价 {@code useGravity = false}）。 */
    public float[] gravityScale;

    // ---- 碰撞体（Unity CapsuleCollider） ----
    /** 胶囊半径 = 实体宽度/2。 */
    public float[] radius;
    /** 胶囊**总高**（Unity 的 CapsuleCollider.height 同样是总高）。 */
    public float[] height;
    public boolean[] isTrigger;

    // ---- 标志位 ----
    /** 运动学：不受重力/力/阻尼影响，但**会推别人**（Unity {@code isKinematic}）。 */
    public boolean[] kinematic;
    /** 自由度约束（{@link RigidbodyConstraints}）。 */
    public byte[] constraints;
    public byte[] interpolation;
    public byte[] ccd;

    // ---- 休眠（Unity {@code sleepThreshold} + PhysX 的 wake counter） ----
    /** 能量阈值：单位质量动能低于它就进入「想睡」状态。 */
    public float[] sleepThreshold;
    /** 「想睡」已持续了多少秒（>= minSleepTime 才真睡）。 */
    public float[] sleepTimer;
    public boolean[] awake;
    public boolean[] allowSleep;

    // ---- 碰撞过滤（Unity Layer + 本项目的队伍轴） ----
    public byte[] layer;
    public byte[] team;
    public int[] teamMask;

    // ---- 材质 ----
    public float[] friction;
    public float[] restitution;

    public RigidbodySoA(int capacity) {
        resize(capacity);
    }

    private void resize(int cap) {
        this.capacity = cap;
        alive = new boolean[cap];
        posX = new float[cap];
        posY = new float[cap];
        posZ = new float[cap];
        prevX = new float[cap];
        prevY = new float[cap];
        prevZ = new float[cap];
        stepX = new float[cap];
        stepY = new float[cap];
        stepZ = new float[cap];
        velX = new float[cap];
        velY = new float[cap];
        velZ = new float[cap];
        angVelX = new float[cap];
        angVelY = new float[cap];
        angVelZ = new float[cap];
        forceX = new float[cap];
        forceY = new float[cap];
        forceZ = new float[cap];
        mass = new float[cap];
        invMass = new float[cap];
        drag = new float[cap];
        angularDrag = new float[cap];
        gravityScale = new float[cap];
        radius = new float[cap];
        height = new float[cap];
        isTrigger = new boolean[cap];
        kinematic = new boolean[cap];
        constraints = new byte[cap];
        interpolation = new byte[cap];
        ccd = new byte[cap];
        sleepThreshold = new float[cap];
        sleepTimer = new float[cap];
        awake = new boolean[cap];
        allowSleep = new boolean[cap];
        layer = new byte[cap];
        team = new byte[cap];
        teamMask = new int[cap];
        friction = new float[cap];
        restitution = new float[cap];
    }

    /**
     * 扩容（保留旧数据）。与 {@code CrowdData} 一样只增不减。
     *
     * <p>用 {@code Arrays.copyOf} 逐字段复制而不是「整体换对象」：
     * 物理层持有的是**数组引用**，换对象会让外部已经拿到的引用指向旧数组 ——
     * 这类错误在真机上表现为「扩容之后一部分单位突然不动了」，极难定位。
     */
    public void ensureCapacity(int cap) {
        if (cap <= capacity) {
            return;
        }
        alive = java.util.Arrays.copyOf(alive, cap);
        posX = java.util.Arrays.copyOf(posX, cap);
        posY = java.util.Arrays.copyOf(posY, cap);
        posZ = java.util.Arrays.copyOf(posZ, cap);
        prevX = java.util.Arrays.copyOf(prevX, cap);
        prevY = java.util.Arrays.copyOf(prevY, cap);
        prevZ = java.util.Arrays.copyOf(prevZ, cap);
        stepX = java.util.Arrays.copyOf(stepX, cap);
        stepY = java.util.Arrays.copyOf(stepY, cap);
        stepZ = java.util.Arrays.copyOf(stepZ, cap);
        velX = java.util.Arrays.copyOf(velX, cap);
        velY = java.util.Arrays.copyOf(velY, cap);
        velZ = java.util.Arrays.copyOf(velZ, cap);
        angVelX = java.util.Arrays.copyOf(angVelX, cap);
        angVelY = java.util.Arrays.copyOf(angVelY, cap);
        angVelZ = java.util.Arrays.copyOf(angVelZ, cap);
        forceX = java.util.Arrays.copyOf(forceX, cap);
        forceY = java.util.Arrays.copyOf(forceY, cap);
        forceZ = java.util.Arrays.copyOf(forceZ, cap);
        mass = java.util.Arrays.copyOf(mass, cap);
        invMass = java.util.Arrays.copyOf(invMass, cap);
        drag = java.util.Arrays.copyOf(drag, cap);
        angularDrag = java.util.Arrays.copyOf(angularDrag, cap);
        gravityScale = java.util.Arrays.copyOf(gravityScale, cap);
        radius = java.util.Arrays.copyOf(radius, cap);
        height = java.util.Arrays.copyOf(height, cap);
        isTrigger = java.util.Arrays.copyOf(isTrigger, cap);
        kinematic = java.util.Arrays.copyOf(kinematic, cap);
        constraints = java.util.Arrays.copyOf(constraints, cap);
        interpolation = java.util.Arrays.copyOf(interpolation, cap);
        ccd = java.util.Arrays.copyOf(ccd, cap);
        sleepThreshold = java.util.Arrays.copyOf(sleepThreshold, cap);
        sleepTimer = java.util.Arrays.copyOf(sleepTimer, cap);
        awake = java.util.Arrays.copyOf(awake, cap);
        allowSleep = java.util.Arrays.copyOf(allowSleep, cap);
        layer = java.util.Arrays.copyOf(layer, cap);
        team = java.util.Arrays.copyOf(team, cap);
        teamMask = java.util.Arrays.copyOf(teamMask, cap);
        friction = java.util.Arrays.copyOf(friction, cap);
        restitution = java.util.Arrays.copyOf(restitution, cap);
        capacity = cap;
    }

    // ------------------------------------------------------------------
    // 槽位生命周期
    // ------------------------------------------------------------------

    /** 分配一个槽位（复用 {highWater} 空位）。 */
    public int alloc() {
        int i = highWater;
        if (i >= capacity) {
            ensureCapacity(Math.max(64, capacity * 2));
        }
        highWater = i + 1;
        alive[i] = true;
        applyDefaults(i);
        return i;
    }

    public void free(int i) {
        alive[i] = false;
        awake[i] = false;
        velX[i] = velY[i] = velZ[i] = 0.0F;
        angVelX[i] = angVelY[i] = angVelZ[i] = 0.0F;
    }

    /**
     * 一个槽位刚启用时的默认值（**Unity 新建 Rigidbody 的默认值**）：
     * mass 1、drag 0、angularDrag 0.05、gravityScale 1、无约束、离散检测、可休眠。
     *
     * <p>之所以要「恢复默认」而不是留着上一次的残值：槽位是复用的
     * （一个单位死了、下一只生成在同一槽位），残值会让「上一只的尺寸/阻尼/速度」
     * 悄悄继承给新单位 —— 这类 bug 在真机上表现为「偶尔有单位一发疯」。
     */
    public void applyDefaults(int i) {
        mass[i] = 1.0F;
        invMass[i] = 1.0F;
        drag[i] = 0.0F;
        angularDrag[i] = 0.05F;
        gravityScale[i] = 1.0F;
        radius[i] = 0.3F;
        height[i] = 1.8F;
        isTrigger[i] = false;
        kinematic[i] = false;
        constraints[i] = (byte) RigidbodyConstraints.UNIT_DEFAULT;
        interpolation[i] = (byte) RigidbodyInterpolation.INTERPOLATE.ordinal();
        ccd[i] = (byte) CollisionDetectionMode.DISCRETE.ordinal();
        sleepThreshold[i] = 0.005F;
        sleepTimer[i] = 0.0F;
        awake[i] = true;
        allowSleep[i] = true;
        layer[i] = (byte) PhysicsLayers.UNIT;
        team[i] = 0;
        teamMask[i] = PhysicsLayers.ALL;
        friction[i] = 0.6F;
        restitution[i] = 0.0F;
        velX[i] = velY[i] = velZ[i] = 0.0F;
        angVelX[i] = angVelY[i] = angVelZ[i] = 0.0F;
        forceX[i] = forceY[i] = forceZ[i] = 0.0F;
    }

    // ------------------------------------------------------------------
    // 读写便捷方法（链式，接线代码好读）
    // ------------------------------------------------------------------

    public RigidbodySoA setPose(int i, float x, float y, float z) {
        posX[i] = x;
        posY[i] = y;
        posZ[i] = z;
        prevX[i] = x;
        prevY[i] = y;
        prevZ[i] = z;
        stepX[i] = x;
        stepY[i] = y;
        stepZ[i] = z;
        return this;
    }

    public RigidbodySoA setVelocity(int i, float x, float y, float z) {
        velX[i] = x;
        velY[i] = y;
        velZ[i] = z;
        return this;
    }

    public RigidbodySoA setCapsule(int i, float radius, float height) {
        this.radius[i] = Math.max(0.01F, radius);
        this.height[i] = Math.max(2.0F * this.radius[i], height);
        return this;
    }

    /**
     * 由原版实体尺寸建胶囊：{@code EntityType.getDimensions()} 的 width/height
     * （{@code LivingEntity} 的 {@code getBbWidth()/getBbHeight()}）。
     */
    public RigidbodySoA setCapsuleFromEntitySize(int i, float width, float height) {
        return setCapsule(i, width * 0.5F, height);
    }

    /** 质量：&le; 0 表示静态（无限质量，等价于没有 Rigidbody 的静态碰撞体）。 */
    public RigidbodySoA setMass(int i, float m) {
        mass[i] = m;
        invMass[i] = (m <= 0.0F || kinematic[i]) ? 0.0F : 1.0F / m;
        return this;
    }

    public RigidbodySoA setKinematic(int i, boolean v) {
        kinematic[i] = v;
        invMass[i] = (v || mass[i] <= 0.0F) ? 0.0F : 1.0F / mass[i];
        if (v) {
            awake[i] = true;   // 运动学体永远「醒着」（它自己会动，也可能推别人）
            sleepTimer[i] = 0.0F;
        }
        return this;
    }

    public RigidbodySoA setConstraints(int i, int mask) {
        constraints[i] = (byte) mask;
        return this;
    }

    public RigidbodySoA setInterpolation(int i, RigidbodyInterpolation v) {
        interpolation[i] = (byte) v.ordinal();
        return this;
    }

    public RigidbodySoA setCollisionDetection(int i, CollisionDetectionMode v) {
        ccd[i] = (byte) v.ordinal();
        return this;
    }

    public RigidbodySoA setSleepThreshold(int i, float energy) {
        sleepThreshold[i] = Math.max(0.0F, energy);
        return this;
    }

    public RigidbodySoA setLayer(int i, int layer) {
        this.layer[i] = (byte) layer;
        return this;
    }

    /**
     * 阵营与「我能和哪些阵营发生查询关系」的掩码。
     * 与 Layer 的关系见 {@link PhysicsLayers}：层是类别，队伍是敌我，两条轴正交。
     */
    public RigidbodySoA setTeam(int i, int team, int teamMask) {
        this.team[i] = (byte) team;
        this.teamMask[i] = teamMask;
        return this;
    }

    public RigidbodySoA setMaterial(int i, float friction, float restitution) {
        this.friction[i] = friction;
        this.restitution[i] = restitution;
        return this;
    }

    public RigidbodySoA setDrag(int i, float drag, float angularDrag) {
        this.drag[i] = Math.max(0.0F, drag);
        this.angularDrag[i] = Math.max(0.0F, angularDrag);
        return this;
    }

    public RigidbodySoA setGravityScale(int i, float scale) {
        gravityScale[i] = scale;
        return this;
    }

    // ------------------------------------------------------------------
    // 唤醒 / 休眠
    // ------------------------------------------------------------------

    /** 该 body 是不是动力学（会被力/接触推动）。 */
    public boolean isDynamic(int i) {
        return invMass[i] > 0.0F;
    }

    /** 触发体：参与查询、不参与接触（Unity 的 {@code Collider.isTrigger}）。 */
    public boolean isTrigger(int i) {
        return isTrigger[i];
    }

    /** 能不能进 broadphase 参与接触：活着、不是触发体（静态体也算，它们要被别人撞）。 */
    public boolean isCollidable(int i) {
        return alive[i] && !isTrigger[i];
    }

    /**
     * 唤醒。被推动、受伤、索敌成功、被传送、被施加冲量时必须调用 ——
     * 这是「休眠只是省算力，不改变结果」的唯一保证。
     */
    public void wake(int i) {
        if (!alive[i]) {
            return;
        }
        awake[i] = true;
        sleepTimer[i] = 0.0F;
    }

    /** 立即入睡（速度清零）。只是状态切换，动能本来就已经低于阈值。 */
    public void sleep(int i) {
        awake[i] = false;
        sleepTimer[i] = 0.0F;
        velX[i] = velY[i] = velZ[i] = 0.0F;
        angVelX[i] = angVelY[i] = angVelZ[i] = 0.0F;
    }

    /** 单位质量的动能（Unity 的 sleepThreshold 就是拿它比的）。 */
    public float energy(int i) {
        float lv = velX[i] * velX[i] + velY[i] * velY[i] + velZ[i] * velZ[i];
        float av = angVelX[i] * angVelX[i] + angVelY[i] * angVelY[i] + angVelZ[i] * angVelZ[i];
        return 0.5F * (lv + av);
    }

    /** 施加冲量（Unity: {@code AddForce(impulse, ForceMode.VelocityChange)}）——**会唤醒**。 */
    public void addImpulse(int i, float jx, float jy, float jz) {
        if (!alive[i] || invMass[i] <= 0.0F) {
            return;
        }
        wake(i);
        velX[i] += jx;
        velY[i] += jy;
        velZ[i] += jz;
    }

    /** 冲量（按质量换算成速度变化；Unity 的默认 {@code AddForce} 语义）。 */
    public void addImpulseForce(int i, float jx, float jy, float jz) {
        addImpulse(i, jx * invMass[i], jy * invMass[i], jz * invMass[i]);
    }

    /** 累加力（Unity: {@code AddForce}，下一个固定步生效）——**会唤醒**。 */
    public void addForce(int i, float fx, float fy, float fz) {
        if (!alive[i] || invMass[i] <= 0.0F) {
            return;
        }
        wake(i);
        forceX[i] += fx;
        forceY[i] += fy;
        forceZ[i] += fz;
    }

    // ------------------------------------------------------------------
    // 几何便捷访问
    // ------------------------------------------------------------------

    /**
     * 胶囊轴起点（下球心）Y。
     *
     * <p><b>位置语义</b>：{@link #posY} 与 X/Z 一样是**碰撞体中心**（Unity 的
     * {@code transform.position}）。所以下球心 = 中心 − 总高/2 + 半径。
     * 三条轴必须同语义，否则会出现「X/Z 是中心、Y 是底边」这种半身位偏移 ——
     * 那是这类 SoA 物理层最经典的隐蔽 bug。
     */
    public float capsuleBottomY(int i) {
        return posY[i] - height[i] * 0.5F + radius[i];
    }

    /** 胶囊轴终点（上球心）Y。总高比直径还小时退化成球。 */
    public float capsuleTopY(int i) {
        float bottom = capsuleBottomY(i);
        float top = posY[i] + height[i] * 0.5F - radius[i];
        return top < bottom ? bottom : top;
    }

    public float minX(int i) {
        return posX[i] - radius[i];
    }

    public float maxX(int i) {
        return posX[i] + radius[i];
    }

    public float minY(int i) {
        return posY[i] - height[i] * 0.5F;
    }

    public float maxY(int i) {
        return posY[i] + height[i] * 0.5F;
    }

    public float minZ(int i) {
        return posZ[i] - radius[i];
    }

    public float maxZ(int i) {
        return posZ[i] + radius[i];
    }

    /**
     * 由实体尺寸与击退抗性推导质量（与 {@code CrowdManager.pushEntities} 里
     * 现行的 {@code sepMass} 公式**同形**，让迁移前后「谁挤得动谁」不发生变化）：
     * <pre>
     *   mass = max(0.1, width * height) * (1 + 4 * min(1, knockbackResistance))
     * </pre>
     * 数据来源都是原版现成的：{@code EntityType.getDimensions().width/height}（体型）、
     * {@code LivingEntity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)}（击退抗性）。
     */
    public static float massFromEntitySize(float width, float height, float knockbackResistance) {
        float kb = knockbackResistance < 0.0F ? 0.0F : (knockbackResistance > 1.0F ? 1.0F : knockbackResistance);
        return Math.max(0.1F, width * height) * (1.0F + 4.0F * kb);
    }
}
