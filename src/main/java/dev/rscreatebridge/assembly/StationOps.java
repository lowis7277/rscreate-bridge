package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the controller does with a station, for both station types: a depot (deployer, press or spout
 * above it) and a basin (mixer or press above it). Machine loading (deployer held item, spout fluid) is done
 * by the controller before {@link #place}, because it needs the request materials.
 */
final class StationOps {
    enum Status {
        /** The machine hasn't processed the inputs yet. */
        WAITING,
        /** Create processed the inputs: the outputs were taken. */
        DONE,
        /** The piece came back untouched (a deployer picked it up): it was taken back as is. */
        UNCHANGED,
        /** The station is empty and nothing came out: the inputs are gone. */
        EMPTY
    }

    record Poll(Status status, List<ItemStack> items, Stock fluids) {
        static Poll of(final Status status) {
            return new Poll(status, List.of(), new Stock());
        }
    }

    private StationOps() {
    }

    /** Is the station empty and ready for new inputs? */
    static boolean isEmpty(final AssemblyPortBlockEntity port, final boolean basin) {
        return basin ? port.basinEmpty() : port.depotEmpty();
    }

    /**
     * Puts the job's inputs at the station: the piece on the depot, or the whole kit in the basin (all or
     * nothing). On success {@code job.placed} holds what was put there and {@code job.holding} is empty.
     */
    static boolean place(final AssemblyPortBlockEntity port, final Job job) {
        return job.usesBasin() ? placeInBasin(port, job) : placeOnDepot(port, job);
    }

    private static boolean placeOnDepot(final AssemblyPortBlockEntity port, final Job job) {
        final IItemHandler depot = port.depot();
        final ItemStack piece = job.piece();
        if (depot == null || piece.isEmpty() || !depot.insertItem(0, piece.copy(), false).isEmpty()) {
            return false;
        }
        job.placed.clear();
        job.placed.addItem(piece);
        job.holding.clear();
        return true;
    }

    private static boolean placeInBasin(final AssemblyPortBlockEntity port, final Job job) {
        final BasinBlockEntity basin = port.basin();
        if (basin == null) {
            return false;
        }
        final IItemHandler input = basin.getInputInventory();
        final IFluidHandler tank = basin.getTanks().getFirst().getCapability();
        // 1) Simulate everything.
        for (final ResourceAmount r : job.holding.asList()) {
            if (r.resource() instanceof ItemResource item) {
                if (!insert(input, item.toItemStack((int) r.amount()), true).isEmpty()) {
                    return false;
                }
            } else if (r.resource() instanceof FluidResource fluid
                && tank.fill(Stock.toFluidStack(fluid, (int) r.amount()), IFluidHandler.FluidAction.SIMULATE) < r.amount()) {
                return false;
            }
        }
        // 2) Insert. If something doesn't fit after all, take everything back out.
        final Stock kit = new Stock();
        kit.addAll(job.holding.asList());
        boolean ok = true;
        for (final ResourceAmount r : kit.asList()) {
            if (r.resource() instanceof ItemResource item) {
                final ItemStack rest = insert(input, item.toItemStack((int) r.amount()), false);
                job.holding.remove(item, r.amount() - rest.getCount());
                ok &= rest.isEmpty();
            } else if (r.resource() instanceof FluidResource fluid) {
                final int filled = tank.fill(Stock.toFluidStack(fluid, (int) r.amount()), IFluidHandler.FluidAction.EXECUTE);
                job.holding.remove(fluid, filled);
                ok &= filled == r.amount();
            }
        }
        if (!ok) {
            takeBasinInputs(basin, job.holding);
            return false;
        }
        job.placed.clear();
        job.placed.addAll(kit.asList());
        return true;
    }

    /**
     * Checks the station. {@code ours} tells whether an item a deployer picked up belongs to the job.
     */
    static Poll poll(final AssemblyPortBlockEntity port, final Job job, final Predicate<ItemStack> ours) {
        if (job.usesBasin()) {
            final BasinBlockEntity basin = port.basin();
            if (basin == null) {
                return Poll.of(Status.EMPTY);
            }
            if (!basinInputsEmpty(basin)) {
                return Poll.of(Status.WAITING);
            }
            // Create consumed the ingredients: the recipe was applied.
            final Stock fluids = new Stock();
            return new Poll(Status.DONE, takeBasinOutputs(basin, fluids), fluids);
        }
        final IItemHandler depot = port.depot();
        if (depot == null) {
            return Poll.of(Status.EMPTY);
        }
        final List<ItemStack> present = contents(depot);
        if (present.isEmpty()) {
            return recoverFromDeployer(port, job, ours);
        }
        if (present.size() == 1 && ItemStack.isSameItemSameComponents(present.getFirst(), job.placedPiece())) {
            return Poll.of(Status.WAITING);
        }
        // Create finished the step: take everything off the depot.
        return new Poll(Status.DONE, takeAll(depot), new Stock());
    }

    /** An empty-handed deployer picks up what is below it: get it back. */
    private static Poll recoverFromDeployer(final AssemblyPortBlockEntity port, final Job job,
                                            final Predicate<ItemStack> ours) {
        final IItemHandler hand = port.deployer();
        if (hand == null) {
            return Poll.of(Status.EMPTY);
        }
        final ItemStack held = hand.getStackInSlot(0);
        if (held.isEmpty() || port.isLoadedItem(held)
            || !(ItemStack.isSameItemSameComponents(held, job.placedPiece()) || ours.test(held))) {
            return Poll.of(Status.EMPTY);
        }
        final ItemStack back = hand.extractItem(0, held.getCount(), false);
        if (back.isEmpty()) {
            return Poll.of(Status.EMPTY);
        }
        return new Poll(ItemStack.isSameItemSameComponents(back, job.placedPiece()) ? Status.UNCHANGED : Status.DONE,
            List.of(back), new Stock());
    }

    /**
     * Takes the job's unprocessed inputs back from the station (after a timeout, an abort or a broken port).
     * A depot gives back only the placed piece; a basin gives back its inputs and any outputs.
     */
    static Stock takeBack(final AssemblyPortBlockEntity port, final Job job) {
        final Stock back = new Stock();
        if (job.usesBasin()) {
            final BasinBlockEntity basin = port.basin();
            if (basin != null) {
                takeBasinInputs(basin, back);
                final Stock fluids = new Stock();
                for (final ItemStack stack : takeBasinOutputs(basin, fluids)) {
                    back.addItem(stack);
                }
                back.addAll(fluids.asList());
            }
            return back;
        }
        final IItemHandler depot = port.depot();
        if (depot != null) {
            for (int slot = 0; slot < depot.getSlots(); slot++) {
                final ItemStack stack = depot.getStackInSlot(slot);
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, job.placedPiece())) {
                    back.addItem(depot.extractItem(slot, stack.getCount(), false));
                }
            }
        }
        return back;
    }

    // ------------------------------------------------------------------ helpers

    private static List<ItemStack> contents(final IItemHandler handler) {
        final List<ItemStack> list = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    private static List<ItemStack> takeAll(final IItemHandler handler) {
        final List<ItemStack> taken = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                final ItemStack out = handler.extractItem(slot, stack.getCount(), false);
                if (!out.isEmpty()) {
                    taken.add(out);
                }
            }
        }
        return taken;
    }

    private static ItemStack insert(final IItemHandler handler, final ItemStack stack, final boolean simulate) {
        ItemStack rest = stack;
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
            rest = handler.insertItem(slot, rest, simulate);
        }
        return rest;
    }

    private static boolean basinInputsEmpty(final BasinBlockEntity basin) {
        final IItemHandler input = basin.getInputInventory();
        for (int slot = 0; slot < input.getSlots(); slot++) {
            if (!input.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return basin.getTanks().getFirst().isEmpty();
    }

    /** Takes everything in the basin's input (items and fluid) into {@code into}. */
    private static void takeBasinInputs(final BasinBlockEntity basin, final Stock into) {
        final IItemHandler input = basin.getInputInventory();
        for (int slot = 0; slot < input.getSlots(); slot++) {
            final ItemStack stack = input.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                into.addItem(input.extractItem(slot, stack.getCount(), false));
            }
        }
        drainAll(basin.getTanks().getFirst().getCapability(), into);
    }

    /** Takes the basin's results: items are returned, fluids go into {@code fluids}. */
    private static List<ItemStack> takeBasinOutputs(final BasinBlockEntity basin, final Stock fluids) {
        final List<ItemStack> items = takeAll(basin.getOutputInventory());
        drainAll(basin.getTanks().getSecond().getCapability(), fluids);
        return items;
    }

    private static void drainAll(@Nullable final IFluidHandler tank, final Stock into) {
        if (tank == null) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            final FluidStack drained = tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                return;
            }
            into.addFluid(drained);
        }
    }
}
