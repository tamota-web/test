# 物理层接线说明（下一轮照此改集成点）

> **本轮我没有改任何集成点**。本文是补丁级说明：每条都给「锚点搜索串 + 本轮读到的行号 + 可粘贴的代码 + 为什么 + 怎么验证」。
> 物理层本身见 [`docs/PHYSICS.md`](PHYSICS.md)；离线验证：`.\gradlew.bat physicsHarness`。

---

## 0. 阅读须知

1. **行号会漂移**：本轮读行号时另一个代理正在改 `crowd/CrowdManager.java`（我从 2393 行读到 3071 行）。
   **一律以锚点搜索串为准**，行号只用来快速定位。
2. **三段独立接线**（阶段 1 / 2 / 3）：每段都能单独上线、单独回滚。建议**按顺序**上，每段上线后先在真机观察一个战斗周期。
3. **两条单位换算铁律（最容易出错，先记住）**：
   - **位置**：`CrowdData.posX/posY/posZ` 是 **AABB 最小角**；`RigidbodySoA.posX/posY/posZ` 是 **碰撞体中心**。
     ```
     center.x = crowd.posX[i] + halfWidth
     center.y = crowd.posY[i] + height * 0.5f
     center.z = crowd.posZ[i] + halfWidth
     写回：crowd.posX[i] = center.x - halfWidth   （Y 同理减 height*0.5f）
     ```
   - **速度**：`CrowdData.velX/velY/velZ` 是 **格/tick**；`RigidbodySoA.velX/velY/velZ` 是 **格/秒**。
     ```
     进：vel_perSec = vel_perTick * settings.fixedRateHz     // 20
     出：vel_perTick = vel_perSec / settings.fixedRateHz
     ```
   - **漏做的典型症状**：位置漏做 → 单位整体偏半个身位、贴地抖 1 ulp；速度漏做 → 重力像被放大/缩小 20 倍（单位瞬间飞天或趴地）。
4. 本文所有代码里的物理层 API 都已在本轮实现并通过 `physicsHarness`，签名可以直接核对：
   `physics/PhysicsWorld.java`、`physics/PhysicsQuery.java`、`physics/RigidbodySoA.java`、`physics/Sweep.java`、`physics/PhysicsLayers.java`、`physics/CollisionDetectionMode.java`、`physics/QueryTriggerInteraction.java`、`physics/LevelVoxelSource.java`。

---

## 1. 阶段 1：查询原语 + CCD（只读路径，风险最低）

**阶段 1 的核心决定：物理世界只当「查询引擎 + CCD」用，不接管积分。**
也就是说：`physicsTick()` 里的积分与 `BlockWorld.move` **一点都不动**，物理层的 `stepOneTick()` 在阶段 1 **不要调用**。
理由：现在有两套地形解算（`BlockWorld.move` 与物理层的体素接触），同时开会**双重修正**（单位被推两次 → 抖动/穿模）。阶段 1 只把物理层用在**只读**路径上。

### 1.1 在 `CrowdManager` 里建一份物理世界

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/CrowdManager.java` |
| 锚点 | 搜索 `private BlockWorld blockWorld;`（本轮行号 88） |
| 操作 | 在它下面插入字段与新方法 |

```java
    // ---- 物理层（Unity3D/PhysX 参照，见 docs/PHYSICS.md）----
    // 阶段 1 只把它当「查询引擎 + CCD」用：地形解算与积分仍由 BlockWorld.move / physicsTick 负责，
    // 因此**不要**在这里调 stepOneTick()（两套地形解算同时开 = 双重修正）。
    private dev.swordkingdom.horde.crowd.physics.PhysicsWorld physics;

    /**
     * 惰性建立物理世界。
     *
     * <p>body 容量跟着 CrowdData 走：阶段 1 里这些槽位只承担「查询代理体」的角色
     * （例如把单位胶囊同步进来只为了能被投掷物扫掠命中，见 1.4 的做法二）。
     * 一个都不建也能跑：查询的方块部分（视线、投掷物撞墙）完全不依赖 body。
     */
    private void ensurePhysics(ServerLevel lvl) {
        if (physics != null || lvl == null) {
            return;
        }
        physics = new dev.swordkingdom.horde.crowd.physics.PhysicsWorld(
                new dev.swordkingdom.horde.crowd.physics.RigidbodySoA(Math.max(64, data.capacity)),
                new dev.swordkingdom.horde.crowd.physics.LevelVoxelSource(lvl),
                // Settings.vanilla() = 20Hz + 原版 0.08 格/tick² 重力 + 原版 0.98/tick 阻力的等价换算
                dev.swordkingdom.horde.crowd.physics.PhysicsWorld.Settings.vanilla());
    }

    /** 物理层（可能为 null：离线 harness / 没有世界时）。 */
    public dev.swordkingdom.horde.crowd.physics.PhysicsWorld physics() {
        return physics;
    }
```

然后在同一文件里：

| 项 | 内容 |
| --- | --- |
| 锚点 | 搜索 `if (blockWorld == null) {`（在 `tickWithLevel` 内，本轮行号 1056） |
| 操作 | 在 `blockWorld = new BlockWorld(level, 1 << 17);` 之后插入一行 |

```java
                ensurePhysics(level);
```

> 建议把 `import` 补齐而不是写全限定名（本文为了便于粘贴而写全限定名）。
> 需要 import 的：`dev.swordkingdom.horde.crowd.physics.{PhysicsWorld, RigidbodySoA, PhysicsQuery, PhysicsLayers, QueryTriggerInteraction, CollisionDetectionMode, Sweep, RaycastHit, LevelVoxelSource}`。

### 1.2 AI 视线：`BlockWorld.hasLineOfSight` → `PhysicsQuery.hasLineOfSight`

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/CrowdManager.java` |
| 锚点 | 搜索 `private boolean hasLineOfSight(float x0`（本轮行号 1505） |
| 操作 | **整段替换**（签名不变，调用方完全不用改） |

```java
    /**
     * 视线判定：优先走物理层的**精确**求交，物理层不可用时退回旧的采样实现。
     *
     * <p>为什么值得换：旧实现是「0.5 格步长采样、上限 24 点、远距自适应变粗」，
     * 于是「薄墙 + 远距离」会漏判（骷髅隔着 1 格厚的墙放箭）。物理层是体素 DDA + slab 精确求交，
     * 任何厚度都能判出来，而且**访问的体素数更少**（实测 48 格 = 49 次，旧采样 = 96 次方块查询）。
     */
    private boolean hasLineOfSight(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (physics != null) {
            return physics.query().hasLineOfSight(x0, y0, z0, x1, y1, z1);
        }
        return blockWorld == null || blockWorld.hasLineOfSight(x0, y0, z0, x1, y1, z1);
    }
```

**行为差异对照表（替换前必须知道）**

| 场景 | 旧（采样） | 新（精确） |
| --- | --- | --- |
| 1 格厚墙、40 格外 | **可能漏判**（采样点跳过薄墙） | 必判为挡住 |
| 48 格视线成本 | 96 次方块查询（上限 24 点时更粗） | 49 次体素访问 |
| 水/草丛等无碰撞方块 | 用 `getCollisionShape`，不吃 | 同（`LevelVoxelSource` 也吃碰撞形状） |
| 语义 | 「采样到实心就挡」 | 「线段与碰撞盒有交就挡」 |

验证：`physicsHarness` 的 `[3]`（40 格外 1 格厚墙必须挡住视线）与 `[4]`（体素访问次数）；真机上观察「薄墙后的骷髅是否还会放箭」。

### 1.3 投掷物方块碰撞 + CCD：替换 `ProjectileManager.blockAdapter`

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/ProjectileManager.java` |
| 锚点 | 搜索 `public static Adapter blockAdapter(BlockWorld world, float halfSize)`（本轮行号 888） |
| 操作 | **新增**一个 `physicsAdapter(...)`（不动旧方法，旧方法留作回滚），然后在 `ProjectileManagers` 里改一行装配 |

```java
    /**
     * 方块碰撞适配（物理层版）：**扫掠**而不是逐轴解算。
     *
     * <h2>为什么必须换成扫掠</h2>
     * 箭 3 格/tick = 60 格/秒，一个子步就走 3 格，而墙只有 1 格厚。
     * 离散检测（只看终点）在「起点墙前、终点墙后」时**完全看不到墙**：
     * physicsHarness 实测终点冲到 x=12.0（墙在 x=10..11，已经穿过去了）；
     * 换成解析 TOI 后落在 x=9.51，且命中时刻 t=0.375 与手算逐位相等。
     *
     * <h2>契约与旧实现完全一致</h2>
     * <ul>
     *   <li>in/out：把「想走的位移 dl」解算成「实际位移 out」，并把位置推进到解算后的位置；</li>
     *   <li>速度：被方块削掉的分量必须从 vel 里扣掉（`vel += out - dl`），
     *       否则贴着墙飞时会一直加速；保留这一条才能保持「沿墙滑动」的手感；</li>
     *   <li>返回 true = 撞到方块（上层据此决定「消失」还是「卡住」）。</li>
     * </ul>
     *
     * <p>用到的 {@code world.sweepVoxelsBox(...)} 已在物理层提供
     * （`PhysicsWorld.sweepVoxelsBox` / `sweepVoxelsSphere` / `voxels()`），无需再补代码。
     *
     * @param world 物理世界（必须已经建好；没有世界时上层仍用旧的 blockAdapter）
     */
    public static Adapter physicsAdapter(dev.swordkingdom.horde.crowd.physics.PhysicsWorld world, float halfSize) {
        final float hs = Math.max(0.02F, halfSize);
        final float[] nrm = new float[3];
        return (p, dl, out, vel) -> {
            // 1) 扫掠：移动 AABB（半宽 hs）vs 体素地形，拿最早接触时刻 t
            dev.swordkingdom.horde.crowd.physics.PhysicsWorld.Settings s = world.settings;
            float t = world.sweepVoxelsBox(p[0], p[1], p[2], dl[0], dl[1], dl[2], hs, hs, hs, nrm);
            if (t == dev.swordkingdom.horde.crowd.physics.Sweep.NO_HIT || t >= 1.0F) {
                out[0] = dl[0];
                out[1] = dl[1];
                out[2] = dl[2];
                p[0] += dl[0];
                p[1] += dl[1];
                p[2] += dl[2];
                vel[0] = dl[0];
                vel[1] = dl[1];
                vel[2] = dl[2];
                return false;
            }
            // 2) 命中：只走到接触点，再沿法线退一个 contactOffset（避免下一 tick 从「已重叠」开始）
            float back = s.contactOffset;
            out[0] = dl[0] * t - nrm[0] * back;
            out[1] = dl[1] * t - nrm[1] * back;
            out[2] = dl[2] * t - nrm[2] * back;
            p[0] += out[0];
            p[1] += out[1];
            p[2] += out[2];
            // 3) 被削掉的分量从速度里扣掉（保留沿墙滑动：法向分量没了，切向还在）
            vel[0] += out[0] - dl[0];
            vel[1] += out[1] - dl[1];
            vel[2] += out[2] - dl[2];
            return true;
        };
    }
```

**装配改动**（这是阶段 1 唯一必改的一行）：

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/ProjectileManagers.java` |
| 锚点 | 搜索 `m.setAdapter(ProjectileManager.blockAdapter(world, 0.09F));`（本轮行号 75） |
| 操作 | 改成下面这样（保留旧路径作为兜底） |

```java
            // 物理层扫掠优先（60 格/秒的箭不隧穿）；没有物理世界时退回旧的逐轴解算
            dev.swordkingdom.horde.crowd.physics.PhysicsWorld pw =
                    PhysicsWorlds.get(level, cm /* 见下方说明，或用一个按维度缓存的 Map */);
            m.setAdapter(pw != null
                    ? ProjectileManager.physicsAdapter(pw, 0.09F)
                    : ProjectileManager.blockAdapter(world, 0.09F));
```

> 这里需要一个「按维度取物理世界」的地方。**最省事的做法**：直接在 `ProjectileManagers` 里加一个
> `private static final Map<ResourceKey<Level>, PhysicsWorld> WORLDS = new HashMap<>();` + `get(level)`（照抄 `blocks(level)` 的写法，锚点搜索 `public static BlockWorld blocks(ServerLevel level)`，本轮行号 36）。
> 更干净的做法：让 `CrowdManager` 暴露 `physics()`（1.1 已经加了），投掷物用 `CrowdManagers.getIfPresent(level.dimension()).physics()`；但注意**群集全死光时 `CrowdManager` 可能已不在表里**，而箭还要继续飞完 —— 所以推荐前者（物理世界独立于群集生命周期）。

验证：`physicsHarness` 的 `[5]`（离散检测穿墙 vs CCD 落在 9.51、`t=0.375`、命中方块 x=10、法线 -X、法向速度清零）。

### 1.4 投掷物命中判定：`crowdHitResolver` 的命中改成胶囊扫掠

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/CrowdManager.java` |
| 锚点 | 搜索 `public ProjectileManager.Hits crowdHitResolver()`（本轮行号 1872） |

现在里面用的是 `SkillParams.sweepHit(prevX.., 中心.., r2)`（球-球）。两种改法，**建议先只做 A（风险最小）**：

**A. 保持遍历不变，只把「球-球距离」换成「球扫掠 vs 胶囊」（推荐）**
锚点：搜索 `if (!SkillParams.sweepHit(`，把那一句替换为：

```java
                            // 用物理层的胶囊 TOI 取代球-球距离判定：
                            //  · 与查询原语同一套形状（直立胶囊，半径 = halfWidth、总高 = height）
                            //  · 半径叠加等价：半径 r2 的球撞半径 R 的胶囊 ≡ 点撞半径 R+r 的胶囊
                            float[] nrm = sweepNrm;   // 复用的 3 float 临时数组（字段）
                            float tHit = dev.swordkingdom.horde.crowd.physics.Sweep.sphereCastCapsule(
                                    prevX, prevY, prevZ,
                                    pd.posX[slot] - prevX, pd.posY[slot] - prevY, pd.posZ[slot] - prevZ,
                                    (float) Math.sqrt(r2),
                                    data.posX[j] + hw, data.posY[j] + r, data.posZ[j] + hw,
                                    data.posX[j] + hw, data.posY[j] + hh - r, data.posZ[j] + hw,
                                    hw, nrm);
                            if (tHit == dev.swordkingdom.horde.crowd.physics.Sweep.NO_HIT) {
                                continue;
                            }
```
其中 `hw = 该单位 halfWidth`、`hh = 该单位 height`、`r = hw`（胶囊半径 = halfWidth），
`data.posY[j] + r` / `data.posY[j] + hh - r` 是胶囊的**下球心/上球心**（注意 `CrowdData.posY` 是最小角）。
`MAX_CANDIDATES = 64` 的上限**保持不变**（它在更外层，照旧生效）。

**B. 把整个「找谁被打到」交给物理层查询**（更彻底，但要先把单位同步成 body）
把每个单位写进 `physics.bodies()`（`configureUnit`），然后：

```java
    RaycastHit hit = new RaycastHit();
    boolean any = physics.query().sphereCastTeam(
            prevX, prevY, prevZ, hitRadius,
            pd.posX[slot] - prevX, pd.posY[slot] - prevY, pd.posZ[slot] - prevZ,
            /*maxDistance=*/ 这一段位移的长度,
            PhysicsLayers.bit(PhysicsLayers.UNIT), team,
            PhysicsQuery.teamMaskExcluding(team),      // 只打敌人
            QueryTriggerInteraction.IGNORE, hit);
    if (any) { damageUnit(hit.body, ...); }            // hit.t 就是命中时刻
```
代价是**每次命中判定前要冲刷一遍 10k 单位的 body 位置**（阶段 2 的同步逻辑本身要做的），
所以 A 更适合阶段 1（零同步成本、语义等价、立刻可用）。

### 1.5 爆炸 / AoE：`blastAt` → `overlapSphereTeam`

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/CrowdManager.java` |
| 锚点 | 搜索 `private int blastAt(float x, float y, float z, float radius, float damage, int team, int skip)`（本轮行号 2021） |

现在是自己遍历 `grid` 的格子。换成物理层查询（同样**两种改法**），推荐先用「保留遍历、把距离判据换成 `PhysicsQuery.overlapSphereExact` 风格的精确球-胶囊」，
阶段 2 单位进物理层之后再整段换成：

```java
    private int blastAt(float x, float y, float z, float radius, float damage, int team, int skip) {
        if (radius <= 0.0F || damage <= 0.0F || physics == null) {
            return 0;   // 物理层不可用时保留旧路径（见下一段）
        }
        int[] out = physics.overlapScratch();
        int n = physics.query().overlapSphereTeam(x, y, z, radius,
                PhysicsLayers.bit(PhysicsLayers.UNIT), team, PhysicsQuery.teamMaskExcluding(team),
                QueryTriggerInteraction.IGNORE, out, out.length);
        int hit = 0;
        for (int k = 0; k < n; k++) {
            int j = out[k];
            if (j == skip) {
                continue;
            }
            float dx = data.posX[j] + halfWidthOf(j) - x;
            float dy = data.posY[j] + heightOf(j) * 0.5F - y;
            float dz = data.posZ[j] + halfWidthOf(j) - z;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > radius * radius) {
                continue;   // broadphase 是候选，精确判据在这里（与旧实现同形）
            }
            float applied = SkillParams.blastFalloff(damage, (float) Math.sqrt(d2), radius);
            if (applied <= 0.0F) {
                continue;
            }
            damageUnit(j, applied, 0.0F, x, z, radius);
            hit++;
        }
        return hit;
    }
```
**距离衰减、`skip` 语义、返回「命中几个」的含义全部不变** —— 这一条很重要：`crowdHitResolver` 用返回的命中数扣穿透次数，语义变了会连带改变穿透弹行为。

### 1.6 阶段 1 验收清单

```powershell
$env:JAVA_HOME='D:\java'; cd horde-mod
.\gradlew.bat physicsHarness --console=plain --offline     # 129 项必须全绿
.\gradlew.bat projectileHarness --console=plain --offline  # 旧的投掷物契约不能被破坏
```

- 真机观察 1：**60 格/秒的箭打 1 格厚墙**。上线前后各打 20 发，看是否还有「箭穿过墙打到了墙后的人」。
- 真机观察 2：**薄墙后的骷髅是否放箭**。精确视线会比采样更严格（该挡住的挡住）。
- 真机观察 3：`HordeProfiler` 的 `projectile` 段耗时（不应上升；体素访问比采样次数更少）。
- 卡顿归因：随时可以打一行
  ```java
  HordeLog.info("physics: {}", physics.describeStep());
  ```
  它会给出 `substeps / gridCells / pairs / contacts / impulses / posFix / ccd / clamp / woke / slept`。
  **阶段 1 里 `sweeps` 应该是 0**（因为还没调 `stepOneTick()`）。

### 1.7 阶段 1 回滚

三处调用各自切回一行即可（都在本文里保留了旧实现的调用形态）：
`physics = null;`（最粗暴：所有走 `physics` 的分支自动退回旧路径）。

---

## 2. 阶段 2：接触求解器替换 `CrowdSeparation`

| 项 | 内容 |
| --- | --- |
| 文件 | `crowd/CrowdManager.java` |
| 锚点 | 搜索 `CrowdSeparation.resolve(d, grid, sepHalfWidth, sepMass, strength, passes);`（本轮行号 2966，在 `private void pushEntities()` 里） |

### 2.1 逐字段同步表

| 方向 | `CrowdData` | `RigidbodySoA` | 换算 |
| --- | --- | --- | --- |
| 进 | `posX/posY/posZ` | `posX/posY/posZ` | `+halfWidth` / `+height*0.5f`（**中心化**） |
| 进 | `velX/velY/velZ` | `velX/velY/velZ` | `× 20`（格/tick → 格/秒） |
| 进 | `aliveFlag` | `alive[]` | 直接（`alloc/free` 或用 `setAlive` 语义手工写） |
| 进 | `team[i]` | `team[]` + `teamMask[]` | `teamMask = PhysicsQuery.teamMaskExcluding(team)` |
| 进 | `statsForSlot(i)` 的 `width/height` | `radius/height` | `radius = width/2`、`height = height`（`setCapsuleFromEntitySize`） |
| 进 | `statsForSlot(i)` 的 `knockbackResistance` | `mass` | `RigidbodySoA.massFromEntitySize(width, height, kbRes)` |
| 出 | `posX/posY/posZ` | `posX/posY/posZ` | `−halfWidth` / `−height*0.5f`（**反中心化**） |
| 出 | `velX/velY/velZ` | `velX/velY/velZ` | `÷ 20` |
| 出 | `onGround[i]` | 接触法线 | 「本步 `vy` 被接触消掉」或「存在 `ny>0.7` 的接触」 |
| 出 | `horizontalCollision[i]` | 水平位移 | 见 2.3 的两种做法 |

> **注意槽位对应关系**：物理层的 body 索引直接用 `CrowdData` 的槽位号（1:1），
> 所以 `alloc/free` 的策略必须一致；`ensureCapacity(data.capacity)` 在 spawn 后调一次。

### 2.2 同步时机（把 `pushEntities` 换成「同步 → step → 同步」）

锚点：搜索 `int passes = Math.max(1, HordeConfig.safeGet(HordeConfig.COMMON.collisionPasses, 2));`（本轮行号 2965），
把它与下面那行 `CrowdSeparation.resolve(...)` 一起替换为：

```java
        if (physics == null) {
            // 物理层不可用（离线的 harness / 没有世界）：退回原来的位置 MTV
            int passes = Math.max(1, HordeConfig.safeGet(HordeConfig.COMMON.collisionPasses, 2));
            CrowdSeparation.resolve(d, grid, sepHalfWidth, sepMass, strength, passes);
            return;
        }
        // ---- 阶段 2：物理层接触求解 ----
        // 1) sync in（中心化 + 格/秒）
        physics.bodies().ensureCapacity(d.capacity);
        for (int i = 0; i < d.highWater; i++) {
            // 只同步活着的单位；死掉的槽位保持原样（下一轮覆盖即可）
            ...
            physics.configureUnit(i, s.width, s.height, s.knockbackResistance, d.team[i] & 0xFF,
                    PhysicsQuery.teamMaskExcluding(d.team[i] & 0xFF));
            physics.bodies().setPose(i, d.posX[i] + hw, d.posY[i] + hh * 0.5F, d.posZ[i] + hw);
            physics.bodies().setVelocity(i, d.velX[i] * rate, d.velY[i] * rate, d.velZ[i] * rate);
        }
        // 2) 只跑「一个主步」的接触求解（权威 tick = 固定步长，绕开累加器）
        physics.stepOneTick();
        // 3) sync out（反中心化 + 格/tick）
        for (int i = 0; i < d.highWater; i++) {
            ...
            d.posX[i] = physics.bodies().posX[i] - hw;
            d.posY[i] = physics.bodies().posY[i] - hh * 0.5F;
            d.posZ[i] = physics.bodies().posZ[i] - hw;
            d.velX[i] = physics.bodies().velX[i] / rate;
            d.velY[i] = physics.bodies().velY[i] / rate;
            d.velZ[i] = physics.bodies().velZ[i] / rate;
        }
```

**为什么同步必须留在 `physicsTick()` 之前**：现有代码的注释已经写明——
「分离放在物理步之前：被挤到墙里时，后面的碰撞解算会兜住」。物理层同样需要这条兜底：
接触求解可能把单位挤进墙里，随后 `BlockWorld.move` 会把它挡出来。

**`configureUnit` 每个 tick 都调用可以吗？** 可以但有成本（每次会写 15 个字段/单位）。
建议：只在「类型变化 / spawn / reapply」时调用一次，其余 tick 只写 `setPose/setVelocity/alive`。

### 2.3 `onGround` / `horizontalCollision` 的两种写法

| 字段 | 做法 A（不碰物理层内部，推荐先上） | 做法 B（更贴 Unity） |
| --- | --- | --- |
| `onGround` | 沿用现有判据：`vy` 被削掉且 `vy<0` | `physics.contacts()` 里存在 `ny > 0.7` 且 `b == i` 的接触 |
| `horizontalCollision` | 沿用现有判据：水平位移 != 期望位移 | 存在 `|ny| < 0.7` 的接触，或水平位移被削掉 |

A 的优点：动画/脚步声等下游行为**逐字不变**（不会因为换物理层而改变视觉）；
B 的优点：与物理层真实接触一致。**建议先 A，观察一个版本后再考虑 B。**

### 2.4 阶段 2 的硬性注意

- **不要**同时开 `Settings.unitVoxelContacts`。它和 `BlockWorld.move` 会同时解算地形 →
  单位被推两次（表现为贴地抖动、台阶上下抖）。这是阶段 3 的事。
- **不要**同时保留 `CrowdSeparation.resolve` 调用（两份分离 = 分离量加倍）。
- 质量语义变了：原来 `sepMass` 只影响 MTV 的比例，现在 `mass` 会参与冲量分配与休眠能量。数值上等价（公式同形），但**击退的手感可能有细微差别**，上线后要打一仗看看。

### 2.5 阶段 2 验收与回滚

```powershell
.\gradlew.bat collisionHarness --console=plain --offline   # 现有：一坨必须被拆开
.\gradlew.bat physicsHarness --console=plain --offline     # [8] 收敛与不抖
```
- 真机：100 单位挤在一个方块里，看是否拆开、是否还抖（`describeStep()` 的 `contacts/posFix` 会给量）。
- 回滚：把 2.2 的替换段整体删掉，恢复 `CrowdSeparation.resolve(...)` 一行。

---

## 3. 阶段 3：层掩码 + 休眠（+ 可选地形接触）

### 3.1 层掩码分配表（建议值，落到 `configureUnit` 里）

| 层 | 用途 | 谁在用 |
| --- | --- | --- |
| `TERRAIN = 0` | 方块体素 | 所有视线/扫掠查询；`LevelVoxelSource` |
| `UNIT = 1` | 群集单位 / 将来的真实实体 | 单位-单位接触、AI 视线（除地形外的第二段）、投掷物命中、AoE |
| `PROJECTILE = 2` | 虚拟投掷物 | 只要参与被查询（例如「箭能不能被箭打掉」这种将来玩法） |
| `TRIGGER = 3` | 射程球 / 爆炸体积 / AoE 体积 | 只被 Overlap 查询，**永不产生接触** |
| `IGNORE_RAYCAST = 4` | 调试可视化体 | 默认射线掩码不含它 |

常用查询该用哪个掩码：

| 用途 | 掩码 | 触发体策略 |
| --- | --- | --- |
| AI 视线 | `bit(TERRAIN)` | `IGNORE` |
| 投掷物扫掠（撞墙消失） | `bit(TERRAIN)` | `IGNORE` |
| 投掷物命中（撞人） | `bit(UNIT)` + 队伍掩码 | `IGNORE` |
| 爆炸 / AoE | `bit(UNIT)` + `teamMaskExcluding(team)` | `IGNORE` |
| 射程/触发器检测 | `bit(TRIGGER)` | `COLLIDE` |

### 3.2 休眠接线：必须在哪些点唤醒

**不唤醒的后果**：出现「有速度但位置不动」的幽灵状态（下一 tick 又突然动一下 = 瞬移）。

| 唤醒点 | 文件 · 锚点 | 插入 |
| --- | --- | --- |
| 受伤 | `CrowdManager.damageUnit`（搜索 `private void damageUnit(int target, float damage`，本轮行号 2068）方法体开头 | `if (physics != null) physics.bodies().wake(target);` |
| 被击退 | 同上，`data.velX[target] += ...` 之后 | 同上（一次就够，放在方法开头即可） |
| 索敌成功 | `CrowdManager` 里写 `d.targetId[i] = ...` 的地方（搜索 `d.targetId[`） | `physics.bodies().wake(i);` |
| spawn / 传送 | `CrowdManager.spawn`（搜索 `public int spawn(`）与任何直接写 `posX[i] =` 的传送代码 | `wakeAll()` 或按槽位 `wake(i)` |
| AI 由静止转移动 | `physicsTick()` 里 `if (idle) { ... continue; }` 之前（搜索 `boolean idle = dirX == 0f`，本轮 `physicsTick` 在 2346 行附近） | `if (!idle) physics.bodies().wake(i);`（阶段 2 起，静止单位的意图变化必须唤醒） |
| 世界切换 / 重生成 | `setLevel`（搜索 `public void setLevel(ServerLevel level)`，本轮行号 924） | `if (physics != null) { physics.wakeAll(); }` |
| 开启休眠 | `pushEntities`（或 `tickWithLevel` 初始化处） | `physics.settings.allowSleep = HordeConfig...`（见 §4） |

### 3.3 地形接触（可选，谨慎）

`Settings.unitVoxelContacts = true` 之后，物理层会为单位生成**体素接触**（单位 AABB vs 方块盒的 MTV）。
**必须同时做二选一**，不能两份都做：

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| (a) 保留 `BlockWorld` 负责地形 | `voxelContactMask = 0`（即关掉物理层的地形接触）→ 阶段 2/3 只管单位-单位 | 台阶、贴墙滑动仍走原路径（**推荐**） |
| (b) 物理层接管地形 | 把 `physicsTick()` 里的 `blockWorld.move(box, ...)`（锚点搜索 `blockWorld.move(box, dx, dy, dz, d.onGround[i]`，本轮行号 2473）换成「只更新 box、不做地形解算」，地形交给物理层 | **台阶（step-up）会丢**：物理层的体素接触是 MTV，没有原版的抬升逻辑；要补一个「水平被挡 + 在地面 → 尝试抬 STEP_HEIGHT 再走」的等价逻辑才能上线 |

> 建议 **阶段 3 先只做 (a) + 休眠 + 层掩码**。等 (b) 的台阶方案想清楚再单独上。

### 3.4 休眠带来的可观测指标（真机看这些判断有没有生效）

| 指标 | 期望趋势 |
| --- | --- |
| `BroadphaseGrid.skippedBothAsleep()` | 静止编队时上升（说明配对被省掉） |
| `PhysicsWorld.sleepingSkippedIntegration` | 上升（说明积分被省掉） |
| `PhysicsWorld.slept` | 上线初期一次性上涨，之后趋于 0（该睡的都睡了） |
| `PhysicsWorld.wakeOnContact` | 战斗中持续 > 0（说明唤醒传播在工作，不是「一睡不起」） |
| `HordeProfiler` 的 `PHYSICS`/`PUSH` 段 | 静止编队时应明显下降 |

### 3.5 阶段 3 验收与回滚

```powershell
.\gradlew.bat physicsHarness --console=plain --offline   # [9] 站在方块上 / [10] 休眠状态机
```
- 真机：10k 单位静止 30 秒，看 `describeStep()` 的 `slept` 与阶段耗时；然后打一仗，确认被唤醒（不能出现「站着不动挨打不还手」）。
- 回滚：`physics.settings.allowSleep = false;`（一行，立刻退回阶段 2 行为）。

---

## 4. 配置项建议（下一轮加，本轮**不改** `HordeConfig`）

本轮这些开关只能通过 `PhysicsWorld.Settings`（构造时传入或事后改 `physics.settings.xxx`）或 `CrowdManager` 的私有字段来调。
下一轮建议加进 `HordeConfig.COMMON`（锚点：搜索 `pushStrength = b.comment(`，本轮行号 132 附近，紧邻现有物理项）：

| 建议配置名 | 类型 | 默认 | 对应字段 | 用途 |
| --- | --- | --- | --- | --- |
| `physicsEnabled` | Boolean | `true` | `CrowdManager.physics != null` | 物理层总开关（关掉 = 完全回旧路径） |
| `physicsContactSolver` | Boolean | `false`（阶段 2 上线后改 `true`） | 是否走 `stepOneTick()` | 接触求解替换 `CrowdSeparation` |
| `physicsFixedRateHz` | Int | `20` | `Settings.fixedRateHz` | 固定步长（与 tick 一致，一般不动） |
| `physicsSolverIterations` | Int | `4` | `Settings.solverIterations` | 顺序冲量速度迭代次数 |
| `physicsPositionIterations` | Int | `3` | `Settings.positionIterations` | split impulse 位置迭代次数 |
| `physicsMaxPositionCorrection` | Double | `0.2` | `Settings.maxPositionCorrection` | 单步位置修正上限（抖动/瞬移的平衡点） |
| `physicsAllowSleep` | Boolean | `true`（阶段 3） | `Settings.allowSleep` | 休眠总开关 |
| `physicsSleepThreshold` | Double | `0.005` | `Settings.sleepThreshold` | 能量阈值 |
| `physicsMinSleepTime` | Double | `0.5` | `Settings.minSleepTime` | 想睡多久才真睡（秒） |
| `physicsWakeOnContactSpeed` | Double | `0.2` | `Settings.wakeOnContactSpeed` | 接触唤醒的速度门槛 |
| `physicsUnitVoxelContacts` | Boolean | `false` | `Settings.unitVoxelContacts` | 物理层是否解算地形（阶段 3 方案 (b)） |
| `physicsCcdEnabled` | Boolean | `true` | `Settings.ccdEnabled` | CCD 总开关（投掷物） |
| `physicsBroadphaseCellSize` | Double | `2.0` | `Settings.broadphaseCellSize` | broadphase 基础格尺寸 |
| `physicsQueriesHitTriggers` | Boolean | `true` | `Settings.queriesHitTriggers` | 查询是否命中触发体 |

---

## 5. 风险与注意事项（诚实清单）

1. **行号会漂移**：本文写于另一个代理正在大改 `CrowdManager`（2393 → 3071 行）期间。**以锚点为准**。
2. **双份解算**是最容易踩的坑，共两处：
   - 阶段 1：不要调 `stepOneTick()`（否则地形被 `BlockWorld.move` 与物理层各解算一次）；
   - 阶段 2/3：`unitVoxelContacts` 与 `BlockWorld.move` 只能留一个。
   症状：贴地抖动、台阶上下抖、单位被挤进墙里。
3. **`PhysicsQuery` 不可重入**：不能在查询回调里再发起查询（内部状态在对象上复用，零分配的代价）。
4. **`LevelVoxelSource` 没有方块缓存**：`boxes()` 每次都走 `getBlockState` + `getCollisionShape`，
   非整格形状还会 `toAabbs()`（**会分配**）。所以只用于**低频**路径（投掷物扫掠、AI 视线）。
   上万单位每 tick 的逐格解算必须继续吃 `BlockWorld` 的那份每 tick 缓存。
5. **单位换算漏做的三种症状**：重力异常（20 倍量级）、半身位偏移、贴地 1 ulp 抖动。
6. **CCD 需要本子步起点**：物理层内部用 `stepX/Y/Z`（`integrate` 开头写入）。
   如果下一轮把投掷物**直接接进物理层的 CCD**（`configureProjectile`），注意运动学体的位置必须由上层驱动：
   物理层不会替它积分，它只负责「扫掠并报告命中」。
7. **`Contacts` 硬上限 `1<<20`**：触到会丢弃接触（`overflowed` 计数）。真机上这条路必须为 0，
   建议在 `describeStep()` 之外加一条告警日志（搜索 `overflowed`）。
8. **首次上线要看的一行**：`physics.describeStep()` 给出的
   `substeps / gridCells / pairs / contacts / impulses / posFix / ccd / clamp / woke / slept`
   —— 卡顿归因时先看 `pairs`（broadphase 是否爆炸）与 `posFix`（是否在处理大量穿透）。

---

## 6. 一次可复制的「阶段 1 最小接线」清单

| 步 | 文件 | 锚点 | 做什么 | 验证 |
| --- | --- | --- | --- | --- |
| 1 | `physics/PhysicsWorld.java` | `public VoxelSource voxels` | 加 `voxels()` 访问器 + `sweepVoxelsBox(...)` 薄封装（§1.3 末尾的代码） | `compileJava` |
| 2 | `crowd/CrowdManager.java` | `private BlockWorld blockWorld;` | 加 `physics` 字段 + `ensurePhysics` + `physics()`（§1.1） | `compileJava` |
| 3 | `crowd/CrowdManager.java` | `if (blockWorld == null) {` | 插一行 `ensurePhysics(level);` | 编译通过即可 |
| 4 | `crowd/CrowdManager.java` | `private boolean hasLineOfSight(float x0` | 整段替换（§1.2） | `physicsHarness` [3]/[4]；真机看薄墙后的骷髅 |
| 5 | `crowd/ProjectileManager.java` | `public static Adapter blockAdapter(` | 新增 `physicsAdapter(...)`（§1.3，**不动旧方法**） | `compileJava` |
| 6 | `crowd/ProjectileManagers.java` | `m.setAdapter(ProjectileManager.blockAdapter(world, 0.09F));` | 加一个按维度的 `PhysicsWorld` 缓存并改用 `physicsAdapter`（§1.3） | `projectileHarness` 全绿 + 真机 60 格/秒打墙 |
| 7 | `crowd/CrowdManager.java` | `if (!SkillParams.sweepHit(` | 换成 `Sweep.sphereCastCapsule`（§1.4 方案 A） | 真机打一仗：命中率/穿透弹行为无回归 |
| 8 | `crowd/CrowdManager.java` | `private int blastAt(` | 保持遍历与衰减，只把距离判据换成精确球-胶囊（§1.5 的保守写法） | 真机看爆炸范围是否与原来一致 |

每一步之后都跑：
```powershell
$env:JAVA_HOME='D:\java'; cd horde-mod
.\gradlew.bat compileJava compileHarnessJava physicsHarness --console=plain --offline
```
> 注意：本会话期间 `compileHarnessJava` 曾因**另一个代理正在改** `client/**` 而导致别人的 `ShaderHarness.java` 编译不过（找不到 `RigBaker`/`CrowdPose`），与物理层无关。
> 如果遇到这种整体失败，可以只验证物理层：
> ```powershell
> & 'D:\java\bin\javac.exe' -encoding UTF-8 -nowarn -cp build\classes\java\main -d build\phys-harness `
>     src\harness\java\dev\swordkingdom\horde\harness\PhysicsHarness.java
> & 'D:\java\bin\java.exe' -cp "build\phys-harness;build\classes\java\main" dev.swordkingdom.horde.harness.PhysicsHarness
> ```
