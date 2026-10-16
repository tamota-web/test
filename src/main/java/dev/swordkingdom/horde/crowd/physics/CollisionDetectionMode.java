package dev.swordkingdom.horde.crowd.physics;

/**
 * 碰撞检测模式 —— 与 Unity 的 {@code CollisionDetectionMode} 同名同义。
 *
 * <p>Unity 的三种模式在这里的落点：
 * <ul>
 *   <li>{@link #DISCRETE}：每步只在**终点**做一次重叠检测。单位（≤ 0.3 格/tick）够用，也最便宜。</li>
 *   <li>{@link #CONTINUOUS}：对**静态几何**（体素）做扫掠，仍可能与动态物体穿透。</li>
 *   <li>{@link #CONTINUOUS_DYNAMIC}：对静态几何**与**其它开了 CCD 的动态体都做扫掠。</li>
 *   <li>{@link #CONTINUOUS_SPECULATIVE}：Unity 的「推测式接触」，用接触偏移提前生成接触点而不钳制位置；
 *       本项目把它当 {@link #CONTINUOUS} 处理，并把这个差异写进文档（阶段 1 不做推测式接触）。</li>
 * </ul>
 *
 * <p><b>为什么箭必须是 CONTINUOUS_DYNAMIC</b>：3 格/tick = 60 格/秒，
 * 一个子步走过去就跨过整格厚的墙；离散检测只看终点，
 * 起点在墙前、终点在墙后时**整面墙都不存在**（隧穿）。见 {@code Sweep} 的 TOI 解法。
 */
public enum CollisionDetectionMode {

    DISCRETE(0),
    CONTINUOUS(1),
    CONTINUOUS_DYNAMIC(2),
    CONTINUOUS_SPECULATIVE(3);

    private final int id;

    CollisionDetectionMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static CollisionDetectionMode byId(int id) {
        for (CollisionDetectionMode m : values()) {
            if (m.id == id) {
                return m;
            }
        }
        return DISCRETE;
    }

    /** 是否需要做扫掠（CCD）。 */
    public boolean sweeps() {
        return this != DISCRETE;
    }

    /** 是否连**动态体**一起扫（否则只扫地形等静态几何）。 */
    public boolean sweepsDynamic() {
        return this == CONTINUOUS_DYNAMIC || this == CONTINUOUS_SPECULATIVE;
    }
}
