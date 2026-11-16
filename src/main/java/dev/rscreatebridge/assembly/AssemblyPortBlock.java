package dev.rscreatebridge.assembly;

import dev.rscreatebridge.common.BridgeBlock;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * The front (FACING) faces the station's depot or basin. Placed by clicking the side of a depot or basin,
 * it faces that block; otherwise it looks for one next to it; if there is none, it faces where the
 * player is looking.
 */
public class AssemblyPortBlock extends BridgeBlock<AssemblyPortBlockEntity> {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final ResourceLocation DEPOT = ResourceLocation.fromNamespaceAndPath("create", "depot");

    public AssemblyPortBlock(final Properties properties) {
        super(properties, AssemblyPortBlockEntity.class, AssemblyPortBlockEntity::new);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    private static final ResourceLocation BASIN = ResourceLocation.fromNamespaceAndPath("create", "basin");

    /** Is it a depot or a basin? (the two possible stations) */
    private static boolean isDepot(final Level level, final BlockPos pos) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return DEPOT.equals(id) || BASIN.equals(id);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        final Direction clicked = context.getClickedFace().getOpposite();
        if (clicked.getAxis().isHorizontal() && isDepot(level, pos.relative(clicked))) {
            return defaultBlockState().setValue(FACING, clicked);
        }
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            if (isDepot(level, pos.relative(side))) {
                return defaultBlockState().setValue(FACING, side);
            }
        }
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
    protected List<Component> describe(final AssemblyPortBlockEntity be) {
        return be.describe();
    }
}
