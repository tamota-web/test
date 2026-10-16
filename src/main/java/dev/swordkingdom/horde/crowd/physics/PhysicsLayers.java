package dev.swordkingdom.horde.crowd.physics;

/**
 * 层（Layer）与层掩码（LayerMask）—— 对应 Unity 的 <b>Layer</b> + <b>LayerMask</b>。
 *
 * <h2>为什么需要它（而不是继续各写一套 if）</h2>
 * 现有代码里「谁能打谁 / 谁能撞谁」散落在 {@code ProjectileCombat}、{@code CrowdManager.blastAt}、
 * {@code hasLineOfSight} 等各处，每处都自己写一遍「跳过同队、跳过死人、跳过自己」。
 * Unity 的做法是：碰撞/查询**只看两个东西** —— 物体的 Layer 与查询的 LayerMask。
 * 于是「箭只和单位+地形发生关系」「AI 视线只关心地形」「爆炸只关心单位」这三件事
 * 变成三条掩码常量，而不是三段复制的循环。
 *
 * <h2>为什么还要一条「队伍掩码」轴</h2>
 * Unity 里敌我通常也塞进 Layer（Player/Enemy），因为它的阵营是**静态**的。
 * 本项目的阵营是**运行期动态**的（{@code CrowdData.team} 是 0..31 的字节，同一只僵尸可能换队），
 * 所以层掩码只表达「类别」（单位/投掷物/地形/触发体），敌我由与层**正交**的队伍掩码表达：
 * {@link PhysicsQuery} 的过滤器同时吃 layerMask 与 teamMask，
 * 这样「打敌人」= UNIT 层 + 队伍掩码取反，两个轴各管一件事，不会互相污染。
 *
 * <h2>位布局</h2>
 * 层号即位数下标，掩码为 {@code 1 << layer}。与 Unity 一样是 32 位掩码，
 * 只是本项目目前只用到低 {@link #LAYER_COUNT} 位。
 */
public final class PhysicsLayers {

    private PhysicsLayers() {
    }

    /** 方块地形（体素）。相当于 Unity 里给静态关卡几何用的 Default/Terrain 层。 */
    public static final int TERRAIN = 0;
    /** 群集单位（也包括将来接入的真实实体）。 */
    public static final int UNIT = 1;
    /** 投掷物（箭 / 火球 / 雪球）。 */
    public static final int PROJECTILE = 2;
    /**
     * 触发体：射程球、爆炸半径、AoE 体积之类的「只参与查询、不产生接触」的东西。
     * Unity 里对应勾了 {@code isTrigger} 的 Collider —— 它照样能被 OverlapSphere 查到，
     * 但**永远不产生接触点**（见 {@link Contacts} 的生成规则）。
     */
    public static final int TRIGGER = 3;
    /**
     * 忽略射线层。Unity 内置了一个 Ignore Raycast 层，默认的射线掩码不含它。
     * 本项目用它来标记「不希望被 AI 视线采样看到的辅助体」（例如将来做射线调试用的可视化体）。
     */
    public static final int IGNORE_RAYCAST = 4;

    /** 实际使用的层数（掩码的合法位宽）。 */
    public static final int LAYER_COUNT = 5;

    /** 空掩码：谁都不匹配。 */
    public static final int NONE = 0;
    /** 全掩码：全部层。 */
    public static final int ALL = (1 << LAYER_COUNT) - 1;
    /**
     * 默认射线掩码 = 除 IGNORE_RAYCAST 之外的所有层。
     * 与 Unity 的 {@code Physics.DefaultRaycastLayers} 同义（那边是「除 Ignore Raycast 之外」）。
     */
    public static final int DEFAULT_RAYCAST = ALL & ~bit(IGNORE_RAYCAST);

    private static final String[] NAMES = {
            "Terrain", "Unit", "Projectile", "Trigger", "IgnoreRaycast"
    };

    /** 层号 -> 掩码位。 */
    public static int bit(int layer) {
        return 1 << (layer & 31);
    }

    /** 把若干层号合成一个掩码（Unity: {@code LayerMask.GetMask("A","B")}）。 */
    public static int mask(int... layers) {
        int m = 0;
        for (int l : layers) {
            m |= bit(l);
        }
        return m;
    }

    /** 掩码里是否含某一层。 */
    public static boolean has(int mask, int layer) {
        return (mask & bit(layer)) != 0;
    }

    public static int add(int mask, int layer) {
        return mask | bit(layer);
    }

    public static int remove(int mask, int layer) {
        return mask & ~bit(layer);
    }

    /** 层的可读名（日志/接线文档用）。 */
    public static String name(int layer) {
        return layer >= 0 && layer < NAMES.length ? NAMES[layer] : ("Layer" + layer);
    }

    /** 掩码的可读形式，例如 {@code [Terrain|Unit]}。日志里比裸数字有用得多。 */
    public static String describe(int mask) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < NAMES.length; i++) {
            if (has(mask, i)) {
                if (!first) {
                    sb.append('|');
                }
                sb.append(NAMES[i]);
                first = false;
            }
        }
        if (first) {
            sb.append("None");
        }
        return sb.append(']').toString();
    }
}
