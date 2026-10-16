package dev.swordkingdom.horde.crowd.physics;

/**
 * 刚体插值模式 —— 与 Unity 的 {@code RigidbodyInterpolation} 同名同义。
 *
 * <p>本项目的**渲染插值已经在客户端做了**（{@code ClientCrowdData.alpha()} 在两次 10Hz 快照之间线性插值），
 * 所以服务端这边真正需要的不是「每帧插值位姿」，而是
 * 「把固定步长的权威状态**原样**留给客户端 → 客户端只需要 alpha 与两个快照」。
 * 因此：
 * <ul>
 *   <li>{@link #NONE}：不保留上一步位置（省 12 字节/单位）。用于纯静态/触发体。</li>
 *   <li>{@link #INTERPOLATE}：保留上一步位置，供 {@link PhysicsWorld#interpolatedX} 之类的
 *       「服务端自己也要按 alpha 取值」的场合（例如真实实体傀儡的位置跟随）。</li>
 *   <li>{@link #EXTRAPOLATE}：Unity 会按速度外推一帧。这里**刻意不实现**：
 *       外推会把「越界/穿墙」的位置当真值发给客户端，而权威服务端不允许发出没解算过的位置。
 *       保留枚举值是为了让「Unity 有这个选项、我们为什么不用」这件事在代码里可见。</li>
 * </ul>
 */
public enum RigidbodyInterpolation {

    NONE,
    INTERPOLATE,
    EXTRAPOLATE;

    /** 是否需要保存上一步位置。 */
    public boolean keepsPrevious() {
        return this != NONE;
    }
}
