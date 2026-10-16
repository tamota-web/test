package dev.swordkingdom.horde.crowd.physics;

/**
 * 查询命中结果 —— 对应 Unity 的 {@code RaycastHit}（以及 {@code RaycastHit2D}、
 * {@code ColliderCastHit} 那一族）。
 *
 * <p><b>为什么是一个可复用的可变对象，而不是每次 new</b>：AI 视线、投掷物扫掠、
 * 范围伤害每 tick 要发起成千上万次查询。Unity 的 {@code RaycastHit} 是 struct
 * （栈上、无 GC），Java 里最接近的等价物就是「调用方持有一个实例、查询往里写」。
 * 于是所有查询方法的签名都是 {@code boolean raycast(..., RaycastHit out)} ——
 * 返回「有没有命中」，命中细节写在 {@code out} 里。
 *
 * <p>字段与 Unity 的对应：
 * <pre>
 *   Unity                    -> 这里
 *   hit.point                -> pointX/Y/Z
 *   hit.normal               -> normalX/Y/Z
 *   hit.distance             -> distance（格）
 *   hit.collider             -> body（>= 0）或 blockX/Y/Z（体素）
 *   hit.collider.gameObject.layer -> layer
 *   hit.collider.isTrigger   -> isTrigger
 *   （Unity 无此字段）        -> t（相对本次位移的参数，TOI 用）
 * </pre>
 */
public final class RaycastHit {

    /** 命中的 body 槽位；{@link #NO_BODY} 表示命中的是静态几何（体素）。 */
    public static final int NO_BODY = -1;

    public int body = NO_BODY;
    /** 命中的方块坐标（仅 {@link #isVoxel} 为 true 时有意义）。 */
    public int blockX;
    public int blockY;
    public int blockZ;

    public float pointX;
    public float pointY;
    public float pointZ;
    public float normalX;
    public float normalY;
    public float normalZ;

    /** 世界距离（格）。 */
    public float distance;
    /** 参数 t ∈ [0,1]（相对本次查询的位移向量）。CCD 直接用它。 */
    public float t;

    public int layer;
    public boolean isTrigger;
    public boolean isVoxel;
    public int team;

    public void clear() {
        body = NO_BODY;
        blockX = blockY = blockZ = 0;
        pointX = pointY = pointZ = 0.0F;
        normalX = normalY = normalZ = 0.0F;
        distance = 0.0F;
        t = 0.0F;
        layer = -1;
        isTrigger = false;
        isVoxel = false;
        team = 0;
    }

    /** 命中的是不是 body（而不是地形）。 */
    public boolean hitBody() {
        return body != NO_BODY;
    }

    @Override
    public String toString() {
        if (isVoxel) {
            return String.format(java.util.Locale.ROOT,
                    "Hit[voxel (%d,%d,%d) t=%.6f dist=%.6f n=(%.3f,%.3f,%.3f) layer=%s]",
                    blockX, blockY, blockZ, t, distance, normalX, normalY, normalZ,
                    PhysicsLayers.name(layer));
        }
        return String.format(java.util.Locale.ROOT,
                "Hit[body %d t=%.6f dist=%.6f p=(%.4f,%.4f,%.4f) n=(%.3f,%.3f,%.3f) layer=%s team=%d trigger=%s]",
                body, t, distance, pointX, pointY, pointZ, normalX, normalY, normalZ,
                PhysicsLayers.name(layer), team, isTrigger);
    }
}
