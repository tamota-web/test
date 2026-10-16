package dev.swordkingdom.horde.crowd.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * {@link VoxelSource} 的生产实现：把真实的方块世界（{@code ServerLevel} 实现了 {@link BlockGetter}）
 * 接成物理层认识的体素源。
 *
 * <p><b>本包唯一 import 原版类的文件</b>。这样切开的好处是：除了这一层薄适配，
 * 整个物理层（TOI、求解器、broadphase、休眠）都是纯 Java + 纯 float，
 * 离线 harness 不引导注册表就能把它跑到逐位对照。
 *
 * <h2>成本与使用边界（诚实说明）</h2>
 * 每次 {@link #boxes} 都要走一次 {@code getBlockState} + {@code getCollisionShape}，
 * 非整格形状还要 {@code toAabbs()}（**会分配**）。所以：
 * <ul>
 *   <li><b>适合</b>：投掷物扫掠（每 tick 几十次）、AI 视线采样（每条射线几十次）这类低频路径；</li>
 *   <li><b>不适合</b>：上万单位每 tick 的逐格碰撞解算 —— 那条路径必须继续吃
 *       {@code BlockWorld} 里那个「每 tick 清空、命中率极高」的方块缓存（见接线文档阶段 3）。
 *       真要挂上去，做法是实现同一个 {@link VoxelSource} 接口并在内部复用那份缓存，
 *       而不是在这里再写一份。</li>
 * </ul>
 */
public final class LevelVoxelSource implements VoxelSource {

    private final BlockGetter level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    // ---- 插桩：真实方块状态读取次数（卡顿归因 / harness 断言） ----
    private long blockReads;

    public LevelVoxelSource(BlockGetter level) {
        this.level = level;
    }

    /** 累计读取了多少次方块状态（不含缓存，因为这里**没有**缓存）。 */
    public long blockReads() {
        return blockReads;
    }

    public void resetCounters() {
        blockReads = 0L;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        pos.set(x, y, z);
        blockReads++;
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public int boxes(int x, int y, int z, float[] out, int offset) {
        pos.set(x, y, z);
        blockReads++;
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0;
        }
        // 整格：绝大多数地形都是这个分支，走它就不用 toAabbs() 分配
        if (shape == Shapes.block()) {
            out[offset] = 0.0F;
            out[offset + 1] = 0.0F;
            out[offset + 2] = 0.0F;
            out[offset + 3] = 1.0F;
            out[offset + 4] = 1.0F;
            out[offset + 5] = 1.0F;
            return 1;
        }
        List<AABB> list = shape.toAabbs();
        int n = Math.min(list.size(), maxBoxes());
        for (int i = 0; i < n; i++) {
            AABB b = list.get(i);
            int p = offset + i * 6;
            out[p] = (float) b.minX;
            out[p + 1] = (float) b.minY;
            out[p + 2] = (float) b.minZ;
            out[p + 3] = (float) b.maxX;
            out[p + 4] = (float) b.maxY;
            out[p + 5] = (float) b.maxZ;
        }
        return n;
    }

    @Override
    public int maxBoxes() {
        return 8;
    }
}
