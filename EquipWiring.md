# 虚拟实体装备：接线说明（照抄即可）

本文件由「逐单位装备」子任务产出。**本任务只新建文件**，下面每一处都是「在哪个文件、哪个位置、插哪几行」。

新增文件清单见文末。

---

## 0. 一句话概括

装备的完整链路是：

```
服务端 EquipPalette（物品 → short 索引） + EquipData（每单位 6 个 short，SoA）
        │  EquipSync（调色板整表按版本发一次；逐单位索引走增量）
        ▼
客户端 ClientEquipData（同一套索引）+ EquipBaker（按「类型 + 装备组合」烘变体网格）
        │  EquipRenderer（每种变体一次 draw call，复用群集着色器）
        ▼
画面：护甲跟着主体骨骼一起动，手持物品刚性挂在手骨上
```

---

## 1. `network/HordeNetwork.java`（**必做**）

在 `register()` 里、`ProjectileTypesPacket` 那段 `.add();` **之后**追加下面 9 个注册
（id 用 7..15，与现有 0..6 不冲突；注意用的是当前的 `ch` 局部变量）：

```java
// ---- 虚拟实体的装备（调色板整表 + 逐单位索引，独立于群集/投掷物两条链路）----
ch.messageBuilder(EquipPalettePacket.class, 7, NetworkDirection.PLAY_TO_CLIENT)
        .encoder(EquipPalettePacket::encode).decoder(EquipPalettePacket::new)
        .consumerMainThread((msg, ctx) -> {
            DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.swordkingdom.horde.client.ClientEquipData.acceptPalette(msg));
            ctx.get().setPacketHandled(true);
        }).add();

ch.messageBuilder(EquipSnapshotPacket.class, 8, NetworkDirection.PLAY_TO_CLIENT)
        .encoder(EquipSnapshotPacket::encode).decoder(EquipSnapshotPacket::new)
        .consumerMainThread((msg, ctx) -> {
            DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.swordkingdom.horde.client.ClientEquipData.accept(msg));
            ctx.get().setPacketHandled(true);
        }).add();

ch.messageBuilder(EquipPackets.Request.class, 9, NetworkDirection.PLAY_TO_SERVER)
        .encoder(EquipPackets.Request::encode).decoder(EquipPackets.Request::new)
        .consumerMainThread((msg, ctx) -> {
            net.minecraft.server.level.ServerPlayer p = ctx.get().getSender();
            ctx.get().setPacketHandled(true);
            if (p != null) {
                dev.swordkingdom.horde.edit.EquipService.sendPalette(p);
            }
        }).add();

ch.messageBuilder(EquipPackets.ApplyUnit.class, 10, NetworkDirection.PLAY_TO_SERVER)
        .encoder(EquipPackets.ApplyUnit::encode).decoder(EquipPackets.ApplyUnit::new)
        .consumerMainThread((msg, ctx) -> {
            net.minecraft.server.level.ServerPlayer p = ctx.get().getSender();
            ctx.get().setPacketHandled(true);
            if (p == null) {
                return;
            }
            int n = dev.swordkingdom.horde.edit.EquipService.applyUnit(
                    p, msg.dimension, msg.unitId, msg.slots, msg.flags);
            HordeNetwork.sendToPlayer(new EquipPackets.Result(n,
                    "已装备单位 #" + msg.unitId + "（" + n + " 个）"), p);
        }).add();

ch.messageBuilder(EquipPackets.ApplyBatch.class, 11, NetworkDirection.PLAY_TO_SERVER)
        .encoder(EquipPackets.ApplyBatch::encode).decoder(EquipPackets.ApplyBatch::new)
        .consumerMainThread((msg, ctx) -> {
            net.minecraft.server.level.ServerPlayer p = ctx.get().getSender();
            ctx.get().setPacketHandled(true);
            if (p == null) {
                return;
            }
            int n = dev.swordkingdom.horde.edit.EquipService.applyBatch(p, msg);
            HordeNetwork.sendToPlayer(new EquipPackets.Result(n, "批量装备 " + n + " 个单位"), p);
        }).add();

ch.messageBuilder(EquipPackets.ApplyHeld.class, 12, NetworkDirection.PLAY_TO_SERVER)
        .encoder(EquipPackets.ApplyHeld::encode).decoder(EquipPackets.ApplyHeld::new)
        .consumerMainThread((msg, ctx) -> {
            net.minecraft.server.level.ServerPlayer p = ctx.get().getSender();
            ctx.get().setPacketHandled(true);
            if (p == null) {
                return;
            }
            int n = dev.swordkingdom.horde.edit.EquipService.applyHeld(p, msg);
            HordeNetwork.sendToPlayer(new EquipPackets.Result(n,
                    n > 0 ? "已装上（" + n + " 个单位）" : "没有装上去"), p);
        }).add();

// 右键拾取：服务端才算「打中了谁」；手里有物品就顺手装上，空手就回结果让客户端开面板
ch.messageBuilder(EquipPackets.Pick.class, 13, NetworkDirection.PLAY_TO_SERVER)
        .encoder(EquipPackets.Pick::encode).decoder(EquipPackets.Pick::new)
        .consumerMainThread((msg, ctx) -> {
            net.minecraft.server.level.ServerPlayer p = ctx.get().getSender();
            ctx.get().setPacketHandled(true);
            if (p == null) {
                return;
            }
            EquipPackets.PickResult r = dev.swordkingdom.horde.edit.EquipService.pick(p, msg);
            if (r.hit() && !p.getMainHandItem().isEmpty()) {
                int slot = dev.swordkingdom.horde.client.EquipClientHandler
                        .slotForStack(p.getMainHandItem());
                int mode = msg.batch ? EquipPackets.ApplyBatch.MODE_TYPE
                        : EquipPackets.ApplyBatch.MODE_ALL;
                dev.swordkingdom.horde.edit.EquipService.applyHeld(p, new EquipPackets.ApplyHeld(
                        msg.dimension, msg.batch ? -1 : r.unitId, mode, slot, false,
                        r.unitType, r.team));
            }
            HordeNetwork.sendToPlayer(r, p);
        }).add();

ch.messageBuilder(EquipPackets.PickResult.class, 14, NetworkDirection.PLAY_TO_CLIENT)
        .encoder(EquipPackets.PickResult::encode).decoder(EquipPackets.PickResult::new)
        .consumerMainThread((msg, ctx) -> {
            DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.swordkingdom.horde.client.EquipClientHandler.accept(msg));
            ctx.get().setPacketHandled(true);
        }).add();

ch.messageBuilder(EquipPackets.Result.class, 15, NetworkDirection.PLAY_TO_CLIENT)
        .encoder(EquipPackets.Result::encode).decoder(EquipPackets.Result::new)
        .consumerMainThread((msg, ctx) -> {
            DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.swordkingdom.horde.client.EquipClientHandler.acceptResult(msg));
            ctx.get().setPacketHandled(true);
        }).add();
```

> `EquipClientHandler.slotForStack(ItemStack)` 是**纯函数、不碰客户端单例**，所以服务端也能直接调
> —— 客户端与服务端用同一份「这件物品该装哪个槽」的判定，不会两边算得不一样。

---

## 2. `HordeEvents.java`（**必做**）

```java
// onServerTick，在 CrowdSync.tick(server); 之后
dev.swordkingdom.horde.network.EquipSync.tick(server);

// onLogout，在 ProjectileSync.forget(...) 之后
dev.swordkingdom.horde.network.EquipSync.forget(event.getEntity().getUUID());
```

---

## 3. `client/CrowdRenderer.java`（**必做；这是「装备显示出来」的关键一行**）

`render(RenderLevelStageEvent event)` 的结尾，也就是

```java
ProjectileRenderer.render(event.getCamera().getPosition());
renderedProjectiles = true;
```

这两行**之后**，加：

```java
EquipRenderer.render(event.getCamera().getPosition());
```

`EquipRenderer` 自带一份 GL 状态（VAO / 实例缓冲 / 姿态缓冲纹理 / 程序），
复用的就是 `CrowdRenderer.VERTEX_SHADER` / `FRAGMENT_SHADER` 那两份**同一个字符串**
—— 着色器一个字符都没改，`shaderHarness` 继续测同一份源码。

---

## 4. `crowd/CrowdManager.java` + `crowd/CrowdData.java`（数值层，可选但建议）

当前 `CrowdManager` 里读属性的地方是 `statsForSlot(slot)`，它只认「类型属性表」。
要让**装备的数值**（护甲/攻击力/攻速/击退抗性）真的生效，需要一张逐单位覆盖表：

```java
// CrowdManager 新增字段
/** 逐单位装备修正（长度 = capacity * 16；由 EquipService 通过 UNIT_STATS_HOOK 写入） */
public float[] unitEquipStats;
```

```java
// CrowdManager 构造函数里装一次挂钩
dev.swordkingdom.horde.edit.EquipService.UNIT_STATS_HOOK = (mgr, unit, v) -> {
    if (mgr.unitEquipStats == null || (unit + 1) * 16 > mgr.unitEquipStats.length) {
        return;   // 缓冲没准备好就跳过（下一次换装/重生会再来）
    }
    int b = unit * 16;
    mgr.unitEquipStats[b]     = v[0];   // armor
    mgr.unitEquipStats[b + 1] = v[1];   // armorToughness
    mgr.unitEquipStats[b + 2] = v[2];   // knockbackResistance
    mgr.unitEquipStats[b + 3] = v[3];   // attackDamage
    mgr.unitEquipStats[b + 4] = v[5];   // attackCooldownTicks
    mgr.unitEquipStats[b + 5] = v[6];   // moveSpeedScale
    mgr.unitEquipStats[b + 6] = v[7];   // maxHealthBonus
    mgr.unitEquipStats[b + 7] = v[8];   // weaponKind.ordinal()
};
```

```java
// statsForSlot 里叠加（未接线的槽位一个字节都没变）
private TypeStats statsForSlot(int slot) {
    TypeStats stats = statsOf(data.typeIndex[slot] & 0x1F);
    if (stats == null) {
        stats = statsOf(0);
    }
    if (stats != null && unitEquipStats != null && (slot + 1) * 16 <= unitEquipStats.length
            && unitEquipStats[slot * 16 + 7] >= 0.0F) {
        int b = slot * 16;
        stats = stats.withEquip(unitEquipStats[b], unitEquipStats[b + 1], unitEquipStats[b + 2],
                unitEquipStats[b + 3], (int) unitEquipStats[b + 4], unitEquipStats[b + 5],
                unitEquipStats[b + 6]);
    }
    return stats;
}
```

`TypeStats.withEquip(...)` 是**你的** `TypeStats` 上的一个小工厂方法，实现就是直接
`new TypeStats(...)`（25 参数那个构造器），把 armor/armorToughness/knockbackResistance/
damage/attackCooldownTicks 换成传入值、`moveSpeed` 乘 `moveSpeedScale`、`maxHp` 加 `maxHealthBonus`。
做到这一步之后，`EquipStats.Adjust.apply` 里那段**反射构造 TypeStats** 就可以删掉换成直接 `new`。

同时请在 `spawn(...)` 里按容量分配/扩容这张表：

```java
if (unitEquipStats == null || unitEquipStats.length < data.capacity * 16) {
    unitEquipStats = new float[data.capacity * 16];
}
```

---

## 5. `HordeConfig.java`（可选）

```java
// Common 里
public final ForgeConfigSpec.IntValue equipVariantCachePerType;
// builder.defineInRange("equipVariantCachePerType", 64, 4, 1024)
```

没接线时 `EquipBaker` 反射找不到就用默认 64，并在淘汰日志里提示，不会崩。

---

## 6. 入口（可选）

- 键盘/按钮开面板：`Minecraft.getInstance().setScreen(new dev.swordkingdom.horde.client.EquipScreen());`
  （`EditKeybinds` 里再加一个键位，或在 `CrowdEditScreen` 里加第 7 页签。）
- `EquipScreen` 的构造重载 `EquipScreen(dimension, unitId, unitType, team)` 可以预选一个单位
  —— `EquipClientHandler.accept(PickResult)` 就是这么打开面板的。

---

## 7. 右键的语义映射（最终版）

| 操作 | 结果 |
|---|---|
| 手持物品 + 右键 | 装到**射线命中的那一个单位**的「对应槽」（单个） |
| 潜行 + 手持物品 + 右键 | 装到**该单位所属实体类型的全部单位**（批量，服务端一次扫完） |
| 空手右键（命中） | 打开装备面板并预选这个单位 |
| 空手右键（没命中） | 什么都不做（不打扰正常右键） |
| 右键没命中 | 什么都不做 |

「对应槽」的判定顺序（客户端/服务端**同一份代码**）：
`Equipable.getEquipmentSlot()`（原版把护甲、盾、以及模组自定义可穿戴物统一在这里）
→ `ArmorItem.getEquipmentSlot()` → 主手。

---

## 8. 新增文件清单

| 文件 | 职责 |
|---|---|
| `crowd/EquipData.java` | 逐单位装备 SoA（每单位 6 个 `short` 索引 + 1 个 `byte flags`），零对象分配；含每维度注册表 |
| `edit/EquipPalette.java` | 物品 → `short` 索引的调色板（**按类型**维护，索引 0 = 空槽），含全局共享实例 |
| `edit/EquipStats.java` | 用原版 `ItemStack.getAttributeModifiers(slot)` 叠加护甲/韧性/攻击力/攻速/移速/生命；纯函数 |
| `edit/EquipProfile.java` | 「这件物品意味着怎么打」：弓/弩 → 远程、剑/斧/三叉戟 → 近战、盾 → 减伤 | 
| `edit/EquipService.java` | 服务端应用入口（单个/批量/手持/拾取），以及 `UNIT_STATS_HOOK` |
| `network/EquipPalettePacket.java` | 调色板整表（类型 + 索引 + 物品 id + 标记），按版本下发 |
| `network/EquipSnapshotPacket.java` | 逐单位 6 索引 + flags，自带拆包（`chunkIndex/chunkCount`），每单位 15 字节 |
| `network/EquipPackets.java` | 交互包：Request / ApplyUnit / ApplyBatch / ApplyHeld / Pick / PickResult / Result |
| `network/EquipSync.java` | 同步节奏（复用 `collectVisible`），首次与版本变化发整帧、之后只发变化过的单位 |
| `client/ClientEquipData.java` | 客户端索引镜像 + 调色板；版本不符时标记 `stale` 并回请整表 |
| `client/EquipMeshCache.java` | 通用 LRU 变体网格缓存（**不碰 GL**，可离线验证），每类型上限可配 |
| `client/EquipBaker.java` | 变体烘焙：宿主网格 + 护甲（原版 `HumanoidArmorLayer` 的 `HumanoidModel`）+ 手持物品捕获 |
| `client/EquipRenderer.java` | 变体的实例化渲染（每种组合一次 draw call，复用群集着色器） |
| `client/EquipScreen.java` | 装备面板：单个 / 批量（类型/阵营/全部/半径）6 个槽 |
| `client/EquipPick.java` | 右键事件 → 把视线发给服务端（命中判定在服务端） |
| `client/EquipClientHandler.java` | 回执处理：空手 → 开面板；有物品 → 提示；以及「物品该装哪个槽」的判定 |
| `harness/EquipHarness.java` | 离线验证（调色板 / SoA / 属性叠加 / 行为判定 / 拆包带宽 / 变体缓存） |
| `build.gradle` | 追加 `equipHarness` 任务 |
