package dev.swordkingdom.horde.crowd.physics;

/**
 * 固定步长时钟 —— 对应 Unity 的 {@code FixedUpdate} + {@code Time.fixedDeltaTime}
 * （以及 {@code Time.maximumDeltaTime} 的死亡螺旋保护）。
 *
 * <h2>为什么必须固定步长（这是整份设计的地基）</h2>
 * 可变 dt 会让物理结果**依赖帧率**：同一个跳跃在 30fps 和 144fps 下高度不同；
 * 接触求解的收敛速度随 dt 变化（dt 越大，一次冲量越"过"）；穿透修正量正比于 1/dt，
 * dt 抖动时修正量跟着抖。对服务端来说更致命：**同一串输入必须得到同一串结果**，
 * 而 dt 一旦与「这一 tick 实际花了多久」挂钩，结果就不可复现了。
 * 所以：
 * <ol>
 *   <li><b>权威步长固定</b>：主步 = 服务端 tick = 20Hz（{@code fixedDeltaTime = 0.05s}），
 *       与帧率/负载**无关**；</li>
 *   <li><b>子步累加器</b>：一个主步内按「本步最大位移」把 0.05s 切成 N 个**等长**子步，
 *       N 只取固定档位 {1,2,4,8}，因此每个子步的 dt 只能是
 *       50/25/12.5/6.25 ms 之一 —— 是**离散档位**，不是随负载连续变化的量；</li>
 *   <li><b>渲染侧只插值</b>：客户端拿 10Hz 快照 + {@code alpha()} 线性插值
 *       （{@code ClientCrowdData.alpha()}），物理层不参与渲染。</li>
 * </ol>
 *
 * <h2>子步档位是怎么定的</h2>
 * 判据是「每个子步的位移不超过 {@code maxDisplacementPerSubStep}（默认 0.5 格）」。
 * 这不是随口定的：接触检测与位置修正都假设「一步内的相对位移小于最小特征尺寸」，
 * 0.5 格小于任何单位的半径（多数单位 ≥ 0.3，玩家 0.3、僵尸 0.3、boss 更大），
 * 于是「穿过对方」在几何上不可能发生。它同时也是 CCD 的兜底：
 * 即使 CCD 被关掉，一步 0.5 格也跨不过一格厚的墙。
 *
 * <h2>累加器与死亡螺旋</h2>
 * {@code beginFrame(realDt)} 把真实时间累加进 accumulator，
 * 但单帧最多补 {@code maxFrameTime}（默认 0.25s = 5 个主步）。
 * 不封顶的话，一次 2 秒的卡顿会让服务端试图补 40 步，卡顿被放大成更长的卡顿（经典的死亡螺旋）。
 * 封顶的代价是「那次卡顿期间世界变慢」——对服务端权威模拟来说这是正确的取舍：
 * 宁可慢，也不能让一个 tick 干 2 秒的活。
 */
public final class FixedStepClock {

    private final float fixedDt;
    private final int maxSubSteps;
    private final float maxFrameTime;

    private float accumulator;
    private int pendingSteps;

    private int lastSubSteps = 1;
    private float lastSubDt;
    private double mainSteps;

    /**
     * @param rateHz      主步频率（服务端权威 = 20）
     * @param maxSubSteps 单主步最多切几个子步（默认 8 → 最小 dt = 6.25ms）
     */
    public FixedStepClock(float rateHz, int maxSubSteps, float maxFrameTime) {
        this.fixedDt = 1.0F / Math.max(1.0F, rateHz);
        this.maxSubSteps = Math.max(1, Integer.highestOneBit(maxSubSteps));
        this.maxFrameTime = Math.max(fixedDt, maxFrameTime);
        this.lastSubDt = fixedDt;
    }

    public float fixedDt() {
        return fixedDt;
    }

    public int maxSubSteps() {
        return maxSubSteps;
    }

    /** 累加真实经过的时间（Unity: 每帧把 {@code Time.deltaTime} 加进累加器）。 */
    public void beginFrame(float realDt) {
        if (!(realDt > 0.0F)) {
            return;
        }
        float clamped = Math.min(realDt, maxFrameTime);
        accumulator += clamped;
        pendingSteps = (int) (accumulator / fixedDt);
        accumulator -= pendingSteps * fixedDt;
        if (pendingSteps < 0) {
            pendingSteps = 0;
        }
    }

    /** 还有主步要跑吗（Unity: {@code while (accumulator >= fixedDeltaTime)}）。 */
    public boolean hasStep() {
        return pendingSteps > 0;
    }

    /** 消费一个主步（子步数由调用方按位移政策决定）。 */
    public void consumeStep(int subSteps) {
        if (pendingSteps > 0) {
            pendingSteps--;
        }
        this.lastSubSteps = Math.max(1, Math.min(maxSubSteps, subSteps));
        this.lastSubDt = fixedDt / this.lastSubSteps;
        mainSteps++;
    }

    /**
     * 按「本主步最大位移」决定子步数：返回 2 的幂档位，使每子步位移 ≤ maxDisplacementPerSubStep。
     *
     * <p>确定性：输入只有「最大位移」这一个标量（由升序归约得出），
     * 因此同一个输入序列必然得到同一个档位序列。
     */
    public int planSubSteps(float maxDisplacementPerTick, float maxDisplacementPerSubStep) {
        if (!(maxDisplacementPerTick > 0.0F)) {
            return 1;
        }
        float threshold = Math.max(0.01F, maxDisplacementPerSubStep);
        int n = 1;
        while (n < maxSubSteps && maxDisplacementPerTick / n > threshold) {
            n <<= 1;
        }
        return n;
    }

    public int lastSubSteps() {
        return lastSubSteps;
    }

    /** **本主步实际用的固定子步 dt**（秒）——只能取 50/25/12.5/6.25 ms 之一。 */
    public float lastSubDt() {
        return lastSubDt;
    }

    /**
     * 渲染插值进度 [0,1]（Unity 的 {@code accumulator / fixedDeltaTime}）。
     *
     * <p>语义上与客户端 {@code ClientCrowdData.alpha()} 完全一致：都是「距离上一帧过去了多少」。
     * 服务端自己需要它的场合是「真实实体傀儡跟随虚拟单位」——那时用同一个 alpha 插值，
     * 傀儡与虚拟单位的视觉位置才能对上。
     */
    public float alpha() {
        float a = accumulator / fixedDt;
        if (a < 0.0F) {
            return 0.0F;
        }
        return a > 1.0F ? 1.0F : a;
    }

    public double mainSteps() {
        return mainSteps;
    }

    /** 未消费的余量（诊断用：长期逼近 fixedDt 说明服务端跟不上）。 */
    public float accumulator() {
        return accumulator;
    }
}
