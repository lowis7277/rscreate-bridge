package dev.rscreatebridge.sink;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.autocrafting.PatternProviderExternalPatternSinkFactory;
import com.refinedmods.refinedstorage.common.api.autocrafting.PlatformPatternProviderExternalPatternSink;

import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.crafter.CrafterInterfaceBlockEntity;

import java.util.Collection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Factory registered in RS2 through the public API. RS calls it for the block each Autocrafter points
 * at. The sink it returns looks up the block entity on every call (the block may be placed or removed
 * after the Autocrafter loaded) and, if it isn't one of our blocks, answers SKIPPED: the rest of the
 * world behaves exactly as without the mod.
 */
public final class BridgeSinkFactory implements PatternProviderExternalPatternSinkFactory {
    @Override
    public PlatformPatternProviderExternalPatternSink create(final ServerLevel level, final BlockPos pos,
                                                             final Direction direction) {
        return new Sink(level, pos);
    }

    private record Sink(ServerLevel level, BlockPos pos) implements PlatformPatternProviderExternalPatternSink {
        @Override
        public ExternalPatternSink.Result accept(final Collection<ResourceAmount> resources, final Action action) {
            if (!level.isLoaded(pos)) {
                return ExternalPatternSink.Result.SKIPPED;
            }
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CrafterInterfaceBlockEntity crafterInterface) {
                return crafterInterface.accept(resources, action);
            }
            if (be instanceof AssemblyControllerBlockEntity controller) {
                return controller.accept(resources, action);
            }
            return ExternalPatternSink.Result.SKIPPED;
        }

        @Override
        public boolean isEmpty() {
            if (!level.isLoaded(pos)) {
                return true;
            }
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CrafterInterfaceBlockEntity crafterInterface) {
                return crafterInterface.isEmptyForRs();
            }
            if (be instanceof AssemblyControllerBlockEntity controller) {
                return controller.isEmptyForRs();
            }
            return true;
        }
    }
}
