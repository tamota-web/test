package net.minecraft.client.model.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

@OnlyIn(Dist.CLIENT)
public class ModelPart$Cube {
   private final ModelPart$Polygon[] polygons;
   public final float minX;
   public final float minY;
   public final float minZ;
   public final float maxX;
   public final float maxY;
   public final float maxZ;

   public ModelPart$Cube(int p_273701_, int p_273034_, float p_272824_, float p_273777_, float p_273748_, float p_273722_, float p_273763_, float p_272823_, float p_272945_, float p_272790_, float p_272870_, boolean p_273589_, float p_273591_, float p_273313_, Set p_273291_) {
      this.minX = p_272824_;
      this.minY = p_273777_;
      this.minZ = p_273748_;
      this.maxX = p_272824_ + p_273722_;
      this.maxY = p_273777_ + p_273763_;
      this.maxZ = p_273748_ + p_272823_;
      this.polygons = new ModelPart$Polygon[p_273291_.size()];
      float $$15 = p_272824_ + p_273722_;
      float $$16 = p_273777_ + p_273763_;
      float $$17 = p_273748_ + p_272823_;
      p_272824_ -= p_272945_;
      p_273777_ -= p_272790_;
      p_273748_ -= p_272870_;
      $$15 += p_272945_;
      $$16 += p_272790_;
      $$17 += p_272870_;
      if (p_273589_) {
         float $$18 = $$15;
         $$15 = p_272824_;
         p_272824_ = $$18;
      }

      ModelPart$Vertex $$19 = new ModelPart$Vertex(p_272824_, p_273777_, p_273748_, 0.0F, 0.0F);
      ModelPart$Vertex $$20 = new ModelPart$Vertex($$15, p_273777_, p_273748_, 0.0F, 8.0F);
      ModelPart$Vertex $$21 = new ModelPart$Vertex($$15, $$16, p_273748_, 8.0F, 8.0F);
      ModelPart$Vertex $$22 = new ModelPart$Vertex(p_272824_, $$16, p_273748_, 8.0F, 0.0F);
      ModelPart$Vertex $$23 = new ModelPart$Vertex(p_272824_, p_273777_, $$17, 0.0F, 0.0F);
      ModelPart$Vertex $$24 = new ModelPart$Vertex($$15, p_273777_, $$17, 0.0F, 8.0F);
      ModelPart$Vertex $$25 = new ModelPart$Vertex($$15, $$16, $$17, 8.0F, 8.0F);
      ModelPart$Vertex $$26 = new ModelPart$Vertex(p_272824_, $$16, $$17, 8.0F, 0.0F);
      float $$27 = (float)p_273701_;
      float $$28 = (float)p_273701_ + p_272823_;
      float $$29 = (float)p_273701_ + p_272823_ + p_273722_;
      float $$30 = (float)p_273701_ + p_272823_ + p_273722_ + p_273722_;
      float $$31 = (float)p_273701_ + p_272823_ + p_273722_ + p_272823_;
      float $$32 = (float)p_273701_ + p_272823_ + p_273722_ + p_272823_ + p_273722_;
      float $$33 = (float)p_273034_;
      float $$34 = (float)p_273034_ + p_272823_;
      float $$35 = (float)p_273034_ + p_272823_ + p_273763_;
      int $$36 = 0;
      if (p_273291_.contains(Direction.DOWN)) {
         this.polygons[$$36++] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$24, $$23, $$19, $$20}, $$28, $$33, $$29, $$34, p_273591_, p_273313_, p_273589_, Direction.DOWN);
      }

      if (p_273291_.contains(Direction.UP)) {
         this.polygons[$$36++] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$21, $$22, $$26, $$25}, $$29, $$34, $$30, $$33, p_273591_, p_273313_, p_273589_, Direction.UP);
      }

      if (p_273291_.contains(Direction.WEST)) {
         this.polygons[$$36++] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$19, $$23, $$26, $$22}, $$27, $$34, $$28, $$35, p_273591_, p_273313_, p_273589_, Direction.WEST);
      }

      if (p_273291_.contains(Direction.NORTH)) {
         this.polygons[$$36++] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$20, $$19, $$22, $$21}, $$28, $$34, $$29, $$35, p_273591_, p_273313_, p_273589_, Direction.NORTH);
      }

      if (p_273291_.contains(Direction.EAST)) {
         this.polygons[$$36++] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$24, $$20, $$21, $$25}, $$29, $$34, $$31, $$35, p_273591_, p_273313_, p_273589_, Direction.EAST);
      }

      if (p_273291_.contains(Direction.SOUTH)) {
         this.polygons[$$36] = new ModelPart$Polygon(new ModelPart$Vertex[]{$$23, $$24, $$25, $$26}, $$31, $$34, $$32, $$35, p_273591_, p_273313_, p_273589_, Direction.SOUTH);
      }

   }

   public void compile(PoseStack.Pose p_171333_, VertexConsumer p_171334_, int p_171335_, int p_171336_, float p_171337_, float p_171338_, float p_171339_, float p_171340_) {
      Matrix4f $$8 = p_171333_.pose();
      Matrix3f $$9 = p_171333_.normal();

      for(ModelPart$Polygon $$10 : this.polygons) {
         Vector3f $$11 = $$9.transform(new Vector3f($$10.normal));
         float $$12 = $$11.x();
         float $$13 = $$11.y();
         float $$14 = $$11.z();

         for(ModelPart$Vertex $$15 : $$10.vertices) {
            float $$16 = $$15.pos.x() / 16.0F;
            float $$17 = $$15.pos.y() / 16.0F;
            float $$18 = $$15.pos.z() / 16.0F;
            Vector4f $$19 = $$8.transform(new Vector4f($$16, $$17, $$18, 1.0F));
            p_171334_.vertex($$19.x(), $$19.y(), $$19.z(), p_171337_, p_171338_, p_171339_, p_171340_, $$15.u, $$15.v, p_171336_, p_171335_, $$12, $$13, $$14);
         }
      }

   }
}
