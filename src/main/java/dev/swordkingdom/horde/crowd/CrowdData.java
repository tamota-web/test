package dev.swordkingdom.horde.crowd;

/**
 * 群集数据体 —— 结构数组（Structure of Arrays，SoA）。
 *
 * 所有单位字段存放在连续的基本类型数组里，而不是「每个单位一个对象」。
 * 好处：内存连续 -> CPU 缓存命中率高 -> 遍历上万单位也不会成为瓶颈。
 *
 * 物理/动画字段严格对齐原版 LivingEntity / Entity 的语义，
 * 因此可以做到与原版僵尸一致的重力、摩擦与动作。
 */
public final class CrowdData {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_MOVING = 1;
    public static final byte STATE_ATTACKING = 2;

    public final int capacity;

    /** 当前存活数量 */
    public int alive;
    /** 已分配过的最高槽位 */
    public int highWater;

    // ---- 位置 / 朝向 ----
    public final float[] posX;
    public final float[] posY;
    public final float[] posZ;
    public final float[] yaw;

    /** 上一 tick 的位置，用于原版 calculateEntityAnimation */
    public final float[] prevX;
    public final float[] prevZ;

    // ---- 原版 deltaMovement ----
    public final float[] velX;
    public final float[] velY;
    public final float[] velZ;
    public final boolean[] onGround;
    public final boolean[] horizontalCollision;

    // ---- 生命 / 阵营 ----
    public final float[] hp;
    public final float[] maxHp;
    public final byte[] team;
    /** 使用的实体类型索引（对应 CrowdManager 的类型表） */
    public final byte[] typeIndex;
    public final boolean[] aliveFlag;
    public final byte[] state;

    // ---- 原版 WalkAnimationState ----
    public final float[] walkPos;
    public final float[] walkSpeed;

    // ---- 原版攻击挥动 updateSwingTime ----
    public final int[] swingTime;
    public final boolean[] swinging;

    // ---- 原版受击闪红 ----
    public final int[] hurtTime;

    // ---- 原版头部朝向（度）----
    public final float[] headYaw;
    public final float[] headPitch;

    // ---- 存活 tick 数（原版 tickCount，用于手臂细微摆动）----
    public final int[] age;

    /** 攻击冷却倒计时 */
    public final int[] attackCd;

    // ---- 动作/技能（对应实体的 getAnimation/setAnimation + getAnimationTick）----
    /** 正在播放的动作编号：0 = 无，k = 该实体第 k 个动画 */
    public final byte[] action;
    /** 该动作已播放的 tick 数 */
    public final short[] actionTick;
    /** 技能施放倒计时（带抖动，避免全服同一 tick 一起放招） */
    public final int[] skillCd;

    /** 当前锁定的敌人槽位（-1 表示无目标） */
    public final int[] targetId;
    /** 重新索敌倒计时（错峰，避免同一 tick 全服扫描） */
    public final int[] retargetCd;

    /** 空闲槽位栈 */
    private final int[] freeStack;
    private int freeTop;

    public CrowdData(int capacity) {
        this.capacity = capacity;
        this.posX = new float[capacity];
        this.posY = new float[capacity];
        this.posZ = new float[capacity];
        this.yaw = new float[capacity];
        this.prevX = new float[capacity];
        this.prevZ = new float[capacity];
        this.velX = new float[capacity];
        this.velY = new float[capacity];
        this.velZ = new float[capacity];
        this.onGround = new boolean[capacity];
        this.horizontalCollision = new boolean[capacity];
        this.hp = new float[capacity];
        this.maxHp = new float[capacity];
        this.team = new byte[capacity];
        this.typeIndex = new byte[capacity];
        this.aliveFlag = new boolean[capacity];
        this.state = new byte[capacity];
        this.walkPos = new float[capacity];
        this.walkSpeed = new float[capacity];
        this.swingTime = new int[capacity];
        this.swinging = new boolean[capacity];
        this.hurtTime = new int[capacity];
        this.headYaw = new float[capacity];
        this.headPitch = new float[capacity];
        this.age = new int[capacity];
        this.attackCd = new int[capacity];
        this.action = new byte[capacity];
        this.actionTick = new short[capacity];
        this.skillCd = new int[capacity];
        this.targetId = new int[capacity];
        this.retargetCd = new int[capacity];
        this.freeStack = new int[capacity];
        this.freeTop = -1;
    }

    /** 申请一个槽位，返回索引；容量已满返回 -1。 */
    public int alloc() {
        if (freeTop >= 0) {
            return freeStack[freeTop--];
        }
        if (highWater < capacity) {
            return highWater++;
        }
        return -1;
    }

    /** 释放槽位。 */
    public void free(int slot) {
        if (!aliveFlag[slot]) {
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
}
