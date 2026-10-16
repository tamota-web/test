package dev.swordkingdom.horde.crowd;

/**
 * 虚拟投掷物的数据体 —— 与 {@link CrowdData} 同一种设计：结构数组（SoA）。
 *
 * 为什么必须也是 SoA：投掷物是**每 tick 都要积分**的东西，一旦多起来
 * （一次技能几百发、全场几千发），「每发一个对象」的分配与缓存抖动会直接吃掉 TPS。
 * 这里全部字段都是连续基本类型数组，整个模拟过程零对象分配。
 *
 * 字段语义**对齐原版 AbstractArrow / ThrowableProjectile / AbstractHurlingProjectile**：
 *   - 位置是**实体中心**（不是 AABB 最小角）—— 投掷物的碰撞盒很小，
 *     原版本身也是拿中心点做射线检测（{@code ProjectileUtil.getEntityHitResult}），
 *     所以这里保存中心既省一次换算，也和原版判定一致；
 *   - velX/Y/Z 就是原版 {@code deltaMovement}；
 *   - life 对应原版 {@code tickCount}、lifeLeft 对应 {@code life}（剩余寿命）。
 */
public final class ProjectileData {

    public final int capacity;

    /** 当前存活数量 */
    public int alive;
    /** 已分配过的最高槽位 */
    public int highWater;

    // ---- 位置 / 速度（原版 deltaMovement）----
    public final float[] posX;
    public final float[] posY;
    public final float[] posZ;
    public final float[] velX;
    public final float[] velY;
    public final float[] velZ;
    /**
     * 上一 tick 的**真实位置**（扫描起点），用于扫掠命中。
     *
     * <h2>为什么不能用 {@code pos - vel} 反推</h2>
     * 命中结算里原来写的是 {@code prev = pos - vel}，但 {@code vel} 在这一步已经被
     * ①重力/阻力的积分结果、②方块碰撞解算方的削峰、③撞墙卡住后的清零 改过了 ——
     * 于是反推出来的「上一 tick 位置」与现实无关：
     * <ul>
     *   <li>撞墙卡住的箭 {@code vel = 0} ⇒ 扫描线段退化成一个点，贴墙的单位再也打不到；</li>
     *   <li>沿墙滑动时 {@code vel} 少了被削掉的分量 ⇒ 线段比真实轨迹短，高速弹会「穿人而过」。</li>
     * </ul>
     * 现在由 {@link ProjectileManager#step} 在积分**之前**把真实位置写进这三个字段，
     * 命中判定用的一定是「这一 tick 真正飞过的那一段」。
     */
    public final float[] prevX;
    public final float[] prevY;
    public final float[] prevZ;

    // ---- 归属 ----
    /** 投掷物自身的类型索引（对应 ProjectileManager 的类型表，与群集单位类型表**分开**） */
    public final byte[] typeIndex;
    /** 发射者的群集单位槽位（-1 = 不是群集单位发的） */
    public final int[] ownerSlot;
    /** 发射者的阵营；命中判定用它排除友军 */
    public final byte[] team;
    /** 发射者的**群集类型**索引（服务端用来做「每个类型自己的投掷物上限」） */
    public final byte[] casterType;

    // ---- 结算参数（生成时从配置快照下来，之后改配置不影响在飞的这一发）----
    public final float[] damage;
    /** 命中判定半径（格） */
    public final float[] hitRadius;
    /** 命中后的 AoE 半径（0 = 无 AoE） */
    public final float[] blastRadius;
    /** AoE 伤害倍率（对命中目标之外的溅射） */
    public final float[] blastScale;
    /** 每 tick 重力（0 = 直线飞行，带加速的火球） */
    public final float[] gravity;
    /** 每 tick 空气阻力（原版箭 = 0.99，投掷物 = 0.98，火球 = 1.0） */
    public final float[] drag;
    /** 击退强度 */
    public final float[] knockback;
    /** 剩余可穿透次数（每命中一次减 1；< 0 表示命中即消失） */
    public final byte[] pierce;
    /** 撞到方块后是否卡住（false = 直接消失） */
    public final boolean[] stickOnBlock;

    // ---- 生命周期 ----
    public final int[] life;
    public final int[] lifeLeft;
    public final boolean[] aliveFlag;

    /** 空闲槽位栈 */
    private final int[] freeStack;
    private int freeTop;

    public ProjectileData(int capacity) {
        this.capacity = capacity;
        this.posX = new float[capacity];
        this.posY = new float[capacity];
        this.posZ = new float[capacity];
        this.velX = new float[capacity];
        this.velY = new float[capacity];
        this.velZ = new float[capacity];
        this.prevX = new float[capacity];
        this.prevY = new float[capacity];
        this.prevZ = new float[capacity];
        this.typeIndex = new byte[capacity];
        this.ownerSlot = new int[capacity];
        this.team = new byte[capacity];
        this.casterType = new byte[capacity];
        this.damage = new float[capacity];
        this.hitRadius = new float[capacity];
        this.blastRadius = new float[capacity];
        this.blastScale = new float[capacity];
        this.gravity = new float[capacity];
        this.drag = new float[capacity];
        this.knockback = new float[capacity];
        this.pierce = new byte[capacity];
        this.stickOnBlock = new boolean[capacity];
        this.life = new int[capacity];
        this.lifeLeft = new int[capacity];
        this.aliveFlag = new boolean[capacity];
        this.freeStack = new int[capacity];
        this.freeTop = -1;
    }

    /** 申请槽位；满了返回 -1。 */
    public int alloc() {
        if (freeTop >= 0) {
            int slot = freeStack[freeTop--];
            aliveFlag[slot] = true;
            alive++;
            return slot;
        }
        if (highWater < capacity) {
            int slot = highWater++;
            aliveFlag[slot] = true;
            alive++;
            return slot;
        }
        return -1;
    }

    /** 回收槽位（幂等）。 */
    public void free(int slot) {
        if (slot < 0 || slot >= highWater || !aliveFlag[slot]) {
            return;
        }
        aliveFlag[slot] = false;
        alive--;
        if (freeTop + 1 < freeStack.length) {
            freeStack[++freeTop] = slot;
        }
    }

    public boolean isAlive(int slot) {
        return slot >= 0 && slot < highWater && aliveFlag[slot];
    }

    public void reset() {
        for (int i = 0; i < highWater; i++) {
            aliveFlag[i] = false;
        }
        alive = 0;
        highWater = 0;
        freeTop = -1;
    }

    /**
     * 该槽位的朝向（度）：由速度方向推出来 —— 投掷物没有独立的 yaw 字段，原版也是靠速度决定朝向的。
     *
     * <b>符号很关键</b>：本模组（以及原版 {@code LivingEntityRenderer}）的朝向约定是
     * <pre>
     *   yaw = 0   -> 朝 +Z
     *   yaw = 90  -> 朝 -X      （即 forward(yaw) = (-sin yaw, ·, cos yaw)）
     * </pre>
     * 所以反解就是 {@code atan2(-velX, velZ)}，与
     * {@link dev.swordkingdom.horde.action.SkillParams#launchVelocity} 严格互逆。
     * 这里曾经写成 {@code atan2(velX, velZ)}（少了负号），后果是「箭往东飞却朝西」——
     * 与 {@code CrowdManager} 里 {@code aimHead} / {@code rotlerp} 用的约定正好相反。
     */
    public float yawOf(int slot) {
        float x = velX[slot];
        float z = velZ[slot];
        if (x * x + z * z < 1.0E-8F) {
            return 0.0F;
        }
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    /** 该槽位的俯仰（度，正 = 抬头）：渲染时用来让模型跟着抛物线转。 */
    public float pitchOf(int slot) {
        float x = velX[slot];
        float z = velZ[slot];
        float horiz = (float) Math.sqrt(x * x + z * z);
        if (horiz < 1.0E-6F && Math.abs(velY[slot]) < 1.0E-6F) {
            return 0.0F;
        }
        return (float) Math.toDegrees(Math.atan2(velY[slot], horiz));
    }
}
