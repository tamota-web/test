package net.minecraft.client.model.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@FunctionalInterface
@OnlyIn(Dist.CLIENT)
public interface ModelPart$Visitor {
   void visit(PoseStack.Pose p_171342_, String p_171343_, int p_171344_, ModelPart$Cube p_171345_);
}
