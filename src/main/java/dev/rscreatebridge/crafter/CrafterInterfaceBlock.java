package dev.rscreatebridge.crafter;

import dev.rscreatebridge.common.BridgeBlock;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class CrafterInterfaceBlock extends BridgeBlock<CrafterInterfaceBlockEntity> {
    public CrafterInterfaceBlock(final Properties properties) {
        super(properties, CrafterInterfaceBlockEntity.class, CrafterInterfaceBlockEntity::new);
    }

    @Override
    protected List<Component> describe(final CrafterInterfaceBlockEntity be) {
        return be.describe();
    }

    @Override
    protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block,
                                   final BlockPos fromPos, final boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        // The output capability depends on whether the neighbor is a crafter: tell the caches.
        level.invalidateCapabilities(pos);
        if (level.getBlockEntity(pos) instanceof CrafterInterfaceBlockEntity be) {
            be.invalidateGrid();
        }
    }
}
