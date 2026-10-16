package dev.swordkingdom.horde.crowd.physics;

/**
 * 刚体自由度约束 —— 与 Unity 的 {@code RigidbodyConstraints} 同名同义。
 *
 * <p>位布局是本项目自己的（位置 3 位 + 旋转 3 位）：
 * <pre>
 *   FreezePositionX=1  FreezePositionY=2  FreezePositionZ=4   FreezePosition=7
 *   FreezeRotationX=8  FreezeRotationY=16 FreezeRotationZ=32  FreezeRotation=56
 *   FreezeAll=63
 * </pre>
 * 语义与 Unity 一一对应；数值不参与任何序列化/存档，所以不追求与 Unity 位值相同
 * （那样只会让「掩码里出现的魔法数字」更难读）。
 *
 * <p><b>为什么单位一律 FreezeRotation</b>：群集单位是「直立胶囊」，原版实体也不翻滚。
 * 关掉 3 个角自由度之后，接触求解只需处理线性冲量（3 个自由度/单位），
 * 求解成本直接砍半，而且不会出现「两个僵尸互推之后原地打转」这种原版没有的行为。
 * 需要翻滚的投掷物（插在地上的箭）后续再放开 —— 那时它已经是静态装饰，不必进求解器。
 */
public final class RigidbodyConstraints {

    private RigidbodyConstraints() {
    }

    public static final int NONE = 0;
    public static final int FREEZE_POSITION_X = 1;
    public static final int FREEZE_POSITION_Y = 2;
    public static final int FREEZE_POSITION_Z = 4;
    public static final int FREEZE_ROTATION_X = 8;
    public static final int FREEZE_ROTATION_Y = 16;
    public static final int FREEZE_ROTATION_Z = 32;

    public static final int FREEZE_POSITION = FREEZE_POSITION_X | FREEZE_POSITION_Y | FREEZE_POSITION_Z;
    public static final int FREEZE_ROTATION = FREEZE_ROTATION_X | FREEZE_ROTATION_Y | FREEZE_ROTATION_Z;
    public static final int FREEZE_ALL = FREEZE_POSITION | FREEZE_ROTATION;

    /** 单位（直立胶囊）的默认约束：只冻结旋转。 */
    public static final int UNIT_DEFAULT = FREEZE_ROTATION;

    public static boolean has(int mask, int flag) {
        return (mask & flag) == flag;
    }

    /** 该轴的位置自由度是否被冻结（axis: 0=X, 1=Y, 2=Z）。 */
    public static boolean positionFrozen(int mask, int axis) {
        return has(mask, FREEZE_POSITION_X << axis);
    }

    /** 该轴的角自由度是否被冻结。 */
    public static boolean rotationFrozen(int mask, int axis) {
        return has(mask, FREEZE_ROTATION_X << axis);
    }

    public static String describe(int mask) {
        if (mask == NONE) {
            return "None";
        }
        if (mask == FREEZE_ALL) {
            return "FreezeAll";
        }
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < 3; a++) {
            if (positionFrozen(mask, a)) {
                sb.append("FreezePos").append("XYZ".charAt(a)).append(' ');
            }
        }
        for (int a = 0; a < 3; a++) {
            if (rotationFrozen(mask, a)) {
                sb.append("FreezeRot").append("XYZ".charAt(a)).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
