package dev.swordkingdom.horde.harness;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.client.CrowdPose;
import dev.swordkingdom.horde.client.RigBaker;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

/**
 * 离线验证：不需要 Minecraft 客户端单例，也不需要 GL 上下文。
 *
 * 验证「群集单位的骨骼动作 = 真实实体」这件事：
 *  1. 烘焙出的绑定姿态顶点，与「旋转归零、缩放归一后按原版 PoseStack 遍历」逐位一致
 *     （即使 setupAnim 已经把位置/旋转/缩放弄脏）；
 *  2. 用 {@link CrowdPose} 驱动模型自己的 setupAnim（与 {@code LivingEntityRenderer} 完全相同的输入），
 *     再用顶点着色器那套「父链旋转」算出顶点位置，与原版 PoseStack 渲染同一姿态的结果一致；
 *     因为两边跑的是同一份模型代码，偏差只应来自矩阵浮点运算（约 1e-7）。
 *     用例覆盖：行走相位、行走幅度、年龄、头部偏航/俯仰、挥动进度、受击 —— 即「完全一样」的全部输入通道。
 *  3. 结构：骨骼数、最深链、骨骼超限裁剪后顶点索引仍然合法。
 */
public final class RigHarness {

    private static int failures;

    public static void main(String[] args) {
        testBiped();
        testQuadruped();
        testSpider();
        testChainDepth();
        testWidePrune();
        System.out.println(failures == 0 ? "\n== ALL PASS ==" : "\n== FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    private static void testBiped() {
        BipedModel model = new BipedModel();
        String tag = "biped(人形 + 帽子/前臂嵌套层级)";
        RigBaker.Rig rig = bake(model, tag);
        if (rig == null) {
            return;
        }
        check(rig.boneCount == 10, tag + ": 骨骼数应为 10，实际 " + rig.boneCount);
        verify(model, rig, tag, model.root, new float[][]{
                // limbSwing, amount, age, headYaw, headPitch, attackAnim, hurtTicks
                {0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0},
                {1.234F, 1.0F, 0.0F, 30.0F, -10.0F, 0.0F, 0},
                {3.0F, 0.5F, 0.0F, -60.0F, 25.0F, 0.0F, 0},
                {5.5F, 0.25F, 0.0F, 0.0F, 0.0F, 0.0F, 0},
                // 挥动 / 受击 / 年龄通道
                {2.0F, 1.0F, 37.5F, 15.0F, 5.0F, 0.7F, 0},
                {2.0F, 1.0F, 12.25F, -20.0F, -8.0F, 0.15F, 4},
                {4.4F, 0.8F, 91.75F, 0.0F, 0.0F, 1.0F, 10},
        });
    }

    private static void testQuadruped() {
        QuadrupedModel model = new QuadrupedModel();
        String tag = "quadruped(四足 + 小腿子部件)";
        RigBaker.Rig rig = bake(model, tag);
        if (rig == null) {
            return;
        }
        check(rig.boneCount == 12, tag + ": 骨骼数应为 12，实际 " + rig.boneCount);
        verify(model, rig, tag, model.root, new float[][]{
                {0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0},
                {1.9F, 1.0F, 0.0F, 20.0F, -15.0F, 0.0F, 0},
                {4.2F, 0.6F, 33.5F, -35.0F, 10.0F, 0.55F, 6},
        });
    }

    private static void testSpider() {
        SpiderModel model = new SpiderModel();
        String tag = "spider(八足 + 每腿两段 + 固定外撇角)";
        RigBaker.Rig rig = bake(model, tag);
        if (rig == null) {
            return;
        }
        check(rig.boneCount == 20, tag + ": 骨骼数应为 20，实际 " + rig.boneCount);
        verify(model, rig, tag, model.root, new float[][]{
                {0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0},
                {2.5F, 1.0F, 8.0F, 45.0F, -20.0F, 0.9F, 3},
                {6.1F, 0.8F, 70.5F, -90.0F, 30.0F, 0.0F, 0},
        });
    }

    /** 8 段深链：正好用满 MAX_CHAIN */
    private static void testChainDepth() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition cur = mesh.getRoot();
        for (int i = 0; i < 8; i++) {
            cur = cur.addOrReplaceChild("seg" + i,
                    CubeListBuilder.create().texOffs(0, 0).addBox(-1.0F, 0.0F, -1.0F, 2, 4, 2),
                    PartPose.offset(0.0F, 4.0F, 0.0F));
        }
        ModelPart baked = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        ChainModel model = new ChainModel(baked, 8);
        String tag = "chain(8 段深链，用满 MAX_CHAIN)";
        RigBaker.Rig rig = bake(model, tag);
        if (rig == null) {
            return;
        }
        check(rig.boneCount == 9, tag + ": 骨骼数应为 9，实际 " + rig.boneCount);
        verify(model, rig, tag, baked, new float[][]{
                {1.1F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0},
                {2.7F, 0.5F, 20.0F, 15.0F, 5.0F, 0.4F, 2},
        });
    }

    /** 40 根骨骼：验证裁剪后仍然自洽（顶点骨骼索引合法、根骨骼保留） */
    private static void testWidePrune() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        for (int i = 0; i < 40; i++) {
            root.addOrReplaceChild("part" + i,
                    CubeListBuilder.create().texOffs(0, 0).addBox(-1.0F, 0.0F, -1.0F, 2, 4, 2),
                    PartPose.offset(i * 0.25F, 0.0F, 0.0F));
        }
        ModelPart baked = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        ChainModel model = new ChainModel(baked, 40);
        String tag = "wide(40 根骨骼 -> 裁剪)";
        RigBaker.Rig rig = bake(model, tag);
        if (rig == null) {
            return;
        }
        int v = rig.vertices.length / RigBaker.FLOATS_PER_VERTEX;
        int maxBone = -1;
        int minBone = Integer.MAX_VALUE;
        for (int i = 0; i < v; i++) {
            int b = (int) rig.vertices[i * RigBaker.FLOATS_PER_VERTEX + 8];
            maxBone = Math.max(maxBone, b);
            minBone = Math.min(minBone, b);
        }
        check(v > 0, tag + ": 应有顶点输出");
        check(rig.boneCount <= RigBaker.MAX_BONES, tag + ": 骨骼数不应超过 " + RigBaker.MAX_BONES
                + "，实际 " + rig.boneCount);
        check(minBone >= 0 && maxBone < rig.boneCount,
                tag + ": 顶点骨骼索引越界 [" + minBone + ", " + maxBone + ")，boneCount=" + rig.boneCount);
        check(rig.pivot4[0] == 0.0F && rig.pivot4[1] == 0.0F && rig.pivot4[2] == 0.0F,
                tag + ": 根骨骼应保留且枢轴在原点");
    }

    // ------------------------------------------------------------------
    // 核心验证
    // ------------------------------------------------------------------

    private static RigBaker.Rig bake(EntityModel<Entity> model, String tag) {
        RigBaker.Rig rig;
        try {
            rig = RigBaker.bake(model, null);
        } catch (Throwable t) {
            failures++;
            System.out.println("[FAIL] " + tag + " 烘焙抛异常: " + t);
            t.printStackTrace(System.out);
            return null;
        }
        if (rig == null) {
            failures++;
            System.out.println("[FAIL] " + tag + " 烘焙返回 null（无法识别部件系统）");
            return null;
        }
        System.out.println("[ ok ] " + tag + " 烘焙成功：骨骼 " + rig.boneCount
                + "，顶点 " + (rig.vertices.length / RigBaker.FLOATS_PER_VERTEX)
                + "，最深链 " + rig.maxChain
                + "，位移通道 " + rig.transCount);
        return rig;
    }

    /**
     * 对每个用例比较「原版 PoseStack」与「CrowdPose + 父链旋转（着色器公式）」。
     */
    private static void verify(EntityModel<Entity> model, RigBaker.Rig rig, String tag,
                               ModelPart root, float[][] cases) {
        int floatsPerVertex = RigBaker.FLOATS_PER_VERTEX;
        int vertexCount = rig.vertices.length / floatsPerVertex;
        CrowdPose pose = new CrowdPose(model, null, rig.parts);
        float[] angles = new float[rig.boneCount * CrowdPose.STRIDE];

        // 0) 绑定姿态：先跑一次 setupAnim 把位置/旋转/缩放弄脏，再验证烘焙结果就是定义姿态
        driveVanilla(model, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        zeroRotationsAndScale(root);
        float[] bind = capture(root);
        check(bind.length == vertexCount * 3,
                tag + ": 顶点数不一致 bind=" + (bind.length / 3) + " rig=" + vertexCount);
        float maxBind = 0.0F;
        for (int i = 0; i < vertexCount; i++) {
            for (int a = 0; a < 3; a++) {
                maxBind = Math.max(maxBind, Math.abs(bind[i * 3 + a] - rig.vertices[i * floatsPerVertex + a]));
            }
        }
        System.out.println("      绑定姿态与烘焙最大偏差 = " + maxBind);
        check(maxBind < 1.0E-4F, tag + ": 绑定姿态顶点与烘焙结果不一致，最大偏差 " + maxBind);

        // 1) 各通道输入：原版 PoseStack vs CrowdPose + 父链旋转
        for (float[] c : cases) {
            float limbSwing = c[0];
            float amount = c[1];
            float age = c[2];
            float headYaw = c[3];
            float headPitch = c[4];
            float attack = c[5];
            int hurt = (int) c[6];

            // 基准：完全按原版渲染器的顺序驱动模型
            driveVanilla(model, limbSwing, amount, age, headYaw, headPitch, attack);
            unitScales(root);
            float[] gt = capture(root);

            // 待验证：CrowdPose 驱动（同样的输入），再用着色器公式算顶点
            boolean ok = pose.evaluate(limbSwing, amount, age, headYaw, headPitch, attack, hurt,
                    0.0D, 0.0D, 0.0D, 0.0F, true, angles);
            check(ok, tag + ": CrowdPose 驱动失败");

            float max = 0.0F;
            int worst = -1;
            for (int i = 0; i < vertexCount; i++) {
                int bone = (int) rig.vertices[i * floatsPerVertex + 8];
                float[] out = chainTransform(rig, angles, bone,
                        rig.vertices[i * floatsPerVertex],
                        rig.vertices[i * floatsPerVertex + 1],
                        rig.vertices[i * floatsPerVertex + 2]);
                for (int a = 0; a < 3; a++) {
                    float d = Math.abs(out[a] - gt[i * 3 + a]);
                    if (d > max) {
                        max = d;
                        worst = i;
                    }
                }
            }
            System.out.println("      用例 limbSwing=" + limbSwing + " amount=" + amount
                    + " age=" + age + " head=(" + headYaw + "," + headPitch + ")"
                    + " attack=" + attack + " hurt=" + hurt + " 最大偏差 = " + max);
            check(max < 1.0E-3F, tag + ": 用例 limbSwing=" + limbSwing + " 偏差过大 " + max
                    + "（最差顶点 #" + worst + "）");
        }

        driveVanilla(model, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    /** 完全按原版 {@code LivingEntityRenderer.render} 的顺序驱动模型（基准侧）。 */
    private static void driveVanilla(EntityModel<Entity> model, float limbSwing, float amount,
                                     float age, float headYaw, float headPitch, float attack) {
        model.attackTime = attack;
        model.riding = false;
        model.young = false;
        model.prepareMobModel(null, limbSwing, amount, 0.0F);
        model.setupAnim(null, limbSwing, amount, age, headYaw, headPitch);
    }

    /** 复刻顶点着色器：沿父链逐级旋转（+ 可选位移）。 */
    private static float[] chainTransform(RigBaker.Rig rig, float[] angles, int bone,
                                          float x, float y, float z) {
        float px = x;
        float py = y;
        float pz = z;
        int b = bone;
        for (int i = 0; i < RigBaker.MAX_CHAIN && b >= 0; i++) {
            int o = b * CrowdPose.STRIDE;
            float[][] m = rotZYX(angles[o], angles[o + 1], angles[o + 2]);
            float tx = angles[o + 3];
            float ty = angles[o + 4];
            float tz = angles[o + 5];
            float pivX = rig.pivot4[b * 4];
            float pivY = rig.pivot4[b * 4 + 1];
            float pivZ = rig.pivot4[b * 4 + 2];
            // 与原版 ModelPart.translateAndRotate 一致：先缩放、再旋转、最后平移到枢轴
            float sx = angles[o + 6];
            float sy = angles[o + 7];
            float sz = angles[o + 8];
            float lx = (px - pivX) * sx;
            float ly = (py - pivY) * sy;
            float lz = (pz - pivZ) * sz;
            px = m[0][0] * lx + m[0][1] * ly + m[0][2] * lz + pivX + tx;
            py = m[1][0] * lx + m[1][1] * ly + m[1][2] * lz + pivY + ty;
            pz = m[2][0] * lx + m[2][1] * ly + m[2][2] * lz + pivZ + tz;
            b = (int) (rig.pivot4[b * 4 + 3] + 0.5F) - 1;
        }
        return new float[]{px, py, pz};
    }

    /** 与着色器 rotZYX(x,y,z) = rotZ(z)·rotY(y)·rotX(x) 完全一致。 */
    private static float[][] rotZYX(float x, float y, float z) {
        float[][] rx = {
                {1.0F, 0.0F, 0.0F},
                {0.0F, cos(x), -sin(x)},
                {0.0F, sin(x), cos(x)},
        };
        float[][] ry = {
                {cos(y), 0.0F, sin(y)},
                {0.0F, 1.0F, 0.0F},
                {-sin(y), 0.0F, cos(y)},
        };
        float[][] rz = {
                {cos(z), -sin(z), 0.0F},
                {sin(z), cos(z), 0.0F},
                {0.0F, 0.0F, 1.0F},
        };
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

    private static float cos(float a) {
        return (float) Math.cos(a);
    }

    private static float sin(float a) {
        return (float) Math.sin(a);
    }

    /** 按原版 PoseStack 遍历，收集顶点位置（顺序与 RigBaker 的发射顺序一致）。 */
    private static float[] capture(ModelPart root) {
        PositionCapture cap = new PositionCapture();
        root.visit(new PoseStack(), (pose, path, index, cube) ->
                cube.compile(pose, cap, 0, 0, 1.0F, 1.0F, 1.0F, 1.0F));
        return cap.toArray();
    }

    private static void zeroRotationsAndScale(ModelPart root) {
        root.getAllParts().forEach(p -> {
            ModelPart part = (ModelPart) p;
            part.xRot = 0.0F;
            part.yRot = 0.0F;
            part.zRot = 0.0F;
            part.xScale = 1.0F;
            part.yScale = 1.0F;
            part.zScale = 1.0F;
        });
    }

    /** 只把缩放归一到定义姿态，保留旋转（着色器不支持缩放动画）。 */
    private static void unitScales(ModelPart root) {
        root.getAllParts().forEach(p -> {
            ModelPart part = (ModelPart) p;
            part.xScale = 1.0F;
            part.yScale = 1.0F;
            part.zScale = 1.0F;
        });
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    // ------------------------------------------------------------------
    // 顶点收集
    // ------------------------------------------------------------------

    private static final class PositionCapture implements VertexConsumer {
        private float[] a = new float[4096];
        private int n;
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
        public VertexConsumer color(int r, int g, int b, int alpha) {
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
            if (n + 3 > a.length) {
                float[] b = new float[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = x;
            a[n++] = y;
            a[n++] = z;
        }

        @Override
        public void defaultColor(int r, int g, int b, int alpha) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        float[] toArray() {
            float[] b = new float[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }

    // ------------------------------------------------------------------
    // 测试模型
    // ------------------------------------------------------------------

    /** 人形 + 帽子/前臂嵌套；故意让右腿的缩放随动画变化，验证烘焙器会还原成定义姿态 */
    public static final class BipedModel extends EntityModel<Entity> {
        public final ModelPart root;
        public final ModelPart head;
        public final ModelPart hat;
        public final ModelPart rightArm;
        public final ModelPart leftArm;
        public final ModelPart rightForearm;
        public final ModelPart leftForearm;
        public final ModelPart rightLeg;
        public final ModelPart leftLeg;

        public BipedModel() {
            super(RenderType::entityCutoutNoCull);
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();
            PartDefinition headP = r.addOrReplaceChild("head",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-4, -8, -4, 8, 8, 8),
                    PartPose.offset(0, 0, 0));
            headP.addOrReplaceChild("hat",
                    CubeListBuilder.create().texOffs(32, 0)
                            .addBox(-4, -8, -4, 8, 8, 8, new CubeDeformation(0.5F)),
                    PartPose.offset(0, -1, 0));
            r.addOrReplaceChild("body",
                    CubeListBuilder.create().texOffs(16, 16).addBox(-4, 0, -2, 8, 12, 4),
                    PartPose.offset(0, 0, 0));
            PartDefinition ra = r.addOrReplaceChild("right_arm",
                    CubeListBuilder.create().texOffs(40, 16).addBox(-3, -2, -2, 4, 12, 4),
                    PartPose.offset(-5, 2, 0));
            ra.addOrReplaceChild("right_forearm",
                    CubeListBuilder.create().texOffs(40, 16).addBox(-2, 0, -2, 4, 10, 4),
                    PartPose.offset(0, -2, 0));
            PartDefinition la = r.addOrReplaceChild("left_arm",
                    CubeListBuilder.create().texOffs(40, 16).addBox(-1, -2, -2, 4, 12, 4),
                    PartPose.offset(5, 2, 0));
            la.addOrReplaceChild("left_forearm",
                    CubeListBuilder.create().texOffs(40, 16).addBox(-2, 0, -2, 4, 10, 4),
                    PartPose.offset(0, -2, 0));
            r.addOrReplaceChild("right_leg",
                    CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4),
                    PartPose.offset(-1.9F, 12, 0));
            r.addOrReplaceChild("left_leg",
                    CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4),
                    PartPose.offset(1.9F, 12, 0));

            root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
            head = root.getChild("head");
            hat = head.getChild("hat");
            rightArm = root.getChild("right_arm");
            leftArm = root.getChild("left_arm");
            rightForearm = rightArm.getChild("right_forearm");
            leftForearm = leftArm.getChild("left_forearm");
            rightLeg = root.getChild("right_leg");
            leftLeg = root.getChild("left_leg");
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            head.xRot = headPitch * 0.017453292F;
            head.yRot = headYaw * 0.017453292F;
            float c = cos(limbSwing * 0.6662F);
            float s = sin(limbSwing * 0.6662F);
            hat.xRot = c * 0.3F * amount;
            hat.yRot = s * 0.2F * amount;
            // 挥动（原版 HumanoidModel.setupAttackAnimation 的形状）
            float f1 = 1.0F - attackTime;
            f1 = f1 * f1;
            f1 = f1 * f1;
            f1 = 1.0F - f1;
            float swing = sin(f1 * (float) Math.PI);
            float chop = sin(attackTime * (float) Math.PI);
            rightArm.xRot = c * 2.0F * amount * 0.5F - swing * 1.2F;
            rightArm.yRot = 0.0F;
            rightArm.zRot = chop * -0.4F;
            leftArm.xRot = cos(limbSwing * 0.6662F + (float) Math.PI) * 2.0F * amount * 0.5F;
            rightForearm.xRot = c * 0.6F * amount;
            leftForearm.xRot = s * 0.6F * amount + sin(age * 0.09F) * 0.05F;
            rightLeg.xRot = c * 1.4F * amount;
            leftLeg.xRot = cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * amount;
            // 缩放也随动画变化：烘焙器必须把它还原成定义姿态（着色器只做旋转）
            float sc = 1.0F + 0.5F * amount;
            rightLeg.xScale = sc;
            rightLeg.yScale = sc;
            rightLeg.zScale = sc;
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        }
    }

    /** 四足动物（牛/猪式），小腿是子部件 */
    public static final class QuadrupedModel extends EntityModel<Entity> {
        public final ModelPart root;
        public final ModelPart head;

        public QuadrupedModel() {
            super(RenderType::entityCutoutNoCull);
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();
            PartDefinition headP = r.addOrReplaceChild("head",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-4, -4, -6, 8, 8, 6),
                    PartPose.offset(0, 4, -8));
            headP.addOrReplaceChild("snout",
                    CubeListBuilder.create().texOffs(0, 14).addBox(-3, -2, -4, 6, 4, 4),
                    PartPose.offset(0, 0, -6));
            r.addOrReplaceChild("body",
                    CubeListBuilder.create().texOffs(18, 4).addBox(-6, -10, -7, 12, 18, 10),
                    PartPose.offset(0, 12, 0));
            addLeg(r, "right_hind_leg", -3, 12, 7);
            addLeg(r, "left_hind_leg", 3, 12, 7);
            addLeg(r, "right_front_leg", -3, 12, -7);
            addLeg(r, "left_front_leg", 3, 12, -7);

            root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
            head = root.getChild("head");
        }

        private static void addLeg(PartDefinition r, String name, float x, float y, float z) {
            PartDefinition leg = r.addOrReplaceChild(name,
                    CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 10, 4),
                    PartPose.offset(x, y, z));
            leg.addOrReplaceChild(name + "_hoof",
                    CubeListBuilder.create().texOffs(0, 30).addBox(-2, 0, -2, 4, 4, 4),
                    PartPose.offset(0, 10, 0));
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            head.xRot = headPitch * 0.017453292F;
            head.yRot = headYaw * 0.017453292F;
            head.getChild("snout").xRot = cos(limbSwing * 0.6662F) * 0.2F * amount;
            // 原版 QuadrupedModel：对角步
            leg("right_hind_leg").xRot = cos(limbSwing * 0.6662F) * 1.4F * amount;
            leg("left_hind_leg").xRot = cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * amount;
            leg("right_front_leg").xRot = cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * amount;
            leg("left_front_leg").xRot = cos(limbSwing * 0.6662F) * 1.4F * amount;
            // 小腿：2 倍频率
            leg("right_hind_leg").getChild("right_hind_leg_hoof").xRot
                    = cos(limbSwing * 0.6662F * 2.0F) * 0.5F * amount;
            leg("left_front_leg").getChild("left_front_leg_hoof").xRot
                    = cos(limbSwing * 0.6662F * 2.0F) * 0.5F * amount;
            // 年龄驱动的待机摆动（以前完全丢失，现在必须一致）
            leg("left_hind_leg").xRot += sin(age * 0.1F) * 0.15F * (1.0F - amount);
        }

        private ModelPart leg(String name) {
            return root.getChild(name);
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        }
    }

    /** 蜘蛛：八足 + 每腿两段 + 固定外撇角 */
    public static final class SpiderModel extends EntityModel<Entity> {
        public final ModelPart root;
        private final ModelPart[] legs = new ModelPart[8];

        public SpiderModel() {
            super(RenderType::entityCutoutNoCull);
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();
            r.addOrReplaceChild("head",
                    CubeListBuilder.create().texOffs(32, 4).addBox(-4, -4, -8, 8, 8, 8),
                    PartPose.offset(0, 15, -3));
            r.addOrReplaceChild("body0",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-3, -3, -3, 6, 6, 6),
                    PartPose.offset(0, 15, 0));
            r.addOrReplaceChild("body1",
                    CubeListBuilder.create().texOffs(0, 12).addBox(-5, -4, -6, 10, 8, 12),
                    PartPose.offset(0, 15, 9));
            for (int i = 0; i < 8; i++) {
                float side = (i % 2 == 0) ? -1.0F : 1.0F;
                int row = i / 2;
                float z = new float[]{2.0F, 1.0F, -1.0F, -2.0F}[row];
                float yRot = side * (float) (Math.PI / 4.0);
                float zRot = side * (float) (Math.PI / 4.0);
                PartDefinition leg = r.addOrReplaceChild("leg" + i,
                        CubeListBuilder.create().texOffs(18, 0).addBox(-15, -1, -1, 16, 2, 2),
                        PartPose.offsetAndRotation(side * 4.0F, 15.0F, z, 0.0F, yRot, zRot));
                leg.addOrReplaceChild("leg" + i + "_tip",
                        CubeListBuilder.create().texOffs(18, 0).addBox(-12, -1, -1, 12, 2, 2),
                        PartPose.offsetAndRotation(side * 14.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
            }
            root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
            for (int i = 0; i < 8; i++) {
                legs[i] = root.getChild("leg" + i);
            }
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            root.getChild("head").yRot = headYaw * 0.017453292F;
            root.getChild("head").xRot = headPitch * 0.017453292F;
            float f = (float) (Math.PI / 4.0);
            // 原版蜘蛛是 0.6662 × 2 频率摆腿，且带固定的外撇角
            float f3 = -cos(limbSwing * 0.6662F * 2.0F) * 0.4F * amount;
            for (int i = 0; i < 8; i++) {
                float side = (i % 2 == 0) ? -1.0F : 1.0F;
                legs[i].zRot = -side * f;
                legs[i].yRot = side * f3;
                legs[i].getChild("leg" + i + "_tip").xRot = side * f3 * 1.5F + sin(age * 0.2F) * 0.1F;
            }
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        }
    }

    /** 链式/多部件模型：每根骨骼都有随行走变化的旋转 */
    public static final class ChainModel extends EntityModel<Entity> {
        public final ModelPart root;
        private final int count;
        private final boolean chain;

        public ChainModel(ModelPart root, int count) {
            super(RenderType::entityCutoutNoCull);
            this.root = root;
            this.count = count;
            this.chain = root.hasChild("seg0");
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            float c = cos(limbSwing * 0.6662F);
            ModelPart p = root;
            for (int i = 0; i < count; i++) {
                p = p.getChild((chain ? "seg" : "part") + i);
                p.xRot = c * 0.1F * amount * (1.0F + i * 0.01F) + sin(age * 0.07F + i) * 0.05F;
                p.yRot = c * 0.05F * amount;
            }
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        }
    }
}
