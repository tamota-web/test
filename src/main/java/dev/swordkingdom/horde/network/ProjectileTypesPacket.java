package dev.swordkingdom.horde.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 投掷物类型表下发（服务端 -&gt; 客户端）。
 *
 * 客户端要按「索引 → 实体类型 id」去烘焙每个投掷物自己的模型
 * （箭的渲染器、雪球的物品渲染器、火球的渲染器都不一样），
 * 所以必须知道索引背后是哪个 {@code EntityType}。
 *
 * 只在类型表**版本变化**时发（见 {@link ProjectileSync}），
 * 因此正常情况下每个玩家每局只会收到一两次，不占带宽。
 */
public class ProjectileTypesPacket {

    public final ResourceLocation dimension;
    public final ResourceLocation[] types;

    public ProjectileTypesPacket(ResourceLocation dimension, ResourceLocation[] types) {
        this.dimension = dimension;
        this.types = types;
    }

    public ProjectileTypesPacket(FriendlyByteBuf buf) {
        this.dimension = buf.readResourceLocation();
        int n = buf.readVarInt();
        if (n < 0) {
            n = 0;
        }
        if (n > 32) {
            n = 32;
        }
        this.types = new ResourceLocation[n];
        for (int i = 0; i < n; i++) {
            types[i] = buf.readResourceLocation();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(types.length);
        for (ResourceLocation t : types) {
            buf.writeResourceLocation(t);
        }
    }
}
