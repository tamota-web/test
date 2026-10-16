package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;

import java.util.List;

/**
 * 模型部件系统的抽象层。
 *
 * 群集渲染需要「骨架」：部件树、每根骨骼的绑定姿态枢轴、以及每帧能读出的旋转/位移。
 * 但不同 mod 用的部件系统不一样：
 *   - 原版 {@code net.minecraft.client.model.geom.ModelPart}（原版生物 + 多数模组）；
 *   - Citadel 的 {@code AdvancedModelBox}（Alex's Caves、Mowzie's Mobs 等）。
 * 所以这里抽出一层接口，{@link RigBaker} / {@link CrowdPose} 只面向接口工作。
 */
public interface ModelRig {

    /** 一根骨骼（一个模型部件）。 */
    interface Part {
        String name();

        Part parent();

        List<Part> children();

        /** 绑定姿态枢轴，单位 1/16 格，相对父部件 */
        float pivotX();

        float pivotY();

        float pivotZ();

        float rotX();

        float rotY();

        float rotZ();

        /** 动画产生的额外位移（原版恒为 0；Citadel 的 offsetX/Y/Z） */
        float offX();

        float offY();

        float offZ();

        boolean visible();

        /** 原版 {@code skipDraw}：跳过自己的立方体，但子部件照常渲染 */
        default boolean skipCubes() {
            return false;
        }

        /** 该部件系统是否支持位移动画 */
        boolean supportsOffset();

        /** 等价于原版 ModelPart.translateAndRotate（平移 + 旋转） */
        void translateAndRotate(PoseStack stack);

        /**
         * 只画自己的立方体。
         *
         * @param parentStack 只含**祖先链**变换的栈（还没压入本部件）——适配器自己负责应用本部件的变换
         */
        void renderOwn(PoseStack parentStack, VertexConsumer buffer, int light, int overlay);

        /** 上一次渲染失败的原因（成功或从未失败返回 null）—— 用来把「烘焙出 0 个顶点」讲清楚 */
        default String lastError() {
            return null;
        }

        // ---- 逐骨骼缩放 ----
        // GeckoLib 的 GeoBone 会做缩放（geo.json 里每个骨骼可带 scale），原版 ModelPart / Citadel
        // AdvancedModelBox 没有这个成员，恒为 1。
        // 施加顺序与原版 ModelPart.translateAndRotate 一致：**先缩放、再旋转、最后平移到枢轴**，
        // 所以顶点着色器里的公式是 p = R · (S · (v - pivot)) + pivot + off。
        default float scaleX() {
            return 1.0F;
        }

        default float scaleY() {
            return 1.0F;
        }

        default float scaleZ() {
            return 1.0F;
        }

        /** 回到模型定义姿态（原版 resetPose / Citadel resetToDefaultPose） */
        void resetBind();

        void saveState();

        void restoreState();
    }

    /** 所有部件，父部件一定排在自己的子部件之前。 */
    List<Part> allParts();

    /**
     * 按模型实际使用的部件系统创建。
     *
     * 先用**零反射**的原版路径（只用公开 API，最稳），失败后走
     * {@link ReflectiveRig} —— 它不针对任何模组：结构化发现部件、按名称族分类成员、
     * 探针校验选择正确的「角度族」成员，因此 Citadel（Alex's Caves 等）以及各种自定义
     * 部件体系都能直接用。
     */
    static ModelRig of(EntityModel<?> model, net.minecraft.world.entity.Entity puppet) {
        return ofObject(model, puppet);
    }

    /**
     * 同上，但接受**任意模型对象** —— GeckoLib 的 {@code GeoModel} 不是 {@code EntityModel}，
     * 走不了原版/反射那条路，由 {@link GeoRig} 按形状识别。
     */
    static ModelRig ofObject(Object model, net.minecraft.world.entity.Entity puppet) {
        if (model instanceof EntityModel<?> entityModel) {
            ModelRig vanilla = VanillaRig.tryCreate(entityModel);
            if (vanilla != null) {
                return vanilla;
            }
            ModelRig reflective = ReflectiveRig.tryCreate(entityModel, puppet);
            if (reflective != null) {
                return reflective;
            }
        }
        return GeoRig.tryCreate(model, puppet);
    }
}
