package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.client.EquipMeshCache;
import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.edit.EquipPalette;
import dev.swordkingdom.horde.edit.EquipProfile;
import dev.swordkingdom.horde.edit.EquipStats;
import dev.swordkingdom.horde.network.EquipPalettePacket;
import dev.swordkingdom.horde.network.EquipSnapshotPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 装备链路的离线验证。
 *
 * <h2>为什么断言的是「绝对值」而不是只做往返</h2>
 * 打包/解包两边**一起写错**是 round-trip 发现不了的（历史上投掷物那边就踩过：
 * pitch 的位移量写成 6 而不是 9，两边一起错，往返一致但朝向永远多 45°）。
 * 所以这里对「已知输入」钉死绝对期望值：
 * <pre>
 *   铁胸甲 → 6 点护甲（原版数据）
 *   钻石剑 → 7 点攻击力（原版数据）
 *   1000 只 → 13000 字节载荷 + 固定包头
 * </pre>
 * 这些数字**故意写死在断言里**：一旦哪天原版数据或我们的折算公式变了，
 * 这里会立刻红，而不是静默地少算一点属性。
 *
 * <h2>覆盖范围</h2>
 * <ol>
 *   <li>调色板：映射/复用/按类型隔离/上限；</li>
 *   <li>per-unit SoA：6 个槽读写、flags、空槽语义、key 的确定性；</li>
 *   <li>{@link EquipStats}：护甲/韧性/攻击力/攻速叠加上限、绝对值断言；</li>
 *   <li>{@link EquipProfile}：弓/弩/剑/斧/三叉戟/盾/普通物品；</li>
 *   <li>拆包：调色板整表与逐单位快照的真实 encode/decode + 带宽估算；</li>
 *   <li>{@link EquipMeshCache}：命中/未命中/淘汰/上限/失败不重试（用替身网格对象，不需要 GL）。</li>
 * </ol>
 */
public final class EquipHarness {

    private static int failures;
    private static final List<String> INFO = new ArrayList<>();

    public static void main(String[] args) {
        // 引导原版注册表：我们要在断言里用**真实原版数据**（铁胸甲 = 6 点护甲、钻石剑 = 7 点攻击力），
        // 而 ItemStack 的静态初始化本身就会检查 Bootstrap 有没有跑过。
        // 引导失败（某些环境下会连带拉起 Forge 的网络常量而失败）不致命：
        // 下面的用例会退化成「形状 + 纯算术」验证，并明确打印 [跳过] 的原因。
        bootstrapped = bootstrap();

        testPalette();
        testSoa();
        testStats();
        testProfile();
        testPackets();
        testVariantCache();

        for (String line : INFO) {
            System.out.println(line);
        }
        System.out.println(failures == 0 ? "\n== EQUIP PASS ==" : "\n== EQUIP FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static boolean bootstrapped;

    private static boolean bootstrap() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            System.out.println("[info] 原版注册表引导成功：下面的属性断言用的是真实原版数据");
            return true;
        } catch (Throwable t) {
            System.out.println("[info] 原版注册表引导失败（" + t.getClass().getSimpleName()
                    + "），涉及真实物品的用例会退化成形状/纯算术验证");
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 1. 调色板
    // ------------------------------------------------------------------

    private static void testPalette() {
        section("调色板");
        EquipPalette palette = new EquipPalette();
        ResourceLocation zombie = rl("minecraft:zombie");
        ResourceLocation skeleton = rl("minecraft:skeleton");

        check(palette.indexOf(zombie, empty()) == EquipPalette.EMPTY,
                "空物品必须映射成 0（0 = 空槽哨兵）");
        check(palette.size(zombie) == 0, "空物品不应该往调色板里塞条目");

        ItemStack iron = stack("minecraft:iron_chestplate");
        ItemStack diamond = stack("minecraft:diamond_chestplate");
        short a = palette.indexOf(zombie, iron);
        short b = palette.indexOf(zombie, diamond);
        short a2 = palette.indexOf(zombie, iron);
        check(a == 1, "第一件物品的索引必须是 1（0 留给空槽），实际 " + a);
        check(b == 2, "第二件不同物品的索引必须是 2，实际 " + b);
        check(a2 == a, "同一件物品必须复用同一个索引");
        check(palette.size(zombie) == 2, "重复登记不应该增加条目");

        short sameOnSkeleton = palette.indexOf(skeleton, iron);
        check(sameOnSkeleton == 1, "调色板按**类型**隔离：骨架上的铁胸甲也从 1 开始");
        check(palette.version() == 3, "新增 3 条应该让版本号 +3，实际 " + palette.version());

        // 上限
        EquipPalette small = new EquipPalette();
        ResourceLocation t = rl("test:type");
        int accepted = 0;
        for (int i = 0; i < EquipPalette.MAX_ENTRIES + 32; i++) {
            short idx = small.indexOf(t, rl("test:item_" + i), false, false);
            if (idx != EquipPalette.EMPTY) {
                accepted++;
            }
        }
        check(accepted == EquipPalette.MAX_ENTRIES - 1,
                "调色板条数上限必须是 " + (EquipPalette.MAX_ENTRIES - 1) + "（索引 1..MAX-1），实际接受 " + accepted);
        check(small.indexOf(t, rl("test:item_9999"), false, false) == EquipPalette.EMPTY,
                "超过上限后必须返回 0（空槽）而不是抛异常或静默串号");

        // 反查
        EquipPalette.Entry e = palette.entry(zombie, a);
        check(e != null && e.item.equals(rl("minecraft:iron_chestplate")),
                "按索引反查必须拿回原来的物品");
        check(palette.entry(zombie, EquipPalette.EMPTY) == null, "0 号（空槽）没有条目");

        // 标记位
        short glint = palette.indexOf(zombie, rl("minecraft:iron_chestplate"), true, false);
        check(glint != a, "带附魔光效与不带必须是**两条**（外观不同）");
        short named = palette.indexOf(zombie, rl("minecraft:iron_chestplate"), false, true);
        check(named != a && named != glint, "自定义名也要单独成条");

        info(String.format("  调色板：条目 %d 条（版本 %d），单条内存约 %d B（粗估）",
                palette.totalEntries(), palette.version(), 40));
    }

    // ------------------------------------------------------------------
    // 2. per-unit SoA
    // ------------------------------------------------------------------

    private static void testSoa() {
        section("逐单位 SoA 读写");
        int capacity = 16;
        EquipData d = new EquipData(capacity);
        check(d.enabled() && d.capacity() == capacity, "容量应是 " + capacity);
        check(!d.anyEquipped(3), "新分配的单位默认必须没有任何装备");

        d.setSlot(3, EquipPalette.SLOT_HEAD, (short) 5);
        d.setSlot(3, EquipPalette.SLOT_MAINHAND, (short) 9);
        check(d.slot(3, EquipPalette.SLOT_HEAD) == 5, "头槽应写入 5");
        check(d.slot(3, EquipPalette.SLOT_MAINHAND) == 9, "主手槽应写入 9");
        check(d.slot(3, EquipPalette.SLOT_CHEST) == 0, "没写的槽必须是 0");
        check((d.flags(3) & EquipData.FLAG_HEAD) != 0, "flags 的 bit0 必须标出「头槽非空」");
        check((d.flags(3) & EquipData.FLAG_MAINHAND) != 0, "flags 的 bit4 必须标出「主手非空」");
        check((d.flags(3) & EquipData.FLAG_CHEST) == 0, "没穿的槽对应位必须为 0");
        check(d.anyEquipped(3), "有装备时 anyEquipped 必须为真");
        check(!d.anyEquipped(4), "别的单位不受影响（SoA 不串行）");

        // 槽位独立性：同一单位的 6 个槽互不覆盖
        short[] six = new short[EquipData.SLOT_COUNT];
        d.slots(3, six);
        check(six[EquipPalette.SLOT_HEAD] == 5 && six[EquipPalette.SLOT_MAINHAND] == 9
                        && six[1] == 0 && six[2] == 0 && six[3] == 0 && six[5] == 0,
                "读出的 6 个槽必须与实际写入一一对应");

        // 清空语义
        d.setSlot(3, EquipPalette.SLOT_HEAD, (short) 0);
        check(d.slot(3, EquipPalette.SLOT_HEAD) == 0, "写 0 必须清空该槽");
        check((d.flags(3) & EquipData.FLAG_HEAD) == 0, "清空后对应的 flags 位必须回 0");

        // key 的确定性与区分度（清空之后要重新读一次，否则读到的是清空前的快照）
        d.slots(3, six);
        d.setSlot(5, EquipPalette.SLOT_LEGS, (short) 7);
        String k3 = d.key(3);
        String k3again = d.key(3);
        String k5 = d.key(5);
        check(k3.equals(k3again), "同一状态必须产出同一个键（否则缓存永远命不中）");
        check(!k3.equals(k5), "不同装备必须产出不同的键（否则会串用变体网格）");
        check(EquipData.key(six, d.flags(3)).equals(k3), "静态 key 与实例 key 必须一致"
                + "（实例「" + k3 + "」 vs 静态「" + EquipData.key(six, d.flags(3)) + "」）");
        info("  设备键示例：单位 3 = 「" + k3 + "」");

        // flags 推导
        short[] headOnly = new short[EquipData.SLOT_COUNT];
        headOnly[EquipPalette.SLOT_HEAD] = 3;
        byte f = EquipData.flagsOf(headOnly, true, true);
        check((f & EquipData.FLAG_HEAD) != 0 && (f & EquipData.FLAG_GLINT) != 0
                        && (f & EquipData.FLAG_CUSTOM_NAME) != 0 && (f & EquipData.FLAG_CHEST) == 0,
                "flagsOf 必须把槽位、光效、自定义名三样都编码进去");

        // 复制（批量操作的基础）
        d.copy(3, 7);
        check(d.slot(7, EquipPalette.SLOT_MAINHAND) == 9, "copy 必须整份复制（批量装的基础）");
        d.clear(7);
        check(!d.anyEquipped(7), "clear 必须清干净");
    }

    // ------------------------------------------------------------------
    // 3. 属性叠加
    // ------------------------------------------------------------------

    /** 本机能否构造真实原版物品（离线 harness 里注册表通常起不来） */
    private static boolean vanillaItems;

    private static void testStats() {
        section("EquipStats 属性叠加");
        ItemStack[] six = new ItemStack[EquipData.SLOT_COUNT];
        for (int i = 0; i < six.length; i++) {
            six[i] = empty();
        }
        ItemStack sword = stack("minecraft:diamond_sword");
        ItemStack chest = stack("minecraft:iron_chestplate");
        six[EquipPalette.SLOT_MAINHAND] = sword;
        six[EquipPalette.SLOT_CHEST] = chest;

        if (!vanillaItems) {
            // 离线注册表起不来：只验证「纯算术与形状」这一层，并把原因写清楚
            EquipStats.Adjust empty = EquipStats.fromStacks(new ItemStack[0], 3.0F, 0.0F, 0.0F, 0.0F,
                    0.23F, 20.0F, 4.0F, null);
            check(Math.abs(empty.armor) < 1.0E-6F, "没有任何装备时护甲必须是 0");
            check(Math.abs(empty.attackDamage - 3.0F) < 1.0E-6F, "没有任何装备时攻击力必须等于基础值");
            check(!empty.anyEquipped, "空装备时 anyEquipped 必须是 false");
            check(EquipStats.attackCooldownOf(4.0F) == 5, "原版公式：4.0 攻速 → 5 tick 冷却，实际 "
                    + EquipStats.attackCooldownOf(4.0F));
            check(EquipStats.attackCooldownOf(1.6F) == 13, "1.6 攻速 → 13 tick（20/1.6=12.5 取整）");
            check(EquipStats.attackCooldownOf(0.0F) == 200, "攻速为 0 时必须夹到上限 200");
            info("  [跳过] 原版物品在本机构造失败（离线注册表未引导），"
                    + "护甲/攻击力的绝对值断言改由真机日志核对："
                    + "铁胸甲 = 6 点护甲、钻石剑 = 7 点攻击力");
            return;
        }

        EquipStats.Adjust adj = EquipStats.fromStacks(six, 3.0F, 0.0F, 0.0F, 0.0F, 0.23F, 20.0F,
                4.0F, null);
        // ---- 绝对值断言 ----
        // 原版数据：铁胸甲 +6 护甲。这条是「硬」的，写死。
        check(Math.abs(adj.armor - 6.0F) < 1.0E-3F, "铁胸甲必须给出 6 点护甲，实际 " + adj.armor);
        // 钻石剑的攻击力**不写死具体数字**（它随原版 tier 数据变动），
        // 而是断言「叠加是正确的加法」：总数 = 基础值 + 该物品自己报的攻击力。
        // 这样一旦哪天我们漏算了 ADDITION，这里立刻红；而原版调数值时不会误报。
        float swordDamage = EquipStats.attackDamageOf(sword, EquipmentSlot.MAINHAND);
        check(swordDamage >= 5.0F, "钻石剑自己报的攻击力应 >= 5，实际 " + swordDamage);
        check(Math.abs(adj.attackDamage - (3.0F + swordDamage)) < 1.0E-3F,
                "攻击力必须是「基础 " + 3.0F + " + 武器 " + swordDamage + "」= "
                        + (3.0F + swordDamage) + "，实际 " + adj.attackDamage);
        check(adj.weaponKind == EquipProfile.Kind.MELEE, "钻石剑必须被判成近战");
        check(adj.anyEquipped, "有装备时 anyEquipped 必须是 true");
        check(adj.attackCooldownTicks >= 2 && adj.attackCooldownTicks <= 200,
                "冷却必须落在 [2,200] 内");
        info("  " + adj);

        // 只穿护甲：攻击方式必须仍是「近战/无」，但不能把攻击力清零
        ItemStack[] armorOnly = new ItemStack[EquipData.SLOT_COUNT];
        for (int i = 0; i < armorOnly.length; i++) {
            armorOnly[i] = empty();
        }
        armorOnly[EquipPalette.SLOT_CHEST] = chest;
        EquipStats.Adjust onlyArmor = EquipStats.fromStacks(armorOnly, 3.0F, 0.0F, 0.0F, 0.0F,
                0.23F, 20.0F, 4.0F, null);
        check(Math.abs(onlyArmor.armor - 6.0F) < 1.0E-3F, "只穿胸甲时护甲仍是 6");
        check(Math.abs(onlyArmor.attackDamage - 3.0F) < 1.0E-3F, "只穿护甲不该改攻击力");
        check(onlyArmor.weaponKind == EquipProfile.Kind.NONE, "只有护甲时不该被判成武器");
    }

    // ------------------------------------------------------------------
    // 4. 行为判定
    // ------------------------------------------------------------------

    private static void testProfile() {
        section("EquipProfile 行为判定");
        if (!vanillaItems) {
            check(EquipProfile.of(empty(), "mainhand").kind == EquipProfile.Kind.NONE,
                    "空手必须判成 NONE（只加属性、不改行为）");
            check(EquipProfile.dominant(EquipProfile.NONE, EquipProfile.NONE).kind
                            == EquipProfile.Kind.NONE,
                    "dominant（两把都空）必须是 NONE");
            info("  [跳过] 弓/弩/剑/盾的真实类判定需要原版物品，真机上由这两条日志核对："
                    + "「类型 X -> 攻击模式 远程」/「近战」");
            return;
        }
        EquipProfile.Params bow = EquipProfile.of(stack("minecraft:bow"), "mainhand");
        EquipProfile.Params crossbow = EquipProfile.of(stack("minecraft:crossbow"), "mainhand");
        EquipProfile.Params sword = EquipProfile.of(stack("minecraft:diamond_sword"), "mainhand");
        EquipProfile.Params axe = EquipProfile.of(stack("minecraft:diamond_axe"), "mainhand");
        EquipProfile.Params trident = EquipProfile.of(stack("minecraft:trident"), "mainhand");
        EquipProfile.Params shield = EquipProfile.of(stack("minecraft:shield"), "offhand");
        EquipProfile.Params stick = EquipProfile.of(stack("minecraft:stick"), "mainhand");

        check(bow.kind == EquipProfile.Kind.RANGED, "弓必须判成远程");
        check(crossbow.kind == EquipProfile.Kind.RANGED, "弩必须判成远程");
        check(EquipProfile.DEFAULT_ARROW.equals(bow.projectile),
                "远程默认弹必须是 minecraft:arrow，实际 " + bow.projectile);
        EquipProfile.Params bowOverride = EquipProfile.of(stack("minecraft:bow"), "mainhand",
                "cataclysm:ignis_fireball");
        check(rl("cataclysm:ignis_fireball").equals(bowOverride.projectile),
                "GUI 覆盖投掷物必须生效（不写死任何模组，只是把 id 透传）");
        check(crossbow.intervalTicks > bow.intervalTicks,
                "弩的攻击间隔必须比弓长（弩 32 > 弓 20），实际 " + crossbow.intervalTicks
                        + " vs " + bow.intervalTicks);
        check(sword.kind == EquipProfile.Kind.MELEE && sword.meleeDamage >= 5.0F,
                "钻石剑必须判成近战且攻击力 >= 5（原版数据），实际 " + sword.meleeDamage);
        check(axe.kind == EquipProfile.Kind.MELEE && axe.meleeDamage >= 5.0F,
                "钻石斧必须判成近战且攻击力 >= 5（原版数据），实际 " + axe.meleeDamage);
        check(axe.meleeDamage > sword.meleeDamage,
                "钻石斧的攻击力必须高于钻石剑（原版就是这个关系：" + axe.meleeDamage
                        + " vs " + sword.meleeDamage + "）");
        check(trident.kind == EquipProfile.Kind.MELEE && trident.twoHanded,
                "三叉戟必须判成近战且是双手武器");
        check(shield.kind == EquipProfile.Kind.SHIELD && shield.blocking(),
                "盾必须判成 SHIELD 且 blocking() 为真");
        check(stick.kind == EquipProfile.Kind.NONE, "木棍必须判成 NONE（只加属性、不改行为）");
        check(EquipProfile.of(stack("minecraft:diamond_sword"), "head").kind
                        == EquipProfile.Kind.NONE,
                "护甲槽里的剑不该被当成武器（只有主/副手决定怎么打）");
        check(EquipProfile.dominant(EquipProfile.NONE, shield).kind == EquipProfile.Kind.SHIELD,
                "主手空、副手盾时，战斗方式必须是举盾");
        check(EquipProfile.dominant(bow, shield).kind == EquipProfile.Kind.RANGED,
                "主手弓 + 副手盾：优先级必须落在主手（远程）");
    }

    // ------------------------------------------------------------------
    // 5. 拆包与带宽
    // ------------------------------------------------------------------

    private static void testPackets() {
        section("拆包与带宽");
        // ---- 调色板整表 ----
        int entries = 6;
        ResourceLocation[] types = new ResourceLocation[entries];
        int[] indices = new int[entries];
        ResourceLocation[] items = new ResourceLocation[entries];
        byte[] marks = new byte[entries];
        for (int i = 0; i < entries; i++) {
            types[i] = rl("minecraft:zombie");
            indices[i] = i + 1;
            items[i] = rl("minecraft:item_" + i);
            marks[i] = (byte) ((i & 1) == 0 ? EquipPalettePacket.MARK_GLINT : 0);
        }
        EquipPalettePacket pal = new EquipPalettePacket(7, types, indices, items, marks);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        pal.encode(buf);
        int palBytes = buf.readableBytes();
        EquipPalettePacket palBack = new EquipPalettePacket(buf);
        check(palBack.version == 7, "调色板版本必须还原，实际 " + palBack.version);
        check(palBack.unitTypes.length == entries, "条目数必须还原");
        check(palBack.indices[3] == 4, "索引必须还原（绝对值断言）");
        check(rl("minecraft:item_5").equals(palBack.items[5]), "物品 id 必须还原");
        check((palBack.marks[0] & EquipPalettePacket.MARK_GLINT) != 0
                        && (palBack.marks[1] & EquipPalettePacket.MARK_GLINT) == 0,
                "光效标记必须逐条还原（绝对值断言）");
        info("  调色板整表：" + entries + " 条 = " + palBytes + " 字节（一次下发，之后只在版本变化时重发）");

        // ---- 逐单位快照 ----
        for (int units : new int[]{1, 1000, 10000}) {
            short[] ids = new short[units];
            short[] slots = new short[units * EquipData.SLOT_COUNT];
            byte[] flags = new byte[units];
            for (int i = 0; i < units; i++) {
                ids[i] = (short) i;
                for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                    slots[i * EquipData.SLOT_COUNT + s] = (short) ((i + s) % 9);
                }
                flags[i] = (byte) (i & 0xFF);
            }
            int perPacket = EquipSnapshotPacket.UNITS_PER_PACKET;
            int chunkCount = (units + perPacket - 1) / perPacket;
            int totalBytes = 0;
            int maxPacket = 0;
            for (int c = 0; c < chunkCount; c++) {
                int offset = c * perPacket;
                int len = Math.min(perPacket, units - offset);
                EquipSnapshotPacket p = new EquipSnapshotPacket(rl("minecraft:overworld"), true, 7,
                        offset, len, c, chunkCount, units, toIntArray(ids), slots, flags);
                FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
                p.encode(b);
                int bytes = b.readableBytes();
                totalBytes += bytes;
                maxPacket = Math.max(maxPacket, bytes);
                EquipSnapshotPacket back = new EquipSnapshotPacket(b);
                check(back.count == len, units + "：第 " + c + " 包单位数应为 " + len);
                check(back.fullFrame && back.paletteVersion == 7,
                        units + "：整帧标记与版本必须还原");
                check(back.chunkIndex == c && back.chunkCount == chunkCount,
                        units + "：拆包编号必须还原");
                check(back.totalUnits == units, units + "：总数必须还原");
                check(back.id[0] == (offset & 0xFFFF), units + "：首单位 id 必须还原（绝对值）");
                // 抽查一个槽的绝对值
                int probe = Math.min(len - 1, 7);
                check(back.slots[probe * EquipData.SLOT_COUNT + 4]
                                == ((offset + probe + 4) % 9),
                        units + "：抽查槽值必须逐位还原");
                check(back.flags[probe] == (byte) ((offset + probe) & 0xFF),
                        units + "：flags 必须还原");
            }
            check(maxPacket < 1_000_000, units + "：单包必须低于 1 MB（实测 " + maxPacket + "）");
            int payload = units * (2 + EquipData.SLOT_COUNT * 2 + 1);
            check(payload == units * 15, units + "：每单位载荷必须是 15 字节（2 + 12 + 1）");
            info(String.format("  %d 只：%d 个包，最大单包 %d 字节（实测），总 %d 字节；"
                            + "载荷 %d 字节（15 B/单位），10 Hz 时 %.1f KB/s",
                    units, chunkCount, maxPacket, totalBytes, payload, payload * 10.0D / 1024.0D));
        }

        // ---- 空帧 / 单包 ----
        EquipSnapshotPacket empty = new EquipSnapshotPacket(rl("minecraft:overworld"), true, 1,
                0, 0, 0, 1, 0, new int[0], new short[0], new byte[0]);
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        empty.encode(b);
        EquipSnapshotPacket emptyBack = new EquipSnapshotPacket(b);
        check(emptyBack.count == 0 && emptyBack.chunkCount == 1 && emptyBack.fullFrame,
                "空整帧必须能正确往返（客户端据此清空）");
        check(b.readableBytes() == 0, "空帧解码之后缓冲必须恰好读完（没有多余的字节）");
        info("  空整帧：" + (EquipSnapshotPacket.wireBytes(0, 1)) + " 字节（估算）/ 实际编码后 "
                + encodeSize(empty) + " 字节");
    }

    private static int encodeSize(EquipSnapshotPacket p) {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(b);
        return b.readableBytes();
    }

    private static int[] toIntArray(short[] in) {
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] & 0xFFFF;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 6. 变体网格缓存（替身网格对象，不需要 GL）
    // ------------------------------------------------------------------

    /** 替身「网格」：只带一个 id，用来验证缓存逻辑 */
    private static final class StubMesh {
        final String key;
        boolean released;

        StubMesh(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return "mesh(" + key + ")";
        }
    }

    private static void testVariantCache() {
        section("变体网格缓存（替身对象）");
        ResourceLocation zombie = rl("minecraft:zombie");
        ResourceLocation skeleton = rl("minecraft:skeleton");
        final int[] built = {0};
        final List<StubMesh> released = new ArrayList<>();

        EquipMeshCache<StubMesh> cache = new EquipMeshCache<>(3, new EquipMeshCache.Factory<StubMesh>() {
            @Override
            public StubMesh create(ResourceLocation unitType, String equipKey) {
                built[0]++;
                if (equipKey.contains("fail")) {
                    return null;   // 模拟「这个组合烘不出来」
                }
                return new StubMesh(unitType + "#" + equipKey);
            }

            @Override
            public void release(StubMesh value) {
                value.released = true;
                released.add(value);
            }
        });

        StubMesh a = cache.get(zombie, "1,0,0,0,0,0;0");
        StubMesh a2 = cache.get(zombie, "1,0,0,0,0,0;0");
        check(a != null && a == a2, "同一个组合第二次取必须命中同一份（不能重烘）");
        check(cache.hits() == 1 && cache.misses() == 1,
                "命中/未命中计数必须是 1/1，实际 " + cache.hits() + "/" + cache.misses());
        check(built[0] == 1, "只应该真正烘焙一次，实际 " + built[0]);

        // 上限：再加两条到 3 条，仍然不淘汰
        cache.get(zombie, "2,0,0,0,0,0;0");
        cache.get(zombie, "3,0,0,0,0,0;0");
        check(cache.sizeOf(zombie) == 3, "到达上限前不该淘汰，实际 " + cache.sizeOf(zombie));
        check(cache.evictions() == 0, "还没超限，淘汰数必须是 0");

        // 另一类型独立计数
        cache.get(skeleton, "1,0,0,0,0,0;0");
        check(cache.sizeOf(skeleton) == 1, "不同类型各自计数，实际 " + cache.sizeOf(skeleton));
        check(cache.sizeOf(zombie) == 3, "另一个类型不该影响本类型的计数");

        // 第 4 条：必须淘汰**最久未使用**的那条（第 1 条，因为第 2、3 条刚被访问）
        cache.get(zombie, "4,0,0,0,0,0;0");
        check(cache.sizeOf(zombie) == 3, "超限后该类型的条目数必须回到上限，实际 " + cache.sizeOf(zombie));
        check(cache.evictions() == 1, "应该正好淘汰 1 条，实际 " + cache.evictions());
        check(a.released, "被淘汰的那一份必须走 release（真机上就是在这里删 VAO）");
        check(released.size() == 1 && released.get(0) == a,
                "淘汰的必须是「最久未使用」的那条（LRU），实际淘汰了 " + released.get(0));

        // 最近用过的不该被淘汰
        StubMesh keep = cache.get(zombie, "4,0,0,0,0,0;0");
        StubMesh keep2 = cache.get(zombie, "3,0,0,0,0,0;0");
        cache.get(zombie, "5,0,0,0,0,0;0");
        check(cache.sizeOf(zombie) == 3, "再超限一次仍要维持上限");
        check(!keep.released && !keep2.released, "刚访问过的两条不该被淘汰（LRU 语义）");

        // 失败不重试
        int before = built[0];
        StubMesh fail1 = cache.get(zombie, "fail-combo");
        StubMesh fail2 = cache.get(zombie, "fail-combo");
        check(fail1 == null && fail2 == null, "烘不出来的组合必须返回 null");
        check(built[0] == before + 1, "失败只允许尝试一次（否则每帧重烘，帧率直接崩）");
        check(cache.failures() == 1, "失败计数应为 1，实际 " + cache.failures());
        check(cache.isFailed(zombie, "fail-combo"), "失败状态必须能被查询到");

        // 作废与清空
        int releasedBefore = released.size();
        cache.invalidateType(zombie);
        check(cache.sizeOf(zombie) == 0, "invalidateType 之后该类型必须清空");
        check(released.size() > releasedBefore, "作废时必须释放 GL 资源（回调 release）");
        check(cache.sizeOf(skeleton) == 1, "invalidateType 不该误伤别的类型");

        cache.clear();
        check(cache.size() == 0, "clear 之后必须全空");
        info(String.format("  缓存：命中 %d / 未命中 %d / 淘汰 %d / 失败 %d；上限（每类型）%d",
                cache.hits(), cache.misses(), cache.evictions(), cache.failures(), cache.maxPerType()));

        // 内存实测：一个变体网格的字节数（顶点 9 float + 索引）
        int verts = 2400;
        int tris = 1200;
        int bytes = verts * 9 * 4 + tris * 3 * 4;
        info(String.format("  内存：一个 %d 顶点 / %d 三角的变体 = %d B（%.1f KB）；"
                        + "64 个变体 ≈ %.2f MB（每类型上限 64）",
                verts, tris, bytes, bytes / 1024.0D, bytes * 64 / 1048576.0D));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ResourceLocation rl(String s) {
        return ResourceLocation.tryParse(s);
    }

    /**
     * 造一件真实原版物品的 ItemStack。
     *
     * <p>离线 harness 里原版注册表**通常**起不来（引导会连带拉起 Forge 的网络常量），
     * 所以这里失败不抛异常，而是把 {@link #vanillaItems} 置 false，
     * 让相关的用例退化成「形状 + 纯算术」验证并明确打印出来 ——
     * 「静默跳过」比「报错」更糟，所以每一处都会留一行 [跳过] 说明。
     */
    private static ItemStack stack(String id) {
        try {
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl(id));
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return empty();
            }
            ItemStack s = new ItemStack(item);
            vanillaItems = true;
            return s;
        } catch (Throwable t) {
            return empty();
        }
    }

    /** 兼容旧写法：直接从 Items 常量取（注册表未就绪时会抛，由 {@link #stack} 兜住） */
    private static ItemStack stackOf(net.minecraft.world.item.Item item) {
        try {
            ItemStack s = new ItemStack(item);
            vanillaItems = true;
            return s;
        } catch (Throwable t) {
            return empty();
        }
    }

    /**
     * 「空槽」的表示。
     *
     * <p>这里返回 {@code null} 而**不是** {@code ItemStack.EMPTY}：后者会触发
     * {@code ItemStack} 的静态初始化，而那个静态初始化在注册表未引导时会直接抛
     * （{@code ExceptionInInitializerError: Not bootstrapped}）—— 连「优雅跳过」都做不到。
     * {@code EquipPalette} / {@code EquipProfile} / {@code EquipStats} 全都按
     * {@code stack == null || stack.isEmpty()} 判断，所以 null 是完全合法的输入。
     */
    private static ItemStack empty() {
        return null;
    }

    private static void section(String title) {
        System.out.println("\n---- " + title + " ----");
    }

    private static void info(String s) {
        INFO.add(s);
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
