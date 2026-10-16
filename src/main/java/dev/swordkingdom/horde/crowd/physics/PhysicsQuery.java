package dev.swordkingdom.horde.crowd.physics;

/**
 * 查询原语 —— 对应 Unity 的 {@code Physics.Raycast / SphereCast / OverlapSphere / OverlapBox / Linecast}。
 *
 * <h2>为什么要把查询收成一套（这是阶段 1 最值钱的部分）</h2>
 * 现在同一个问题在代码里有多份实现：AI 视线是 {@code BlockWorld.hasLineOfSight} 的**采样**、
 * 投掷物命中是 {@code SkillParams.sweepHit} 的**球-球距离**、AoE 是 {@code blastAt} 里
 * 又写一遍「按半径遍历格子」。三份实现的语义各不相同（采样 vs 精确、球 vs 方块、
 * 是否检查高度差），于是「为什么这一箭穿墙了/为什么这个爆炸没打到人」这类问题
 * 要同时在三个地方查。Unity 的答案是把它们收成**同一组查询原语 + 同一个过滤器**：
 * <pre>
 *   AI 视线        -> Raycast(origin, dir, dist, TERRAIN, IGNORE)         // 只被地形挡
 *   投掷物 CCD     -> SphereCast(from, delta, r, TERRAIN|UNIT, IGNORE)    // 精确 TOI
 *   投掷物命中     -> OverlapSphere 或 SphereCast，同一套过滤器（队伍轴）
 *   范围伤害/爆炸  -> OverlapSphere(pos, radius, UNIT, 队伍=敌人)
 *   射程/触发检测  -> OverlapSphere(..., TRIGGER, COLLIDE)
 * </pre>
 * 这不是「少写几行」，而是**把语义固定下来**：同一个过滤器输入必然给出同一个答案。
 *
 * <h2>两条正交的过滤轴</h2>
 * <ul>
 *   <li><b>层掩码</b>（{@link PhysicsLayers}）：类别过滤（地形 / 单位 / 投掷物 / 触发体），
 *       与 Unity 的 LayerMask 一比一；</li>
 *   <li><b>队伍掩码</b>：敌我过滤。Unity 把敌我塞进 Layer 是因为它的阵营静态；
 *       本项目阵营运行期可换（{@code CrowdData.team}），所以单列一条轴，
 *       {@link #teamMaskExcluding(int)} 就是「只打敌人」。</li>
 * </ul>
 *
 * <h2>无分配与不可重入</h2>
 * 所有查询都写在调用方给的 {@link RaycastHit} 上，内部状态都在本对象里复用，
 * 一次查询**不产生任何对象**。代价是本类**不可重入**（不能在查询回调里再发起查询）——
 * 物理层只在服务端单线程跑，这条限制是有意为之并且在文档里写明。
 */
public final class PhysicsQuery implements BroadphaseGrid.BodyVisitor {

    /** 一次查询最多走多少个 broadphase 格子（超长/对角线射线兜底）。 */
    public static final int DEFAULT_MAX_QUERY_CELLS = 4096;

    private final RigidbodySoA bodies;
    private final VoxelSource voxels;
    private final VoxelSweep voxelSweep;
    private final BroadphaseGrid grid;
    private final PhysicsWorld.Settings settings;

    private int[] visited = new int[1];
    private int stamp = 1;
    private int maxQueryCells = DEFAULT_MAX_QUERY_CELLS;

    // 复用的临时数组（本类不可重入 → 可以安全持有）
    private final float[] nrm = new float[3];
    private final float[] cp1 = new float[3];
    private final float[] cp2 = new float[3];
    private final float[] seg = new float[3];

    // ---- 当前查询状态（cast 模式） ----
    private float qpx;
    private float qpy;
    private float qpz;
    private float qdx;
    private float qdy;
    private float qdz;
    private float qCastRadius;
    private float qBoxHx = -1.0F;
    private float qBoxHy;
    private float qBoxHz;
    private int qLayerMask;
    private int qTeam;
    private int qTeamMask;
    private boolean qHitTriggers;

    // ---- 当前查询结果（cast 模式） ----
    private boolean found;
    private float bestT;
    private int bestBody;
    private float bestNx;
    private float bestNy;
    private float bestNz;
    private float bestPx;
    private float bestPy;
    private float bestPz;

    // ---- 当前查询状态（overlap 模式） ----
    private boolean overlapMode;
    private int[] ovOut;
    private int ovMax;
    private int ovCount;
    private float ovRadius;
    private float ovMinX;
    private float ovMinY;
    private float ovMinZ;
    private float ovMaxX;
    private float ovMaxY;
    private float ovMaxZ;
    private boolean ovSphere;

    // ---- 插桩 ----
    public long queries;
    public long bodyTests;
    public long voxelQueries;
    public long queryCells;
    public long truncated;

    PhysicsQuery(RigidbodySoA bodies, VoxelSource voxels, BroadphaseGrid grid,
                 PhysicsWorld.Settings settings) {
        this.bodies = bodies;
        this.voxels = voxels == null ? VoxelSource.EMPTY : voxels;
        this.grid = grid;
        this.settings = settings;
        this.voxelSweep = new VoxelSweep(this.voxels);
    }

    public VoxelSweep voxelSweep() {
        return voxelSweep;
    }

    public void resetCounters() {
        queries = 0L;
        bodyTests = 0L;
        voxelQueries = 0L;
        queryCells = 0L;
        truncated = 0L;
        voxelSweep.resetCounters();
    }

    public void setMaxQueryCells(int max) {
        this.maxQueryCells = Math.max(16, max);
    }

    /**
     * 让下一次查询忽略某个 body（CCD 用它排除自己）。
     *
     * <p>为什么用「一个可变字段」而不是加参数：所有查询方法的签名都要与 Unity 一一对应
     * （{@code raycast(origin, dir, out, dist, mask, q)}），多塞一个 self 参数就变形了。
     * 代价是调用方必须成对使用（用完设回 -1），物理层内部只有 CCD 一处用它。
     */
    public void setIgnoreBody(int body) {
        this.ignoreBody = body;
    }

    private int ignoreBody = -1;

    /** 「除 team 之外的所有阵营」掩码：只打敌人时用它。 */
    public static int teamMaskExcluding(int team) {
        return ~(1 << (team & 31));
    }

    // ------------------------------------------------------------------
    // Unity 同形 API —— Raycast / Linecast / SphereCast
    // ------------------------------------------------------------------

    /**
     * 射线（Unity: {@code Physics.Raycast(origin, direction, out hit, maxDistance, layerMask, q)}）。
     *
     * @param direction 方向（**不必归一化**；内部归一化后乘 maxDistance）
     * @param out       命中结果（可为 null，只要 boolean 结论）
     * @return 是否命中
     */
    public boolean raycast(float ox, float oy, float oz,
                           float dx, float dy, float dz,
                           float maxDistance, int layerMask, QueryTriggerInteraction triggers,
                           RaycastHit out) {
        return cast(ox, oy, oz, dx, dy, dz, 0.0F, -1.0F, 0.0F, 0.0F,
                maxDistance, layerMask, 0, 0, triggers, out);
    }

    /** 带队伍轴的射线（「只打敌人」的视线/瞄准查询用）。 */
    public boolean raycastTeam(float ox, float oy, float oz,
                               float dx, float dy, float dz,
                               float maxDistance, int layerMask, int team, int teamMask,
                               QueryTriggerInteraction triggers, RaycastHit out) {
        return cast(ox, oy, oz, dx, dy, dz, 0.0F, -1.0F, 0.0F, 0.0F,
                maxDistance, layerMask, team, teamMask, triggers, out);
    }

    /** 两点连线（Unity: {@code Physics.Linecast}）—— 语义与 {@link #raycast} 相同。 */
    public boolean linecast(float x0, float y0, float z0, float x1, float y1, float z1,
                            int layerMask, QueryTriggerInteraction triggers, RaycastHit out) {
        return raycast(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, distance(x0, y0, z0, x1, y1, z1),
                layerMask, triggers, out);
    }

    /**
     * 视线是否通畅（本项目的 {@code BlockWorld.hasLineOfSight} 的**精确**替代）。
     *
     * <p>与旧实现的差别：旧的是固定步长采样（0.5 格，上限 24 点，还会在远距离变粗），
     * 于是「薄墙 + 远距离」会漏判；这里是体素 DDA + 精确 slab 求交，**任何厚度**都能判出来。
     */
    public boolean hasLineOfSight(float x0, float y0, float z0, float x1, float y1, float z1) {
        // 注意 maxDistance 必须是**两点距离**：raycast 的语义是「方向归一化后 × maxDistance」（与 Unity 一致），
        // 传 1.0 只会检查 1 格长的射线 —— 这是最容易写错的一处，所以这里显式算出来。
        return !raycast(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, distance(x0, y0, z0, x1, y1, z1),
                PhysicsLayers.bit(PhysicsLayers.TERRAIN), QueryTriggerInteraction.IGNORE, null);
    }

    private static float distance(float x0, float y0, float z0, float x1, float y1, float z1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        return (float) StrictMath.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 球体扫掠（Unity: {@code Physics.SphereCast}）。 */
    public boolean sphereCast(float ox, float oy, float oz, float radius,
                              float dx, float dy, float dz,
                              float maxDistance, int layerMask, QueryTriggerInteraction triggers,
                              RaycastHit out) {
        return cast(ox, oy, oz, dx, dy, dz, radius, -1.0F, 0.0F, 0.0F,
                maxDistance, layerMask, 0, 0, triggers, out);
    }

    /** 带队伍轴的球体扫掠（投掷物 CCD + 敌我过滤一次搞定）。 */
    public boolean sphereCastTeam(float ox, float oy, float oz, float radius,
                                  float dx, float dy, float dz,
                                  float maxDistance, int layerMask, int team, int teamMask,
                                  QueryTriggerInteraction triggers, RaycastHit out) {
        return cast(ox, oy, oz, dx, dy, dz, radius, -1.0F, 0.0F, 0.0F,
                maxDistance, layerMask, team, teamMask, triggers, out);
    }

    /**
     * 盒体扫掠（Unity: {@code Physics.BoxCast}）。
     *
     * <p>对**地形**精确（Minkowski 外扩 + 线段求交）；对**单位**用「外接半径的球体扫掠」近似
     * （保守：多报不漏报）。之所以接受这个近似：本项目的投掷物都是「小方块/小球」，
     * 真正常用的是 {@link #sphereCast}；盒体扫掠在这里是为了「将来做真实实体傀儡」时不再各写一套。
     */
    public boolean boxCast(float ox, float oy, float oz,
                           float hx, float hy, float hz,
                           float dx, float dy, float dz,
                           float maxDistance, int layerMask, QueryTriggerInteraction triggers,
                           RaycastHit out) {
        return cast(ox, oy, oz, dx, dy, dz, 0.0F, hx, hy, hz,
                maxDistance, layerMask, 0, 0, triggers, out);
    }

    // ------------------------------------------------------------------
    // Unity 同形 API —— OverlapSphere / OverlapBox
    // ------------------------------------------------------------------

    /**
     * 球体重叠（Unity: {@code Physics.OverlapSphereNonAlloc}）。
     *
     * @param outBodies 输出 body 槽位；返回**写入的数量**（&le; outBodies.length），
     *                  而 {@link #lastOverlapFound} 给出实际重叠总数（便于发现「上限截断」）
     */
    public int overlapSphere(float x, float y, float z, float radius,
                             int layerMask, QueryTriggerInteraction triggers,
                             int[] outBodies, int max) {
        return overlapCore(true, x, y, z, radius, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                layerMask, 0, 0, triggers, outBodies, max);
    }

    /** 带队伍轴的球体重叠（范围伤害/爆炸：只取敌人）。 */
    public int overlapSphereTeam(float x, float y, float z, float radius,
                                 int layerMask, int team, int teamMask,
                                 QueryTriggerInteraction triggers,
                                 int[] outBodies, int max) {
        return overlapCore(true, x, y, z, radius, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                layerMask, team, teamMask, triggers, outBodies, max);
    }

    /** 盒体重叠（Unity: {@code Physics.OverlapBoxNonAlloc}）。 */
    public int overlapBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                          int layerMask, QueryTriggerInteraction triggers,
                          int[] outBodies, int max) {
        return overlapCore(false, 0.0F, 0.0F, 0.0F, 0.0F, minX, minY, minZ, maxX, maxY, maxZ,
                layerMask, 0, 0, triggers, outBodies, max);
    }

    /** 盒体重叠 + 队伍轴。 */
    public int overlapBoxTeam(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                              int layerMask, int team, int teamMask,
                              QueryTriggerInteraction triggers, int[] outBodies, int max) {
        return overlapCore(false, 0.0F, 0.0F, 0.0F, 0.0F, minX, minY, minZ, maxX, maxY, maxZ,
                layerMask, team, teamMask, triggers, outBodies, max);
    }

    /** Unity: {@code Physics.CheckSphere} —— 只问「有没有」。 */
    public boolean checkSphere(float x, float y, float z, float radius, int layerMask) {
        return overlapSphere(x, y, z, radius, layerMask, QueryTriggerInteraction.USE_GLOBAL, null, 0) > 0
                || lastOverlapFound > 0;
    }

    /** 上一次重叠查询实际重叠了多少（可能大于写入 outBodies 的数量）。 */
    public int lastOverlapFound;

    /**
     * 重叠查询的唯一实现。
     *
     * <p>候选集来自 broadphase 的格子遍历（只可能与查询体积相交的格子），
     * 精确判据是 {@link #overlapSphereExact} / {@link #overlapBoxExact}。
     * 触发体与队伍过滤与扫掠查询共用同一段逻辑（{@link #visit}），
     * 于是「爆炸该不该打到友军/该不该打到触发器」不会有第二套答案。
     */
    private int overlapCore(boolean sphere,
                            float cx, float cy, float cz, float radius,
                            float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                            int layerMask, int team, int teamMask,
                            QueryTriggerInteraction triggers, int[] outBodies, int max) {
        queries++;
        lastOverlapFound = 0;
        ovOut = outBodies;
        ovMax = outBodies == null ? 0 : Math.min(max, outBodies.length);
        ovCount = 0;
        overlapMode = true;
        ovSphere = sphere;
        ovCx = cx;
        ovCy = cy;
        ovCz = cz;
        ovRadius = radius;
        if (!sphere) {
            ovMinX = minX;
            ovMinY = minY;
            ovMinZ = minZ;
            ovMaxX = maxX;
            ovMaxY = maxY;
            ovMaxZ = maxZ;
        } else {
            ovMinX = cx - radius;
            ovMinY = cy - radius;
            ovMinZ = cz - radius;
            ovMaxX = cx + radius;
            ovMaxY = cy + radius;
            ovMaxZ = cz + radius;
        }
        qLayerMask = layerMask;
        qTeam = team;
        qTeamMask = teamMask;
        qHitTriggers = triggers.hitsTriggers(settings.queriesHitTriggers);

        if (bodies != null && (layerMask & unitLayers()) != 0) {
            ensureVisited(bodies.capacity);
            stamp++;
            if (stamp <= 0) {
                java.util.Arrays.fill(visited, 0);
                stamp = 1;
            }
            grid.forEachInAabb(bodies, ovMinX, ovMinY, ovMinZ, ovMaxX, ovMaxY, ovMaxZ,
                    visited, stamp, this);
        }
        overlapMode = false;
        return ovCount;
    }

    private float ovCx;
    private float ovCy;
    private float ovCz;

    /** 球与胶囊的精确重叠（球心到胶囊轴的距离 &le; 半径和）。 */
    public boolean overlapSphereExact(float x, float y, float z, float radius, int j) {
        ShapeMath.closestPointOnSegment(x, y, z,
                bodies.posX[j], bodies.capsuleBottomY(j), bodies.posZ[j],
                bodies.posX[j], bodies.capsuleTopY(j), bodies.posZ[j], cp1);
        float dx = x - cp1[0];
        float dy = y - cp1[1];
        float dz = z - cp1[2];
        float sum = radius + bodies.radius[j];
        return dx * dx + dy * dy + dz * dz <= sum * sum;
    }

    /**
     * 盒与胶囊的重叠。
     *
     * <p>做法：把盒子各方向外扩胶囊半径，再与**胶囊轴线段**求交。
     * 这对「盒角」是保守的（外扩盒在角上是方的），方向是「多报不漏报」——
     * 对范围伤害来说就是「墙角后面也可能被溅到」，与「宁可多给」的 broadphase 语义一致。
     */
    public boolean overlapBoxExact(float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ, int j) {
        float r = bodies.radius[j];
        float t = Sweep.segmentAabb(bodies.posX[j], bodies.capsuleBottomY(j), bodies.posZ[j],
                0.0F, bodies.capsuleTopY(j) - bodies.capsuleBottomY(j), 0.0F,
                minX - r, minY - r, minZ - r, maxX + r, maxY + r, maxZ + r, nrm);
        return t != Sweep.NO_HIT;
    }

    // ------------------------------------------------------------------
    // 核心：扫掠
    // ------------------------------------------------------------------

    /**
     * 所有扫掠查询的唯一实现（射线是 radius = 0 的球扫掠，球扫掠是半径 R 的球扫掠）。
     *
     * <p>顺序上**先地形后单位**：体素 DDA 的结果用作单位遍历的提前终止阈值
     * （地形命中在前时，后面的单位不必再精确求交）。
     */
    private boolean cast(float ox, float oy, float oz,
                         float dirX, float dirY, float dirZ,
                         float castRadius, float boxHx, float boxHy, float boxHz,
                         float maxDistance, int layerMask, int team, int teamMask,
                         QueryTriggerInteraction triggers, RaycastHit out) {
        queries++;
        if (out != null) {
            out.clear();
        }
        float len = (float) StrictMath.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len <= 1.0E-9F || maxDistance <= 0.0F) {
            return false;
        }
        float inv = maxDistance / len;
        float dx = dirX * inv;
        float dy = dirY * inv;
        float dz = dirZ * inv;

        boolean hitTriggers = triggers.hitsTriggers(settings.queriesHitTriggers);
        qpx = ox;
        qpy = oy;
        qpz = oz;
        qdx = dx;
        qdy = dy;
        qdz = dz;
        qCastRadius = Math.max(0.0F, castRadius);
        qBoxHx = boxHx;
        qBoxHy = boxHy;
        qBoxHz = boxHz;
        qLayerMask = layerMask;
        qTeam = team;
        qTeamMask = teamMask;
        qHitTriggers = hitTriggers;
        overlapMode = false;
        found = false;
        bestT = Float.MAX_VALUE;
        bestBody = RaycastHit.NO_BODY;
        bestNx = bestNy = bestNz = 0.0F;
        bestPx = bestPy = bestPz = 0.0F;

        // ---- 1) 地形（体素） ----
        boolean wantTerrain = PhysicsLayers.has(layerMask, PhysicsLayers.TERRAIN);
        if (wantTerrain) {
            voxelQueries++;
            float pad = qBoxHx >= 0.0F ? Math.max(qBoxHx, Math.max(qBoxHy, qBoxHz)) : qCastRadius;
            float t = voxelSweep.raycast(voxels, ox, oy, oz, dx, dy, dz, pad, nrm);
            if (t != Sweep.NO_HIT) {
                found = true;
                bestT = t;
                bestNx = nrm[0];
                bestNy = nrm[1];
                bestNz = nrm[2];
            }
        }

        // ---- 2) 单位（body 层掩码非空才走） ----
        if (bodies != null && (layerMask & unitLayers()) != 0) {
            ensureVisited(bodies.capacity);
            stamp++;
            if (stamp <= 0) {
                java.util.Arrays.fill(visited, 0);
                stamp = 1;
            }
            // 扫掠的查询走廊按「半径 + 网格陈旧余量」外扩：
            // CCD 在 integrate 之后跑，此时 broadphase 还是积分前的位置，
            // 每个 body 最多移动 maxDisplacementPerSubStep，所以必须把余量算进来（见 PhysicsWorld.ccd）。
            float pad = (qBoxHx >= 0.0F ? Math.max(qBoxHx, Math.max(qBoxHy, qBoxHz)) : qCastRadius)
                    + settings.gridQuerySlack();
            if (found) {
                // 地形已经命中 → 只需要查到 bestT 之前的位置
                float endT = bestT;
                grid.forEachAlongSegment(bodies, ox, oy, oz,
                        ox + dx * endT, oy + dy * endT, oz + dz * endT,
                        pad, visited, stamp, maxQueryCells, this);
            } else {
                grid.forEachAlongSegment(bodies, ox, oy, oz, ox + dx, oy + dy, oz + dz,
                        pad, visited, stamp, maxQueryCells, this);
            }
            queryCells += grid.lastWalkCells();
            truncated += grid.lastWalkTruncated();
        }

        if (!found) {
            return false;
        }
        if (out != null) {
            float t = bestT;
            out.t = t;
            out.distance = t * maxDistance;
            out.pointX = ox + dx * t;
            out.pointY = oy + dy * t;
            out.pointZ = oz + dz * t;
            out.normalX = bestNx;
            out.normalY = bestNy;
            out.normalZ = bestNz;
            out.body = bestBody;
            if (bestBody == RaycastHit.NO_BODY) {
                out.isVoxel = true;
                // 用扫掠记下的**体素坐标**，而不是 floor(命中点)：
                // 命中点常常正好落在方块面上，floor 会把它算到隔壁的空气格（见 VoxelSweep 的说明）
                out.blockX = voxelSweep.hitVoxelX();
                out.blockY = voxelSweep.hitVoxelY();
                out.blockZ = voxelSweep.hitVoxelZ();
                out.layer = PhysicsLayers.TERRAIN;
            } else {
                out.layer = bodies.layer[bestBody] & 0xFF;
                out.team = bodies.team[bestBody] & 0xFF;
                out.isTrigger = bodies.isTrigger[bestBody];
            }
        }
        return true;
    }

    /** 单位相关的层（除了地形与忽略层之外的所有层都可能挂在 body 上）。 */
    private static int unitLayers() {
        return PhysicsLayers.ALL & ~PhysicsLayers.bit(PhysicsLayers.TERRAIN);
    }

    /**
     * 单位候选的精确求交（{@link BroadphaseGrid} 的回调）。
     *
     * <p>这里体现「同一套过滤器」：层、触发体策略、队伍三条判据分别对应一个 if，
     * 任何查询都不会漏掉其中一条（旧代码里「忘了判队伍」就是这么发生的）。
     * 同一个回调同时服务「扫掠」与「重叠」两种模式 —— 过滤逻辑因此只有一份。
     */
    @Override
    public void visit(int j) {
        if (!bodies.alive[j] || j == ignoreBody) {
            return;
        }
        int layer = bodies.layer[j] & 0xFF;
        if (!PhysicsLayers.has(qLayerMask, layer)) {
            return;
        }
        if (bodies.isTrigger[j] && !qHitTriggers) {
            return;
        }
        if (qTeamMask != 0 && (qTeamMask & (1 << (bodies.team[j] & 31))) == 0) {
            return;
        }
        bodyTests++;
        if (overlapMode) {
            boolean hit = ovSphere
                    ? overlapSphereExact(ovCx, ovCy, ovCz, ovRadius, j)
                    : overlapBoxExact(ovMinX, ovMinY, ovMinZ, ovMaxX, ovMaxY, ovMaxZ, j);
            if (hit) {
                lastOverlapFound++;
                if (ovCount < ovMax) {
                    ovOut[ovCount++] = j;
                }
            }
            return;
        }
        float ax = bodies.posX[j];
        float ay = bodies.capsuleBottomY(j);
        float az = bodies.posZ[j];
        float bx = bodies.posX[j];
        float by = bodies.capsuleTopY(j);
        float bz = bodies.posZ[j];
        float r = bodies.radius[j];
        // 盒体扫掠对单位退化成外接球（保守，见 boxCast 的注释）
        float castR = qBoxHx >= 0.0F ? Math.max(qBoxHx, Math.max(qBoxHy, qBoxHz)) : qCastRadius;
        float t = Sweep.sphereCastCapsule(qpx, qpy, qpz, qdx, qdy, qdz, castR,
                ax, ay, az, bx, by, bz, r, nrm);
        if (t == Sweep.NO_HIT || t >= bestT) {
            return;
        }
        bestT = t;
        bestBody = j;
        bestNx = nrm[0];
        bestNy = nrm[1];
        bestNz = nrm[2];
        bestPx = qpx + qdx * t;
        bestPy = qpy + qdy * t;
        bestPz = qpz + qdz * t;
        found = true;
    }

    private void ensureVisited(int cap) {
        if (visited.length < cap) {
            visited = new int[cap];
            stamp = 0;
        }
    }
}
