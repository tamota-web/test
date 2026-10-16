package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.action.ActionDriver;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * GeckoLib 群集单位的**姿态采样表** —— 「烘焙期采样 + 运行期查表」里的那张表。
 *
 * <h2>为什么必须查表，而不是运行时驱动模型</h2>
 * {@code GeoModel} 自带 {@code lastRenderedInstance / animTime / currentModel / AnimationProcessor}
 * 这些**每实例状态**，而群集里成千上万个单位**共用一个模型实例**。逐单位去驱动它必然互相污染
 * （后一个单位把前一个的姿态覆盖掉，同一帧的姿态取决于遍历顺序）。所以：
 * <ul>
 *   <li><b>烘焙期</b>（每个类型一次）：让该模组**自己的渲染器**把傀儡实体渲染一次 ——
 *       我们要的不是画面，而是让 GeckoLib 自己把骨骼姿态算出来，然后立刻用
 *       {@link ModelRig.Part} 的 {@code rotX/Y/Z()}、{@code offX/Y/Z()}、{@code scaleX/Y/Z()}
 *       把每根骨骼的 9 个 float 读回来存进表里；</li>
 *   <li><b>运行期</b>（每单位每次）：只做「按输入查表 + 相邻样本线性插值」，一个模型方法都不调。</li>
 * </ul>
 *
 * <h2>表结构（两个维度：相位 × 时间）</h2>
 * <pre>
 *   walk：    [WALK_PHASES][walkTime][bones][9]   limbSwing 相位铺满 [0,2π)（amount = 1）
 *             或 [WALK_PHASES][1][bones][9]      只随相位变（该模型没有时间成分时降成 1 维）
 *   idle：    [idleTime][bones][9]               amount = 0（站立/待机），**沿时间轴**采样
 *   action[a]：[ACTION_PHASES][bones][9]         第 a 个动作，actionTick 0 .. ACTION_DURATION_TICKS-1
 * </pre>
 * 内存 = 行数 × 骨骼数 × 9 × 4 字节。<b>上限</b> {@link #MAX_TABLE_BYTES}（4 MiB）在采样**之前**
 * 就核算，超了按「先砍时间维、再整表弃用」的顺序降级（见 {@link #build}），而不是等 OOM。
 *
 * <h2>为什么必须有「时间」这条轴（这一版的核心修复）</h2>
 * GeckoLib 的动画有两类驱动量：
 * <ul>
 *   <li><b>相位驱动</b>：{@code limbSwing}/{@code limbSwingAmount}（走路、跑步）；</li>
 *   <li><b>时间驱动</b>：{@code AnimationState.animationTick = GeoModel.animTime}，
 *       一个**按帧真实流逝累加**的时钟（待机呼吸、摆头、尾巴摇摆都是这类）。</li>
 * </ul>
 * 上一版只沿相位采样、且待机样本全在 {@code limbSwingAmount = 0} 下采，于是待机姿态与相位无关，
 * 8 个样本被压成 1 行 —— 站着不动的单位就被永久冻在那一帧上（真机表现就是「一动不动」）。
 * 所以现在：<b>待机维度沿时间轴采</b>，运行期用原版 {@code tickCount}（我们同步过来的 {@code age}）
 * 取模索引。走路维度也顺带探测时间成分（同一个相位、两个不同 tickCount 采两次比对），
 * 有成分才升成「相位 × 时间」二维，并复用**同一个时间轴映射**，这样 amount 插值两端的时间相位一致。
 *
 * <h2>行走与待机为什么要分两组样本</h2>
 * 原版把行走动画的强度编码在 {@code limbSwingAmount} 里，而 {@code limbSwing} 是**相位**。
 * 直接按相位采样、再用 amount 去缩放角度是错的（旋转不是线性的）。但 amount 的两个端点姿态
 * 可以精确给出：{@code pose(amount=0)} 与 {@code pose(amount=1)}。所以这里有「待机组」，
 * 运行期按 amount 在这两组之间插值 —— 端点精确、中间单调，够用且不会抖。
 *
 * <h2>退化约定（采样失败绝不能拖垮烘焙）</h2>
 * 渲染器形状不符、渲染抛异常、样本塌缩（说明 GeckoLib 的每帧动画被 {@code lastRenderedInstance}
 * 或「时间没变」挡住，采到的其实是静止姿态）……任何一种都**不抛异常**：返回不可用的表
 * （{@link #usable()} == false，{@link #reason()} 说明原因），由调用方退回绑定姿态并打 WARN，
 * 行为与「B 档之前」完全一致。
 */
public final class GeoPoseTable {

    /** 行走相位样本数（把 [0, 2π) 均分）—— 64 个样本时「相邻两样本线性插值」的误差已远低于 1e-3 */
    public static final int WALK_PHASES = 64;
    /**
     * 待机（limbSwingAmount = 0）样本数，**沿时间轴**铺满一个周期。
     *
     * 待机姿态与相位无关（行走相位在 amount=0 时对姿态没有影响），所以这一维不再浪费在相位上，
     * 全部用来描述「时间」。32 个样本 / 160 tick = 每 5 tick 一个样本，
     * 对 GeckoLib 里常见的 2~4 秒呼吸、摆头周期（40~80 tick）来说，相邻样本间的线性插值误差够小。
     */
    public static final int IDLE_TIME_SAMPLES = 32;
    /**
     * 时间维覆盖的 tick 数（周期）。
     *
     * <b>为什么是保守常量而不是「从该模组的动画定义里读循环长度」</b>：动画定义在
     * {@code BakedAnimations} 里，长度只有在渲染器真的跑起来之后才拿得到，而我们要在这一步之前
     * 就把表尺寸算清楚（否则就是「采到一半发现超限」）。所以取一个覆盖绝大多数待机循环的保守值
     * （160 tick = 8 秒），并且**首尾闭合**（表会多存一行 = 第 0 行的副本，见 {@link #build}），
     * 于是运行期 {@code age % 周期} 环绕时不会有跳变；即使真实循环长度不等于它，也只是
     * 「按 160 tick 采样再首尾相接」，最坏在接缝处有一次极小的不连续，而不是静止。
     */
    public static final int IDLE_PERIOD_TICKS = 160;
    /** 时间维相邻样本相隔多少 tick（160 / 32 = 5，整除，保证首尾正好闭合） */
    public static final int IDLE_STEP_TICKS = IDLE_PERIOD_TICKS / IDLE_TIME_SAMPLES;
    /**
     * 走路表升成二维时，时间轴上取几个样本。
     *
     * <b>必须与待机表用同一个时间轴</b>（同一个周期、同一个步长、同一个样本数）：
     * 运行期只有**一个** age 输入，两张表要按同一个「age -> 表内时间位置」映射去索引，
     * 否则 amount 在两者之间插值时，两边的待机成分会处在不同的动画时刻（走路动画会以错误的速度播）。
     * 曾经为了省内存把走路的时间轴取稀（每 10 tick），结果就是动画被压缩成两倍速、误差 0.2 —— 这是个真 bug。
     */
    public static final int WALK_TIME_SAMPLES = IDLE_TIME_SAMPLES;
    /** 走路表时间轴的步长（与待机表一致，见上） */
    public static final int WALK_TIME_STRIDE = IDLE_STEP_TICKS;
    /** 每个动作的 tick 相位样本数 */
    public static final int ACTION_PHASES = 16;
    /** 动作动画在表里覆盖的 tick 数：actionTick 超过它就折回（真实动作一般 20~60 tick） */
    public static final int ACTION_DURATION_TICKS = 16;
    /** 动作数上限：超出的动作不采样（运行期落到行走/待机姿态） */
    public static final int MAX_ACTIONS = 8;
    /** 采样表内存上限（字节）。一个类型一张表，这里限制的是单张表 */
    public static final long MAX_TABLE_BYTES = 4L * 1024L * 1024L;
    /** 每骨骼 9 个 float（与 {@link CrowdPose#STRIDE} 一致） */
    public static final int FLOATS = CrowdPose.STRIDE;
    /**
     * 「走路有没有时间成分」的判据阈值：同一相位、相隔 {@link #WALK_TIME_STRIDE} 个 tick 采两次，
     * 逐 float 最大偏差超过它就认为该模型的时间成分会被烘进走路姿态（于是走路表升二维）。
     *
     * 取 1e-4 而不是更大：GeckoLib 的姿态是 float 精确计算出来的，纯粹的相位动画在不同 tickCount 下
     * 结果应当**逐位相同**；只要有一点时间成分（呼吸叠加）就会远超这个量级。
     */
    public static final float WALK_TIME_EPS = 1.0E-4F;

    // ------------------------------------------------------------------
    // 外部接口
    // ------------------------------------------------------------------

    /**
     * 采样源：把「摆好某维度的状态，并让模型自己算好一次姿态」抽象出来。
     *
     * 生产实现是「调该模组的渲染器渲染一次傀儡实体」（见 {@link #fromRenderer}），
     * 离线验证里是「直接调替身模型自己的 setupAnim」—— 表的组装/插值逻辑与姿态来源无关，
     * 所以这一层是能被离线验证的关键。
     */
    public interface SampleSource {
        /**
         * @param state 这一行要采的采样状态（相位/强度/动作/时钟都由 {@link #build} 给出，
         *              实现方只负责「摆到傀儡上 + 让模型算一次 + 读骨骼」）
         * @param out   长度 = 骨骼数 × {@link #FLOATS}，按骨骼顺序写 9 个 float
         *              （rotX,rotY,rotZ,offX,offY,offZ,scaleX,scaleY,scaleZ）
         * @return 是否采样成功（false 的这一行不会被采用）
         */
        boolean read(State state, float[] out);

        /**
         * 采样源的**动画驱动统计**（没有显式驱动时返回 null）。
         *
         * 为什么要把它挂在这一层：{@link #build} 要能区分「这个模型根本没有时间成分（纯相位动画，
         * 正常）」与「我们驱动了动画器但它压根没被推动（该类型不支持采样）」——
         * 后者必须明确报出来并退回绑定姿态，而不是留下 2048 个相同样本。
         * 做成 default 方法是为了不动既有实现（离线用例的采样源直接 setupAnim，没有驱动）。
         */
        default GeoAnimator.DriveStatus driveStatus() {
            return null;
        }
    }

    /**
     * 一次采样的完整状态。
     *
     * {@code dimension} 用来选傀儡（0 = 行走，1 = 待机，2+ = 第 dimension-1 个动作）：
     * GeckoLib 的每帧动画需要「时间真的在往前走」，而且不同维度的时间原点必须分开，
     * 否则会采出跳帧的姿态。
     *
     * {@code clock} 是**该维度自己的 age（时间轴位置，单位 tick）**—— 表里的时间维就是按它索引的
     * （运行期换成原版 {@code tickCount % 周期}）。它**不是**要写进傀儡的值：生产路径会把
     * age 翻译成单调的 tickCount（见 {@link TimeSampler}），离线路径直接把它当 tick 用。
     */
    public static final class State {
        public final int dimension;
        public final float limbSwing;
        public final float walkAmount;
        public final float headYaw;
        public final float headPitch;
        public final int actionIndex;
        public final int actionTick;
        public final int clock;

        public State(int dimension, float limbSwing, float walkAmount, float headYaw, float headPitch,
                     int actionIndex, int actionTick, int clock) {
            this.dimension = dimension;
            this.limbSwing = limbSwing;
            this.walkAmount = walkAmount;
            this.headYaw = headYaw;
            this.headPitch = headPitch;
            this.actionIndex = actionIndex;
            this.actionTick = actionTick;
            this.clock = clock;
        }
    }

    /** 采样前把「群集单位的当前状态」写到傀儡实体上 */
    public interface StateApplier {
        /**
         * @param walkAmount limbSwingAmount（1 = 行走样本，0 = 待机样本）
         * @param tick       要写进 {@code puppet.tickCount} 的值。必须**单调递增**，
         *                   否则 GeckoLib 会因为「时间没变」把整帧动画跳过；
         *                   它的**增量**必须等于样本的 age 增量（生产路径由 {@link TimeSampler} 保证）
         */
        void apply(LivingEntity puppet, float limbSwing, float walkAmount, float headYaw, float headPitch,
                   int actionIndex, int actionTick, int tick);
    }

    /** 采样不到时的空表：只输出绑定姿态（旋转 0、位移 0、缩放 1） */
    public static final GeoPoseTable EMPTY =
            new GeoPoseTable(null, 0, 1, null, 0, 1, null, 0L, null);

    // ------------------------------------------------------------------
    // 表本体
    // ------------------------------------------------------------------

    private final float[] walk;
    private final int walkRows;
    /** 走路表时间轴的样本数（1 = 只有相位维，没升二维） */
    private final int walkTime;
    private final float[] idle;
    private final int idleRows;
    private final int idleTime;
    private final float[][] actions;
    private final long bytes;
    private final String reason;
    private final int boneCount;

    /** 空表（= 采样不可用），也用于 {@link #EMPTY} */
    private GeoPoseTable(float[] walk, int walkRows, int walkTime,
                         float[] idle, int idleRows, int idleTime,
                         float[][] actions, long bytes, String reason) {
        this.walk = walk;
        this.walkRows = walkRows;
        this.walkTime = Math.max(1, walkTime);
        this.idle = idle;
        this.idleRows = idleRows;
        this.idleTime = Math.max(1, idleTime);
        this.actions = actions;
        this.bytes = bytes;
        this.reason = reason;
        this.boneCount = walkRows > 0 ? walk.length / (walkRows * this.walkTime * FLOATS) : 0;
    }

    /** 该表是不是「走路也带时间维」的二维表（诊断/日志用） */
    public boolean walkHasTime() {
        return walkTime > 1;
    }

    public int walkTimeSamples() {
        return walkTime;
    }

    public int idleTimeSamples() {
        return idleTime;
    }

    /** 待机时间轴铺了几个样本（与表无关，采样形状的常量；离线用例的期望值也要用它） */
    public static int idleTimeSampleCount() {
        return IDLE_TIME_SAMPLES;
    }

    /** 走路时间轴上铺了几个样本（同上） */
    public static int walkTimeSampleCount() {
        return WALK_TIME_SAMPLES;
    }

    /** 走路时间轴相邻样本相隔多少 tick */
    public static int walkTimeStepTicks() {
        return WALK_TIME_STRIDE;
    }

    /** 待机时间轴相邻样本相隔多少 tick */
    public static int idleTimeStepTicks() {
        return IDLE_STEP_TICKS;
    }

    /** 待机时间表覆盖的 tick 数（运行期用 {@code age % period} 索引） */
    public static int idlePeriodTicks() {
        return IDLE_PERIOD_TICKS;
    }

    /** 表是否可用（有行走样本才算可用；动作表可以缺席 = 该类型没有可采到的动作动画） */
    public boolean usable() {
        return walk != null && walkRows > 0 && boneCount > 0;
    }

    /** 采样失败原因（可用时为 null） */
    public String reason() {
        return reason;
    }

    public int boneCount() {
        return boneCount;
    }

    public int walkRows() {
        return walkRows;
    }

    public int idleRows() {
        return idleRows;
    }

    /** 真的采到了样本的动作数（下标 = 动作编号 - 1） */
    public int actionCount() {
        return actions == null ? 0 : actions.length;
    }

    public boolean hasAction(int actionIndex) {
        return actions != null && actionIndex >= 1 && actionIndex <= actions.length
                && actions[actionIndex - 1] != null;
    }

    /** 单张表的字节数（不含对象头）；不可用的表为 0 */
    public long bytes() {
        return bytes;
    }

    /**
     * 表规模描述（日志/报告用）。
     *
     * 关键是要能一眼看出**待机是时间表还是常量**、**走路是否二维**，以及各表字节数 ——
     * 「站着不动」这个故障在旧日志里完全看不出来（写着「待机 1」但那其实是正常输出）。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("行走 ").append(walkRows).append(" 相位");
        if (walkTime > 1) {
            sb.append(" × 时间 ").append(walkTime).append("（每 ")
                    .append(WALK_TIME_STRIDE).append(" tick，二维）");
        } else {
            sb.append("（一维，纯相位：采样判定该模型没有时间成分）");
        }
        sb.append("；待机 ");
        if (idleRows <= 0) {
            sb.append("无（该类型不区分 limbSwingAmount，已弃用）");
        } else if (idleTime > 1) {
            sb.append("时间表 ").append(idleTime).append(" 行 / 周期 ").append(IDLE_PERIOD_TICKS)
                    .append(" tick（每 ").append(IDLE_STEP_TICKS).append(" tick，首尾闭合）");
        } else {
            sb.append("常量 1 行（该模型待机姿态不随时间变化）");
        }
        sb.append("；动作 ").append(actionCount());
        sb.append("；骨骼 ").append(boneCount);
        sb.append("；行走 ").append(bytesOf(walk)).append(" B");
        sb.append(" + 待机 ").append(bytesOf(idle)).append(" B");
        long ab = 0L;
        if (actions != null) {
            for (float[] t : actions) {
                ab += bytesOf(t);
            }
        }
        sb.append(" + 动作 ").append(ab).append(" B = ").append(bytes).append(" B（")
                .append(bytes / 1024L).append(" KiB）");
        return sb.toString();
    }

    /** 单张子表的字节数（float 4 字节，不含对象头） */
    private static long bytesOf(float[] t) {
        return t == null ? 0L : (long) t.length * 4L;
    }

    /**
     * ★ **采样表自审**：表自身在「时间维」「相位维」上到底有多少变化（旋转 / 位移分开算）。
     *
     * <h2>为什么必须有这一行</h2>
     * 真机「有静止动画、行为动画没有」时，日志里既有「采样表 64 相位 × 时间 32」又有
     * 「动画诊断：旋转与 1 秒前完全相同」—— 光看这两条分不清是
     * <b>(A) 表本身是废的</b>（采样时没设对相位/幅度）还是
     * <b>(B) 表是好的、运行时查错了表 / 把结果乘没了</b>。
     * 这里把表的原始数据直接量化出来，一次分叉：
     * <ul>
     *   <li>走表**相位维**旋转差异大 ⇒ 表里有真的走路姿态（采样时 amount 传对了）；
     *       若 ≈0 而该模型是 GeckoLib 系，通常说明采样时
     *       {@code AnimationState.limbSwingAmount} 传了 0，控制器全程选了待机动画；</li>
     *   <li>走表/待机表**时间维**旋转差异 ⇒ 动画是不是时间驱动的；</li>
     *   <li>待机表「有/无」+ 差异 ⇒ 待机维到底采到了什么。</li>
     * </ul>
     * 纯读表、零分配，只在烘焙时打一次。
     */
    public String audit() {
        if (!usable()) {
            return "表不可用（" + reason + "）";
        }
        int rowLen = boneCount * FLOATS;
        // 走表：时间维（固定相位 0）与相位维（固定时间 0）上的最大差异
        float[] rotWalkTime = maxDelta(walk, walkRows, walkTime, 0, true, true);
        float[] rotWalkPhase = maxDelta(walk, walkRows, walkTime, 0, false, true);
        float[] transWalkTime = maxDelta(walk, walkRows, walkTime, 0, true, false);
        // 待机表：时间维
        String idleText;
        if (idle == null || idleRows <= 0) {
            idleText = "无（该类型在采样时没有可用的待机姿态）";
        } else if (idleRows <= 1) {
            idleText = "1 行（待机姿态不随时间变化）";
        } else {
            float[] rotIdle = maxDelta(idle, idleRows, idleTime, 0, true, true);
            float[] transIdle = maxDelta(idle, idleRows, idleTime, 0, true, false);
            idleText = idleRows + " 行（旋转|Δ|时间 " + fmt(rotIdle[0]) + "，位移|Δ|时间 "
                    + fmt(transIdle[0]) + "）";
        }
        return "走表旋转|Δ|：时间 " + fmt(rotWalkTime[0]) + "、相位 " + fmt(rotWalkPhase[0])
                + "；走表位移|Δ|：时间 " + fmt(transWalkTime[0])
                + "；待机表 " + idleText
                + "；骨骼 " + boneCount;
    }

    /**
     * 表内最大差异。
     *
     * @param alongTime true = 沿时间维（相位固定 0），false = 沿相位维（时间固定 0）
     * @param rotation  true = 只看旋转（xyz），false = 只看位移（off xyz）
     * @return {@code [最大差异, 出现在第几根骨骼]}
     */
    private float[] maxDelta(float[] table, int phases, int times, int fixed, boolean alongTime,
                             boolean rotation) {
        int rowLen = boneCount * FLOATS;
        int base = rowStart(0, 0, phases, times, rowLen);
        float worst = 0.0F;
        int worstBone = -1;
        int span = alongTime ? times : phases;
        for (int k = 1; k < span; k++) {
            int row = alongTime ? rowStart(0, k, phases, times, rowLen)
                    : rowStart(k, 0, phases, times, rowLen);
            for (int b = 0; b < boneCount; b++) {
                int o = b * FLOATS;
                int c0 = rotation ? 0 : 3;
                for (int c = c0; c < c0 + 3; c++) {
                    float d = Math.abs(table[row + o + c] - table[base + o + c]);
                    if (d > worst) {
                        worst = d;
                        worstBone = b;
                    }
                }
            }
        }
        return new float[]{worst, worstBone};
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /**
     * 给定幅度，运行期会用哪张表（日志/诊断用）。
     *
     * <p>为什么单独暴露：真机排查时「用了哪张表」必须一眼可见 ——
     * 上一轮就是因为分不清「查了走表」还是「查了待机表」而多跑了一轮。
     */
    public String branchFor(float limbSwingAmount) {
        if (idle == null || idleRows == 0) {
            return "走表（没有待机表：全部幅度都查走表）";
        }
        float amount = Float.isFinite(limbSwingAmount)
                ? Math.max(0.0F, Math.min(1.0F, limbSwingAmount)) : 0.0F;
        if (amount <= 0.0F) {
            return "待机表（幅度 0）";
        }
        if (amount >= 1.0F) {
            return "走表（幅度 1）";
        }
        return "走表 ↔ 待机表插值（幅度 " + fmt(amount) + "）";
    }

    private static GeoPoseTable bad(String reason) {
        return new GeoPoseTable(null, 0, 1, null, 0, 1, null, 0L, reason);
    }

    // ------------------------------------------------------------------
    // 运行期：查表 + 插值
    // ------------------------------------------------------------------

    /**
     * 查表得到一次姿态（每骨骼 {@link #FLOATS} 个 float）。
     *
     * 三个输入各自负责一条轴：
     * <ul>
     *   <li>{@code limbSwing} = 行走**相位**（走路动画的主要驱动量）；</li>
     *   <li>{@code ageInTicks} = 原版 {@code tickCount}，用来索引**时间**维
     *       （待机呼吸/摆头，以及走路表升二维后的那层慢变化），运行期做 {@code age % 周期}；</li>
     *   <li>{@code limbSwingAmount} 在「行走表 ↔ 待机表」之间插值。</li>
     * </ul>
     *
     * 相位、时间、动作计时都在相邻两个样本之间**线性插值**，否则群集单位会以样本间隔「跳帧」。
     * 有动作时动作样本**覆盖**行走/待机样本（与真实实体一致：动作动画盖住其它动画）。
     *
     * @return 是否真的查到了（false 时 out 未被触碰，调用方保持上一帧/绑定姿态）
     */
    public boolean readAt(float limbSwing, float limbSwingAmount, float ageInTicks,
                          int actionIndex, float actionTick, float[] out) {
        if (!usable() || out == null || out.length < boneCount * FLOATS) {
            return false;
        }
        if (hasAction(actionIndex)) {
            // 动作表只有相位维（动作时长一般 20~60 tick，时间成分已经在动作动画自身里）
            return readTable(actions[actionIndex - 1], ACTION_PHASES, 1, actionPhase(actionTick), 0.0F, out);
        }
        if (!Float.isFinite(limbSwing)) {
            // NaN/Inf 相位会让下标算出 NaN -> 抛异常。当成 0 处理，绝不把异常抛进渲染循环。
            limbSwing = 0.0F;
        }
        float amount = Float.isFinite(limbSwingAmount)
                ? Math.max(0.0F, Math.min(1.0F, limbSwingAmount)) : 0.0F;
        float tIdle = timePos(ageInTicks, idleTime, IDLE_STEP_TICKS);
        float wPos = walkPhase(limbSwing);
        if (idle == null || idleRows == 0) {
            return readTable(walk, walkRows, walkTime, wPos,
                    timePos(ageInTicks, walkTime, WALK_TIME_STRIDE), out);
        }
        if (amount <= 0.0F) {
            // 站着不动：完全由**时间表**决定姿态（这一支就是「真机静止」的修复点）
            return readTable(idle, idleRows, idleTime, 0.0F, tIdle, out);
        }
        if (amount >= 1.0F) {
            // 端点必须精确落在行走样本本身上（行走单位不该被待机组「稀释」）
            return readTable(walk, walkRows, walkTime, wPos,
                    timePos(ageInTicks, walkTime, WALK_TIME_STRIDE), out);
        }
        // amount ∈ (0,1)：行走 ↔ 待机线性插值。两表各按自己的时间轴取时间位置。
        // 这里**不分配临时数组** —— 这段代码每个单位每帧都会跑，分配会把 GC 压力带进渲染循环。
        return readTable(walk, walkRows, walkTime, wPos,
                timePos(ageInTicks, walkTime, WALK_TIME_STRIDE),
                idle, idleRows, idleTime, tIdle, amount, out);
    }

    /**
     * 兼容旧签名（没有时间维入参 = age 取 0）。
     *
     * 保留它是为了让「只关心相位」的调用点（探针、离线用例）读起来简单；
     * 生产路径一律用带 {@code ageInTicks} 的那个重载。
     */
    public boolean readAt(float limbSwing, float limbSwingAmount, int actionIndex,
                          float actionTick, float[] out) {
        return readAt(limbSwing, limbSwingAmount, 0.0F, actionIndex, actionTick, out);
    }

    /** limbSwing（弧度，可能绕了好几圈）-> 表内相位位置 [0, WALK_PHASES) */
    private static float walkPhase(float limbSwing) {
        float turns = limbSwing / (float) (Math.PI * 2.0);
        turns -= (float) Math.floor(turns);
        return turns * WALK_PHASES;
    }

    /**
     * 运行期「时间 -> 表内时间位置」映射。
     *
     * 同一个 age 要按**每张表自己的时间轴**翻译：待机表与走路表的样本数/步长可以不同
     * （当前实现故意让它们相同，见 {@link #WALK_TIME_SAMPLES}，但函数本身不假设这一点 ——
     * 一旦有人把某一边改稀，映射必须跟着这条轴走，否则动画会以错误速度播放）。
     *
     * 用 {@code % period} 而不是自己算 floor：这是渲染循环里的每单位每帧代码，
     * 少一次 floor 就少一点开销；而 {@code age} 恰好落在样本倍数上时结果一定是精确整数
     * （步长与比率都是 2 的幂或 5×2^k，二进制里精确），于是样本点上取到的就是**严格等于**采样值的那一行。
     */
    private static float timePos(float ageInTicks, int timeSamples, int stepTicks) {
        if (!Float.isFinite(ageInTicks) || timeSamples <= 1) {
            return 0.0F;
        }
        float t = ageInTicks % IDLE_PERIOD_TICKS;
        if (t < 0.0F) {
            t += IDLE_PERIOD_TICKS;
        }
        return t / stepTicks;
    }

    /** actionTick -> 动作表内相位位置 [0, ACTION_PHASES) */
    private static float actionPhase(float actionTick) {
        if (!Float.isFinite(actionTick)) {
            return 0.0F;
        }
        float t = actionTick % ACTION_DURATION_TICKS;
        if (t < 0.0F) {
            t += ACTION_DURATION_TICKS;
        }
        return t * ((float) ACTION_PHASES / (float) ACTION_DURATION_TICKS);
    }

    /** 取一个表的行首下标（行 = 所有骨骼的 9 个 float） */
    private static int rowStart(int phase, int time, int phases, int times, int rowLen) {
        int p = ((phase % phases) + phases) % phases;
        int t = ((time % times) + times) % times;
        return (p * times + t) * rowLen;
    }

    /** 在「两个时间行」之间按 {@code tf} 混合，写进 out（不分配） */
    private static void lerpRows(float[] table, int i0, int i1, float tf, float[] out) {
        if (tf <= 0.0F) {
            System.arraycopy(table, i0, out, 0, out.length);
            return;
        }
        for (int k = 0; k < out.length; k++) {
            out[k] = table[i0 + k] + (table[i1 + k] - table[i0 + k]) * tf;
        }
    }

    /**
     * 同一张表内按「相位位置 + 时间位置」取行：两维都插值，且**不分配临时数组**。
     *
     * 布局是 {@code [phases][times][bones*9]}，时间是最内层（连续的）轴 —— 时间插值走
     * 内存相邻的两行（缓存友好），相位插值跨 times 行。
     */
    private boolean readTable(float[] table, int phases, int times,
                              float pos, float tpos, float[] out) {
        if (table == null || phases <= 0) {
            return false;
        }
        int rowLen = boneCount * FLOATS;   // 一行 = 所有骨骼的 9 个 float
        int f = (int) Math.floor(pos);
        float u = pos - f;
        int tf = (int) Math.floor(tpos);
        float tt = tpos - tf;
        int a = rowStart(f, tf, phases, times, rowLen);
        if (u <= 0.0F) {
            // 相位落在样本点上：只需要在时间行之间插值
            lerpRows(table, a, rowStart(f, tf + 1, phases, times, rowLen), tt, out);
            return true;
        }
        int b = rowStart(f + 1, tf, phases, times, rowLen);
        int b1 = rowStart(f + 1, tf + 1, phases, times, rowLen);
        int a1 = rowStart(f, tf + 1, phases, times, rowLen);
        for (int k = 0; k < out.length; k++) {
            float v0 = table[a + k] + (table[a1 + k] - table[a + k]) * tt;
            float v1 = table[b + k] + (table[b1 + k] - table[b + k]) * tt;
            out[k] = v0 + (v1 - v0) * u;
        }
        return true;
    }

    /**
     * 两张表之间插值（行走 ↔ 待机）：两表各自在「相位 × 时间」内插值一次，再按 {@code t} 混合。
     *
     * 合并成一个循环是为了**不分配临时数组**：每个群集单位每帧都会走到这里。
     * 两张表各带自己的时间位置（{@code tpos0}/{@code tpos1}）—— 它们的时间轴样本数可以不同；
     * 待机表按定义只有一个相位（相位在 amount=0 时对姿态没有影响），所以它的相位位置固定传 0。
     */
    private boolean readTable(float[] table0, int phases0, int times0, float pos0, float tpos0,
                              float[] table1, int phases1, int times1, float tpos1,
                              float t, float[] out) {
        if (table0 == null || phases0 <= 0 || table1 == null || phases1 <= 0) {
            return false;
        }
        int rowLen = boneCount * FLOATS;
        int tf0 = (int) Math.floor(tpos0);
        float tt0 = tpos0 - tf0;
        int f0 = (int) Math.floor(pos0);
        float u0 = pos0 - f0;
        int a0 = rowStart(f0, tf0, phases0, times0, rowLen);
        int a01 = rowStart(f0, tf0 + 1, phases0, times0, rowLen);
        int b0 = rowStart(f0 + 1, tf0, phases0, times0, rowLen);
        int b01 = rowStart(f0 + 1, tf0 + 1, phases0, times0, rowLen);
        int tf1 = (int) Math.floor(tpos1);
        float tt1 = tpos1 - tf1;
        int a1 = rowStart(0, tf1, phases1, times1, rowLen);
        int a11 = rowStart(0, tf1 + 1, phases1, times1, rowLen);
        for (int k = 0; k < out.length; k++) {
            float w0 = table0[a0 + k] + (table0[a01 + k] - table0[a0 + k]) * tt0;
            float w1 = table0[b0 + k] + (table0[b01 + k] - table0[b0 + k]) * tt0;
            float v0 = w0 + (w1 - w0) * u0;
            float i0 = table1[a1 + k] + (table1[a11 + k] - table1[a1 + k]) * tt1;
            out[k] = v0 + (i0 - v0) * t;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 烘焙期：组装表
    // ------------------------------------------------------------------

    /** 便捷入口（离线验证/自定义来源用）：状态也由采样源自己摆 */
    public static GeoPoseTable build(SampleSource source, int bones, int actions) {
        return build(source, bones, actions, true, new TimeSampler());
    }

    /**
     * 同上，可以显式关掉时间维（离线用例里比对「有时间轴 / 没时间轴」两种表）。
     *
     * @param timeAxis 是否沿时间轴采待机样本。true 是生产路径（也是修复「静止」的那条路）；
     *                 false 复刻旧行为（待机按相位采，最多 1 行），只给对比用例用
     */
    public static GeoPoseTable build(SampleSource source, int bones, int actions, boolean timeAxis) {
        return build(source, bones, actions, timeAxis, new TimeSampler());
    }

    /**
     * 完整入口：外部（生产路径）可以把 {@link TimeSampler} 传进来共享给采样源。
     *
     * 离线用例里的采样源自己拿 {@link State#clock} 当 tick 用（不需要翻译），
     * 这时传一个新实例即可 —— 两者都不影响表的组装逻辑。
     */
    public static GeoPoseTable build(SampleSource source, int bones, int actions, boolean timeAxis,
                                     TimeSampler clock) {
        if (source == null || bones <= 0 || bones > RigBaker.MAX_BONES) {
            return bad("骨骼数 " + bones + " 不合法（上限 " + RigBaker.MAX_BONES + "）或采样器为空");
        }
        int nActions = Math.max(0, Math.min(actions, MAX_ACTIONS));
        int iTime = timeAxis ? IDLE_TIME_SAMPLES : 1;
        int iStep = timeAxis ? IDLE_STEP_TICKS : 1;
        // 待机表首尾闭合需要多存一行（第 idleTime 行 = 第 0 行的副本），所以行数是 iTime + 1
        long rowBytes = (long) bones * FLOATS * 4L;
        long idleRows0 = iTime + 1L;

        // 先按「走路也升二维」核算。超上限就退成「走路只有相位维」，
        // 再超就整表弃用 —— 无论如何都不在采样中途才发现装不下。
        boolean walkWithTime = timeAxis;
        long need = rowBytes * (timeAxis
                ? (long) WALK_PHASES * WALK_TIME_SAMPLES + idleRows0 + (long) nActions * ACTION_PHASES
                : (long) WALK_PHASES + idleRows0 + (long) nActions * ACTION_PHASES);
        String degrade = null;
        if (need > MAX_TABLE_BYTES && timeAxis) {
            degrade = "二维走路表需要 " + need + " 字节 > 上限 " + MAX_TABLE_BYTES
                    + "（骨骼 " + bones + "：相位 " + WALK_PHASES + " × 时间 " + WALK_TIME_SAMPLES
                    + " + 待机 " + idleRows0 + " + 动作 " + nActions + "×" + ACTION_PHASES
                    + "）—— 走路降级为一维（纯相位），待机仍是时间表";
            walkWithTime = false;
            need = rowBytes * ((long) WALK_PHASES + idleRows0 + (long) nActions * ACTION_PHASES);
        }
        if (need > MAX_TABLE_BYTES) {
            return bad("采样表需要 " + need + " 字节，超过上限 " + MAX_TABLE_BYTES
                    + "（骨骼 " + bones + "：行走 " + WALK_PHASES + " + 待机 " + idleRows0
                    + " + 动作 " + nActions + "×" + ACTION_PHASES + "）");
        }

        float[] row = new float[bones * FLOATS];
        // ---- 采样时钟：为什么不能简单用 step++ ----
        // GeckoLib 的 AnimationState.animationTick 是 GeoModel 上的**模型级累加器**：
        //   animTime += (tick + partialTick) - lastGameTickTime
        // 其中 tick = 傀儡的 tickCount。于是「动画时间」只跟**相邻样本之间 tickCount 的差**有关，
        // 而我们要的是「这张表的第 t 行对应动画的第 t*step tick」。所以：
        //   * 每个维度（傀儡）自己的 tickCount 由**该维度的 age**决定，不受全局累加漂移影响；
        //   * 采样**会话**（一组连续的采样帧）开始前先 warm-up 两帧，把「上一会话残留的 tickCount」
        //     通过偏移量消化掉，之后每帧的 age 增量就精确等于 tickCount 增量。
        // 只记日志、不抛异常：读不到 animTime 就退化成「按相对步长采样」，仍然是单调推进的。
        try {
            if (!clock.warmUp(source, 0, row)) {
                return bad("行走样本 warm-up 失败（渲染器/模型形状不符，或渲染抛异常）");
            }
            // ---- 维度 0：行走，相位铺满 [0, 2π)，amount = 1 ----
            // 先用「同一相位、两个不同 age」探一下这个模型有没有时间成分：
            // 纯相位动画在这两次采样里应当逐位相同，只要有一点呼吸/摇摆叠加就会远超阈值。
            //
            // ★ 会话边界（很关键）：每个会话都是「rewind -> warmUp -> 按单调 age 采」。
            //   GeckoLib 是在 warmUp 那两帧里记下 tickOffset 的，所以 warmUp 必须**紧挨着**它要定标的那批样本：
            //   中间若再 rewind，样本的 tick 与 tickOffset 就对不上，表里的「第 t 行 = 第 t·step tick」会整体偏移。
            float[] probeA = new float[row.length];
            float[] probeB = new float[row.length];
            float worstTime = 0.0F;
            for (int pi = 0; pi < 2; pi++) {
                if (pi > 0) {
                    clock.rewind();
                    if (!clock.warmUp(source, 0, row)) {
                        return bad("行走样本 warm-up 失败（渲染器/模型形状不符，或渲染抛异常）");
                    }
                }
                float phase = (float) (pi * 16 * (Math.PI * 2.0) / WALK_PHASES);
                if (!sampleOne(source, new State(0, phase, 1.0F, 0.0F, 0.0F, 0, 0, 0), probeA)
                        || !sampleOne(source, new State(0, phase, 1.0F, 0.0F, 0.0F, 0, 0,
                        WALK_TIME_STRIDE), probeB)) {
                    return bad("行走相位 " + pi + " 的时间成分探测采样失败"
                            + "（渲染器/模型形状不符，或渲染抛异常）");
                }
                worstTime = Math.max(worstTime, deviation(probeA, probeB));
            }
            // 正式采表：重开一个会话（warmUp 紧挨着这批样本），于是第 t 行的有效动画时间恰好是 t·step
            clock.rewind();
            if (!clock.warmUp(source, 0, row)) {
                return bad("行走样本 warm-up 失败（渲染器/模型形状不符，或渲染抛异常）");
            }
            // ---- 驱动自检：显式驱动到底有没有真的被调起来 ----
            // 为什么把这一步放在**采全表之前**：真机上「2048 个样本完全相同」是在采完之后才被
            // 塌缩检测发现的，于是白采了 2048 帧、日志里也只有一句笼统的话。这里在正式采样之前
            // 只看「驱动调用自己的成败」，失败立刻给出**可读原因**并退回绑定姿态。
            //
            // 刻意**不**用「姿态有没有差异」当判据：纯相位动画的模型（只响应 limbSwing、
            // 完全不随时间变化）本来就该采出一维表，用差异判定会把它误杀。
            // 「姿态一个都没变」这种真故障由下面的样本塌缩检测负责，那里会带上这份统计。
            GeoAnimator.DriveStatus drive = source.driveStatus();
            if (drive != null) {
                if (drive.managers == 0) {
                    return bad("拿不到 GeckoLib 的动画实例管理器（AnimatableManager）：傀儡实体上找不到"
                            + "「无参、返回 AnimatableInstanceCache」的取值点，或缓存上没有"
                            + "「单参 long、返回 AnimatableManager」的方法 —— 该类型不支持动画采样，"
                            + "已退回绑定姿态（画面静止，不出错误姿态）");
                }
                if (drive.driveCalls > 0 && drive.driveApplied == 0) {
                    return bad("显式驱动动画器全部失败（" + drive.describe()
                            + "）—— 该类型不支持动画采样，已退回绑定姿态（画面静止，不出错误姿态）");
                }
                HordeMod.LOGGER.info("[horde] geo 姿态采样：显式驱动自检通过（{}）", drive.describe());
            }
            // 探测说有时间成分才升二维；预算不允许（walkWithTime == false）时**一律**降级，
            // 否则就会采出一张比预算大的表（超限的风险必须在这里拦住，而不是等 OOM）。
            boolean walkWantsTime = walkWithTime && worstTime > WALK_TIME_EPS;
            boolean walk2d = walkWantsTime;
            int wTimes = walk2d ? WALK_TIME_SAMPLES : 1;

            // ---- 行走表：**时间外层、相位内层** ----
            // 顺序很关键：相位维的 64 个样本共用同一个 age（同一个动画时刻），
            // 时间维才逐档 +5。这样 tickCount 在整个表内单调不减，
            // 「第 t 行 = 动画第 t·step tick」才真的成立（真机事故：反过来采样会让动画时间倒退）。
            // 表的**存储布局仍是 [相位][时间]**（运行期 rowStart 按这个索引），所以写回时要按槽位放。
            float[][] walkSlots = new float[WALK_PHASES * wTimes][];
            for (int t = 0; t < wTimes; t++) {
                for (int i = 0; i < WALK_PHASES; i++) {
                    float phase = (float) (i * (Math.PI * 2.0) / WALK_PHASES);
                    // 时间轴与待机表**共用同一个映射**（同一个周期、同一个起点）：
                    // 于是 amount 在行走/待机之间插值时，两边的待机成分处在同一时间相位上，不会互相打架。
                    State st = new State(0, phase, 1.0F, 0.0F, 0.0F, 0, 0,
                            t * WALK_TIME_STRIDE);
                    if (!sampleOne(source, st, row)) {
                        return bad("行走相位 " + i + " 采样失败"
                                + "（渲染器/模型形状不符，或渲染抛异常）");
                    }
                    walkSlots[i * wTimes + t] = row.clone();
                }
            }
            List<float[]> walkRows = new ArrayList<>(java.util.Arrays.asList(walkSlots));
            if (allSame(walkRows)) {
                return bad("行走样本塌缩：" + collapseReport("行走", walkRows, row.length)
                        + " —— 说明 GeckoLib 没有真的重算每帧动画（该类型不支持动画采样），"
                        + "已退回绑定姿态（画面静止，但绝不会出错误姿态）");
            }
            // 反向确认：探测说有时间成分、表也确实升了二维，但相邻时间行完全一样
            // -> 那不是「时间成分」，而是采样时钟没能推动动画（例如 GeckoLib 复用了上一次的姿态）。
            // 这时把时间维压掉并给出 WARN 级说明，免得白占内存还让人以为已经修好了。
            if (walk2d && !anyTimeRowDiffers(walkRows, WALK_PHASES, wTimes, row.length)) {
                HordeMod.LOGGER.warn("[horde] geo 姿态采样：行走表的时间维塌缩"
                        + "（相位 {} × 时间 {} 的相邻时间行完全相同）—— 说明采样时钟没能推动 GeckoLib 的"
                        + "animTime，行走表退回纯相位一维；待机时间表若也塌缩会另行告警", WALK_PHASES, wTimes);
                walk2d = false;
                wTimes = 1;
                List<float[]> collapsed = new ArrayList<>(WALK_PHASES);
                for (int i = 0; i < WALK_PHASES; i++) {
                    collapsed.add(walkRows.get(i * WALK_TIME_SAMPLES));
                }
                walkRows = collapsed;
            } else if (!walk2d && worstTime > WALK_TIME_EPS && degrade != null) {
                // 只有**真的**因为预算降级（degrade != null）时才告警。
                // 这里原来写的是 `walkWantsTime && worstTime > WALK_TIME_EPS`，于是在「二维走路表
                // 正常成立、时间行也确实有差异」这条**成功路径**上也会打一条 WARN（还说「装不下…
                // 原因：null」）—— 真机排查时这种假告警会把注意力带偏，所以必须只在真降级时说。
                HordeMod.LOGGER.warn("[horde] geo 姿态采样：该模型走路有时间成分（最大偏差 {}），"
                        + "但二维走路表装不下 —— 走路维降级为一维（纯相位）。原因：{}", worstTime, degrade);
            }

            // ---- 维度 1：待机（amount = 0），沿**时间轴**采 ----
            // 待机姿态与相位无关（amount=0 时 limbSwing 不影响姿态），所以相位固定取 0：
            // 这一维的全部样本都用来描述时间，运行期用 age % 周期 索引。
            //
            // ★ 先 rewind 再 warm-up：换维度必须让「时钟原点」越过上一维度的最大值，
            //   否则本维度的 tickCount 会被「绝不倒退」的兜底夹住（= 动画时间不动 → 整维冻成一帧）。
            //   而且 warm-up 用的 tick 必须**恰好等于本维度 age=0 的 tick**：
            //   GeckoLib 就是在那两帧里记下 tickOffset 的，之后 tick-tickOffset 才等于样本的 age。
            clock.rewind();
            if (!clock.warmUp(source, 1, row)) {
                HordeMod.LOGGER.debug("[horde] geo 姿态采样：待机维 warm-up 失败，待机组将缺失");
            }
            List<float[]> idleRows = new ArrayList<>(iTime + 1);
            boolean idleComplete = true;
            for (int i = 0; i < iTime; i++) {
                State st = new State(1, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, i * iStep);
                if (!sampleOne(source, st, row)) {
                    idleRows.clear();   // 待机采不到不影响可用性：行走组已经能撑起动画
                    idleComplete = false;
                    break;
                }
                idleRows.add(row.clone());
            }
            boolean idleSame = !idleRows.isEmpty() && allSame(idleRows);
            if (idleComplete && !idleRows.isEmpty()) {
                // 首尾闭合：多存一行 = 第 0 行的副本，于是「第 t 行 -> 第 t+1 行」的插值在
                // t = 周期 处自然接回起点，运行期取模时不会有跳变。
                idleRows.add(idleRows.get(0).clone());
            }
            if (!idleComplete) {
                // 待机采不到就整组丢掉（不要拿半张表去索引，那会画出一个不存在的中间相位）
                idleRows.clear();
            } else if (idleSame) {
                // 待机姿态不随时间变化 —— 那不是故障，是「这个模型待机本来就不动」，
                // 只留 1 行即可（readAt 在单行表上就是恒定姿态），并明确区分于「塌缩」。
                float[] only = idleRows.get(0);
                idleRows.clear();
                idleRows.add(only);
                if (timeAxis) {
                    HordeMod.LOGGER.debug("[horde] geo 姿态采样：待机时间表塌缩"
                            + "（{} 个时间样本完全相同）—— 该模型待机姿态不随时间变化，只保留 1 行",
                            iTime);
                }
            }
            // 但「待机 == 行走」说明采样根本没区分 amount（比如行走相位与强度是硬绑死的），
            // 这种表按 amount 插值会得到恒定姿态，等于回到静止 —— 不如弃用待机组。
            boolean idleOk = !idleRows.isEmpty() && !same(idleRows.get(0), walkRows.get(0));
            if (!idleOk && !idleRows.isEmpty()) {
                HordeMod.LOGGER.debug("[horde] geo 姿态采样：待机样本与行走样本相同，"
                        + "该模型不区分 limbSwingAmount，待机组已弃用");
            }

            // ---- 维度 2+：每个动作 ACTION_PHASES 个 tick 相位 ----
            // 动作维也要 warm-up：换维度时上一维的 tickCount 残留必须消化掉，
            // 否则「第 i 帧」的动画时间会带上一个与 actionTick 无关的偏移（动作会跳帧）。
            List<float[]> actionTables = new ArrayList<>();
            for (int a = 1; a <= nActions; a++) {
                // 同上：动作维也是「先 rewind、再 warm-up、然后按单调 age 采」
                clock.rewind();
                if (!clock.warmUp(source, a + 1, row)) {
                    break;
                }
                List<float[]> rows = new ArrayList<>(ACTION_PHASES);
                boolean complete = true;
                for (int i = 0; i < ACTION_PHASES; i++) {
                    int tick = i * (ACTION_DURATION_TICKS / ACTION_PHASES);
                    State st = new State(a + 1, 0.0F, 1.0F, 0.0F, 0.0F, a, tick, tick);
                    if (!sampleOne(source, st, row)) {
                        complete = false;
                        break;
                    }
                    rows.add(row.clone());
                }
                // 采不全、或这个动作根本没让骨骼动（很多动作只在特定状态才播）-> 不放进去
                if (complete && !allSame(rows)) {
                    actionTables.add(flatten(rows));
                }
            }

            float[] walkTable = flatten(walkRows);
            float[] idleTable = idleOk ? flatten(idleRows) : null;
            long bytes = bytesOf(walkTable) + bytesOf(idleTable);
            for (float[] t : actionTables) {
                bytes += bytesOf(t);
            }
            GeoPoseTable table = new GeoPoseTable(walkTable, WALK_PHASES, wTimes,
                    idleTable, idleOk ? idleRows.size() : 0, idleOk ? (idleSame ? 1 : iTime) : 0,
                    actionTables.isEmpty() ? null : actionTables.toArray(new float[0][]), bytes, null);
            if (degrade != null && walk2d) {
                HordeMod.LOGGER.warn("[horde] geo 姿态采样：{}", degrade);
            }
            return table;
        } catch (Throwable t) {
            // 采样失败必须退化成现状（静止姿态），绝不能让烘焙整体失败
            return bad("采样抛异常: " + t);
        }
    }

    /**
     * 走路表的二维是否真的有时间变化：比较相邻时间行（相位固定）是否不同。
     *
     * 与塌缩检测互补 —— 塌缩检测问「整张表是不是死的」，这里问「时间这一维是不是死的」。
     */
    private static boolean anyTimeRowDiffers(List<float[]> rows, int phases, int times, int len) {
        if (times <= 1) {
            return false;
        }
        for (int p = 0; p < phases; p++) {
            int base = p * times;
            for (int t = 1; t < times; t++) {
                if (!same(rows.get(base), rows.get(base + t))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 采样时钟：把「表里的 age」翻译成「傀儡的 tickCount + 抵消偏移」。
     *
     * <h2>为什么需要它</h2>
     * GeckoLib 的动画时间来自 {@code GeoModel.animTime}，那是一个**模型级**（跨傀儡共享的）累加器：
     * <pre>
     *   animTime += (puppet.tickCount + partialTick) - lastGameTickTime
     * </pre>
     * 我们采样时要的是「这个样本对应动画的第 age 个 tick」。如果直接
     * {@code puppet.tickCount = age}，那么换傀儡/换维度时 tickCount 会跳变，
     * 而这个跳变量会被原样累进 animTime —— 动画时间就带上了与 age 无关的偏移。
     * 所以这里维护一个**墙钟** {@code wall}（只增不减，保证 GeckoLib 不会因为「时间没变」跳帧），
     * 再维护一个偏移 {@code base}：
     * <pre>
     *   base_{n+1} = wall_n - age                （每次采样前重算）
     *   puppet.tickCount = wall + base           => animTime 每帧增量恰好 = age 增量
     * </pre>
     *
     * <h2>warm-up</h2>
     * 换维度（新傀儡）后第一次 render 会把 animTime 重算一遍，这一帧的姿态不可信，
     * 所以每个维度开采样之前先 warm-up 两帧（一帧定标 + 一帧把偏移落实）并把这帧丢掉。
     * warm-up 会消耗傀儡的 {@code age}（动画时间是单调的），所以 warm-up 用一个**很小的 age**
     * （会让 animTime 回退 —— GeckoLib 里动画时间回退等价于「这个动画从头播」，这正是我们要的），
     * 之后采样从 age 0 重新开始。
     */
    private static final class TimeSampler {

        /**
         * 该维度的「时钟原点」（只增不减）。
         *
         * <h2>真机事故：动画采样成功但画面完全不动 —— 时钟增量算错了</h2>
         * 上一版是 {@code tickCount = 2*wall - age}、且每采一个样本 {@code wall++}，于是
         * <pre>
         *   ΔtickCount = 2·Δwall - Δage = 2 - Δage
         * </pre>
         * 只有「每个样本 age 恰好 +1」时才等于 {@code Δage}。而**时间轴**是以 {@code WALK_TIME_STRIDE = 5}
         * 为步长采的，于是 {@code ΔtickCount = -3}：动画时间**在倒退**。
         * GeckoLib 的 {@code AnimationController.adjustTick} 是
         * {@code max(tick - tickOffset, 0)}（对着 jar javap 确认），倒退会被 clamp 到 0 ——
         * 于是那一整列样本都停在动画的第一帧：表看起来「行走 64 相位 × 时间 32」、
         * 尺寸也对（所以塌缩检测与时间维检测都没报），但运行期查出来的姿态几乎全一样，
         * 真机表现就是**鸭子/鹅完全不动**。
         *
         * <h2>现在的口径</h2>
         * <pre>
         *   tickCount = origin + age        =>  ΔtickCount == Δage（任何步长都成立）
         * </pre>
         * 于是「表里第 t 行 = 动画第 t·step tick」这条约定**真的成立**：
         * 采样开始时 {@code tickCount = origin}，GeckoLib 的 {@code tickOffset} 正是在那一帧被记下的，
         * 之后 {@code tick - tickOffset} 恰好等于样本的 age。
         *
         * <p><b>同一维度内 age 必须单调不减</b>（否则动画会倒退）：
         * 走表已改成「时间外层、相位内层」采样，相位维的 64 个样本共用同一个 age，
         * 时间维才逐档 +5。{@link #tickFor} 另有一道「绝不倒退」的兜底。
         */
        private int origin = 0;
        /** 该维度已经发出过的最大 tickCount（兜底：任何情况下都不让动画时间回退） */
        private int lastTick = Integer.MIN_VALUE;

        /** 兼容旧调用点：tick 现在完全由 age 决定，不需要额外推进 */
        void next() {
            // 空实现（保留是为了不动 build() 的采样循环结构）
        }

        /**
         * 重新开始一个会话 / 换到下一个维度：把原点推到当前进度**之后**。
         *
         * 为什么要「之后」：GeckoLib 的动画时间是**模型级**累加器，且控制器会记住 tickOffset；
         * 新维度（新傀儡、新管理器）必须从一个比上一维度更大的 tick 起算，
         * 否则新维度的动画时间会回退、被 clamp 到 0（真机上就是「待机维和行走维采出来一模一样」）。
         */
        void rewind() {
            origin = Math.max(origin, lastTick == Integer.MIN_VALUE ? origin : lastTick) + 64;
            lastTick = origin;
        }

        /**
         * 为一个维度做 warm-up：先定标、再让偏移落地（两帧都丢掉）。
         *
         * <p>两帧都取 age 0（tick 相同）：第一帧让 GeckoLib 记下 {@code tickOffset} 并启动动画控制器，
         * 第二帧确认它已经稳定。这样「采样第一行（age 0）」读到的就是动画第 0 帧。
         *
         * @return 采样源是否真的读到了姿态（失败时调用方决定是弃表还是忽略）
         */
        boolean warmUp(SampleSource source, int dimension, float[] row) {
            if (!source.read(new State(dimension, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0), row)) {
                return false;
            }
            next();
            if (!source.read(new State(dimension, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0), row)) {
                return false;
            }
            next();
            return true;
        }

        /**
         * 取这一次采样该写进傀儡的 tickCount。
         *
         * 注意 {@code age} 是**本维度**的 age（0 = 该维度的第一个样本），
         * 由调用方放在 {@link State#clock} 里传进来。
         */
        int tickFor(int age) {
            int tick = origin + Math.max(0, age);
            if (tick < lastTick) {
                tick = lastTick;   // 同一维度内 age 应当单调；万一不单调，也绝不让动画时间倒退
            }
            lastTick = tick;
            return tick;
        }
    }

    /**
     * 采样时钟的**契约自检**（离线用例/真机排查用）：给定一串 age，返回生产实现给出的 tickCount 序列。
     *
     * <p>为什么要有它：真机事故「采样成功但完全不动」的根因就在时钟增量上
     * （旧实现 {@code tickCount = 2·wall - age}、{@code wall++} ⇒ 时间轴上 Δtick = -3，
     * 动画时间倒退被 GeckoLib clamp 到 0，整列样本冻在第一帧）。而**离线 harness 之前抓不到**，
     * 因为 harness 的采样源直接拿 {@code state.clock} 当动画时间用（那条路是对的），
     * 根本没经过生产的 {@link TimeSampler}。现在把它做成公开的纯函数，断言就不会再漏。
     *
     * <p>契约：**单调不减**，且 {@code Δtick == Δage}（任何步长都成立）。
     */
    public static int[] sampleTickSequence(int... ages) {
        TimeSampler clock = new TimeSampler();
        int[] out = new int[ages.length];
        for (int i = 0; i < ages.length; i++) {
            out[i] = clock.tickFor(ages[i]);
        }
        return out;
    }

    /** 逐分量最大绝对偏差（探测时间成分用） */
    private static float deviation(float[] a, float[] b) {
        float worst = 0.0F;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    private static boolean sampleOne(SampleSource source, State state, float[] row) {
        return source.read(state, row) && allFinite(row);
    }

    /** 采样结果必须全是有限数：NaN/Inf 进了表就等于把垃圾画到脸上 */
    private static boolean allFinite(float[] row) {
        for (float v : row) {
            if (!Float.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(float[] a, float[] b) {
        for (int k = 0; k < a.length && k < b.length; k++) {
            if (Math.abs(a[k] - b[k]) > SAME_EPS) {
                return false;
            }
        }
        return true;
    }

    private static boolean allSame(List<float[]> rows) {
        if (rows.size() < 2) {
            return true;
        }
        float[] first = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            if (!same(first, rows.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 样本塌缩的**可读报告**：判定为塌缩的那一维、样本数、以及前两个样本的差异值。
     *
     * 为什么必须打这些数字：真机上原来的日志只有一句「2048 个样本完全相同」，
     * 于是「哪一维塌了」「差多少算塌」「是逐位相同还是差一点点被阈值判死」全都看不出来 ——
     * 而这三件事恰好决定了下一步该查什么。阈值取 1e-5（与 {@link #same} 一致）。
     */
    public static String collapseReport(String dimension, List<float[]> rows, int rowLen) {
        int n = rows.size();
        StringBuilder sb = new StringBuilder(96);
        sb.append("维度=").append(dimension).append("，样本数=").append(n);
        if (n >= 2) {
            float[] a = rows.get(0);
            float[] b = rows.get(1);
            float worst = 0.0F;
            int worstIndex = -1;
            int differing = 0;
            for (int k = 0; k < Math.min(a.length, b.length); k++) {
                float d = Math.abs(a[k] - b[k]);
                if (d > SAME_EPS) {
                    differing++;
                }
                if (d > worst) {
                    worst = d;
                    worstIndex = k;
                }
            }
            sb.append("，前两个样本：最大差异=").append(trim(worst));
            if (worstIndex >= 0) {
                sb.append("（第 ").append(worstIndex).append(" 个 float = 骨骼 ").append(worstIndex / FLOATS)
                        .append(" 的 ").append(componentName(worstIndex % FLOATS)).append("，值 ")
                        .append(trim(a[worstIndex])).append(" vs ").append(trim(b[worstIndex])).append('）');
            }
            sb.append("，差异分量数=").append(differing).append('/').append(Math.min(a.length, b.length));
        }
        sb.append("（阈值 ").append(SAME_EPS).append("）");
        return sb.toString();
    }

    /** 「前两个样本」判定的阈值（与 {@link #same} 保持一致，日志里要打出来给人看） */
    public static final float SAME_EPS = 1.0E-5F;

    /** 每骨骼 9 个 float 的名字（顺序与 {@link #FLOATS} 一致，日志可读性用） */
    private static String componentName(int index) {
        return switch (index) {
            case 0 -> "rotX";
            case 1 -> "rotY";
            case 2 -> "rotZ";
            case 3 -> "offX";
            case 4 -> "offY";
            case 5 -> "offZ";
            case 6 -> "scaleX";
            case 7 -> "scaleY";
            case 8 -> "scaleZ";
            default -> "float[" + index + "]";
        };
    }

    /** 压成 6 位有效数字的一行（日志用；避免打出 1.0000000000000002 这种噪音） */
    static String trim(float v) {
        if (v == 0.0F) {
            return "0";
        }
        if (!Float.isFinite(v)) {
            return String.valueOf(v);
        }
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }

    private static float[] flatten(List<float[]> rows) {
        int n = rows.isEmpty() ? 0 : rows.get(0).length;
        float[] out = new float[rows.size() * n];
        for (int i = 0; i < rows.size(); i++) {
            System.arraycopy(rows.get(i), 0, out, i * n, n);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 生产采样器：调该模组自己的渲染器，让 GeckoLib 把骨骼姿态算出来
    // ------------------------------------------------------------------

    /**
     * 用渲染器 + 傀儡实体采样一张表（生产路径）。
     *
     * 渲染器的 render 有三个坑，都在这里处理：
     * <ol>
     *   <li><b>形状（SRG 名）</b>：按**参数形状**找渲染入口，不按名字找（线上名字是 SRG），
     *       也不写死参数个数 —— 只认「实体 + 数值段 + PoseStack + MultiBufferSource + int」
     *       这个尾部三段固定的形状，实参再按真实参数类型逐个填（见 {@link RenderEntry}）。
     *       <b>上一版写死「8 参 void」</b>，而 1.20.1 的标准形状是 6 参，于是真机上一个都匹配不到；</li>
     *   <li><b>缓冲区</b>：我们不要像素，所以自己造一个「丢弃一切」的 {@link MultiBufferSource}。
     *       传 null 更省事，但那样 GeckoLib 会先撞上 NPE 才轮到我们读骨骼；</li>
     *   <li><b>姿态落地时机</b>：GeckoLib 在 {@code GeoModel.handleAnimations} 里就把骨骼姿态写好了
     *       （在 {@code actuallyRender} 的前半段），之后才动矩阵、才画。所以只要 render 走到动画
     *       那一步，哪怕后面因为拿不到 RenderType/buffer 抛异常，读到的姿态**依然是有效的**，
     *       这里只记 debug 日志、不当失败。</li>
     * </ol>
     *
     * @param renderer 该类型的渲染器（GeckoLib 的 {@code GeoEntityRenderer} 这类）
     * @param puppet   替身实体（不入世界）
     * @param parts    骨头表（顺序必须与 {@link RigBaker.Rig#parts} 一致）
     * @param driver   动作/技能动画驱动（可为 null）
     * @return 表；不可用时 {@code usable() == false}，{@link #reason()} 说明原因
     */
    public static GeoPoseTable fromRenderer(EntityRenderer<?> renderer, LivingEntity puppet,
                                            ModelRig parts, ActionDriver driver) {
        if (parts == null) {
            return bad("骨骼表还没拆出来（GeoRig 没识别出 geo 部件系统）");
        }
        return fromRenderer(renderer, puppet, null, parts.allParts().toArray(new ModelRig.Part[0]), driver);
    }

    /** 同上，直接给骨骼数组 */
    public static GeoPoseTable fromRenderer(EntityRenderer<?> renderer, LivingEntity puppet,
                                            ModelRig.Part[] parts, ActionDriver driver) {
        return fromRenderer(renderer, puppet, null, parts, driver);
    }

    /**
     * 完整入口：把「该类型的 geo 模型对象」也传进来。
     *
     * <p><b>为什么需要它（真机事故②）</b>：GeckoLib 的每帧动画有一个节流 ——
     * {@code GeoModel.handleAnimations} 在「动画时间没变 + 同一个 instanceId」时直接 return
     * （对 {@code geckolib-forge-1.20.1-4.8.4.jar} 用 {@code javap -p -c} 确认），
     * 而群集采样是同一个模型实例连续采几千帧，于是 2048 个样本逐位相同（真机日志：
     * 「行走样本塌缩（2048 个样本完全相同）」）。所以采样必须**显式驱动动画器**
     * （见 {@link GeoAnimator}），而驱动需要模型对象 —— 它就是从这里进来的。
     *
     * @param model 该类型的 geo 模型（可为 null：那时退回「渲染驱动」并在日志里说明）
     */
    public static GeoPoseTable fromRenderer(EntityRenderer<?> renderer, LivingEntity puppet, Object model,
                                            ModelRig.Part[] parts, ActionDriver driver) {
        if (renderer == null || puppet == null || parts == null || parts.length == 0) {
            return bad("渲染器/傀儡/部件表不完整");
        }
        if (parts.length > RigBaker.MAX_BONES) {
            return bad("骨骼数 " + parts.length + " 超过上限 " + RigBaker.MAX_BONES);
        }
        // ---- 找渲染入口：只认**形状**（尾部三段 PoseStack/MultiBufferSource/int + 前面的数值段）----
        // 真机病根就在这里：上一版写死「8 参 void 的 render」，而 1.20.1 的标准形状是
        // render(T, float entityYaw, float partialTick, PoseStack, MultiBufferSource, int) —— **6 参**，
        // 于是一个都匹配不到，日志里只剩一句「没有 8 参的 render」，鸭子/鹅在群集里全部静止。
        RenderEntry entry = RenderEntry.find(renderer.getClass(), puppet.getClass());
        if (entry == null) {
            // 失败必须把**候选形状**打出来（只按形状筛，不按名字 —— 线上名字是 SRG，按 "render" 过滤一个字都打不出来）
            return bad("渲染器 " + renderer.getClass().getName() + " 上找不到可用的渲染入口"
                    + "（要求 void(实体, 数值…, PoseStack, MultiBufferSource, int)；"
                    + "1.20.1 的标准形状是 render(T,float,float,PoseStack,MultiBufferSource,int)）。"
                    + "该渲染器层级上 " + RenderEntry.describeShapes(renderer.getClass()));
        }
        HordeMod.LOGGER.info("[horde] geo 姿态采样：{} 按形状找到渲染入口 {}.{}",
                renderer.getClass().getSimpleName(),
                entry.method.getDeclaringClass().getSimpleName(), entry.signature);
        // ---- 显式动画驱动口（真机事故②的修复点）----
        // 「靠渲染来驱动 GeckoLib 动画」不成立：GeoModel.handleAnimations 有
        // 「动画时间没变 + 同一个 instanceId 就整帧 return」的节流，而采样是同一实例连续几千帧。
        // 所以这里按形状找动画处理入口（AnimationProcessor.tickAnimation），采样时主动推进到目标 tick。
        // 找不到时**不放弃**：退回原来的渲染驱动，并在采样自检（build 里）用姿态差异判定，
        // 判定失败就明确报「该类型不支持动画采样」并退回绑定姿态 —— 绝不出错误画面。
        GeoAnimator.Handle animator = GeoAnimator.fromModel(model);
        if (animator == null) {
            // 失败原因由 GeoAnimator 一处给出（形状判据只有那一份，这里不再复述判据文本）
            HordeMod.LOGGER.warn("[horde] geo 姿态采样：{} 上按形状找不到动画处理入口 —— {}；"
                            + "退回渲染驱动（若样本塌缩会明确报「该类型不支持动画采样」）。诊断：{}",
                    model == null ? renderer.getClass().getSimpleName() : model.getClass().getSimpleName(),
                    model == null ? "没拿到 geo 模型对象" : GeoAnimator.whyOf(model.getClass()),
                    GeoAnimator.diagnostics().replace("\n", " | "));
        } else {
            HordeMod.LOGGER.info("[horde] geo 姿态采样：{} 通过 {} 找到动画处理入口 {} —— "
                            + "采样时按单调递增的真实 tick 显式驱动动画器（不再依赖渲染帧）",
                    model == null ? renderer.getClass().getSimpleName() : model.getClass().getSimpleName(),
                    animator.via(), animator.entry().describe());
        }
        StateApplier applier = applierFor(puppet, driver);
        // 傀儡按维度懒创建：维度 0 用传进来的那只，后面每维新建一只（instanceId 必须不同，
        // 否则 GeckoLib 会把它们当成同一个实例，动画相位会互相污染）。
        List<LivingEntity> puppets = new ArrayList<>();
        puppets.add(puppet);
        // 采样时钟必须与「采样源实际渲染的帧」共用同一个实例：墙钟是由采样源推进的，
        // 两个实例会让 age 与 tickCount 对不上（动画时间就带上了偏移）。
        TimeSampler clock = new TimeSampler();
        // ★ 绑定位移快照：先把模型复位到定义姿态再读 —— 表里的位移通道必须存**相对绑定的增量**
        //   （GeckoLib 的 GeoBone.pos 是「绑定位移 + 关键帧」，直接存会让绑定位移被加两遍 ⇒ 组件错位）
        float[] bindOff = captureBindOff(parts);
        logBindProbe(parts, bindOff);
        SampleSource source = sampleSource(renderer, entry, parts, bindOff, applier, puppet, puppets, clock, animator);
        int actions = driver == null || !driver.usable() ? 0 : driver.actionCount();
        GeoPoseTable table = build(source, parts.length, actions, true, clock);
        // 驱动统计进日志：真机排查「样本塌缩」时，这一行立刻区分「没找到入口」/「拿不到管理器」/「调用抛异常」。
        // 成功路径这一行的格式（真机核对用）：
        //   [horde] geo 动画驱动：DuckModel 通过 getAnimationProcessor() 找到
        //   AnimationProcessor.tickAnimation(GeoAnimatable,CoreGeoModel,AnimatableManager,double,AnimationState,boolean)
        //   （long@-1、double@3），管理器 OK，已驱动 2088/2088 次
        if (animator != null) {
            HordeMod.LOGGER.info("{}", driveSummary(model, animator,
                    table.usable() ? "采样成功" : "采样失败（" + table.reason() + "）"));
        } else {
            HordeMod.LOGGER.warn("[horde] geo 动画驱动：{} 没找到动画处理入口 —— {}；"
                            + "退回渲染驱动（该类型若样本塌缩会明确报不支持动画采样）",
                    model == null ? "（无模型）" : model.getClass().getSimpleName(),
                    model == null ? "模型对象为空" : GeoAnimator.whyOf(model.getClass()));
        }
        return table;
    }

    /**
     * 成功路径那一行日志（**格式固定，真机直接 grep 它**）：
     * <pre>
     *   [horde] geo 动画驱动：DuckModel 通过 getAnimationProcessor() 找到
     *   AnimationProcessor.tickAnimation(GeoAnimatable,CoreGeoModel,AnimatableManager,double,AnimationState,boolean)
     *   （long@-1、double@3），管理器 OK，已驱动 2088/2088 次 —— 采样成功
     * </pre>
     * 抽成独立方法是为了让 {@code geoHarness} 能**离线断言这一行的格式**（真机核对时少一次来回）。
     * 注意「long@-1」是正常值：真实的 {@code tickAnimation} **没有** long 形参
     * （long 是 {@code handleAnimations} 的 instanceId），下标由反射实测给出、不写死。
     */
    public static String driveSummary(Object model, GeoAnimator.Handle animator, String outcome) {
        String managerState = animator.status.managers > 0
                ? "管理器 OK" : "管理器**失败**（拿不到 AnimatableManager）";
        return "[horde] geo 动画驱动：" + (model == null ? "（无模型）" : model.getClass().getSimpleName())
                + " 通过 " + animator.via() + " 找到 " + animator.entry().full()
                + "（long@" + animator.entry().longIndex() + "、double@" + animator.entry().doubleIndex()
                + "），" + managerState + "，已驱动 " + animator.status.driveApplied + "/"
                + animator.status.driveCalls + " 次 —— " + outcome;
    }

    /**
     * 把「渲染器采样 + 状态写入 + 按维度建傀儡」拼成一个采样源。
     *
     * 采样源是唯一推进 {@link TimeSampler} 墙钟的地方（每次 read = 一帧），
     * 所以时钟与「傀儡实际被渲染的帧数」严格对齐 —— 这正是 GeckoLib 的 animTime 累加器的输入。
     */
    static SampleSource sampleSource(EntityRenderer<?> renderer, RenderEntry entry, ModelRig.Part[] parts,
                                     float[] bindOff, StateApplier applier, Entity template,
                                     List<LivingEntity> puppets, TimeSampler clock) {
        return sampleSource(renderer, entry, parts, bindOff, applier, template, puppets, clock, null);
    }

    /**
     * 采样前把模型复位到定义姿态，快照**绑定位移**（单位 1/16 格）。
     *
     * <p>复位再读是必须的：{@code ModelRig.Part.offX()} 的约定是「动画产生的额外位移」，
     * 但 GeckoLib 的 {@code GeoBone.pos} 是**绑定位移 + 关键帧**（控制器写的是
     * {@code keyframe + snapshot}），所以只有定义姿态下读到的才是绑定值，
     * 此后表里存 {@code off − bindOff} 才是着色器要的增量。
     */
    static float[] captureBindOff(ModelRig.Part[] parts) {
        float[] bindOff = new float[parts.length * 3];
        try {
            for (ModelRig.Part p : parts) {
                p.resetBind();
            }
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] geo 姿态采样：复位到定义姿态失败（{}）—— 绑定位移快照可能不准", root(t));
        }
        for (int i = 0; i < parts.length; i++) {
            bindOff[i * 3] = parts[i].offX();
            bindOff[i * 3 + 1] = parts[i].offY();
            bindOff[i * 3 + 2] = parts[i].offZ();
        }
        return bindOff;
    }

    /**
     * ★ 组件错位排查的**一次性**日志（每类型一行，收在 {@code [debug] logAnimationProbe} 后面，默认开）。
     *
     * <p>它回答「哪些骨骼的绑定位移非 0」—— 这些骨骼在修复前会被着色器**加两遍绑定位移**
     * （顶点里已经含绑定位置，off 又加了一遍），也就是玩家看到的「模型组件错位」。
     * 只给总数 + 前 4 根最大的，保证日志有界。
     */
    static void logBindProbe(ModelRig.Part[] parts, float[] bindOff) {
        boolean on;
        try {
            on = HordeConfig.COMMON.logAnimationProbe.get();
        } catch (Throwable t) {
            on = false;
        }
        if (!on || parts.length == 0) {
            return;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (Math.abs(bindOff[i * 3]) + Math.abs(bindOff[i * 3 + 1]) + Math.abs(bindOff[i * 3 + 2])
                    > 1.0E-4F) {
                order.add(i);
            }
        }
        order.sort((a, b) -> Float.compare(
                Math.abs(bindOff[b * 3]) + Math.abs(bindOff[b * 3 + 1]) + Math.abs(bindOff[b * 3 + 2]),
                Math.abs(bindOff[a * 3]) + Math.abs(bindOff[a * 3 + 1]) + Math.abs(bindOff[a * 3 + 2])));
        StringBuilder worst = new StringBuilder(96);
        for (int k = 0; k < order.size() && k < 4; k++) {
            int i = order.get(k);
            worst.append(k > 0 ? "、" : "").append(parts[i].name()).append("=(")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", bindOff[i * 3])).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", bindOff[i * 3 + 1])).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", bindOff[i * 3 + 2])).append(')');
        }
        HordeMod.LOGGER.info("[horde] geo 采样插桩：骨骼 {} 根，其中**绑定位移非 0** 的有 {} 根"
                        + "（这些骨骼以前会把绑定位移加两遍 ⇒ 组件错位；现在存的是相对绑定的增量）"
                        + "{}｜单位 1/16 格",
                parts.length, order.size(), worst.length() == 0 ? "" : "；最大几根：" + worst);
    }

    /**
     * 把「显式驱动动画器 + 渲染器采样 + 状态写入 + 按维度建傀儡」拼成一个采样源。
     *
     * <h2>采样一帧到底做了什么（顺序很关键）</h2>
     * <pre>
     *   1) 摆状态：把行走相位/强度/动作/年龄写进该维度的傀儡实体；
     *   2) 显式驱动（可选）：AnimationProcessor.tickAnimation(傀儡, 模型, 该实例的 manager,
     *      clock.tickFor(age), 新建的 AnimationState, false) —— 直接让 GeckoLib 重算骨骼；
     *   3) 调渲染入口：保持「渲染器自己的前置变换/自定义动画」这一层照旧生效
     *      （有些模组重写了 setCustomAnimations，那一步只有渲染路径会走）；
     *   4) 读骨骼。
     * </pre>
     * 2) 与 3) 用的是**同一个 tick**，所以渲染那一步自己算出来的动画时间与显式驱动完全一致，
     * 姿态不会被叠加两次（渲染内部的 handleAnimations 即便重算，结果也逐位相同）。
     *
     * <p>采样源是唯一推进 {@link TimeSampler} 墙钟的地方（每次 read = 一帧），
     * 所以时钟与「傀儡实际被渲染的帧数」严格对齐 —— 这正是 GeckoLib 的 animTime 累加器的输入。
     */
    static SampleSource sampleSource(EntityRenderer<?> renderer, RenderEntry entry, ModelRig.Part[] parts,
                                     float[] bindOff, StateApplier applier, Entity template,
                                     List<LivingEntity> puppets, TimeSampler clock,
                                     GeoAnimator.Handle animator) {
        PoseStack stack = new PoseStack();
        DiscardBuffers buffers = new DiscardBuffers();
        java.util.Set<Integer> warned = new java.util.HashSet<>();
        // 驱动失败的原因只打一次（每维度一条），否则 2048 个样本会刷两千行日志
        java.util.Set<Integer> driveWarned = new java.util.HashSet<>();
        GeoAnimator.DriveStatus status = animator == null ? null : animator.status;
        // 用匿名类而不是 lambda：{@link #build} 要读 {@code driveStatus()} 来区分
        // 「模型没有时间成分（正常）」与「动画器压根没被推动（该类型不支持采样）」。
        return new SampleSource() {
            @Override
            public boolean read(State state, float[] out) {
            int dimension = state.dimension;
            while (puppets.size() <= dimension) {
                LivingEntity created = null;
                try {
                    Entity fresh = template.getType().create(template.level());
                    if (fresh instanceof LivingEntity le) {
                        // 每个维度一个**新实例 id**：GeckoLib 的 AnimatableManager 按 id 分桶，
                        // id 不同 = 动画从零起算（tickOffset/startedAt 重新定标），
                        // 于是「时钟 -> 动画相位」的映射是确定的、可验证的。
                        le.setId(nextEntityId());
                        created = le;
                    }
                } catch (Throwable t) {
                    HordeMod.LOGGER.debug("[horde] geo 姿态采样：新建傀儡失败，退回复用（{}）", root(t));
                }
                puppets.add(created == null ? puppets.get(0) : created);
            }
            LivingEntity puppet = puppets.get(dimension);
            // 采样时钟 -> 傀儡 tickCount（见 TimeSampler：GeckoLib 的 animTime 是模型级累加器，
            // 直接写 age 会让换维度时的 tickCount 跳变被累进动画时间）。
            int tick = clock.tickFor(state.clock);
            if (applier != null) {
                applier.apply(puppet, state.limbSwing, state.walkAmount, state.headYaw, state.headPitch,
                        state.actionIndex, state.actionTick, tick);
            }
            // ---- 显式驱动动画器：推进到目标 tick（真实 tick 数，单调递增）----
            boolean drove = false;
            double beforeRot = 0.0D;
            if (animator != null) {
                Object manager = GeoAnimator.managerOf(puppet, puppet.getId());
                if (manager == null) {
                    if (driveWarned.add(dimension)) {
                        HordeMod.LOGGER.warn("[horde] geo 姿态采样：第 {} 维拿不到 GeckoLib 的 AnimatableManager"
                                        + "（傀儡 {} 上没有形状相符的动画实例缓存）—— 这一维退回渲染驱动",
                                dimension, puppet.getClass().getSimpleName());
                    }
                } else {
                    // 管理器计数由 Handle.drive 累加（manager 非空才算「建立管理器」）
                    // ★ 相位/幅度必须一起传进去（GeckoLib 的动画选择器读的就是 AnimationState 里的
                    //   getLimbSwingAmount()）：传 0 会让所有样本都掉进「待机」分支，
                    //   于是走表里根本没有走路姿态。
                    drove = animator.drive(puppet, manager, tick, state.limbSwing, state.walkAmount);
                    if (!drove && driveWarned.add(dimension)) {
                        HordeMod.LOGGER.warn("[horde] geo 姿态采样：第 {} 维显式驱动动画器失败（{}）：{} —— "
                                        + "这一维退回渲染驱动；采样自检若判定驱动全败会明确报「该类型不支持动画采样」",
                                dimension, animator.entry().describe(), status.lastFailure);
                    }
                }
            }
            if (drove) {
                // ★★★ 真机事故（本轮）：**必须在这里读骨骼，而且不能再走 render**。
                // （详见下方 render 分支的注释：GeckoLib 的 render 会用模型级 animTime 再算一遍动画，
                //   那个时间线与我们的绝对 tick 不是一个量纲，控制器 clamp 到 0 ⇒ 覆盖成动画第 0 帧。）
                readBones(parts, bindOff, out);
                clock.next();
                return true;
            }
            try {
                // 实参按方法的**真实参数类型**逐个填（6 参 / 8 参 / 9 参都能对上）——
                // 这是「兼容 GeckoLib 各版本」这一步的全部实现，见 RenderEntry.args
                entry.invoke(renderer, puppet, stack, buffers, SAMPLE_LIGHT);
            } catch (Throwable t) {
                if (warned.add(dimension)) {
                    HordeMod.LOGGER.debug("[horde] geo 姿态采样：render 在第 {} 维中途失败"
                            + "（GeckoLib 通常此时姿态已经算好，继续读骨骼）: {}", dimension, root(t));
                }
            }
            readBones(parts, bindOff, out);
            clock.next();
            return true;
            }

            @Override
            public GeoAnimator.DriveStatus driveStatus() {
                return status;
            }
        };
    }

    /** 把每根骨骼的 9 个 float 读出来（单位换算：位置 1/16 格 -> 格） */
    /**
     * 读出一行姿态。
     *
     * <h2>★ 真机事故（模型组件错位）：位移通道必须是「相对绑定姿态的增量」</h2>
     * {@link ModelRig.Part#offX()} 的接口约定就是「**动画产生的额外位移**」，着色器的公式
     * <pre>p = R · (S · (v − pivot)) + pivot + off</pre>
     * 里 {@code v} 是**绑定姿态**烘出来的顶点、{@code pivot} 是绑定枢轴，所以 {@code off} 只能加**增量**。
     * 两条路径的实际值：
     * <ul>
     *   <li>原版/Citadel（{@code ReflectiveRig}）：{@code (pos_now − pivot_bind) + off_now} —— **本来就是增量**，
     *       绑定时恰好为 0（{@code RigBaker.bake} 里先 resetBind 再取 pivot/bindOff）；</li>
     *   <li>GeckoLib（{@code GeoRig}）：直接返回 {@code GeoBone.getPosX/Y/Z()} —— Bedrock 的 {@code pos}
     *       是**绑定位移 + 关键帧增量**（GeckoLib 的控制器写的是 {@code keyframe + snapshot}），
     *       于是绑定位移被**加了两遍**：鸭子/鹅这类腿、翅膀带 pos 的模型整块部位偏掉
     *       （“模型组件错位”），而龙那种 pos 骨骼最多的模型最明显。</li>
     * </ul>
     * 修法：这里统一减去**绑定位移** {@code bindOff}（在建表时于模型复位到定义姿态后快照）。
     * 对本来就是增量的原版路径是**恒等变换**（绑定时 offX() 已经是 0），所以不可能造成回归。
     */
    private static void readBones(ModelRig.Part[] parts, float[] bindOff, float[] out) {
        int n = parts.length;
        for (int b = 0; b < n; b++) {
            ModelRig.Part p = parts[b];
            int o = b * FLOATS;
            out[o] = p.rotX();
            out[o + 1] = p.rotY();
            out[o + 2] = p.rotZ();
            // Bedrock/GeckoLib 的 position 单位是 1/16 格，着色器用的是格（与 CrowdPose 一致）
            out[o + 3] = (p.offX() - bindOff[b * 3]) / 16.0F;
            out[o + 4] = (p.offY() - bindOff[b * 3 + 1]) / 16.0F;
            out[o + 5] = (p.offZ() - bindOff[b * 3 + 2]) / 16.0F;
            out[o + 6] = p.scaleX();
            out[o + 7] = p.scaleY();
            out[o + 8] = p.scaleZ();
        }
    }

    /** 丢弃一切的缓冲源：我们只要姿态，不要像素 */
    private static final class DiscardBuffers implements MultiBufferSource {
        private static final VertexConsumer SINK = new VertexConsumer() {
            @Override
            public VertexConsumer vertex(double x, double y, double z) {
                return this;
            }

            @Override
            public VertexConsumer color(int r, int g, int b, int a) {
                return this;
            }

            @Override
            public VertexConsumer uv(float u, float v) {
                return this;
            }

            @Override
            public VertexConsumer overlayCoords(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer uv2(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer normal(float x, float y, float z) {
                return this;
            }

            @Override
            public void endVertex() {
                // 丢弃
            }

            @Override
            public void defaultColor(int r, int g, int b, int a) {
                // 丢弃
            }

            @Override
            public void unsetDefaultColor() {
                // 丢弃
            }
        };

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return SINK;
        }
    }

    /** 每次采样调用渲染入口时用的光照值（{@code 0xF000F0} = 全亮，与 EntityMeshBaker 一致） */
    public static final int SAMPLE_LIGHT = 0xF000F0;

    /**
     * 「按形状找渲染入口」的完整实现 —— 找方法 ＋ 按方法的真实参数类型填实参。
     *
     * <h2>为什么单独抽成一个类，而不是在 {@code fromRenderer} 里内联</h2>
     * 这一段就是**真机病根**（上一版写死「8 参 void render」），所以它必须能被离线验证。
     * 但离线环境里造不出真实的 {@code LivingEntity}：{@code EntityType} 的静态初始化会注册全部
     * 实体类型并调用 {@code Util.fetchChoiceType}（需要 DataFixer），而 {@code Bootstrap.bootStrap()}
     * 在离线 harness 里必然失败（见 HARNESS.md）。于是这里刻意把「找入口 + 填实参」与「造傀儡」
     * 解耦：只要给一个替身渲染器 + 一个实体类形状，就能验证 6 参 / 8 参 / 9 参三种形状都找得到、
     * 实参也都填得对（{@code GeoHarness} 的「6 参 render 入口」用例走的就是这一条）。
     *
     * <h2>形状判据（只认形状，不认名字 —— 线上名字是 SRG）</h2>
     * <pre>
     *   void 渲染方法(实体, 数值…, PoseStack, MultiBufferSource, int)
     * </pre>
     * 尾部三段固定，前面是「数值段」：1.20.1 是 {@code (entityYaw, partialTick)} 两个 float（6 参），
     * 1.16~1.18 是 {@code (x, y, z, entityYaw, partialTick)}（9 参），中间版本还出现过省掉
     * partialTick 的 8 参。**实参逐个按 {@code getParameterTypes()} 填**，所以任何形状都对得上 ——
     * 这与 {@code ProjectileBaker} 对 {@code EntityRenderDispatcher#render} 的做法是同一套判据
     * （那边服务投掷物，数值段是 pos+yaw；这里服务实体渲染器，数值段是 yaw+partialTick，
     * 所以两份实现不能互相复用，只能共用思路）。
     */
    public static final class RenderEntry {

        /** 找到的渲染方法（{@code void}、尾部三段固定） */
        public final Method method;
        /** 可读签名（日志/诊断用；线上方法名是 SRG，但参数类型是真实的） */
        public final String signature;

        private RenderEntry(Method method) {
            this.method = method;
            this.signature = signatureOf(method);
        }

        /**
         * 按形状找渲染入口。
         *
         * @param rendererClass 渲染器类（会一路往父类找）
         * @param entityClass   傀儡的实体类，只用来判「首参收不收得下它」；传 null 表示不判这一点
         * @return 找到的入口；找不到返回 null（调用方必须用 {@link #describeShapes} 把候选形状打出来）
         */
        public static RenderEntry find(Class<?> rendererClass, Class<?> entityClass) {
            if (rendererClass == null) {
                return null;
            }
            Method best = null;
            int bestScore = -1;
            for (Class<?> c = rendererClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    int n = p.length;
                    // 实体 + 至少 1 个数值参数 + 尾部三段 = 至少 5；但尾部三段本身就能表达形状，
                    // 所以下界取 4（实体 + PoseStack + MultiBufferSource + int），多留一点兼容性。
                    if (n < 4) {
                        continue;
                    }
                    if (!Entity.class.isAssignableFrom(p[0])) {
                        continue;
                    }
                    if (entityClass != null && !p[0].isAssignableFrom(entityClass)) {
                        continue;   // 首参收不下这只傀儡（渲染器只认更具体的子类时就是这样）
                    }
                    if (p[n - 1] != int.class
                            || !MultiBufferSource.class.isAssignableFrom(p[n - 2])
                            || !PoseStack.class.isAssignableFrom(p[n - 3])) {
                        continue;
                    }
                    boolean numeric = true;
                    for (int i = 1; i < n - 3; i++) {
                        if (p[i] != float.class && p[i] != double.class) {
                            numeric = false;
                            break;
                        }
                    }
                    if (!numeric) {
                        continue;
                    }
                    // 优先「非桥接方法」：泛型擦除会额外生成一个 render(Entity,...) 的桥接方法，
                    // 它也能调通，但真正的实现（render(T,...)）语义更清楚、日志里也更好认。
                    int score = (m.isBridge() || m.isSynthetic() ? 0 : 2)
                            + (entityClass != null && p[0] == entityClass ? 1 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = m;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            try {
                best.setAccessible(true);
            } catch (Throwable ignored) {
                // 公开方法不需要；这里失败也不影响后面的 invoke
            }
            return new RenderEntry(best);
        }

        /**
         * 按方法的**真实参数类型**逐个填实参。
         *
         * 数值段一律填 0（位置 0,0,0、朝向 0、部分刻 0）：
         * <ul>
         *   <li>傀儡的朝向/走路相位由 {@link StateApplier} 写在实体自己身上，这里传 0 才不会多转一次；</li>
         *   <li>部分刻<b>必须是常量 0</b>：GeckoLib 的 {@code animTime} 增量是
         *       {@code (tickCount + partialTick) - lastGameTickTime}，部分刻一变，{@link TimeSampler}
         *       算出来的「age -> tickCount」映射就对不上了（动画会以错误速度播）。</li>
         * </ul>
         */
        public Object[] args(Entity puppet, PoseStack stack, MultiBufferSource buffers, int light) {
            Class<?>[] p = method.getParameterTypes();
            int n = p.length;
            Object[] out = new Object[n];
            out[0] = puppet;
            for (int i = 1; i < n - 3; i++) {
                out[i] = p[i] == float.class ? (Object) 0.0F : (Object) 0.0D;
            }
            out[n - 3] = stack;
            out[n - 2] = buffers;
            out[n - 1] = light;
            return out;
        }

        /** 调一次渲染入口（实参按形状自动填） */
        public void invoke(Object target, Entity puppet, PoseStack stack, MultiBufferSource buffers, int light)
                throws ReflectiveOperationException {
            method.invoke(target, args(puppet, stack, buffers, light));
        }

        /**
         * 把渲染器层级上所有**形状接近**的 void 方法签名打出来（失败诊断用）。
         *
         * 为什么按形状筛而不是按名字筛：线上方法名是 SRG（如 {@code m_114485_}），
         * 按 "render" 过滤在真机上会一个字都打不出来 —— 那样「失败必须说话」这条约束等于没落实。
         * 这里只要求「void + 参数 ≥ 4 + 带一个 PoseStack」，然后把候选形状全列出来，
         * 一眼就能看出「是不是形状判据写窄了」。
         */
        public static String describeShapes(Class<?> rendererClass) {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            int shown = 0;
            for (Class<?> c = rendererClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class || m.getParameterCount() < 4) {
                        continue;
                    }
                    boolean pose = false;
                    for (Class<?> t : m.getParameterTypes()) {
                        if (PoseStack.class.isAssignableFrom(t)) {
                            pose = true;
                            break;
                        }
                    }
                    if (!pose) {
                        continue;
                    }
                    total++;
                    if (shown++ < 12) {
                        if (sb.length() > 0) {
                            sb.append('、');
                        }
                        sb.append(c.getSimpleName()).append('.').append(signatureOf(m));
                    }
                }
            }
            if (total == 0) {
                return "共有 0 个「void 且带 PoseStack」的候选形状";
            }
            return "共有 " + total + " 个候选形状：" + sb + (total > 12 ? " …" : "");
        }
    }

    /** 方法签名的可读形式：{@code render(Boolean,float,float,PoseStack,MultiBufferSource,int)} */
    static String signatureOf(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------------
    // 采样状态写入（生产路径）
    // ------------------------------------------------------------------

    /**
     * 把「群集单位的当前状态」写成「真实实体的状态」。
     *
     * 与 {@link CrowdPose} 对 EntityModel 系做的完全同一件事，只是这里不调 setupAnim ——
     * GeckoLib 的动画由渲染器自己的动画器跑，我们只负责把**输入状态**摆对。
     *
     * <p><b>最后一个参数是 puppet.tickCount</b>（不是表里的 age）。GeckoLib 的动画时间来自
     * {@code AnimationState.animationTick = GeoModel.animTime}，而那是个**模型级累加器**：
     * <pre>animTime += (puppet.tickCount + frameTime) - lastGameTickTime</pre>
     * 直接写 age 会让「换维度时的 tickCount 跳变」被原样累进动画时间。所以由
     * {@link TimeSampler} 算出一个只增不减的 tickCount，使相邻两帧的**增量恰好等于 age 的增量**。
     * 真实游戏里 tickCount 本来就每 tick 加一，这里也保持单调 —— GeckoLib 的
     * 「时间没变就跳过整帧动画」判断因此不会误触发。</p>
     */
    public static StateApplier applierFor(Entity entity, ActionDriver driver) {
        return (puppet, limbSwing, amount, headYaw, headPitch, actionIndex, actionTick, tick) -> {
            puppet.tickCount = tick;
            puppet.setPos(0.0D, 0.0D, 0.0D);
            puppet.setYRot(0.0F);
            puppet.setXRot(headPitch);
            puppet.yRotO = 0.0F;
            puppet.xRotO = headPitch;
            puppet.yBodyRot = 0.0F;
            puppet.yBodyRotO = 0.0F;
            puppet.yHeadRot = headYaw;
            puppet.yHeadRotO = headYaw;
            puppet.hurtTime = 0;
            puppet.hurtDuration = 10;
            puppet.deathTime = 0;
            puppet.attackAnim = 0.0F;
            puppet.oAttackAnim = 0.0F;
            puppet.swinging = false;
            puppet.setDeltaMovement(0.0D, 0.0D, 0.0D);
            puppet.setOnGround(true);
            puppet.setPose(net.minecraft.world.entity.Pose.STANDING);
            puppet.setSwimming(false);
            puppet.setSprinting(false);
            puppet.setShiftKeyDown(false);
            if (puppet.isUsingItem()) {
                puppet.stopUsingItem();
            }
            setWalkAnimation(puppet, limbSwing, amount);
            if (driver != null && driver.usable()) {
                driver.apply(puppet, actionIndex, actionTick);
            }
        };
    }

    /** 傀儡的自减 id：GeckoLib 用 {@code entity.getId()} 当「实例 id」，不同傀儡必须不同 */
    private static int NEXT_ID = -1000000;

    private static synchronized int nextEntityId() {
        return NEXT_ID--;
    }

    // ---- walkAnimation 的反射写入口（按**字段类型**找，不看名字：线上是 SRG 名） ----

    private static final java.util.Map<Class<?>, Field[]> WALK_FIELDS = new IdentityHashMap<>();

    /**
     * 写 {@code LivingEntity.walkAnimation.position/speed}。
     *
     * 找不到就**放弃**（debug 日志）—— 退化成「所有行走样本同一相位」，由塌缩检测兜住，
     * 不会错得很隐蔽。
     */
    private static void setWalkAnimation(LivingEntity puppet, float position, float speed) {
        Field[] pair = WALK_FIELDS.computeIfAbsent(puppet.getClass(), GeoPoseTable::findWalkFields);
        if (pair[0] == null || pair[1] == null) {
            return;
        }
        try {
            pair[0].setFloat(puppet, position);
            pair[1].setFloat(puppet, speed);
        } catch (Throwable ignored) {
            // 写不进去就保持原值
        }
    }

    private static Field[] findWalkFields(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                // WalkAnimationState 这类字段：类型名里带 walkanimation
                if (!f.getType().getName().toLowerCase(Locale.ROOT).contains("walkanimation")) {
                    continue;
                }
                List<Field> floats = new ArrayList<>();
                for (Field g : f.getType().getDeclaredFields()) {
                    if (!Modifier.isStatic(g.getModifiers()) && g.getType() == float.class) {
                        floats.add(g);
                    }
                }
                if (floats.size() < 2) {
                    continue;
                }
                // 名字能对上就用名字（position/speed），对不上按声明顺序（SRG 名就是这样的）
                Field pos = null;
                Field spd = null;
                for (Field g : floats) {
                    String n = g.getName().toLowerCase(Locale.ROOT);
                    if (pos == null && n.contains("position")) {
                        pos = g;
                    } else if (spd == null && n.contains("speed")) {
                        spd = g;
                    }
                }
                if (pos == null) {
                    pos = floats.get(0);
                }
                if (spd == null || spd == pos) {
                    spd = pos == floats.get(0) ? floats.get(1) : floats.get(0);
                }
                try {
                    f.setAccessible(true);
                    pos.setAccessible(true);
                    spd.setAccessible(true);
                    return new Field[]{pos, spd};
                } catch (Throwable ignored) {
                    // 继续找
                }
            }
        }
        HordeMod.LOGGER.debug("[horde] geo 姿态采样：{} 上找不到 walkAnimation 字段，"
                + "行走样本会退化成同一相位（由塌缩检测兜住）", cls.getSimpleName());
        return new Field[]{null, null};
    }

    // ------------------------------------------------------------------
    // 位移/缩放通道探测（复用 RigBaker 的判据，只是姿态来源换成表）
    // ------------------------------------------------------------------

    /**
     * 探测哪些骨骼会被**平移**、哪些会被**缩放**（决定要不要给它们开 GPU 通道）。
     *
     * 与 {@link RigBaker} 对 EntityModel 系做的事完全一样，只是姿态来自采样表 ——
     * geo 模型没有 {@code setupAnim}，所以「跑一组代表性输入、看哪些骨骼偏离绑定姿态」
     * 这件事只能建立在表上。
     */
    public static void probe(GeoPoseTable table, ModelRig.Part[] parts,
                             boolean[] needTrans, boolean[] needScale) {
        if (table == null || !table.usable() || parts == null || parts.length == 0) {
            return;
        }
        int n = parts.length;
        float[] bindOff = new float[n * 3];
        for (int i = 0; i < n; i++) {
            ModelRig.Part p = parts[i];
            bindOff[i * 3] = p.offX();
            bindOff[i * 3 + 1] = p.offY();
            bindOff[i * 3 + 2] = p.offZ();
        }
        float[] pose = new float[n * FLOATS];
        // 行走相位取几档 ×（二维时）几个时间点 + 待机时间轴几档 + 每个动作取几帧：
        // 只要有一次偏离绑定姿态，就该开通道。
        // 时间点必须覆盖：**待机单位也会呼吸/摆头**（位移动画在时间维里出现），
        // 如果只探 age=0，那些「只在待机时位移」的通道会被漏掉。
        int tSamples = Math.max(2, table.idleTimeSamples());
        for (int i = 0; i < WALK_PHASES; i += 4) {
            float phase = (float) (i * (Math.PI * 2.0) / WALK_PHASES);
            for (int s = 0; s < 2; s++) {
                float age = s * (IDLE_PERIOD_TICKS * 0.5F);
                if (table.readAt(phase, 1.0F, age, 0, 0.0F, pose)) {
                    scan(pose, n, bindOff, needTrans, needScale);
                }
            }
        }
        for (int s = 0; s < tSamples; s += Math.max(1, tSamples / 4)) {
            float age = s * IDLE_STEP_TICKS;
            if (table.readAt(0.0F, 0.0F, age, 0, 0.0F, pose)) {
                scan(pose, n, bindOff, needTrans, needScale);
            }
        }
        for (int a = 1; a <= table.actionCount(); a++) {
            for (int tick = 0; tick < ACTION_DURATION_TICKS; tick += 4) {
                if (table.readAt(0.0F, 1.0F, tick, a, tick, pose)) {
                    scan(pose, n, bindOff, needTrans, needScale);
                }
            }
        }
    }

    private static void scan(float[] pose, int n, float[] bindOff,
                             boolean[] needTrans, boolean[] needScale) {
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS;
            if (!needTrans[i]) {
                // 表里的位移已经是「格」，×16 与 Part.offX() 同一量纲，只判「有没有动」
                float dx = pose[o + 3] * 16.0F - bindOff[i * 3];
                float dy = pose[o + 4] * 16.0F - bindOff[i * 3 + 1];
                float dz = pose[o + 5] * 16.0F - bindOff[i * 3 + 2];
                if (Math.abs(dx) > 1.0E-4F || Math.abs(dy) > 1.0E-4F || Math.abs(dz) > 1.0E-4F) {
                    needTrans[i] = true;
                }
            }
            if (!needScale[i]
                    && (Math.abs(pose[o + 6] - 1.0F) > 1.0E-4F
                    || Math.abs(pose[o + 7] - 1.0F) > 1.0E-4F
                    || Math.abs(pose[o + 8] - 1.0F) > 1.0E-4F)) {
                needScale[i] = true;
            }
        }
    }

    /** 绑定姿态（旋转/位移 0、缩放 1）—— 采样不可用时的输出 */
    public static void bindPose(int bones, float[] out) {
        int n = Math.min(bones, out.length / FLOATS);
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS;
            out[o] = 0.0F;
            out[o + 1] = 0.0F;
            out[o + 2] = 0.0F;
            out[o + 3] = 0.0F;
            out[o + 4] = 0.0F;
            out[o + 5] = 0.0F;
            out[o + 6] = 1.0F;
            out[o + 7] = 1.0F;
            out[o + 8] = 1.0F;
        }
    }

    /** 只给日志用：把异常链最内层的原因压成一行 */
    static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.toString();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
