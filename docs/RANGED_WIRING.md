# 远程攻击接线（原地开火 + 近战可达距离 + 投掷物伤害虚拟单位）

本文件是**战斗距离语义**这一段的接线说明，覆盖三件事：

1. **远程单位原地开火、永不近战**：按自身攻击模式决定站位（弹道射程内驻留射击、太近后撤、
   够不着才接近、没视线侧移）；门槛判远程的类型**不再进入近战分支**；
2. **近战可达距离由双方碰撞箱推导**，结算伤害前再判一次（真机反馈「手太长」的修复）；
3. **投掷物能伤害虚拟（群集）单位**：扫掠命中 → 护甲减免 → 扣血 → 击退 → 死亡/移除。

> 为什么不改 `build.gradle` 注册新的 harness 任务：`build.gradle` 不归我改。
> 所以**所有新用例都并进了现有** `projectileHarness`
> （`src/harness/java/dev/swordkingdom/horde/harness/ProjectileHarness.java`），
> 跑法不变：`.\gradlew.bat projectileHarness --console=plain --offline`。
> 如果将来要把它们拆成独立任务，下面「新增用例清单」给出了每个用例的方法名。

---

## 一、远程：原地开火、永不近战

### 1. 站位语义（2026-10 真机反馈后收紧）

玩家原话：「远程攻击需要在原地就能向远距离的目标释放对应的攻击，比如骷髅可以直接向极远的
目标直接发射箭矢，不会自己跑过去近战」。于是**站位带不再决定要不要挪动**：

```
d <= ballisticRange  →  HOLD / RETREAT   （弹道够得着 → 原地开火；太近才后撤）
d >  ballisticRange  →  APPROACH         （弹道都够不着 → 只能靠近）
没有视线（真的采样过）→  STRAFE_LOS      （侧移找视线，不顶上去贴脸）
```

- **只有弹道真够不着、或没有视线时才产生位移**；
- **远程类型永不进近战分支**（`resolveCombat` 里那条「贴脸切近战」的兜底已删除）。

### 2. 规则怎么来（`crowd/RangedKiting.java`）

理想站位**不是拍魔数**，而是从该类型那枚投掷物自己的弹道参数推出来的
（参数来自 `ProjectileCatalog.defaultsOf(kind)`，与 `ProjectileManager` 发射时用的是同一份）：

```
弹道射程    ballisticRange = min( 寿命水平位移, 有重力时 v²/g )    再夹到绝对上限 64 格
有效射程    effectiveRange = min( 寿命水平位移, 垂到「下垂预算」的时刻的水平位移 ) + 预算
最小开火距离 min          = max( 0.45 · rangedDistance, 近战距离 + 1.2 )
理想站位     max          = min( rangedDistance, max(min, effectiveRange) )   ← 仅诊断/日志
```

**`followRange` 不参与站位**：索敌半径是「能不能发现你」，不是「够不够得着你」。
（旧版把两者绑在一起，于是索敌半径小的远程怪明明够得着还往前走 —— 那是「远程跑过去」的一半成因。）

`ballisticRange` 对「箭 / 雪球 / 火球」这类**撞方块即消失**的弹取 `v²/g`
（垂直上抛的理论射程；水平射程必然 ≤ 它），因为撞墙才是它们的硬约束。
绝对上限 `BALLISTIC_RANGE_CAP = 64` 格是**唯一**安全阀（防止直射箭 180 格跨地图点射）。

实测三档（默认 `rangedDistance = 16`）：

| 弹 | 初速/重力/阻力 | 弹道射程 | 有效射程 | 站位带（诊断） | 间隔 | 提前量 |
|---|---|---|---|---|---|---|
| 箭 (ARROW) | 3.0 / 0.05 / 0.99 | 64.00（夹到上限） | 15.01 | 7.20 ~ 15.01 | 40 tick | 5 tick |
| 雪球 (THROWABLE) | 1.5 / 0.03 / 0.99 | 64.00（夹到上限） | 9.84 | 7.20 ~ 9.84 | 40 tick | 5 tick |
| 火球 (FIREBALL) | 0.6 / 0.0 / 1.0 | 48.00 | 16.00 | 7.20 ~ 16.00 | 40 tick | 5 tick |

**同一类型内按 id 哈希小幅错开**（`RangedKiting.Rule.standoffFor(slot)`，±15%），
错开只作用在 `max` 侧：`min` 必须全类型一致，否则最近的那一批会先被吃掉。

### 3. 移动意图（`CrowdManager.physicsTick`）

```
远程：d < min              → RETREAT    后撤（**倒着走**：yaw 仍朝目标）
      d <= ballisticRange  → HOLD       原地开火（不产生任何朝目标的位移）
      d >  ballisticRange  → APPROACH   只能靠近
      无视线（采样确认）    → STRAFE_LOS 侧移找视线
近战：直奔目标            → 与改动之前逐字一致
```

`RETREAT` 被方块挡住（`horizontalCollision && onGround`）时才允许迎上去。

### 4. 攻击节奏与瞄准（`CrowdManager.rangedAttackDefault` + `ProjectileCombat.decide`）

- **开火射程上界 = `max(rangedDistance, ballisticRange)`**（`RangedKiting.fireEnvelope`）：
  只要弹道真的够得着就该能射 —— 这就是「骷髅可以直接向极远的目标发射箭矢」。
  近战距离仍然照旧（低于它不射）；
- **弹速取真实值**：先按与 `launch()` **完全同一条顺序**解析出这一发要用的投掷物，再用它的 `speed` 做提前量；
- **提前量**：对移动目标做 4 次不动点迭代求交点，目标速度取 `pos - prev`；
- **视线**：`decide` 用「炮口 → 提前量瞄准点」这一段几何；拿不到方块世界时会同时关掉 `requireLos`
  （否则会拿一个没有证据的「被挡住」结论去驱动位移）；
- **射击时面向目标**：`faceYaw()` 把**身体朝向**也转向目标，再叠加 `aimHead()` 的头部偏移；
- **重力补偿（远距离打得中）**：见 {@link #5 重力补偿}；
- **攻击模式限频日志**（每个类型一次）：

```
[horde] 类型 minecraft:skeleton -> 攻击模式：原地开火（距离保持 [7.20,15.01] 格，弹道射程 64.00 格，
        有效射程 15.01），间隔 5 tick，提前量 5 tick（弹速 3.00 格/tick，重力 0.050，寿命 100，类别 ARROW）
```

逐发详情仍然只走 `debug.logLaunchDetail`（默认关）。

### 5. 重力补偿（「会发射」之外还要「打得中」）

原版骷髅的 `performRangedAttack` 就是**带下垂补偿的瞄准**：
`arrow.shoot(dx, dy + d * 0.2F, dz, speed, inaccuracy)` —— 水平距离 `d` 每远 1 格多抬 0.2 格。
本模组把它换成**由该弹自己的弹道参数解出来的量**（原版那个 0.2 是手感常数，换弹种就不对了）：

```
// ProjectileCombat.gravityCompensation（与 SkillParams.integrate 逐字同形的离散积分）
x = 0; y = 0; vx = speed; vy = 0
while (x < distance) { x += vx; y -= vy; vx *= drag; vy = (vy - gravity) * drag; }
comp = -y   // 负值 = 需要抬升多少格；抬升量加在**瞄准点的高度**上
```

为什么不能用闭式近似 `g·t²/2`：第一个版本就是这么写的，实测**打高了约 1 格**
（22 格处瞄点被抬 2.15 格、真实下垂只有 1.77 格），远距离命中率归零。
根因是「飞行时间」与「下垂」在这个积分器里不是同一个 t（水平方向每 tick 乘 `drag`）。

代入各弹种（本模组的默认参数）：

| 弹 | 5 格 | 8 格 | 10 格 | 15 格 | 22 格 | 30 格 | 原版常数 `0.2·d` 在 30 格 |
|---|---|---|---|---|---|---|---|
| 箭 (3.0/0.05/0.99) | 0.05 | 0.13 | 0.30 | 0.68 | 1.36 | **2.64** | 6.00 |
| 雪球 (1.5/0.03/0.99) | — | — | 0.42 | 1.05 | 2.44 | 4.90 | 6.00 |
| 火球 (0.6/0.0/1.0) | 0（直线弹，无需补偿） | | | | | | 6.00 |

实测对照（不补偿、平射到 30 格）：弹丸高度落到 **62.11**，而目标脚底是 **64.00** ——
整整低 1.89 格、**必然落空**；补偿之后 30 格命中率 **95%**（见用例表）。

**只在弹道射程内生效**：超出 `ballisticRange` 时 `rangedAttackDefault` 在 `decide` 之前就
`return false`，**不发射也不位移**（真机口径见 `rangedNeverMelees` 的 180 格档）。
解不出来时（抬升量 > `COMP_MAX = 24` 格）会夹住并把 `compClamped` 置位，
调用方**照原版语义仍然发射**，同时打一条限频日志说明「抬升量被夹到 24 格，可能打高或打低」。

**视线也沿真实弹道采样**（`losAlongBallistic`，步长 2 格、上限 40 点）：
补偿把瞄准点抬高之后，「炮口 → 瞄准点」那条直线整条都在弹道上方，
一段挡住直线的矮墙会被误判成「看不见」，于是远程单位对着空气反复侧移。

逐发详情仍然只走 `debug.logLaunchDetail`（默认关）。

---
## 五之补、命中判定改用**线段 × 碰撞箱**（远距离修好之后的第二层病根）

原判据是 `SkillParams.sweepHit`：**线段到球心**的距离 ≤ `hitRadius`（0.3）。
而一个原版怪是 0.6×1.95×0.6 的**长方体** —— 拿它的半宽当球半径，
竖直方向的判定范围就只有 0.3 格（真实半高 0.975）。后果是极其隐蔽的**按距离分段的命中/落空**：

```
近距离（≈6 格）：箭还在上升段，弹道比目标中心高约 0.5 格 ⇒ 打不到
远距离（≥12 格）：箭开始下垂、接近目标中心高度     ⇒ 又能打到了
```

真机表现就是「同样是箭，近的打不中、远的反而中」。现在改用
`ProjectileCombat.segmentHitsBox`（**slab 法**，线段 × AABB），判定范围就是单位真实的碰撞箱
（水平 = 投掷物半径 + 目标半宽，竖直 = ±身高/2，另加投掷物命中半径的外扩）。

---

## 二、近战可达距离（真机反馈「手太长」）

### 1. 缺陷与修法

**缺陷（改动前的真实机制）**：`TypeStats.attackReach` 对**远程类型**取
`max(meleeReach, rangedDistance)` = 16 格，而这个值**同时**被用作

1. `resolveCombat` 里「已有目标还值不值得打」的判定半径；
2. 近战分支的距离判定（旧代码只排除「贴脸」，远程判定失败时会落到近战分支）。

于是任何「登记时算远程、运行期判近战」的类型（门槛 `UNKNOWN → MELEE` 翻转、
或玩家把配置的投掷物删掉）都会带着 16 格的近战判定距离。此外 `attackReach` 是
**登记那一刻的 final 快照**，门槛在运行期翻转时它不会被重算 —— 这是矛盾的另一半。

**修法**：

```
近战可达（由双方碰撞箱推导）
  水平：中心距 <= (攻击者宽 + 目标宽)/2 + 0.5·(攻击者宽 + 目标宽)
  垂直：两个碰撞箱在 Y 方向必须有重叠
```

- 索敌半径（`attackReach` / `followRange`）**只**用于「发现 / 保持锁定」，可以很宽；
- **结算伤害前必须再过一次 `meleeRangeOk`**（`CrowdManager`），超出即可达距离 ⇒ 不结算；
- 日志按类型限频一行：
  `类型 X -> 近战范围 A 格（由碰撞箱推导：攻击者 w×h、目标 w×h；对同体型 B 格、对当前目标 A 格）`
- 诊断计数：
  `meleeRejectedByRange()`（被可达距离拒绝的结算次数）、
  `rangedMeleeAttempts()`（**远程类型**落进近战分支的次数，真机**必须恒为 0**）。

### 2. 各体型的可达距离（±0.01）

| 攻击者宽 | 目标宽 | 可达距离（水平中心距上界） |
|---|---|---|
| 0.6（僵尸/骷髅） | 0.6（僵尸/骷髅） | **1.20 格** |
| 0.6 | 0.4（幼体/小鸡级） | **1.00 格** |
| 0.6 | 1.4（铁傀儡/马级） | **2.60 格** |
| 0.6 | 4.0（巨人级） | **5.20 格** |
| 0.6 | 16.0（末影龙级） | **16.60 格** |
| 1.4（铁傀儡） | 0.6 | **2.60 格** |

> 对照：旧口径 `getMeleeAttackRangeSqr = sqrt(w·2·w·2 + w)` 只看**攻击者**体型
> （0.6 宽 ⇒ 1.79 格，与目标无关）。新口径对同体型给 1.20 格（比旧口径**更严**），
> 但对大体型目标会放宽 —— 这正是「由碰撞箱推导」与「按档位拍一个数」的区别。

---

## 三、投掷物命中并伤害虚拟单位

### 1. 扫掠命中（修掉 `pos - vel` 反推）

- `ProjectileData` 新增 `prevX/prevY/prevZ`：**上一 tick 的真实位置**，
  由 `ProjectileManager.step` 在积分**之前**写入，`ProjectileManager.fire` 在出射时初始化。
- `CrowdManager.crowdHitResolver` 一律读这三个字段做 `[prev, cur]` 线段扫掠。
  以前写的是 `pos - vel`，而 `vel` 到那时已经被重力/阻力积分、方块解算削峰、
  甚至撞墙清零改过了 —— 撞墙卡住的箭线段退化成一个点，沿墙滑动的箭线段比真实轨迹短。
- 粗筛用 `SpatialGrid`（**签名一个都没动**），精确判定用 `SkillParams.sweepHit`
  （线段到球心的最短距离，球心 = 单位中心 `pos + (halfWidth, height/2, halfWidth)`）。
- 地形：撞到实心方块由 `ProjectileManager.blockAdapter` → `BlockWorld.move` 的
  `horizontalCollision` / onGround 标志判定，`stickOnBlock=true` 的箭会插住并停止伤害。

### 2. 结算链

```
sweepHit 命中
  → 队伍过滤（team 相同直接跳过 = 同阵营不伤害，与近战/索敌同一口径）
  → damageUnit：护甲走 SkillParams.afterArmorFor → 原版 CombatRules.getDamageAfterAbsorb
  → hp -= applied；hurtTime = HURT_DURATION(=10)
  → 击退：速度方向 + SkillParams.knockback(base, 目标的 knockbackResistance)
  → hp <= 0 → queueDeath → flushDeaths → data.free(slot)
  → 命中数 > 0 → 不可穿透则投掷物本 tick 消失（pierce 就减 1）
```

- 真实实体那条路（`hurtRealEntities` 配置）**语义未改**；
- 每次结算的统计（**只计数，不参与判定**）：
  `CrowdManager.damageDealt()` / `sweepTests()` / `knockbackCount()` / `lastCandidateChecks()`。

### 3. 调用次数上界（硬要求）

`crowdHitResolver` 里的候选截断从「命中过一次才停」（`checked > MAX && hits == 0`）
改成**无条件** `checked > MAX_CANDIDATES(=64)` 就停。

> 为什么：旧条件意味着**一发打空的箭**会把整格扫完 —— 命中开销随「格子密度」增长，
> 那正是「100 单位 + 几十枚投掷物 = 每 tick 全量两两判定」的真实形态。

于是上界是一条可以写进断言的常数：

```
每 tick 命中判定调用次数 ≤ 投掷物数 × MAX_CANDIDATES(64)
100 单位 + 10 枚在飞 ⇒ ≤ 640 次/tick（全量两两判定是 100×100 = 10000 次）
```

---

## 四、离线用例（都在 `projectileHarness` 里）

| 方法名 | 验的是什么 | 实测数字 |
|---|---|---|
| `kitingRuleFromBallistics` | 站位规则由**弹道推导**：箭站得比雪球远、min > 近战距离、错开有界、四段式判定（射程内一律 HOLD） | 箭 7.20~15.01 / 雪球 7.20~9.84 / 火球 7.20~16.00，错开 ≤ 14.72% |
| `kitingKeepsDistance` | **20 骷髅 vs 20 僵尸、600 tick**：最小距离、贴脸 tick、后撤/驻留、近战事件、发射数、掉血 | 最小距离 1.50 格、**贴脸 0 tick**、后撤 136 tick、驻留 10566 tick、发射 301 枚、僵尸掉血 32.00、**近战 0 次** |
| `crowdTakeProjectileDamage` | 箭命中虚拟单位 → 扣血 → 箭消失；命中结算/精确扫掠/击退计数 | 命中结算 1253 次、精确扫掠 48 次、总伤害 192.00（每次命中 4.00）、僵尸掉血 192.00、击退 48 次 |
| `hitCandidateCallBound` | **每 tick 候选检查数 ≤ 64**（4 射手 vs **100 个挤在 4×4 的僵尸**） | 实测最大 21 ≤ 64；10 枚在飞 ⇒ ≤ 640 次/tick |
| `meleeReachGate` ★新 | 可达距离定义（各体型）+ **3 格外不得结算** + 可达距离内必须结算 + **索敌半径 48 格也不放行超距近战** | 0.6×0.6 → 1.20 / 0.6×0.4 → 1.00 / 0.6×16 → 16.60；3 格外被拒、1.0 格内结算 |
| `rangedFiresInPlace` ★新 | **15 格 / 30 格**目标下自身位移 ≈ 0、发射数 > 0、近战 0 | 位移 **0.00 格**、各发射 8 枚、近战 0、弹道射程 64.00 格 |
| `rangedNeverMelees` ★新 | 远程类型在 **0.9 / 2.4 / 12 / 180 格**下近战结算恒为 0、`rangedMeleeAttempts` 恒为 0；贴脸必须后撤 | 四档全部近战 0、落进近战分支 0；贴脸两档后撤 tick 20 / 17 |
| `gravityCompensationMath` ★新 | 补偿公式：0 距离/零重力为 0、随距离单调、30 格不触发夹取；**与真实弹道对照** | 10/22/30 格 → 0.30/1.36/2.64 格；不补偿时 30 格实测下垂 3.16 格（同量级），弹丸落到 y=62.11（目标脚底 64.00） |
| `rangedHitRateAtDistance` ★新 | **22 格 / 30 格静止靶各 20 发**，命中率 ≥50%、位移 ≈0；并跑「关掉补偿」的旧行为对照 | 22 格 **19/20 = 95.0%**、30 格 **19/20 = 95.0%**、位移均 **0.00 格**；关掉补偿：22 格 19/20、**30 格 0/20 = 0.0%** |
| `rangedHitRateConsistency` ★新 | **8 / 15 / 22 / 30 格**四档命中率不得随距离崩掉；最近档不得明显差于最远档 | 8 → 95.0%、15 → 95.0%、22 → 95.0%、30 → **95.0%**（全档位移 0.00） |

> 四档为什么从 8 格起：**7.20 格是最小开火距离**，比它更近时射手按攻击模式**后撤**
> （那是正确行为、已由 `rangedNeverMelees` 的贴脸档单独覆盖），会把「原地不动」这条断言弄脏。

### 既有用例的**数字变化**（语义没变）

- `rangedAttackMode`：200 tick 发射数 4 → **6**（远程单位不再被拉到贴脸而丢掉远程节拍），断言区间 4~8 未动；
- `meleePlusRangedNoDoubleCount`：贴脸 1.2 格时由「只走一次近战」改为
  **「既不远射也不砍」**（远程类型不再有贴脸抡弓那条路）；12 格仍然只走远程。

### 已知会在 harness 里看到的 WARN（不是失败）

`本维度还没有方块世界…视线判定暂时退化为「不要求视线」` ——
离线没有 `ServerLevel`，这是**显式退化**路径（线上有方块世界时不会出现）。

---

## 五、没有改动 / 刻意没做的部分

- `SpatialGrid`、`BlockWorld`、`CrowdSeparation`、`crowd/physics/**`、`client/**`、
  `HordeProfiler`、`HordeLog`、`HordeConfig`、`build.gradle`、`src/harness/**` 的既有文件
  —— **只看只调**，签名与行为未改。
- **门槛/打分/护甲公式的既有语义未改**，阈值对照：

| 判定 | 改动前 | 改动后 |
|---|---|---|
| 自动解析门槛（是否远程） | 先门槛后打分（唯一入口 `ProjectileCatalog.autoAttack`） | 同左，未动 |
| 打分分层 | 同名 3000 > 同命名空间 1000 > 词干 ≤500 > 基类 ≤400 − 外来名 ≤1200 | 同左，未动 |
| **近战可达距离** | `sqrt(w·2·w·2 + w)`（0.6 宽 ⇒ 1.79 格，**只看攻击者**）；远程类型甚至取 16 格 | **(攻击者宽+目标宽)/2 + 0.5·(攻击者宽+目标宽)**（0.6×0.6 ⇒ 1.20 格），**结算前再判一次** |
| **索敌半径** | 与近战判定距离**同一个变量**（`attackReach`） | 与「能不能打到」**彻底分开**：索敌可宽（followRange / 远程射程），结算另判 |
| **远程开火射程上界** | `rangedDistance`（默认 16，被 followRange 间接约束） | `max(rangedDistance, ballisticRange)`（默认 64 = 纯弹道射程，**不**被 followRange 夹） |
| **远程单位的近战** | 目标贴脸（≤ 近战距离）时切近战 | **永不进近战分支**（该兜底已删） |
| **远程站位** | 距离 > standoff 就接近（会一路顶着走） | 弹道射程内一律**原地开火**；只有够不着 / 没视线才位移 |
| **瞄准（远距离）** | 只瞄直线 + 水平提前量 ⇒ 22 格外开始落空 | **重力补偿**（按该弹弹道的离散积分解抬升量）⇒ 22/30 格 95% |
| **命中判定** | 线段到**球心** ≤ hitRadius(0.3)（竖直容差只有 0.3 格，近打不中远反而中） | 线段 × **碰撞箱**（slab 法，竖直 ±身高/2） |
| **视线判定** | 「炮口 → 瞄准点」一条直线 | 沿**真实弹道**采样（步长 2 格、≤40 点） |
| 护甲吸收 | `CombatRules.getDamageAfterAbsorb` | 同左，未动 |
| 命中候选截断 | `checked > 64 && hits == 0` | `checked > 64`（收紧为硬上界） |

---

## 六、如果要做成独立 harness 任务（留给 `build.gradle` 的所有者）

```
文件：src/harness/java/dev/swordkingdom/horde/harness/ProjectileHarness.java（既有文件，未新增文件）
方法：kitingRuleFromBallistics / kitingKeepsDistance / crowdTakeProjectileDamage /
      hitCandidateCallBound / meleeReachGate / rangedFiresInPlace / rangedNeverMelees /
      gravityCompensationMath / rangedHitRateAtDistance / rangedHitRateConsistency
```

这些方法已经挂在 `ProjectileHarness.main()` 里（`projectileHarness` 任务会跑）。
若要单独拆一个任务，只需在 `build.gradle` 里加一个 `JavaExec`，`mainClass` 指向
（新写的）入口类，并把上面几个方法搬过去即可 —— 生产代码不需要任何改动。
