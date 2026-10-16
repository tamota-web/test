package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.world.entity.Entity;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * 通用骨骼烘焙器 —— 与 Minecraft 客户端单例无关，只依赖模型对象本身。
 *
 * 只负责「几何 + 骨架」：
 *   - 通过 {@link ModelRig} 支持不同部件系统（原版 ModelPart / Citadel AdvancedModelBox）；
 *   - 顶点格式 9 个 float：位置、法线、UV、**骨骼索引**；
 *   - 顶点着色器沿父链逐级旋转（+ 可选位移），嵌套部件不会散架。
 *
 * 动画由 {@link CrowdPose} 在 CPU 上驱动模型自己的 {@code setupAnim}，
 * 因此骨骼动作与真实实体完全一致。烘焙阶段只做一件事：
 * **探测哪些骨骼会被动画平移**（Citadel 的 {@code progressPosition} 会写 offsetX/Y/Z），
 * 需要位移的骨骼才额外占一条位移通道。
 */
public final class RigBaker {

    public static final int FLOATS_PER_VERTEX = 9;

    /** 单次绘制可用的骨骼上限（着色器 uniform 数组长度） */
    public static final int MAX_BONES = 64;
    /** 顶点着色器沿父链回溯的层数上限 */
    public static final int MAX_CHAIN = 16;
    /** 每个类型最多支持多少根「会平移」的骨骼 */
    public static final int MAX_TRANS = 16;

    private RigBaker() {}

    /** 几何 + 骨架。 */
    public static final class Rig {
        public final float[] vertices;
        public final int[] indices;
        public final int boneCount;
        /** 每骨骼 vec4：(pivotX, pivotY, pivotZ, parent + 1) */
        public final float[] pivot4;
        /** 骨骼索引 -> 部件（setupAnim 之后从这里读旋转/位移） */
        public final ModelRig.Part[] parts;
        /** 每骨骼：位移通道下标（-1 = 该骨骼不平移） */
        public final int[] transSlot;
        public final int transCount;
        /** 每骨骼：缩放通道下标（-1 = 该骨骼不缩放，恒为 1） */
        public final int[] scaleSlot;
        public final int scaleCount;
        /** 骨骼表里最长的父链长度 */
        public final int maxChain;

        Rig(float[] vertices, int[] indices, int boneCount, float[] pivot4,
            ModelRig.Part[] parts, int[] transSlot, int transCount,
            int[] scaleSlot, int scaleCount, int maxChain) {
            this.vertices = vertices;
            this.indices = indices;
            this.boneCount = boneCount;
            this.pivot4 = pivot4;
            this.parts = parts;
            this.transSlot = transSlot;
            this.transCount = transCount;
            this.scaleSlot = scaleSlot;
            this.scaleCount = scaleCount;
            this.maxChain = maxChain;
        }
    }

    /** 把模型拆成「骨骼 + 绑定姿态网格」。会临时改动模型姿态，退出前完整还原。 */
    public static Rig bake(Object model, Entity puppet) {
        return bake(model, puppet, null, null);
    }

    /**
     * 同上，但会应用该**群集类型**在 GUI 里配的模型覆盖：
     * 整体缩放/偏移（乘加到渲染器自身变换上）与逐部件（组件）的显隐/偏移/缩放。
     *
     * @param model    可以是 {@code EntityModel}（原版/Citadel/Lionfish），也可以是 GeckoLib 的 geo 模型
     * @param unitType 群集单位自身的类型 id（模型可能被覆盖成别的类型，所以覆盖表按它查）
     */
    public static Rig bake(Object model, Entity puppet, net.minecraft.resources.ResourceLocation unitType) {
        return bake(model, puppet, unitType, null);
    }

    /**
     * 同上，额外接受一份 **geo 姿态表**（{@link GeoPoseTable}）。
     *
     * 为什么要它：位移/缩放通道是靠「跑一组代表性动画输入，看哪些骨骼偏离绑定姿态」探出来的，
     * 而 geo 模型没有 {@code setupAnim}（姿态由 GeckoLib 的动画器写），跑不了那一套 ——
     * 于是把「采样表」当姿态来源，用同一套判据探测。<b>不传就保持原行为</b>
     * （geo 只开旋转通道，位移/缩放被丢掉）。
     */
    public static Rig bake(Object model, Entity puppet, net.minecraft.resources.ResourceLocation unitType,
                           Object geoPoseTable) {
        ModelRig modelRig = ModelRig.ofObject(model, puppet);
        if (modelRig == null) {
            HordeMod.LOGGER.warn("[horde] 无法识别该模型使用的部件系统"
                    + "（既不是 ModelPart 派生，也没有可识别的角度/位置成员）");
            return null;
        }
        List<ModelRig.Part> all = modelRig.allParts();
        int n = all.size();
        if (n == 0) {
            return null;
        }

        IdentityHashMap<ModelRig.Part, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(all.get(i), i);
        }
        int[] parent = new int[n];
        int[][] children = new int[n][];
        int[] childCount = new int[n];
        for (int i = 0; i < n; i++) {
            ModelRig.Part par = all.get(i).parent();
            Integer pi = par == null ? null : index.get(par);
            parent[i] = pi == null ? -1 : pi;
            if (parent[i] >= 0) {
                childCount[parent[i]]++;
            }
        }
        for (int i = 0; i < n; i++) {
            children[i] = new int[childCount[i]];
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
        // 先复位到定义姿态，再取枢轴（某些体系的「位置」成员会被动画改写，必须在复位后读）
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

        boolean[] needTrans = new boolean[n];
        boolean[] needScale = new boolean[n];
        try {
            // 探测依赖模型自己的 setupAnim —— 只有 EntityModel 系才有这一步。
            // GeckoLib 的 geo 模型没有 setupAnim（骨骼状态由它自己的动画器写），
            // 所以这里改成用**烘焙期采样表**当姿态来源（同一条判据，见 GeoPoseTable.probe）。
            if (model instanceof EntityModel<?> entityModel) {
                probeChannels(entityModel, puppet, all, needTrans, needScale);
            } else if (geoPoseTable instanceof GeoPoseTable table) {
                GeoPoseTable.probe(table, all.toArray(new ModelRig.Part[0]), needTrans, needScale);
            }
            for (ModelRig.Part p : all) {
                p.resetBind();
            }

            // ---- 骨骼裁剪：前序保留前 MAX_BONES 根 ----
            int[] remap = new int[n];
            for (int i = 0; i < n; i++) {
                remap[i] = i < MAX_BONES ? i : -1;
            }
            for (int i = 0; i < n; i++) {
                if (remap[i] < 0) {
                    int cur = i;
                    while (cur >= 0 && remap[cur] < 0) {
                        cur = parent[cur];
                    }
                    remap[i] = cur < 0 ? 0 : cur;
                }
            }
            int[] slot = new int[n];
            for (int i = 0; i < n; i++) {
                slot[i] = remap[i] == i ? i : -1;
            }
            if (slot[0] < 0) {
                slot[0] = 0;
                remap[0] = 0;
            }
            int boneCount = 0;
            for (int i = 0; i < n; i++) {
                if (slot[i] >= 0) {
                    slot[i] = boneCount++;
                }
            }
            if (boneCount > MAX_BONES) {
                boneCount = MAX_BONES;
            }
            if (n > MAX_BONES) {
                HordeMod.LOGGER.info("[horde] 模型有 {} 根骨骼，超过上限 {}，已按前序裁剪", n, MAX_BONES);
            }
            int[] boneOf = new int[n];
            for (int i = 0; i < n; i++) {
                int s = slot[remap[i]];
                boneOf[i] = s < 0 ? 0 : s;
            }
            ModelRig.Part[] boneParts = new ModelRig.Part[boneCount];
            float[] outPivot = new float[boneCount * 4];
            int[] transSlot = new int[boneCount];
            int[] scaleSlot = new int[boneCount];
            int transCount = 0;
            int scaleCount = 0;
            for (int i = 0; i < n; i++) {
                if (slot[i] < 0 || slot[i] >= boneCount) {
                    continue;
                }
                int s = slot[i];
                int parentBone = parent[i] >= 0 ? boneOf[parent[i]] : -1;
                outPivot[s * 4] = pivot[i * 3];
                outPivot[s * 4 + 1] = pivot[i * 3 + 1];
                outPivot[s * 4 + 2] = pivot[i * 3 + 2];
                outPivot[s * 4 + 3] = parentBone + 1;
                boneParts[s] = all.get(i);
                if (needTrans[i] && transCount < MAX_TRANS) {
                    transSlot[s] = transCount++;
                } else {
                    transSlot[s] = -1;
                }
                // 缩放通道：只有真的会缩放的骨骼才多占一条（原版/Citadel 模型一条都不会占）
                if (needScale[i] && scaleCount < MAX_TRANS) {
                    scaleSlot[s] = scaleCount++;
                } else {
                    scaleSlot[s] = -1;
                }
            }
            for (int s = 0; s < boneCount; s++) {
                if (boneParts[s] == null) {
                    boneParts[s] = all.get(0);
                }
            }
            int maxChain = 0;
            for (int s = 0; s < boneCount; s++) {
                int depth = 0;
                int cur = s;
                while (cur >= 0 && depth <= MAX_CHAIN) {
                    depth++;
                    cur = (int) (outPivot[cur * 4 + 3] + 0.5F) - 1;
                }
                maxChain = Math.max(maxChain, depth);
            }
            if (maxChain > MAX_CHAIN) {
                HordeMod.LOGGER.warn("[horde] 骨骼链最深 {} 层，超过着色器上限 {} 层，更深处的骨骼旋转将被忽略",
                        maxChain, MAX_CHAIN);
            }
            int animated = 0;
            for (boolean b : needTrans) {
                if (b) {
                    animated++;
                }
            }
            if (animated > MAX_TRANS) {
                HordeMod.LOGGER.warn("[horde] 有 {} 根骨骼会做位移动画，超过位移通道上限 {}，多余的位移会被忽略",
                        animated, MAX_TRANS);
            }
            int scaled = 0;
            for (boolean b : needScale) {
                if (b) {
                    scaled++;
                }
            }
            if (scaled > 0) {
                HordeMod.LOGGER.info("[horde] 该模型有 {} 根骨骼会做缩放动画（逐骨骼缩放通道已启用）", scaled);
            }

            // ---- 发射绑定姿态顶点 ----
            Capture cap = new Capture();
            PoseStack stack = new PoseStack();
            dev.swordkingdom.horde.edit.TypeOverride boneOv =
                    unitType == null ? null : dev.swordkingdom.horde.edit.TypeOverride.get(unitType);
            for (int i = 0; i < n; i++) {
                if (parent[i] == -1) {
                    emit(i, stack, all, children, boneOf, cap, boneOv);
                }
            }
            reportEmission(all, cap.vertexCount());

            return new Rig(cap.buildVertices(), cap.buildIndices(), boneCount, outPivot,
                    boneParts, transSlot, transCount, scaleSlot, scaleCount, maxChain);
        } finally {
            for (ModelRig.Part p : all) {
                p.restoreState();
            }
        }
    }

    /**
     * 发射完顶点后做一次自检：如果**一个顶点都没画出来**（最常见的原因是该模组的部件渲染方法
     * 调用失败，而失败以前是被静默吞掉的），把原因连同部件名打到日志里 ——
     * 否则上线表现就是「这个 boss 根本不显示」，日志里却什么都没有。
     */
    private static void reportEmission(List<ModelRig.Part> all, int vertices) {
        if (vertices > 0) {
            return;
        }
        int shown = 0;
        StringBuilder sb = new StringBuilder();
        for (ModelRig.Part p : all) {
            String err = p.lastError();
            if (err == null) {
                continue;
            }
            if (shown++ > 0) {
                sb.append("；");
            }
            sb.append(p.name()).append(" -> ").append(err);
            if (shown >= 3) {
                break;
            }
        }
        if (shown == 0) {
            HordeMod.LOGGER.warn("[horde] 模型烘焙出 0 个顶点：{} 个部件都没能画出几何"
                    + "（可能是所有部件都不可见，或该模型的部件渲染方式不被支持）", all.size());
        } else {
            HordeMod.LOGGER.warn("[horde] 模型烘焙出 0 个顶点，前 {} 个部件报错：{}", shown, sb);
        }
    }

    private static void emit(int i, PoseStack stack, List<ModelRig.Part> all, int[][] children,
                             int[] boneOf, Capture cap, dev.swordkingdom.horde.edit.TypeOverride ov) {
        ModelRig.Part part = all.get(i);
        dev.swordkingdom.horde.edit.TypeOverride.BoneEdit edit = ov == null ? null : ov.bone(part.name());

        // 部件显隐：GUI 里强制显示/隐藏优先于模型自己的判断
        boolean visible = part.visible();
        if (edit != null) {
            if (edit.visible == 0) {
                return;   // 隐藏 -> 整棵子树跳过
            }
            if (edit.visible == 1) {
                visible = true;
            }
        }
        if (!visible) {
            return;
        }

        boolean transformed = edit != null && !edit.isEmpty()
                && (edit.offX != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET
                || edit.offY != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET
                || edit.offZ != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET
                || edit.scaleX >= 0.0F || edit.scaleY >= 0.0F || edit.scaleZ >= 0.0F);
        if (transformed) {
            // 覆盖变换包住「本部件 + 它的子树」，与在模型里直接改这个部件等价
            stack.pushPose();
            float ox = edit.offX == dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET ? 0.0F : edit.offX;
            float oy = edit.offY == dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET ? 0.0F : edit.offY;
            float oz = edit.offZ == dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET ? 0.0F : edit.offZ;
            if (ox != 0.0F || oy != 0.0F || oz != 0.0F) {
                stack.translate(ox, oy, oz);
            }
            float sx = edit.scaleX >= 0.0F ? edit.scaleX : 1.0F;
            float sy = edit.scaleY >= 0.0F ? edit.scaleY : 1.0F;
            float sz = edit.scaleZ >= 0.0F ? edit.scaleZ : 1.0F;
            if (sx != 1.0F || sy != 1.0F || sz != 1.0F) {
                stack.scale(sx, sy, sz);
            }
        }

        cap.bone = boneOf[i];
        if (!part.skipCubes()) {
            part.renderOwn(stack, cap, 0, 0);
        }
        stack.pushPose();
        part.translateAndRotate(stack);
        for (int child : children[i]) {
            emit(child, stack, all, children, boneOf, cap, ov);
        }
        stack.popPose();

        if (transformed) {
            stack.popPose();
        }
    }

    /**
     * 探测哪些骨骼在动画里会被平移、哪些会被缩放。
     *
     * 用一组代表性输入跑 {@code setupAnim}（行走相位 × 挥动进度 × 年龄）：
     * 某根骨骼的 offset 偏离绑定姿态 → 打开位移通道；scale 偏离 1 → 打开缩放通道。
     * 只有真的有这类动画的骨骼才多占 texel，所以原版/Citadel 模型的每骨骼开销仍是 1 个 texel。
     */
    public static void probeChannels(EntityModel model, Entity puppet, List<ModelRig.Part> all,
                                     boolean[] needTrans, boolean[] needScale) {
        int n = all.size();
        float[] bindOff = new float[n * 3];
        for (int i = 0; i < n; i++) {
            ModelRig.Part p = all.get(i);
            bindOff[i * 3] = p.offX();
            bindOff[i * 3 + 1] = p.offY();
            bindOff[i * 3 + 2] = p.offZ();
        }
        float[] phases = {0.0F, 1.5F, 3.0F, 4.5F, 6.0F, 7.5F, 9.0F};
        float[] attacks = {0.0F, 0.35F, 0.7F, 1.0F};
        float[] ages = {0.0F, 17.0F, 41.0F};
        try {
            for (float phase : phases) {
                for (float attack : attacks) {
                    for (float age : ages) {
                        model.attackTime = attack;
                        model.riding = false;
                        model.young = false;
                        model.prepareMobModel(puppet, phase, 1.0F, 0.0F);
                        model.setupAnim(puppet, phase, 1.0F, age, 0.0F, 0.0F);
                        scanChannels(all, bindOff, needTrans, needScale);
                    }
                }
            }
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] 通道探测失败，按「无位移/无缩放」处理: {}", t.toString());
        }
    }

    /** 逐骨骼比对当前姿态与绑定姿态：偏离了就说明该骨骼需要对应的通道。 */
    public static void scanChannels(List<ModelRig.Part> all, float[] bindOff,
                                    boolean[] needTrans, boolean[] needScale) {
        int n = all.size();
        for (int i = 0; i < n; i++) {
            ModelRig.Part p = all.get(i);
            if (!needTrans[i] && p.supportsOffset()
                    && (Math.abs(p.offX() - bindOff[i * 3]) > 1.0E-4F
                    || Math.abs(p.offY() - bindOff[i * 3 + 1]) > 1.0E-4F
                    || Math.abs(p.offZ() - bindOff[i * 3 + 2]) > 1.0E-4F)) {
                needTrans[i] = true;
            }
            if (!needScale[i]
                    && (Math.abs(p.scaleX() - 1.0F) > 1.0E-4F
                    || Math.abs(p.scaleY() - 1.0F) > 1.0E-4F
                    || Math.abs(p.scaleZ() - 1.0F) > 1.0E-4F)) {
                needScale[i] = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // 顶点截获
    // ------------------------------------------------------------------

    private static final class Capture implements VertexConsumer {

        private final FloatBag out = new FloatBag(8192);
        private final IntBag indices = new IntBag(4096);

        int bone;

        /** 已发射的顶点数（用于「一个都没画出来」的自检） */
        int vertexCount() {
            return out.n / FLOATS_PER_VERTEX;
        }

        private float px;
        private float py;
        private float pz;
        private float nx;
        private float ny;
        private float nz;
        private float u;
        private float v;

        private final int[] quad = new int[4];
        private int quadSize;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.px = (float) x;
            this.py = (float) y;
            this.pz = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer uv(float uu, float vv) {
            this.u = uu;
            this.v = vv;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer normal(float xx, float yy, float zz) {
            this.nx = xx;
            this.ny = yy;
            this.nz = zz;
            return this;
        }

        @Override
        public void endVertex() {
            int vi = out.size() / FLOATS_PER_VERTEX;
            out.add(px);
            out.add(py);
            out.add(pz);
            out.add(nx);
            out.add(ny);
            out.add(nz);
            out.add(u);
            out.add(v);
            out.add(bone);

            quad[quadSize++] = vi;
            if (quadSize == 4) {
                indices.add(quad[0]);
                indices.add(quad[1]);
                indices.add(quad[2]);
                indices.add(quad[0]);
                indices.add(quad[2]);
                indices.add(quad[3]);
                quadSize = 0;
            }
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            // no-op
        }

        @Override
        public void unsetDefaultColor() {
            // no-op
        }

        float[] buildVertices() {
            float[] verts = out.toArray();
            float maxUv = 0f;
            for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
                if (verts[i + 6] > maxUv) maxUv = verts[i + 6];
                if (verts[i + 7] > maxUv) maxUv = verts[i + 7];
            }
            if (maxUv > 1.5f) {
                for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
                    verts[i + 6] /= 16.0F;
                    verts[i + 7] /= 16.0F;
                }
            }
            return verts;
        }

        int[] buildIndices() {
            return indices.toArray();
        }
    }

    private static final class FloatBag {
        private float[] a;
        private int n;

        FloatBag(int cap) {
            a = new float[Math.max(16, cap)];
        }

        int size() {
            return n;
        }

        void add(float v) {
            if (n == a.length) {
                float[] b = new float[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = v;
        }

        float[] toArray() {
            float[] b = new float[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }

    private static final class IntBag {
        private int[] a;
        private int n;

        IntBag(int cap) {
            a = new int[Math.max(16, cap)];
        }

        void add(int v) {
            if (n == a.length) {
                int[] b = new int[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = v;
        }

        int[] toArray() {
            int[] b = new int[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }
}
