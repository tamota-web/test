package net.minecraft.client.model.geom;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
class ModelPart$Vertex {
   public final Vector3f pos;
   public final float u;
   public final float v;

   public ModelPart$Vertex(float p_104375_, float p_104376_, float p_104377_, float p_104378_, float p_104379_) {
      this(new Vector3f(p_104375_, p_104376_, p_104377_), p_104378_, p_104379_);
   }

   public ModelPart$Vertex remap(float p_104385_, float p_104386_) {
      return new ModelPart$Vertex(this.pos, p_104385_, p_104386_);
   }

   public ModelPart$Vertex(Vector3f p_253667_, float p_253662_, float p_254308_) {
      this.pos = p_253667_;
      this.u = p_253662_;
      this.v = p_254308_;
   }
}
