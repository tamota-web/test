# 物理层设计文档（参照 Unity3D / PhysX）

> 交付范围：本文档 + 新包 `src/main/java/dev/swordkingdom/horde/crowd/physics/` + 离线验证 `physicsHarness`。
> **本轮不改任何集成点**（`CrowdManager` / `ProjectileManager` / `BlockWorld` / `CrowdData` / `CrowdSeparation` / `client/**` / `HordeConfig` 一个字都没动）。
> 接线方式见 [`docs/PHYSICS_WIRING.md`](PHYSICS_WIRING.md)。

---

## 1. 目标与约束

**目标**：把现在「逐单位速度积分 + 方块 AABB 解算 + 位置 MTV 去穿透」这套**特设（ad-hoc）拼装**的物理，
换成一个**按 Unity3D（其 3D 物理就是 PhysX）的组件模型与固定管线组织起来**的物理层：
固定步长、刚体字段化、碰撞体形状化、查询原语化、接触顺序冲量求解、高速体扫掠、休眠、确定性。

四条约束（决定了下面每一个设计选择）：

| 约束 | 含义 | 直接后果 |
| --- | --- | --- |
| **服务端权威** | 物理只在服务端跑，结果是唯一真相 | 必须**确定可复现**：固定步长、固定迭代顺序、不用跨平台不确定的数学函数 |
| **与现有代码零耦合** | 新包不 import `CrowdManager`，不依赖 `CrowdData` 的具体字段 | 可离线逐位验证；接线由对方下一轮完成（所以有独立的接线文档） |
| **阶段 1 必须真的能用** | 不是「先搭架子」 | 查询原语 + CCD 现在就能把投掷物命中与 AI 视线接管过去 |
| **不动物理之外的语义** | 不重新调手感，不改数值语义 | 提供 `Settings.vanilla(...)`：把原版「每 tick ×0.98 阻力 / 0.08 重力」**数学等价**地换算成 PhysX 的 `drag`/`gravity`（实测误差 ≤ 2.4e-7） |

## 2. Unity3D 概念 → 本项目实现 → 为什么（逐条映射表）

| # | Unity 概念 | 本项目实现 | 为什么 |
| --- | --- | --- | --- |
| 1 | `FixedUpdate` / `Time.fixedDeltaTime` | `physics/FixedStepClock`：主步 = 服务端 tick = 20Hz（`0.05s`）；`PhysicsWorld.stepOneTick()` 是权威路径 | 可变 dt 会让结果**依赖帧率**：同一跳跃在 30/144fps 高度不同；接触求解收敛速度随 dt 变；穿透修正量 ∝ 1/dt，dt 一抖修正量就抖。服务端更致命：dt 若与「这一 tick 实际花了多久」挂钩，同一串输入无法复现 |
| 2 | 子步（PhysX 内部细分） | `FixedStepClock.planSubSteps(最大位移, 0.5)` → 子步数只取 **{1,2,4,8}**，所以每子步 dt 只能是 `0.05 / 0.025 / 0.0125 / 0.00625` | 「固定步长」的本质是 **dt 只取离散档位**，不是「每帧调一次 dt」。档位由「本主步最大位移」（升序归约得出，确定性）决定：0.5 格小于任何单位半径，几何上不可能穿过对方，同时是 CCD 之外的第二道防线 |
| 3 | `Time.maximumDeltaTime` | `Settings.maxFrameTime = 0.25`（最多补 5 个主步） | 一次 2 秒卡顿若不封顶，服务端会试图补 40 步，卡顿被放大成更长的卡顿（死亡螺旋）。实测：`beginFrame(2.0f)` 只产出 5 个主步。代价是那段时间世界变慢 —— 对服务端权威模拟是正确取舍 |
| 4 | 累加器 + `accumulator/fixedDeltaTime` 的渲染插值 | `FixedStepClock.alpha()`，与客户端 `ClientCrowdData.alpha()`（`client/ClientCrowdData.java`）同语义 | 渲染侧只做插值：10Hz 快照 + alpha 线性插值，物理不参与渲染。服务端自己需要它的场合是「真实实体傀儡跟随虚拟单位」（同一 alpha 才能视觉对齐） |
| 5 | 权威 tick 为什么绕开累加器 | `PhysicsWorld.stepOneTick()` 直接跑一个主步 | 累加器有浮点欠账：实测 `0.02f + 0.03f = 0.049999997 < 0.05f` → 会推迟一步。服务端 1 tick = 1 主步是硬约定，绕开累加器就没有这个问题（`step(float)` 仍保留给「帧率 ≠ 物理频率」的场合） |
| 6 | `Rigidbody.mass` | `RigidbodySoA.mass[] / invMass[]`；`massFromEntitySize(width, height, knockbackResistance)` | 质量不是拍的：与原 `CrowdManager.pushEntities` 的 `sepMass` 公式**同形**（`max(0.1, w*h) * (1+4*min(1,kbRes))`），迁移前后「谁挤得动谁」不变。数据来源都是原版现成的：`EntityType.getDimensions()` 的 `width/height`、`LivingEntity` 的 `KNOCKBACK_RESISTANCE` 属性 |
| 7 | `Rigidbody.drag` | `drag[]`，PhysX 形式 `v *= 1/(1+drag*dt)`；`Settings.vanillaDragToPhysX(perTick, rate) = rate*(1/perTick - 1)` | 原版是「每 tick ×0.98」，PhysX 是连续线性阻尼。换算后**一个主步恰好等价一个原版 tick**：`1/(1+0.408163*0.05) = 0.98`。实测：81 个速度采样点最大误差 `2.38e-7` 格/tick（2 ulp），阻尼单步差值 `0.0`。不用 `v *= 1 - drag*dt`（`drag*dt > 1` 时会反向加速） |
| 8 | `Rigidbody.angularDrag` | `angularDrag[]`（默认 0.05，Unity 默认值） | 单位一律 `FreezeRotation`，角自由度不参与冲量求解，所以这里只有阻尼一项起作用 |
| 9 | `Rigidbody.gravityScale` / `useGravity` | `gravityScale[]`（0 = 无重力，等价 `useGravity=false`）；`Settings.gravityY = -32 格/秒²` | 原版 0.08 格/tick² × 20² = 32 格/秒²。实测换算值 `-31.999998`（0.08f 本身不精确，1 ulp），因此断言用「同一表达式逐位相等 + 容差等于 -32」两条 |
| 10 | `Rigidbody.isKinematic` | `kinematic[]`：`invMass=0`，不受重力/力/阻尼，但**会推别人**；虚拟投掷物用它 | 投掷物的运动由弹道公式（`SkillParams.integrate`）驱动，不该被接触求解改向；但它必须能撞到/被查询到 |
| 11 | `Rigidbody.constraints`（`FreezeRotation`） | `physics/RigidbodyConstraints`（位布局自定义，语义与 Unity 同名同义；单位默认 `UNIT_DEFAULT = FREEZE_ROTATION`） | 群集单位是直立胶囊，原版实体也不翻滚。关掉 3 个角自由度后接触求解只需线性冲量（3 自由度/单位），成本砍半，且不会出现「两个僵尸互推后原地打转」 |
| 12 | `Rigidbody.interpolation` | `physics/RigidbodyInterpolation`（`prevX/Y/Z` 供插值） | 真正需要的是「把固定步长的权威状态原样留着」；`EXTRAPOLATE` **刻意不实现**：外推会把「越界/穿墙」的位置当真值发出去，权威服务端不允许发出没解算过的位置（枚举值保留，让这个决定在代码里可见） |
| 13 | `Rigidbody.collisionDetectionMode` | `physics/CollisionDetectionMode`：`DISCRETE / CONTINUOUS / CONTINUOUS_DYNAMIC / CONTINUOUS_SPECULATIVE` | 单位（≤0.3 格/tick）用 `DISCRETE` 足够；**箭 3 格/tick = 60 格/秒必须 `CONTINUOUS_DYNAMIC`**（下面 §5 有实测对照：离散检测终点 x=12.0 已穿墙，CCD 落在 9.51）。`CONTINUOUS_SPECULATIVE` 当 `CONTINUOUS` 处理（阶段 1 不做推测式接触），差异写在这里而不是悄悄吞掉 |
| 14 | `Rigidbody.sleepThreshold` + PhysX wake counter | `sleepThreshold[]`（默认 0.005，能量 `0.5*(v²+ω²)`）+ `Settings.minSleepTime = 0.5s` + `awake[]/sleepTimer[]` + `wake()/sleep()` | 10k 单位里绝大多数在绝大多数 tick 里**站着不动**，却仍被积分、被塞进 broadphase 与求解器。休眠让它们彻底退出，把每 tick 成本压到与**活跃数**成正比。实测：静置第 10 个子步（= 0.5s@20Hz）入睡；入睡后 50 子步新增积分次数 **0**、跳过 53 次 |
| 15 | `Collider`（Box / Capsule / Sphere） | 单位 = 直立胶囊 `radius[]/height[]`（由实体 `width/height` 推导，`setCapsuleFromEntitySize`）；地形 = `VoxelSource` 的方块盒；查询 = `Sweep` 的球/盒/胶囊闭式解 | 胶囊两端是半球，最近点是**闭式**（线段-线段最近点），没有「圆柱端面 vs 侧面」的分支地狱；圆柱端面在「站在别人头上」时会数值退化。原版实体本就是圆角盒，胶囊反而更贴视觉 |
| 16 | `Collider.isTrigger` + `QueryTriggerInteraction` + `Physics.queriesHitTriggers` | `isTrigger[]` + `physics/QueryTriggerInteraction`（`USE_GLOBAL/IGNORE/COLLIDE`）+ `Settings.queriesHitTriggers` | 同一份射线代码，AI 视线希望「别被射程触发器挡住」，而「这一发能不能打到爆炸触发器」又希望命中它。做成全局开关一定会出现「某处悄悄改了全局值，另一处行为就变了」的幽灵 bug，所以它是**显式参数**。触发器进 broadphase（能被 Overlap 查到）但**永不产生接触**（`BroadphaseGrid.forEachPair` 的 `skippedTrigger`） |
| 17 | `Layer` + `LayerMask` | `physics/PhysicsLayers`：`TERRAIN / UNIT / PROJECTILE / TRIGGER / IGNORE_RAYCAST`（32 位掩码，`bit/mask/has/add/remove/describe`）+ `RigidbodySoA.layer[]` | 「谁能撞谁 / 谁能查谁」从散落的 if 变成三条掩码常量。实测：`TERRAIN` 掩码只命中方块、`UNIT` 掩码只命中单位、`TERRAIN|UNIT` 命中更近者（t 取最小）、`IGNORE` 跳过触发器 |
| 18 | Unity 里敌我也塞进 Layer（`Player`/`Enemy`） | 本项目**多一条与层正交的队伍轴**：`team[]` + `teamMask[]` + `PhysicsQuery.teamMaskExcluding(team)` | Unity 的阵营是**静态**的，所以塞进 Layer 没问题；本项目 `CrowdData.team` 是运行期可变的 0..31 字节（同一只僵尸可能换队）。于是层只表达**类别**，队伍表达**敌我**，两条轴各管一件事。实测：队伍 0 的查询用「除 0 之外」掩码打不到同队，队伍 1 用「除 1 之外」能打到队伍 0 |
| 19 | `Physics.Raycast` | `PhysicsQuery.raycast(origin, direction, out, maxDistance, layerMask, QueryTriggerInteraction)` | 与 Unity 一一对应（`direction` 不必归一化，内部归一化后 × `maxDistance`）。**这个语义有个陷阱**：传原始位移向量 + `maxDistance=1.0` 只会扫 1 格 —— 我自己的 CCD 第一版就踩了，所以 `linecast`/`hasLineOfSight` 显式算两点距离 |
| 20 | `Physics.Linecast` | `PhysicsQuery.linecast(x0,y0,z0,x1,y1,z1,...)` | 视线判定的自然形式；**它就是精确的视线**，取代 `BlockWorld.hasLineOfSight` 的固定步长采样 |
| 21 | `Physics.SphereCast` | `PhysicsQuery.sphereCast(...)` / `sphereCastTeam(...)` | 半径叠加等价：半径 R 的球沿 d 移动撞半径 r 的胶囊 ≡ 点沿 d 撞半径 R+r 的胶囊。一次胶囊 TOI 同时解决「零半径射线」与「有半径扫掠」 |
| 22 | `Physics.BoxCast` | `PhysicsQuery.boxCast(...)` | 对地形精确（Minkowski 外扩 + 线段求交）；对单位退化成外接球扫掠（保守，多报不漏报）。真实投掷物都是小方块/小球，常用的是 SphereCast，这个原语是为将来接真实实体傀儡准备的 |
| 23 | `Physics.OverlapSphere` / `OverlapBox` / `CheckSphere` | `PhysicsQuery.overlapSphere / overlapSphereTeam / overlapBox / checkSphere`（NonAlloc 风格，写进调用方数组） | 范围伤害/爆炸/射程触发都走它，不再各写一遍「按半径遍历格子」。返回**写入数**，实际重叠数在 `lastOverlapFound`（便于发现上限截断，实测：缓冲 2 个槽 → 返回 2、实际 4） |
| 24 | `Physics.DefaultRaycastLayers` | `PhysicsLayers.DEFAULT_RAYCAST` = 除 `IGNORE_RAYCAST` 之外的所有层 | 与 Unity 同义；调试可视化体不应该挡住 AI 视线 |
| 25 | `RaycastHit`（`point/normal/distance/collider`） | `physics/RaycastHit`：`pointX/Y/Z`、`normalX/Y/Z`、`distance`、`t`、`body`（-1 = 静态几何）、`blockX/Y/Z`、`layer`、`team`、`isTrigger`、`isVoxel` | 可变、可复用、零分配（Unity 的 `RaycastHit` 是 struct，Java 里最接近的等价物就是「调用方持有一个实例」）。多出的 `t` 是给 CCD 用的 TOI |
| 26 | 接触求解（PhysX 顺序冲量） | `physics/ContactSolver` + `physics/Contacts`：法向累积冲量夹取 ≥0、**摩擦锥** `|jt| ≤ μ·jn`、恢复系数阈值、slop、split impulse 位置修正 | **为什么不用现有的位置 MTV**（`CrowdSeparation`）：① 没有速度交换 → 掰开后被 AI 又推回来，反复拉扯看起来是「挤着抖」；② 没有摩擦 → 沿墙滑动/挤成一排不稳定；③ 质量语义不一致（MTV 按质量掰位置，冲量按 `invMass` 分配——同一件事的物理正确写法）；④ 无法与地形共用一条路径（静态几何就是 `a = -1` 的接触） |
| 27 | PhysX `contactOffset` | `Settings.contactOffset = 0.01` | 距离小于它就开始生成接触（推测式接触），避免「刚好贴上」时接触时有时无造成的抖动 |
| 28 | Box2D `linearSlop` / PhysX restart tolerance | `Settings.slop = 0.005` | 允许的**残留**穿透。没有它位置修正会每步去追一个永远追不到的 0，表现为持续微抖。实测收敛后残留正是 0.005（= slop） |
| 29 | split impulse / Baumgarte | `Settings.splitImpulse = true`（默认）+ `baumgarte = 0.2`（仅在关掉 split impulse 时使用） | 位置修正走**独立伪速度**通道，不污染真实速度 → **不产生动能**，这是「不抖」的根因。Baumgarte 把穿透塞进速度里，会凭空给物体加速（能量不守恒） |
| 30 | `b2_maxLinearCorrection = 0.2` | `Settings.maxPositionCorrection = 0.2` | 单步最多修正 0.2 格，防止「一坨重叠」被一次性弹飞（看起来就是瞬移） |
| 31 | `PhysicsMaterial.dynamicFriction/bounciness`（Combine = Average） | `friction[]/restitution[]`，生成接触时取两者平均；`Settings.unitFriction = 0.6`、`unitRestitution = 0` | 原版实体互推不弹（恢复系数 0）；摩擦锥用圆而不是「两切向各自夹取」（后者允许 |jt| 达到 √2·μ·jn，摩擦系数凭空大 41%） |
| 32 | `Physics.bounceThreshold` | `Settings.restitutionVelocityThreshold = 2 格/秒` | 恢复系数只在接近速度超过阈值时生效。否则一个球放地上会永远以极小速度弹个不停（经典静置抖动来源），也顺带让休眠成为可能 |
| 33 | `Settings.unitContactsXzOnly = true` | 接触法线 | 上下接触（`|ny| > 0.7`）**不生成接触** | 与原版 `Entity.push` 一致：只在水平面互推，**允许踩在别人头上**。真 3D 胶囊接触会把叠罗汉的单位竖直推开，那是原版没有的行为 |
| 34 | PhysX `PxContactSet` | `physics/Contacts`（SoA，法线从 A 指向 B，A 可为 `-1` = 静态几何） | 一坨 100 个单位能产生上千接触点，对象化会被 GC 拖垮。A = -1 让「踩在地上」和「挤着别人」走**同一条求解路径** |
| 35 | PhysX broadphase（SAP/网格） | `physics/BroadphaseGrid`（三维均匀网格 + CSR + 计数排序 + 访问戳去重） | 不用现有 `SpatialGrid` 的三个理由：它是**二维**的（站在 3 格高墙上的单位不该和地面单位配对）、按**中心**入格（半径跨格会漏）、配对顺序由遍历决定（物理求解顺序必须逐位可复现）。实测密集阵 1.0 次格子查询/单位/步、1.5 对窄相位/单位/步 |
| 36 | 单位制（Unity：秒/米） | 物理层内部 **秒 + 格**；`posX/Y/Z` 是碰撞体**中心** | 与 Unity 一致才能照抄它的公式；与 `CrowdData`（格/tick + AABB 最小角）之间必须显式换算。`Settings.vanilla(rateHz, gravityPerTick2, dragPerTick)` 把原版每 tick 常量换算成 PhysX 设置，让「迁移物理层」不必重新调手感 |
| 37 | 确定性（服务端权威必需） | 见 §5 | Unity 单机不在意「同一输入跨机逐位一致」；服务端在意：一旦不同步，只能整包状态回滚 |

## 3. 数据布局（SoA）

### 3.1 `RigidbodySoA`：一个字段一个数组

与 Unity `Rigidbody` 组件逐字段对齐（命名刻意保持一致，「Unity 里怎么写这里就怎么读」是可查表的，而不是靠注释维系的口头约定）：

| 分组 | 字段 | 类型 | Unity 对应 / 单位 |
| --- | --- | --- | --- |
| 位姿 | `posX/posY/posZ` | float[] | `transform.position`（**碰撞体中心**，格） |
| | `prevX/prevY/prevZ` | float[] | 插值用上一步位置（主步粒度） |
| | `stepX/stepY/stepZ` | float[] | 本子步起点（CCD 扫掠起点；PhysX 的 `previousTransform` 同职） |
| 运动 | `velX/velY/velZ` | float[] | `Rigidbody.velocity`（**格/秒**） |
| | `angVelX/Y/Z` | float[] | `angularVelocity`（rad/s；单位一律冻结） |
| | `forceX/forceY/forceZ` | float[] | `AddForce` 的累加器（积分后清零） |
| 质量阻尼 | `mass[]`, `invMass[]` | float[] | `mass`；`invMass = 0` 即无限质量（静态/运动学） |
| | `drag[]`, `angularDrag[]` | float[] | `drag` / `angularDrag` |
| | `gravityScale[]` | float[] | `gravityScale`（0 = `useGravity=false`） |
| 碰撞体 | `radius[]`, `height[]` | float[] | `CapsuleCollider.radius/height`（总高） |
| | `isTrigger[]` | boolean[] | `Collider.isTrigger` |
| 标志 | `kinematic[]`, `constraints[]`, `interpolation[]`, `ccd[]` | boolean/byte | `isKinematic` / `constraints` / `interpolation` / `collisionDetectionMode` |
| 休眠 | `sleepThreshold[]`, `sleepTimer[]`, `awake[]`, `allowSleep[]` | float/float/boolean/boolean | `sleepThreshold` + PhysX wake counter |
| 过滤 | `layer[]`, `team[]`, `teamMask[]` | byte/byte/int | `gameObject.layer` + 本项目扩展的队伍轴 |
| 材质 | `friction[]`, `restitution[]` | float[] | `PhysicsMaterial.dynamicFriction/bounciness` |
| 生命周期 | `alive[]`, `capacity`, `highWater` | boolean/int | 与 `CrowdData` 同语义（槽位复用 + 默认值复位） |

**槽位复用必须复位默认值**（`applyDefaults`）：一个单位死了、下一只生成在同一槽位，残值会让「上一只的尺寸/阻尼/速度」悄悄继承给新单位 —— 这类 bug 在真机上表现为「偶尔有单位一发疯」。

### 3.2 内存量级

每个 body 的字段合计 ≈ **132 B**（纯基本类型数组，无对象头）：

| 项 | 字节/单位 |
| --- | --- |
| 位姿 3×3 组（pos/prev/step） | 36 |
| 速度 3 组（线性/角/力） | 36 |
| 质量与阻尼 5 | 20 |
| 碰撞体 2 + trigger 1 | 9 |
| 标志 3 | 3 |
| 休眠 4 | 10 |
| 过滤 4（含 int teamMask） | 8 |
| 材质 2 + alive 1 | 9 |
| **合计** | **≈ 131~132** |

- 10k 单位 ≈ **1.3 MB**；50k ≈ 6.4 MB（对比：`CrowdData` 本身约 90 B/单位）
- `Contacts` 每个接触 ≈ **80 B**（4 个 int + 15 个 float）：20k 接触 ≈ 1.6 MB，100k ≈ 8 MB；另有 16 B/body 的伪速度与 touched 列表
- `BroadphaseGrid` ≈ 12 B/body + 4 B/格引用（每 body ≤ 27）+ 8 B/格：10k 单位密集阵（8112 格）≈ 1.2 MB
- **全部数组复用、稳态零分配**：`substep()` 里没有 `new`（`Contacts` 只在接触数超过当前容量时按 2 倍扩容，实测 200 单位挤在 4×4 区域也只扩容几次）

### 3.3 法线方向约定（全包唯一）

法线 `n` 从 A 指向 B：「把 B 沿 `+n` 推、把 A 沿 `-n` 推」。A 允许是 `-1`（静态几何：体素）。于是：

- 「单位-单位」与「单位-地形」在求解器里走**同一条** `solveNormal/solveFriction/solvePosition`；
- 接触点取两轴最近点的中点，穿透深度取生成时刻的值（迭代中不重算 —— 避免「位置一动深度就变」的反馈振荡，这是不抖的关键之一）。

## 4. 每步执行顺序

```
stepOneTick():                          // 一个服务端 tick = 一个主步（权威）
  dt_total = 1/fixedRateHz              // 0.05s
  maxDisp  = max(|v|) * dt_total        // 升序归约（确定性）
  sub      = planSubSteps(maxDisp, 0.5) // 只取 {1,2,4,8}
  dt       = dt_total / sub             // 固定档位之一
  foreach body: prev = pos              // 主步粒度的插值基准
  repeat sub times: substep(dt)

substep(dt):
  1 broadphase    grid.build()          // 三维均匀网格 + CSR；触发体也入网格（要能被查到）
                  grid.forEachPair()    // (i,j) 升序、访问戳去重、跳过「双方都睡」与触发体
  2 integrate     位置起点存 stepX/Y/Z   // CCD 的扫掠起点
                  v += g*gravityScale*dt          // 重力
                  v += force/mass*dt              // 累积力（Unity AddForce）
                  v *= 1/(1+drag*dt)              // PhysX 线性阻尼
                  p += v*dt                       // 按 constraints 逐轴冻结
  3 narrowphase   胶囊-胶囊（单位之间）   // 过滤层/触发体 → 唤醒传播 → 生成接触（XZ 法线，允许踩头）
                  （可选）单位-体素       // Settings.unitVoxelContacts，默认关（阶段 3）
  4 solver        顺序冲量 ×solverIterations：法向累积夹取 ≥0 + 摩擦锥
                  split impulse 位置修正 ×positionIterations（伪速度 → pos += biasV*dt）
  5 ccd           对 collisionDetectionMode != DISCRETE 且位移 > minSweepDistance 的 body：
                  从 stepX/Y/Z 扫到 pos（本地形 + 可选动态体），命中则钳到 TOI、
                  沿法线退 contactOffset、消掉法向速度、上报 CcdHit
  6 sleep         能量 ≤ sleepThreshold 且持续 ≥ minSleepTime → sleep()；睡着的不再积分
```

**两处与 PhysX 的刻意差异（必须写清，不能含糊）**：

1. **CCD 放在 solver 之后**。PhysX 里 CCD 在 narrowphase 之前（它要参与接触生成）。本项目 CCD 的使用者是**投掷物**：需要的是「命中时刻 + 命中点」，拿到就立刻上报伤害，而不是让它继续参与接触求解。放最后还有一个好处：求解器的位置修正可能把单位推进墙里，CCD 最后扫一遍正好兜住。
2. **broadphase 是「积分前」的表**。因为 CCD 在 integrate 之后跑，它看到的网格是积分**前**的位置。所以所有查询走廊按 `Settings.gridQuerySlack() = maxDisplacementPerSubStep + maxPositionCorrection + 0.05 = 0.75 格` 外扩。这**不是近似**：每个 body 在一个子步内最多移动 0.5（积分）+ 0.2（位置修正）格，外扩 0.75 保证候选集不漏（宁可多给，不可漏给）。

## 5. 确定性（服务端权威的硬要求）

| # | 手段 | 说明 |
| --- | --- | --- |
| 1 | 固定步长 + 离散档位 | 主步恒为 0.05s（由服务端 tick 驱动），子步 dt 只可能是 4 个固定值之一。dt 不是「负载的函数」，而是「最大位移的函数」，而最大位移又由升序归约得出 |
| 2 | 固定迭代顺序 | broadphase 配对按 `(i 升序, 该 i 的格子顺序, 格内 body 升序)` 发射，每个无序对**恰好一次**（访问戳 = i+1）；求解器按接触生成顺序迭代固定次数。**不做并行求解**：并行归约会改变浮点求和顺序，于是同一份存档在两个核心数不同的机器上会走出不同结果 |
| 3 | 只用确定性数学 | 只用 `+ - * /` 与 `StrictMath.sqrt`（fdlibm，跨平台逐位一致）；不用 `Math.sin/cos/atan2`（多数 JDK 走平台 intrinsics，不保证逐位一致）。唯一需要方向的地方（两个单位完全重合）用「由两个编号推出的确定性种子」，绝不随机 |
| 4 | 休眠不改变结果 | 能量阈值只是「要不要继续积分」的开关，而能量的定义就是速度的函数；所有可能改变速度的入口（施加冲量、受伤、接触、传送、AI 主动唤醒）都显式覆盖 `wake()` |
| 5 | 无哈希迭代顺序 | broadphase 是数组 + 下标，`PhysicsWorld` 里没有 `HashMap` 参与模拟 |

**实测**：200 个单位（LCG 造初值、挤在 4×4 区域内）× 100 子步，两次运行的状态摘要逐位一致
`525522715102a7b7`，累计配对 93269、累计接触 9789；把「谁占哪个槽位」反过来（打乱输入顺序）后摘要不同 —— 证明摘要真的对顺序敏感，上面的相等不是因为「什么都没算」。

## 6. 成本模型与量级估算

### 6.1 实测（`physicsHarness`）

| 场景 | 指标 | 实测 |
| --- | --- | --- |
| 10k 单位密集阵（100×100，间距 1.0 格） | 格子数 | 8112 |
| | 格子查询 | **1.0 次/单位/步** |
| | 窄相位测试 | **1.5 对/单位/步** |
| | 建表 + 配对耗时 | **1256 µs/步**（20 次重复均值，含 `build`） |
| 10k 单位稀疏（4000×4000 格） | 格尺寸自动放大 | 2.0 → **8.0** |
| | 格子数 | 744012（≤ 上限 1048576） |
| 48 格水平视线 | 体素访问 | **49 次**（旧实现是 96 次方块查询，且采样上限 24 点后更粗） |
| 垂直 4 格射线 | 体素访问 | 4 次（DDA 每格恰好访问一次） |
| 初始穿透 0.4 的两单位 | 收敛步数 | **1 步**（残留 0.005 = slop） |
| 收敛后 40 步 | 最大 \|vx\| / 位置漂移 | **0.0 / 0.0** |
| 站在方块地板上 | 脚底 y | 0.995（= 1 − slop），40 步位置变化 0.0 |

### 6.2 推算（明确标注为推算，不是实测）

- 每 tick 总成本 ≈ `broadphase（实测 1.26ms/10k）` + `窄相位与求解（正比于接触数）` + `查询（正比于查询次数）`。
  密集阵的接触数 ≈ 1.5 对/单位 → 10k 单位约 1.5 万接触；求解 4 次迭代 × 1.5 万接触 × (法向+摩擦) 属于几十万次浮点运算量级，远小于 broadphase 的建表成本。
- **休眠把成本从「总单位数」压到「活跃单位数」**：一个 10k 编制静止时，绝大多数单位在 10 个 tick 后入睡，之后的每 tick 成本主要是 broadphase 的建表（仍要扫描全部 body 的包围盒，O(n) 但极便宜）+ 活跃单位的求解。**（这条只有阶段 2/3 接线后才能在真机上量出真实数字，离线只验证了状态机本身。）**
- 查询侧：投掷物每 tick 一次扫掠（几十个体素访问），AI 视线每条 24~49 次体素访问，与旧采样实现同级或更好，且**精确**。

## 7. 与现有 `BlockWorld` / `CrowdSeparation` / `SpatialGrid` 的关系与迁移路径

### 7.1 `SpatialGrid`（`crowd/SpatialGrid.java`）

物理层**不用它**，自带 `BroadphaseGrid`。三条不用的理由（二维、中心入格、配对顺序由遍历决定）见 §2 第 35 行。
两者并存、互不影响：`SpatialGrid` 继续服务 AI 索敌/流场/现有分离，物理层的网格只服务接触与查询。
**阶段 3 之后再谈合并**（那时需要的是「一份既能给 AI 又能给物理的索引」，而这需要先把 AI 的查询也搬到物理层的查询原语上）。

### 7.2 `BlockWorld`（`crowd/BlockWorld.java`）

- `LevelVoxelSource` 是**同一份方块世界的另一个只读视图**（`VoxelSource.isSolid/boxes`），它逐条复刻了 `BlockWorld.compute` 的做法（整格走 `Shapes.block()` 快路径、多盒走 `toAabbs()`）。
- **为什么不直接复用 `BlockWorld`**：它的方块缓存（`lookup`）是**私有**的，对外只暴露「按原版轴序移动一个盒子」。所以：
  - 阶段 1 只把物理层用在**低频**路径（投掷物扫掠、AI 视线）—— 这些路径每 tick 的体素访问在几十次量级，不建缓存也可接受；
  - 上万单位每 tick 的逐格碰撞解算**继续吃 `BlockWorld` 那份每 tick 清空的缓存**（命中率极高，这是现在能撑住 10k 单位的原因之一）；
  - 阶段 3 若要接，正确做法是实现同一个 `VoxelSource` 接口并在内部复用那份缓存，**而不是另写一份**。
- 迁移期间地形行为**不变**：`voxelContactMask` 默认只含 `UNIT`，且 `Settings.unitVoxelContacts` 默认 `false` → 地形仍由 `BlockWorld.move` 负责，台阶（step-up）逻辑原样保留。

### 7.3 `CrowdSeparation`（`crowd/CrowdSeparation.java`）

阶段 2 由 `ContactSolver` 取代。行为差异清单：

| 维度 | 现有（位置 MTV） | 物理层（顺序冲量） |
| --- | --- | --- |
| 速度 | 不交换（只给一点经验性冲量） | 法向相对速度收敛到不接近（`vn_target = max(restitution, bias)`） |
| 摩擦 | 无 | 摩擦锥 `|jt| ≤ μ·jn`（沿墙滑动、挤成一排更稳） |
| 质量 | 按质量比例直接掰位置 | 体现在 `invMass` 上（同一套质量语义与击退共用） |
| 地形 | 完全分开（`BlockWorld` 一条路、MTV 另一条） | 静态几何是 `a = -1` 的接触 → 同一条求解路径（阶段 3 才启用） |
| 抖动 | 推开→推回反复拉扯 | split impulse 位置修正不产生动能；实测收敛后 40 步零漂移 |
| 收敛速度 | 单步可完全拆开（`MAX_STEP=0.25` 封顶） | 受 `maxPositionCorrection=0.2` 限制：下界 `ceil((pen-slop)/0.2)` 步（初始 0.4 格 → 实测 1 步收敛到 slop） |
| 顺序 | 按 `i` 升序（已是确定的） | 同样升序，且**配对本身**也保证唯一且升序 |

### 7.4 `CrowdData` 字段映射（接线时的换算表）

| `CrowdData` | `RigidbodySoA` | 换算 |
| --- | --- | --- |
| `posX/posY/posZ`（AABB 最小角） | `posX/posY/posZ`（中心） | `center.x = min.x + halfWidth`、`center.y = min.y + height*0.5`；写回反向相减 |
| `velX/velY/velZ`（格/tick） | `velX/velY/velZ`（格/秒） | `× / ÷ fixedRateHz`（20） |
| `onGround` | 由求解后 `vy` 与接触法线推导 | 阶段 2 用「向下速度被接触消掉」判据 |
| `horizontalCollision` | 由水平位移被削掉的比例或接触数推导 | 阶段 2 给出两种做法（见接线文档） |
| `typeIndex` → `statsForSlot` 的 `width/height` | `radius = width/2`、`height = height` | `setCapsuleFromEntitySize` |
| `statsForSlot` 的 `knockbackResistance` | `mass` | `RigidbodySoA.massFromEntitySize(...)` |
| `team` | `team` + `teamMask` | `teamMask = PhysicsQuery.teamMaskExcluding(team)`（只打敌人） |

### 7.5 真实实体（`crowd/RealEntities.java`）

后续接 CCD/查询的入口已经在 API 层准备好了：真实实体作为 `layer = UNIT` 的 body 放进同一个 `RigidbodySoA`（`configureUnit` + `setKinematic(true)` 由原版驱动位置），投掷物扫掠就会自动命中它们。**本轮未做**。

## 8. 分阶段落地计划

### 阶段 1：查询原语 + CCD（**已实现，已验收**）

- **目标**：投掷物命中与 AI 视线改走统一查询原语；60 格/秒的箭不隧穿。
- **已交付**：`FixedStepClock`、`PhysicsLayers`、`PhysicsQuery`（Raycast/Linecast/SphereCast/BoxCast/OverlapSphere/OverlapBox/CheckSphere + 队伍轴）、`Sweep`（线段-AABB/球/胶囊/移动 AABB 的闭式 TOI）、`VoxelSweep`（体素 DDA）、`RigidbodySoA`、`BroadphaseGrid`、`Contacts`、`ContactSolver`、`PhysicsWorld`、`VoxelSource`/`LevelVoxelSource`、枚举与常量。
- **要改的集成点（下一轮，本轮未做）**：`ProjectileManager.blockAdapter` / `ProjectileManager.step` 的命中判定；`CrowdManager.hasLineOfSight` / `blastAt` / `crowdHitResolver`。见接线文档 §1。
- **验收**：`.\gradlew.bat physicsHarness` → **129 项全绿**（本会话已跑通）。关键对照：离散检测终点 x=12.0（穿墙）vs CCD 落在 9.51 且 TOI `t = 0.375`（逐位等于手算值）。
- **回滚**：阶段 1 是只读路径，接线出问题只需把三处调用切回原实现（`BlockWorld.hasLineOfSight`、`SkillParams.sweepHit`、`blastAt` 的遍历）。

### 阶段 2：接触求解器替换 `CrowdSeparation`

- **目标**：单位之间从「位置 MTV」改成「顺序冲量 + 摩擦锥 + split impulse 位置修正」。
- **要改**：`CrowdManager.pushEntities()` 里 `CrowdSeparation.resolve(...)` 那一处调用（约 2545 行）+ 每 tick 的 SoA 同步（sync in / step / sync out）+ `configureUnit` 的参数来源。
- **验收**：现有 `collisionHarness`（一堆重合成一坨的单位必须被拆开）+ `physicsHarness` 的 `[8]`（初始穿透 0.4 → 收敛、40 步零漂移）+ 真机观察「挤成一坨是否拆开、是否还抖」。
- **回滚**：把调用切回 `CrowdSeparation.resolve` 一行。
- **为什么在这个顺序**：它改的是每 tick 的核心路径，风险高于阶段 1；而且它必须先有阶段 1 的查询/SoA 基础设施。

### 阶段 3：层掩码 + 休眠（+ 可选的地形接触）

- **目标**：静止单位退出积分与求解（性能），层/队伍掩码统一「谁能撞谁、谁能打谁」。
- **要改**：`settings.allowSleep` 与 `wake()` 的插入点（`damageUnit` 受伤、索敌成功、spawn/传送、AI 由静止转移动）；`unitVoxelContacts` 的取舍（**不能**与 `BlockWorld.move` 同时解算地形）；层分配表。
- **验收**：`physicsHarness` 的 `[10]`（静置第 10 子步入睡、睡着不积分、被推唤醒、`allowSleep=false` 不睡）与 `[9]`（站在方块上脚底 = 1−slop、40 步零下沉）；真机 10k 单位阶段耗时对比（`describeStep()` 的 `slept/wakeOnContact/skippedBothAsleep` 趋势）。
- **回滚**：`settings.allowSleep = false`（一行，立刻退回阶段 2 行为）。
- **为什么最后**：休眠改的是「什么会被跳过」，任何「该唤醒没唤醒」的疏漏都表现为偶发瞬移/不动 —— 必须在接触求解稳定之后再开，且必须在真机上观察一段时间。

## 9. 已知限制与未验证项（诚实清单）

1. **离线无法验证与真实地形的耦合**：`LevelVoxelSource` 只做了「整格/多盒」的转发，从未在真机（Forge 服务端）跑过。`hasLineOfSight` 的替换在真机上需要重新观察远距离薄墙的表现（语义从「采样」变成「精确」，行为会变，但不该变坏）。
2. **胶囊 vs 体素接触用「单位 AABB vs 方块盒」的 MTV**（`unitVoxelContacts`），与原版 `Entity.collide` 的**形状一致**，但**不做台阶（step-up）**。台阶仍由 `BlockWorld` 负责 → 阶段 3 若真要让物理层接管地形，必须先有台阶方案（否则单位会上不去 0.6 格台阶）。
3. **`boxCast` 对单位退化成外接球扫掠**；`sphereCast` vs AABB 在盒角是**保守**的（多报不漏报，方向是「宁可早停一格，也不穿墙」）。
4. **单位制是秒/格**：接线时必须做 `格/tick ↔ 格/秒` 的 20× 换算，漏做会表现为重力异常（放大/缩小 20 倍那个量级）。
5. **`PhysicsQuery` 不可重入**：不能在查询回调里再发起查询（内部状态在对象上复用，这是零分配的代价）。物理层只在服务端单线程跑，这条限制是有意为之。
6. **`Contacts` 有 `1<<20` 硬上限**：触到会丢弃接触并计入 `overflowed`，真机上这条路必须为 0（harness 已断言）。
7. **未做的部分**：角自由度冲量求解（单位一律 `FreezeRotation`）；关节/约束（Joint）；触发器事件回调（只有查询，没有 enter/exit 事件）；`CONTINUOUS_SPECULATIVE` 的独立实现（当 `CONTINUOUS` 处理）；真实实体的物理接入。
8. **未在真机验证**：本轮全部数字来自离线 harness（`physicsHarness`），没有跑过 Forge 客户端/服务端，也没有观察过 TPS/MSPT 影响。
9. **本轮验证方式的一个坑（如实记录）**：本会话中途 `compileHarnessJava` **整体**编译失败，原因是**另一个代理正在修改** `crowd/` 下的主源码，导致别人的 `src/harness/java/dev/swordkingdom/horde/client/ShaderHarness.java` 找不到 `RigBaker`/`CrowdPose`（与物理层无关）。为了取到数字，我用 `javac` 单独编译 `PhysicsHarness.java` 并对 `build/classes/java/main` 运行。**按用户要求，我没有为了让 gradle 变绿去改别人的文件。**
10. **`PhysicsWorld.stepOneTick()` 与 `substep()` 都会重建 broadphase**：目前没有增量/持久化 broadphase（PhysX 的 SAP 是增量的）。10k 单位实测 1.26ms/步，够用；若将来单位数再上一个量级，这里是第一优化点（可做「按 AABB 变化增量更新格子」）。

## 10. 附录：命令与实测结果

```powershell
$env:JAVA_HOME='D:\java'
cd horde-mod
.\gradlew.bat compileJava compileHarnessJava physicsHarness build --console=plain --offline
# 只要物理层的对照：
.\gradlew.bat physicsHarness --console=plain --offline
```

`physicsHarness` 的分节与关键数字（本会话实跑）：

| 分节 | 内容 | 关键实测 |
| --- | --- | --- |
| [1] | 线段 vs AABB（slab）逐位对照 | `t = 0.5 / 0.375 / 0.9375`、起点在盒内 `t=0` + 最小平移轴法线 |
| [2] | 线段 vs 胶囊（三段解析）逐位对照 | `t = 0.4375`（侧面）/`0.625`（端球）/`0.25`（擦过）/`0.40625`（球扫掠） |
| [3] | SphereCast / BoxCast / 视线 | 球扫掠 `t=0.375`、命中点 `x=1.5`；40 格外的 1 格厚墙必须挡住视线 |
| [4] | 体素 DDA | 垂直 4 格 = 4 次体素访问；48 格 = 49 次（旧采样 96 次） |
| [5] | **CCD 不隧穿** | 离散检测终点 `x=12.0`（穿墙）；CCD 落在 `9.51`、`t=0.375`、命中方块 `x=10`、法线 `-X`、法向速度清零；60 格/秒 × 6 tick 仍在墙前 |
| [6] | 端到端 | 60 格/秒飞 20 tick → `x=19.885`（墙在 20），14 次 CCD 钳制，首 tick 8 个子步 |
| [7] | 确定性 | 摘要 `525522715102a7b7` 两次一致；93269 配对 / 9789 接触一致；打乱顺序后不同 |
| [8] | 求解器收敛与稳定性 | 初始穿透 0.4 → 1 步收敛到 0.005；最大 `|vx| = 0`；漂移 0 |
| [9] | 体素接触（阶段 3 开关） | 脚底 `0.995`（= 1 − slop）；40 步零下沉 |
| [10] | 休眠状态机 | 第 10 子步（0.5s@20Hz）入睡；睡着新增积分 **0** 次；被冲量/接触唤醒；`allowSleep=false` 不睡 |
| [11] | 层 / 队伍 / 触发体 | 三条掩码语义与 `QueryTriggerInteraction` 全覆盖 |
| [12] | OverlapSphere / OverlapBox | 闭区间边界（半径和恰好等于中心距算命中）、升序输出、上限截断语义 |
| [13] | 固定步长 | dt 只能取 `0.05/0.025/0.0125/0.00625`；2 秒卡顿夹到 5 主步；80 格/秒 → 8 子步 × `0.00625` |
| [14] | 与原版逐 tick 语义对照 | 81 个速度采样点最大误差 `2.3841858e-7` 格/tick（2 ulp）；阻尼单步差值 `0.0` |
| [15] | 10k broadphase 成本 | 1.0 格查询/单位、1.5 配对/单位、**1256 µs/步**；稀疏场景自动放大格尺寸到 8.0 |

合计 **129 项检查全部通过**。
