package net.minecraft.world.level.block.state;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class BlockBehaviour$BlockStateBase extends StateHolder<Block, BlockState> {
   private final int lightEmission;
   private final boolean useShapeForLightOcclusion;
   private final boolean isAir;
   private final boolean ignitedByLava;
   /** @deprecated */
   @Deprecated
   private final boolean liquid;
   /** @deprecated */
   @Deprecated
   private boolean legacySolid;
   private final PushReaction pushReaction;
   private final MapColor mapColor;
   private final float destroySpeed;
   private final boolean requiresCorrectToolForDrops;
   private final boolean canOcclude;
   private final BlockBehaviour.StatePredicate isRedstoneConductor;
   private final BlockBehaviour.StatePredicate isSuffocating;
   private final BlockBehaviour.StatePredicate isViewBlocking;
   private final BlockBehaviour.StatePredicate hasPostProcess;
   private final BlockBehaviour.StatePredicate emissiveRendering;
   private final Optional<BlockBehaviour.OffsetFunction> offsetFunction;
   private final boolean spawnParticlesOnBreak;
   private final NoteBlockInstrument instrument;
   private final boolean replaceable;
   @Nullable
   protected BlockBehaviour.BlockStateBase.Cache cache;
   private FluidState fluidState = Fluids.EMPTY.defaultFluidState();
   private boolean isRandomlyTicking;

   protected BlockBehaviour$BlockStateBase(Block p_60608_, ImmutableMap<Property<?>, Comparable<?>> p_60609_, MapCodec<BlockState> p_60610_) {
      super(p_60608_, p_60609_, p_60610_);
      BlockBehaviour.Properties blockbehaviour$properties = p_60608_.properties;
      this.lightEmission = blockbehaviour$properties.lightEmission.applyAsInt(this.asState());
      this.useShapeForLightOcclusion = p_60608_.useShapeForLightOcclusion(this.asState());
      this.isAir = blockbehaviour$properties.isAir;
      this.ignitedByLava = blockbehaviour$properties.ignitedByLava;
      this.liquid = blockbehaviour$properties.liquid;
      this.pushReaction = blockbehaviour$properties.pushReaction;
      this.mapColor = (MapColor)blockbehaviour$properties.mapColor.apply(this.asState());
      this.destroySpeed = blockbehaviour$properties.destroyTime;
      this.requiresCorrectToolForDrops = blockbehaviour$properties.requiresCorrectToolForDrops;
      this.canOcclude = blockbehaviour$properties.canOcclude;
      this.isRedstoneConductor = blockbehaviour$properties.isRedstoneConductor;
      this.isSuffocating = blockbehaviour$properties.isSuffocating;
      this.isViewBlocking = blockbehaviour$properties.isViewBlocking;
      this.hasPostProcess = blockbehaviour$properties.hasPostProcess;
      this.emissiveRendering = blockbehaviour$properties.emissiveRendering;
      this.offsetFunction = blockbehaviour$properties.offsetFunction;
      this.spawnParticlesOnBreak = blockbehaviour$properties.spawnParticlesOnBreak;
      this.instrument = blockbehaviour$properties.instrument;
      this.replaceable = blockbehaviour$properties.replaceable;
   }

   private boolean calculateSolid() {
      if ((this.owner).properties.forceSolidOn) {
         return true;
      } else if ((this.owner).properties.forceSolidOff) {
         return false;
      } else if (this.cache == null) {
         return false;
      } else {
         VoxelShape voxelshape = this.cache.collisionShape;
         if (voxelshape.isEmpty()) {
            return false;
         } else {
            AABB aabb = voxelshape.bounds();
            if (aabb.getSize() >= 0.7291666666666666D) {
               return true;
            } else {
               return aabb.getYsize() >= 1.0D;
            }
         }
      }
   }

   public void initCache() {
      this.fluidState = this.owner.getFluidState(this.asState());
      this.isRandomlyTicking = this.owner.isRandomlyTicking(this.asState());
      if (!this.getBlock().hasDynamicShape()) {
         this.cache = new BlockBehaviour.BlockStateBase.Cache(this.asState());
      }

      this.legacySolid = this.calculateSolid();
   }

   public Block getBlock() {
      return this.owner;
   }

   public Holder<Block> getBlockHolder() {
      return this.owner.builtInRegistryHolder();
   }

   /** @deprecated */
   @Deprecated
   public boolean blocksMotion() {
      Block block = this.getBlock();
      return block != Blocks.COBWEB && block != Blocks.BAMBOO_SAPLING && this.isSolid();
   }

   /** @deprecated */
   @Deprecated
   public boolean isSolid() {
      return this.legacySolid;
   }

   public boolean isValidSpawn(BlockGetter p_60644_, BlockPos p_60645_, EntityType<?> p_60646_) {
      return this.getBlock().properties.isValidSpawn.test(this.asState(), p_60644_, p_60645_, p_60646_);
   }

   public boolean propagatesSkylightDown(BlockGetter p_60632_, BlockPos p_60633_) {
      return this.cache != null ? this.cache.propagatesSkylightDown : this.getBlock().propagatesSkylightDown(this.asState(), p_60632_, p_60633_);
   }

   public int getLightBlock(BlockGetter p_60740_, BlockPos p_60741_) {
      return this.cache != null ? this.cache.lightBlock : this.getBlock().getLightBlock(this.asState(), p_60740_, p_60741_);
   }

   public VoxelShape getFaceOcclusionShape(BlockGetter p_60656_, BlockPos p_60657_, Direction p_60658_) {
      return this.cache != null && this.cache.occlusionShapes != null ? this.cache.occlusionShapes[p_60658_.ordinal()] : Shapes.getFaceShape(this.getOcclusionShape(p_60656_, p_60657_), p_60658_);
   }

   public VoxelShape getOcclusionShape(BlockGetter p_60769_, BlockPos p_60770_) {
      return this.getBlock().getOcclusionShape(this.asState(), p_60769_, p_60770_);
   }

   public boolean hasLargeCollisionShape() {
      return this.cache == null || this.cache.largeCollisionShape;
   }

   public boolean useShapeForLightOcclusion() {
      return this.useShapeForLightOcclusion;
   }

   /** @deprecated */
   @Deprecated
   public int getLightEmission() {
      return this.lightEmission;
   }

   public boolean isAir() {
      return this.getBlock().isAir((BlockState)this);
   }

   public boolean ignitedByLava() {
      return this.ignitedByLava;
   }

   /** @deprecated */
   @Deprecated
   public boolean liquid() {
      return this.liquid;
   }

   public MapColor getMapColor(BlockGetter p_285002_, BlockPos p_285293_) {
      return this.getBlock().getMapColor(this.asState(), p_285002_, p_285293_, this.mapColor);
   }

   /** @deprecated */
   @Deprecated
   public BlockState rotate(Rotation p_60718_) {
      return this.getBlock().rotate(this.asState(), p_60718_);
   }

   public BlockState mirror(Mirror p_60716_) {
      return this.getBlock().mirror(this.asState(), p_60716_);
   }

   public RenderShape getRenderShape() {
      return this.getBlock().getRenderShape(this.asState());
   }

   public boolean emissiveRendering(BlockGetter p_60789_, BlockPos p_60790_) {
      return this.emissiveRendering.test(this.asState(), p_60789_, p_60790_);
   }

   public float getShadeBrightness(BlockGetter p_60793_, BlockPos p_60794_) {
      return this.getBlock().getShadeBrightness(this.asState(), p_60793_, p_60794_);
   }

   public boolean isRedstoneConductor(BlockGetter p_60797_, BlockPos p_60798_) {
      return this.isRedstoneConductor.test(this.asState(), p_60797_, p_60798_);
   }

   public boolean isSignalSource() {
      return this.getBlock().isSignalSource(this.asState());
   }

   public int getSignal(BlockGetter p_60747_, BlockPos p_60748_, Direction p_60749_) {
      return this.getBlock().getSignal(this.asState(), p_60747_, p_60748_, p_60749_);
   }

   public boolean hasAnalogOutputSignal() {
      return this.getBlock().hasAnalogOutputSignal(this.asState());
   }

   public int getAnalogOutputSignal(Level p_60675_, BlockPos p_60676_) {
      return this.getBlock().getAnalogOutputSignal(this.asState(), p_60675_, p_60676_);
   }

   public float getDestroySpeed(BlockGetter p_60801_, BlockPos p_60802_) {
      return this.destroySpeed;
   }

   public float getDestroyProgress(Player p_60626_, BlockGetter p_60627_, BlockPos p_60628_) {
      return this.getBlock().getDestroyProgress(this.asState(), p_60626_, p_60627_, p_60628_);
   }

   public int getDirectSignal(BlockGetter p_60776_, BlockPos p_60777_, Direction p_60778_) {
      return this.getBlock().getDirectSignal(this.asState(), p_60776_, p_60777_, p_60778_);
   }

   public PushReaction getPistonPushReaction() {
      PushReaction reaction = this.getBlock().getPistonPushReaction(this.asState());
      return reaction != null ? reaction : this.pushReaction;
   }

   public boolean isSolidRender(BlockGetter p_60805_, BlockPos p_60806_) {
      if (this.cache != null) {
         return this.cache.solidRender;
      } else {
         BlockState blockstate = this.asState();
         return blockstate.canOcclude() ? Block.isShapeFullBlock(blockstate.getOcclusionShape(p_60805_, p_60806_)) : false;
      }
   }

   public boolean canOcclude() {
      return this.canOcclude;
   }

   public boolean skipRendering(BlockState p_60720_, Direction p_60721_) {
      return this.getBlock().skipRendering(this.asState(), p_60720_, p_60721_);
   }

   public VoxelShape getShape(BlockGetter p_60809_, BlockPos p_60810_) {
      return this.getShape(p_60809_, p_60810_, CollisionContext.empty());
   }

   public VoxelShape getShape(BlockGetter p_60652_, BlockPos p_60653_, CollisionContext p_60654_) {
      return this.getBlock().getShape(this.asState(), p_60652_, p_60653_, p_60654_);
   }

   public VoxelShape getCollisionShape(BlockGetter p_60813_, BlockPos p_60814_) {
      return this.cache != null ? this.cache.collisionShape : this.getCollisionShape(p_60813_, p_60814_, CollisionContext.empty());
   }

   public VoxelShape getCollisionShape(BlockGetter p_60743_, BlockPos p_60744_, CollisionContext p_60745_) {
      return this.getBlock().getCollisionShape(this.asState(), p_60743_, p_60744_, p_60745_);
   }

   public VoxelShape getBlockSupportShape(BlockGetter p_60817_, BlockPos p_60818_) {
      return this.getBlock().getBlockSupportShape(this.asState(), p_60817_, p_60818_);
   }

   public VoxelShape getVisualShape(BlockGetter p_60772_, BlockPos p_60773_, CollisionContext p_60774_) {
      return this.getBlock().getVisualShape(this.asState(), p_60772_, p_60773_, p_60774_);
   }

   public VoxelShape getInteractionShape(BlockGetter p_60821_, BlockPos p_60822_) {
      return this.getBlock().getInteractionShape(this.asState(), p_60821_, p_60822_);
   }

   public final boolean entityCanStandOn(BlockGetter p_60635_, BlockPos p_60636_, Entity p_60637_) {
      return this.entityCanStandOnFace(p_60635_, p_60636_, p_60637_, Direction.UP);
   }

   public final boolean entityCanStandOnFace(BlockGetter p_60639_, BlockPos p_60640_, Entity p_60641_, Direction p_60642_) {
      return Block.isFaceFull(this.getCollisionShape(p_60639_, p_60640_, CollisionContext.of(p_60641_)), p_60642_);
   }

   public Vec3 getOffset(BlockGetter p_60825_, BlockPos p_60826_) {
      return (Vec3)this.offsetFunction.map((p_273089_) -> p_273089_.evaluate(this.asState(), p_60825_, p_60826_)).orElse(Vec3.ZERO);
   }

   public boolean hasOffsetFunction() {
      return !this.offsetFunction.isEmpty();
   }

   public boolean triggerEvent(Level p_60678_, BlockPos p_60679_, int p_60680_, int p_60681_) {
      return this.getBlock().triggerEvent(this.asState(), p_60678_, p_60679_, p_60680_, p_60681_);
   }

   /** @deprecated */
   @Deprecated
   public void neighborChanged(Level p_60691_, BlockPos p_60692_, Block p_60693_, BlockPos p_60694_, boolean p_60695_) {
      this.getBlock().neighborChanged(this.asState(), p_60691_, p_60692_, p_60693_, p_60694_, p_60695_);
   }

   public final void updateNeighbourShapes(LevelAccessor p_60702_, BlockPos p_60703_, int p_60704_) {
      this.updateNeighbourShapes(p_60702_, p_60703_, p_60704_, 512);
   }

   public final void updateNeighbourShapes(LevelAccessor p_60706_, BlockPos p_60707_, int p_60708_, int p_60709_) {
      BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

      for(Direction direction : BlockBehaviour.UPDATE_SHAPE_ORDER) {
         blockpos$mutableblockpos.setWithOffset(p_60707_, direction);
         p_60706_.neighborShapeChanged(direction.getOpposite(), this.asState(), blockpos$mutableblockpos, p_60707_, p_60708_, p_60709_);
      }

   }

   public final void updateIndirectNeighbourShapes(LevelAccessor p_60759_, BlockPos p_60760_, int p_60761_) {
      this.updateIndirectNeighbourShapes(p_60759_, p_60760_, p_60761_, 512);
   }

   public void updateIndirectNeighbourShapes(LevelAccessor p_60763_, BlockPos p_60764_, int p_60765_, int p_60766_) {
      this.getBlock().updateIndirectNeighbourShapes(this.asState(), p_60763_, p_60764_, p_60765_, p_60766_);
   }

   public void onPlace(Level p_60697_, BlockPos p_60698_, BlockState p_60699_, boolean p_60700_) {
      this.getBlock().onPlace(this.asState(), p_60697_, p_60698_, p_60699_, p_60700_);
   }

   public void onRemove(Level p_60754_, BlockPos p_60755_, BlockState p_60756_, boolean p_60757_) {
      this.getBlock().onRemove(this.asState(), p_60754_, p_60755_, p_60756_, p_60757_);
   }

   public void tick(ServerLevel p_222964_, BlockPos p_222965_, RandomSource p_222966_) {
      this.getBlock().tick(this.asState(), p_222964_, p_222965_, p_222966_);
   }

   public void randomTick(ServerLevel p_222973_, BlockPos p_222974_, RandomSource p_222975_) {
      this.getBlock().randomTick(this.asState(), p_222973_, p_222974_, p_222975_);
   }

   public void entityInside(Level p_60683_, BlockPos p_60684_, Entity p_60685_) {
      this.getBlock().entityInside(this.asState(), p_60683_, p_60684_, p_60685_);
   }

   public void spawnAfterBreak(ServerLevel p_222968_, BlockPos p_222969_, ItemStack p_222970_, boolean p_222971_) {
      this.getBlock().spawnAfterBreak(this.asState(), p_222968_, p_222969_, p_222970_, p_222971_);
   }

   public List<ItemStack> getDrops(LootParams.Builder p_287688_) {
      return this.getBlock().getDrops(this.asState(), p_287688_);
   }

   public InteractionResult use(Level p_60665_, Player p_60666_, InteractionHand p_60667_, BlockHitResult p_60668_) {
      return this.getBlock().use(this.asState(), p_60665_, p_60668_.getBlockPos(), p_60666_, p_60667_, p_60668_);
   }

   public void attack(Level p_60687_, BlockPos p_60688_, Player p_60689_) {
      this.getBlock().attack(this.asState(), p_60687_, p_60688_, p_60689_);
   }

   public boolean isSuffocating(BlockGetter p_60829_, BlockPos p_60830_) {
      return this.isSuffocating.test(this.asState(), p_60829_, p_60830_);
   }

   public boolean isViewBlocking(BlockGetter p_60832_, BlockPos p_60833_) {
      return this.isViewBlocking.test(this.asState(), p_60832_, p_60833_);
   }

   public BlockState updateShape(Direction p_60729_, BlockState p_60730_, LevelAccessor p_60731_, BlockPos p_60732_, BlockPos p_60733_) {
      return this.getBlock().updateShape(this.asState(), p_60729_, p_60730_, p_60731_, p_60732_, p_60733_);
   }

   public boolean isPathfindable(BlockGetter p_60648_, BlockPos p_60649_, PathComputationType p_60650_) {
      return this.getBlock().isPathfindable(this.asState(), p_60648_, p_60649_, p_60650_);
   }

   public boolean canBeReplaced(BlockPlaceContext p_60630_) {
      return this.getBlock().canBeReplaced(this.asState(), p_60630_);
   }

   public boolean canBeReplaced(Fluid p_60723_) {
      return this.getBlock().canBeReplaced(this.asState(), p_60723_);
   }

   public boolean canBeReplaced() {
      return this.replaceable;
   }

   public boolean canSurvive(LevelReader p_60711_, BlockPos p_60712_) {
      return this.getBlock().canSurvive(this.asState(), p_60711_, p_60712_);
   }

   public boolean hasPostProcess(BlockGetter p_60836_, BlockPos p_60837_) {
      return this.hasPostProcess.test(this.asState(), p_60836_, p_60837_);
   }

   @Nullable
   public MenuProvider getMenuProvider(Level p_60751_, BlockPos p_60752_) {
      return this.getBlock().getMenuProvider(this.asState(), p_60751_, p_60752_);
   }

   public boolean is(TagKey<Block> p_204337_) {
      return this.getBlock().builtInRegistryHolder().is(p_204337_);
   }

   public boolean is(TagKey<Block> p_204339_, Predicate<BlockBehaviour$BlockStateBase> p_204340_) {
      return this.is(p_204339_) && p_204340_.test(this);
   }

   public boolean is(HolderSet<Block> p_204342_) {
      return p_204342_.contains(this.getBlock().builtInRegistryHolder());
   }

   public Stream<TagKey<Block>> getTags() {
      return this.getBlock().builtInRegistryHolder().tags();
   }

   public boolean hasBlockEntity() {
      return this.getBlock() instanceof EntityBlock;
   }

   @Nullable
   public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level p_155945_, BlockEntityType<T> p_155946_) {
      return this.getBlock() instanceof EntityBlock ? ((EntityBlock)this.getBlock()).getTicker(p_155945_, this.asState(), p_155946_) : null;
   }

   public boolean is(Block p_60714_) {
      return this.getBlock() == p_60714_;
   }

   public FluidState getFluidState() {
      return this.fluidState;
   }

   public boolean isRandomlyTicking() {
      return this.isRandomlyTicking;
   }

   public long getSeed(BlockPos p_60727_) {
      return this.getBlock().getSeed(this.asState(), p_60727_);
   }

   public SoundType getSoundType() {
      return this.getBlock().getSoundType(this.asState());
   }

   public void onProjectileHit(Level p_60670_, BlockState p_60671_, BlockHitResult p_60672_, Projectile p_60673_) {
      this.getBlock().onProjectileHit(p_60670_, p_60671_, p_60672_, p_60673_);
   }

   public boolean isFaceSturdy(BlockGetter p_60784_, BlockPos p_60785_, Direction p_60786_) {
      return this.isFaceSturdy(p_60784_, p_60785_, p_60786_, SupportType.FULL);
   }

   public boolean isFaceSturdy(BlockGetter p_60660_, BlockPos p_60661_, Direction p_60662_, SupportType p_60663_) {
      return this.cache != null ? this.cache.isFaceSturdy(p_60662_, p_60663_) : p_60663_.isSupporting(this.asState(), p_60660_, p_60661_, p_60662_);
   }

   public boolean isCollisionShapeFullBlock(BlockGetter p_60839_, BlockPos p_60840_) {
      return this.cache != null ? this.cache.isCollisionShapeFullBlock : this.getBlock().isCollisionShapeFullBlock(this.asState(), p_60839_, p_60840_);
   }

   protected abstract BlockState asState();

   public boolean requiresCorrectToolForDrops() {
      return this.requiresCorrectToolForDrops;
   }

   public boolean shouldSpawnParticlesOnBreak() {
      return this.spawnParticlesOnBreak;
   }

   public NoteBlockInstrument instrument() {
      return this.instrument;
   }
}
