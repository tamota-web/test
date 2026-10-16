package dev.swordkingdom.horde.harness;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.action.ActionDriver;
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
 * 验证**通用动作/技能动画驱动**（{@link ActionDriver}）。
 *
 * 灾变（L_Ender's Cataclysm）的 Ignis / Ender Guardian、以及 Citadel / Lionfish 系的 boss，
 * 动作动画都不是写死在 {@code setupAnim} 里的，而是由实体上的
 * 「动画状态 + 动画计时 + 动画清单」选择：
 * <pre>
 *   Animation getAnimation();         void setAnimation(Animation);
 *   int       getAnimationTick();     void setAnimationTick(int);
 *   Animation[] getAnimations();
 * </pre>
 *
 * 这里用**同样形状**的替身（连命名习惯都照搬：静态常量 SWING_ATTACK/SMASH… 放在实体类上、
 * 清单来自 {@code getAnimations()}）来验证：
 *
 *  1. {@code ActionDriver.of(...)} 能按形状发现这套 API 并列出全部动作；
 *  2. {@code apply(...)} 写进去的状态/计时能被读回来（0 = 无动作）；
 *  3. **{@link CrowdPose} 确实会驱动它** —— 同一个 (动作, 计时)，群集姿态与
 *     「真实实体处于该状态」时模型自己渲染出来的姿态逐点一致。
 */
public final class ActionHarness {

    private static int failures;

    public static void main(String[] args) {
        // ---- 1) 发现能力 ----
        ActionDriver driver = ActionDriver.of(AnimatedStub.class);
        if (driver == null || !driver.usable()) {
            failures++;
            System.out.println("[FAIL] 没能发现「动画状态 + 计时 + 清单」这套 API");
            System.out.println("== ACTION FAILURES: " + failures + " ==");
            System.exit(1);
        }
        System.out.println("[ ok ] 发现动作 API：可用动作 " + driver.actionCount() + " 个");
        System.out.println("      " + driver.describe());
        check(driver.actionCount() == AnimatedStub.ANIMATION_COUNT,
                "动作数应为 " + AnimatedStub.ANIMATION_COUNT + "，实际 " + driver.actionCount());

        // 名字里带 NO_ANIMATION 的常量不应算作可选动作
        check(driver.actionCount() == 4, "NO_ANIMATION 这类常量不应计入动作清单");

        // ---- 2) 写入/读回 ----
        AnimatedStub stub = new AnimatedStub();
        driver.apply(stub, 2, 7);
        System.out.println("      诊断：直接读 getAnimation=" + stub.getAnimation()
                + " animationTick=" + stub.getAnimationTick()
                + " | driver.read=" + java.util.Arrays.toString(driver.read(stub)));
        for (int a = 0; a <= driver.actionCount(); a++) {
            for (int tick : new int[]{0, 7, 21}) {
                driver.apply(stub, a, tick);
                int[] read = driver.read(stub);
                check(read[0] == a, "动作 " + a + " 写入后应读到同样编号（实际 " + read[0] + "）");
                check(read[1] == (a == 0 ? 0 : tick),
                        "动作 " + a + " 的计时应为 " + (a == 0 ? 0 : tick) + "（实际 " + read[1] + "）");
            }
        }
        driver.apply(stub, 0, 0);

        // ---- 3) CrowdPose 是否真的驱动它 ----
        AnimatedModel model = new AnimatedModel();
        RigBaker.Rig rig = RigBaker.bake(model, null);
        if (rig == null) {
            failures++;
            System.out.println("[FAIL] 替身模型烘焙失败");
            System.out.println("== ACTION FAILURES: " + failures + " ==");
            System.exit(1);
        }
        AnimatedStub entity = new AnimatedStub();
        CURRENT = entity;
        CrowdPose pose = new CrowdPose(model, null, rig.parts, driver, entity);
        check(pose.actionCount() == driver.actionCount(), "CrowdPose 应暴露同样的动作数");

        float[] angles = new float[rig.boneCount * CrowdPose.STRIDE];
        int verts = rig.vertices.length / RigBaker.FLOATS_PER_VERTEX;
        for (int action = 0; action <= driver.actionCount(); action++) {
            for (int tick : new int[]{0, 9, 19}) {
                // 基准：让实体真的处于这个状态，然后模型自己渲染
                entity.animation = action == 0 ? AnimatedStub.NO_ANIMATION
                        : AnimatedStub.ANIMATIONS[action - 1];
                entity.animationTick = tick;
                model.renderToBuffer(new PoseStack(), new Capture(), 0, 0, 1.0F, 1.0F, 1.0F, 1.0F);

                // 待验证：群集姿态（CrowdPose 会通过 ActionDriver 把状态写到替身实体上）
                boolean ok = pose.evaluate(0.0F, 1.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, true,
                        action, tick, angles);
                check(ok, "动作 " + action + "/tick " + tick + "：CrowdPose 驱动失败");

                int[] read = driver.read(entity);
                check(read[0] == action && read[1] == (action == 0 ? 0 : tick),
                        "动作 " + action + "/tick " + tick + "：CrowdPose 应把状态写到实体上（读回 "
                                + read[0] + "/" + read[1] + "）");
            }
        }

        // 姿态一致性：用与真实实体相同的状态渲染出来的顶点，必须与群集姿态一致
        for (int action = 1; action <= driver.actionCount(); action++) {
            int tick = 11;
            entity.animation = AnimatedStub.ANIMATIONS[action - 1];
            entity.animationTick = tick;
            CURRENT = entity;
            // 基准：模型自己按实体当前状态摆好姿势再渲染
            // （renderToBuffer 只负责画，姿势必须先由 setupAnim 摆好 —— 真实渲染管线就是这样）
            model.attackTime = 0.0F;
            model.riding = false;
            model.young = false;
            model.prepareMobModel(null, 0.0F, 1.0F, 0.0F);
            model.setupAnim(null, 0.0F, 1.0F, 12.0F, 0.0F, 0.0F);
            Capture truth = new Capture();
            model.renderToBuffer(new PoseStack(), truth, 0, 0, 1.0F, 1.0F, 1.0F, 1.0F);

            pose.evaluate(0.0F, 1.0F, 12.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0.0F, true,
                    action, tick, angles);
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
            double dev = multisetDeviation(ours, truth.toArray());
            System.out.printf("      动作 %d / tick %d：顶点 %d/%d  多重集最大偏差 %.7f%n",
                    action, tick, ours.length / 3, truth.toArray().length / 3, dev);
            check(dev < 1.0E-3, "动作 " + action + " 的姿态应与真实实体一致（偏差 " + dev + "）");
        }

        System.out.println(failures == 0 ? "\n== ACTION PASS ==" : "\n== ACTION FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

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
            String key = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                    flat[i], flat[i + 1], flat[i + 2]);
            out.computeIfAbsent(key, k -> new java.util.ArrayList<>())
                    .add(new double[]{flat[i], flat[i + 1], flat[i + 2]});
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

    // ==================================================================
    // 替身：形状与灾变/Lionfish 的 IAnimatedEntity 一致
    // ==================================================================

    /** 动作类型（对应 lionfishapi 的 Animation） */
    static final class Animation {
        final String name;

        Animation(String name) {
            this.name = name;
        }
    }

    /** 实体侧：NO_ANIMATION 常量 + 各种动作常量 + 获取/设置 + 清单 */
    static class AnimatedStub {
        static final Animation NO_ANIMATION = new Animation("no_animation");
        static final Animation SWING_ATTACK = new Animation("swing_attack");
        static final Animation SMASH_ATTACK = new Animation("smash_attack");
        static final Animation MAGIC_ATTACK = new Animation("magic_attack");
        static final Animation PHASE_2 = new Animation("phase_2");
        static final Animation[] ANIMATIONS = {SWING_ATTACK, SMASH_ATTACK, MAGIC_ATTACK, PHASE_2};
        static final int ANIMATION_COUNT = ANIMATIONS.length;

        Animation animation = NO_ANIMATION;
        int animationTick;

        public Animation getAnimation() {
            return animation;
        }

        public void setAnimation(Animation animation) {
            this.animation = animation;
        }

        public int getAnimationTick() {
            return animationTick;
        }

        public void setAnimationTick(int tick) {
            this.animationTick = tick;
        }

        public Animation[] getAnimations() {
            return ANIMATIONS;
        }
    }

    /** 模型：动作与计时决定姿态，且会写 offset（位移通道） */
    static final class AnimatedModel extends EntityModel<Entity> {
        final List<Bone> all = new ArrayList<>();
        final Bone root;

        AnimatedModel() {
            super(RenderType::entityCutoutNoCull);
            Bone r = new Bone(null, 0, 0, 0, 4);
            Bone body = new Bone(r, 0, 20, 0, 6);
            Bone arm = new Bone(body, -5, 4, 0, 3);
            Bone head = new Bone(body, 0, 8, 0, 4);
            all.addAll(List.of(r, body, arm, head));
            root = r;
        }

        public Iterable<Bone> getAllParts() {
            return all;
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float amount, float age,
                              float headYaw, float headPitch) {
            for (Bone b : all) {
                b.resetToDefaultPose();
            }
            AnimatedStub stub = CURRENT;
            Animation a = stub == null ? AnimatedStub.NO_ANIMATION : stub.animation;
            int t = stub == null ? 0 : stub.animationTick;
            float phase = (float) Math.sin(t * 0.35F);
            if (a == AnimatedStub.SWING_ATTACK) {
                all.get(2).rotateAngleX = -1.2F + phase * 0.25F;
                all.get(1).rotateAngleY = phase * 0.4F;
            } else if (a == AnimatedStub.SMASH_ATTACK) {
                all.get(2).rotateAngleX = 1.4F * (1.0F - Math.min(1.0F, t / 20.0F));
                all.get(1).rotateAngleX = 0.5F;
                all.get(2).offsetY = phase * 4.0F;
            } else if (a == AnimatedStub.MAGIC_ATTACK) {
                all.get(3).rotateAngleX = -0.6F + phase * 0.3F;
                all.get(2).rotateAngleZ = 1.1F;
            } else if (a == AnimatedStub.PHASE_2) {
                all.get(1).rotateAngleX = -0.3F;
                all.get(1).offsetY = 6.0F;
            }
            all.get(0).rotateAngleY = (float) Math.cos(limbSwing * 0.4F) * 0.1F * amount;
        }

        @Override
        public void renderToBuffer(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            render(root, stack, buffer, light, overlay, red, green, blue, alpha);
        }

        private void render(Bone box, PoseStack stack, VertexConsumer buffer, int light, int overlay,
                            float red, float green, float blue, float alpha) {
            if (!box.showModel) {
                return;
            }
            stack.pushPose();
            box.translateAndRotate(stack);
            invokeDoRender(box, stack, buffer, light, overlay, red, green, blue, alpha);
            for (Bone child : box.childModels) {
                render(child, stack, buffer, light, overlay, red, green, blue, alpha);
            }
            stack.popPose();
        }

        private void invokeDoRender(Bone box, PoseStack stack, VertexConsumer buffer, int light, int overlay,
                                    float red, float green, float blue, float alpha) {
            try {
                java.lang.reflect.Method m = Bone.class.getDeclaredMethod("doRender",
                        PoseStack.Pose.class, VertexConsumer.class, int.class, int.class,
                        float.class, float.class, float.class, float.class);
                m.setAccessible(true);
                m.invoke(box, stack.last(), buffer, light, overlay, red, green, blue, alpha);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    /** 基准渲染时用：替身模型的 setupAnim 从这里读「实体状态」 */
    static AnimatedStub CURRENT;

    static final class Bone {
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

        private final Bone parent;
        private final float size;
        private final List<Bone> childModels = new ArrayList<>();
        private final float defX;
        private final float defY;
        private final float defZ;

        Bone(Bone parent, float x, float y, float z, float size) {
            this.parent = parent;
            this.rotationPointX = x;
            this.rotationPointY = y;
            this.rotationPointZ = z;
            this.size = size;
            this.defX = x;
            this.defY = y;
            this.defZ = z;
            if (parent != null) {
                parent.childModels.add(this);
            }
        }

        public Bone getParent() {
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
            if (!showModel) {
                return;
            }
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
    }

    private static final class Capture implements VertexConsumer {
        private final List<float[]> points = new ArrayList<>();
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
}
