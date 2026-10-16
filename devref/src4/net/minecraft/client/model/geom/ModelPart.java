package net.minecraft.client.model.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public final class ModelPart {
   public static final float DEFAULT_SCALE = 1.0F;
   public float x;
   public float y;
   public float z;
   public float xRot;
   public float yRot;
   public float zRot;
   public float xScale = 1.0F;
   public float yScale = 1.0F;
   public float zScale = 1.0F;
   public boolean visible = true;
   public boolean skipDraw;
   private final List cubes;
   private final Map children;
   private PartPose initialPose = PartPose.ZERO;

   public ModelPart(List p_171306_, Map p_171307_) {
      this.cubes = p_171306_;
      this.children = p_171307_;
   }

   public PartPose storePose() {
      return PartPose.offsetAndRotation(this.x, this.y, this.z, this.xRot, this.yRot, this.zRot);
   }

   public PartPose getInitialPose() {
      return this.initialPose;
   }

   public void setInitialPose(PartPose p_233561_) {
      this.initialPose = p_233561_;
   }

   public void resetPose() {
      this.loadPose(this.initialPose);
   }

   public void loadPose(PartPose p_171323_) {
      this.x = p_171323_.x;
      this.y = p_171323_.y;
      this.z = p_171323_.z;
      this.xRot = p_171323_.xRot;
      this.yRot = p_171323_.yRot;
      this.zRot = p_171323_.zRot;
      this.xScale = 1.0F;
      this.yScale = 1.0F;
      this.zScale = 1.0F;
   }

   public void copyFrom(ModelPart p_104316_) {
      this.xScale = p_104316_.xScale;
      this.yScale = p_104316_.yScale;
      this.zScale = p_104316_.zScale;
      this.xRot = p_104316_.xRot;
      this.yRot = p_104316_.yRot;
      this.zRot = p_104316_.zRot;
      this.x = p_104316_.x;
      this.y = p_104316_.y;
      this.z = p_104316_.z;
   }

   public boolean hasChild(String p_233563_) {
      return this.children.containsKey(p_233563_);
   }

   public ModelPart getChild(String p_171325_) {
      ModelPart $$1 = (ModelPart)this.children.get(p_171325_);
      if ($$1 == null) {
         throw new NoSuchElementException("Can't find part " + p_171325_);
      } else {
         return $$1;
      }
   }

   public void setPos(float p_104228_, float p_104229_, float p_104230_) {
      this.x = p_104228_;
      this.y = p_104229_;
      this.z = p_104230_;
   }

   public void setRotation(float p_171328_, float p_171329_, float p_171330_) {
      this.xRot = p_171328_;
      this.yRot = p_171329_;
      this.zRot = p_171330_;
   }

   public void render(PoseStack p_104302_, VertexConsumer p_104303_, int p_104304_, int p_104305_) {
      this.render(p_104302_, p_104303_, p_104304_, p_104305_, 1.0F, 1.0F, 1.0F, 1.0F);
   }

   public void render(PoseStack p_104307_, VertexConsumer p_104308_, int p_104309_, int p_104310_, float p_104311_, float p_104312_, float p_104313_, float p_104314_) {
      if (this.visible) {
         if (!this.cubes.isEmpty() || !this.children.isEmpty()) {
            p_104307_.pushPose();
            this.translateAndRotate(p_104307_);
            if (!this.skipDraw) {
               this.compile(p_104307_.last(), p_104308_, p_104309_, p_104310_, p_104311_, p_104312_, p_104313_, p_104314_);
            }

            for(ModelPart $$8 : this.children.values()) {
               $$8.render(p_104307_, p_104308_, p_104309_, p_104310_, p_104311_, p_104312_, p_104313_, p_104314_);
            }

            p_104307_.popPose();
         }
      }
   }

   public void visit(PoseStack p_171310_, ModelPart.Visitor p_171311_) {
      this.visit(p_171310_, p_171311_, "");
   }

   private void visit(PoseStack p_171313_, ModelPart.Visitor p_171314_, String p_171315_) {
      if (!this.cubes.isEmpty() || !this.children.isEmpty()) {
         p_171313_.pushPose();
         this.translateAndRotate(p_171313_);
         PoseStack.Pose $$3 = p_171313_.last();

         for(int $$4 = 0; $$4 < this.cubes.size(); ++$$4) {
            p_171314_.visit($$3, p_171315_, $$4, (ModelPart.Cube)this.cubes.get($$4));
         }

         String $$5 = p_171315_ + "/";
         this.children.forEach((p_171320_, p_171321_) -> p_171321_.visit(p_171313_, p_171314_, $$5 + p_171320_));
         p_171313_.popPose();
      }
   }

   public void translateAndRotate(PoseStack p_104300_) {
      p_104300_.translate(this.x / 16.0F, this.y / 16.0F, this.z / 16.0F);
      if (this.xRot != 0.0F || this.yRot != 0.0F || this.zRot != 0.0F) {
         p_104300_.mulPose((new Quaternionf()).rotationZYX(this.zRot, this.yRot, this.xRot));
      }

      if (this.xScale != 1.0F || this.yScale != 1.0F || this.zScale != 1.0F) {
         p_104300_.scale(this.xScale, this.yScale, this.zScale);
      }

   }

   private void compile(PoseStack.Pose p_104291_, VertexConsumer p_104292_, int p_104293_, int p_104294_, float p_104295_, float p_104296_, float p_104297_, float p_104298_) {
      for(ModelPart.Cube $$8 : this.cubes) {
         $$8.compile(p_104291_, p_104292_, p_104293_, p_104294_, p_104295_, p_104296_, p_104297_, p_104298_);
      }

   }

   public ModelPart.Cube getRandomCube(RandomSource p_233559_) {
      return (ModelPart.Cube)this.cubes.get(p_233559_.nextInt(this.cubes.size()));
   }

   public boolean isEmpty() {
      return this.cubes.isEmpty();
   }

   public void offsetPos(Vector3f p_253873_) {
      this.x += p_253873_.x();
      this.y += p_253873_.y();
      this.z += p_253873_.z();
   }

   public void offsetRotation(Vector3f p_253983_) {
      this.xRot += p_253983_.x();
      this.yRot += p_253983_.y();
      this.zRot += p_253983_.z();
   }

   public void offsetScale(Vector3f p_253957_) {
      this.xScale += p_253957_.x();
      this.yScale += p_253957_.y();
      this.zScale += p_253957_.z();
   }

   public Stream getAllParts() {
      return Stream.concat(Stream.of(this), this.children.values().stream().flatMap(ModelPart::getAllParts));
   }
}
