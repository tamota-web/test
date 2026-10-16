package dev.swordkingdom.horde.harness;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.client.CrowdPose;
import dev.swordkingdom.horde.client.RigBaker;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 验证**通用**部件系统适配器：不针对任何具体模组，而是「结构化发现 + 名称族分类 + 探针校验」。
 *
 * 线上 mod 的 jar 是 SRG 名重混淆的，dev 环境的 MC 是官方名，**没法在 dev 里直接构造真实 mod 模型**
 * （会 NoSuchMethodError: RenderType.m_110458_）。所以这里用两个**命名风格完全不同**的替身部件系统：
 *
 *  A. Citadel 风格：{@code rotationPointX/Y/Z} + {@code rotateAngleX/Y/Z} + {@code offsetX/Y/Z}
 *     + {@code getParent()} + 私有 {@code doRender(PoseStack.Pose, …)}（只画自己的立方体）
 *     + {@code resetToDefaultPose()}；
 *  B. 基岩/GeckoLib 风格：{@code posX/Y/Z}（位置，**同时被动画当位移用**）+ {@code rotX/Y/Z}
 *     + {@code visible} + 子部件 List 字段 + 公开 {@code render(PoseStack, …)}（会连子部件一起画）
 *     + {@code resetPose()}。
 *
 * 两者都必须被识别，且「旋转 + 位移」的合成结果都要与模型自身渲染逐点一致。
 */
public final class AdapterHarness {

    private static int failures;

    public static void main(String[] args) {
        test("citadel-style", new CitadelStyleModel());
        test("bedrock-style", new BedrockStyleModel());
        System.out.println(failures == 0 ? "\n== ADAPTER PASS ==" : "\n== ADAPTER FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private interface TestModel {
        List<?> all();

        EntityModel<Entity> model();

        int boneOf(Object part);
    }

    private static void test(String tag, TestModel tm) {
        EntityModel<Entity> model = tm.model();
        RigBaker.Rig rig;
        try {
            rig = RigBaker.bake(model, null);
        } catch (Throwable t) {
            failures++;
            System.out.println("[FAIL] " + tag + " 烘焙异常: " + t);
            t.printStackTrace(System.out);
            return;
        }
        if (rig == null) {
            failures++;
            System.out.println("[FAIL] " + tag + " 通用适配器没识别出这个部件系统");
            return;
        }
        System.out.println("[ ok ] " + tag + " 烘焙成功：骨骼 " + rig.boneCount + "（期望 " + tm.all().size()
                + "），最深链 " + rig.maxChain + "，位移通道 " + rig.transCount);
        check(rig.boneCount == tm.all().size(), tag + "：骨骼数应为 " + tm.all().size());
        check(rig.maxChain >= 3, tag + "：应还原出至少 3 层父子链，实际 " + rig.maxChain);
        check(rig.transCount >= 1, tag + "：被动画位移的骨骼应获得位移通道");

        CrowdPose pose = new CrowdPose(model, null, rig.parts);
        float[] angles = new float[rig.boneCount * CrowdPose.STRIDE];
        int verts = rig.vertices.length / RigBaker.FLOATS_PER_VERTEX;
        float[][] cases = {{0.0F, 1.0F}, {1.7F, 1.0F}, {3.3F, 0.6F}, {5.2F, 1.0F}};
        for (float[] c : cases) {
            float limbSwing = c[0];
            float amount = c[1];

            // 基准：模型自己渲染
            model.attackTime = 0.0F;
            model.riding = false;
            model.young = false;
            model.prepareMobModel(null, limbSwing, amount, 0.0F);
            model.setupAnim(null, limbSwing, amount, 0.0F, 0.0F, 0.0F);
            Capture truth = new Capture();
            model.renderToBuffer(new PoseStack(), truth, 0, 0, 1.0F, 1.0F, 1.0F, 1.0F);

            boolean ok = pose.evaluate(limbSwing, amount, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, true, angles);
            check(ok, tag + "：CrowdPose 驱动失败");

            float[] ours = new float[verts * 3];
            for (int i = 0; i < verts; i++) {
                int bone = (int) rig.vertices[i * RigBaker.FLOATS_PER_VERTEX + 8];
                float[] out = chain(rig, angles, bone,
                        rig.vertices[i * RigBaker.FLOATS_PER_VERTEX],
                        rig.vertices[i * RigBaker.FLOATS_PER_VERTEX + 1],
                        rig.vertices[i * RigBaker.FLOATS_PER_VERTEX + 2]);
                ours[i * 3] = out[0];
                ours[i * 3 + 1] = out[1];
                ours[i * 3 + 2] = out[2];
            }
            float[] ref = truth.toArray();
            double dev = multisetDeviation(ours, ref);
            System.out.printf("      姿态 limbSwing=%.1f amount=%.1f  顶点 %d/%d  多重集最大偏差 %.7f%n",
                    limbSwing, amount, ours.length / 3, ref.length / 3, dev);
            check(ours.length == ref.length, tag + "：顶点数应与模型自身渲染一致");
            check(dev < 1.0E-3, tag + "：旋转+位移合成后应与模型自身渲染一致（偏差 " + dev + "）");
        }
    }

    /** 复刻顶点着色器的父链公式（旋转 + 位移）。 */
    private static float[] chain(RigBaker.Rig rig, float[] angles, int bone, float x, float y, float z) {
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

    /** 多重集偏差：按 1e-3 分桶、桶内排序后逐点比较（避免大量重复顶点导致的排序错配）。 */
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

    private static void cubes(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, float size) {
        float s = size;
        float[][] corners = {
                {0, 0, 0}, {s, 0, 0}, {s, s, 0}, {0, s, 0},
                {0, 0, s}, {s, 0, s}, {s, s, s}, {0, s, s},
        };
        int[][] faces = {
                {0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7},
                {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0},
        };
        for (int[] f : faces) {
            for (int idx : f) {
                float[] c = corners[idx];
                buffer.vertex(pose.pose(), c[0], c[1], c[2])
                        .color(255, 255, 255, 255)
                        .uv(0.0F, 0.0F)
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(pose.normal(), 0.0F, 1.0F, 0.0F)
                        .endVertex();
            }
        }
    }

    private static final class Capture implements VertexConsumer {
        private final List<float[]> points = new ArrayList<>();
        private float x;
        private float y;
        private float z;

        Capture() {
        }

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
            points.add(new float[]{x, y, z});
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        float[] toArray() {
            float[] out = new float[points.size() * 3];
            for (int i = 0; i < points.size(); i++) {
                out[i * 3] = points.get(i)[0];
                out[i * 3 + 1] = points.get(i)[1];
                out[i * 3 + 2] = points.get(i)[2];
            }
            return out;
        }
    }

    // ==================================================================
    // A. Citadel 风格：rotationPoint* / rotateAngle* / offset* / doRender(Pose)
    // ==================================================================

    static final class CitadelBox {
        public float rotationPointX;
        public float rotationPointY;
        public float rotationPointZ;
        public float rotateAngleX;
        public float rotateAngleY;
        public float rotateAngleZ;
        public float offsetX;
        public float offsetY;
        public float offsetZ;
        public boolean showModel = true;
        public String boxName;

        private final CitadelBox parent;
        private final float size;
        private final List<CitadelBox> childModels = new ArrayList<>();

        private float defX;
        private float defY;
        private float defZ;

        CitadelBox(CitadelBox parent, String name, float x, float y, float z, float size) {
            this.parent = parent;
            this.boxName = name;
            this.rotationPointX = x;
            this.rotationPointY = y;
            this.rotationPointZ = z;
            this.size = size;
            if (parent != null) {
                parent.childModels.add(this);
            }
            defX = x;
            defY = y;
            defZ = z;
        }

        public CitadelBox getParent() {
            return parent;
        }

        public void resetToDefaultPose() {
            rotationPointX = defX;
            rotationPointY = defY;
            rotationPointZ = defZ;
            rotateAngleX = 0.0F;
            rotateAngleY = 0.0F;
            rotateAngleZ = 0.0F;
            offsetX = 0.0F;
            offsetY = 0.0F;
            offsetZ = 0.0F;
        }

        public void translateAndRotate(PoseStack stack) {
            stack.translate((rotationPointX + offsetX) / 16.0F,
                    (rotationPointY + offsetY) / 16.0F,
                    (rotationPointZ + offsetZ) / 16.0F);
            if (rotateAngleX != 0.0F || rotateAngleY != 0.0F || rotateAngleZ != 0.0F) {
                stack.mulPose(new Quaternionf().rotationZYX(rotateAngleZ, rotateAngleY, rotateAngleX));
            }
        }

        @SuppressWarnings("unused")
        private void doRender(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay,
                              float red, float green, float blue, float alpha) {
            if (showModel) {
                cubes(pose, buffer, light, overlay, size);
            }
        }
    }

    static final class CitadelStyleModel extends EntityModel<Entity> implements TestModel {
        final List<CitadelBox> all = new ArrayList<>();
        final CitadelBox root;

        CitadelStyleModel() {
            super(RenderType::entityCutoutNoCull);
            CitadelBox r = new CitadelBox(null, "root", 0, 0, 0, 4);
            CitadelBox body = new CitadelBox(r, "body", 0, 20, 0, 6);
            CitadelBox neck = new CitadelBox(body, "neck", 0, 6, -4, 3);
            CitadelBox head = new CitadelBox(neck, "head", 0, 4, -4, 4);
            CitadelBox tail = new CitadelBox(body, "tail", 0, 2, 10, 3);
            CitadelBox tailTip = new CitadelBox(tail, "tail_tip", 0, 0, 6, 2);
            CitadelBox legL = new CitadelBox(r, "leg_left", -4, 20, 0, 3);
            CitadelBox legR = new CitadelBox(r, "leg_right", 4, 20, 0, 3);
            all.addAll(List.of(r, body, neck, head, tail, tailTip, legL, legR));
            root = r;
        }

        public Iterable<CitadelBox> getAllParts() {
            return all;
        }

        @Override
        public List<?> all() {
            return all;
        }

        @Override
        public EntityModel<Entity> model() {
            return this;
        }

        @Override
        public int boneOf(Object part) {
            return all.indexOf(part);
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            for (CitadelBox b : all) {
                b.resetToDefaultPose();
            }
            float c = (float) Math.cos(limbSwing * 0.6662F);
            all.get(1).rotateAngleX = c * 0.1F * amount;
            all.get(2).rotateAngleX = c * 0.2F * amount;
            all.get(3).rotateAngleY = headYaw * 0.017453292F;
            all.get(4).offsetY = (float) Math.sin(limbSwing) * 2.0F * amount;
            all.get(5).offsetX = c * 3.0F * amount;
            all.get(6).rotateAngleX = c * 0.8F * amount;
            all.get(7).rotateAngleX = -c * 0.8F * amount;
        }

        @Override
        public void renderToBuffer(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            render(root, stack, buffer, light, overlay, red, green, blue, alpha);
        }

        private void render(CitadelBox box, PoseStack stack, VertexConsumer buffer, int light, int overlay,
                            float red, float green, float blue, float alpha) {
            if (!box.showModel) {
                return;
            }
            stack.pushPose();
            box.translateAndRotate(stack);
            invokeDoRender(box, stack, buffer, light, overlay, red, green, blue, alpha);
            for (CitadelBox child : box.childModels) {
                render(child, stack, buffer, light, overlay, red, green, blue, alpha);
            }
            stack.popPose();
        }

        private void invokeDoRender(CitadelBox box, PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                    float red, float green, float blue, float alpha) {
            try {
                java.lang.reflect.Method m = CitadelBox.class.getDeclaredMethod("doRender",
                        PoseStack.Pose.class, VertexConsumer.class, int.class, int.class,
                        float.class, float.class, float.class, float.class);
                m.setAccessible(true);
                m.invoke(box, stack.last(), buffer, light, overlay, red, green, blue, alpha);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    // ==================================================================
    // B. 基岩/GeckoLib 风格：pos* / rot* / visible / render(PoseStack)
    // ==================================================================

    static final class BedrockBone {
        public float posX;
        public float posY;
        public float posZ;
        public float rotX;
        public float rotY;
        public float rotZ;
        public boolean visible = true;
        public final List<BedrockBone> children = new ArrayList<>();

        private final BedrockBone parent;
        private final float size;
        private final float defX;
        private final float defY;
        private final float defZ;

        BedrockBone(BedrockBone parent, float x, float y, float z, float size) {
            this.parent = parent;
            this.posX = x;
            this.posY = y;
            this.posZ = z;
            this.defX = x;
            this.defY = y;
            this.defZ = z;
            this.size = size;
            if (parent != null) {
                parent.children.add(this);
            }
        }

        public BedrockBone getParent() {
            return parent;
        }

        public void resetPose() {
            posX = defX;
            posY = defY;
            posZ = defZ;
            rotX = 0.0F;
            rotY = 0.0F;
            rotZ = 0.0F;
        }

        public void translateAndRotate(PoseStack stack) {
            stack.translate(posX / 16.0F, posY / 16.0F, posZ / 16.0F);
            if (rotX != 0.0F || rotY != 0.0F || rotZ != 0.0F) {
                stack.mulPose(new Quaternionf().rotationZYX(rotZ, rotY, rotX));
            }
        }

        /** 公开的渲染方法：画自己 + 子部件（子部件容器是 public List 字段，可以被临时摘掉） */
        public void render(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                           float red, float green, float blue, float alpha) {
            if (!visible) {
                return;
            }
            stack.pushPose();
            translateAndRotate(stack);
            cubes(stack.last(), buffer, light, overlay, size);
            for (BedrockBone child : new ArrayList<>(children)) {
                child.render(stack, buffer, light, overlay, red, green, blue, alpha);
            }
            stack.popPose();
        }
    }

    static final class BedrockStyleModel extends EntityModel<Entity> implements TestModel {
        final List<BedrockBone> all = new ArrayList<>();
        final BedrockBone root;

        BedrockStyleModel() {
            super(RenderType::entityCutoutNoCull);
            BedrockBone r = new BedrockBone(null, 0, 0, 0, 4);
            BedrockBone body = new BedrockBone(r, 0, 20, 0, 6);
            BedrockBone neck = new BedrockBone(body, 0, 6, -4, 3);
            BedrockBone head = new BedrockBone(neck, 0, 4, -4, 4);
            BedrockBone tail = new BedrockBone(body, 0, 2, 10, 3);
            BedrockBone tailTip = new BedrockBone(tail, 0, 0, 6, 2);
            BedrockBone legL = new BedrockBone(r, -4, 20, 0, 3);
            BedrockBone legR = new BedrockBone(r, 4, 20, 0, 3);
            all.addAll(List.of(r, body, neck, head, tail, tailTip, legL, legR));
            root = r;
        }

        @Override
        public List<?> all() {
            return all;
        }

        @Override
        public EntityModel<Entity> model() {
            return this;
        }

        @Override
        public int boneOf(Object part) {
            return all.indexOf(part);
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            for (BedrockBone b : all) {
                b.resetPose();
            }
            float c = (float) Math.cos(limbSwing * 0.6662F);
            all.get(1).rotX = c * 0.1F * amount;
            all.get(2).rotX = c * 0.2F * amount;
            all.get(3).rotY = headYaw * 0.017453292F;
            // 位移动画直接写位置成员（基岩系习惯）
            all.get(4).posY += (float) Math.sin(limbSwing) * 2.0F * amount;
            all.get(5).posX += c * 3.0F * amount;
            all.get(6).rotX = c * 0.8F * amount;
            all.get(7).rotX = -c * 0.8F * amount;
        }

        @Override
        public void renderToBuffer(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            root.render(stack, buffer, light, overlay, red, green, blue, alpha);
        }
    }
}
