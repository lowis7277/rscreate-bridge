package dev.rscreatebridge.common;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import java.util.List;
import java.util.function.BiFunction;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Simple block with a network block entity: right click shows its status to whoever clicked. */
public abstract class BridgeBlock<T extends BridgeNodeBlockEntity> extends Block implements EntityBlock, IWrenchable {
    private final BiFunction<BlockPos, BlockState, T> factory;
    private final Class<T> type;

    protected BridgeBlock(final Properties properties, final Class<T> type, final BiFunction<BlockPos, BlockState, T> factory) {
        super(properties);
        this.type = type;
        this.factory = factory;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return factory.apply(pos, state);
    }

    @Nullable
    @Override
    public <E extends BlockEntity> BlockEntityTicker<E> getTicker(final Level level, final BlockState state,
                                                                  final BlockEntityType<E> blockEntityType) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (type.isInstance(be)) {
                type.cast(be).serverTick();
            }
        };
    }

    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            @Nullable final LivingEntity placer, final ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player && type.isInstance(level.getBlockEntity(pos))) {
            type.cast(level.getBlockEntity(pos)).setOwner(player.getUUID());
        }
    }

    @Override
    protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos,
                                               final Player player, final BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) {
            // With something in hand (for example a Create wrench) the click belongs to the item, not the block.
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (!type.isInstance(be)) {
            return InteractionResult.PASS;
        }
        final T bridge = type.cast(be);
        if (player.isShiftKeyDown()) {
            return onShiftUse(bridge, player);
        }
        for (final Component line : describe(bridge)) {
            player.sendSystemMessage(line);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Create wrench: a click turns the block 90 degrees horizontally (whatever face is clicked).
     * Shift + click picks it up, like any Create block. The Crafter Interface has no front.
     */
    @Override
    public BlockState getRotatedBlockState(final BlockState state, final Direction targetedFace) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING,
                state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise());
        }
        return state;
    }

    protected InteractionResult onShiftUse(final T be, final Player player) {
        for (final Component line : describe(be)) {
            player.sendSystemMessage(line);
        }
        return InteractionResult.CONSUME;
    }

    protected abstract List<Component> describe(T be);

    protected static Component noPermission() {
        return Component.translatable("rscreatebridge.no_permission").withStyle(ChatFormatting.RED);
    }
}
