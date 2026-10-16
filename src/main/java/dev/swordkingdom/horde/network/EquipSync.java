package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.CrowdData;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.edit.EquipPalette;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 装备同步：把「每单位 6 个调色板索引 + flags」送到玩家手上。
 *
 * <h2>与 {@link CrowdSync} 的关系</h2>
 * 同一节奏（{@code snapshotIntervalTicks}）、同一批「玩家附近的单位」
 * （复用 {@code CrowdManager.collectVisible}，不额外做一次空间哈希查询），
 * 但**单独一个包**：装备是低频事件（换一次装才变），位置每帧都在变，
 * 混在一起会让「只改了 3 个单位的装备」也背上整帧位置数据的开销。
 *
 * <h2>什么时候发整帧 / 什么时候发增量</h2>
 * <pre>
 *   玩家第一次收 / 调色板版本变了 / 之前发过空表  -> 整帧（fullFrame = true，客户端据此清掉旧值）
 *   之后                                          -> 只发**与上次发给这个玩家不同**的单位
 * </pre>
 * 「与上次不同」用一张按玩家缓存的 {@code short[]} 记住（键是单位槽位 id）。
 * 稳态（没人换装）时每帧只有一个五十来字节的包头，可以忽略。
 *
 * <h2>槽位复用</h2>
 * 群集槽位会被复用（死人腾出来的号分给新单位），而 {@link EquipData} 是按槽位索引的、
 * 新单位沿用该槽位原有的装备 —— 这和上面的增量缓存**天然自洽**
 * （服务端认为「这个号还是这套装备」，客户端也这么认为），不会两边不一致。
 */
public final class EquipSync {

    /** 每个玩家记住「上一次发给他的整表装备值」，用于算增量 */
    private static final Map<UUID, short[]> LAST_SENT = new HashMap<>();
    /** 每个玩家记住「上一次发的调色板版本」 */
    private static final Map<UUID, Integer> LAST_PALETTE = new HashMap<>();
    /** 每个玩家记住「上一次是不是整帧」 */
    private static final Map<UUID, Boolean> LAST_FULL = new HashMap<>();

    private EquipSync() {}

    /**
     * 把调色板整表发给某个玩家（进服 / 进 GUI / 客户端说版本对不上）。
     *
     * 顺序很重要：**先发表，再发装备快照** —— 否则客户端收到索引时还不知道索引是什么，
     * 只能把这一帧丢掉等下一帧。这里发完表就把该玩家的「上次版本」清掉，
     * 于是紧接着的 {@link #tick} 一定会发一个**整帧**装备快照。
     */
    public static void sendPalette(ServerPlayer player) {
        EquipPalette palette = EquipPalette.shared();
        java.util.List<ResourceLocation> types = new java.util.ArrayList<>(palette.types());
        int total = palette.totalEntries();
        ResourceLocation[] typeArr = new ResourceLocation[Math.min(total, EquipPalettePacket.MAX_ENTRIES)];
        int[] idxArr = new int[typeArr.length];
        ResourceLocation[] itemArr = new ResourceLocation[typeArr.length];
        byte[] markArr = new byte[typeArr.length];
        int n = 0;
        outer:
        for (ResourceLocation type : types) {
            for (EquipPalette.Entry e : palette.entries(type)) {
                if (n >= typeArr.length) {
                    break outer;
                }
                typeArr[n] = type;
                idxArr[n] = e.index & 0xFFFF;
                itemArr[n] = e.item;
                byte mark = 0;
                if (e.glint) {
                    mark |= EquipPalettePacket.MARK_GLINT;
                }
                if (e.customName) {
                    mark |= EquipPalettePacket.MARK_CUSTOM_NAME;
                }
                markArr[n] = mark;
                n++;
            }
        }
        // 表比上限长时截断（并说清楚），不静默丢
        if (n < total) {
            HordeMod.LOGGER.warn("[horde] 装备调色板共 {} 条，超过单包上限 {}，本次只下发前 {} 条"
                            + "（超出的索引在客户端会被当成空槽；"
                            + "正常情况下永远不会到这个量级）",
                    total, EquipPalettePacket.MAX_ENTRIES, n);
        }
        HordeNetwork.sendToPlayer(new EquipPalettePacket(palette.version(),
                java.util.Arrays.copyOf(typeArr, n), java.util.Arrays.copyOf(idxArr, n),
                java.util.Arrays.copyOf(itemArr, n), java.util.Arrays.copyOf(markArr, n)), player);
        // 强制下一次装备快照走「整帧」
        LAST_PALETTE.remove(player.getUUID());
        LAST_FULL.put(player.getUUID(), false);
    }

    /** 只清缓存（重登/换维度时用） */
    public static void forget(UUID player) {
        LAST_SENT.remove(player);
        LAST_PALETTE.remove(player);
        LAST_FULL.remove(player);
    }

    /** 该玩家的上次发送缓存；返回的数组长度足够覆盖 {@code units * 6}，不足时扩容并填 -1 */
    private static short[] cache(UUID player, int units) {
        short[] arr = LAST_SENT.get(player);
        int need = Math.max(0, units) * EquipData.SLOT_COUNT;
        if (arr == null || arr.length < need) {
            short[] bigger = new short[Math.max(need, 4096)];
            java.util.Arrays.fill(bigger, (short) -1);
            if (arr != null) {
                System.arraycopy(arr, 0, bigger, 0, arr.length);
            }
            LAST_SENT.put(player, bigger);
            return bigger;
        }
        return arr;
    }

    /**
     * 每 tick（按快照间隔）跑一次，对每个玩家发这一帧的装备。
     *
     * 注意：这里对每个玩家**各自**调一次 {@code collectVisible}（与 {@link CrowdSync} 的做法一样），
     * 因为不同玩家附近的单位集合不同 —— 复用一个玩家的集合会让另一个玩家收到远处单位的装备。
     * 代价是一次空间哈希查询，和位置同步同量级。
     */
    public static void tick(MinecraftServer server) {
        int interval = HordeConfig.COMMON.snapshotIntervalTicks.get();
        if (interval <= 0 || server.getTickCount() % interval != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            CrowdManager m = CrowdManagers.getIfPresent(level.dimension());
            if (m == null) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer p = players.get(i);
                int n = m.aliveCount() == 0 ? 0
                        : m.collectVisible((float) p.getX(), (float) p.getZ(),
                        HordeConfig.COMMON.snapshotRange.get().floatValue(),
                        HordeConfig.COMMON.syncBudgetPerPlayer.get());
                sendToPlayer(m, p, n);
            }
        }
    }

    /** 给一个玩家发这一帧；调用前必须已经用该玩家的位置调过 {@code collectVisible} */
    private static void sendToPlayer(CrowdManager m, ServerPlayer player, int n) {
        ResourceLocation dim = m.dimension().location();
        EquipPalette palette = EquipPalette.shared();
        int version = palette.version();

        Integer lastVersion = LAST_PALETTE.get(player.getUUID());
        boolean versionUnknown = lastVersion == null || lastVersion != version;
        boolean fullFrame = versionUnknown || !Boolean.TRUE.equals(LAST_FULL.get(player.getUUID()));

        EquipData data = EquipData.getIfPresent(m.dimension());
        if (data == null || !data.enabled()) {
            // 这个维度还没有装备表：只在「第一次 / 版本变了」时发一个空整帧让客户端清干净
            if (fullFrame) {
                HordeNetwork.sendToPlayer(new EquipSnapshotPacket(dim, true, version,
                        0, 0, 0, 1, 0, new int[0], new short[0], new byte[0]), player);
                LAST_PALETTE.put(player.getUUID(), version);
                LAST_FULL.put(player.getUUID(), true);
            }
            return;
        }

        int[] sendIds = new int[Math.max(1, n)];
        short[] payload = new short[Math.max(1, n) * EquipData.SLOT_COUNT];
        byte[] flagPayload = new byte[Math.max(1, n)];
        int sendCount = 0;

        CrowdData cd = m.data();
        short[] last = fullFrame ? null : cache(player.getUUID(), data.capacity());
        for (int i = 0; i < n; i++) {
            int s = m.outSlots[i];
            if (!cd.aliveFlag[s]) {
                continue;
            }
            int base = s * EquipData.SLOT_COUNT;
            boolean changed = fullFrame;
            if (!changed && last != null) {
                for (int k = 0; k < EquipData.SLOT_COUNT; k++) {
                    int p = base + k;
                    if (p >= last.length || last[p] != data.slots[p]) {
                        changed = true;
                        break;
                    }
                }
                if (!changed && data.flags[s] != 0) {
                    // flags 变了也算变（光效/自定义名）；flags == 0 时上面的槽位比较已经覆盖了
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            int idx = sendCount++;
            sendIds[idx] = s;
            int dst = idx * EquipData.SLOT_COUNT;
            for (int k = 0; k < EquipData.SLOT_COUNT; k++) {
                payload[dst + k] = data.slots[base + k];
            }
            flagPayload[idx] = data.flags[s];
        }

        if (sendCount == 0 && !fullFrame) {
            // 一个都没变：什么都不发（这是稳态，最常见的情况）
            LAST_PALETTE.put(player.getUUID(), version);
            return;
        }

        int perPacket = EquipSnapshotPacket.UNITS_PER_PACKET;
        int chunkCount = sendCount <= 0 ? 1 : (sendCount + perPacket - 1) / perPacket;
        for (int c = 0; c < chunkCount; c++) {
            int offset = c * perPacket;
            int len = Math.min(perPacket, sendCount - offset);
            HordeNetwork.sendToPlayer(new EquipSnapshotPacket(dim, fullFrame, version,
                    offset, len, c, chunkCount, sendCount, sendIds, payload, flagPayload), player);
        }

        // 记住这次发出去的值
        short[] remember = cache(player.getUUID(), data.capacity());
        if (fullFrame) {
            java.util.Arrays.fill(remember, (short) 0);
        }
        for (int i = 0; i < sendCount; i++) {
            int s = sendIds[i];
            int base = s * EquipData.SLOT_COUNT;
            if (base + EquipData.SLOT_COUNT <= remember.length) {
                System.arraycopy(data.slots, base, remember, base, EquipData.SLOT_COUNT);
            }
        }
        LAST_PALETTE.put(player.getUUID(), version);
        LAST_FULL.put(player.getUUID(), true);

        if ((player.tickCount % 400) == 0) {
            HordeMod.LOGGER.info("[horde] 装备同步：{} 只（{} 个包，整包约 {} 字节，调色板 {} 条，版本 {}，{}）",
                    sendCount, chunkCount,
                    EquipSnapshotPacket.wireBytes(Math.min(Math.max(sendCount, 1), perPacket), chunkCount),
                    palette.totalEntries(), version, fullFrame ? "整帧" : "增量");
        }
    }
}
