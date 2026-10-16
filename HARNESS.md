# horde 群集渲染：骨骼系统与验证工具

## 目标

群集（模拟）单位的**模型骨骼动作必须与对应的已注册真实实体完全一致**：
同一份模型代码、同一套动画输入、逐骨骼同角度。

代价是 CPU 每帧要驱动动画；为此加了与服务端物理相同的 LOD 节奏（近处每帧、远处每 2/4/8 帧）。

## 设计

### 1. 几何 + 骨架（`client/RigBaker.java` + `client/ModelRig.java`）

**部件系统抽象**：不同 mod 用的模型部件系统不一样，`ModelRig` 抽出一层接口。**不针对任何模组**，
而是「结构化发现 + 名称族分类 + 探针校验」：

| 适配器 | 覆盖 | 机制 |
|---|---|---|
| `VanillaRig` | 原版 + 绝大多数模组 | 只用**公开 API**（`visit` 路径还原层级、`getChild`、`resetPose`），零反射、最稳；命中优先 |
| `ReflectiveRig` | **任意**「部件对象自持角度/位置」的体系 | 见下 |

`ReflectiveRig` 的四个步骤，全部与具体模组无关：

1. **发现部件**：模型上任何「返回 `Iterable` 的无参方法」（`getAllParts()` / `getAllBones()` / `parts()` …）
   的元素，加上从模型字段出发的有限深度对象图遍历中**结构像部件**的对象
   （≥3 个「角度族」成员 + ≥3 个「位置族」成员）。
2. **识别层级**：优先 `getParent()`；否则用「元素确实是同类部件」的集合/映射字段（原版 `ModelPart.cubes`
   这种装立方体的字段会被排除）。
3. **分类成员**（按名称族，不绑定具体类）：
   - 位置：`x`（原版）、`posX`/`positionX`（基岩系）、`rotationPointX`（Citadel）、`pivotX` …
   - 旋转：`xRot`（原版）、`rotX`（基岩系）、`rotateAngleX`/`rotationAngleX`（Citadel）…
   - 位移：`offsetX`/`offX`/`translateX` …
   - 可见性：`showModel`/`visible`/`isVisible`/`hidden`/`isHidden`
   - 复位：名字含 `reset` 且含 `pose`/`default` 的无参方法
   - **位移的统一表示**：`位置成员相对绑定姿态的增量 + 独立 offset 成员` ——
     于是「写 `posY`」和「写 `offsetY`」两种习惯都能被正确当成平移。
4. **探针校验**：烘焙时用行走姿态跑一遍 `setupAnim`，每个轴在多个候选成员里改选
   **真正会动**的那个；命名习惯再怪也不会选错。

**自己那部分几何**（两种体系都覆盖）：

* 有「参数是 `PoseStack.Pose` + `VertexConsumer`」的方法 → 直接调用（Citadel 的 `doRender`、
  原版 `ModelPart.compile` 都是这个形状），只画自己的立方体。
* 只有「参数是 `PoseStack` + `VertexConsumer`」的渲染方法 → **临时摘掉子部件**再调用，
  画完挂回去（原版 `ModelPart.render`、基岩/GeckoLib 风格的自渲染部件都属于这类）。

配套：

* 每个顶点带骨骼索引；顶点着色器沿父链逐级旋转 + 位移，任意深度的嵌套部件都能跟随。
* **骨骼上限 64 根、父链上限 16 层、位移通道 16 条**。
* 绑定姿态 = 定义位置 + **零旋转/零位移**，采样得到的绝对角度由着色器一次性施加，不会重复叠加。
* 每种实体类型还会取该渲染器自己的局部变换：`renderer.scale()`（按**方法签名**反射，避开 SRG 名问题）
  与公开的 `getRenderOffset()`，作为 uniform 下发 —— 巨大的恐龙和微小的生物在群集里尺寸/偏移都与真实实体一致。

### 2. 动画 = 直接跑模型自己的 `setupAnim`（`client/CrowdPose.java`）

* 用**真实实体实例**（傀儡，绝不加入世界）喂给 `setupAnim`，输入完全对齐
  `LivingEntityRenderer.render`：行走相位/幅度、`tickCount`、`netHeadYaw`、`headPitch`、
  `attackAnim`、`hurtTime`、朝向、`setAggressive` 等。
* **动作/技能动画**（`action/ActionDriver.java`，对任何模组通用）：
  很多 mod（Citadel 系、Lionfish 系、以及**灾变 Cataclysm** 的 Ignis / Ender Guardian 等）的动作
  不是写死在 `setupAnim` 里的，而是由实体上的
  `getAnimation()/setAnimation()` + `getAnimationTick()/setAnimationTick()` + 动画清单
  （`getAnimations()` 或类里的 `public static final` 常量）选择。
  `ActionDriver` 只按**形状**发现这套 API（获取器/设置器成对、返回类型一致、清单来自返回数组的方法），
  群集单位攻击时把「第 k 个动作 + 已播 tick」写到傀儡实体上，于是 boss 的挥砍/砸地/魔法等
  动作动画会以**与真实实体完全相同**的方式播出来（`actionDurationTicks` 控制单次动作时长）。
* **动作的实际效果**：群集单位不是真实实体，跑不了 mod 自己的技能代码；这里用「**动作 = 技能**」的
  通用模型在**群集模拟内部**结算（`action/SkillParams.java`）：
  动作播到**命中帧**（`actionImpactFraction`，默认进度 50%）时，以该实体自己的 `ATTACK_DAMAGE`
  为伤害、以它的体型/近战距离推算范围为半径，对范围内敌人造成伤害并击退 ——
  目标的护甲走原版 `CombatRules.getDamageAfterAbsorb`，击退按目标击退抗性衰减、范围内按距离线性减弱。
  命中帧用的就是**客户端正在播的那个动作**（动作状态随快照同步），所以「看到的招式」与「打出的伤害」是同一件事。
  想逐个技能微调用 `actionRadiusScale` / `actionDamageScale` / `actionKnockback` / `actionIntervalTicks`。

每个单位在 CPU 上被一个**傀儡实体**驱动一次模型自己的 `setupAnim`，
输入完全对齐原版 `LivingEntityRenderer.render`：

| 原版 | 群集输入来源 |
|---|---|
| `walkAnimation.position(partialTick)` | 快照的行走相位（服务端镜像 `WalkAnimationState`） |
| `walkAnimation.speed(partialTick)`，clamp 1 | 快照的行走幅度 |
| `entity.tickCount + partialTick` | 快照的单位年龄（tick，客户端按快照插值） |
| `yHeadRot - yBodyRot` / `xRot` | 快照的头部偏航 / 俯仰 |
| `model.attackTime = getAttackAnim()` | 快照的挥动进度（= `swingTime / 挥动时长`，与原版 `updateSwingTime` 同义） |
| `hurtTime` | 快照的受击 tick 数 |
| `model.riding / young` | 恒 false（群集单位无骑乘/幼体状态） |
| `isAggressive()` 等实体状态 | 阵营标志（敌对） |

然后逐骨骼读 `xRot/yRot/zRot` —— 这就是模型自己算出来的姿态，因此与真实实体逐位一致。

### 3. GPU 侧（`client/CrowdRenderer.java`）

* 实例属性只有 6 个 float：位置 + (朝向, 阵营, 受击)。
* 骨骼角度走一张 **缓冲纹理（TBO，RGBA32F）**：每个实例 `uPoseStride` 个 texel
  （= 骨骼数 + 位移通道数 + 缩放通道数；xyz = 欧拉角，缩放/位移各占一个 texel）。
* 每种类型仍是一次 `glDrawElementsInstanced`；`uBonePivot[64]`（枢轴 + 父索引）是每类型 uniform。
* **投掷物渲染器（`client/ProjectileRenderer.java`）用同一份 GLSL**：它的顶点着色器就是这份字符串
  加两处注入（`uYawOffset` 声明 + 朝向/俯仰计算），所以**两边的 uniform 清单必须一致**。
  真机事故「上百枚箭一枚都看不见」的根因就是注入锚点随群集着色器改动而失效、
  `uYawOffset` 从未被声明 → `glGetUniformLocation` 返回 -1 → 整个渲染器自我停用。
  现在：注入走 `replaceOnceLoose`（锚点必须逐字命中，否则抛异常并打出锚点原文；
  空白漂移时按逐行去缩进兜一次）、uniform 逐个定位并**逐条列出缺失名字**、
  必需/可选分级（只有真必需才停用），离线由 `projectileHarness` 的
  「共用 GLSL 的两个渲染器 uniform 清单必须一致」用例钉死。
* 姿态纹理绑在 3 号纹理单元的 `GL_TEXTURE_BUFFER` 目标（原版只用 0/1/2 的 `GL_TEXTURE_2D`，
  目标不同、互不影响）。**不要改用 2D 纹理 + `glTexSubImage2D`**，原因见下方踩坑第 5 条。

### 4. 同步精度（`network/CrowdSnapshotPacket.java`，每单位 21 字节）

* 朝向、头部偏航/俯仰：byte（与原版同步实体朝向同样是 1/256 圈）；
* 行走相位 / 幅度：16 bit（相位抖动 ≈ 1.4e-4 弧度，幅度 1/65536）；
* 挥动进度：byte；受击：tick 数；年龄：tick 数（short，上限 32767 tick ≈ 27 分钟）。

## 离线验证（9 个 gradle 任务，都不进 jar）

```bash
# 1) 数学验证：不需要 GL，也不需要客户端单例
./gradlew harness

# 2) 通用部件适配器验证：两个命名风格完全不同的替身部件系统
./gradlew adapterHarness

# 3) 着色器验证：隐藏窗口里建 GL 3.2 Core 上下文，编译并渲染成 PNG
./gradlew shaderHarness     # 产物在 build/harness-shots/*.png

# 4) 同步链路验证：拆包 -> 真实 encode/decode -> 客户端拼装，一台不落
./gradlew syncHarness

# 5) 动作/技能动画验证：灾变那套「动画状态 + 计时 + 清单」的 API
./gradlew actionHarness

# 6) 技能结算验证：命中帧、范围（随体型放大）、伤害（原版护甲公式）、击退
./gradlew skillHarness

# 7) 碰撞分离验证：挤成一坨的单位必须被拆开，且不会互相穿透
./gradlew collisionHarness

# 8) 虚拟投掷物验证：弹道/扫掠命中/穿透/护甲/AoE/上限/快照打包 + **远程攻击模式**
./gradlew projectileHarness

# 9) 投掷物自动发现验证：同命名空间+名字词干+基类打分 + skeleton -> arrow 这条链
#    + 门槛/打分分离（末影龙/僵尸/苦力怕/蜘蛛/鸭子/鹅必须近战，且门槛不放行时打分一次都不跑）
./gradlew skillDiscoveryHarness
```

## GeckoLib（geo）动画：为什么必须**显式驱动**动画器（2026-10 修正）

真机日志：

```
[horde] geo 姿态采样：DuckRenderer 按形状找到渲染入口 GeoEntityRenderer.m_7392_(Entity,float,float,PoseStack,MultiBufferSource,int)
[horde] untitledduckmod:duck 的 geo 动画采样失败…：行走样本塌缩（2048 个样本完全相同，说明 GeckoLib 没有真的重算每帧动画）
```

**「靠渲染来驱动 GeckoLib 动画」这条路不成立**：`GeoModel.handleAnimations` 有节流
（`javap -p -c` 对着 `geckolib-forge-1.20.1-4.8.4.jar` 确认）：

```java
public void handleAnimations(T animatable, long instanceId, AnimationState<T> state) {
    // curTick = state.getData(TICK) 或 entity.tickCount
    // if (!manager.isFirstTick() && curTick == manager.getLastUpdateTime() && instanceId == lastRenderedInstance) return;
    // animTime += (curTick + partialTick) - lastGameTickTime;  ...
}
```

群集采样是**同一个模型实例**连续采几千帧，只要 `TICK` 没真的往前走，整帧动画都会被跳过 ——
于是 2048 个样本逐位相同。

> ⚠ **签名别记错（这里踩过两次，值得单独写出来）**：`long instanceId` 属于
> **`GeoModel.handleAnimations`**；而真正要调的 `AnimationProcessor.tickAnimation`
> **是 6 参、没有 long、double（tick）在下标 3**：
> ```java
> // software.bernie.geckolib.core.animation.AnimationProcessor<T extends GeoAnimatable>
> public void tickAnimation(T, CoreGeoModel<T>, AnimatableManager<T>, double, AnimationState<T>, boolean);
> public void preAnimationSetup(T, double);            // 只是 model.applyMolangQueries(animatable, tick)
> // software.bernie.geckolib.model.GeoModel<T> implements CoreGeoModel<T>
> public AnimationProcessor<T> getAnimationProcessor();
> ```
> 第一版实现把两者串在一起，写成「7 参、位置 1 long、位置 4 double」，于是真机上
> 一个入口都找不到（日志：`DuckModel 上按形状找不到动画处理入口`）。

现在 `client/GeoAnimator.java` 在**每次采样前显式调**（形状按名族 + 相对顺序判定，
**不写死任何下标**，也不认类名/包名）：

* 入口 = 在 **`getAnimationProcessor()` 返回对象**的类及其父类上找一个 `void` 方法：
  参数里**恰好一个 `double`**（tick）、名族含 `CoreGeoModel`/`AnimatableManager`/`AnimationState`
  各一个、末位 `boolean`，且**相对顺序 `AnimatableManager` < `double` < `AnimationState`**；
  若存在 `long` 就填 instanceId（可选，真实签名里没有）；
* 实参**按类型**逐个填（不看下标）：`double`=tick、`CoreGeoModel`=模型、
  `AnimatableManager`=`AnimatableInstanceCache.getManagerForId(instanceId)`、
  `AnimationState`=按形状新建、能收下动画宿主的形参=傀儡实体；
* 这条路上没有 `lastRenderedInstance` 节流，每次调用都真的重算骨骼；
* `tick` 必须是**单调递增的真实 tick**（不是渲染帧、也不是「第几次采样」）；
* 采样仍然会调一次该模组的渲染入口（保住它自己的 `setCustomAnimations` 等），
  且用**同一个 tick**，所以姿态不会被叠加两次。

成功路径固定打一行（真机直接 grep）：

```
[horde] geo 动画驱动：DuckModel 通过 getAnimationProcessor() 找到
AnimationProcessor.tickAnimation(GeoAnimatable,CoreGeoModel,AnimatableManager,double,AnimationState,boolean)
（long@-1、double@3），管理器 OK，已驱动 2088/2088 次 —— 采样成功
```

（`long@-1` 是正常值：真实 `tickAnimation` 没有 long 形参；下标全部由反射实测给出。
这一行的格式由 `GeoPoseTable.driveSummary` 一处合成，`geoHarness` 离线断言它。）

退化约定（**绝不出错误画面**）：拿不到管理器 / 驱动调用全败 → `GeoPoseTable` 直接判不可用，
原因里带上「驱动尝试 N 次、成功 M 次、最后失败：…」；样本仍然塌缩 → 判不可用，
原因里带上**判定塌缩的那一维、样本数、前两个样本的差异值（含哪个骨骼哪个分量）**；
两种情况下 `CrowdPose` 都退回**绑定姿态**（旋转 0、位移 0、缩放 1）。
`geoHarness` 的「geo 显式动画驱动」用例把这几条逐条钉住，并且**走的就是生产发现路径**
（`GeoAnimator.fromModel`）—— 判据只有那一份，harness 不再自己写一份形状知识。

## ★ 采样成功但「完全不动」：采样时钟的增量算错了（2026-10 修正，鸭子/鹅）

真机现象：日志里同时有「`行走 64 相位 × 时间 32`」的采样表、「`已驱动 2088/2088 次`」，
**玩家看到的却是完全静止**。

根因在 `GeoPoseTable.TimeSampler`：旧实现是

```java
void next() { wall++; }                                        // 每采一个样本墙钟 +1
int tickFor(int age) { base = wall - age; return wall + base; } // = 2·wall - age
```

于是 `ΔtickCount = 2·Δwall − Δage = 2 − Δage`。**只有「每个样本 age 恰好 +1」时才等于 Δage**，
而时间轴是以 `WALK_TIME_STRIDE = 5` 为步长采的 ⇒ `ΔtickCount = −3`：**动画时间在倒退**。
GeckoLib 的 `AnimationController.adjustTick` 是 `max(tick − tickOffset, 0)`（javap 确认），
倒退被夹到 0 ⇒ 一整列样本都停在动画第 0 帧；表看起来是二维、尺寸也对（所以塌缩检测与
时间维检测都没报），运行期却几乎是同一个姿态。

现在：

```java
int tickFor(int age) { /* tick = origin + age，且绝不倒退 */ }   // ΔtickCount == Δage（任何步长都成立）
```

并且**同一维度内 age 必须单调不减**，所以走表改成「时间外层、相位内层」采样
（相位维的 64 个样本共用同一个 age，时间维才逐档 +5；表的**存储布局仍是 [相位][时间]**），
每个维度/会话都是「先 `rewind()`（把时钟原点推到上一维度最大值之后）、再 `warmUp`（两帧同一 tick，
GeckoLib 就在这两帧记下 `tickOffset`）、然后按单调 age 采」——
于是「表里第 t 行 = 动画第 t·step tick」这条约定**真的成立**。

离线断言（`geoHarness`）：
* 「采样时钟契约」：age `[0,5,10,…]` → tickCount 必须**单调不减**且 **Δtick == Δage**，
  有效动画时间（按 GeckoLib 的 clamp 语义）必须覆盖多个不同的 tick；
  用例里还把**旧公式**复算一遍，断言它「不单调、且有效动画时间几乎全塌成 1 个值」——
  保证这条用例真的抓得住那个 bug；
* 「端到端：采样表 → CrowdPose 最终发射的骨骼旋转」：不同相位必须给出不同姿态、
  有表时不得等于绑定姿态、**相位固定只推时间姿态也必须变**（站着不动也要呼吸）、
  没表时确实走绑定姿态并给出原因。

**为什么离线 harness 之前没抓到**：harness 的采样源直接拿 `state.clock` 当动画时间用
（那条路是对的），**从来没经过生产的 `TimeSampler`** —— 这个 bug 在离线世界里不存在。
现在由 `GeoPoseTable.sampleTickSequence(int...)`（生产实现）把它暴露出来给 harness 断言。

## ★ 候选池扫描：真实 Level 优先（2026-10 修正，null Level 构造探针）

真机逐字：

```
java.lang.NullPointerException: … "LivingEntity.m_9236_()" is null
  at …l2library…ClientEffectCap.lambda$static$0
  at …/ProjectileCatalog.createProbe(ProjectileCatalog.java:221)   ← 我们传了 null Level
```

旧实现「**先** `create(null)`、失败再试真实 Level」；有些模组在实体构造路径上直接
解引用 `entity.level()`，异常被 Forge 事件总线打成 ERROR 堆栈（我们拦不住）⇒ 上百条 ERROR + 卡顿。

现在 `ProjectileCatalog.probeWith`（探针构造的**唯一实现**）：

1. 先拿真实 Level（`probeLevel()`：注入来源 → 服务端 `overworld` → 客户端 `level`），有就用它；
2. 只有真的拿不到 Level（或真实 Level 构造失败）才退回 `null`；
3. 仍然失败：**按类型只记一次**（DEBUG 一行、不带堆栈），计数汇进扫描汇总行。

扫描汇总（每次扫描一行）：

```
[horde] 投掷物候选池扫描完成：861 个实体类型，判出 161 个候选，耗时 42 ms（期间构造失败 3 个，已按类型归并记录）
[horde] 投掷物候选池**首次**扫描耗时 213 ms（861 个实体类型）—— 首次扫描会卡一下，之后走缓存，不会再重复扫描（已扫描 1 次）
```

池就绪后**不再重复扫描**：`scanAll()`（GUI 列表）也走缓存，`rangedGate`/`autoAttack` 走 `pool()`
的缓存；另有诊断计数器 `scanInvocations()` / `rescansWhileReady()` 供离线断言
（`skillDiscoveryHarness`：池就绪后再问 20 轮门槛/自动解析 + GUI 列表，扫描次数必须不变）。

## ★★ 「有静止动画、行为动画没有；鹅完全没有」：驱动时没把**相位/幅度**传进 AnimationState

时间轴修好之后（上一条），真机变成「位移随时间在变、旋转恒定，鸭子/鹅都没有行为动画」。
决定性证据来自**反汇编模组自己**（`javap -c` 对 `untitledduckmod-1.5.2` 的
`DuckEntity.predicate` 与 `GooseEntity.predicate`，两者**同形**）：

```java
float amount = state.getLimbSwingAmount();
boolean moving = !(amount <= -0.05f || amount >= 0.05f);   // 即 |amount| >= 0.05
controller.setAnimation(moving ? WALK_ANIM : IDLE_ANIM);
```

而 GeckoLib 自己的渲染路径（`GeoEntityRenderer.actuallyRender`，javap 确认）是这样喂这个值的：

```java
new AnimationState(animatable,
        living.walkAnimation.position(partialTick),   // 相位
        living.walkAnimation.speed(partialTick),      // 幅度 ← 模组的选择器读的就是它
        partialTick,
        isMoving);
```

**我们过去构造 `AnimationState` 时把 limbSwing / limbSwingAmount 一律填 0** ⇒ 控制器永远判为「没在走」
⇒ **所有样本（包括「走」那一档）都选了 IDLE_ANIM**：走表里根本没有走路姿态；待机维用同样的 0 采出来
与走表**逐位相同**，于是被判成「该类型不区分 limbSwingAmount」而弃用（真机日志里的「待机 无」就是这么来的，
**不是**模组没有待机动画）。运行期站着时读走表 = 同一套待机姿态在固定相位上取值 ⇒ 旋转恒定、只剩一点位移
⇒ 看起来不动（鸭子「有静止动画，但行为动画没有」，鹅与鸭子同因）。

修法（`GeoAnimator.Handle.drive`）：

* `drive(animatable, manager, tick, limbSwing, limbSwingAmount)` 新增两个入参，采样路径
  （`GeoPoseTable.sampleSource`）把 `state.limbSwing` / `state.walkAmount` 一起传进去；
* `newAnimationState` 按 GeckoLib 的口径构造
  `(animatable, limbSwing, limbSwingAmount, 0.0F, |amount| >= 0.05)` —— `partialTick` **仍然是常量 0**
  （否则 animTime 增量不再等于 age 增量）；`isMoving` 用「幅度非 0」当等价信号
  （群集采样里傀儡不位移，GeckoLib 原规则还要求实体水平速度超阈值）。

另外确认了 `AnimationController.setAnimation(RawAnimation)` 在**同一个动画**上是幂等的
（`!needsAnimationReload && rawAnimation.equals(currentRawAnimation)` 直接 return，不置 `shouldResetTick`），
所以「模组每帧都调 setAnimation」不会把动画按回第 0 帧；而**换**动画（走↔待机、动作维切换）时会重置
`tickOffset` —— 这正好落在我们每个维度「rewind → warmUp」的 warm-up 那一帧上。

运行期这一侧本来就是对的：`CrowdData.walkSpeed` 就是原版
`WalkAnimationState.update(min(moved*4, 1), 0.4F)`（`CrowdManager.updateWalkAnimation`），
与 GeckoLib 在真实实体上读的是**同一个量**，所以「站着 = 幅度 0 → 待机表」「走动 = 幅度 > 0 → 走表」
与模组自己的选择器一致。

## 采样表自审（决定性分叉，一行数字）

「表是废的」和「运行时查错表 / 把结果乘没了」必须一眼分开。烘焙成功后紧跟一行：

```
[horde] geo 采样表自审 untitledduckmod:duck：走表旋转|Δ|：时间 0.2400、相位 0.0000；
        走表位移|Δ|：时间 0.0100；待机表 33 行（旋转|Δ|时间 0.0600，位移|Δ|时间 0.0625）；骨骼 11
```

判读方法：

* **走表相位维旋转差异 ≈ 0** 说明该 GeckoLib 模型的走路姿态不是相位驱动（正常：动画按 animTime 走，
  相位只用来**选**动画）——此时靠时间维；若**时间维也 ≈ 0**，说明采样时幅度仍是 0（控制器选了待机）；
* **待机表「无」** ⇒ 待机样本与走表样本逐位相同（= 采样时 amount 没起作用）或采样失败；
* 运行期「动画诊断」行现在还会打印**用了哪张表**（`来源=geo 采样表[待机表（幅度 0）]`）与
  「1 秒内变化最大的骨骼」（不再挑到那根静止的骨骼而误报「完全没动」）。

`geoHarness` 的两条新用例（**旧代码必红**）用与鸭子同形的替身控制器
（`StubAnimationProcessor` 读 `state.getLimbSwingAmount()`，`|amount| >= 0.05` 选走/待机两条曲线）：

```
[ok] 幅度 1.0 必须让替身控制器判为「在走」（说明幅度真的传进了 AnimationState）—— 旧代码恒传 0，这里会是 false
[ok] 同 tick 下「幅度 1（走）」与「幅度 0（待机）」必须给出**不同**姿态（实际差异 0.652427）—— 旧代码两条曲线重合
[ok] 幅度 0、时间推进 20 tick 时姿态必须变化（实际差异 0.339754）—— 「站着不动也必须会动」
[ok] 幅度 1、相位从 0 推进到 1.6 时姿态必须变化（实际差异 0.699701）
[ok] **反例**：输入完全相同（同 tick、同相位、同幅度）时姿态必须逐位相同（实际 0.000000000）
```

端到端用例（采样表 → `CrowdPose` 最终发射的骨骼旋转）追加：

```
[ok] 幅度 0 必须走待机表（真机「站着不动」靠它）：实际 待机表（幅度 0）
[ok] 幅度 1 必须走表：实际 走表（幅度 1）
[ok] 同一时间下「待机（幅度 0）」与「走（幅度 1）」必须是不同姿态（实际 0.800000）
```

## 动画诊断：每类型每秒一行（真机排查「看起来不动」）

```
[horde] 动画诊断 untitledduckmod:duck：来源=geo 采样表（相位 0.375、幅度 1.000、t=116）；
        骨骼[3] 旋转=(-0.31, 0.00, 0.00) → 1 秒前=(-0.31, 0.00, 0.00)｜位移通道 2 个（…）｜本帧实例 40
        （**与 1 秒前完全相同**：这条就是「看起来不动」的直接证据）
```

* 明确写出**来源**：geo 采样表（带相位/幅度/时间输入）还是绑定姿态（带原因）；
* 给出**某一根骨骼**的旋转与**1 秒前**的对比（相同就是「没接上」的直接证据），位移通道也打出来；
* 只在「该类型真的有单位被画出来」时打，且每类型每秒最多一行；正常播放的类型只报前几次，
  之后只有**仍然没变**才继续报（那才是要排查的），避免刷屏。

同时把烘焙日志的「A 档：静止姿态，动画驱动待 B 档」这句**误导性文案**改成按事实写：

* 有表：`动画 = 该模组自己的骨骼（GeoLib 显式驱动，采样表：行走 64 相位 × 时间 32；待机 33 行）`
* 无表：`动画 = 绑定姿态（该类型不支持动画采样：<原因>）`

另外修掉同一条链上的一个潜伏 bug：`CrowdRenderer.uploadPoseBuffer` 的每实例 stride 漏了
`scaleCount`（写死 `bones + transCount`），于是**有缩放通道的类型**行偏移错位、缩放 texel 从没被写过。
现在 stride 与 `fillInstance`/着色器口径一致，并真的写入缩放 texel。

## 远程攻击模式：门槛与打分**彻底分开**（2026-10 修正：末影龙不许射箭）

真机日志：

```
[horde] 为 minecraft:ender_dragon 自动选中的投掷物：minecraft:arrow
        （161 个候选里最高 1050 分：同命名空间 1000 + 词干 0 + 基类 350 - 外来实体名 300 = 1050）
[horde] 类型 minecraft:ender_dragon -> 攻击模式 远程（距离 16.00 格，间隔 40 tick）
```

根因是**两条路并存且顺序不同**：类型登记（`CrowdManager.TypeStats`）直接调 `resolve()`（只打分，
**门槛根本没被问过**），而发射点用的是「先门槛、后打分」。

现在：

| 阶段 | 判据 | 代码位置 |
|---|---|---|
| **门槛（是否远程）** | 只认三条**证据**：`CrossbowAttackMob` / `RangedAttackMob` / **施法者字节码引用过候选池里的某枚弹** | `ProjectileCatalog.rangedGate` |
| **打分（选哪一枚）** | 分层打分只在「已经确定是远程」的类型内部排序：同名 3000 > 同命名空间 1000 > 词干 ≤500 > 基类 ≤400 − 外来实体名 ≤1200 | `ProjectileCatalog.breakdown` |
| **唯一入口** | 先门槛、后打分，写死在一个方法里；类型登记 / 发射点判定 / GUI 预览都走它 | `ProjectileCatalog.autoAttack` |

名字/命名空间/基类打分**绝不参与**「是不是远程」的判断 —— 这就是末影龙射箭的来源。
顺序本身也被钉住了：门槛不通过的施法者，`breakdown()` 的一次调用都不许发生
（`projectileCatalog.scoreInvocations()` 计数 + `skillDiscoveryHarness` 的断言）。

判定路径（`skillDiscoveryHarness` 逐个类型实测）：

| 类型 | 门槛 | 结果 |
|---|---|---|
| 末影龙 / 僵尸 / 苦力怕 / 蜘蛛 / 鸭子 / 鹅 | MELEE（无证据） | **近战** |
| 骷髅 / 流浪者 / 凋灵骷髅 | RangedAttackMob | 远程 -> `arrow` |
| 雪傀儡 | RangedAttackMob | 远程 -> `snowball` |
| 女巫 | RangedAttackMob | 远程 -> `potion` |
| 掠夺者 | CrossbowAttackMob | 远程 -> `arrow` |
| 烈焰人 | 字节码 new SmallFireball | 远程 -> `small_fireball` |
| 恶魂 | 字节码 new LargeFireball | 远程 -> `fireball` |
| 潜影贝 | 字节码 new ShulkerBullet | 远程 -> `shulker_bullet` |
| 凋灵 | RangedAttackMob | 远程 -> `wither_skull` |

## 远程攻击模式（2026-10 修正：骷髅不射箭）

### 病根

上一轮的「虚拟投掷物」在真机上**完全没有发生**。两条原因：

1. `CrowdManager.skillTick()` 把「施放节奏」与「动作编号」都挂在 `actionCount > 0` 上。
   `actionCount` 来自 `action/ActionDriver`（实体有没有 `getAnimation/setAnimation + 清单` 那套 API）。
   **原版骷髅没有这套 API** → `actionCount == 0` → 永远不进分支 → 永不发射。
2. `resolveCombat()` 只用**近战距离**（由碰撞盒宽度推出，通常 1~2 格）判定「攻击」，
   于是骷髅必须贴脸才算攻击，自然不会放箭 —— 而真实骷髅是在十几格外放箭。

### 现在的模型

| 环节 | 判据/行为 |
|---|---|
| **是不是远程单位** | 玩家在技能页签里配了投掷物，**或者** `ProjectileCatalog.resolve()` 按「原版基类 + 同命名空间 + 名字词干」自动挑出了一枚（`ProjectileCombat.isRangedAttacker`）。**完全不看动作数** —— 没有动画 API 也能是远程。 |
| **索敌** | 远程单位的索敌半径至少覆盖射程（`max(followRange, rangedDistance + 2)`），否则目标根本进不了视野。 |
| **攻击距离** | `TypeStats.attackReach` 对远程单位取 `max(近战距离, 远程距离)`；远程距离默认 16 格（`projectile.ranged.distance`），可按类型覆盖（`TypeOverride.rangedRange`）。 |
| **打谁** | `resolveCombat()` 现在按整个攻击半径扫空间哈希（不是原来的邻格 3×3）—— 3×3 格只有 6 格，够不到 16 格的射程。 |
| **远则放箭** | 目标距离 > 近战距离、有视线、冷却好了 → **朝目标**发射（带垂直分量，`ProjectileManager.fire` 的显式瞄准重载）。 |
| **近则近战** | 目标贴脸 → 走原来的近战结算，一个字段都没改。 |
| **不重复结算** | 两条路共用同一个 `CrowdData.attackCd`，并且按距离**互斥**进入 —— 同一次攻击只会走一条。`projectileHarness` 的「近战 + 远程并存」用例钉住了这条性质。 |
| **不每 tick 连发** | 发射后立刻把 `attackCd` 盖上（`max(period, rangedInterval, attackCooldownTicks)`）。 |
| **没有动画 API 时** | `actionCount == 0` 就不播动作，只按冷却发射（合成一个「只有命中帧」的动作）。 |

视线判定用 `BlockWorld.hasLineOfSight`（走样采样碰撞形状，步长 0.5 格，上限 256 步）。
采样拿不到方块世界时**退化为「不要求视线」并打一条限频 INFO**，绝不静默 ——
配置项 `projectile.ranged.requireLineOfSight`。

### 失效可见

* 登记实体类型时打一条 INFO，直接写出**攻击模式**（远程/近战、距离、间隔、投掷物来源、动作数）；
* 每次发射打一条**限频 INFO**（`projectile.logIntervalTicks`，默认 200 tick = 5 秒）：
  类型、单位槽位、投掷物、初速、方向向量、距离；
* 解析不出投掷物 / 写入失败 / 视线被挡 / 没有方块世界时打**限频 WARN**，并带上原因
  （`候选池为空`、`GUI 里设成了不发射`、`生成上限已满`…）。

### 打分：为什么 `minecraft:skeleton` 会选中 `minecraft:arrow`

权重分层（高 → 低）：

```
同名(3000) > 同命名空间(1000) > 名字词干(≤500) > 基类优先级(≤400) > 长度惩罚(≤50) - 「外来实体名」扣分(≤1200)
```

原版骷髅的候选池里**所有投掷物都在 `minecraft` 命名空间**，于是命名空间加分对谁都一样；
基类优先级让 `minecraft:dragon_fireball`（火球 400）压过 `minecraft:arrow`（箭 350）——
一个普通骷髅会去放龙的火球。所以新增了一条**通用**判据：

> 候选投掷物的 path 里若含有**另一个已注册实体**的 path 词干
> （`dragon` ⊂ `dragon_fireball`，而 `minecraft:ender_dragon` 在注册表里），
> 说明这枚弹是「那个实体的专属弹」，扣分（按词干长度 60/字，上限 1200）。

豁免：命中**施法者自己**的名字（`xxx:goblin` → `xxx:goblin_arrow`）不扣。
扣分上限 1200 仍然小于「同命名空间(1000) + 基类(≤400)」，
所以它只用来排**同一个命名空间内部**的先后，不会把外来的候选抬上来。

实测（`skillDiscoveryHarness`）：`arrow = 1350`、`dragon_fireball = 920`（不扣分时 1400）、
`snowball = 720` —— 选中的是 `minecraft:arrow`。

> 最后一条豁免刻意**没有**加：「候选名字等于自己」不算外来。
> 原版把投掷物**也注册成实体类型**（`minecraft:arrow`、`minecraft:fireball`、`minecraft:snowball`…），
> 如果豁免这一条，`minecraft:fireball` 就永远不扣分，于是它又会盖过 `minecraft:arrow` ——
> 这正是「骷髅放火球」的第二种形态（第一种是 `dragon_fireball`）。
> 这个坑是 `skillDiscoveryHarness` 打印逐项打分时抓出来的。
>
> **离线验证的边界（必须知道）**：`ForgeRegistries.ENTITY_TYPES` 在离线 harness 里
> 会以 `NoClassDefFoundError` 失败（Bootstrap 会连带拉起 Forge 网络常量），
> 所以这个用例里的「候选池」和「外来实体名判据」**都是注入的替身**
> （`ProjectileCatalog.setDryPool` / `setForeignPathCheck`），替身内容是 1.20.1 真实存在的那些
> 投掷物/实体 id。线上那条判据是否真的建起来了，看日志：
> 「投掷物打分已纳入『外来实体名』判据：注册表里共 N 个实体 path 词干」。

> 线上 mod 的 jar 是 SRG 名重混淆的，dev 环境的 MC 是官方名，**没法在 dev 里直接构造真实 mod 模型**
> （会 `NoSuchMethodError: RenderType.m_110458_`）。所以 `adapterHarness` 用两套
> **字段/方法名完全不同**的替身部件系统来验证通用适配器：
> Citadel 风格（`rotationPoint*`/`rotateAngle*`/`offset*`/`doRender(Pose)`）与
> 基岩/GeckoLib 风格（`pos*`/`rot*`/`render(PoseStack)` + 子部件可摘除）。
> 两者的动画结果都必须与模型自身渲染逐点一致（实测偏差 ~2e-6）。

`harness`：

1. 烘焙出的绑定姿态顶点 vs「旋转归零、缩放归一后按原版 PoseStack 遍历」—— 逐位一致（即使
   `setupAnim` 已经把位置/旋转/缩放弄脏）；
2. 用 `CrowdPose` 驱动 `setupAnim`，再用「父链旋转」（着色器同款公式）算顶点，与原版 PoseStack
   渲染同一姿态对比。用例覆盖**全部输入通道**：行走相位、行走幅度、年龄、头部偏航/俯仰、
   挥动进度、受击 —— 实测偏差 ~1e-7（只有矩阵浮点误差）；
   测试模型里刻意带了 `sin(age × 0.09)` 这类**年龄驱动的待机摆动**，以前完全丢失，现在必须一致。
3. 结构：骨骼数、最深链、40 根骨骼裁剪后顶点索引合法。

`shaderHarness`：用生产那份着色器源码编译链接、按同样的属性布局 + 姿态缓冲纹理（含位移通道）实例化绘制，
把 biped / 四足 / 蜘蛛画成 PNG；并**先把 `GL_PIXEL_UNPACK_BUFFER` / `GL_UNPACK_*` 弄脏**再渲染，
作为「上传路径不能被别的 mod 的全局 GL 状态影响」的回归测试。

## 实体碰撞（不再挤成一坨）

`crowd/CrowdSeparation.java`：位置层面的**最小平移分量**去穿透，相当于原版实体的碰撞推挤。

* 之前只有「速度冲量」，被 AI 往同一处推时单位会叠在一起 —— 冲量不解决重叠。
* 现在按各自碰撞盒把重叠沿 X/Z 中**重叠较小**的轴推开（最小平移，比随机方向稳定、不抖），
  推开量按「质量」反比分配：`质量 = 体积 × (1 + 4×击退抗性)`，所以大 boss 会挤开小怪。
* 只在**同一高度层**（Y 区间相交）的单位之间分离 —— 允许踩在别人头上（原版也允许）。
* 每次位移有上限（0.25 格），并**放在物理步之前**，所以被挤到墙里时后续碰撞解算会兜住。
* 合并了「完全重合」的处理：所有重合对**不能往同一方向推**（否则整坨一起平移、永远拆不开），
  改成按对编号散开 —— 这个坑是 `collisionHarness` 抓出来的。
* 配置：`collisionStrength`（默认 1.0，0 = 关闭分离）、`collisionPasses`（默认 2，密集时提高）。

## 数量上限

**默认不设限**：有多少单位就同步多少、就渲染多少。

* 一帧快照按单包 {@link CrowdSnapshotPacket#UNITS_PER_PACKET}（8000 只 ≈ 160 KB）**拆成多个包**，
  客户端按 `chunkIndex/chunkCount` 收齐后才提交这一帧，所以总数不受单包大小限制
  （原版自定义负载上限 1 MB 也不会碰到）。
* 服务端的收集缓冲 `outSlots` 会按需扩容，不会因为缓冲不够而少发。
* `snapshotRange`（配置键 `maxSyncDistance`）与 `syncBudgetPerPlayer`（配置键
  `maxSyncedUnitsPerPlayer`）默认都是 **0 = 不限制**；真被限制住时会打 WARN 并说明是哪个配置项，
  不会静默少渲染。
* 客户端侧全部数组都按在场数量增长（实例缓冲、姿态缓冲纹理按倍增扩容），
  上限只受 GL 的 `GL_MAX_TEXTURE_BUFFER_SIZE` 约束（实测 2.68 亿 texel，
  7 骨骼的模型约 3800 万只）。
* 带宽参考：10000 只 × 21 字节 × 10 Hz ≈ 2.1 MB/s；觉得高就把 `snapshotIntervalTicks` 调大
  （4 = 5 Hz）或设置 `maxSyncDistance`。

## 实体属性

服务端不再用僵尸常量，而是**按实体类型读真实属性**（`CrowdManager.TypeStats`，配置项
`simulation.useEntityAttributes` 默认开启）：

| 用途 | 来源 |
|---|---|
| 碰撞盒尺寸（物理/互推） | `EntityType` 的 dimensions（`entity.getBbWidth/getBbHeight`） |
| 移动速度 | `Attributes.MOVEMENT_SPEED`（再乘 `speedModifier` 倍率） |
| 生命值 | `Attributes.MAX_HEALTH` |
| 攻击力 | `Attributes.ATTACK_DAMAGE`，命中时按目标的 `ARMOR`/`ARMOR_TOUGHNESS` 走原版 `CombatRules.getDamageAfterAbsorb` |
| 攻击冷却 | `Attributes.ATTACK_SPEED` → `clamp(20/攻速, 2, 200)` tick |
| 近战距离 | 原版 `Mob.getMeleeAttackRangeSqr` 的公式（按双方宽度） |
| 索敌半径 | `Attributes.FOLLOW_RANGE` |
| 眼睛高度（瞄准/转头） | `entity.getEyeHeight()` |
| 击退抗性 | `Attributes.KNOCKBACK_RESISTANCE`（作用于互推） |

类型表上限也提到 **32 种**同时在场（快照里用 5 bit 编码类型索引）。
读不到属性的类型会打警告并退回配置兜底值。**仍未模拟**：附魔/药水/暴击/护盾等完整伤害管线
（群集单位不是真实实体，无法走 vanilla 的 `hurt` 流程）。

## 已知限制

* 只烘焙基础模型，不含附加图层（眼睛、羊毛、盔甲、鞘翅等 `RenderLayer`）。
* **技能效果是「通用近似」，不是 mod 原始技能代码**：群集单位的技能伤害/范围由它自己的属性推算
  （见「动作/技能」一节），不包含 mod 特有的弹射物、地形破坏、多段判定、召唤物等。
  要 100% 原样，只能让真实实体去跑 —— 群集模式下我们选择不这么做（会失去「一万只」的意义）。
* **只复现旋转与位移**：`setupAnim` 里对部件缩放的动画不生效（Citadel 里 86 个模型只有 1 个用 scale）。
* 部件系统支持原版 `ModelPart` 派生体系与任何「部件对象自持角度/位置成员」的自定义体系
  （Citadel `AdvancedModelBox`、基岩/GeckoLib 风格部件等）。
  仅当模型**由渲染器集中驱动骨骼、部件对象不自持姿态**、且渲染器又不是 `LivingEntityRenderer` 时
  才无法接入（例如 GeckoLib/AzureLib 的 `GeoModel` + `GeoEntityRenderer`）—— 这类会干净地跳过并打日志。
* 通用路径假设位置/位移成员的单位是 1/16（原版与所有基岩系模型的统一惯例）。
* 只喂给模型群集单位真实拥有的状态：骑乘 / 幼体 / 使用物品 / 游泳 / 蹲下这些状态群集单位没有，恒为默认值。
* 骨骼上限 64 根、父链上限 16 层、位移通道 16 条；超出会按前序裁剪并打日志。
* 姿态刷新按 LOD 分档（近处每帧、远处每 2/4/8 帧）——远处单位的动画刷新率低于真实实体，
  但服务端对它们的物理模拟同样降频。
* 伤害结算只做「攻击力 + 护甲减免」，没有附魔/药水/暴击等完整管线。
* 非 `LivingEntity` 实体仍只能用 `/horde real`。
* **远程单位与真实实体仍有差距**（按「离真实实体有多远」排序）：
  1. **不会走位保持距离**：真实骷髅会一边后退一边拉弓（`RangedAttackGoal` 里有
     `minAttackInterval` 与「太近就后撤」的逻辑）；群集单位只是停下、转身、放箭，
     被贴脸时切近战。要做到这一点需要在 `physicsTick` 里给远程单位一条「保持 preferred distance」的
     移动意图（可行，但会与流场寻路抢方向，需要单独设计）。
  2. **不会躲避**：真实骷髅会侧移躲箭/躲近战（`StrafeGoal`）。
  3. **箭不受「箭矢数量/蓄力」影响**：真实骷髅拉弓 1 秒、被干扰会放下弓、
     弹药用尽改用近战；这里是「冷却到了就发一枚」，初速固定（按投掷物基类默认值）。
  4. **不预测提前量**：真实实体的箭也是朝当前位置射（所以同样会打偏移动目标），
     但群集这边**没有**模拟「目标移动 -> 散布误差」这一层，等速移动的目标反而会被稳定命中。
  5. **投掷物与真实实体之间只有「伤害」这一条通路**：虚拟投掷物不会推开实体、
     不会触发 `ProjectileImpactEvent`、不会点燃/爆炸破坏地形（可以加，但那是另一个量级的工程）。
  6. **视线是走样采样**（0.5 格步长），不是原版 `ClipContext` 的精确体素射线：
     极薄的墙或恰好卡在采样点之间的方块可能被漏判（代价是每 tick 零对象分配）。

## 踩过的坑（都是「开发环境能跑、线上必炸」那类）

1. **`EntityType#getBaseClass()` 在 1.20.1 恒返回 `Entity.class`**（字节码里就是 `ldc Entity.class`）。
   用它判断「是不是生物」会把所有实体都拒绝 —— 这就是 `/horde spawn` 报
   「群集模式只支持生物实体」的原因。改用 `type.create(level) instanceof LivingEntity`。
2. **反射字段名在线上是 SRG 名**：`ModelPart.children` 运行时叫 `f_104213_`，
   reobf 不改字符串常量，所以按 `"children"` 反射必定失败。改成「按类型是 Map 且值是 ModelPart」定位。
3. **GL 3.2 上下文里 `GL33C.glVertexAttribDivisor` 可能没有函数指针**：
   MC 申请的是 3.2 Core，而该函数 3.3 才进核心；实测 AMD 驱动直接
   `FATAL ERROR ... JVM will abort`（**不可捕获**，try/catch 救不了）。
   现在先探测 `GLCapabilities`，能用 3.3 用 3.3，否则退回 `ARB_instanced_arrays`，都不行就干净地停用渲染。
4. **`level.getAllEntities()` 是实时视图**：`/horde clearreal` 边遍历边 `discard()` 会抛
   `ArrayIndexOutOfBoundsException`。改成先收集再删除。
5. **上传纹理时不能假设全局像素解包状态是干净的**：姿态数据最初用
   `glTexSubImage2D(GL_TEXTURE_2D, ..., FloatBuffer)` 上传，在干净上下文（离线 harness）一切正常，
   但在装了 300+ mod 的真实客户端里**首次绘制就整机崩溃**：
   `EXCEPTION_ACCESS_VIOLATION`，崩溃帧 `C [atio6axx.dll+...]` ← `GL11C.nglTexSubImage2D`。
   原因是别的 mod（如 ImmediatelyFast）会把 `GL_PIXEL_UNPACK_BUFFER` / `GL_UNPACK_ROW_LENGTH`
   等状态留着不还原：此时驱动会把「客户端内存指针」当成**缓冲区内的偏移**，
   再按 row-length 去读 —— 直接越界读到非法地址。
   现在姿态数据走**缓冲纹理 + `glBufferSubData`**，完全不经过像素解包路径；
   `shaderHarness` 里也专门加了一步「先把这些状态弄脏，再渲染」，作为这个 bug 的回归测试。
6. **同一段几何算了两次：眼高叠了两遍**（2026-10 远程攻击）。远程攻击改走「显式瞄准」重载时，
   `CrowdManager.launch()` 交出的炮口坐标**已经含了眼高**（`脚底 + eye×0.85`），
   而 `ProjectileManager.fire()` 又按原语义补了一次 —— 箭从 `64 + 1.62×0.85×2 = 66.75` 出射，
   比目标头顶（`64 + 1.95`）还高，于是**永远从目标上方掠过、永远不命中**
   （真机表现就是「有箭，但打不到人」）。现在 `fire()` 的约定写死为：
   `aimVelocity != null` 时 `casterY` 就是**炮口绝对坐标**，不再补眼高。
   这个 bug 是 `projectileHarness` 里「箭要命中 6 格外的目标」用例抓出来的 ——
   只验「箭有没有生成」的话，它会一直藏着。
7. **冷却取 max() 时把「播动作的间隔」也算进去了**。远程单位的发射冷却曾经写成
   `max(period, rangedInterval, attackCooldown, skillIntervalTicks)`，
   而 `skillIntervalTicks` 是**技能动画的施放间隔**（默认 120 tick），对没有动画 API 的骷髅毫无意义：
   实测症状是「200 tick 只放了一箭」（射速被从 2 秒拖成 6 秒）。
   现在只取 `max(period, rangedInterval, attackCooldown)`。
8. **日志本身可能不可用**。`LogUtils.getLogger()` 在离线 harness 里会抛异常，
   而「发射投掷物」这条链是要在 harness 里跑的：日志失败会把发射一起带崩
   （表现为「明明没异常逻辑，就是不发射」）。现在 `CrowdManager.logInfo/logWarn`
   把日志包在 try/catch 里 —— **日志绝不该影响模拟**。

