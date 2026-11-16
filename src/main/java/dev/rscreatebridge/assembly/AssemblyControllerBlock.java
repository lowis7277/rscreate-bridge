package dev.rscreatebridge.assembly;

import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.common.BridgeBlock;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * The Assembly Controller. It joins the RS network (the Autocrafter points at it) and uses the
 * Assembly Ports on that same network. FACING is purely cosmetic.
 */
public class AssemblyControllerBlock extends BridgeBlock<AssemblyControllerBlockEntity> {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public AssemblyControllerBlock(final Properties properties) {
        super(properties, AssemblyControllerBlockEntity.class, AssemblyControllerBlockEntity::new);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected List<Component> describe(final AssemblyControllerBlockEntity be) {
        return be.describe();
    }

    /** Window to confirm an abort with a second shift + click (3 s). */
    private static final long CONFIRM_TICKS = 60;

    /**
     * Shift + right click with an empty hand: abort and return everything (owner or OP). The first click only
     * warns; it has to be repeated within 3 s to abort (so nothing gets cancelled by accident).
     */
    @Override
    protected InteractionResult onShiftUse(final AssemblyControllerBlockEntity be, final Player player) {
        if (!be.canAdminister(player)) {
            player.sendSystemMessage(noPermission());
            return InteractionResult.CONSUME;
        }
        if (be.isEmptyForRs()) {
            player.sendSystemMessage(Component.translatable("rscreatebridge.assembly.nothing_to_abort").withStyle(ChatFormatting.GRAY));
            return InteractionResult.CONSUME;
        }
        final long now = player.level().getGameTime();
        if (!be.confirmAbort(player.getUUID(), now, CONFIRM_TICKS)) {
            player.sendSystemMessage(Component.translatable("rscreatebridge.assembly.confirm_abort", be.pendingCount())
                .withStyle(ChatFormatting.YELLOW));
            return InteractionResult.CONSUME;
        }
        RSCreateBridge.LOGGER.info("{} aborted the assembly controller at {}", player.getGameProfile().getName(),
            be.getBlockPos().toShortString());
        be.abortAll(true);
        player.sendSystemMessage(Component.translatable("rscreatebridge.assembly.aborted").withStyle(ChatFormatting.GREEN));
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(final BlockState state, final Level level, final BlockPos pos, final BlockState newState,
                            final boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()
            && level.getBlockEntity(pos) instanceof AssemblyControllerBlockEntity be) {
            be.abortAll(true);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
