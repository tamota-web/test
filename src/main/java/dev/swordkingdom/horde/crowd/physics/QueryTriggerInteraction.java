package dev.swordkingdom.horde.crowd.physics;

/**
 * 查询要不要命中触发体 —— 对应 Unity 的 {@code QueryTriggerInteraction}
 * （{@code Physics.Raycast(origin, dir, out hit, dist, layerMask, QueryTriggerInteraction)}）。
 *
 * <p>为什么必须做成显式参数：同一份射线代码，AI 视线希望「别被射程触发器挡住」，
 * 而「这一发箭能不能打到那个爆炸触发器」又希望命中它。把它做成全局开关，
 * 就一定会出现「某处悄悄改了全局值，另一处行为就变了」的幽灵 bug。
 */
public enum QueryTriggerInteraction {

    /** 跟随 {@link PhysicsWorld.Settings#queriesHitTriggers}（Unity 的 {@code Physics.queriesHitTriggers}）。 */
    USE_GLOBAL,
    /** 本次查询忽略触发体。 */
    IGNORE,
    /** 本次查询命中触发体。 */
    COLLIDE;

    /** 按全局设置与本次查询的枚举解出「这次到底要不要命中触发体」。 */
    public boolean hitsTriggers(boolean global) {
        switch (this) {
            case IGNORE:
                return false;
            case COLLIDE:
                return true;
            default:
                return global;
        }
    }
}
