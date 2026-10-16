package dev.swordkingdom.horde.harness;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.client.EntityMeshBaker;
import dev.swordkingdom.horde.client.ProjectileBaker;
import dev.swordkingdom.horde.client.RendererCapture;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * **渲染器覆盖面验证**：把一个实体类型的渲染器可能长成的每一种形状都造一个替身，
 * 验证 {@link EntityMeshBaker#bakeFromRenderer} 会把它们各自送到正确的路径上。
 *
 * <p>为什么必须离线验证：{@code EntityMeshBaker.bake(EntityType)} 那条线上需要
 * Minecraft 客户端单例 + 一个真实世界才能造傀儡实体，而这一整段逻辑（模型发现 / 贴图发现 /
 * 渲染器前置变换 / 顶点捕获）**本来就不依赖客户端单例** —— 所以「拿到渲染器之后」的部分
 * 单独开了一个入口（{@code bakeFromRenderer(Object, Entity, ResourceLocation)}），
 * 于是这里能用替身渲染器把每条分支真的跑一遍，而线上跑的是同一份代码。
 *
 * <p>覆盖的用例：
 * <ol>
 *   <li><b>(a)</b> 渲染器实例字段里有 {@code EntityModel}（原版末影龙的形状）→ 字段路径、骨骼数正确；</li>
 *   <li><b>(a2)</b> 前置变换里带额外平移（末影龙那种 {@code translate(0,0,1)}）→ 必须烘进顶点；</li>
 *   <li><b>(a3)</b> <b>真的原版末影龙模型</b>（{@code EnderDragonRenderer.createBodyLayer()} +
 *       {@code DragonModel}）→ 离线把真模型烘一遍，骨骼数/顶点数必须对得上；</li>
 *   <li><b>(b)</b> 渲染器只有 {@code getGeoModel()}（GeckoLib 形状）→ geo 路径；</li>
 *   <li><b>(b2)</b> geo 模型被放在渲染器字段里 → 字段路径也必须认出「这是 geo（朝向不同）」；</li>
 *   <li><b>(c)</b> 什么模型都没有，但 {@code render()} 会往顶点消费者写 → 捕获兜底、boneCount=1；</li>
 *   <li><b>(d)</b> {@code render()} 抛异常 → 不崩、不产出错误网格、有原因可读；</li>
 *   <li><b>(d2)</b> {@code render()} 什么都不写 → 同上；</li>
 *   <li><b>(e)</b> {@code render()} 写的顶点超过上限 → 整块跳过并说明；</li>
 *   <li><b>(e2)</b> 上限写在截获器的写入路径上（内存有界）：恰好停在上限、且标记溢出；</li>
 *   <li><b>(f)</b> 三条路都不成立 → 必须给出逐条原因（绝不静默）。</li>
 * </ol>
 *
 * <p>「走的是哪条路」这件事**没有单独的字段**可以断言（不为了测试给生产代码加状态），
 * 但每条路的产物是可区分的：捕获兜底恒为 1 根骨骼且 {@code geo=true}；
 * geo 路径必然多骨骼且 {@code geo=true}；字段路径的普通模型是多骨骼且 {@code geo=false}。
 * 断言就是按这些可观测差异写的，注释里逐条说明。
 */
public final class RendererCoverageHarness {

    private static final ResourceLocation UNIT = ResourceLocation.tryParse("horde:coverage_probe");
    private static final ResourceLocation FIELD_TEX = ResourceLocation.tryParse("horde:field_probe");
    private static final ResourceLocation GEO_TEX = ResourceLocation.tryParse("horde:geo_probe");
    private static final ResourceLocation CAPTURE_TEX = ResourceLocation.tryParse("horde:capture_probe");

    private static int failures;

    public static void main(String[] args) {
        System.out.println("== 渲染器覆盖面验证（离线：不需要 Minecraft 客户端单例，也不需要 GL）==");
        System.out.println();
        diagnoseRenderType();
        System.out.println();

        caseEntityModelField();
        caseFieldWithPreludeTranslation();
        caseRealEnderDragonModel();
        caseGeoViaGetter();
        caseGeoInField();
        caseCaptureFallback();
        caseRenderThrows();
        caseRenderWritesNothing();
        caseCaptureOverflow();
        caseCaptureCapAtWritePath();
        casePrimitiveModes();
        caseNothingAtAll();
        casePoseLayoutRoundTrip();

        System.out.println();
        System.out.println(failures == 0 ? "== RENDERER COVERAGE PASS =="
                : "== RENDERER COVERAGE FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // ★ 姿态 Texel 布局的往返校验（写入端 ↔ 着色器索引）
    // ==================================================================

    /**
     * ★ 真机事故（本轮）：「模型组件错位」—— 用**哨兵往返**把写入端与着色器的索引公式钉死。
     *
     * <p>着色器读法是（{@code CrowdRenderer.VERTEX_SHADER}，逐字）：
     * <pre>
     *   int texelBase = gl_InstanceID * uPoseStride;
     *   pose  = texelFetch(uPose, texelBase + b)                     // 旋转
     *   trans = texelFetch(uPose, texelBase + uTransBase + tslot)    // 位移
     *   scale = texelFetch(uPose, texelBase + uScaleBase + sslot)    // 缩放
     * </pre>
     * 这里给**每根骨骼/每个通道填互不相同的哨兵值**（第 i 根骨骼位移 = (i, 1000+i, 2000+i)），
     * 再按上面这条公式逐 texel 读回：任何一处 stride/基址/槽位错位都会立刻对不上。
     * 用例同时覆盖「有缩放通道」与「零通道」两种类型（后者行为必须不变）。
     */
    private static void casePoseLayoutRoundTrip() {
        System.out.println("[case] ★ 姿态 Texel 布局往返（写入端 ↔ 着色器公式；组件错位的照妖镜）");
        // 三种形状：只有旋转 / 有位移 / 位移+缩放都齐（龙那种 bone 多、通道也多的最像最后一种）
        int[][] shapes = {{12, 0, 0}, {20, 6, 0}, {20, 6, 4}, {7, 1, 1}};
        for (int[] shape : shapes) {
            int bones = shape[0];
            int trans = shape[1];
            int scale = shape[2];
            int instances = 3;
            int stride = dev.swordkingdom.horde.client.CrowdRenderer.poseStride(bones, trans, scale);
            int transBase = dev.swordkingdom.horde.client.CrowdRenderer.poseTransBase(bones);
            int scaleBase = dev.swordkingdom.horde.client.CrowdRenderer.poseScaleBase(bones, trans);
            check(stride == bones + trans + scale && transBase == bones && scaleBase == bones + trans,
                    "布局常量必须与着色器口径一致（骨骼 " + bones + "、位移 " + trans + "、缩放 " + scale
                            + "）：stride=" + stride + "、uTransBase=" + transBase + "、uScaleBase=" + scaleBase);

            float[] buf = new float[instances * stride * 4];
            // 哨兵：每根骨骼、每个通道、每个实例都填不同的值
            float[] rot = new float[bones * 3];
            float[] tr = new float[Math.max(1, trans) * 3];
            float[] sc = new float[Math.max(1, scale) * 3];
            for (int inst = 0; inst < instances; inst++) {
                for (int b = 0; b < bones; b++) {
                    rot[b * 3] = inst * 100000 + b;
                    rot[b * 3 + 1] = inst * 100000 + 1000 + b;
                    rot[b * 3 + 2] = inst * 100000 + 2000 + b;
                }
                for (int t = 0; t < trans; t++) {
                    tr[t * 3] = inst * 100000 + 3000 + t;
                    tr[t * 3 + 1] = inst * 100000 + 4000 + t;
                    tr[t * 3 + 2] = inst * 100000 + 5000 + t;
                }
                for (int s = 0; s < scale; s++) {
                    sc[s * 3] = inst * 100000 + 6000 + s;
                    sc[s * 3 + 1] = inst * 100000 + 7000 + s;
                    sc[s * 3 + 2] = inst * 100000 + 8000 + s;
                }
                dev.swordkingdom.horde.client.CrowdRenderer.packPoseRow(buf, inst, bones, trans, scale,
                        rot, trans > 0 ? tr : null, scale > 0 ? sc : null);
            }

            // ---- 按着色器公式逐骨骼/逐通道读回 ----
            int bad = 0;
            String firstBad = null;
            for (int inst = 0; inst < instances; inst++) {
                for (int b = 0; b < bones; b++) {
                    int texel = dev.swordkingdom.horde.client.CrowdRenderer
                            .poseTexelAt(inst, stride, 0, b);
                    float[] got = texel3(buf, texel);
                    float want0 = inst * 100000 + b;
                    float want1 = inst * 100000 + 1000 + b;
                    float want2 = inst * 100000 + 2000 + b;
                    if (got[0] != want0 || got[1] != want1 || got[2] != want2) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "实例 " + inst + " 骨骼 " + b + " 旋转读到 "
                                    + java.util.Arrays.toString(got) + "，应为 [" + want0 + ", " + want1
                                    + ", " + want2 + "]";
                        }
                    }
                }
                for (int t = 0; t < trans; t++) {
                    int texel = dev.swordkingdom.horde.client.CrowdRenderer
                            .poseTexelAt(inst, stride, transBase, t);
                    float[] got = texel3(buf, texel);
                    float want0 = inst * 100000 + 3000 + t;
                    if (got[0] != want0 || got[1] != want0 + 1000 || got[2] != want0 + 2000) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "实例 " + inst + " 位移通道 " + t + " 读到 "
                                    + java.util.Arrays.toString(got) + "，应为 [" + want0 + ", "
                                    + (want0 + 1000) + ", " + (want0 + 2000) + "]";
                        }
                    }
                }
                for (int s = 0; s < scale; s++) {
                    int texel = dev.swordkingdom.horde.client.CrowdRenderer
                            .poseTexelAt(inst, stride, scaleBase, s);
                    float[] got = texel3(buf, texel);
                    float want0 = inst * 100000 + 6000 + s;
                    if (Math.abs(got[0] - want0) > 1.0E-3F || Math.abs(got[1] - (want0 + 1000)) > 1.0E-3F
                            || Math.abs(got[2] - (want0 + 2000)) > 1.0E-3F) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "实例 " + inst + " 缩放通道 " + s + " 读到 "
                                    + java.util.Arrays.toString(got) + "，应为 [" + want0 + ", "
                                    + (want0 + 1000) + ", " + (want0 + 2000) + "]";
                        }
                    }
                }
            }
            check(bad == 0, "骨骼 " + bones + "/位移 " + trans + "/缩放 " + scale
                    + "：逐骨骼逐通道哨兵必须全部对上（错位 " + bad + " 处"
                    + (firstBad == null ? "" : "；首个：" + firstBad) + "）");

            // ---- 相邻实例不许互相覆盖：实例 0 的最后一个 texel 之外就是实例 1 的第 0 个 texel ----
            int lastOfRow0 = dev.swordkingdom.horde.client.CrowdRenderer
                    .poseTexelAt(0, stride, scaleBase, Math.max(0, scale - 1));
            int firstOfRow1 = dev.swordkingdom.horde.client.CrowdRenderer.poseTexelAt(1, stride, 0, 0);
            check(firstOfRow1 - lastOfRow0 == stride - (scaleBase + Math.max(0, scale - 1)),
                    "每个实例的起始 texel 必须正好错开一个 stride（实际间隔 " + (firstOfRow1 - lastOfRow0)
                            + "，期望 " + (stride - (scaleBase + Math.max(0, scale - 1))) + "）");
            check(buf.length == instances * stride * 4,
                    "缓冲大小必须是 实例数 × stride × 4 float（texel = 4 float；实际 " + buf.length + "）");
        }
        System.out.println("      形状 12/0/0、20/6/0、20/6/4、7/1/1 的哨兵往返全部逐骨骼对上");
    }

    /** 按 texel 下标读回三个分量（texel 是 4 float，第 4 个未用 —— 与着色器 .xyz 一致） */
    private static float[] texel3(float[] buf, int texel) {
        int at = texel * 4;
        return new float[]{buf[at], buf[at + 1], buf[at + 2]};
    }


    /**
     * 替身渲染器需要往 {@code MultiBufferSource} 要一个顶点消费者，而真实渲染器要的是
     * {@code buffer.getBuffer(RenderType.xxx())}。离线的 harness 里 {@code RenderType}
     * 可能根本初始化不了（它的静态初始化要一堆渲染期才有的东西），于是这里**先探一次**并说明：
     * 探得通就按真实写法用；探不通就让替身传 {@code null}（{@code StaticSource} 本来就会忽略它），
     * 并在输出里讲明白为什么 —— 而不是让「捕获失败」蒙上一层看不懂的异常。
     */
    private static void diagnoseRenderType() {
        // RenderType 的静态初始化要用到注册表（离线 JVM 里默认没 bootstrap）——
        // 先试一次原版的 Bootstrap.bootStrap()：成功的话替身渲染器就能用真实 RenderType，
        // 而且能直接构造**真实的末影龙模型**（EnderDragonRenderer 的静态初始化也要 RenderType）。
        try {
            // 原版 main() 的顺序就是先 tryDetectVersion 再 bootStrap（否则 SharedConstants 会报
            // "Game version not set"）
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            System.out.println("[diag] Bootstrap.bootStrap() 成功（注册表就绪）");
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            System.out.println("[diag] Bootstrap.bootStrap() 失败（" + c + "）→ 继续用不依赖注册表的替身");
        }
        try {
            Class.forName("net.minecraft.client.renderer.RenderType");
            renderTypeUsable = true;
            System.out.println("[diag] RenderType 离线可初始化 → 替身渲染器按真实写法 getBuffer(RenderType.lightning())");
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            renderTypeUsable = false;
            System.out.println("[diag] RenderType 离线不可初始化（" + c + "）"
                    + " → 替身渲染器改为 getBuffer(null)（StaticSource 忽略 RenderType，线上照样是真实 RenderType）");
        }
    }

    /** 替身渲染器要用的 RenderType（离线拿不到就用 null） */
    private static RenderType bufferType() {
        return renderTypeUsable ? RenderType.lightning() : null;
    }

    private static boolean renderTypeUsable;

    // ==================================================================
    // (a) 渲染器实例字段里的 EntityModel
    // ==================================================================

    private static void caseEntityModelField() {
        System.out.println("[case a] 渲染器字段里有 EntityModel（原版 EnderDragonRenderer 的形状：private final Model model）");
        EntityMeshBaker.Mesh mesh = bake(new FieldRenderer(new ProbeModel()), "a");
        check(mesh != null && mesh.supported, "a: 应烘焙成功");
        if (mesh == null || !mesh.supported) {
            return;
        }
        check(mesh.boneCount == 4, "a: 骨骼数应为 4（root→torso/child→grandchild），实际 " + mesh.boneCount);
        check(mesh.maxChain == 3, "a: 最深链应为 3，实际 " + mesh.maxChain);
        check(!mesh.geo, "a: 普通 EntityModel 不能被当成 geo（朝向约定不同）");
        check(FIELD_TEX.equals(mesh.texture), "a: 贴图应来自渲染器的单参方法，实际 " + mesh.texture);
        check(mesh.indices.length > 0 && mesh.vertices.length > 0, "a: 应有几何输出");
        System.out.println("        骨骼 " + mesh.boneCount + " 根 / 顶点 "
                + mesh.vertices.length / EntityMeshBaker.FLOATS_PER_VERTEX + " / 三角 " + mesh.indices.length / 3
                + " / geo=" + mesh.geo + " / 贴图 " + mesh.texture);
    }

    // ==================================================================
    // (a2) 前置变换里的额外平移必须被烘进顶点
    // ==================================================================

    private static void caseFieldWithPreludeTranslation() {
        System.out.println("[case a2] 前置变换带额外平移（末影龙的 translate(0,0,1)）→ 必须烘进顶点");
        EntityMeshBaker.Mesh plain = bake(new FieldRenderer(new ProbeModel()), "a2-plain");
        EntityMeshBaker.Mesh shifted = bake(new DragonLikeRenderer(new ProbeModel()), "a2-shifted");
        check(plain != null && plain.supported && shifted != null && shifted.supported,
                "a2: 两种渲染器都应烘焙成功");
        if (plain == null || !plain.supported || shifted == null || !shifted.supported) {
            return;
        }
        float[] a = bounds(plain.vertices);
        float[] b = bounds(shifted.vertices);
        check(Math.abs(b[2] - a[2] - 1.0F) < 1.0E-3F && Math.abs(b[5] - a[5] - 1.0F) < 1.0E-3F,
                "a2: z 应整体 +1（前置 translate(0,0,1)），实际 z ∈ [" + b[2] + ", " + b[5]
                        + "]，基准 z ∈ [" + a[2] + ", " + a[5] + "]");
        check(Math.abs(b[0] - a[0]) < 1.0E-4F && Math.abs(b[1] - a[1]) < 1.0E-4F
                        && Math.abs(b[3] - a[3]) < 1.0E-4F && Math.abs(b[4] - a[4]) < 1.0E-4F,
                "a2: x/y 不应被改动（着色器那套固定变换已经覆盖了它们）");
        check(Math.abs(shifted.renderScaleX - 1.0F) < 1.0E-4F,
                "a2: scale(-1,-1,1) 取各列模长后应为 1，实际 " + shifted.renderScaleX);
        System.out.println("        基准 z ∈ [" + a[2] + ", " + a[5] + "]，带前置平移后 z ∈ ["
                + b[2] + ", " + b[5] + "]（差值 " + (b[5] - a[5]) + "）");
    }

    // ==================================================================
    // (a3) 真的原版末影龙模型跑一遍
    // ==================================================================

    /**
     * 这一条是本任务的核心证据：把**真正的** {@code EnderDragonRenderer.DragonModel}
     * （骨架由 {@code EnderDragonRenderer.createBodyLayer()} 烘出真 ModelPart 树）交给字段路径，
     * 验证它真的能被拆成骨架、出几何。真机上的「末影龙完全不显示」就是死在这一步之前。
     */
    private static void caseRealEnderDragonModel() {
        System.out.println("[case a3] 真实原版末影龙模型（createBodyLayer + DragonModel）");
        try {
            ModelPart root = EnderDragonRenderer.createBodyLayer().bakeRoot();
            int partCount = (int) root.getAllParts().count();
            Object model = new EnderDragonRenderer.DragonModel(root);
            EntityMeshBaker.Mesh mesh = bake(new FieldRenderer(model), "a3");
            check(mesh != null && mesh.supported, "a3: 真末影龙模型应烘焙成功");
            if (mesh == null || !mesh.supported) {
                return;
            }
            int verts = mesh.vertices.length / EntityMeshBaker.FLOATS_PER_VERTEX;
            // 方块数用公开的 visit() 数（ModelPart.cubes 是私有的）：每个方块 6 面 × 4 顶点 = 24 顶点
            int[] cubeCount = {0};
            root.visit(new PoseStack(), (pose, path, index, cube) -> cubeCount[0]++);
            // 根部件既没有方块、也不是 DragonModel 的字段（VanillaRig 用「字段 + visit 路径」发现部件），
            // 而它的变换是单位阵、没有几何，所以不需要它当骨骼：20 根 = 21 个部件 − 1 个根
            check(mesh.boneCount == partCount - 1, "a3: 骨骼数应为「部件数 − 1 个根」= " + (partCount - 1)
                    + "，实际 " + mesh.boneCount);
            check(verts == cubeCount[0] * 24, "a3: 顶点数应为 方块数×24 = " + (cubeCount[0] * 24) + "，实际 " + verts);
            check(mesh.indices.length > 0, "a3: 应有三角输出");
            check(!mesh.geo, "a3: 末影龙走的是原版朝向约定（着色器的翻转 + 1.501 正好等于它自己的 render）");
            check(mesh.maxChain <= 16, "a3: 最深链不应超过着色器上限 16，实际 " + mesh.maxChain);
            System.out.println("        部件 " + partCount + " 个（方块 " + cubeCount[0] + " 个）→ 骨骼 " + mesh.boneCount
                    + " 根，顶点 " + verts + "（= 方块×24），三角 " + mesh.indices.length / 3
                    + "，最深链 " + mesh.maxChain + "，贴图 " + mesh.texture);
        } catch (Throwable t) {
            fail("a3: 真末影龙模型烘焙抛异常: " + t);
        }
    }

    // ==================================================================
    // (b) geo（GeckoLib 形状）
    // ==================================================================

    private static void caseGeoViaGetter() {
        System.out.println("[case b] 渲染器只有 getGeoModel()（GeckoLib 的形状，模型**不**在字段里）→ geo 路径");
        EntityMeshBaker.Mesh mesh = bake(new GeoGetterRenderer(), "b");
        check(mesh != null && mesh.supported, "b: 应烘焙成功");
        if (mesh == null || !mesh.supported) {
            return;
        }
        check(mesh.geo, "b: 必须是 geo（朝向约定）");
        // 捕获兜底恒为 1 根骨骼、这个替身又没有任何模型字段 → boneCount > 1 就是「geo 路径真的跑了」的证据
        check(mesh.boneCount > 1, "b: geo 路径应拆出多根骨骼（>1），实际 " + mesh.boneCount
                + "（=1 说明落到捕获兜底了）");
        check(mesh.indices.length > 0, "b: 应有几何输出");
        check(GEO_TEX.equals(mesh.texture), "b: 贴图应来自 getTextureLocation，实际 " + mesh.texture);
        System.out.println("        骨骼 " + mesh.boneCount + " 根 / 顶点 "
                + mesh.vertices.length / EntityMeshBaker.FLOATS_PER_VERTEX + " / geo=" + mesh.geo);
    }

    private static void caseGeoInField() {
        System.out.println("[case b2] geo 模型被放在渲染器字段里 → 字段路径也要认出 geo 朝向");
        EntityMeshBaker.Mesh mesh = bake(new GeoFieldRenderer(), "b2");
        check(mesh != null && mesh.supported, "b2: 应烘焙成功");
        if (mesh == null || !mesh.supported) {
            return;
        }
        check(mesh.geo, "b2: geo 模型即使从字段里拿到，也必须按 geo 朝向处理（否则会倒立）");
        check(mesh.boneCount > 1, "b2: 应拆出多根骨骼，实际 " + mesh.boneCount);
        System.out.println("        骨骼 " + mesh.boneCount + " 根 / geo=" + mesh.geo);
    }

    // ==================================================================
    // (c) 捕获兜底
    // ==================================================================

    private static void caseCaptureFallback() {
        System.out.println("[case c] 没有模型、没有 geo，但 render() 会写顶点 → 捕获兜底（静态、boneCount=1）");
        EntityMeshBaker.Mesh mesh = bake(new CaptureRenderer(), "c");
        check(mesh != null && mesh.supported, "c: 应烘焙成功（这正是「永不不可见」的保证）");
        if (mesh == null || !mesh.supported) {
            return;
        }
        check(mesh.boneCount == 1, "c: 捕获网格应只有 1 根骨骼，实际 " + mesh.boneCount);
        check(mesh.geo, "c: 捕获顶点已是最终朝向 → 必须按 geo（不翻不偏移）渲染");
        check(mesh.transCount == 0 && mesh.scaleCount == 0, "c: 捕获网格不应有位移/缩放通道");
        check(mesh.maxChain == 1, "c: 捕获网格没有父链，最深链应为 1，实际 " + mesh.maxChain);
        check(mesh.indices.length == 6, "c: 一个四边形应变成 2 个三角形（6 个下标），实际 " + mesh.indices.length);
        check(CAPTURE_TEX.equals(mesh.texture), "c: 贴图应来自 getTextureLocation，实际 " + mesh.texture);
        check(mesh.pose != null && mesh.pose.boneCount() == 1, "c: 姿态对象应有 1 根骨骼");
        check(mesh.pose != null && mesh.pose.parts().length == 1 && mesh.pose.parts()[0].name() != null,
                "c: 部件名必须可读（编辑界面要列它，不能 NPE）");
        check(mesh.pose != null && mesh.pose.evaluate(0, 0, 0, 0, 0, 0, 0, 0D, 0D, 0D, 0, false, 0, 0,
                new float[9]), "c: 静态姿态必须能求值");
        System.out.println("        顶点 " + mesh.vertices.length / EntityMeshBaker.FLOATS_PER_VERTEX
                + " / 三角 " + mesh.indices.length / 3 + " / geo=" + mesh.geo
                + " / 贴图 " + mesh.texture);
    }

    // ==================================================================
    // (d) 渲染器抛异常 / 什么都不写
    // ==================================================================

    private static void caseRenderThrows() {
        System.out.println("[case d] render() 抛异常 → 不崩、不产出错误网格、原因可读（并由收尾那句打 WARN）");
        EntityMeshBaker.Mesh mesh = bake(new ThrowingRenderer(), "d");
        check(mesh != null && !mesh.supported, "d: 不应产出网格");
        check(mesh == null || mesh.indices.length == 0, "d: 不应有三角");
        List<String> reasons = EntityMeshBaker.lastReasons();
        check(!reasons.isEmpty(), "d: 必须有逐条原因（绝不静默）");
        check(reasons.stream().anyMatch(r -> r.contains("抛异常")), "d: 原因里应说明「渲染时抛异常」");
        System.out.println("        原因：" + reasons);
    }

    private static void caseRenderWritesNothing() {
        System.out.println("[case d2] render() 什么都不写 → 跳过并说明");
        EntityMeshBaker.Mesh mesh = bake(new EmptyRenderer(), "d2");
        check(mesh != null && !mesh.supported, "d2: 不应产出网格");
        List<String> reasons = EntityMeshBaker.lastReasons();
        check(reasons.stream().anyMatch(r -> r.contains("一个顶点都没写")), "d2: 原因应说明「没写顶点」");
        System.out.println("        原因：" + reasons);
    }

    // ==================================================================
    // (e) 顶点数上限
    // ==================================================================

    private static void caseCaptureOverflow() {
        System.out.println("[case e] render() 写的顶点超过上限（" + RendererCapture.MAX_VERTICES
                + "，这里写 250k）→ 整块跳过并说明（别把内存打爆）");
        EntityMeshBaker.Mesh mesh = bake(new HugeRenderer(), "e");
        check(mesh != null && !mesh.supported, "e: 超上限时不应产出网格");
        List<String> reasons = EntityMeshBaker.lastReasons();
        check(reasons.stream().anyMatch(r -> r.contains("超过上限")), "e: 原因应说明「超过上限」");
        System.out.println("        原因：" + reasons);
    }

    /** 直接验证「上限写在写入路径上」：到顶之后不再增长，内存有界。 */
    private static void caseCaptureCapAtWritePath() {
        System.out.println("[case e2] 上限写在顶点截获器的写入路径上（内存有界）");
        ProjectileBaker.Capture cap = new ProjectileBaker.Capture();
        cap.setMaxVertices(10);
        for (int i = 0; i < 40; i++) {
            cap.vertex(i, i, i).color(255, 255, 255, 255).uv(0, 0)
                    .overlayCoords(0, 0).uv2(0, 0).normal(0, 1, 0).endVertex();
        }
        check(cap.vertexCount() == 10, "e2: 应恰好停在 10 个顶点，实际 " + cap.vertexCount());
        check(cap.overflowed(), "e2: 必须标记「溢出过」（否则调用方会当成完整网格）");
        System.out.println("        上限 10、写入 40 → 截获 " + cap.vertexCount() + " 个顶点，overflowed="
                + cap.overflowed());
    }

    // ==================================================================
    // (e3) 图元模式：四边形 vs 三角形（顶点序列本身分不出来，必须由 RenderType 告诉截获器）
    // ==================================================================

    /**
     * 这条用例是给一个**已存在的缺陷**上锁的：原来的 {@code Capture} 是「先按 4 个一组，
     * 又在第 3 个顶点提前收口」，于是每个四边形只出一个三角形、第 4 个顶点还会跟下一个面的
     * 前两个顶点拼成跨面垃圾三角形 —— 而生物模型的面**全是四边形**。
     * 现在按 {@code RenderType.mode()}（{@code QUADS}/{@code TRIANGLES}）分组，默认四边形。
     */
    private static void casePrimitiveModes() {
        System.out.println("[case e3] 图元模式：四边形 → 2 个三角形；三角形模式 → 1 个三角形");
        ProjectileBaker.Capture quad = new ProjectileBaker.Capture();
        writeVertices(quad, 4);
        check(quad.buildIndices().length == 6, "e3: 4 个顶点（四边形）应出 2 个三角形 = 6 个下标，实际 "
                + quad.buildIndices().length);

        ProjectileBaker.Capture tri = new ProjectileBaker.Capture();
        tri.setPrimitiveMode(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES);
        writeVertices(tri, 3);
        check(tri.buildIndices().length == 3, "e3: 3 个顶点（三角形模式）应出 1 个三角形 = 3 个下标，实际 "
                + tri.buildIndices().length);

        ProjectileBaker.Capture quads2 = new ProjectileBaker.Capture();
        quads2.setPrimitiveMode(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        writeVertices(quads2, 8);
        check(quads2.buildIndices().length == 12, "e3: 8 个顶点（两个四边形）应出 4 个三角形 = 12 个下标，实际 "
                + quads2.buildIndices().length);
        System.out.println("        四边形 4 顶点 → " + quad.buildIndices().length + " 下标；"
                + "三角形 3 顶点 → " + tri.buildIndices().length + " 下标；"
                + "四边形 8 顶点 → " + quads2.buildIndices().length + " 下标");
    }

    private static void writeVertices(VertexConsumer vc, int count) {
        for (int i = 0; i < count; i++) {
            vc.vertex(i, i, i).color(255, 255, 255, 255).uv(0, 0)
                    .overlayCoords(0, 0).uv2(0, 0).normal(0, 1, 0).endVertex();
        }
    }

    // ==================================================================
    // (f) 三条路都不成立
    // ==================================================================

    private static void caseNothingAtAll() {
        System.out.println("[case f] 三条路都不成立（无字段、无取值方法、无 render）→ 必须给出逐条原因");
        EntityMeshBaker.Mesh mesh = bake(new Object(), "f");
        check(mesh != null && !mesh.supported, "f: 不应产出网格");
        List<String> reasons = EntityMeshBaker.lastReasons();
        check(reasons.size() >= 3, "f: 三条路各自都要有原因，实际 " + reasons.size() + " 条");
        check(reasons.stream().anyMatch(r -> r.contains("EntityModel")), "f: 应说明「字段里没有 EntityModel」");
        check(reasons.stream().anyMatch(r -> r.contains("geo")), "f: 应说明「没有 geo 模型」");
        check(reasons.stream().anyMatch(r -> r.contains("render")), "f: 应说明「找不到 render 方法」");
        System.out.println("        原因：" + reasons);
    }

    // ==================================================================
    // 替身渲染器 / 替身模型
    // ==================================================================

    /** 3 根骨骼的普通（ModelPart 系）替身模型：root → torso/child → grandchild */
    public static final class ProbeModel extends EntityModel<Entity> {
        final ModelPart root;

        public ProbeModel() {
            super(RenderType::entityCutoutNoCull);
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();
            // 注意：每个部件都要有方块 —— 原版 ModelPart.visit 只对「有方块或子部件」的部件回调，
            // 而 VanillaRig 靠 visit 的路径还原层级（没方块的叶子部件发现不到）
            r.addOrReplaceChild("torso",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-3, 0, -2, 6, 6, 4),
                    PartPose.offset(0, 12, 0));
            PartDefinition child = r.addOrReplaceChild("child",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-2, 0, -2, 4, 6, 4),
                    PartPose.offset(0, 6, 0));
            child.addOrReplaceChild("grandchild",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-1, 0, -1, 2, 4, 2),
                    PartPose.offset(0, 6, 0));
            root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        }

        @Override
        public void prepareMobModel(Entity entity, float limbSwing, float limbSwingAmount, float partialTick) {
            // 替身模型不依赖实体状态；离线时 entity 传 null 也照样能跑
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                              float netHeadYaw, float headPitch) {
            // 故意留空：骨骼保持绑定姿态，便于断言几何
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            root.render(poseStack, buffer, light, overlay, red, green, blue, alpha);
        }
    }

    /**
     * == 原版 {@code EnderDragonRenderer} 的形状：模型只在私有实例字段里
     * （字段声明类型故意写成 {@code Object}，验证「按运行时的值认模型」也成立）。
     */
    public static final class FieldRenderer {
        private final Object model;

        public FieldRenderer(Object model) {
            this.model = model;
        }

        public ResourceLocation getTextureLocation(Object entity) {
            return FIELD_TEX;
        }
    }

    /**
     * 形状同 {@link FieldRenderer}，但 {@code render()} 先摆出末影龙那套前置变换
     * （{@code translate(0,0,1); scale(-1,-1,1); translate(0,-1.501,0)}）再画。
     * 用来验证「前置变换的残余平移会被烘进顶点」。
     */
    public static final class DragonLikeRenderer {
        private final ProbeModel model;

        public DragonLikeRenderer(ProbeModel model) {
            this.model = model;
        }

        public ResourceLocation getTextureLocation(Object entity) {
            return FIELD_TEX;
        }

        public void render(Object entity, float yaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffer, int light) {
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, 1.0F);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            model.renderToBuffer(poseStack, buffer.getBuffer(bufferType()), light, 0, 1, 1, 1, 1);
            poseStack.popPose();
        }
    }

    /**
     * == GeckoLib 的 {@code GeoEntityRenderer}：模型靠一个无参取值方法暴露。
     *
     * <p>故意**每次现造**一个新模型（不放在字段里）：这样「字段路径」看不到它，
     * 验证的才是 geo 那条路本身（GeoHarness 里那套替身 geo 模型是 EntityModel 的子类，
     * 放在字段里会被字段路径先接走）。
     */
    public static final class GeoGetterRenderer {
        public Object getGeoModel() {
            return new GeoHarness.QuadsGeoModel();
        }

        public ResourceLocation getTextureLocation(Object entity) {
            return GEO_TEX;
        }
    }

    /** geo 模型真的放在字段里（有模组这么干）：字段路径也必须认得出它是 geo 形状 */
    public static final class GeoFieldRenderer {
        private final GeoHarness.QuadsGeoModel geoModel = new GeoHarness.QuadsGeoModel();

        public ResourceLocation getTextureLocation(Object entity) {
            return GEO_TEX;
        }
    }

    /** 只有 render()：既没有模型字段，也没有模型取值方法 → 只能走捕获兜底 */
    public static final class CaptureRenderer {
        public ResourceLocation getTextureLocation(Object entity) {
            return CAPTURE_TEX;
        }

        public void render(Object entity, float yaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffer, int light) {
            quad(buffer.getBuffer(bufferType()), 0.0F, 0.0F, 0.0F, 1.0F);
        }
    }

    /** 渲染时抛异常 */
    public static final class ThrowingRenderer {
        public void render(Object entity, float yaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffer, int light) {
            throw new IllegalStateException("替身渲染器故意抛异常");
        }
    }

    /** 一个顶点都不写 */
    public static final class EmptyRenderer {
        public void render(Object entity, float yaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffer, int light) {
            buffer.getBuffer(bufferType());
        }
    }

    /** 写 250k 个顶点（超过 200k 的上限） */
    public static final class HugeRenderer {
        public void render(Object entity, float yaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffer, int light) {
            VertexConsumer vc = buffer.getBuffer(bufferType());
            for (int i = 0; i < 62_500; i++) {
                quad(vc, i * 0.001F, 0.0F, 0.0F, 1.0F);
            }
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 往顶点消费者里写一个朝向 +Z 的四边形（4 顶点 = 2 个三角形） */
    private static void quad(VertexConsumer vc, float x, float y, float z, float size) {
        float[][] corners = {
                {x, y, z}, {x + size, y, z}, {x + size, y + size, z}, {x, y + size, z},
        };
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            vc.vertex(corners[i][0], corners[i][1], corners[i][2])
                    .color(255, 255, 255, 255)
                    .uv(uvs[i][0], uvs[i][1])
                    .overlayCoords(0, 0)
                    .uv2(15728880)
                    .normal(0.0F, 0.0F, 1.0F)
                    .endVertex();
        }
    }

    private static float[] bounds(float[] vertices) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i + EntityMeshBaker.FLOATS_PER_VERTEX <= vertices.length;
             i += EntityMeshBaker.FLOATS_PER_VERTEX) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], vertices[i + a]);
                b[a + 3] = Math.max(b[a + 3], vertices[i + a]);
            }
        }
        return b;
    }

    /**
     * 跑一遍烘焙并打印证据。
     *
     * <p>返回 null 表示「烘焙过程中抛了异常」—— 那本身就是缺陷（这条链路必须自己消化异常），
     * 这里记一次失败后继续跑别的用例，好把问题一次看全。
     */
    private static EntityMeshBaker.Mesh bake(Object renderer, String tag) {
        EntityMeshBaker.Mesh mesh;
        try {
            mesh = EntityMeshBaker.bakeFromRenderer(renderer, null, UNIT);
        } catch (Throwable t) {
            fail(tag + ": bakeFromRenderer 抛异常（必须内部消化）: " + t);
            return null;
        }
        if (!EntityMeshBaker.lastReasons().isEmpty()) {
            System.out.println("        （路径说明：" + EntityMeshBaker.lastReasons() + "）");
        }
        return mesh;
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
}
