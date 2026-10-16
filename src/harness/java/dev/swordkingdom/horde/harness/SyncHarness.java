package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.client.ClientCrowdData;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.network.CrowdSnapshotPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证「数量不设限」这条链路：
 *
 * 1. 造一份 N 个单位的快照数据（N 远大于单包上限），按 {@link CrowdSnapshotPacket#UNITS_PER_PACKET}
 *    拆包 —— 每个包都真实走一遍 {@code encode} / {@code decode}（FriendlyByteBuf），
 *    确认单包字节数远低于原版自定义负载 1 MB 上限；
 * 2. 把这些包按网络顺序喂给客户端 {@link ClientCrowdData#accept}，
 *    确认**收齐后在场数量 == N**（也就是一只都不丢），并且抽查数值正确；
 * 3. 再验证「一帧只发一个包」的普通情况、以及包的顺序/编号字段。
 */
public final class SyncHarness {

    private static int failures;

    public static void main(String[] args) {
        testRoundTrip(10000);
        testRoundTrip(25000);
        testRoundTrip(60000);
        testSinglePacketCase();
        System.out.println(failures == 0 ? "\n== SYNC PASS ==" : "\n== SYNC FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void testRoundTrip(int units) {
        ResourceLocation dim = ResourceLocation.tryParse("minecraft:overworld");
        ResourceLocation type = ResourceLocation.tryParse("minecraft:zombie");
        double ox = 100.0D;
        double oy = 64.0D;
        double oz = -200.0D;

        short[] id = new short[units];
        short[] x = new short[units];
        short[] y = new short[units];
        short[] z = new short[units];
        byte[] yaw = new byte[units];
        short[] walkPos = new short[units];
        short[] walkSpeed = new short[units];
        byte[] headYaw = new byte[units];
        byte[] headPitch = new byte[units];
        byte[] attack = new byte[units];
        byte[] hurt = new byte[units];
        byte[] teamFlags = new byte[units];
        short[] age = new short[units];
        for (int i = 0; i < units; i++) {
            id[i] = (short) i;
            x[i] = (short) (i % 500 - 250);
            y[i] = 0;
            z[i] = (short) (i % 300 - 150);
            yaw[i] = (byte) (i & 0xFF);
            walkPos[i] = (short) (i * 7 & 0xFFFF);
            walkSpeed[i] = (short) (i * 3 & 0xFFFF);
            headYaw[i] = (byte) ((i % 127) - 63);
            headPitch[i] = 0;
            attack[i] = (byte) (i % 256);
            hurt[i] = (byte) (i % 11);
            teamFlags[i] = (byte) ((i % 4) | (0 << 2) | 0x80);
            age[i] = (short) (i % 32767);
        }

        int chunkCount = (units + CrowdSnapshotPacket.UNITS_PER_PACKET - 1)
                / CrowdSnapshotPacket.UNITS_PER_PACKET;
        List<CrowdSnapshotPacket> sent = new ArrayList<>();
        int maxBytes = 0;
        for (int c = 0; c < chunkCount; c++) {
            int offset = c * CrowdSnapshotPacket.UNITS_PER_PACKET;
            int len = Math.min(CrowdSnapshotPacket.UNITS_PER_PACKET, units - offset);
            CrowdSnapshotPacket p = new CrowdSnapshotPacket(dim, ox, oy, oz, offset, len, c, chunkCount, units,
                    new ResourceLocation[]{type}, id, x, y, z, yaw, walkPos, walkSpeed,
                    headYaw, headPitch, attack, hurt, teamFlags, age);
            // 真实编码 -> 解码
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            p.encode(buf);
            maxBytes = Math.max(maxBytes, buf.readableBytes());
            CrowdSnapshotPacket back = new CrowdSnapshotPacket(buf);
            check(back.count == len, units + "：第 " + c + " 包单位数应为 " + len + "，实际 " + back.count);
            check(back.chunkIndex == c && back.chunkCount == chunkCount,
                    units + "：第 " + c + " 包的拆包编号应正确");
            check(back.totalUnits == units, units + "：第 " + c + " 包应带上一帧总数 " + units);
            sent.add(back);
        }
        check(maxBytes < 1_000_000, units + "：单包应低于 1 MB（实测最大 " + maxBytes + " 字节）");
        System.out.println("[ ok ] " + units + " 只 → " + chunkCount + " 个包，最大单包 " + maxBytes + " 字节");

        // 客户端逐包收，收齐后应一台不落
        ClientCrowdData.clearAll();
        ClientCrowdData data = ClientCrowdData.get(dim);
        boolean sawPartial = false;
        for (int i = 0; i < sent.size(); i++) {
            ClientCrowdData.accept(sent.get(i));
            if (i < sent.size() - 1 && data.count != 0) {
                sawPartial = true;   // 收齐之前不应提交半帧
            }
        }
        check(!sawPartial, units + "：收齐之前不应把半帧当成完整帧提交");
        check(data.count == units, units + "：客户端在场数量应为 " + units + "，实际 " + data.count);

        // 抽查若干单位的数值
        int[] probes = {0, units / 3, units / 2, units - 1};
        for (int idProbe : probes) {
            float ex = (float) (ox + x[idProbe] / 32.0D);
            float ez = (float) (oz + z[idProbe] / 32.0D);
            check(Math.abs(data.curX[idProbe] - ex) < 1.0E-3F,
                    units + "：单位 " + idProbe + " 的 X 应还原（" + data.curX[idProbe] + " vs " + ex + "）");
            check(Math.abs(data.curZ[idProbe] - ez) < 1.0E-3F,
                    units + "：单位 " + idProbe + " 的 Z 应还原");
            check(Math.abs(data.curYaw[idProbe] - ((yaw[idProbe] & 0xFF) / 256.0F * 360.0F)) < 1.0E-3F,
                    units + "：单位 " + idProbe + " 的朝向应还原");
            check(Math.abs(data.curWalkPos[idProbe]
                    - ((walkPos[idProbe] & 0xFFFF) / 65535.0F * CrowdManager.WALK_WRAP)) < 1.0E-2F,
                    units + "：单位 " + idProbe + " 的行走相位应还原");
        }
        check(data.types.length == 1 && type.equals(data.types[0]), units + "：类型表应还原");

        // 插值前提：第二帧应有「上一帧」值可用
        ClientCrowdData.accept(sent.get(0));
        check(Math.abs(data.prvX(0) - data.curX[0]) < 1.0E-3F,
                units + "：第二帧应能用上一帧值插值");
    }

    private static void testSinglePacketCase() {
        ResourceLocation dim = ResourceLocation.tryParse("minecraft:the_nether");
        CrowdSnapshotPacket p = new CrowdSnapshotPacket(dim, 0, 0, 0, 1,
                new ResourceLocation[]{ResourceLocation.tryParse("minecraft:pig")},
                new short[]{0}, new short[]{32}, new short[]{0}, new short[]{0},
                new byte[]{0}, new short[]{0}, new short[]{0}, new byte[]{0}, new byte[]{0},
                new byte[]{0}, new byte[]{0}, new byte[]{0}, new short[]{0});
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        CrowdSnapshotPacket back = new CrowdSnapshotPacket(buf);
        check(back.chunkCount == 1 && back.chunkIndex == 0 && back.count == 1,
                "单包快照应保持 chunkCount=1/chunkIndex=0");
        ClientCrowdData.clearAll();
        ClientCrowdData.accept(back);
        ClientCrowdData d = ClientCrowdData.get(dim);
        check(d.count == 1, "单包快照应提交 1 个单位");
        ClientCrowdData.accept(new CrowdSnapshotPacket(dim, 0, 0, 0, 0,
                new ResourceLocation[0], new short[0], new short[0], new short[0], new short[0],
                new byte[0], new short[0], new short[0], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], new short[0]));
        check(d.count == 0, "空快照应把在场数量清成 0");
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
