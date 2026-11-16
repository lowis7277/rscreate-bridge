package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import dev.rscreatebridge.Config;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * The Controller's basin jobs: mixer recipes ({@code create:mixing}) and basin press recipes
 * ({@code create:compacting}). Each job is one kit of the recipe's ingredients: the controller puts it in the
 * basin of a free port (with the heat the recipe needs), waits for Create to process it and moves the
 * results from the basin to the network.
 */
final class BasinJobs {
    private static final int PORT_COOLDOWN_TICKS = 200;
    private static final int MATERIAL_RETRY_TICKS = 100;

    enum State { WAITING_PORT, AT_PORT, WAITING_MATERIALS }

    static final class Job {
        final ResourceLocation recipe;
        /** What has to go into the basin (one kit). Kept for retries. */
        final Stock kit;
        /** What the controller still holds of that kit (empty while it is in the basin). */
        final Stock holding = new Stock();
        State state = State.WAITING_PORT;
        @Nullable
        BlockPos port;
        long sentAt;
        long nextTryAt;
        int attempts;

        Job(final ResourceLocation recipe, final Stock kit) {
            this.recipe = recipe;
            this.kit = kit;
        }
    }

    private final AssemblyControllerBlockEntity controller;
    final List<Job> jobs = new ArrayList<>();

    BasinJobs(final AssemblyControllerBlockEntity controller) {
        this.controller = controller;
    }

    void add(final BasinRecipeInfo info, final List<Stock> kits) {
        for (final Stock kit : kits) {
            final Job job = new Job(info.id(), kit);
            job.holding.addAll(kit.asList());
            jobs.add(job);
        }
    }

    int atPorts() {
        int n = 0;
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT) {
                n++;
            }
        }
        return n;
    }

    boolean busy(final AssemblyPortBlockEntity port) {
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT && port.getBlockPos().equals(job.port)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    void tick(final long now) {
        watch(now);
        retryMaterials(now);
        dispatch(now);
    }

    private void dispatch(final long now) {
        List<AssemblyPortBlockEntity> ports = null;
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != State.WAITING_PORT || job.nextTryAt > now) {
                continue;
            }
            final BasinRecipeInfo info = BasinRecipeInfo.byId(controller.getLevel(), job.recipe);
            if (info == null) {
                // The recipe disappeared (datapack reload): whatever it holds goes back to the network.
                controller.giveBack(job.holding);
                jobs.remove(job);
                continue;
            }
            if (ports == null) {
                ports = controller.workPorts();
            }
            AssemblyPortBlockEntity chosen = null;
            boolean heatMissing = false;
            for (final AssemblyPortBlockEntity port : ports) {
                if (port.machineType() != info.type() || !port.isFreeFor(controller.getBlockPos(), now)
                    || controller.isPortBusy(port) || port.stationProblem() != null || !port.basinEmpty()) {
                    continue;
                }
                if (!info.heatOk(port.heat())) {
                    heatMissing = true;
                    continue;
                }
                chosen = port;
                break;
            }
            if (chosen == null) {
                if (heatMissing) {
                    controller.basinStatus(true, "rscreatebridge.assembly.problem.no_heat", info.name(), info.heatName());
                }
                continue;
            }
            if (!fill(chosen, job)) {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS / 5;
                continue;
            }
            chosen.claim(controller.getBlockPos(), now);
            job.state = State.AT_PORT;
            job.port = chosen.getBlockPos();
            job.sentAt = now;
            controller.setChanged();
        }
    }

    /** Puts the kit into the basin: all or nothing. */
    private boolean fill(final AssemblyPortBlockEntity port, final Job job) {
        final BasinBlockEntity basin = port.basin();
        if (basin == null) {
            return false;
        }
        final IItemHandler input = basin.getInputInventory();
        final IFluidHandler tank = basin.getTanks().getFirst().getCapability();
        // 1) Simulate.
        for (final ResourceAmount r : job.holding.asList()) {
            if (r.resource() instanceof ItemResource item) {
                if (!insert(input, item.toItemStack((int) r.amount()), true).isEmpty()) {
                    port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                    return false;
                }
            } else if (r.resource() instanceof FluidResource fluid) {
                if (tank.fill(Stock.toFluidStack(fluid, (int) r.amount()), IFluidHandler.FluidAction.SIMULATE) < r.amount()) {
                    port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                    return false;
                }
            }
        }
        // 2) Insert. If something doesn't fit, take everything back out.
        boolean ok = true;
        for (final ResourceAmount r : job.holding.asList()) {
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
            takeInputsBack(basin, job.holding);
            port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
            return false;
        }
        return true;
    }

    private static ItemStack insert(final IItemHandler handler, final ItemStack stack, final boolean simulate) {
        ItemStack rest = stack;
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
            rest = handler.insertItem(slot, rest, simulate);
        }
        return rest;
    }

    /** Takes everything in the basin's input (items and fluid) and adds it to {@code into}. */
    private static void takeInputsBack(final BasinBlockEntity basin, final Stock into) {
        final IItemHandler input = basin.getInputInventory();
        for (int slot = 0; slot < input.getSlots(); slot++) {
            final ItemStack stack = input.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                into.addItem(input.extractItem(slot, stack.getCount(), false));
            }
        }
        final IFluidHandler tank = basin.getTanks().getFirst().getCapability();
        for (int i = 0; i < 4; i++) {
            final FluidStack drained = tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                break;
            }
            into.addFluid(drained);
        }
    }

    private static boolean inputsEmpty(final BasinBlockEntity basin) {
        final IItemHandler input = basin.getInputInventory();
        for (int slot = 0; slot < input.getSlots(); slot++) {
            if (!input.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return basin.getTanks().getFirst().isEmpty();
    }

    /** Moves the basin's results (items and fluids) to the network. */
    private void collectOutputs(final BasinBlockEntity basin) {
        final Stock out = new Stock();
        final IItemHandler output = basin.getOutputInventory();
        for (int slot = 0; slot < output.getSlots(); slot++) {
            final ItemStack stack = output.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                out.addItem(output.extractItem(slot, stack.getCount(), false));
            }
        }
        final IFluidHandler tank = basin.getTanks().getSecond().getCapability();
        for (int i = 0; i < 4; i++) {
            final FluidStack drained = tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                break;
            }
            out.addFluid(drained);
        }
        controller.giveBack(out);
    }

    private void watch(final long now) {
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != State.AT_PORT) {
                continue;
            }
            final AssemblyPortBlockEntity port = controller.portAt(job.port);
            final BasinBlockEntity basin = port == null ? null : port.basin();
            if (basin == null) {
                // The port or the basin is gone (Create drops what it held): redo it with materials from the network.
                job.port = null;
                fail(job, now, Component.translatable("rscreatebridge.assembly.problem.lost_basin"));
                continue;
            }
            port.claim(controller.getBlockPos(), now);
            if (inputsEmpty(basin)) {
                // Create consumed the ingredients: done.
                collectOutputs(basin);
                port.release(controller.getBlockPos());
                port.clearPortProblem();
                jobs.remove(job);
                controller.basinProgress();
                controller.setChanged();
                continue;
            }
            if (now - job.sentAt > Config.PIECE_TIMEOUT_SECONDS.getAsInt() * 20L) {
                // Not processed: take it back and try another station.
                takeInputsBack(basin, job.holding);
                collectOutputs(basin);
                port.release(controller.getBlockPos());
                port.coolDown(now + PORT_COOLDOWN_TICKS,
                    Component.translatable("rscreatebridge.port.problem.timeout", Config.PIECE_TIMEOUT_SECONDS.getAsInt()));
                controller.basinStatus(true, "rscreatebridge.assembly.problem.stuck", port.getBlockPos().toShortString());
                job.port = null;
                job.state = State.WAITING_PORT;
                controller.setChanged();
            }
        }
    }

    private void fail(final Job job, final long now, final Component reason) {
        job.attempts++;
        final int max = Config.MAX_RETRIES.getAsInt();
        final BasinRecipeInfo info = BasinRecipeInfo.byId(controller.getLevel(), job.recipe);
        final Component name = info != null ? info.name() : Component.literal(job.recipe.toString());
        if (job.attempts > max) {
            jobs.remove(job);
            controller.basinGaveUp(name, job.attempts);
            return;
        }
        controller.basinRetry(reason, job.attempts, max);
        job.holding.clear();
        job.state = State.WAITING_MATERIALS;
        job.nextTryAt = now;
        controller.setChanged();
    }

    /** After losing a kit: take it from the network again, all or nothing. */
    private void retryMaterials(final long now) {
        for (final Job job : jobs) {
            if (job.state != State.WAITING_MATERIALS || job.nextTryAt > now) {
                continue;
            }
            if (controller.takeKitFromNetwork(job.kit)) {
                job.holding.clear();
                job.holding.addAll(job.kit.asList());
                job.state = State.WAITING_PORT;
                controller.setChanged();
            } else {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS;
                controller.basinStatus(true, "rscreatebridge.assembly.problem.missing_kit");
            }
        }
    }

    // ------------------------------------------------------------------ abort

    /** Returns everything: what the controller holds and what is in the basins of its jobs. */
    void abortAll(final Stock into) {
        for (final Job job : jobs) {
            into.addAll(job.holding.asList());
            if (job.state == State.AT_PORT) {
                final AssemblyPortBlockEntity port = controller.portAt(job.port);
                final BasinBlockEntity basin = port == null ? null : port.basin();
                if (basin != null) {
                    takeInputsBack(basin, into);
                    final Stock out = new Stock();
                    final IItemHandler output = basin.getOutputInventory();
                    for (int slot = 0; slot < output.getSlots(); slot++) {
                        final ItemStack stack = output.getStackInSlot(slot);
                        if (!stack.isEmpty()) {
                            out.addItem(output.extractItem(slot, stack.getCount(), false));
                        }
                    }
                    into.addAll(out.asList());
                }
                if (port != null) {
                    port.release(controller.getBlockPos());
                }
            }
        }
        jobs.clear();
    }

    // ------------------------------------------------------------------ NBT

    ListTag save(final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final Job job : jobs) {
            final CompoundTag tag = new CompoundTag();
            tag.putString("Recipe", job.recipe.toString());
            tag.put("Kit", job.kit.save(registries));
            tag.put("Holding", job.holding.save(registries));
            tag.putString("State", job.state.name());
            if (job.port != null) {
                tag.put("Port", NbtUtils.writeBlockPos(job.port));
            }
            tag.putLong("SentAt", job.sentAt);
            tag.putLong("NextTryAt", job.nextTryAt);
            tag.putInt("Attempts", job.attempts);
            list.add(tag);
        }
        return list;
    }

    void load(final ListTag list, final HolderLookup.Provider registries) {
        jobs.clear();
        for (final Tag raw : list) {
            final CompoundTag tag = (CompoundTag) raw;
            final ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("Recipe"));
            if (recipe == null) {
                continue;
            }
            final Stock kit = new Stock();
            kit.load(tag.getList("Kit", Tag.TAG_COMPOUND), registries);
            final Job job = new Job(recipe, kit);
            job.holding.load(tag.getList("Holding", Tag.TAG_COMPOUND), registries);
            try {
                job.state = State.valueOf(tag.getString("State"));
            } catch (final IllegalArgumentException e) {
                job.state = State.WAITING_MATERIALS;
            }
            job.port = NbtUtils.readBlockPos(tag, "Port").orElse(null);
            if (job.state == State.AT_PORT && job.port == null) {
                job.state = State.WAITING_MATERIALS;
            }
            job.sentAt = tag.getLong("SentAt");
            job.nextTryAt = tag.getLong("NextTryAt");
            job.attempts = tag.getInt("Attempts");
            jobs.add(job);
        }
    }
}
