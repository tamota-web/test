package dev.swordkingdom.horde.crowd.physics;

/**
 * 体素（方块）世界的**只读**碰撞查询接口。
 *
 * <h2>为什么另立一个接口，而不是直接用 {@code BlockWorld}</h2>
 * 物理层要做到「与现有代码零耦合」才能在离线 harness 里被独立验证。
 * {@code BlockWorld} 的方块缓存是**内部私有**的（{@code lookup} 是 private），
 * 它对外只暴露「按原版轴序移动一个盒子」，没有「这个格子是不是实心」这种查询原语。
 * 于是这里定义只有两个方法的最小接口：
 * <ul>
 *   <li>{@link #isSolid} —— 覆盖判定/重叠查询用；</li>
 *   <li>{@link #boxes} —— 扫掠 TOI 用（把该格子的碰撞盒写进调用方的数组，**免分配**）。</li>
 * </ul>
 * 生产实现由 {@link LevelVoxelSource}（唯一一个 import 原版类的文件）提供，
 * 离线 harness 注入「哪儿有墙」的替身即可完整验证扫掠数学。
 *
 * <h2>坐标系约定</h2>
 * {@code boxes} 写出的盒子是**相对方块原点**的局部坐标（{@code 0..1} 之内）。
 * 这样实现方可以只算一次形状、复用缓存，避免每次查询都做世界坐标加法。
 */
public interface VoxelSource {

    /** 该方块坐标有没有碰撞形状（草地/空气/水都是 false）。 */
    boolean isSolid(int x, int y, int z);

    /**
     * 把该方块的碰撞盒写进 {@code out[offset..offset+6n)}（每盒 6 个 float：
     * minX,minY,minZ,maxX,maxY,maxZ，相对方块原点），返回盒子数量（0 = 无碰撞）。
     *
     * <p>默认实现把「实心」当成整格 1×1×1 的盒子 —— 离线替身只需要回答
     * {@link #isSolid}，不用碰任何需要注册表的方块状态。
     */
    default int boxes(int x, int y, int z, float[] out, int offset) {
        if (!isSolid(x, y, z)) {
            return 0;
        }
        out[offset] = 0.0F;
        out[offset + 1] = 0.0F;
        out[offset + 2] = 0.0F;
        out[offset + 3] = 1.0F;
        out[offset + 4] = 1.0F;
        out[offset + 5] = 1.0F;
        return 1;
    }

    /** 单格最多几个碰撞盒（阶梯/栅栏这类形状可能有好几个）。查询方按它准备缓冲。 */
    default int maxBoxes() {
        return 8;
    }

    /** 全空世界（没有地形）：纯数学验证与客户端预览用。 */
    VoxelSource EMPTY = (x, y, z) -> false;
}
