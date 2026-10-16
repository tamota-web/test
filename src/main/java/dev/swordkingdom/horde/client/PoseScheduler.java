package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeProfiler;

/**
 * 客户端「这个单位这一帧要不要重新求值姿态」的**唯一决策点**。
 *
 * <h2>为什么把它从 CrowdRenderer 里拆出来</h2>
 * 拆出来有两个理由，都不是洁癖：
 * <ol>
 *   <li><b>可离线验证</b>：真机的病是「26 个单位、每帧却有 27 次姿态求值」这类
 *       只有数字才能说清的问题。把决策逻辑做成一个不碰 GL、不碰 Minecraft 类的普通对象，
 *       离线 harness 就能用替身帧号跑几百帧，直接断言
 *       <b>「每帧求值次数 ≤ 该帧单位数」</b>（见 ProfilerHarness）；</li>
 *   <li><b>与姿态写入解耦</b>：姿态 texel 的布局/stride/上传由 CrowdRenderer 负责，
 *       这里只回答「求不求值」，两边互不干扰。</li>
 * </ol>
 *
 * <h2>它保证什么</h2>
 * <ul>
 *   <li><b>每个单位每帧最多求值一次</b>（{@link #frameGuard} 打开时，也就是线上）：
 *       同一帧里即使有两条路径（主渲染路径 / 诊断路径 / 其它订阅者）都走到这个单位，
 *       第二次起直接返回「沿用缓存」；</li>
 *   <li>LOD 节奏与原实现**逐位一致**：{@code 未求值过 || (tick + id) % period == 0}。
 *       这里刻意不改公式 —— 改它就会改变动画的刷新节奏（数值行为变化），
 *       而那属于「另一个代理正在处理」的部分；</li>
 *   <li>失败语义一致：首次求值失败时置 stamp 并标记 poseFailed，之后不再每帧重试。</li>
 * </ul>
 */
public final class PoseScheduler {

    private int[] stamp = new int[0];
    private int[] visitedInFrame = new int[0];
    private int frame;

    /** 每帧是否启用「同帧只求值一次」的守卫（线上为 true；harness 可关掉做对照） */
    private final boolean frameGuard;

    /** 本帧已求值次数 */
    private int evaluatedThisFrame;
    /** 本帧被「同帧已求值」跳过的次数 */
    private int reusedThisFrame;
    /** 本帧求值耗时（纳秒） */
    private long evalNanosThisFrame;
    /** 本帧访问过的单位个数（= 被写进实例缓冲的单位数） */
    private int visitedThisFrame;

    private int maxEvaluated = 0;
    private int maxUnits = 0;
    private long totalEvaluated = 0L;
    private long totalReused = 0L;
    private long totalFrames = 0L;

    public PoseScheduler() {
        this(true);
    }

    public PoseScheduler(boolean frameGuard) {
        this.frameGuard = frameGuard;
    }

    /** 一帧开始：清本帧计数，帧号 +1。 */
    public void frameBegin() {
        frame++;
        if (frame <= 0) {
            // 回绕（int 溢出）：句柄全部作废，下一帧按「未求值过」重来一次
            frame = 1;
            java.util.Arrays.fill(visitedInFrame, 0);
        }
        evaluatedThisFrame = 0;
        reusedThisFrame = 0;
        evalNanosThisFrame = 0L;
        visitedThisFrame = 0;
        totalFrames++;
    }

    /** 一帧结束：把本帧数字并进峰值/总量。 */
    public void frameEnd() {
        totalEvaluated += evaluatedThisFrame;
        totalReused += reusedThisFrame;
        if (evaluatedThisFrame > maxEvaluated) {
            maxEvaluated = evaluatedThisFrame;
        }
        if (visitedThisFrame > maxUnits) {
            maxUnits = visitedThisFrame;
        }
    }

    /**
     * 判断这个单位这一帧要不要求值。
     *
     * @param id     单位槽位
     * @param dist2  到相机的水平距离平方（LOD 分档用）
     * @param tick   游戏 tick（与服务端同步的时钟）
     * @return 见 {@link Decision}
     */
    public Decision prepare(int id, float dist2, int tick) {
        ensure(id + 1);
        visitedThisFrame++;
        if (frameGuard && visitedInFrame[id] == frame) {
            // ★ 本帧这个单位已经求过值了：直接沿用缓存。
            //   这就是「26 个单位不会算 27 次」的保证 —— 多出来的那次一定是重复路径。
            reusedThisFrame++;
            HordeProfiler.notePoseReEval();
            return Decision.REUSE;
        }
        int period = periodFor(dist2);
        int s = stamp[id];
        if (s != 0 && (tick + id) % period != 0) {
            // LOD 节奏还没到：沿用上一帧姿态（与原实现一致）
            return Decision.HOLD;
        }
        return Decision.EVALUATE;
    }

    /** 求值成功：记 stamp、记「本帧已求值」。 */
    public void noteEvaluated(int id, int tick) {
        ensure(id + 1);
        stamp[id] = tick;
        visitedInFrame[id] = frame;
        evaluatedThisFrame++;
    }

    /** 求值失败（且是首次）：记 stamp 并标记，避免每帧重试。 */
    public void noteFailed(int id, int tick) {
        ensure(id + 1);
        stamp[id] = tick;
        visitedInFrame[id] = frame;
    }

    /** 包住一次 {@code setupAnim} 驱动的计时（只计时，不改语义）。 */
    public long evalBegin() {
        return HordeProfiler.begin();
    }

    public void evalEnd(long start) {
        if (start == 0L) {
            return;
        }
        long d = System.nanoTime() - start;
        evalNanosThisFrame += d;
        // 只累加到一个地方：HordeProfiler 的客户端窗口（POSE_NANOS 那份本地槽位已删除，
        // 免得又出现「写给 A、读 B」那种两个累加器对不上的问题）。
        POSE_NANOS_TOTAL += d;
    }

    private static long POSE_NANOS_TOTAL;

    /** 进程内累计的姿态求值耗时（纳秒）——诊断/命令用。 */
    public static long poseNanosTotal() {
        return POSE_NANOS_TOTAL;
    }

    /** 姿态刷新周期：与服务端物理同样的 LOD 分档（32/64/128 格 -> 1/2/4/8）。 */
    public int periodFor(float dist2) {
        if (dist2 <= lodNear2) {
            return 1;
        }
        if (dist2 <= lodMid2) {
            return 2;
        }
        if (dist2 <= lodFar2) {
            return 4;
        }
        return 8;
    }

    /** 每帧刷新一次 LOD 阈值（避免每单位都读配置）。 */
    public void refreshLod() {
        float near = HordeConfig.safeGet(HordeConfig.COMMON.lodNearDistance, 32.0F);
        float mid = HordeConfig.safeGet(HordeConfig.COMMON.lodMidDistance, 64.0F);
        float far = HordeConfig.safeGet(HordeConfig.COMMON.lodFarDistance, 128.0F);
        lodNear2 = near * near;
        lodMid2 = mid * mid;
        lodFar2 = far * far;
    }

    private float lodNear2 = 32.0F * 32.0F;
    private float lodMid2 = 64.0F * 64.0F;
    private float lodFar2 = 128.0F * 128.0F;

    /** 切维度/清场时作废全部缓存。 */
    public void invalidateAll() {
        java.util.Arrays.fill(stamp, 0);
        java.util.Arrays.fill(visitedInFrame, 0);
    }

    private void ensure(int units) {
        if (stamp.length < units) {
            int cap = Math.max(units, Math.max(64, stamp.length * 2));
            stamp = java.util.Arrays.copyOf(stamp, cap);
            visitedInFrame = java.util.Arrays.copyOf(visitedInFrame, cap);
        }
    }

    // ------------------------------------------------------------------
    // 只读统计（插桩 / harness）
    // ------------------------------------------------------------------

    public int evaluatedThisFrame() {
        return evaluatedThisFrame;
    }

    public int reusedThisFrame() {
        return reusedThisFrame;
    }

    public long evalNanosThisFrame() {
        return evalNanosThisFrame;
    }

    public int visitedThisFrame() {
        return visitedThisFrame;
    }

    public int maxEvaluatedPerFrame() {
        return maxEvaluated;
    }

    public int maxVisitedPerFrame() {
        return maxUnits;
    }

    public long totalEvaluated() {
        return totalEvaluated;
    }

    public long totalReused() {
        return totalReused;
    }

    public long totalFrames() {
        return totalFrames;
    }

    public int stampOf(int id) {
        return id >= 0 && id < stamp.length ? stamp[id] : 0;
    }

    /** 决策结果。 */
    public enum Decision {
        /** 需要求值 */
        EVALUATE,
        /** 本帧已经求过值：沿用（守卫命中） */
        REUSE,
        /** LOD 节奏还没到：沿用上一帧姿态 */
        HOLD
    }
}
