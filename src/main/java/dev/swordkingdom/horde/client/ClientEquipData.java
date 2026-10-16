package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.network.EquipPalettePacket;
import dev.swordkingdom.horde.network.EquipSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端装备数据（按维度），结构与 {@link ClientCrowdData} / {@link ClientProjectileData} 同款：
 * 全部是基本类型数组（按单位 id 索引），零对象分配；一帧由多个包组成时**收齐才提交**。
 *
 * <h2>它一定要和 {@link ClientCrowdData} 对得上</h2>
 * 两边用的是**同一套单位 id**（服务端快照里的槽位 id），所以渲染时
 * 「这个 id 的位置/姿态」和「这个 id 的装备」天然对齐，不需要额外的映射表。
 *
 * <h2>贴图/索引错位怎么防</h2>
 * 调色板带版本号。装备快照里的版本与本地不一致时：
 *   - 不提交这一帧（避免把索引当成本地另一件物品去烘焙，那会「穿错装备」）；
 *   - 置 {@link #stale} 并让 GUI 回请整表（{@link #requestPalette}）。
 * 服务端在发表之后一定会补一个整帧（见 {@code EquipSync.sendPalette}），所以最多丢一帧。
 */
public final class ClientEquipData {

    private static final Map<ResourceLocation, ClientEquipData> BY_DIMENSION = new HashMap<>();

    public final ResourceLocation dimension;

    private int capacity;
    /** 每单位 6 个调色板索引 */
    public short[] slots = new short[0];
    public byte[] flags = new byte[0];
    /** 本帧在场的单位 id */
    public int[] ids = new int[0];
    public int count;

    /** 调色板版本（服务端说的） */
    public int paletteVersion = -1;
    /** 本地索引是否过期（GUI 据此回请整表） */
    public boolean stale;
    /** 调色板整表：单位类型 -> (索引 -> 条目) */
    private final Map<ResourceLocation, Map<Integer, Entry>> palette = new HashMap<>();

    /** 拼帧缓冲 */
    private int[] pendingIds = new int[0];
    private short[] pendingSlots = new short[0];
    private byte[] pendingFlags = new byte[0];
    private int pendingN;
    private int pendingTotal = -1;
    private int pendingVersion = -1;

    /** 调色板里的一条（客户端只需要「是什么物品 / 有没有光效」） */
    public static final class Entry {
        public final ResourceLocation item;
        public final boolean glint;
        public final boolean customName;

        Entry(ResourceLocation item, boolean glint, boolean customName) {
            this.item = item;
            this.glint = glint;
            this.customName = customName;
        }
    }

    private ClientEquipData(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public static ClientEquipData get(ResourceLocation dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, ClientEquipData::new);
    }

    public static ClientEquipData forCurrentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : BY_DIMENSION.get(level.dimension().location());
    }

    // ------------------------------------------------------------------
    // 收包
    // ------------------------------------------------------------------

    /** 收调色板整表（覆盖式：整表就是全量，不做增量） */
    public static void acceptPalette(EquipPalettePacket msg) {
        if (msg == null) {
            return;
        }
        // 调色板是全局表，但按「当前维度」存一份（与快照的维度一致，取用最方便）
        ClientLevel level = Minecraft.getInstance().level;
        ResourceLocation dim = level == null
                ? ResourceLocation.tryParse("minecraft:overworld")
                : level.dimension().location();
        ClientEquipData d = get(dim);
        d.palette.clear();
        for (int i = 0; i < msg.unitTypes.length; i++) {
            d.palette.computeIfAbsent(msg.unitTypes[i], k -> new HashMap<>())
                    .put(msg.indices[i], new Entry(msg.items[i],
                            (msg.marks[i] & EquipPalettePacket.MARK_GLINT) != 0,
                            (msg.marks[i] & EquipPalettePacket.MARK_CUSTOM_NAME) != 0));
        }
        d.paletteVersion = msg.version;
        d.stale = false;
        // 索引表换了 -> 所有已烘焙的变体网格作废，下一帧按新表重烘
        EquipBaker.invalidateAll();
    }

    /** 收一个装备快照包；同一帧被拆成多个包时收齐后提交 */
    public static void accept(EquipSnapshotPacket packet) {
        ClientEquipData d = get(packet.dimension);
        if (packet.chunkIndex == 0 || d.pendingTotal < 0) {
            d.beginFrame(packet);
        }
        d.appendChunk(packet);
        if (packet.chunkIndex >= packet.chunkCount - 1 || d.pendingN >= d.pendingTotal) {
            d.commitFrame();
        }
    }

    private void beginFrame(EquipSnapshotPacket packet) {
        if (packet.paletteVersion != paletteVersion) {
            // 本地索引与这一帧不属于同一张表：丢掉这一帧（下一帧会随整表一起来）
            stale = true;
            pendingTotal = -1;
            pendingN = 0;
            return;
        }
        stale = false;
        pendingN = 0;
        pendingVersion = packet.paletteVersion;
        pendingTotal = packet.totalUnits > 0 ? packet.totalUnits : packet.count;
        if (packet.fullFrame) {
            // 整帧：先清空上一帧的值（不在本帧里的单位就是「没装备」）
            Arrays.fill(flags, (byte) 0);
            Arrays.fill(slots, (short) 0);
        }
    }

    private void appendChunk(EquipSnapshotPacket packet) {
        if (stale || packet.paletteVersion != paletteVersion) {
            return;
        }
        int n = packet.count;
        if (n <= 0) {
            return;
        }
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            int id = packet.id[i] & 0xFFFF;
            if (id > maxId) {
                maxId = id;
            }
        }
        ensure(maxId + 1);
        if (pendingIds.length < pendingN + n) {
            int cap = Math.max(pendingN + n, Math.max(16, pendingIds.length * 2));
            pendingIds = Arrays.copyOf(pendingIds, cap);
        }
        for (int i = 0; i < n; i++) {
            int id = packet.id[i] & 0xFFFF;
            pendingIds[pendingN++] = id;
            int src = i * EquipData.SLOT_COUNT;
            int dst = id * EquipData.SLOT_COUNT;
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                slots[dst + s] = packet.slots[src + s];
            }
            flags[id] = packet.flags[i];
        }
    }

    private void commitFrame() {
        if (ids.length < pendingN) {
            ids = Arrays.copyOf(ids, Math.max(pendingN, 16));
        }
        System.arraycopy(pendingIds, 0, ids, 0, pendingN);
        count = pendingN;
        pendingN = 0;
        pendingTotal = -1;
    }

    private void ensure(int needCapacity) {
        if (capacity >= needCapacity) {
            return;
        }
        capacity = needCapacity;
        slots = Arrays.copyOf(slots, capacity * EquipData.SLOT_COUNT);
        flags = Arrays.copyOf(flags, capacity);
    }

    // ------------------------------------------------------------------
    // 查询（渲染器每帧用）
    // ------------------------------------------------------------------

    /** 该单位 6 个槽是否全空（全空时走「基础网格」那条路，不需要变体） */
    public boolean isEmpty(int id) {
        if (id < 0 || id >= flags.length) {
            return true;
        }
        return (flags[id] & 0x3F) == 0;
    }

    public byte flags(int id) {
        return id >= 0 && id < flags.length ? flags[id] : 0;
    }

    public short slot(int id, int s) {
        int i = id * EquipData.SLOT_COUNT + s;
        return i >= 0 && i < slots.length ? slots[i] : 0;
    }

    /** 该单位装备的确定性键（缓存用）—— 与 {@link EquipData#key(short[], int)} 同一套规则 */
    public String keyOf(int id) {
        int base = id * EquipData.SLOT_COUNT;
        StringBuilder sb = new StringBuilder(24);
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            if (s > 0) {
                sb.append(',');
            }
            sb.append(base + s < slots.length ? (slots[base + s] & 0xFFFF) : 0);
        }
        sb.append(';').append(flags(id) & 0xFF);
        return sb.toString();
    }

    /** 调色板条目（没有返回 null） */
    public Entry entry(ResourceLocation unitType, short index) {
        if (index == 0 || unitType == null) {
            return null;
        }
        Map<Integer, Entry> byIndex = palette.get(unitType);
        return byIndex == null ? null : byIndex.get(index & 0xFFFF);
    }

    /** 该单位某个槽的物品（没有返回 null） */
    public Entry entryOf(int id, ResourceLocation unitType, int slot) {
        return entry(unitType, slot(id, slot));
    }

    public boolean hasPalette() {
        return !palette.isEmpty();
    }

    /** 回请整表（GUI 发现 {@link #stale} 时调用） */
    public static void requestPalette() {
        dev.swordkingdom.horde.network.HordeNetwork.sendToServer(
                new dev.swordkingdom.horde.network.EquipPackets.Request(-1));
    }

    public static void clearAll() {
        BY_DIMENSION.clear();
    }
}
