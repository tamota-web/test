package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.action.ActionDriver;
import net.minecraft.client.model.EntityModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 用**真实实体实例**驱动模型自己的 {@code setupAnim}，从而让群集单位的骨骼动作与真实实体完全一致。
 *
 * 输入完全对齐原版 {@code LivingEntityRenderer.render}：
 * <pre>
 *   f5 = walkAnimation.position(partialTick)      -> limbSwing
 *   f8 = min(walkAnimation.speed(partialTick), 1)  -> limbSwingAmount
 *   f7 = entity.tickCount + partialTick           -> ageInTicks
 *   f2 = yHeadRot - yBodyRot（度）                 -> netHeadYaw
 *   f6 = xRot（度）                                -> headPitch
 *   model.attackTime = getAttackAnim(...)         -> attackTime
 *   model.riding / model.young                    -> 群集单位恒为 false
 *   model.prepareMobModel(...) -> model.setupAnim(...)
 * </pre>
 *
 * 之后逐骨骼读旋转（以及 Citadel 模型会用到的位移），上传 GPU。
 * 输出布局：每骨骼 6 个 float —— xRot, yRot, zRot, offX, offY, offZ（位移已换算成格）。
 *
 * <h2>两条姿态来源（B 档）</h2>
 * <ul>
 *   <li><b>EntityModel 系</b>（原版 / Citadel / Lionfish / 灾变）：直接喂傀儡实体调
 *       {@code setupAnim}，和真实实体完全一致；</li>
 *   <li><b>geo 系</b>（GeckoLib）：<b>一个模型方法都不调</b>，只按
 *       {@link GeoPoseTable} 查表 —— 因为 {@code GeoModel} 的每实例状态
 *       （{@code lastRenderedInstance / animTime / currentProcessor}）在群集里是**共用**的，
 *       逐单位驱动必然互相污染。表在烘焙期由该模组自己的渲染器采样得到
 *       （见 {@link GeoPoseTable#fromRenderer}）。</li>
 * </ul>
 *
 * <h2>geo 查表的三个输入</h2>
 * <pre>
 *   limbSwing / limbSwingAmount  -> 行走相位 + 行走强度（走路动画的驱动量）
 *   ageInTicks                   -> **时间维**索引（age % 周期）
 * </pre>
 * 时间维是必需的：GeckoLib 的待机动画（呼吸、摆头、尾巴）由 {@code animTime} 这个**按帧累加**的
 * 时钟驱动，而不是由相位驱动。只按相位采样会把待机姿态压成 1 行（相位在 amount=0 时不影响姿态），
 * 站着不动的群集单位就会被永久冻住。
 *
 * 表不可用时（采样失败/超上限/塌缩）输出**绑定姿态**，绝不抛异常、绝不调到模型上，
 * 于是行为退回到 B 档之前的样子。
 */
public final class CrowdPose {

    /** LivingEntity.hurtDuration 默认值 */
    private static final int HURT_DURATION = 10;
    /** 每骨骼输出 float 数：xRot, yRot, zRot, offX, offY, offZ, scaleX, scaleY, scaleZ */
    public static final int STRIDE = 9;

    private final Object model;
    private final LivingEntity puppet;
    private final ModelRig.Part[] parts;
    private final boolean mob;
    private final ActionDriver actionDriver;
    /** 动作状态写到哪里（正常就是替身实体；离线验证时可以换成别的对象） */
    private final Object actionTarget;
    /** geo 系（GeckoLib）的姿态查表；null = 不是 geo 系（走 setupAnim）或表不可用 */
    private final GeoPoseTable geoTable;
    /** 表不可用的原因（null = 表可用或没给表）—— 退回绑定姿态时日志要按事实写 */
    private final String geoTableReason;
    /** 该类型是不是 geo 系（GeckoLib）—— geo 系**只允许查表**，绝不能在运行期碰模型 */
    private final boolean geo;
    private boolean aggressive;

    public CrowdPose(Object model, LivingEntity puppet, ModelRig.Part[] parts) {
        this(model, puppet, parts, null, puppet);
    }

    public CrowdPose(Object model, LivingEntity puppet, ModelRig.Part[] parts, ActionDriver actionDriver) {
        this(model, puppet, parts, actionDriver, puppet);
    }

    /**
     * 带 geo 姿态表的构造。
     *
     * <b>geo 系要走查表就必须用这个（显式带上 {@code geo = true}）</b>；
     * 只传表而不置位 {@code geo} 会让 EntityModel 系仍然走 setupAnim —— 那是故意的：
     * 「有没有表」和「是不是 geo 系」是两件事，混在一起就会把原版/Citadel 系冻成静止。
     * 所以这里**不**根据 {@code table != null} 自动置位。
     */
    public static CrowdPose ofGeo(Object model, LivingEntity puppet, ModelRig.Part[] parts,
                                  ActionDriver actionDriver, Object actionTarget, GeoPoseTable table) {
        return new CrowdPose(model, puppet, parts, actionDriver, actionTarget, table, true);
    }

    /**
     * 与 {@link #CrowdPose(Object, LivingEntity, ModelRig.Part[], ActionDriver, Object)} 同义
     * （保留这个重载只是为了让「带表」的调用点读起来一致：不置位 geo）。
     */
    public CrowdPose(Object model, LivingEntity puppet, ModelRig.Part[] parts,
                     ActionDriver actionDriver, Object actionTarget) {
        this(model, puppet, parts, actionDriver, actionTarget, null, false);
    }

    /**
     * @param geo 该模型是否属于 geo 系（GeckoLib）。是的话**只允许查表**：
     *            {@code GeoModel} 的每实例状态在群集里是共用的，运行期调模型必然互相污染。
     *            表不可用时输出绑定姿态（静止），行为退回到「B 档之前」。
     *            这个开关是显式的（生产里只有「渲染器不是 LivingEntityRenderer」那一支才会置位），
     *            不是靠 {@code instanceof EntityModel} 猜的 —— 猜错就会把 EntityModel 系也冻成静止。
     */
    public CrowdPose(Object model, LivingEntity puppet, ModelRig.Part[] parts,
                     ActionDriver actionDriver, Object actionTarget, GeoPoseTable table, boolean geo) {
        this.model = model;
        this.puppet = puppet;
        this.parts = parts;
        this.mob = puppet instanceof Mob;
        this.actionDriver = actionDriver != null && actionDriver.usable() ? actionDriver : null;
        this.actionTarget = actionTarget;
        this.geoTable = table != null && table.usable() ? table : null;
        // 表不可用时的原因（真机文案要按事实写：有表就别再说「静止姿态」）
        this.geoTableReason = table == null ? "没建表（拿不到渲染器/替身实体）"
                : (table.usable() ? null : table.reason());
        this.geo = geo;
        if (geo && this.geoTable == null) {
            HordeMod.LOGGER.warn("[horde] geo 姿态表不可用（{}），该类型退回绑定姿态（静止），"
                    + "群集单位的动画将缺失", table == null ? "没建表" : table.reason());
        }
    }

    /** 该类型是否走「geo 查表」这条路（日志/诊断用） */
    public boolean usesPoseTable() {
        return geoTable != null;
    }

    /**
     * 采样表的形状摘要（日志/诊断用），例如 {@code 行走 64 相位 × 时间 32；待机 33 行}；
     * 没走表时返回 null。
     */
    public String poseTableSummary() {
        if (geoTable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append("行走 ").append(geoTable.walkRows()).append(" 相位");
        if (geoTable.walkTimeSamples() > 1) {
            sb.append(" × 时间 ").append(geoTable.walkTimeSamples());
        } else {
            sb.append("（一维）");
        }
        if (geoTable.idleRows() > 0) {
            sb.append("；待机 ").append(geoTable.idleRows()).append(" 行");
        } else {
            sb.append("；待机 无");
        }
        if (geoTable.actionCount() > 0) {
            sb.append("；动作 ").append(geoTable.actionCount());
        }
        return sb.toString();
    }

    /**
     * 采样表**不可用**时的原因（退回绑定姿态的原因）；走表时或压根没建表时为 null。
     *
     * <p>为什么要专门留一个：真机上「明明建出表却仍报静止姿态」这种文案最误导人 ——
     * 现在有表/没表都是**按事实**写的（见 {@link CrowdRenderer} 的烘焙日志）。
     */
    public String poseTableReason() {
        return geoTableReason;
    }

    /**
     * 给定幅度，运行期会用哪张表（走表 / 待机表 / 两表插值）—— 日志与诊断用。
     *
     * <p>真机排查「站着不动」时这一项必须一眼可见：上一轮就是因为分不清
     * 「查了走表」还是「查了待机表」而多跑了一轮。
     */
    public String tableBranch(float limbSwingAmount) {
        return geoTable == null ? "无表（绑定姿态）" : geoTable.branchFor(limbSwingAmount);
    }

    /** 该类型支持几种「动作动画」（0 = 该实体没有动画状态 API） */
    public int actionCount() {
        return actionDriver == null ? 0 : actionDriver.actionCount();
    }

    public int boneCount() {
        return parts.length;
    }

    /** 部件表（GUI 列部件名用） */
    public ModelRig.Part[] parts() {
        return parts;
    }

    /**
     * 驱动一次模型动画，把每根骨骼的旋转/位移写进 {@code out}（每骨骼 {@link #STRIDE} 个 float）。
     *
     * @return 是否成功（失败时 {@code out} 不被信任，调用方应保持上一帧姿态）
     */
    public boolean evaluate(float limbSwing, float limbSwingAmount, float ageInTicks,
                            float netHeadYaw, float headPitch, float attackAnim, int hurtTicks,
                            double x, double y, double z, float bodyYaw, boolean isAggressive,
                            float[] out) {
        return evaluate(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, attackAnim,
                hurtTicks, x, y, z, bodyYaw, isAggressive, 0, 0, out);
    }

    /**
     * 同上，额外驱动**动作/技能动画**：{@code actionIndex}（0 = 无动作，k = 该实体第 k 个动画）
     * 与 {@code actionTick}（该动作已播了多少 tick）会被写到替身实体上，
     * 于是模型自己的 {@code setupAnim} 会像真实实体那样选出对应的动作动画。
     */
    public boolean evaluate(float limbSwing, float limbSwingAmount, float ageInTicks,
                            float netHeadYaw, float headPitch, float attackAnim, int hurtTicks,
                            double x, double y, double z, float bodyYaw, boolean isAggressive,
                            int actionIndex, int actionTick, float[] out) {
        try {
            // geo 系（GeckoLib）：**不碰模型**，只查表。
            // 模型方法一个都不能调 —— GeoModel 的每实例状态在群集里是共用的，调了就互相污染。
            //
            // ageInTicks（= 原版 tickCount，已从服务端同步过来）在这里是**时间维的索引**：
            // GeckoLib 的呼吸/摆头/尾巴摇摆都是时间驱动的（animTime），只按 limbSwing 相位采样的
            // 表表达不了它们 —— 站着不动的单位会被冻在同一帧上（真机表现就是「一动不动」）。
            if (geo) {
                if (geoTable != null && geoTable.readAt(limbSwing, limbSwingAmount, ageInTicks,
                        actionIndex, actionTick, out)) {
                    return true;
                }
                // 表不可用 / 输入非法：退回绑定姿态（旋转/位移 0、缩放 1），绝不抛异常、绝不碰模型
                GeoPoseTable.bindPose(parts.length, out);
                return true;
            }

            if (puppet != null) {
                puppet.tickCount = (int) ageInTicks;
                puppet.setPos(x, y, z);
                puppet.setYRot(bodyYaw);
                puppet.setXRot(headPitch);
                puppet.yRotO = bodyYaw;
                puppet.xRotO = headPitch;
                puppet.yBodyRot = bodyYaw;
                puppet.yBodyRotO = bodyYaw;
                puppet.yHeadRot = bodyYaw + netHeadYaw;
                puppet.yHeadRotO = puppet.yHeadRot;

                puppet.hurtTime = hurtTicks;
                puppet.hurtDuration = HURT_DURATION;
                puppet.deathTime = 0;
                puppet.attackAnim = attackAnim;
                puppet.oAttackAnim = attackAnim;
                puppet.swinging = attackAnim > 0.0F;
                puppet.setDeltaMovement(0.0D, 0.0D, 0.0D);
                puppet.setOnGround(true);
                puppet.setPose(net.minecraft.world.entity.Pose.STANDING);
                puppet.setSwimming(false);
                puppet.setSprinting(false);
                puppet.setShiftKeyDown(false);
                if (puppet.isUsingItem()) {
                    puppet.stopUsingItem();
                }
                if (mob && this.aggressive != isAggressive) {
                    ((Mob) puppet).setAggressive(isAggressive);
                    this.aggressive = isAggressive;
                }
                // 动作/技能动画：写到替身实体的动画状态上
                if (actionDriver != null) {
                    actionDriver.apply(actionTarget, actionIndex, actionTick);
                }
            } else if (actionDriver != null && actionTarget != null) {
                // 没有替身实体（离线验证）时也照样驱动动作状态
                actionDriver.apply(actionTarget, actionIndex, actionTick);
            }

            // 原版/Citadel/Lionfish 系：动画就是模型自己的 setupAnim
            if (model instanceof EntityModel) {
                EntityModel em = (EntityModel) model;
                em.attackTime = attackAnim;
                em.riding = false;
                em.young = false;
                em.prepareMobModel(puppet, limbSwing, limbSwingAmount, 0.0F);
                em.setupAnim(puppet, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            }

            int n = parts.length;
            for (int b = 0; b < n; b++) {
                ModelRig.Part p = parts[b];
                int o = b * STRIDE;
                out[o] = p.rotX();
                out[o + 1] = p.rotY();
                out[o + 2] = p.rotZ();
                // Citadel 的 offset 单位是 1/16 格，这里换成格
                out[o + 3] = p.offX() / 16.0F;
                out[o + 4] = p.offY() / 16.0F;
                out[o + 5] = p.offZ() / 16.0F;
                // 逐骨骼缩放（GeckoLib 会用到；其它体系恒为 1）
                out[o + 6] = p.scaleX();
                out[o + 7] = p.scaleY();
                out[o + 8] = p.scaleZ();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
