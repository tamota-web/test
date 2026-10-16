package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 原版 {@code ModelPart} 部件系统的适配器。
 *
 * 完全不反射 MC 的私有成员：
 *   - 部件枚举：按**类型**找出模型类上的所有 {@code ModelPart} 字段（不看字段名，避免 SRG 名问题）；
 *   - 层级：用公开的 {@code ModelPart.visit} 回调路径（"/head/hat"）+ 公开的 {@code getChild} 还原；
 *   - 自己的立方体：{@code visit} 回调里路径为空的那批就是这个部件自己的。
 */
final class VanillaRig implements ModelRig {

    private final List<Part> parts;

    private VanillaRig(List<Part> parts) {
        this.parts = parts;
    }

    @Override
    public List<Part> allParts() {
        return parts;
    }

    static ModelRig tryCreate(EntityModel<?> model) {
        List<ModelPart> fields = fieldParts(model);
        if (fields.isEmpty()) {
            return null;
        }

        IdentityHashMap<ModelPart, VanillaPart> wrappers = new IdentityHashMap<>();
        for (ModelPart f : fields) {
            wrappers.put(f, new VanillaPart(f));
        }
        PoseStack stack = new PoseStack();
        for (ModelPart root : fields) {
            try {
                root.visit(stack, (pose, path, index, cube) -> {
                    if (path == null || path.isEmpty()) {
                        return;
                    }
                    ModelPart cur = root;
                    for (String seg : path.split("/")) {
                        if (seg.isEmpty()) {
                            continue;
                        }
                        ModelPart child;
                        try {
                            child = cur.getChild(seg);
                        } catch (Throwable t) {
                            return;   // 名字对不上就放弃这条路径
                        }
                        VanillaPart childWrapper = wrappers.get(child);
                        if (childWrapper == null) {
                            childWrapper = new VanillaPart(child);
                            wrappers.put(child, childWrapper);
                        }
                        VanillaPart parentWrapper = wrappers.get(cur);
                        if (parentWrapper != null && childWrapper.parent == null) {
                            childWrapper.parent = parentWrapper;
                            parentWrapper.kids.add(childWrapper);
                        }
                        cur = child;
                    }
                });
            } catch (Throwable ignored) {
                // 单个根部件遍历失败不影响其它
            }
        }

        List<Part> ordered = new ArrayList<>();
        for (ModelPart f : fields) {
            VanillaPart w = wrappers.get(f);
            if (w.parent == null) {
                addTree(w, ordered);
            }
        }
        for (VanillaPart w : wrappers.values()) {
            if (!w.visited) {
                w.parent = null;
                addTree(w, ordered);
            }
        }
        if (ordered.isEmpty()) {
            return null;
        }
        return new VanillaRig(ordered);
    }

    private static void addTree(VanillaPart part, List<Part> out) {
        if (part.visited) {
            return;
        }
        part.visited = true;
        out.add(part);
        for (VanillaPart child : part.kids) {
            addTree(child, out);
        }
    }

    private static List<ModelPart> fieldParts(EntityModel<?> model) {
        List<ModelPart> out = new ArrayList<>();
        IdentityHashMap<ModelPart, Boolean> seen = new IdentityHashMap<>();
        Class<?> c = model.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(model);
                    if (v instanceof ModelPart mp && seen.put(mp, Boolean.TRUE) == null) {
                        out.add(mp);
                    }
                } catch (Throwable ignored) {
                    // 忽略不可访问的字段
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static final class VanillaPart implements Part {
        private final ModelPart part;
        private final List<VanillaPart> kids = new ArrayList<>();
        private VanillaPart parent;
        private boolean visited;

        private float sx;
        private float sy;
        private float sz;
        private float sxr;
        private float syr;
        private float szr;
        private float sxs;
        private float sys;
        private float szs;
        private boolean svisible;

        VanillaPart(ModelPart part) {
            this.part = part;
        }

        @Override
        public String name() {
            return part.getClass().getSimpleName();
        }

        @Override
        public Part parent() {
            return parent;
        }

        @Override
        public List<Part> children() {
            return new ArrayList<>(kids);
        }

        @Override
        public float pivotX() {
            return part.x;
        }

        @Override
        public float pivotY() {
            return part.y;
        }

        @Override
        public float pivotZ() {
            return part.z;
        }

        @Override
        public float rotX() {
            return part.xRot;
        }

        @Override
        public float rotY() {
            return part.yRot;
        }

        @Override
        public float rotZ() {
            return part.zRot;
        }

        @Override
        public float offX() {
            return 0.0F;
        }

        @Override
        public float offY() {
            return 0.0F;
        }

        @Override
        public float offZ() {
            return 0.0F;
        }

        @Override
        public boolean visible() {
            return part.visible;
        }

        @Override
        public boolean skipCubes() {
            return part.skipDraw;
        }

        @Override
        public boolean supportsOffset() {
            return false;
        }

        @Override
        public void translateAndRotate(PoseStack stack) {
            part.translateAndRotate(stack);
        }

        @Override
        public void renderOwn(PoseStack parentStack, VertexConsumer buffer, int light, int overlay) {
            // visit 自己会做 push + translateAndRotate，并回调自己立方体（path 为空）与子孙（path 非空）
            try {
                part.visit(parentStack, (pose, path, index, cube) -> {
                    if (path == null || path.isEmpty()) {
                        cube.compile(pose, buffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                    }
                });
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @Override
        public void resetBind() {
            // 定义位置（PartPose 的 x/y/z）保留，旋转/缩放归零：
            // 这样顶点是「纯平移」的绑定姿态，采样得到的绝对欧拉角由着色器一次性施加，不会重复叠加。
            part.resetPose();
            part.xRot = 0.0F;
            part.yRot = 0.0F;
            part.zRot = 0.0F;
        }

        @Override
        public void saveState() {
            sx = part.x;
            sy = part.y;
            sz = part.z;
            sxr = part.xRot;
            syr = part.yRot;
            szr = part.zRot;
            sxs = part.xScale;
            sys = part.yScale;
            szs = part.zScale;
            svisible = part.visible;
        }

        @Override
        public void restoreState() {
            part.x = sx;
            part.y = sy;
            part.z = sz;
            part.xRot = sxr;
            part.yRot = syr;
            part.zRot = szr;
            part.xScale = sxs;
            part.yScale = sys;
            part.zScale = szs;
            part.visible = svisible;
        }
    }
}
