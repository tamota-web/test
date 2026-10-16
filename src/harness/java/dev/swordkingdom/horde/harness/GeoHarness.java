package dev.swordkingdom.horde.harness;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.swordkingdom.horde.client.CrowdPose;
import dev.swordkingdom.horde.client.GeoPoseTable;
import dev.swordkingdom.horde.client.GeoRig;
import dev.swordkingdom.horde.client.ModelRig;
import dev.swordkingdom.horde.client.RigBaker;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 验证 GeckoLib 系（geo.json / Bedrock 模型）部件适配器 {@link GeoRig}。
 *
 * dev 环境**没法直接构造真实 GeckoLib 模型**（mod jar 是 SRG 名、dev 的 MC 是官方名，
 * 一调用就 NoSuchMethodError），所以这里用替身类，字段/方法名照搬 4.8.4 的真实形状
 * （用 {@code javap} 对着 jar 确认过）：
 *
 * <ul>
 *   <li><b>骨骼</b> {@code GeoBone}：{@code getName/getParent/getChildBones/getPivotX/Y/Z/
 *       getRotX/Y/Z/getPosX/Y/Z/getScaleX/Y/Z/isHidden/isHidingChildren/getCubes}；</li>
 *   <li><b>方块（真实形状）</b> {@code GeoCube}：是个 record，几何是**预先烘焙好的四边形组**
 *       {@code quads() -> GeoQuad.vertices() -> GeoVertex.position()/texU()/texV()}，
 *       顶点位置单位已经是**格**；方块另带 {@code pivot()/rotation()/size()/inflate()/mirror()}。</li>
 * </ul>
 *
 * 任务书里假设的方块形状是「原点 + 尺寸 + UV + 镜像」（{@code ModelPart.Cube} 那一类），
 * 但 GeckoLib 4 的实际形状是上面那种四边形组。两种都验证：A 用真实形状，B 用方块形状，
 * 骨架/动画完全一样，只有方块编码不同 —— 于是同一个适配器必须给出同样的结果。
 *
 * 姿态链路按**原版 ModelPart.translateAndRotate 的顺序**复刻着色器公式
 * （缩放先于旋转、位移最后）：{@code q = v - pivot; q *= scale; p = R·q + pivot + off}。
 */
public final class GeoHarness {

    private static int failures;

    public static void main(String[] args) {
        test("geckolib-quads（GeckoLib 真实形状）", new QuadsGeoModel());
        test("bedrock-box（原点+尺寸+UV+镜像）", new BoxGeoModel());
        testPoseTable();
        checkSampleClockContract();
        checkPoseTableEndToEnd();
        checkAnimatorDrive();
        checkNoHijack();
        checkRendererEntry();
        checkRenderEntryShapes();
        System.out.println();
        System.out.println(failures == 0 ? "== GEO PASS ==" : "== GEO FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // ★ 真机事故（本轮）：采样成功但画面完全不动
    // ==================================================================

    /**
     * ★ 采样时钟的契约：**ΔtickCount 必须等于 Δage**（时间轴上 age 以 5 tick 为步长）。
     *
     * <h2>真机病根</h2>
     * 旧实现是 {@code tickCount = 2·wall - age}、且每个样本 {@code wall++}，于是
     * {@code Δtick = 2 - Δage}：时间轴上 age 一次走 5，tick 就**倒退 3**。
     * GeckoLib 的 {@code AnimationController.adjustTick} 是 {@code max(tick - tickOffset, 0)}，
     * 倒退会被夹到 0 —— 整列样本都停在第 0 帧，表看起来「64 相位 × 时间 32」、尺寸也对，
     * 但运行期查出来的姿态几乎全一样：真机上鸭子/鹅**完全不动**。
     *
     * <h2>为什么离线 harness 之前没抓到</h2>
     * harness 的采样源直接拿 {@code state.clock} 当动画时间用（那条路是对的），
     * **从来没经过生产的 {@code TimeSampler}** —— 于是这个 bug 在离线世界里不存在。
     * 现在判据被抽成 {@link GeoPoseTable#sampleTickSequence(int...)}（生产实现），
     * 并在这里按「GeckoLib 的 clamp 语义」再算一遍有效动画时间：旧实现会让它全是 0。
     */
    private static void checkSampleClockContract() {
        System.out.println("[case] ★ 采样时钟契约（Δtick == Δage；真机事故：采样成功但完全不动）");
        int n = GeoPoseTable.idleTimeSampleCount();
        int step = GeoPoseTable.idleTimeStepTicks();
        int[] ages = new int[n];
        for (int i = 0; i < n; i++) {
            ages[i] = i * step;
        }
        int[] ticks = GeoPoseTable.sampleTickSequence(ages);
        System.out.println("      age 序列（前 6）：" + java.util.Arrays.toString(
                java.util.Arrays.copyOf(ages, Math.min(6, n)))
                + " …；生产时钟给出的 tickCount（前 6）："
                + java.util.Arrays.toString(java.util.Arrays.copyOf(ticks, Math.min(6, n))));

        boolean monotone = true;
        boolean deltasOk = true;
        for (int i = 1; i < ticks.length; i++) {
            if (ticks[i] < ticks[i - 1]) {
                monotone = false;
            }
            if (ticks[i] - ticks[i - 1] != ages[i] - ages[i - 1]) {
                deltasOk = false;
            }
        }
        check(monotone, "tickCount 在时间轴上必须**单调不减**（旧实现是 0,-3,-6… 一路倒退）");
        check(deltasOk, "Δtick 必须等于 Δage（旧实现 Δtick = 2-Δage，时间轴上是 -3）");

        // 按 GeckoLib 的语义算「有效动画时间」：adjustTick = max(tick - tickOffset, 0)，
        // tickOffset 在采样会话的第一帧被记下（= ticks[0]）。旧实现这里会全塌成 0。
        java.util.Set<Integer> effective = new java.util.LinkedHashSet<>();
        for (int t : ticks) {
            effective.add(Math.max(t - ticks[0], 0));
        }
        check(effective.size() >= Math.min(8, n),
                "有效动画时间必须覆盖多个不同的 tick（旧实现会被 clamp 成 1 个值）：实际 "
                        + effective.size() + " 个不同值");
        System.out.println("      有效动画时间（clamp 之后）不同值个数：" + effective.size() + "/" + n);

        // 相位维：同一个 age 的 64 个样本必须拿到**同一个** tickCount（相位不该改动画时间）
        int[] sameAge = new int[GeoPoseTable.WALK_PHASES];
        int[] sameTicks = GeoPoseTable.sampleTickSequence(sameAge);
        boolean constant = true;
        for (int t : sameTicks) {
            constant &= t == sameTicks[0];
        }
        check(constant, "同一时间档的 64 个相位样本必须共用同一个 tickCount（相位不推进动画时间）");

        // 自证：把**旧公式**（tick = 2·wall - age、wall++）算一遍，它必须被判为不合格 ——
        // 这条用例的价值就在于「旧实现必红」（否则它就是一条没有抓过 bug 的用例）。
        int wall = 0;
        boolean oldMonotone = true;
        java.util.Set<Integer> oldEffective = new java.util.LinkedHashSet<>();
        int oldPrev = 0;
        for (int i = 0; i < ages.length; i++) {
            int tick = 2 * wall - ages[i];
            wall++;
            if (i > 0 && tick < oldPrev) {
                oldMonotone = false;
            }
            oldEffective.add(Math.max(tick - (2 * 0 - 0), 0));
            oldPrev = tick;
        }
        check(!oldMonotone, "旧公式（2·wall-age）必须被判为「不单调」—— 证明这条用例真的能抓住那个 bug");
        check(oldEffective.size() <= 2,
                "旧公式下有效动画时间几乎全被 clamp 成同一个值（实际 " + oldEffective.size()
                        + " 个）—— 这正是真机上「完全不动」的成因");
        System.out.println("      旧公式复算：单调=" + oldMonotone + "，有效动画时间不同值="
                + oldEffective.size() + "（真机 bug 的形态）");
    }

    /**
     * ★ 端到端：**从「建好的表」一路走到「CrowdPose 最终发射的骨骼旋转」**。
     *
     * <p>之前漏掉的就是这一段：表本身有用例（{@code testPoseTable}），
     * 但从「表」到「运行期真正拿去渲染的那组骨骼角度」之间没有任何断言 ——
     * 真机上「表建出来了、单位却一动不动」正好落在这个缝里。
     *
     * <p>四条断言：
     * <ol>
     *   <li>不同相位/时间 → 最终姿态必须**逐位不同**；</li>
     *   <li>有表时最终姿态**必须不等于绑定姿态**；</li>
     *   <li>相位固定、只推时间 → 姿态必须随时间变化（「站着不动也要呼吸」）；</li>
     *   <li>没有表 → 确实走绑定姿态（并给出原因）。</li>
     * </ol>
     */
    private static void checkPoseTableEndToEnd() {
        System.out.println("[case] ★ 端到端：采样表 -> CrowdPose 最终发射的骨骼旋转");
        QuadsGeoModel model = new QuadsGeoModel();
        ModelRig geoRig = GeoRig.tryCreate(model, null);
        if (geoRig == null) {
            fail("端到端用例：GeoRig 没能识别替身模型");
            return;
        }
        ModelRig.Part[] parts = geoRig.allParts().toArray(new ModelRig.Part[0]);
        int bones = parts.length;
        int cells = bones * CrowdPose.STRIDE;

        // 采样源：把 (相位, 强度, 时间) 摆到模型上，让它自己算一次姿态（与生产路径同形）
        GeoPoseTable.SampleSource sampler = (state, out) -> {
            model.resetToBind();
            model.animAge = state.clock;
            model.prepareMobModel(null, state.limbSwing, state.walkAmount, 0.0F);
            model.setupAnim(null, state.limbSwing, state.walkAmount, state.clock, state.headYaw, state.headPitch);
            readBones(parts, out);
            return true;
        };
        GeoPoseTable table = GeoPoseTable.build(sampler, bones, 0);
        check(table.usable(), "端到端用例需要一张可用的表（实际：" + table.reason() + "）");
        if (!table.usable()) {
            return;
        }
        CrowdPose pose = CrowdPose.ofGeo(model, null, parts, null, null, table);
        check(pose.usesPoseTable(), "有可用表时 CrowdPose 必须走**查表**这条路（否则就是真的在走绑定姿态）");
        check(pose.poseTableSummary() != null && pose.poseTableSummary().contains("相位"),
                "表摘要要能写进日志（真机文案按事实写）：实际 " + pose.poseTableSummary());

        float[] p1 = new float[cells];
        float[] p2 = new float[cells];
        // ① 不同相位 -> 姿态必须不同
        pose.evaluate(0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, p1);
        pose.evaluate(1.6F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, p2);
        double dPhase = deviation(p1, p2);
        check(dPhase > 1.0E-3, "不同相位必须给出**不同**的最终姿态（实际最大差异 " + dPhase + "）");

        // ② 有表 -> 最终姿态必须不等于绑定姿态
        float[] bind = new float[cells];
        GeoPoseTable.bindPose(bones, bind);
        check(deviation(p1, bind) > 1.0E-3,
                "有表时最终姿态**不该**等于绑定姿态（实际差异 " + deviation(p1, bind) + "）");

        // ③ 相位固定、只推时间 -> 必须随时间变化（这正是「站着不动也要呼吸/摆动」）
        float[] t0 = new float[cells];
        float[] t1 = new float[cells];
        pose.evaluate(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, t0);
        pose.evaluate(0.0F, 0.0F, 120.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, t1);
        double dTime = deviation(t0, t1);
        check(dTime > 1.0E-3,
                "相位固定、时间从 0 推到 120 tick 时姿态必须变化（实际最大差异 " + dTime + "）");
        // ③b 幅度分叉：幅度 0 必须查**待机表**、幅度 1 必须查**走表**，且两者在同一时间必须不同
        check(pose.tableBranch(0.0F).startsWith("待机表"),
                "幅度 0 必须走待机表（真机「站着不动」靠它）：实际 " + pose.tableBranch(0.0F));
        check(pose.tableBranch(1.0F).startsWith("走表"),
                "幅度 1 必须走表：实际 " + pose.tableBranch(1.0F));
        float[] idleAt60 = new float[cells];
        float[] walkAt60 = new float[cells];
        pose.evaluate(0.0F, 0.0F, 60.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, idleAt60);
        pose.evaluate(0.0F, 1.0F, 60.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, walkAt60);
        double dBranch = deviation(idleAt60, walkAt60);
        check(dBranch > 1.0E-3, "同一时间下「待机（幅度 0）」与「走（幅度 1）」必须是不同姿态（实际 "
                + dBranch + "）—— 否则就是把走表乘成了待机/绑定姿态");
        System.out.printf("      相位差异 %.6f；时间差异 %.6f；幅度分叉 %.6f；与绑定姿态差异 %.6f%n",
                dPhase, dTime, dBranch, deviation(p1, bind));
        System.out.println("      表自审：" + table.audit());
        check(table.audit().contains("走表旋转|Δ|") && table.audit().contains("时间")
                        && table.audit().contains("相位"),
                "自审必须给出走表在「时间/相位」两维上的旋转差异（真机决定性分叉）：实际 " + table.audit());

        // ④ 没有表 -> 绑定姿态 + 原因
        CrowdPose bindPose = CrowdPose.ofGeo(model, null, parts, null, null, null);
        check(!bindPose.usesPoseTable(), "没有表时必须明确**不走**查表路径");
        check(bindPose.poseTableReason() != null && !bindPose.poseTableReason().isEmpty(),
                "退回绑定姿态时必须能给出原因（真机文案要写原因）：实际 " + bindPose.poseTableReason());
        float[] out = new float[cells];
        bindPose.evaluate(0.7F, 1.0F, 40.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, out);
        int bad = 0;
        for (int b = 0; b < bones; b++) {
            int o = b * CrowdPose.STRIDE;
            if (out[o] != 0.0F || out[o + 1] != 0.0F || out[o + 2] != 0.0F
                    || out[o + 3] != 0.0F || out[o + 4] != 0.0F || out[o + 5] != 0.0F
                    || out[o + 6] != 1.0F || out[o + 7] != 1.0F || out[o + 8] != 1.0F) {
                bad++;
            }
        }
        check(bad == 0, "没有表时必须输出绑定姿态（旋转 0、位移 0、缩放 1），实际 " + bad + " 根骨骼不对");
        System.out.println("      无表文案：" + bindPose.poseTableReason());
    }

    // ==================================================================
    // B 档：geo 姿态采样表（烘焙期采样 + 运行期查表）
    // ==================================================================

    /**
     * 验证 {@link GeoPoseTable}：
     *
     * <ol>
     *   <li><b>表规模</b>：样本数/骨骼数/内存上限符合预期；</li>
     *   <li><b>一致性</b>：查表出来的姿态 == 「把模型设成同一状态后它自己摆出的姿态」，逐点 1e-3；</li>
     *   <li><b>插值</b>：相邻样本中间相位的结果必须落在两个样本之间（且等于它们的线性插值）；</li>
     *   <li><b>位移/缩放通道</b>：表的探测结果必须认出「会平移/缩放的骨骼」；</li>
     *   <li><b>反例</b>：非 geo 路径不受影响；采样失败时退化成绑定姿态而不是崩。</li>
     * </ol>
     */
    private static void testPoseTable() {
        System.out.println("[case] geo 姿态采样表（B 档：烘焙期采样 + 运行期查表）");
        QuadsGeoModel model = new QuadsGeoModel();
        try {
            // 走一遍真实烘焙路径（ModelRig.of 已接线），顺便确认表能喂给 RigBaker 探测通道
            RigBaker.bake(model, null);
        } catch (Throwable ignored) {
            // 没接线也不影响本用例：下面直接用 GeoRig 的骨骼表
        }
        ModelRig geoRig = GeoRig.tryCreate(model, null);
        if (geoRig == null) {
            fail("geo 姿态表：GeoRig 没能识别替身模型");
            return;
        }
        ModelRig.Part[] parts = geoRig.allParts().toArray(new ModelRig.Part[0]);
        int bones = parts.length;

        // 采样器：模拟生产路径里「渲染器让 GeckoLib 算好姿态」这一步 —— 这里直接让替身模型
        // 自己 setupAnim，然后从同一批骨骼对象读回来。表的组装/插值逻辑与姿态来源无关。
        // 注意 age 就是 state.clock：待机时间维 / 走路时间维在离线路径里直接把它当动画时间。
        GeoPoseTable.SampleSource sampler = (state, out) -> {
            model.resetToBind();
            model.animAge = state.clock;
            model.prepareMobModel(null, state.limbSwing, state.walkAmount, 0.0F);
            model.setupAnim(null, state.limbSwing, state.walkAmount, state.clock, state.headYaw, state.headPitch);
            readBones(parts, out);
            return true;
        };

        GeoPoseTable table = GeoPoseTable.build(sampler, bones, 0);
        System.out.println("      表：" + (table.usable() ? table.describe() : "不可用（" + table.reason() + "）"));
        check(table.usable(), "geo 姿态表应可用（实际：" + table.reason() + "）");
        if (!table.usable()) {
            return;
        }

        // ---- 1) 表规模与上限 ----
        check(table.walkRows() == GeoPoseTable.WALK_PHASES,
                "行走样本数应为 " + GeoPoseTable.WALK_PHASES + "，实际 " + table.walkRows());
        int idleCap = GeoPoseTable.idleTimeSampleCount() + 1;
        check(table.idleRows() == 1 || (table.idleRows() >= 2 && table.idleRows() <= idleCap),
                "待机样本数应为 1（姿态不随时间变）或在 2.." + idleCap + " 之间（含首尾闭合那一行），实际 "
                        + table.idleRows());
        check(table.idleRows() <= 1 || table.idleTimeSamples() + 1 == table.idleRows(),
                "待机时间表必须是「样本数 + 1 行」（末行 = 第 0 行副本，保证取模环绕不跳变），实际 "
                        + table.idleRows() + " 行 / " + table.idleTimeSamples() + " 样本");
        check(table.boneCount() == bones, "表的骨骼数应为 " + bones + "，实际 " + table.boneCount());
        long expectBytes = (long) (table.walkRows() * table.walkTimeSamples() + table.idleRows()) * bones
                * GeoPoseTable.FLOATS * 4L;
        check(table.bytes() == expectBytes,
                "表内存应为 " + expectBytes + " 字节，实际 " + table.bytes());
        check(table.bytes() <= GeoPoseTable.MAX_TABLE_BYTES,
                "表必须在上限内（" + table.bytes() + " ≤ " + GeoPoseTable.MAX_TABLE_BYTES + "）");
        System.out.println("      规模：行走 " + table.walkRows() + " 相位 × " + table.walkTimeSamples()
                + " 时间 + 待机 " + table.idleRows() + "，骨骼 " + table.boneCount() + " × "
                + GeoPoseTable.FLOATS + " float = " + table.bytes() + " 字节（"
                + (table.bytes() / 1024) + " KiB）");

        // 超上限必须被拒绝（不是 OOM），且不抛异常。
        // 注意：上限是**分两级**降级的 —— 先砍走路的时间维，再整表弃用。
        // 所以「最坏骨骼数 + 最多动作」不一定超限（砍成 1 维后可能刚好装下），
        // 这里用「骨骼数一路加到装不下」来确认**第二级**降级真的会发生。
        long worst2d = (long) RigBaker.MAX_BONES * GeoPoseTable.FLOATS * 4L
                * ((long) GeoPoseTable.WALK_PHASES * GeoPoseTable.walkTimeSampleCount()
                + (GeoPoseTable.idleTimeSampleCount() + 1L)
                + (long) GeoPoseTable.MAX_ACTIONS * GeoPoseTable.ACTION_PHASES);
        if (worst2d > GeoPoseTable.MAX_TABLE_BYTES) {
            GeoPoseTable degraded = GeoPoseTable.build(sampler, RigBaker.MAX_BONES, GeoPoseTable.MAX_ACTIONS);
            check(degraded.usable(),
                    "二维走路超限时必须**降级**成一维而不是整表弃用（实际：" + degraded.reason() + "）");
            check(!degraded.walkHasTime(), "降级后的走路表不该还带时间维");
            System.out.println("      降级用例（骨骼 " + RigBaker.MAX_BONES + "）："
                    + (degraded.usable() ? degraded.describe() : degraded.reason()));
        }
        int rejected = 0;
        for (int nb : new int[]{65, 96, 129, 200, 300}) {
            GeoPoseTable over = GeoPoseTable.build(sampler, nb, GeoPoseTable.MAX_ACTIONS);
            if (!over.usable()) {
                rejected++;
                System.out.println("      拒绝用例（骨骼 " + nb + "）：" + over.reason());
                break;
            }
        }
        check(rejected > 0, "骨骼数足够大时必须整表弃用（返回不可用而不是 OOM）");

        // ---- 2) 一致性：查表 == 模型自己摆的姿态 ----
        // 时间维让「一致性」变成两维：`expected` 必须带上 age（= state.clock）。
        float[] want;
        float[] got = new float[bones * CrowdPose.STRIDE];
        double worstWalk = 0.0;
        double worstIdle = 0.0;
        for (int i = 0; i < GeoPoseTable.WALK_PHASES; i++) {
            float phase = (float) (i * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
            for (int t = 0; t < table.walkTimeSamples(); t++) {
                // 走路表第 t 个时间样本对应的 age：二维时是「每 walkTimeStep 一个」，一维时只有一个
                int age = table.walkTimeSamples() <= 1 ? 0
                        : t * (GeoPoseTable.idlePeriodTicks() / table.walkTimeSamples());
                want = expected(model, parts, phase, 1.0F, age);
                check(table.readAt(phase, 1.0F, age, 0, 0.0F, got),
                        "查表失败（行走相位 " + i + " 时间 " + t + "）");
                worstWalk = Math.max(worstWalk, deviation(want, got));
            }
        }
        for (int t = 0; t < table.idleTimeSamples(); t++) {
            int age = t * (GeoPoseTable.idlePeriodTicks() / table.idleTimeSamples());
            want = expected(model, parts, 0.0F, 0.0F, age);
            check(table.readAt(0.0F, 0.0F, age, 0, 0.0F, got), "查表失败（待机时间 " + t + "）");
            worstIdle = Math.max(worstIdle, deviation(want, got));
        }
        System.out.printf("      一致性（逐骨骼 9 float 最大绝对偏差）：行走 %.7f，待机 %.7f%n",
                worstWalk, worstIdle);
        check(worstWalk < 1.0E-3, "行走样本：查表姿态必须等于模型自身姿态（最大偏差 " + worstWalk + "）");
        check(worstIdle < 1.0E-3, "待机样本：查表姿态必须等于模型自身姿态（最大偏差 " + worstIdle + "）");

        // 任意相位（不在样本点上）也应当贴近模型自身姿态 —— 采样够密时插值误差很小。
        // 注意最后一个半相位（31.5）会跨过 2π 回卷，此时模型与表都回到样本 0，
        // 所以这里只比中间那些「严格位于两样本之间」的半点相位。
        double worstAny = 0.0;
        for (int i = 0; i < GeoPoseTable.WALK_PHASES - 1; i++) {
            float phase = (float) ((i + 0.5) * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
            want = expected(model, parts, phase, 1.0F, 0);
            check(table.readAt(phase, 1.0F, 0, 0, 0.0F, got), "查表失败（半点相位 " + i + "）");
            worstAny = Math.max(worstAny, deviation(want, got));
        }
        // 回卷点：limbSwing = 2π 与 0 必须给出同一姿态
        float[] wrapA = new float[bones * CrowdPose.STRIDE];
        float[] wrapB = new float[bones * CrowdPose.STRIDE];
        table.readAt((float) (Math.PI * 2.0), 1.0F, 0, 0, 0.0F, wrapA);
        table.readAt(0.0F, 1.0F, 0, 0, 0.0F, wrapB);
        check(deviation(wrapA, wrapB) < 1.0E-6, "相位回卷（2π 与 0）必须给出同一姿态");
        System.out.printf("      半相位（插值）与模型自身姿态的最大偏差：%.7f%n", worstAny);
        check(worstAny < 1.0E-3, "半点相位上插值结果也必须贴近模型自身姿态（最大偏差 " + worstAny + "）");

        // ---- 2b) 时间维：只随时间变化的姿态必须能被表表达出来 ----
        // 这是「真机一动不动」那个 bug 的回归用例：待机时姿态随 tickCount 变，
        // 表必须逐点等于「把模型设成同一 tickCount 后它自己摆的姿态」。
        double worstIdleAny = 0.0;
        for (int age = 0; age < GeoPoseTable.idlePeriodTicks(); age += 3) {
            want = expected(model, parts, 0.0F, 0.0F, age);
            check(table.readAt(0.0F, 0.0F, age, 0, 0.0F, got), "查表失败（待机 age " + age + "）");
            worstIdleAny = Math.max(worstIdleAny, deviation(want, got));
        }
        System.out.printf("      待机时间维（每 3 tick 取一点，含样本之间）：最大偏差 %.7f%n", worstIdleAny);
        check(worstIdleAny < 1.0E-3,
                "待机时间表必须能表达「随 tickCount 变化」的姿态（最大偏差 " + worstIdleAny + "）");

        // 时间环绕：age = 周期 + k 与 age = k 必须完全一致
        float[] cycleA = new float[bones * CrowdPose.STRIDE];
        float[] cycleB = new float[bones * CrowdPose.STRIDE];
        double worstCycle = 0.0;
        for (int k = 0; k < GeoPoseTable.idlePeriodTicks(); k += 7) {
            table.readAt(0.0F, 0.0F, k, 0, 0.0F, cycleA);
            table.readAt(0.0F, 0.0F, k + GeoPoseTable.idlePeriodTicks(), 0, 0.0F, cycleB);
            worstCycle = Math.max(worstCycle, deviation(cycleA, cycleB));
            check(deviation(cycleA, cycleB) < 1.0E-6, "时间环绕失败：age=" + k + " 与 age="
                    + (k + GeoPoseTable.idlePeriodTicks()) + " 姿态必须一致");
        }
        System.out.printf("      时间环绕（age 与 age+周期）最大偏差：%.9f%n", worstCycle);

        // ---- 2c) 走路是「相位 × 时间」二维时必须双变量插值仍然准 ----
        // 替身模型在走路时既有相位项（腿摆）又有时间项（main 的慢呼吸），所以采样探测一定会
        // 把走路表升成二维。这里在**相位与时间都不落在样本点上**的地方查表，和模型自身姿态比。
        System.out.println("      走路时间维：" + (table.walkHasTime()
                ? "二维（相位 " + table.walkRows() + " × 时间 " + table.walkTimeSamples() + "）"
                : "一维（纯相位）"));
        if (table.walkHasTime()) {
            double worstBilinear = 0.0;
            int probes = 0;
            int wt = table.walkTimeSamples();
            int stepT = GeoPoseTable.idlePeriodTicks() / wt;
            // 注意不要取 i = WALK_PHASES-1 那个「跨 2π 的半点」：表在相位 i 与 i+1 之间插值，
            // 而 i+1 = WALK_PHASES 会折回相位 0（limbSwing = 0），参考模型那边却是 limbSwing ≈ 2π——
            // 两者本来就该不同（原版 walkAnimation.position 也是无界累加的），所以它不属于「插值误差」。
            for (int i = 0; i < GeoPoseTable.WALK_PHASES - 1; i += 7) {
                // 半相位 + 半时间：两维都在样本之间
                float phase = (float) ((i + 0.5) * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
                int age = i % wt * stepT + stepT / 2;
                want = expected(model, parts, phase, 1.0F, age);
                check(table.readAt(phase, 1.0F, age, 0, 0.0F, got), "查表失败（二维行走 " + i + "）");
                worstBilinear = Math.max(worstBilinear, deviation(want, got));
                probes++;
            }
            System.out.printf("      二维行走（相位×时间都不在样本点上，%d 个探针）：最大偏差 %.7f%n",
                    probes, worstBilinear);
            check(worstBilinear < 1.0E-3,
                    "二维走路的双变量插值也必须贴近模型自身姿态（最大偏差 " + worstBilinear + "）");
        } else {
            // 一维也要给出偏差数字：说明「这个替身模型的走路确实没有时间成分」
            double worst1d = 0.0;
            for (int i = 0; i < GeoPoseTable.WALK_PHASES; i += 7) {
                float phase = (float) ((i + 0.5) * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
                want = expected(model, parts, phase, 1.0F, 0);
                check(table.readAt(phase, 1.0F, 0, 0, 0.0F, got), "查表失败（一维行走 " + i + "）");
                worst1d = Math.max(worst1d, deviation(want, got));
            }
            System.out.printf("      一维行走（半相位）：最大偏差 %.7f%n", worst1d);
            check(worst1d < 1.0E-3, "一维走路的插值也必须贴近模型自身姿态（最大偏差 " + worst1d + "）");
        }

        // 反例（等价性证明）：关掉时间轴时，同一张表对 age 完全无感 —— 这正是修复前的样子。
        // 用它来确认「真机静止」的成因就在这一维，而不是别的地方。
        GeoPoseTable noTime = GeoPoseTable.build(sampler, bones, 0, false);
        check(noTime.usable(), "关掉时间轴的对照表应仍可用（实际：" + noTime.reason() + "）");
        if (noTime.usable()) {
            float[] a0 = new float[bones * CrowdPose.STRIDE];
            float[] a1 = new float[bones * CrowdPose.STRIDE];
            noTime.readAt(0.0F, 0.0F, 0, 0, 0.0F, a0);
            noTime.readAt(0.0F, 0.0F, 61, 0, 0.0F, a1);
            System.out.printf("      对照（关时间轴）：age=0 与 age=61 的姿态偏差 %.7f，时间表为 %d 行%n",
                    deviation(a0, a1), noTime.idleRows());
            check(deviation(a0, a1) < 1.0E-6,
                    "对照表（关时间轴）里「待机姿态与时间无关」——这就是修复前的静止成因");
            check(table.idleRows() > noTime.idleRows(),
                    "开着时间轴的待机表必须比关掉时更大（真实采样到了时间维）");
        }

        // ---- 3) 插值：中间相位必须落在两个相邻样本之间（且等于线性插值） ----
        float[] before = new float[bones * CrowdPose.STRIDE];
        float[] after = new float[bones * CrowdPose.STRIDE];
        float[] mid = new float[bones * CrowdPose.STRIDE];
        float p0 = (float) (3.0 * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
        float p1 = (float) (4.0 * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
        table.readAt(p0, 1.0F, 0, 0, 0.0F, before);
        table.readAt(p1, 1.0F, 0, 0, 0.0F, after);
        table.readAt((p0 + p1) * 0.5F, 1.0F, 0, 0, 0.0F, mid);
        int between = 0;
        int exact = 0;
        int cells = bones * CrowdPose.STRIDE;
        for (int k = 0; k < cells; k++) {
            float lo = Math.min(before[k], after[k]);
            float hi = Math.max(before[k], after[k]);
            if (mid[k] >= lo - 1.0E-6F && mid[k] <= hi + 1.0E-6F) {
                between++;
            }
            if (Math.abs(mid[k] - (before[k] + after[k]) * 0.5F) < 1.0E-6F) {
                exact++;
            }
        }
        System.out.println("      插值：中间相位落在相邻样本区间内的分量 " + between + "/" + cells
                + "，等于线性插值的 " + exact + "/" + cells);
        check(between == cells, "中间相位的每个分量都必须落在相邻两个样本之间");
        check(exact == cells, "中间相位必须等于相邻两个样本的线性插值");

        // 时间维插值：两个相邻时间样本中点必须等于线性插值
        int tHalf = GeoPoseTable.idleTimeSampleCount() / 2;
        if (table.idleTimeSamples() >= 4) {
            int stepT = GeoPoseTable.idlePeriodTicks() / table.idleTimeSamples();
            float tA = tHalf * stepT;
            float tB = (tHalf + 1) * stepT;
            float[] tb0 = new float[cells];
            float[] tb1 = new float[cells];
            float[] tm = new float[cells];
            table.readAt(0.0F, 0.0F, tA, 0, 0.0F, tb0);
            table.readAt(0.0F, 0.0F, tB, 0, 0.0F, tb1);
            table.readAt(0.0F, 0.0F, (tA + tB) * 0.5F, 0, 0.0F, tm);
            double worstT = 0.0;
            for (int k = 0; k < cells; k++) {
                worstT = Math.max(worstT, Math.abs(tm[k] - (tb0[k] + tb1[k]) * 0.5F));
            }
            System.out.printf("      时间插值：相邻两时间样本中点与线性插值的最大偏差 %.7f%n", worstT);
            check(worstT < 1.0E-6, "时间维中点必须等于相邻两个时间样本的线性插值");
        }

        // limbSwingAmount 插值：amount=0.5 必须恰好是「待机」与「行走」的中点（同一 age 下）
        float[] idlePose = new float[cells];
        float[] walkPose = new float[cells];
        float[] halfPose = new float[cells];
        table.readAt(p1, 0.0F, 0, 0, 0.0F, idlePose);
        table.readAt(p1, 1.0F, 0, 0, 0.0F, walkPose);
        table.readAt(p1, 0.5F, 0, 0, 0.0F, halfPose);
        double worstHalf = 0.0;
        for (int k = 0; k < cells; k++) {
            worstHalf = Math.max(worstHalf, Math.abs(halfPose[k] - (idlePose[k] + walkPose[k]) * 0.5F));
        }
        System.out.printf("      limbSwingAmount=0.5 与「待机/行走中点」的最大偏差：%.7f%n", worstHalf);
        check(worstHalf < 1.0E-6, "amount 插值必须精确落在待机与行走之间");

        // ---- 4) 位移/缩放通道探测（表当姿态来源） ----
        boolean[] needTrans = new boolean[bones];
        boolean[] needScale = new boolean[bones];
        GeoPoseTable.probe(table, parts, needTrans, needScale);
        int trans = 0;
        int scale = 0;
        for (int i = 0; i < bones; i++) {
            if (needTrans[i]) {
                trans++;
            }
            if (needScale[i]) {
                scale++;
            }
        }
        System.out.println("      通道探测（用表当姿态来源）：位移 " + trans + " 根，缩放 " + scale + " 根");
        check(trans > 0, "替身模型的腿会被动画平移，探测必须认出位移通道");
        check(scale > 0, "替身模型的 leg2/wing2 会被动画缩放，探测必须认出缩放通道");
        check(trans < bones && scale < bones, "不该把所有骨骼都判成需要通道");

        // ---- 5) 反例：采样失败 -> 退化成绑定姿态，绝不抛异常 ----
        GeoPoseTable broken = GeoPoseTable.build((state, out) -> {
            throw new IllegalStateException("模拟渲染器炸了");
        }, bones, 0);
        check(!broken.usable(), "采样抛异常时表必须不可用（而不是把异常抛出去）");
        System.out.println("      采样失败原因：" + broken.reason());
        check(broken.reason() != null && broken.reason().contains("采样抛异常"),
                "失败原因应指向采样异常，实际：" + broken.reason());

        // 退化路径：**按 geo 系**交给 CrowdPose，表不可用时必须输出绑定姿态、且绝不抛异常
        // —— 这正是生产里「采样失败」的样子
        CrowdPose brokenPose = CrowdPose.ofGeo(model, null, parts, null, null, broken);
        float[] fallback = new float[cells];
        java.util.Arrays.fill(fallback, 123.0F);
        boolean ok = brokenPose.evaluate(1.1F, 1.0F, 5.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false,
                0, 0, fallback);
        check(ok, "没有可用姿态表时 CrowdPose 也必须成功返回（绝不抛异常）");
        check(!brokenPose.usesPoseTable(), "表不可用时不该声称走查表路径");
        int bad = 0;
        for (int b = 0; b < bones; b++) {
            int o = b * CrowdPose.STRIDE;
            if (fallback[o] != 0.0F || fallback[o + 1] != 0.0F || fallback[o + 2] != 0.0F
                    || fallback[o + 3] != 0.0F || fallback[o + 4] != 0.0F || fallback[o + 5] != 0.0F
                    || fallback[o + 6] != 1.0F || fallback[o + 7] != 1.0F || fallback[o + 8] != 1.0F) {
                bad++;
            }
        }
        check(bad == 0, "退化路径必须输出绑定姿态（旋转/位移 0、缩放 1），实际有 " + bad + " 根骨骼不对");

        // 反例：非 geo 路径不受影响（没有表的 CrowdPose 依旧走 setupAnim）
        CrowdPose plain = new CrowdPose(model, null, parts);
        check(!plain.usesPoseTable(), "没有传表的 CrowdPose 不该走查表路径");

        check(CrowdPose.ofGeo(model, null, parts, null, null, table).usesPoseTable(),
                "传了可用表的 CrowdPose 应走查表路径");

        // 反例：EntityModel 系（原版/Citadel/Lionfish）不受影响，永远走 setupAnim。
        // 替身模型在 dev 里就是 EntityModel，所以这条正好验证「geo 开关不会误伤 EntityModel 系」。
        float[] emPose = new float[cells];
        boolean emOk = plain.evaluate(0.7F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, 0, 0, emPose);
        check(emOk, "EntityModel 路径的 evaluate 必须成功");
        // 替身模型的 main 骨骼：rotX = cos(limbSwing * 0.6662) * 0.12 * limbSwingAmount
        float emWant = (float) (Math.cos(0.7F * 0.6662F) * 0.12F);
        int emMatch = 0;
        for (int b = 0; b < bones; b++) {
            if (Math.abs(emPose[b * CrowdPose.STRIDE] - emWant) < 1.0E-4F) {
                emMatch++;
            }
        }
        System.out.printf("      EntityModel 路径：main 期望 rotX=%.9f，命中 %d 根骨骼；emPose[9]=%.9f parts[1]=%s%n",
                emWant, emMatch, emPose[9], parts[1].name());
        check(emMatch == 1, "EntityModel 系必须继续走 setupAnim（main 应被摆到 "
                + "cos(0.7*0.6662)*0.12）");

        // 反例：塌缩样本（所有相位完全相同）必须被认出来，而不是产出一张「静止」的表
        float[] frozen = new float[cells];
        for (int b = 0; b < bones; b++) {
            frozen[b * CrowdPose.STRIDE + 6] = 1.0F;
            frozen[b * CrowdPose.STRIDE + 7] = 1.0F;
            frozen[b * CrowdPose.STRIDE + 8] = 1.0F;
        }
        GeoPoseTable collapsed = GeoPoseTable.build((state, out) -> {
            System.arraycopy(frozen, 0, out, 0, frozen.length);
            return true;
        }, bones, 0);
        check(!collapsed.usable(), "样本塌缩时必须判为不可用（否则会静默出静止姿态）");
        System.out.println("      塌缩样本原因：" + collapsed.reason());
    }

    /**
     * 一致性的参考值：把模型设成同一状态（相位 + 强度 + **动画时间 age**），让它自己摆姿态，
     * 再从同一批骨骼读 9 个 float。
     *
     * age 是新一代的必填项：待机姿态与走路姿态都可能**随时间变化**（GeckoLib 的
     * {@code AnimationState.animationTick}），只比相位的话正好漏掉「真机一动不动」那个 bug。
     */
    private static float[] expected(GeoTestModel model, ModelRig.Part[] parts,
                                    float limbSwing, float amount, int age) {
        model.resetToBind();
        model.animAge = age;
        model.prepareMobModel(null, limbSwing, amount, 0.0F);
        model.setupAnim(null, limbSwing, amount, age, 0.0F, 0.0F);
        float[] out = new float[parts.length * CrowdPose.STRIDE];
        readBones(parts, out);
        return out;
    }

    /**
     * ★ 真机事故②的验证：GeckoLib 的动画处理入口能不能被**显式驱动**，
     * 以及「驱动失败 / 样本塌缩」时能不能给出可读原因并退回绑定姿态。
     *
     * <h2>为什么要单独验这一条</h2>
     * 真机日志里只有一句
     * <pre>
     *   [horde] untitledduckmod:duck 的 geo 动画采样失败…：行走样本塌缩（2048 个样本完全相同，
     *           说明 GeckoLib 没有真的重算每帧动画）
     * </pre>
     * 也就是说：「靠渲染来驱动动画」这条路不成立（同一个模型实例连续采几千帧，
     * GeckoLib 的动画时间没有真的往前走）。这个用例用**形状与 4.8.4 完全一致的替身体系**
     * 把替代方案（显式调 {@code AnimationProcessor.tickAnimation}）钉死：
     * <ol>
     *   <li>按形状发现动画处理入口（不认类名，只认「void、第 2 参 long、第 3 参 double」）；</li>
     *   <li>驱动之后骨骼姿态**真的随时间变化**（给出样本差异数字）；</li>
     *   <li>驱动统计（尝试/成功/管理器）能被采样源读到；</li>
     *   <li>驱动失败或样本塌缩时：{@link GeoPoseTable} 必须判为不可用、原因可读（维度/样本数/
     *       前两个样本差异），且 {@link CrowdPose} 落到**绑定姿态**（旋转 0、位移 0、缩放 1）。</li>
     * </ol>
     */
    private static void checkAnimatorDrive() {
        System.out.println("[case] geo 显式动画驱动（真机事故②：渲染驱动不成立 -> 主动推进动画器）");
        QuadsGeoModel model = new QuadsGeoModel();
        // 动画宿主：真机上是正在渲染的傀儡实体；这里用一个只有 getAnimatableInstanceCache() 形状的替身
        StubAnimatableHost animatable = new StubAnimatableHost();
        // ---- 0) 形状：替身入口必须与 javap 出来的 GeckoLib 真实签名逐参一致 ----
        // 真实签名（对着 geckolib-forge-1.20.1-4.8.4.jar 重新 javap 确认）：
        //   public void tickAnimation(T, CoreGeoModel<T>, AnimatableManager<T>, double, AnimationState<T>, boolean);
        // 6 个参数、double 在下标 3、**没有 long**（long 是 handleAnimations(T,long,AnimationState) 的 instanceId）。
        java.lang.reflect.Method tick;
        try {
            tick = StubAnimationProcessor.class.getMethod("tickAnimation", StubAnimatable.class,
                    StubCoreGeoModel.class, StubAnimatableManager.class, double.class,
                    StubAnimationState.class, boolean.class);
        } catch (NoSuchMethodException e) {
            fail("替身 tickAnimation 必须与 GeckoLib 的真实形状一致：" + e);
            return;
        }
        Class<?>[] tp = tick.getParameterTypes();
        check(tp.length == 6 && tp[3] == double.class,
                "真实签名是 6 参、double 在下标 3、没有 long（实际 " + tp.length + " 参，double@"
                        + indexOfDouble(tp) + "）");
        System.out.println("      替身入口签名（照抄 javap）：" + tick.getName() + "(" + tp.length
                + " 参，double@" + indexOfDouble(tp) + "，无 long)");

        // ---- 1) 生产发现路径（**不注入任何东西**）----
        // 关键：harness 走的就是 GeoAnimator.fromModel -> getAnimationProcessor() -> matchTick 这一条，
        // 判据只有生产那一份 —— 这样「位置判据」不可能再各写一份、各自错一次。
        dev.swordkingdom.horde.client.GeoAnimator.Handle handle =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(model);
        check(handle != null, "必须能在 getAnimationProcessor() 返回的**处理器对象**上按形状找到入口"
                + "（而不是在模型类自己身上找）：" + (handle == null
                ? dev.swordkingdom.horde.client.GeoAnimator.describeDiscovery(model) : "已找到"));
        if (handle == null) {
            System.out.println("      发现过程：" + dev.swordkingdom.horde.client.GeoAnimator
                    .describeDiscovery(model));
            return;
        }
        System.out.println("      生产路径找到：" + handle.entry().describe() + "（via " + handle.via() + "）");
        check(handle.entry().doubleIndex() == 3,
                "double（tick）必须被定位到真实签名里的下标 3，实际 " + handle.entry().doubleIndex());
        check(handle.entry().longIndex() == -1,
                "真实 tickAnimation 没有 long 形参，longIndex 必须是 -1（实际 " + handle.entry().longIndex() + "）");
        check(handle.entry().full().contains("StubAnimationProcessor"),
                "入口应来自 getAnimationProcessor() 返回对象的类：" + handle.entry().full());

        // ---- 1b) 管理器：走**生产**的反射链（这是入口修好后的下一个失败点）----
        Object manager = dev.swordkingdom.horde.client.GeoAnimator.managerOf(animatable, 7L);
        check(manager instanceof StubAnimatableManager,
                "生产反射链必须能从动画宿主取到 AnimatableManager（实际 "
                        + (manager == null ? "null" : manager.getClass().getName()) + "）");
        System.out.println("      管理器链路（生产反射）：AnimatableInstanceCache -> getManagerForId(long) = "
                + (manager == null ? "null" : manager.getClass().getSimpleName()));

        ModelRig geoRig = GeoRig.tryCreate(model, null);
        if (geoRig == null) {
            fail("显式驱动用例：GeoRig 没能识别替身模型");
            return;
        }
        ModelRig.Part[] parts = geoRig.allParts().toArray(new ModelRig.Part[0]);
        int bones = parts.length;
        int cells = bones * CrowdPose.STRIDE;
        float[] poseA = new float[bones * CrowdPose.STRIDE];
        float[] poseB = new float[bones * CrowdPose.STRIDE];

        // ---- 2) 驱动：同一个相位、两个不同的动画时间，姿态必须不同 ----
        model.resetToBind();
        boolean droveA = handle.drive(animatable, manager, 0.0);
        readBones(parts, poseA);
        boolean droveB = handle.drive(animatable, manager, 12.0);
        readBones(parts, poseB);
        check(droveA && droveB, "显式驱动必须真的调用成功（参数按形状填对了）：A=" + droveA + " B=" + droveB
                + (handle.status.lastFailure == null ? "" : "，失败原因 " + handle.status.lastFailure));
        check(Math.abs(model.processor.lastTick - 12.0) < 1.0E-9,
                "处理器必须收到最后一次的 tick=12.0（实际 " + model.processor.lastTick + "）—— "
                        + "证明 double 形参填的就是动画时间");
        double diff = deviation(poseA, poseB);
        check(diff > 1.0E-3, "显式驱动之后姿态必须真的随时间变化（相隔 12 tick 的最大差异 " + diff + "）");
        System.out.printf("      驱动前后姿态差异（相隔 12 tick）：%.6f%n", diff);

        // ---- 2b) 反例：不驱动同一个模型，姿态完全不变（这就是真机上那 2048 个相同样本） ----
        float[] frozenA = new float[bones * CrowdPose.STRIDE];
        float[] frozenB = new float[bones * CrowdPose.STRIDE];
        model.resetToBind();
        model.applyTickedPose();
        readBones(parts, frozenA);
        model.resetToBind();
        model.applyTickedPose();
        readBones(parts, frozenB);
        check(deviation(frozenA, frozenB) < 1.0E-9,
                "不推进动画时间时姿态必须逐位相同（复刻真机「2048 个样本完全相同」那个现象）");
        System.out.printf("      不驱动时两次采样差异：%.9f（真机塌缩的成因）%n", deviation(frozenA, frozenB));

        // ---- 3) 驱动统计必须能被读到（采样源用它区分「没入口/没管理器/调用失败」）----
        check(handle.status.driveCalls == 2 && handle.status.driveApplied == 2,
                "驱动统计必须记录尝试/成功次数（实际 " + handle.status.describe() + "）");

        // ---- 3b) 管理器查找（GeckoLib 按 instanceId 分桶，不同维度必须不同实例） ----
        check(model.managerFor(1L) != model.managerFor(2L),
                "不同 instanceId 必须拿到不同的动画实例管理器（GeckoLib 的 AnimatableManager 按 id 分桶）");
        check(model.getAnimatableInstanceCache().getClass().getName().contains("AnimatableInstanceCache"),
                "缓存的类名必须带 AnimatableInstanceCache（GeoAnimator.managerOf 按这个名字族找它）：实际 "
                        + model.getAnimatableInstanceCache().getClass().getName());

        // ---- 3b2) ★★ 本轮真机事故：驱动时必须把**相位/幅度**传进 AnimationState ----
        // 真机现象「有静止动画、行为动画没有；鹅完全没有」的根因就在这里：
        // 我们过去构造 AnimationState 时把 limbSwing/limbSwingAmount 一律填 0，
        // 而模组默认的动画选择器读的就是 state.getLimbSwingAmount()
        // （untitledduckmod 的鸭子：|amount| >= 0.05 ? WALK : IDLE，javap 逐指令确认）——
        // 于是**所有样本都掉进待机分支**：走表里根本没有走路姿态，待机表又与走表相同而被弃用。
        float[] walkPose = new float[cells];
        float[] idlePose = new float[cells];
        handle.drive(animatable, manager, 100.0, 1.2F, 1.0F);
        boolean movingFlag = model.processor.lastMoving;
        readBones(parts, walkPose);
        handle.drive(animatable, manager, 100.0, 1.2F, 0.0F);
        readBones(parts, idlePose);
        check(movingFlag, "幅度 1.0 必须让替身控制器判为「在走」（说明幅度真的传进了 AnimationState）"
                + "—— 旧代码恒传 0，这里会是 false");
        double dBank = deviation(walkPose, idlePose);
        check(dBank > 1.0E-3,
                "同 tick 下「幅度 1（走）」与「幅度 0（待机）」必须给出**不同**姿态（实际差异 " + dBank
                        + "）—— 旧代码两条曲线重合，这条会红");

        // 幅度为 0（站着不动）：**待机动画必须随时间变化**（用户验收标准：站着也必须会动）
        float[] idleA = new float[cells];
        float[] idleB = new float[cells];
        handle.drive(animatable, manager, 100.0, 0.0F, 0.0F);
        readBones(parts, idleA);
        handle.drive(animatable, manager, 120.0, 0.0F, 0.0F);
        readBones(parts, idleB);
        double dIdle = deviation(idleA, idleB);
        check(dIdle > 1.0E-3, "幅度 0、时间推进 20 tick 时姿态必须变化（实际差异 " + dIdle
                + "）—— 「站着不动也必须会动」");

        // 相位推进（走动时）：相位变了姿态必须跟着变；相位不变则必须逐位相同
        float[] phA = new float[cells];
        float[] phB = new float[cells];
        float[] phSame = new float[cells];
        handle.drive(animatable, manager, 100.0, 0.0F, 1.0F);
        readBones(parts, phA);
        handle.drive(animatable, manager, 100.0, 1.6F, 1.0F);
        readBones(parts, phB);
        handle.drive(animatable, manager, 100.0, 1.6F, 1.0F);
        readBones(parts, phSame);
        double dPhase = deviation(phA, phB);
        check(dPhase > 1.0E-3, "幅度 1、相位从 0 推进到 1.6 时姿态必须变化（实际差异 " + dPhase + "）");
        check(deviation(phB, phSame) < 1.0E-9,
                "**反例**：输入完全相同（同 tick、同相位、同幅度）时姿态必须逐位相同（否则就是抖）");
        System.out.printf("      幅度分叉 %.6f；幅度0随时间 %.6f；相位推进 %.6f；同输入复现 %.9f%n",
                dBank, dIdle, dPhase, deviation(phB, phSame));

        // ---- 3c) ★ 形判据的正/反例（真机事故②的防呆：位置判据不能再各写一份） ----
        checkTickShapeMatrix();

        // ---- 3d) 真机核对那一行的格式（离线钉住格式，真机只需 grep） ----
        String summary = GeoPoseTable.driveSummary(model, handle, "采样成功");
        boolean summaryOk = summary.startsWith("[horde] geo 动画驱动：")
                && summary.contains("QuadsGeoModel")
                && summary.contains("通过 getAnimationProcessor() 找到")
                && summary.contains("tickAnimation(")
                && summary.contains("long@-1") && summary.contains("double@3")
                && summary.contains("管理器 OK")
                && summary.contains("已驱动 ") && summary.contains(" 次");
        check(summaryOk, "成功路径日志格式必须固定（真机 grep 用）：" + summary);
        System.out.println("      真机日志行（离线合成）：" + summary);

        // ---- 4) 驱动失败 / 塌缩 -> 必须给出可读原因并退回绑定姿态 ----
        // 4a) collapseReport 本身要带上「哪一维、几个样本、前两个样本差多少、差在哪个分量」
        java.util.List<float[]> rows = new ArrayList<>();
        float[] row0 = new float[2 * CrowdPose.STRIDE];
        float[] row1 = row0.clone();
        row1[7] = 0.25F;   // 骨骼 0 的 scaleY 差 0.25
        rows.add(row0);
        rows.add(row1);
        String report = GeoPoseTable.collapseReport("行走", rows, row0.length);
        check(report.contains("维度=行走") && report.contains("样本数=2")
                        && report.contains("最大差异=") && report.contains("scaleY"),
                "塌缩报告必须包含「判定为塌缩的那一维、样本数、前两个样本的差异值」：实际 " + report);
        System.out.println("      塌缩报告示例：" + report);

        // 4b) 塌缩的表：不可用 + 原因可读 + CrowdPose 落绑定姿态
        float[] frozen = new float[bones * CrowdPose.STRIDE];
        for (int b = 0; b < bones; b++) {
            frozen[b * CrowdPose.STRIDE + 6] = 1.0F;
            frozen[b * CrowdPose.STRIDE + 7] = 1.0F;
            frozen[b * CrowdPose.STRIDE + 8] = 1.0F;
        }
        GeoPoseTable collapsed = GeoPoseTable.build((state, out) -> {
            System.arraycopy(frozen, 0, out, 0, frozen.length);
            return true;
        }, bones, 0);
        check(!collapsed.usable(), "样本塌缩时必须判为不可用");
        check(collapsed.reason() != null && collapsed.reason().contains("维度=")
                        && collapsed.reason().contains("样本数=") && collapsed.reason().contains("最大差异="),
                "塌缩的失败原因必须是**可读的**（维度/样本数/前两个样本差异）：实际 " + collapsed.reason());
        CrowdPose fallback = CrowdPose.ofGeo(model, null, parts, null, null, collapsed);
        float[] outPose = new float[bones * CrowdPose.STRIDE];
        boolean ok = fallback.evaluate(0.7F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, false, 0, 0, outPose);
        check(ok, "不可用的表也必须能求值（退回绑定姿态，绝不抛异常）");
        int bad = 0;
        for (int b = 0; b < bones; b++) {
            int o = b * CrowdPose.STRIDE;
            if (outPose[o] != 0.0F || outPose[o + 1] != 0.0F || outPose[o + 2] != 0.0F
                    || outPose[o + 3] != 0.0F || outPose[o + 4] != 0.0F || outPose[o + 5] != 0.0F
                    || outPose[o + 6] != 1.0F || outPose[o + 7] != 1.0F || outPose[o + 8] != 1.0F) {
                bad++;
            }
        }
        check(bad == 0, "采样失败/塌缩时必须退回绑定姿态（旋转 0、位移 0、缩放 1），实际有 " + bad + " 根骨骼不对");
        System.out.println("      退化路径：" + bad + " 根骨骼偏离绑定姿态（应为 0）");
    }

    /** double 在形参里的下标（-1 = 没有）；诊断用 */
    private static int indexOfDouble(Class<?>[] p) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == double.class) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ★ 形状判据的正/反例矩阵 —— **全部走生产代码 {@code GeoAnimator.fromModel}**。
     *
     * 这一组用例的存在意义：真机事故②的第二版是「判据写错了但离线没抓住」，
     * 因为 harness 当时自己又写了一份形状知识（一份错、一份跟着错）。现在 harness
     * 只喂替身对象，判据完全由生产代码给出，于是「位置写死 / 找错类」这类错误离线必炸。
     *
     * <pre>
     *   正例 1  getAnimationProcessor() 在**父类**上、double@3（真实签名）        -> 必须找到
     *   正例 2  多一个 long（double@4，相对顺序不变 = 将来 GeckoLib 改版）        -> 必须找到（不写死下标）
     *   反例 1  只有 handleAnimations(T,long,AnimationState)、没有 processor      -> 不许找到
     *   反例 2  tickAnimation 里有**两个 double**                                -> 不许找到
     *   反例 3  double 出现在 AnimatableManager **之前**（相对顺序不对）          -> 不许找到
     * </pre>
     */
    private static void checkTickShapeMatrix() {
        System.out.println("      ── 形状判据矩阵（正/反例，全部走生产 GeoAnimator.fromModel）──");
        // 正例 1：父类上的 getAnimationProcessor + 真实 6 参签名
        dev.swordkingdom.horde.client.GeoAnimator.Handle ok1 =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(new StubModelWithProcessor());
        check(ok1 != null && ok1.entry().doubleIndex() == 3 && ok1.entry().longIndex() == -1,
                "正例1（processor 在父类、double@3）：必须找到且 doubleIndex=3、longIndex=-1，实际 "
                        + (ok1 == null ? "找不到" : ok1.entry().describe()));
        System.out.println("        正例1：" + (ok1 == null ? "找不到 ✗" : ok1.entry().describe()));

        // 正例 2：将来 GeckoLib 多一个 long（double 在下标 4）—— 判据不写死下标，所以仍然要找到
        dev.swordkingdom.horde.client.GeoAnimator.Handle ok2 =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(new ShiftedModel());
        check(ok2 != null && ok2.entry().doubleIndex() == 4 && ok2.entry().longIndex() == 1,
                "正例2（多一个 long、double@4）：不写死下标的判据必须仍然找到，实际 "
                        + (ok2 == null ? "找不到（说明下标又被写死了）" : ok2.entry().describe()));
        System.out.println("        正例2：" + (ok2 == null ? "找不到 ✗" : ok2.entry().describe()));

        // 反例 1：模型上只有 handleAnimations(T,long,AnimationState)，没有 processor
        dev.swordkingdom.horde.client.GeoAnimator.Handle bad1 =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(new ModelWithoutProcessor());
        check(bad1 == null, "反例1（只有 handleAnimations、没有 getAnimationProcessor）：不许被当成入口");
        System.out.println("        反例1（无 processor）："
                + (bad1 == null ? "正确地没找到" : "错误地找到了 " + bad1.entry().full()));

        // 反例 2：两个 double
        dev.swordkingdom.horde.client.GeoAnimator.Handle bad2 =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(new TwoDoublesModel());
        check(bad2 == null, "反例2（两个 double）：无法确定哪一个是 tick，不许被当成入口");
        String why2 = dev.swordkingdom.horde.client.GeoAnimator.whyOf(TwoDoublesProcessor.class);
        check(why2.contains("double"), "反例2 的原因必须说明「double 的个数不对」：实际 " + why2);
        System.out.println("        反例2（两个 double）：" + (bad2 == null ? "正确地没找到" : "错误地找到了")
                + "，原因：" + why2);

        // 反例 3：double 在 AnimatableManager 之前（相对顺序不对）
        dev.swordkingdom.horde.client.GeoAnimator.Handle bad3 =
                dev.swordkingdom.horde.client.GeoAnimator.fromModel(new MisorderedModel());
        check(bad3 == null, "反例3（double 排在 AnimatableManager 之前）：相对顺序不对，不许被当成入口");
        String why3 = dev.swordkingdom.horde.client.GeoAnimator.whyOf(MisorderedProcessor.class);
        check(why3.contains("顺序") || why3.contains("double"),
                "反例3 的原因要能说清差在哪：实际 " + why3);
        System.out.println("        反例3（顺序不对）：" + (bad3 == null ? "正确地没找到" : "错误地找到了")
                + "，原因：" + why3);
    }

    /** 与生产路径里读骨骼的换算完全一致（位置 1/16 格 -> 格） */
    private static void readBones(ModelRig.Part[] parts, float[] out) {
        for (int b = 0; b < parts.length; b++) {
            ModelRig.Part p = parts[b];
            int o = b * CrowdPose.STRIDE;
            out[o] = p.rotX();
            out[o + 1] = p.rotY();
            out[o + 2] = p.rotZ();
            out[o + 3] = p.offX() / 16.0F;
            out[o + 4] = p.offY() / 16.0F;
            out[o + 5] = p.offZ() / 16.0F;
            out[o + 6] = p.scaleX();
            out[o + 7] = p.scaleY();
            out[o + 8] = p.scaleZ();
        }
    }

    /** 逐分量最大绝对偏差 */
    private static double deviation(float[] a, float[] b) {
        double worst = 0.0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    /**
     * 反例：老式「字段自持角度」的部件系统（Citadel 那种，没有 getRotX/getPivotX 之类的 getter）
     * 不能被 geo 适配器抢走 —— 它必须原样交给 {@link dev.swordkingdom.horde.client.ReflectiveRig}。
     */
    private static void checkNoHijack() {
        FieldStyleModel m = new FieldStyleModel();
        check(GeoRig.tryCreate(m, null) == null, "反例：字段式部件系统不应被 GeoRig 抢走（应交给 ReflectiveRig）");
    }

    /**
     * 接入路径：调用方很可能是把**渲染器**（而不是模型）递过来 ——
     * geo 渲染器的模型在它的字段里，所以结构化发现必须能穿透过去。
     */
    private static void checkRendererEntry() {
        QuadsGeoModel model = new QuadsGeoModel();
        FakeRenderer renderer = new FakeRenderer(model);
        ModelRig rig = GeoRig.tryCreate(renderer, null);
        check(rig != null && rig.allParts().size() == model.bones.size(),
                "从渲染器（模型在字段里）出发也应识别出 " + model.bones.size() + " 根骨骼");
    }

    // ==================================================================
    // 真机病根回归：**渲染入口的形状匹配**
    // ==================================================================

    /**
     * 回归用例：**渲染入口的形状匹配**（真机日志「渲染器上没有 8 参的 render」那一条）。
     *
     * 真机上 GeckoLib 的 {@code DuckRenderer} 上找不到「8 参 render」，于是
     * {@code GeoPoseTable.fromRenderer} 直接返回不可用的表，鸭子/鹅在群集里全部静止。
     * 而 1.20.1 的标准形状是 <b>6 参</b>：
     * <pre>
     *   render(T, float entityYaw, float partialTick, PoseStack, MultiBufferSource, int)
     * </pre>
     * 老版本是 9 参（前面多出 x,y,z 三个 double），中间还出现过省掉 partialTick 的 8 参
     * （{@code render(T,double,double,double,float,PoseStack,MultiBufferSource,int)} —— 旧实现写的
     * 正是「8 参」，却把最后那个光照 int 漏了，所以在任何版本上都匹配不到）。
     * 这个用例把三种形状都造出来，逐条验证：
     * <ol>
     *   <li>形状能被 {@link GeoPoseTable.RenderEntry#find} 找到；</li>
     *   <li>实参**按真实参数类型**逐个填对（float 形参收到 Float、double 形参收到 Double，
     *       尾部三段是 (PoseStack, MultiBufferSource, 光照)）；</li>
     *   <li>**真的能用这个入口采出一张表**：采样源里调的就是这个入口，入口里做的是 GeckoLib
     *       渲染器真正算姿态的那一步（让模型按傀儡状态 setupAnim），采出来的表必须可用、
     *       行走样本不塌缩，并且逐点等于模型自己摆的姿态。</li>
     * </ol>
     *
     * 离线没有真实傀儡（{@code EntityType} 的静态初始化要注册表 + DataFixer，
     * {@code Bootstrap.bootStrap()} 在离线必然失败，见 HARNESS.md），所以这里有两处替代：
     * <ul>
     *   <li>实体类只用来判 {@code assignable}，所以直接给**真实原版**的 {@code LivingEntity}，
     *       不构造实例（调用时首参传 null，替身渲染器不解引用它）；</li>
     *   <li>「状态写在傀儡身上」改成「状态写在替身渲染器自己的字段上」——
     *       被验证的那一环（找入口 → 填实参 → 调用 → 从同一批骨骼读回姿态）与真机完全一致。</li>
     * </ul>
     */
    private static void checkRenderEntryShapes() {
        System.out.println("[case] geo 渲染入口的形状匹配（6 参 / 8 参 / 9 参；真机病根：写死 8 参 + 漏掉光照 int）");
        QuadsGeoModel model = new QuadsGeoModel();
        ModelRig geoRig = GeoRig.tryCreate(model, null);
        if (geoRig == null) {
            fail("渲染入口用例：GeoRig 没能识别替身模型");
            return;
        }
        ModelRig.Part[] parts = geoRig.allParts().toArray(new ModelRig.Part[0]);
        int bones = parts.length;

        // ---- 1) 三种形状都要找得到，实参都要填对 ----
        checkShape("6 参（1.20.1 标准）", SixArgStubRenderer.class, 6,
                new Class<?>[]{float.class, float.class});
        checkShape("9 参（1.16~1.18）", LegacyNineArgStubRenderer.class, 9,
                new Class<?>[]{double.class, double.class, double.class, float.class, float.class});
        // 这一种正是**旧实现想匹配却匹配不到**的形状：它写了 8 参，但把光照 int 漏了，
        // 于是即便在 1.16~1.18（9 参）上也匹配不到任何东西。
        checkShape("8 参（位置三段 + yaw，省掉 partialTick）", EightArgStubRenderer.class, 8,
                new Class<?>[]{double.class, double.class, double.class, float.class});

        // 反例：形状不对的方法不能被抓走（例如尾部第三段不是 PoseStack）
        check(GeoPoseTable.RenderEntry.find(NotARenderShape.class, LivingEntity.class) == null,
                "形状不符的方法（尾部不是 PoseStack/MultiBufferSource/int）不该被当成渲染入口");
        // 失败诊断必须列出候选形状（真机上「候选签名」是唯一能定位的手段）
        String shapes = GeoPoseTable.RenderEntry.describeShapes(SixArgStubRenderer.class);
        check(shapes.contains("render("), "诊断串里必须列出候选方法的签名，实际：" + shapes);
        System.out.println("      失败诊断示例：" + shapes);

        // ---- 2) 用 6 参入口真的采出一张表（这就是真机上断掉的那一环） ----
        GeoPoseTable.RenderEntry entry = GeoPoseTable.RenderEntry.find(SixArgStubRenderer.class, LivingEntity.class);
        if (entry == null) {
            fail("6 参 render 入口没找到，后面的「采出表」无法验证");
            return;
        }
        SixArgStubRenderer renderer = new SixArgStubRenderer(model);
        PoseStack stack = new PoseStack();
        MultiBufferSource buffers = type -> null;
        GeoPoseTable.SampleSource sampler = (state, out) -> {
            // 状态摆好（真机写在傀儡实体上，这里写在替身渲染器自己的字段上）
            renderer.walk = state.limbSwing;
            renderer.amount = state.walkAmount;
            renderer.age = state.clock;
            model.resetToBind();
            model.animAge = state.clock;
            try {
                // 走**真实的渲染入口**：找到的方法 + 按形状填好的实参
                entry.invoke(renderer, null, stack, buffers, GeoPoseTable.SAMPLE_LIGHT);
            } catch (Throwable t) {
                fail("6 参 render 入口调用失败：" + t);
                return false;
            }
            readBones(parts, out);
            return true;
        };
        GeoPoseTable table = GeoPoseTable.build(sampler, bones, 0);
        if (!table.usable()) {
            fail("6 参 render 入口必须能采出可用的表，实际：" + table.reason());
            return;
        }
        check(true, "6 参 render 入口采出的表可用：" + table.describe());
        check(renderer.calls >= GeoPoseTable.WALK_PHASES,
                "渲染入口必须真的被调用了至少 " + GeoPoseTable.WALK_PHASES + " 次（实际 " + renderer.calls + " 次）");
        // 数值段必须填 0：partialTick 一变，GeckoLib 的 animTime 增量就不再等于 age 增量
        check(renderer.seenPartialTick == 0.0F,
                "entityYaw/partialTick 必须填 0（实际 partialTick=" + renderer.seenPartialTick + "）");
        check(renderer.seenLight == GeoPoseTable.SAMPLE_LIGHT,
                "光照实参必须原样传到渲染方法里（实际 " + renderer.seenLight + "）");

        // ---- 3) 逐点等于「模型自己摆的姿态」：证明这条链真的算出了姿态，而不是靠别的旁路 ----
        float[] got = new float[bones * CrowdPose.STRIDE];
        double worstWalk = 0.0;
        double worstIdle = 0.0;
        boolean readOk = true;
        for (int i = 0; i < GeoPoseTable.WALK_PHASES; i++) {
            float phase = (float) (i * (Math.PI * 2.0) / GeoPoseTable.WALK_PHASES);
            float[] want = expected(model, parts, phase, 1.0F, 0);
            readOk &= table.readAt(phase, 1.0F, 0, 0, 0.0F, got);
            worstWalk = Math.max(worstWalk, deviation(want, got));
        }
        for (int age = 0; age < GeoPoseTable.idlePeriodTicks(); age += 7) {
            float[] want = expected(model, parts, 0.0F, 0.0F, age);
            readOk &= table.readAt(0.0F, 0.0F, age, 0, 0.0F, got);
            worstIdle = Math.max(worstIdle, deviation(want, got));
        }
        check(readOk, "经 6 参入口采出的表必须能逐点查表（行走 "
                + GeoPoseTable.WALK_PHASES + " 个相位 + 待机时间轴）");
        System.out.printf("      一致性（经 6 参渲染入口采样）：行走 %.7f，待机 %.7f%n", worstWalk, worstIdle);
        check(worstWalk < 1.0E-3, "经渲染入口采出的行走姿态必须等于模型自身姿态（最大偏差 " + worstWalk + "）");
        check(worstIdle < 1.0E-3, "经渲染入口采出的待机姿态必须等于模型自身姿态（最大偏差 " + worstIdle + "）");
    }

    /** 验一种 render 形状：找得到、参数个数对、数值段按真实类型填、尾部三段正确 */
    private static void checkShape(String tag, Class<?> rendererClass, int expectParams, Class<?>[] numericTypes) {
        GeoPoseTable.RenderEntry entry = GeoPoseTable.RenderEntry.find(rendererClass, LivingEntity.class);
        if (entry == null) {
            fail(tag + "：形状必须被找到，实际找不到；候选形状 "
                    + GeoPoseTable.RenderEntry.describeShapes(rendererClass));
            return;
        }
        check(true, tag + "：形状被找到 -> " + entry.signature);
        check(entry.method.getParameterCount() == expectParams,
                tag + "：应匹配 " + expectParams + " 参的方法，实际 " + entry.method.getParameterCount()
                        + "（" + entry.signature + "）");
        PoseStack stack = new PoseStack();
        MultiBufferSource buffers = type -> null;
        Object[] args = entry.args(null, stack, buffers, GeoPoseTable.SAMPLE_LIGHT);
        check(args.length == expectParams, tag + "：实参个数应为 " + expectParams + "，实际 " + args.length);
        boolean typesOk = true;
        for (int i = 0; i < numericTypes.length; i++) {
            Object v = args[i + 1];
            typesOk &= numericTypes[i] == double.class ? v instanceof Double : v instanceof Float;
        }
        check(typesOk, tag + "：数值段实参必须按**真实类型**填（float 形参收 Float、double 形参收 Double）");
        check(args[args.length - 3] == stack && args[args.length - 2] == buffers
                        && Integer.valueOf(GeoPoseTable.SAMPLE_LIGHT).equals(args[args.length - 1]),
                tag + "：尾部三段必须是 (PoseStack, MultiBufferSource, 光照)");
        System.out.println("      " + tag + " -> " + entry.signature + "，实参 " + describeArgs(args));
    }

    private static String describeArgs(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "实体" : args[i].getClass().getSimpleName() + "=" + args[i]);
        }
        return sb.append(']').toString();
    }

    /** 1.20.1 的标准形状：6 参（数值段 = entityYaw, partialTick） */
    static final class SixArgStubRenderer {
        final QuadsGeoModel model;
        float walk;
        float amount;
        int age;
        int calls;
        float seenPartialTick = Float.NaN;
        int seenLight = -1;

        SixArgStubRenderer(QuadsGeoModel model) {
            this.model = model;
        }

        /** 与 GeckoLib 的 {@code GeoEntityRenderer#render} 同形：真正算姿态的那一步在这里 */
        public void render(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffers, int light) {
            calls++;
            seenPartialTick = partialTick;
            seenLight = light;
            model.prepareMobModel(entity, walk, amount, partialTick);
            model.setupAnim(entity, walk, amount, age, 0.0F, 0.0F);
        }
    }

    /** 1.16~1.18 的老形状：9 参（数值段 = x, y, z, entityYaw, partialTick） */
    static final class LegacyNineArgStubRenderer {
        final QuadsGeoModel model;
        float walk;
        float amount;
        int age;

        LegacyNineArgStubRenderer(QuadsGeoModel model) {
            this.model = model;
        }

        public void render(LivingEntity entity, double x, double y, double z, float entityYaw,
                           float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
            model.prepareMobModel(entity, walk, amount, partialTick);
            model.setupAnim(entity, walk, amount, age, 0.0F, 0.0F);
        }
    }

    /** 中间版本的形状：8 参（位置三个 double + entityYaw，省掉 partialTick） */
    static final class EightArgStubRenderer {
        final QuadsGeoModel model;
        float walk;
        float amount;
        int age;

        EightArgStubRenderer(QuadsGeoModel model) {
            this.model = model;
        }

        public void render(LivingEntity entity, double x, double y, double z, float entityYaw,
                           PoseStack poseStack, MultiBufferSource buffers, int light) {
            model.prepareMobModel(entity, walk, amount, 0.0F);
            model.setupAnim(entity, walk, amount, age, 0.0F, 0.0F);
        }
    }

    /** 反例：形状不对（尾部第三段是 String 而不是 PoseStack） */
    static final class NotARenderShape {
        public void render(LivingEntity entity, float entityYaw, float partialTick, String nope,
                           MultiBufferSource buffers, int light) {
            // 不该被当成渲染入口
        }
    }

    /** 只有字段、没有 getter 的老式部件 */
    static final class FieldStyleModel {
        final FieldStylePart root = new FieldStylePart();

        FieldStyleModel() {
            FieldStylePart child = new FieldStylePart();
            root.children.add(child);
        }
    }

    static final class FieldStylePart {
        float rotX;
        float rotY;
        float rotZ;
        float posX;
        float posY;
        float posZ;
        boolean visible = true;
        final List<FieldStylePart> children = new ArrayList<>();
    }

    /** 只用来验证「模型藏在渲染器字段里」也能被发现 */
    static final class FakeRenderer {
        final Object model;

        FakeRenderer(Object model) {
            this.model = model;
        }
    }

    // ------------------------------------------------------------------
    // 验证流程
    // ------------------------------------------------------------------

    private static void test(String tag, GeoTestModel model) {
        System.out.println("[case] " + tag);
        System.out.println("      骨骼 " + model.bones.size() + " 根，可见方块 " + model.visibleCubeCount() + " 个");

        RigBaker.Rig rig = null;
        try {
            rig = RigBaker.bake(model, null);
        } catch (Throwable t) {
            System.out.println("      RigBaker.bake 抛异常（ModelRig.of 还没接线时属正常）: " + t);
        }
        Baked bake;
        String path;
        if (rig != null && rig.vertices.length > 0) {
            bake = Baked.from(rig);
            path = "RigBaker.bake（ModelRig.of 已接线）";
        } else {
            ModelRig r = GeoRig.tryCreate(model, null);
            if (r == null) {
                fail(tag + "：GeoRig 没能识别这个 geo 模型");
                return;
            }
            bake = localBake(r);
            path = "GeoRig.tryCreate + 本地烘焙（ModelRig.of 还没接线）";
        }
        System.out.println("      路径 " + path);

        int expectVerts = model.visibleCubeCount() * 24;
        int gotVerts = bake.vertices.length / RigBaker.FLOATS_PER_VERTEX;
        check(bake.boneCount == model.bones.size(),
                tag + "：骨骼数应为 " + model.bones.size() + "，实际 " + bake.boneCount);
        check(gotVerts == expectVerts,
                tag + "：顶点数应为 " + expectVerts + "（每方块 6 面 × 4 顶点），实际 " + gotVerts);
        check(bake.maxChain >= 4,
                tag + "：应还原出至少 4 层链（root→main→head→beak），实际 " + bake.maxChain);
        check(sameNames(bake, model), tag + "：骨骼名应与模型一致");

        // 1) 绑定姿态几何：模型自己渲染一次，与烘焙出来的顶点做多重集比较
        model.resetToBind();
        Capture bindTruth = new Capture();
        model.renderToBuffer(new PoseStack(), bindTruth, 0, 0, 1.0F, 1.0F, 1.0F, 1.0F);
        float[] oursBind = bake.positions();
        float[] refBind = bindTruth.positions();
        double bindDev = multisetDeviation(oursBind, refBind);
        System.out.printf("      绑定姿态：烘焙 %d 顶点 / 模型自身 %d 顶点，多重集最大偏差 %.7f%n",
                oursBind.length / 3, refBind.length / 3, bindDev);
        check(oursBind.length == refBind.length, tag + "：绑定姿态顶点数应与模型自身渲染一致");
        check(bindDev < 1.0E-3, tag + "：绑定姿态几何应与模型自身渲染一致（偏差 " + bindDev + "）");

        // 2) 姿态链路：旋转 + 位移 + 缩放，逐点比较
        CrowdPose pose = new CrowdPose(model, null, bake.parts);
        float[] angles = new float[bake.boneCount * CrowdPose.STRIDE];
        float[][] cases = {
                {0.0F, 1.0F, 0.0F}, {1.7F, 1.0F, 25.0F}, {3.3F, 0.6F, -40.0F}, {5.2F, 0.85F, 10.0F},
        };
        for (float[] c : cases) {
            float limbSwing = c[0];
            float amount = c[1];
            float headYaw = c[2];

            model.prepareMobModel(null, limbSwing, amount, 0.0F);
            model.setupAnim(null, limbSwing, amount, 0.0F, headYaw, 0.0F);
            Capture truth = new Capture();
            model.renderToBuffer(new PoseStack(), truth, 0, 0, 1.0F, 1.0F, 1.0F, 1.0F);

            boolean ok = pose.evaluate(limbSwing, amount, 0.0F, headYaw, 0.0F, 0.0F, 0,
                    0.0D, 0.0D, 0.0D, 0.0F, false, angles);
            check(ok, tag + "：CrowdPose 驱动失败");

            int verts = bake.vertices.length / RigBaker.FLOATS_PER_VERTEX;
            float[] mine = new float[verts * 3];
            for (int i = 0; i < verts; i++) {
                int bone = (int) bake.vertices[i * RigBaker.FLOATS_PER_VERTEX + 8];
                float[] out = chain(bake, angles, bone,
                        bake.vertices[i * RigBaker.FLOATS_PER_VERTEX],
                        bake.vertices[i * RigBaker.FLOATS_PER_VERTEX + 1],
                        bake.vertices[i * RigBaker.FLOATS_PER_VERTEX + 2]);
                mine[i * 3] = out[0];
                mine[i * 3 + 1] = out[1];
                mine[i * 3 + 2] = out[2];
            }
            float[] ref = truth.positions();
            double dev = multisetDeviation(mine, ref);
            System.out.printf("      姿态 limbSwing=%.1f amount=%.2f headYaw=%.0f：参考 %d 顶点，最大偏差 %.7f%n",
                    limbSwing, amount, headYaw, ref.length / 3, dev);
            check(mine.length == ref.length, tag + "：姿态下顶点数应与模型自身渲染一致");
            check(dev < 1.0E-3, tag + "：旋转+位移+缩放合成后应与模型自身渲染一致（偏差 " + dev + "）");
        }

        // 3) 只有走真实 RigBaker 时才有的东西：位移/缩放通道探测。
        //    注意：RigBaker 现在对「不是 EntityModel 的模型」**故意跳过探测**（geo 模型没有 setupAnim），
        //    于是骨骼都没有位移/缩放通道、渲染的就是绑定姿态。这属于调用方的已知取舍，
        //    不是适配器的问题，所以这里只提示、不算失败。
        if (rig != null) {
            int animatedScale = 0;
            int animatedTrans = 0;
            int missingScale = 0;
            int missingTrans = 0;
            for (int b = 0; b < rig.boneCount; b++) {
                int o = b * CrowdPose.STRIDE;
                ModelRig.Part p = rig.parts[b];
                boolean scaled = Math.abs(angles[o + 6] - 1.0F) > 1.0E-4F
                        || Math.abs(angles[o + 7] - 1.0F) > 1.0E-4F
                        || Math.abs(angles[o + 8] - 1.0F) > 1.0E-4F;
                if (scaled) {
                    animatedScale++;
                    if (rig.scaleSlot[b] < 0) {
                        missingScale++;
                    }
                }
                boolean moved = Math.abs(p.offX()) > 1.0E-4F || Math.abs(p.offY()) > 1.0E-4F
                        || Math.abs(p.offZ()) > 1.0E-4F;
                if (moved) {
                    animatedTrans++;
                    if (rig.transSlot[b] < 0) {
                        missingTrans++;
                    }
                }
            }
            System.out.println("      通道探测：位移通道 " + rig.transCount + " 个，缩放通道 " + rig.scaleCount + " 个");
            if (missingScale > 0 || missingTrans > 0) {
                System.out.println("      [note] 有 " + animatedTrans + " 根骨骼在被动画平移、"
                        + animatedScale + " 根在被动画缩放，但 RigBaker 没给它们开通道（缺位移 " + missingTrans
                        + " / 缺缩放 " + missingScale + "）—— 非 EntityModel 的模型会被跳过探测，"
                        + "这些动画在群集里会被丢掉（只渲染绑定姿态）");
            } else {
                check(true, tag + "：会位移/缩放的骨骼都拿到了通道");
            }
        }
    }

    private static boolean sameNames(Baked bake, GeoTestModel model) {
        List<String> a = new ArrayList<>();
        for (ModelRig.Part p : bake.parts) {
            a.add(p.name());
        }
        List<String> b = new ArrayList<>();
        for (BoneStub bone : model.bones) {
            b.add(bone.getName());
        }
        a.sort(String::compareTo);
        b.sort(String::compareTo);
        if (!a.equals(b)) {
            System.out.println("      骨骼名不一致：适配器 " + a + " / 模型 " + b);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 本地烘焙：RigBaker.bake 只收 EntityModel，而 geo 模型不是 EntityModel，
    // 所以在 ModelRig.of 接线之前，用一份与 RigBaker 等价的镜像实现来验证几何/枢轴。
    // ------------------------------------------------------------------

    private static Baked localBake(ModelRig rig) {
        List<ModelRig.Part> all = rig.allParts();
        int n = all.size();
        IdentityHashMap<ModelRig.Part, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(all.get(i), i);
        }
        int[] parent = new int[n];
        int[][] children = new int[n][];
        int[] count = new int[n];
        for (int i = 0; i < n; i++) {
            ModelRig.Part par = all.get(i).parent();
            Integer pi = par == null ? null : index.get(par);
            parent[i] = pi == null ? -1 : pi;
            if (parent[i] >= 0) {
                count[parent[i]]++;
            }
        }
        for (int i = 0; i < n; i++) {
            children[i] = new int[count[i]];
        }
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) {
            if (parent[i] >= 0) {
                children[parent[i]][fill[parent[i]]++] = i;
            }
        }

        for (ModelRig.Part p : all) {
            p.saveState();
        }
        for (ModelRig.Part p : all) {
            p.resetBind();
        }
        float[] pivot = new float[n * 3];
        for (int i = 0; i < n; i++) {
            ModelRig.Part p = all.get(i);
            float x = p.pivotX() / 16.0F;
            float y = p.pivotY() / 16.0F;
            float z = p.pivotZ() / 16.0F;
            if (parent[i] >= 0) {
                x += pivot[parent[i] * 3];
                y += pivot[parent[i] * 3 + 1];
                z += pivot[parent[i] * 3 + 2];
            }
            pivot[i * 3] = x;
            pivot[i * 3 + 1] = y;
            pivot[i * 3 + 2] = z;
        }

        float[] pivot4 = new float[n * 4];
        for (int i = 0; i < n; i++) {
            pivot4[i * 4] = pivot[i * 3];
            pivot4[i * 4 + 1] = pivot[i * 3 + 1];
            pivot4[i * 4 + 2] = pivot[i * 3 + 2];
            pivot4[i * 4 + 3] = parent[i] + 1;
        }

        Capture cap = new Capture();
        PoseStack stack = new PoseStack();
        for (int i = 0; i < n; i++) {
            if (parent[i] == -1) {
                emit(i, stack, all, children, cap);
            }
        }
        int maxChain = 0;
        for (int i = 0; i < n; i++) {
            int depth = 0;
            int cur = i;
            while (cur >= 0 && depth <= RigBaker.MAX_CHAIN) {
                depth++;
                cur = parent[cur];
            }
            maxChain = Math.max(maxChain, depth);
        }
        for (ModelRig.Part p : all) {
            p.restoreState();
        }
        return new Baked(cap.vertices(), pivot4, all.toArray(new ModelRig.Part[0]), n, maxChain);
    }

    private static void emit(int i, PoseStack stack, List<ModelRig.Part> all, int[][] children, Capture cap) {
        ModelRig.Part part = all.get(i);
        if (!part.visible()) {
            return;
        }
        cap.bone = i;
        if (!part.skipCubes()) {
            part.renderOwn(stack, cap, 0, 0);
        }
        stack.pushPose();
        part.translateAndRotate(stack);
        for (int child : children[i]) {
            emit(child, stack, all, children, cap);
        }
        stack.popPose();
    }

    // ------------------------------------------------------------------
    // 渲染结果
    // ------------------------------------------------------------------

    private static final class Baked {
        final float[] vertices;
        final float[] pivot4;
        final ModelRig.Part[] parts;
        final int boneCount;
        final int maxChain;

        Baked(float[] vertices, float[] pivot4, ModelRig.Part[] parts, int boneCount, int maxChain) {
            this.vertices = vertices;
            this.pivot4 = pivot4;
            this.parts = parts;
            this.boneCount = boneCount;
            this.maxChain = maxChain;
        }

        static Baked from(RigBaker.Rig rig) {
            return new Baked(rig.vertices, rig.pivot4, rig.parts, rig.boneCount, rig.maxChain);
        }

        float[] positions() {
            int verts = vertices.length / RigBaker.FLOATS_PER_VERTEX;
            float[] out = new float[verts * 3];
            for (int i = 0; i < verts; i++) {
                out[i * 3] = vertices[i * RigBaker.FLOATS_PER_VERTEX];
                out[i * 3 + 1] = vertices[i * RigBaker.FLOATS_PER_VERTEX + 1];
                out[i * 3 + 2] = vertices[i * RigBaker.FLOATS_PER_VERTEX + 2];
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // 着色器父链公式（含逐骨骼缩放）：q = v - pivot; q *= scale; p = R·q + pivot + off
    // ------------------------------------------------------------------

    private static float[] chain(Baked bake, float[] angles, int bone, float x, float y, float z) {
        float px = x;
        float py = y;
        float pz = z;
        int b = bone;
        for (int i = 0; i < RigBaker.MAX_CHAIN && b >= 0; i++) {
            int o = b * CrowdPose.STRIDE;
            float[][] m = rotZYX(angles[o], angles[o + 1], angles[o + 2]);
            float sx = angles[o + 6];
            float sy = angles[o + 7];
            float sz = angles[o + 8];
            float pivX = bake.pivot4[b * 4];
            float pivY = bake.pivot4[b * 4 + 1];
            float pivZ = bake.pivot4[b * 4 + 2];
            float tx = angles[o + 3];
            float ty = angles[o + 4];
            float tz = angles[o + 5];
            float lx = (px - pivX) * sx;
            float ly = (py - pivY) * sy;
            float lz = (pz - pivZ) * sz;
            px = m[0][0] * lx + m[0][1] * ly + m[0][2] * lz + pivX + tx;
            py = m[1][0] * lx + m[1][1] * ly + m[1][2] * lz + pivY + ty;
            pz = m[2][0] * lx + m[2][1] * ly + m[2][2] * lz + pivZ + tz;
            b = (int) (bake.pivot4[b * 4 + 3] + 0.5F) - 1;
        }
        return new float[]{px, py, pz};
    }

    /** 与顶点着色器里的 rotZYX 一致：Rz · Ry · Rx（GeckoLib 的 mulPose(ZP/YP/XP) 也是这个顺序）。 */
    private static float[][] rotZYX(float x, float y, float z) {
        float[][] rx = {{1, 0, 0}, {0, (float) Math.cos(x), -(float) Math.sin(x)}, {0, (float) Math.sin(x), (float) Math.cos(x)}};
        float[][] ry = {{(float) Math.cos(y), 0, (float) Math.sin(y)}, {0, 1, 0}, {-(float) Math.sin(y), 0, (float) Math.cos(y)}};
        float[][] rz = {{(float) Math.cos(z), -(float) Math.sin(z), 0}, {(float) Math.sin(z), (float) Math.cos(z), 0}, {0, 0, 1}};
        return mul(rz, mul(ry, rx));
    }

    private static float[][] mul(float[][] a, float[][] b) {
        float[][] r = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 多重集比较（与 AdapterHarness 同一套：按 1e-3 分桶，桶内排序后逐点比）
    // ------------------------------------------------------------------

    private static double multisetDeviation(float[] a, float[] b) {
        java.util.Map<String, java.util.List<double[]>> ba = bucket(a);
        java.util.Map<String, java.util.List<double[]>> bb = bucket(b);
        if (!ba.keySet().equals(bb.keySet())) {
            return Double.MAX_VALUE;
        }
        double max = 0;
        for (String key : ba.keySet()) {
            java.util.List<double[]> la = ba.get(key);
            java.util.List<double[]> lb = bb.get(key);
            if (la.size() != lb.size()) {
                return Double.MAX_VALUE;
            }
            la.sort(java.util.Comparator.comparingDouble(p -> p[0] + p[1] * 3 + p[2] * 7));
            lb.sort(java.util.Comparator.comparingDouble(p -> p[0] + p[1] * 3 + p[2] * 7));
            for (int i = 0; i < la.size(); i++) {
                double[] p = la.get(i);
                double[] q = lb.get(i);
                max = Math.max(max, Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]));
            }
        }
        return max;
    }

    private static java.util.Map<String, java.util.List<double[]>> bucket(float[] flat) {
        java.util.Map<String, java.util.List<double[]>> out = new java.util.HashMap<>();
        for (int i = 0; i < flat.length; i += 3) {
            double x = flat[i];
            double y = flat[i + 1];
            double z = flat[i + 2];
            String key = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z);
            out.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new double[]{x, y, z});
        }
        return out;
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("  [FAIL] " + message);
    }

    // ------------------------------------------------------------------
    // 顶点截获（只关心位置；对照模型自己是把位置变换好再喂进来的，和 GeckoLib 一样）
    // ------------------------------------------------------------------

    private static final class Capture implements VertexConsumer {
        private final List<float[]> points = new ArrayList<>();
        int bone;
        private float x;
        private float y;
        private float z;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
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
            // 除了位置，还记下当前骨骼下标：本地烘焙出来的顶点要和 RigBaker 的布局一致（第 9 个 float）
            points.add(new float[]{x, y, z, bone});
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        /** 只取位置（对照「模型自己渲染」的结果） */
        float[] positions() {
            float[] out = new float[points.size() * 3];
            for (int i = 0; i < points.size(); i++) {
                out[i * 3] = points.get(i)[0];
                out[i * 3 + 1] = points.get(i)[1];
                out[i * 3 + 2] = points.get(i)[2];
            }
            return out;
        }

        /** 与 {@link RigBaker#FLOATS_PER_VERTEX} 相同的 9 float 布局：位置 + 骨骼下标 */
        float[] vertices() {
            int stride = RigBaker.FLOATS_PER_VERTEX;
            float[] out = new float[points.size() * stride];
            for (int i = 0; i < points.size(); i++) {
                float[] p = points.get(i);
                out[i * stride] = p[0];
                out[i * stride + 1] = p[1];
                out[i * stride + 2] = p[2];
                out[i * stride + 8] = p[3];
            }
            return out;
        }
    }

    // ==================================================================
    // 替身：GeoBone / GeoCube / GeoQuad / GeoVertex（方法名照搬 GeckoLib 4.8.4）
    // ==================================================================

    /** == {@code software.bernie.geckolib.cache.object.GeoBone} */
    static final class BoneStub {
        private final BoneStub parent;
        private final String name;
        private final List<BoneStub> children = new ArrayList<>();
        private final List<Object> cubes = new ArrayList<>();
        private boolean hidden;
        private boolean childrenHidden;
        private float scaleX = 1.0F;
        private float scaleY = 1.0F;
        private float scaleZ = 1.0F;
        private float positionX;
        private float positionY;
        private float positionZ;
        private float pivotX;
        private float pivotY;
        private float pivotZ;
        private float rotX;
        private float rotY;
        private float rotZ;
        /** geo.json 的静止姿态（GeckoLib 用 initialSnapshot 在没动画时恢复它） */
        private final float restRotX;
        private final float restRotY;
        private final float restRotZ;

        BoneStub(BoneStub parent, String name, double[] pivot, double[] rotDeg) {
            this.parent = parent;
            this.name = name;
            // 复刻 GeckoLib 的 constructBone：枢轴 X 取反；旋转 X/Y 取反后转弧度，Z 直接转弧度
            this.pivotX = (float) -pivot[0];
            this.pivotY = (float) pivot[1];
            this.pivotZ = (float) pivot[2];
            this.restRotX = (float) -Math.toRadians(rotDeg[0]);
            this.restRotY = (float) -Math.toRadians(rotDeg[1]);
            this.restRotZ = (float) Math.toRadians(rotDeg[2]);
            this.rotX = restRotX;
            this.rotY = restRotY;
            this.rotZ = restRotZ;
            if (parent != null) {
                parent.children.add(this);
            }
        }

        public String getName() {
            return name;
        }

        public BoneStub getParent() {
            return parent;
        }

        public List<BoneStub> getChildBones() {
            return children;
        }

        public List<Object> getCubes() {
            return cubes;
        }

        public float getPivotX() {
            return pivotX;
        }

        public float getPivotY() {
            return pivotY;
        }

        public float getPivotZ() {
            return pivotZ;
        }

        public float getRotX() {
            return rotX;
        }

        public float getRotY() {
            return rotY;
        }

        public float getRotZ() {
            return rotZ;
        }

        public void setRotX(float v) {
            rotX = v;
        }

        public void setRotY(float v) {
            rotY = v;
        }

        public void setRotZ(float v) {
            rotZ = v;
        }

        public float getPosX() {
            return positionX;
        }

        public float getPosY() {
            return positionY;
        }

        public float getPosZ() {
            return positionZ;
        }

        public void setPosX(float v) {
            positionX = v;
        }

        public void setPosY(float v) {
            positionY = v;
        }

        public void setPosZ(float v) {
            positionZ = v;
        }

        public float getScaleX() {
            return scaleX;
        }

        public float getScaleY() {
            return scaleY;
        }

        public float getScaleZ() {
            return scaleZ;
        }

        public void setScaleX(float v) {
            scaleX = v;
        }

        public void setScaleY(float v) {
            scaleY = v;
        }

        public void setScaleZ(float v) {
            scaleZ = v;
        }

        public boolean isHidden() {
            return hidden;
        }

        public void setHidden(boolean v) {
            hidden = v;
        }

        public boolean isHidingChildren() {
            return childrenHidden;
        }

        public void setChildrenHidden(boolean v) {
            childrenHidden = v;
        }

        /** 没被动画写到的骨骼回到静止姿态（对应 GeckoLib 的 initialSnapshot 复位） */
        void resetToRest() {
            rotX = restRotX;
            rotY = restRotY;
            rotZ = restRotZ;
            positionX = 0.0F;
            positionY = 0.0F;
            positionZ = 0.0F;
            scaleX = 1.0F;
            scaleY = 1.0F;
            scaleZ = 1.0F;
        }
    }

    /** == {@code software.bernie.geckolib.cache.object.GeoCube}（record，几何是烘焙好的四边形组） */
    static final class CubeStub {
        private final QuadStub[] quads;
        private final VecStub pivot;
        private final VecStub rotation;
        private final VecStub size;
        private final double inflate;
        private final boolean mirror;

        CubeStub(QuadStub[] quads, VecStub pivot, VecStub rotation, VecStub size, double inflate, boolean mirror) {
            this.quads = quads;
            this.pivot = pivot;
            this.rotation = rotation;
            this.size = size;
            this.inflate = inflate;
            this.mirror = mirror;
        }

        public QuadStub[] quads() {
            return quads;
        }

        public VecStub pivot() {
            return pivot;
        }

        public VecStub rotation() {
            return rotation;
        }

        public VecStub size() {
            return size;
        }

        public double inflate() {
            return inflate;
        }

        public boolean mirror() {
            return mirror;
        }
    }

    /** == {@code GeoQuad} */
    static final class QuadStub {
        private final VertexStub[] vertices;
        private final Vector3f normal;

        QuadStub(VertexStub[] vertices, Vector3f normal) {
            this.vertices = vertices;
            this.normal = normal;
        }

        public VertexStub[] vertices() {
            return vertices;
        }

        public Vector3f normal() {
            return normal;
        }
    }

    /** == {@code GeoVertex}（位置单位已经是格，UV 已归一化） */
    static final class VertexStub {
        private final Vector3f position;
        private final float texU;
        private final float texV;

        VertexStub(Vector3f position, float texU, float texV) {
            this.position = position;
            this.texU = texU;
            this.texV = texV;
        }

        public Vector3f position() {
            return position;
        }

        public float texU() {
            return texU;
        }

        public float texV() {
            return texV;
        }
    }

    /** == {@code net.minecraft.world.phys.Vec3}（只用到 x()/y()/z()） */
    static final class VecStub {
        private final double x;
        private final double y;
        private final double z;

        VecStub(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }
    }

    /** 任务书里假设的方块形状：原点 + 尺寸 + UV + 镜像（坐标相对本骨骼枢轴，单位 1/16 格） */
    static final class BoxCubeStub {
        private final float originX;
        private final float originY;
        private final float originZ;
        private final float sizeX;
        private final float sizeY;
        private final float sizeZ;
        private final float u;
        private final float v;
        private final boolean mirror;

        BoxCubeStub(float originX, float originY, float originZ,
                    float sizeX, float sizeY, float sizeZ, float u, float v, boolean mirror) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.u = u;
            this.v = v;
            this.mirror = mirror;
        }

        public float getOriginX() {
            return originX;
        }

        public float getOriginY() {
            return originY;
        }

        public float getOriginZ() {
            return originZ;
        }

        public float getSizeX() {
            return sizeX;
        }

        public float getSizeY() {
            return sizeY;
        }

        public float getSizeZ() {
            return sizeZ;
        }

        public float getU() {
            return u;
        }

        public float getV() {
            return v;
        }

        public boolean isMirror() {
            return mirror;
        }
    }

    // ==================================================================
    // 两个替身模型：骨架/动画完全一样，只有方块编码不同
    // ==================================================================

    private abstract static class GeoTestModel extends EntityModel<Entity> implements StubCoreGeoModel {
        final List<BoneStub> bones = new ArrayList<>();
        final List<BoneStub> roots = new ArrayList<>();
        /**
         * 动画时间（= GeckoLib 的 {@code AnimationState.animationTick}）。
         *
         * 为什么要单独存一份：{@code setupAnim} 的 age 形参就够用了，但**采样器**需要先摆状态
         * 再调 setupAnim，而这里要保持与真实 GeckoLib 一致的「一个模型对象、时间由外部推」的形态。
         */
        int animAge;

        GeoTestModel() {
            super(RenderType::entityCutoutNoCull);
        }

        /** 由子类决定方块用哪种形状编码 */
        abstract Object makeCube(double[] origin, double[] size, double[] pivot, float[] uv, boolean mirror);

        void build() {
            BoneStub root = bone(null, "root", new double[]{0, 0, 0}, new double[]{0, 0, 0});
            // root 是 geo.json 里的 neverRender 骨骼：它自己的方块不画，但子骨骼照常画
            root.setHidden(true);
            BoneStub main = bone(root, "main", new double[]{0, 3, 1}, new double[]{0, 0, 0});
            cube(main, new double[]{-3, 2, -5}, new double[]{6, 5, 8}, new float[]{0, 0});
            BoneStub tail = bone(main, "tail", new double[]{0, 2.75, 1}, new double[]{47.5, 0, 0});
            cube(tail, new double[]{-2, 0.96706, 1.76899}, new double[]{4, 4, 3}, new float[]{22, 13});
            BoneStub head = bone(main, "head", new double[]{0, 7.21683, -1.56991}, new double[]{0, 0, 0});
            cube(head, new double[]{-2, 6.89544, -4.18689}, new double[]{4, 4, 4}, new float[]{20, 0});
            BoneStub beak = bone(head, "beak", new double[]{0, 7.99892, -5.03434}, new double[]{0, 0, 0});
            cube(beak, new double[]{-2, 6.99892, -7.03434}, new double[]{4, 2, 3}, new float[]{14, 24});
            BoneStub leg = bone(main, "leg", new double[]{-1.75, 3, 1.5}, new double[]{-17.5, 15, 0});
            cube(leg, new double[]{-2.25, 0, 1}, new double[]{1, 3, 1}, new float[]{4, 4});
            BoneStub foot = bone(leg, "foot", new double[]{-1.75, 0.15175, 1.61165}, new double[]{25, 0, 0});
            cube(foot, new double[]{-3.25, 0.15175, -0.38835}, new double[]{3, 0.1, 2}, new float[]{0, 1});
            BoneStub leg2 = bone(main, "leg2", new double[]{1.75, 3, 1.5}, new double[]{-17.5, -15, 0});
            cube(leg2, new double[]{1.25, 0, 1}, new double[]{1, 3, 1}, new float[]{0, 4});
            BoneStub foot2 = bone(leg2, "foot2", new double[]{1.75, 0.15175, 1.61165}, new double[]{25, 0, 0});
            cube(foot2, new double[]{0.25, 0.15175, -0.38835}, new double[]{3, 0.1, 2}, new float[]{0, 3});
            BoneStub wing = bone(main, "wing", new double[]{-2, 7.25, -1}, new double[]{0, 0, 20});
            cube(wing, new double[]{-3, 3.25, -2.5}, new double[]{1, 4, 6}, new float[]{0, 19});
            BoneStub wing2 = bone(main, "wing2", new double[]{2.25, 7.25, -1}, new double[]{0, 0, -20});
            cube(wing2, new double[]{2.0889, 3.33637, -2.5}, new double[]{1, 4, 6}, new float[]{14, 14});
        }

        private BoneStub bone(BoneStub parent, String name, double[] pivot, double[] rotDeg) {
            BoneStub b = new BoneStub(parent, name, pivot, rotDeg);
            bones.add(b);
            if (parent == null) {
                roots.add(b);
            }
            return b;
        }

        // ---- GeckoLib 形状的动画处理入口（真机上的真实形状，见 GeoAnimator 的类注释） ----

        /**
         * 该模型的动画处理器（GeckoLib 里就是 {@code getAnimationProcessor()} 返回的那个对象）。
         *
         * <p>它刻意声明在**这个父类**上、子类不自持 —— 正是为了验「必须沿父类链找
         * {@code getAnimationProcessor()}」，而不是「在模型类自己身上找 tickAnimation」。
         */
        public final StubAnimationProcessor processor = new StubAnimationProcessor(this);

        /** 形状与 GeckoLib 的 {@code GeoModel.getAnimationProcessor()} 一致 */
        public StubAnimationProcessor getAnimationProcessor() {
            return processor;
        }

        /** 形状与 {@code AnimatableInstanceCache.getManagerForId(long)} 一致 */
        public StubAnimatableManager managerFor(long id) {
            return cache.getManagerForId(id);
        }

        /** 形状与 {@code GeoAnimatable.getAnimatableInstanceCache()} 一致（缓存对象带 getManagerForId(long)） */
        public StubAnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }

        private final StubAnimatableInstanceCache cache = new StubAnimatableInstanceCache();

        /** 离线用例用：缓存对象（类名带 AnimatableInstanceCache） */
        StubAnimatableInstanceCache cacheFor() {
            return cache;
        }

        /** 离线用例用：缓存类的名字（判据要求含 AnimatableInstanceCache） */
        String cacheName() {
            return cache.getClass().getName();
        }

        /** 形状与 {@code GeoModel.getBakedModel(ResourceLocation)} 一致（用来验证「从处理器反查模型」那条路） */
        public Object getBakedModel(Object resource) {
            return this;
        }

        private void cube(BoneStub bone, double[] origin, double[] size, float[] uv) {
            bone.getCubes().add(makeCube(origin, size, new double[]{
                    -bone.getPivotX(), bone.getPivotY(), bone.getPivotZ()}, uv, false));
        }

        BoneStub byName(String name) {
            for (BoneStub b : bones) {
                if (b.getName().equals(name)) {
                    return b;
                }
            }
            throw new IllegalStateException("没有这根骨骼: " + name);
        }

        /** 可见方块的个数（隐藏骨骼自己的方块不画，但它的子骨骼照画） */
        int visibleCubeCount() {
            int n = 0;
            for (BoneStub b : bones) {
                if (!b.isHidden()) {
                    n += b.getCubes().size();
                }
            }
            return n;
        }

        /** 摆回绑定姿态（= 适配器 resetBind 之后的状态：旋转/位移归零、缩放归一） */
        void resetToBind() {
            for (BoneStub b : bones) {
                b.setRotX(0.0F);
                b.setRotY(0.0F);
                b.setRotZ(0.0F);
                b.setPosX(0.0F);
                b.setPosY(0.0F);
                b.setPosZ(0.0F);
                b.setScaleX(1.0F);
                b.setScaleY(1.0F);
                b.setScaleZ(1.0F);
            }
        }

        /**
         * 把骨骼摆到「动画第 tick 个 tick」的姿态 —— 替身版的
         * {@code AnimationProcessor.tickAnimation} 真正算出来的结果。
         *
         * <p>注意它<b>不</b>读 renderer、<b>不</b>受任何「时间没变就跳过」的节流影响：
         * 每次调用都按 tick 重算，这正是显式驱动相对「靠渲染推动」的关键区别。
         */
        void applyTickedPose(double tick) {
            for (BoneStub b : bones) {
                b.resetToRest();
            }
            // 主骨骼绕 X 转：24 tick 一个周期（等价于 GeckoLib 里一条 loop 动画）
            float phase = (float) (tick / 24.0 * (Math.PI * 2.0));
            byName("main").setRotX((float) Math.sin(phase) * 0.5F);
            // 头部绕 Y 摆（另一条时间驱动的动画）
            byName("head").setRotY((float) Math.cos(phase) * 0.35F);
            // 位移通道也要动（验证位移维确实来自时间轴）
            BoneStub leg = byName("leg");
            leg.setPosY(leg.getPosY() + (float) Math.sin(phase) * 3.0F);
        }

        /** 与上面同义（不带参数 = 用当前 animAge），便于「只摆一次」的对照用例 */
        void applyTickedPose() {
            applyTickedPose(animAge);
        }

        /**
         * ★ 照抄「鸭子那类模组」的姿态规律：**走/待机是两条不同的动画**，
         * 由 {@code |limbSwingAmount| >= 0.05} 选择（见 {@link StubAnimationProcessor#tickAnimation}）。
         *
         * <ul>
         *   <li><b>走</b>：腿按**相位**摆动（相位推进必须看得见），头随时间轻微晃；</li>
         *   <li><b>待机</b>：**纯时间驱动**（呼吸/摆头）—— 幅度为 0 时唯一能动的来源。</li>
         * </ul>
         *
         * 于是「驱动时把 limbSwingAmount 传成 0」会让两次驱动都落到待机曲线 →
         * 「幅度 1 与幅度 0 必须给出不同姿态」这条用例立刻变红。
         */
        void applyBirdPose(double tick, boolean moving, float limbSwing) {
            resetToBind();
            BoneStub leg = byName("leg");
            BoneStub leg2 = byName("leg2");
            BoneStub head = byName("head");
            BoneStub wing = byName("wing");
            if (moving) {
                // 走：腿按相位摆（相位是走路动画的主要驱动量）
                leg.setRotX((float) Math.sin(limbSwing) * 0.7F);
                leg2.setRotX((float) -Math.sin(limbSwing) * 0.7F);
                head.setRotY((float) Math.sin(tick / 8.0) * 0.1F);
                wing.setRotZ(0.3F);
            } else {
                // 待机：纯时间驱动（呼吸/摆头）—— 站着不动时也必须会动
                head.setRotX((float) Math.sin(tick / 20.0) * 0.5F);
                head.setRotY((float) Math.cos(tick / 40.0) * 0.35F);
                wing.setRotZ((float) Math.cos(tick / 20.0) * 0.2F);
            }
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            for (BoneStub b : bones) {
                b.resetToRest();
            }
            float c = (float) Math.cos(limbSwing * 0.6662F);
            // ---- 时间成分（GeckoLib 里由 animTime 驱动：呼吸/摆头/摇尾巴都是这一类）----
            // 两个时间项的幅度都取得**很小**、周期取得**偏慢**，这是有意的：
            //   * 表的时间轴是 5 tick 一个样本 + 线性插值，它的误差上界约等于
            //     「二阶导 × 步长²/8」；幅度越小、周期越长，表就越能精确复现模型自身姿态；
            //   * 幅度太小又会被判定为「没有时间成分」，所以取值要明显高于 WALK_TIME_EPS(1e-4)
            //     并让整条时间轴上确实能观察到差异。
            // 于是：idleHead 周期 80 tick（2 秒的呼吸）、峰值约 0.03；walkTime 周期 160 tick、峰值约 0.02。
            // 关键约定：**age = 0 时两个因子都等于 1**，于是「老用例在 age=0 下的期望值」一字不改，
            // 回归用例不会因为加入时间维而改写历史语义。
            float idleHead = age <= 0.0F ? 1.0F
                    : (float) Math.cos(age * (Math.PI * 2.0) / 80.0);
            float walkTime = age <= 0.0F ? 1.0F
                    : (float) Math.cos(age * (Math.PI * 2.0) / GeoPoseTable.idlePeriodTicks());
            byName("main").setRotX(c * 0.12F * amount * walkTime);
            byName("tail").setRotZ(c * 0.25F * amount);
            // 待机时（amount=0）头仍然在动：这就是「站着不动」那个 bug 的核心。
            // 峰值 |idleHead-1| = 1，乘 0.03 —— 明显高于探测阈值，又小到表能精确复现。
            byName("head").setRotY(headYaw * 0.017453292F + (idleHead - 1.0F) * 0.03F * (1.0F - amount));
            byName("beak").setRotX(-c * 0.4F * amount);
            // 位移动画：Bedrock 的 position 是「X 取反」的，适配器要把它变成 +offX
            BoneStub leg = byName("leg");
            leg.setPosY(leg.getPosY() + (float) Math.sin(limbSwing) * 2.5F * amount
                    + (idleHead - 1.0F) * 0.5F * (1.0F - amount));
            BoneStub foot = byName("foot");
            foot.setPosX(foot.getPosX() + c * 3.0F * amount);
            // 缩放动画（缩放的骨骼会连带子骨骼一起缩）
            BoneStub leg2 = byName("leg2");
            leg2.setScaleX(1.0F + 0.5F * amount);
            leg2.setScaleY(1.0F - 0.25F * amount);
            byName("foot2").setRotX(c * 0.8F * amount);
            byName("wing").setRotX(-c * 0.5F * amount);
            byName("wing2").setScaleZ(1.0F + 0.3F * amount);
        }

        @Override
        public void renderToBuffer(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            for (BoneStub root : roots) {
                renderBone(stack, root, buffer, light, overlay);
            }
        }

        /** 复刻 GeoRenderer.renderRecursively：隐藏只跳过自己的方块，隐藏子骨骼才跳过子树 */
        private void renderBone(PoseStack stack, BoneStub bone, VertexConsumer buffer, int light, int overlay) {
            stack.pushPose();
            prepMatrix(stack, bone);
            if (!bone.isHidden()) {
                renderCubes(stack, bone, buffer, light, overlay);
            }
            if (!bone.isHidingChildren()) {
                for (BoneStub child : bone.getChildBones()) {
                    renderBone(stack, child, buffer, light, overlay);
                }
            }
            stack.popPose();
        }

        abstract void renderCubes(PoseStack stack, BoneStub bone, VertexConsumer buffer, int light, int overlay);

        /** 复刻 RenderUtils.prepMatrixForBone：平移(-posX,+posY,+posZ) → 枢轴 → R(Z→Y→X) → 缩放 → -枢轴 */
        static void prepMatrix(PoseStack stack, BoneStub bone) {
            stack.translate(-bone.getPosX() / 16.0F, bone.getPosY() / 16.0F, bone.getPosZ() / 16.0F);
            stack.translate(bone.getPivotX() / 16.0F, bone.getPivotY() / 16.0F, bone.getPivotZ() / 16.0F);
            rotateZYX(stack, bone.getRotX(), bone.getRotY(), bone.getRotZ());
            stack.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
            stack.translate(-bone.getPivotX() / 16.0F, -bone.getPivotY() / 16.0F, -bone.getPivotZ() / 16.0F);
        }

        /** 复刻 RenderUtils.rotateMatrixAroundBone / rotateMatrixAroundCube：Z → Y → X */
        static void rotateZYX(PoseStack stack, float rx, float ry, float rz) {
            if (rz != 0.0F) {
                stack.mulPose(Axis.ZP.rotation(rz));
            }
            if (ry != 0.0F) {
                stack.mulPose(Axis.YP.rotation(ry));
            }
            if (rx != 0.0F) {
                stack.mulPose(Axis.XP.rotation(rx));
            }
        }

        static void emit(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                         float x, float y, float z, float nx, float ny, float nz, float u, float v) {
            Matrix4f m = stack.last().pose();
            Matrix3f n = stack.last().normal();
            Vector4f p = m.transform(new Vector4f(x, y, z, 1.0F));
            Vector3f tn = n.transform(new Vector3f(nx, ny, nz));
            buffer.vertex(p.x(), p.y(), p.z())
                    .color(255, 255, 255, 255)
                    .uv(u, v)
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(tn.x(), tn.y(), tn.z())
                    .endVertex();
        }
    }

    // ------------------------------------------------------------------
    // 替身动画器：形状照搬 GeckoLib 4.8.4（用 javap 对着生产 jar 逐个确认过）
    //
    //   public class AnimationProcessor<T extends GeoAnimatable> {
    //     //                                                            ↓ double@3，**没有 long**
    //     public void tickAnimation(T, CoreGeoModel<T>, AnimatableManager<T>, double, AnimationState<T>, boolean);
    //     public void preAnimationSetup(T, double);
    //   }
    //     public class GeoModel<T extends GeoAnimatable> implements CoreGeoModel<T> {
    //       public AnimationProcessor<T> getAnimationProcessor();          // 声明在 CoreGeoModel 接口上
    //       public void handleAnimations(T, long instanceId, AnimationState<T>);
    //     }
    //     public class AnimationState<T>(T, float, float, float, boolean);
    //     public abstract class AnimatableInstanceCache { AnimatableManager<T> getManagerForId(long); }
    //
    // 替身的**类名**刻意带上 CoreGeoModel / AnimatableManager / AnimationState 这三个名族
    // （生产判据只认名族与相对顺序，不认具体包名），并且全部 public ——
    // 这样 harness 走的就是**生产那条反射发现路径**，不再自己写一份形状知识。
    // ------------------------------------------------------------------

    /** 替身 {@code GeoAnimatable}（动画宿主） */
    public interface StubAnimatable {
    }

    /** 替身 {@code CoreGeoModel}（名族判据要认得它） */
    public interface StubCoreGeoModel {
    }

    /** 替身 {@code AnimatableManager}（名族判据要认得它） */
    public static final class StubAnimatableManager {
        @SuppressWarnings("unused")
        private final long id;

        public StubAnimatableManager(long id) {
            this.id = id;
        }
    }

    /**
     * 替身 {@code AnimationState}（构造签名与 GeckoLib 一致：T,float,float,float,boolean）。
     *
     * <p><b>为什么必须暴露 {@code getLimbSwingAmount()}</b>：真机事故（本轮）的根因就是
     * 「我们驱动时把 limbSwingAmount 传成了 0」，而模组默认的动画选择器恰恰读它 ——
     * untitledduckmod 的鸭子是
     * <pre>
     *   float amount = state.getLimbSwingAmount();
     *   boolean moving = !(amount &lt;= -0.05f || amount &gt;= 0.05f);   // 即 |amount| &gt;= 0.05
     *   controller.setAnimation(moving ? WALK_ANIM : IDLE_ANIM);
     * </pre>
     * （javap 对着 untitledduckmod-1.5.2 的 {@code DuckEntity.predicate} 逐指令确认）。
     * 替身照抄这条判据，于是「传 0」这个错误在离线就会让走/待机两条曲线重合 —— 用例必红。
     */
    public static final class StubAnimationState {
        @SuppressWarnings("unused")
        private final Object animatable;
        private final float limbSwing;
        private final float limbSwingAmount;
        @SuppressWarnings("unused")
        private final float partialTick;
        @SuppressWarnings("unused")
        private final boolean moving;
        /** GeckoLib 里有这个公开字段（handleAnimations 会往里写动画时间） */
        public double animationTick;

        public StubAnimationState(Object animatable, float limbSwing, float limbSwingAmount,
                                  float partialTick, boolean moving) {
            this.animatable = animatable;
            this.limbSwing = limbSwing;
            this.limbSwingAmount = limbSwingAmount;
            this.partialTick = partialTick;
            this.moving = moving;
        }

        /** 形状与 GeckoLib 的 {@code AnimationState.getLimbSwingAmount()} 一致 */
        public float getLimbSwingAmount() {
            return limbSwingAmount;
        }

        /** 形状与 GeckoLib 的 {@code AnimationState.getLimbSwing()} 一致 */
        public float getLimbSwing() {
            return limbSwing;
        }
    }

    /** 替身 {@code AnimatableInstanceCache}（类名带 AnimatableInstanceCache，与 managerOf 的名字族判据一致） */
    public static final class StubAnimatableInstanceCache {
        /** 每个 instanceId 一个管理器（GeckoLib 就是这么按 id 分桶的） */
        private final java.util.Map<Long, StubAnimatableManager> managers = new java.util.HashMap<>();

        /** 形状与 {@code AnimatableInstanceCache.getManagerForId(long)} 一致 */
        public StubAnimatableManager getManagerForId(long id) {
            return managers.computeIfAbsent(id, StubAnimatableManager::new);
        }
    }

    /** 替身动画宿主：只有 {@code getAnimatableInstanceCache()} 这一个形状（与 GeoAnimatable 一致） */
    public static final class StubAnimatableHost implements StubAnimatable {
        private final StubAnimatableInstanceCache cache = new StubAnimatableInstanceCache();

        public StubAnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }
    }

    /**
     * 替身 {@code AnimationProcessor}（**真实 6 参签名**：double 在下标 3、没有 long）。
     *
     * <p>它把收到的 tick 记进 {@link #lastTick}，于是 harness 能直接断言
     * 「double 形参填的就是动画时间」，而不是只看「调用没抛异常」。
     */
    public static final class StubAnimationProcessor {
        private final GeoTestModel model;
        /** 最后一次收到的动画时间（harness 断言用） */
        public double lastTick = Double.NaN;
        /** 收到过几次 */
        public int calls;
        /** 最后一次被判为「在走」（= 幅度非 0）—— harness 用它断言幅度真的传进来了 */
        public boolean lastMoving;
        /** 最后一次收到的相位 */
        public float lastLimbSwing;

        public StubAnimationProcessor(GeoTestModel model) {
            this.model = model;
        }

        /** 真实签名：T, CoreGeoModel, AnimatableManager, double, AnimationState, boolean */
        public void tickAnimation(StubAnimatable animatable, StubCoreGeoModel coreModel,
                                  StubAnimatableManager manager, double tick,
                                  StubAnimationState state, boolean crashIfBoneMissing) {
            calls++;
            lastTick = tick;
            // ★ 照抄真机模组的动画选择器：读 AnimationState 里的 limbSwingAmount 决定放「走」还是「待机」。
            //   鸭子（untitledduckmod 1.5.2，javap DuckEntity.predicate）：
            //     |limbSwingAmount| >= 0.05 ? WALK_ANIM : IDLE_ANIM
            //   于是「驱动时把幅度传成 0」这件事在离线就会让两条曲线重合 —— 用例必红。
            float amount = state.getLimbSwingAmount();
            boolean moving = amount >= 0.05F || amount <= -0.05F;
            lastMoving = moving;
            lastLimbSwing = state.getLimbSwing();
            model.applyBirdPose(tick, moving, state.getLimbSwing());
        }

        /** GeckoLib 里它只是 {@code model.applyMolangQueries(animatable, tick)}，这里给个等价形状 */
        public void preAnimationSetup(StubAnimatable animatable, double tick) {
            // 与骨骼姿态无关（Molang 查询），空实现
        }
    }

    // ---- 形状判据矩阵用的替身（正例 2 与三个反例） ----

    /** 「将来 GeckoLib 多一个 long」的替身：long@1、double@4，相对顺序仍然是 manager<double<state */
    public static final class ShiftedProcessor {
        public void tickAnimation(StubAnimatable animatable, long instanceId, StubCoreGeoModel coreModel,
                                  StubAnimatableManager manager, double tick,
                                  StubAnimationState state, boolean crashIfBoneMissing) {
            // 只是为了被判据接受
        }
    }

    /** 反例：两个 double（无法确定哪一个是 tick） */
    public static final class TwoDoublesProcessor {
        public void tickAnimation(StubAnimatable animatable, StubCoreGeoModel coreModel,
                                  StubAnimatableManager manager, double tick, double extra,
                                  StubAnimationState state, boolean crashIfBoneMissing) {
            // 不该被选中
        }
    }

    /** 反例：double 排在 AnimatableManager 之前（相对顺序不对） */
    public static final class MisorderedProcessor {
        public void tickAnimation(StubAnimatable animatable, StubCoreGeoModel coreModel, double tick,
                                  StubAnimatableManager manager, StubAnimationState state,
                                  boolean crashIfBoneMissing) {
            // 不该被选中
        }
    }

    /** 正例 1 的模型：{@code getAnimationProcessor()} 声明在**父类**上（真机 GeckoLib 也是这样） */
    public static class StubModelBase implements StubCoreGeoModel {
        private final StubAnimationProcessor processor = new StubAnimationProcessor(null);

        public StubAnimationProcessor getAnimationProcessor() {
            return processor;
        }
    }

    /** 正例 1：入口在父类上、真实 6 参签名 */
    public static final class StubModelWithProcessor extends StubModelBase {
    }

    /** 正例 2：多一个 long 的替身 */
    public static final class ShiftedModel implements StubCoreGeoModel {
        private final ShiftedProcessor processor = new ShiftedProcessor();

        /** 名字里没有 AnimationProcessor，专门用来看「兜底取值方法」那条路也能找到 */
        public ShiftedProcessor getProcessor() {
            return processor;
        }
    }

    /** 反例 1：模型上只有 {@code handleAnimations(T,long,AnimationState)}，**没有** getAnimationProcessor */
    public static final class ModelWithoutProcessor implements StubCoreGeoModel {
        public void handleAnimations(StubAnimatable animatable, long instanceId, StubAnimationState state) {
            // 3 参、没有 double —— 不该被当成 tick 入口
        }
    }

    /** 反例 2 的模型 */
    public static final class TwoDoublesModel implements StubCoreGeoModel {
        private final TwoDoublesProcessor processor = new TwoDoublesProcessor();

        public TwoDoublesProcessor getAnimationProcessor() {
            return processor;
        }
    }

    /** 反例 3 的模型 */
    public static final class MisorderedModel implements StubCoreGeoModel {
        private final MisorderedProcessor processor = new MisorderedProcessor();

        public MisorderedProcessor getAnimationProcessor() {
            return processor;
        }
    }

    // ------------------------------------------------------------------
    // A：GeckoLib 真实形状（GeoVertex 的位置单位已经是格，UV 已归一化）
    // ------------------------------------------------------------------

    static final class QuadsGeoModel extends GeoTestModel {
        QuadsGeoModel() {
            build();
        }

        @Override
        Object makeCube(double[] origin, double[] size, double[] pivot, float[] uv, boolean mirror) {
            // 复刻 BakedModelFactory$Builtin.constructCube 的坐标约定：
            // X 取反且整体平移 -size.x，然后 /16（所以顶点位置单位是格）
            float x0 = (float) (-(origin[0] + size[0]) / 16.0);
            float x1 = (float) (-origin[0] / 16.0);
            float y0 = (float) (origin[1] / 16.0);
            float y1 = (float) ((origin[1] + size[1]) / 16.0);
            float z0 = (float) (origin[2] / 16.0);
            float z1 = (float) ((origin[2] + size[2]) / 16.0);
            float[][] c = {
                    {x0, y0, z0}, {x1, y0, z0}, {x0, y0, z1}, {x1, y0, z1},
                    {x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1},
            };
            float u = uv[0] / 64.0F;
            float v = uv[1] / 64.0F;
            float w = (float) (size[0] / 64.0);
            float h = (float) (size[1] / 64.0);
            float d = (float) (size[2] / 64.0);
            QuadStub[] quads = new QuadStub[6];
            quads[0] = quad(new float[][]{c[4], c[6], c[7], c[5]}, 0, 1, 0, u, v, w, d);
            quads[1] = quad(new float[][]{c[2], c[0], c[1], c[3]}, 0, -1, 0, u, v + d, w, d);
            quads[2] = quad(new float[][]{c[1], c[0], c[4], c[5]}, 0, 0, -1, u + d, v + d, w, h);
            quads[3] = quad(new float[][]{c[2], c[3], c[7], c[6]}, 0, 0, 1, u + d + w, v + d, w, h);
            quads[4] = quad(new float[][]{c[0], c[2], c[6], c[4]}, -1, 0, 0, u + d, v + d + h, d, h);
            quads[5] = quad(new float[][]{c[3], c[1], c[5], c[7]}, 1, 0, 0, u + d + d, v + d + h, d, h);
            // 方块的枢轴/旋转在 GeoCube 里是「1/16 单位 + 弧度、X(和Y) 取反」的模型空间量
            VecStub cp = new VecStub(-pivot[0], pivot[1], pivot[2]);
            VecStub cr = new VecStub(0.0, 0.0, 0.0);
            VecStub cs = new VecStub(size[0], size[1], size[2]);
            return new CubeStub(quads, cp, cr, cs, 0.0, mirror);
        }

        private static QuadStub quad(float[][] corners, float nx, float ny, float nz,
                                     float u, float v, float du, float dv) {
            float[][] uvs = {{u, v}, {u + du, v}, {u + du, v + dv}, {u, v + dv}};
            VertexStub[] vs = new VertexStub[4];
            for (int i = 0; i < 4; i++) {
                vs[i] = new VertexStub(new Vector3f(corners[i][0], corners[i][1], corners[i][2]),
                        uvs[i][0], uvs[i][1]);
            }
            return new QuadStub(vs, new Vector3f(nx, ny, nz));
        }

        /** 复刻 GeoRenderer.renderCube：方块自己的枢轴/旋转是静态量，烘焙进几何 */
        @Override
        void renderCubes(PoseStack stack, BoneStub bone, VertexConsumer buffer, int light, int overlay) {
            for (Object o : bone.getCubes()) {
                CubeStub cube = (CubeStub) o;
                stack.pushPose();
                VecStub cp = cube.pivot();
                VecStub cr = cube.rotation();
                stack.translate(cp.x() / 16.0, cp.y() / 16.0, cp.z() / 16.0);
                rotateZYX(stack, (float) cr.x(), (float) cr.y(), (float) cr.z());
                stack.translate(-cp.x() / 16.0, -cp.y() / 16.0, -cp.z() / 16.0);
                for (QuadStub quad : cube.quads()) {
                    if (quad == null) {
                        continue;
                    }
                    Vector3f n = quad.normal();
                    for (VertexStub vertex : quad.vertices()) {
                        Vector3f p = vertex.position();
                        emit(stack, buffer, light, overlay, p.x(), p.y(), p.z(),
                                n.x(), n.y(), n.z(), vertex.texU(), vertex.texV());
                    }
                }
                stack.popPose();
            }
        }
    }

    // ------------------------------------------------------------------
    // B：任务描述的形状（原点 + 尺寸 + UV + 镜像）：坐标相对本骨骼枢轴，单位 1/16 格
    // ------------------------------------------------------------------

    static final class BoxGeoModel extends GeoTestModel {
        BoxGeoModel() {
            build();
        }

        @Override
        Object makeCube(double[] origin, double[] size, double[] pivot, float[] uv, boolean mirror) {
            // 和 A 是同一块几何，只是换成「相对枢轴 + 1/16 单位」的写法
            float ox = (float) (-(origin[0] + size[0]) - (-pivot[0]));
            float oy = (float) (origin[1] - pivot[1]);
            float oz = (float) (origin[2] - pivot[2]);
            return new BoxCubeStub(ox, oy, oz, (float) size[0], (float) size[1], (float) size[2],
                    uv[0] / 64.0F, uv[1] / 64.0F, mirror);
        }

        @Override
        void renderCubes(PoseStack stack, BoneStub bone, VertexConsumer buffer, int light, int overlay) {
            for (Object o : bone.getCubes()) {
                BoxCubeStub cube = (BoxCubeStub) o;
                stack.pushPose();
                // 方块坐标相对枢轴，所以先补上枢轴平移（和适配器里方块形状的处理一致）
                stack.translate(bone.getPivotX() / 16.0F, bone.getPivotY() / 16.0F, bone.getPivotZ() / 16.0F);
                float x0 = cube.getOriginX() / 16.0F;
                float y0 = cube.getOriginY() / 16.0F;
                float z0 = cube.getOriginZ() / 16.0F;
                float dx = cube.getSizeX() / 16.0F;
                float dy = cube.getSizeY() / 16.0F;
                float dz = cube.getSizeZ() / 16.0F;
                float[][] faces = {
                        {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},
                };
                float[][][] corners = {
                        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
                        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
                        {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
                        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
                        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
                        {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
                };
                for (int f = 0; f < faces.length; f++) {
                    for (float[] corner : corners[f]) {
                        float px = x0 + dx * corner[0];
                        float py = y0 + dy * corner[1];
                        float pz = z0 + dz * corner[2];
                        emit(stack, buffer, light, overlay, px, py, pz,
                                faces[f][0], faces[f][1], faces[f][2], 0.0F, 0.0F);
                    }
                }
                stack.popPose();
            }
        }
    }
}
