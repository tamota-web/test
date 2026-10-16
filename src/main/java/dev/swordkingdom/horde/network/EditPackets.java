package dev.swordkingdom.horde.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.swordkingdom.horde.edit.TypeOverride;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 覆盖表的网络包。
 *
 * 直接传 JSON 字符串：覆盖表字段多、以后还会加，用 JSON 比逐字段序列化省事且不会两边不同步。
 *  - {@link Request}：客户端进 GUI 时请求当前覆盖表；
 *  - {@link Sync}：服务端把整表下发（登录/改动后）；
 *  - {@link Set}：客户端提交一条改动（type = "" 表示「还原全部」）；
 *  - {@link OpenGui}：服务端让客户端打开配置界面（`/horde edit` 用）。
 */
public final class EditPackets {

    private static final Gson GSON = new GsonBuilder().create();

    private EditPackets() {}

    public static String writeTable() {
        Map<String, TypeOverride> raw = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TypeOverride> e : TypeOverride.all().entrySet()) {
            raw.put(e.getKey().toString(), e.getValue());
        }
        return GSON.toJson(raw);
    }

    public static void readTable(String json) {
        TypeOverride.all().clear();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            Map<String, TypeOverride> raw = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, TypeOverride>>() { }.getType());
            if (raw == null) {
                return;
            }
            for (Map.Entry<String, TypeOverride> e : raw.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if (id != null && e.getValue() != null) {
                    TypeOverride.all().put(id, e.getValue());
                }
            }
        } catch (Throwable ignored) {
            // 解析失败就当空表
        }
    }

    public static String writeOne(TypeOverride override) {
        return override == null ? "" : GSON.toJson(override);
    }

    public static TypeOverride readOne(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, TypeOverride.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    /** 客户端 -> 服务端：请求整表 */
    public static final class Request {
        public Request() {}

        public Request(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}
    }

    /** 客户端 -> 服务端：提交一条覆盖（type 为空 = 还原全部） */
    public static final class Set {
        public final String type;
        public final String json;
        public final boolean reset;

        public Set(String type, String json, boolean reset) {
            this.type = type;
            this.json = json;
            this.reset = reset;
        }

        public Set(FriendlyByteBuf buf) {
            this.type = buf.readUtf(256);
            this.json = buf.readUtf(65536);
            this.reset = buf.readBoolean();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(type == null ? "" : type);
            buf.writeUtf(json == null ? "" : json);
            buf.writeBoolean(reset);
        }
    }

    /** 服务端 -> 客户端：整表下发 */
    public static final class Sync {
        public final String json;

        public Sync(String json) {
            this.json = json;
        }

        public Sync(FriendlyByteBuf buf) {
            this.json = buf.readUtf(1048576);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json == null ? "" : json);
        }
    }

    /** 服务端 -> 客户端：打开配置界面 */
    public static final class OpenGui {
        public OpenGui() {}

        public OpenGui(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}
    }
}
