package net.minecraft.client.model.geom;

import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
class ModelPart$Polygon {
   public final ModelPart$Vertex[] vertices;
   public final Vector3f normal;

   public ModelPart$Polygon(ModelPart$Vertex[] p_104362_, float p_104363_, float p_104364_, float p_104365_, float p_104366_, float p_104367_, float p_104368_, boolean p_104369_, Direction p_104370_) {
      this.vertices = p_104362_;
      float $$9 = 0.0F / p_104367_;
      float $$10 = 0.0F / p_104368_;
      p_104362_[0] = p_104362_[0].remap(p_104365_ / p_104367_ - $$9, p_104364_ / p_104368_ + $$10);
      p_104362_[1] = p_104362_[1].remap(p_104363_ / p_104367_ + $$9, p_104364_ / p_104368_ + $$10);
      p_104362_[2] = p_104362_[2].remap(p_104363_ / p_104367_ + $$9, p_104366_ / p_104368_ - $$10);
      p_104362_[3] = p_104362_[3].remap(p_104365_ / p_104367_ - $$9, p_104366_ / p_104368_ - $$10);
      if (p_104369_) {
         int $$11 = p_104362_.length;

         for(int $$12 = 0; $$12 < $$11 / 2; ++$$12) {
            ModelPart$Vertex $$13 = p_104362_[$$12];
            p_104362_[$$12] = p_104362_[$$11 - 1 - $$12];
            p_104362_[$$11 - 1 - $$12] = $$13;
         }
      }

      this.normal = p_104370_.step();
      if (p_104369_) {
         this.normal.mul(-1.0F, 1.0F, 1.0F);
      }

   }
}
