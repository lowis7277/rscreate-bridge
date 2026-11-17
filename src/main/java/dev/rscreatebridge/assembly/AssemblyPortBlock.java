package dev.rscreatebridge.assembly;

import dev.rscreatebridge.common.BridgeBlock;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    // ------------------------------------------------------------------ shape (head, neck and cable stub)

    /** The model's boxes facing north (pixels): head towards the station, neck, cable stub on the back. */
    private static final int[][] BOXES_NORTH = {
        {2, 2, 0, 14, 14, 3},
        {4, 4, 3, 12, 12, 7},
        {6, 6, 7, 10, 10, 16},
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (final Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (final int[] b : BOXES_NORTH) {
                shape = Shapes.or(shape, rotated(b, facing));
            }
            SHAPES.put(facing, shape.optimize());
        }
    }

    /** Turns a north-facing box (x1 y1 z1 x2 y2 z2, pixels) to the given horizontal facing. */
    private static VoxelShape rotated(final int[] b, final Direction facing) {
        return switch (facing) {
            case EAST -> Block.box(16 - b[5], b[1], b[0], 16 - b[2], b[4], b[3]);
            case SOUTH -> Block.box(16 - b[3], b[1], 16 - b[5], 16 - b[0], b[4], 16 - b[2]);
            case WEST -> Block.box(b[2], b[1], 16 - b[3], b[5], b[4], 16 - b[0]);
            default -> Block.box(b[0], b[1], b[2], b[3], b[4], b[5]);
        };
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos,
                                  final CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
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

    /** Breaking (or picking up) a port in use hands the inputs at its station back to the controller. */
    @Override
    protected void onRemove(final BlockState state, final Level level, final BlockPos pos, final BlockState newState,
                            final boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()
            && level.getBlockEntity(pos) instanceof AssemblyPortBlockEntity port) {
            final BlockPos controller = port.claimedBy();
            if (controller != null && level.isLoaded(controller)
                && level.getBlockEntity(controller) instanceof AssemblyControllerBlockEntity be) {
                be.portRemoved(port);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
