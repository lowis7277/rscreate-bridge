package dev.rscreatebridge.crafter;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;

import dev.rscreatebridge.Config;
import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.Registration;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;
import dev.rscreatebridge.common.Stock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Crafter Interface: the Autocrafter points here. It receives the multiset from RS and hands out
 * 1 item per crafter at the position matching the mechanical_crafting recipe.
 * <p>
 * It exposes no capability towards the Autocrafter (so RS's generic item sink can't insert on its own:
 * no double insertion). It only exposes an insert-only handler on faces touching a Mechanical Crafter,
 * to receive the result and pass it straight to the network.
 */
public class CrafterInterfaceBlockEntity extends BridgeNodeBlockEntity {
    private static final String TAG_PENDING_START = "PendingStart";
    private static final int START_RETRY_TICKS = 10;
    /** Time with the array idle and empty, with no result, before the craft is considered lost. */
    private static final int IDLE_GRACE_TICKS = 100;

    private final IItemHandler outputHandler = new OutputHandler();

    /** A recipe with gaps was inserted and still needs a "kick" (like a redstone signal). */
    private boolean pendingStart;
    private int pendingTicks;
    private int ticks;

    // --- Craft in progress (persisted): to detect that it was lost and retry.
    /** Exact ingredients of the craft in progress; null = no craft in progress. */
    @Nullable
    private Stock activeKit;
    private ItemStack activeOutput = ItemStack.EMPTY;
    private int activeReceived;
    /** Crafter positions when the craft started: if one is missing, the craft was lost. */
    private final Set<BlockPos> activeCrafters = new HashSet<>();
    /** The head points at this interface: the result can be verified when it arrives. */
    private boolean activeVerifiable;
    private int activeAttempts;
    private int activeIdleTicks;
    /** The craft was lost and waits for a complete array and materials to retry. */
    private boolean retryPending;

    public CrafterInterfaceBlockEntity(final BlockPos pos, final BlockState state) {
        super(Registration.CRAFTER_INTERFACE_BE.get(), pos, state, Config.CRAFTER_INTERFACE_ENERGY.getAsInt());
    }

    // ------------------------------------------------------------------ RS sink

    public ExternalPatternSink.Result accept(final Collection<ResourceAmount> resources, final Action action) {
        if (level == null || level.isClientSide()) {
            return ExternalPatternSink.Result.SKIPPED;
        }
        if (!isNetworkActive()) {
            setStatus(true, "rscreatebridge.status.no_network");
            return ExternalPatternSink.Result.REJECTED;
        }
        final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
        if (discovery instanceof CrafterGrid.Problem problem) {
            setStatus(true, problem.translationKey());
            return ExternalPatternSink.Result.REJECTED;
        }
        final CrafterGrid grid = ((CrafterGrid.Found) discovery).grid();
        if (!grid.isRotating()) {
            setStatus(true, "rscreatebridge.crafter.problem.not_rotating");
            return ExternalPatternSink.Result.REJECTED;
        }
        if (retryPending) {
            // There is still a lost craft to redo: that one goes first.
            return ExternalPatternSink.Result.REJECTED;
        }
        if (pendingStart || activeKit != null || !grid.isIdleAndEmpty()) {
            // Busy with a previous recipe: RS retries later on its own. Not a problem.
            setStatus(false, "rscreatebridge.crafter.status.busy");
            return ExternalPatternSink.Result.REJECTED;
        }
        final MechanicalPlanner.Outcome outcome = MechanicalPlanner.plan(level, grid, resources);
        if (outcome instanceof MechanicalPlanner.Rejected rejected) {
            setStatus(true, rejected.translationKey());
            return ExternalPatternSink.Result.REJECTED;
        }
        final MechanicalPlanner.Plan plan = ((MechanicalPlanner.Planned) outcome).plan();
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED;
        }
        final ExternalPatternSink.Result result = execute(grid, plan);
        if (result == ExternalPatternSink.Result.ACCEPTED) {
            activeAttempts = 0;
        }
        return result;
    }

    private ExternalPatternSink.Result execute(final CrafterGrid grid, final MechanicalPlanner.Plan plan) {
        final List<MechanicalCrafterBlockEntity> filled = new ArrayList<>();
        for (final Map.Entry<MechanicalCrafterBlockEntity, ItemStack> entry : plan.placements().entrySet()) {
            final ItemStack rest = entry.getKey().getInventory().insertItem(0, entry.getValue().copyWithCount(1), false);
            if (!rest.isEmpty()) {
                // Should never happen (it was simulated in the same tick). Undo without losing anything.
                RSCreateBridge.LOGGER.warn("A crafter at {} refused {} during execution; undoing the insertion.",
                    entry.getKey().getBlockPos().toShortString(), entry.getValue());
                rollback(filled);
                returnOrDrop(rest);
                plan.placements().entrySet().stream()
                    .dropWhile(e -> e.getKey() != entry.getKey()).skip(1)
                    .forEach(e -> returnOrDrop(e.getValue().copyWithCount(1)));
                setStatus(true, "rscreatebridge.crafter.problem.insert_failed");
                return ExternalPatternSink.Result.REJECTED;
            }
            filled.add(entry.getKey());
        }
        RSCreateBridge.LOGGER.debug("Mechanical craft started at {}: {}", worldPosition.toShortString(),
            MechanicalPlanner.describe(plan));
        startTracking(grid, plan);
        // If the recipe leaves empty uncovered crafters, Create won't start on its own: kick it like redstone.
        if (grid.anyItemPresent()) {
            pendingStart = true;
            pendingTicks = 0;
            grid.head().checkCompletedRecipe(true);
        }
        setStatus(false, "rscreatebridge.crafter.status.crafting", plan.output().getHoverName());
        setChanged();
        return ExternalPatternSink.Result.ACCEPTED;
    }

    private void rollback(final List<MechanicalCrafterBlockEntity> filled) {
        for (final MechanicalCrafterBlockEntity crafter : filled) {
            final ItemStack inside = crafter.getInventory().getStackInSlot(0).copy();
            crafter.getInventory().setStackInSlot(0, ItemStack.EMPTY);
            returnOrDrop(inside);
        }
    }

    /** For RS's "block until empty" mode. */
    public boolean isEmptyForRs() {
        if (pendingStart || activeKit != null || retryPending) {
            return false;
        }
        if (level == null) {
            return true;
        }
        final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
        return !(discovery instanceof CrafterGrid.Found found) || !found.grid().anyItemPresent();
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected boolean hasWork() {
        return pendingStart || activeKit != null || retryPending;
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (level == null || ++ticks % START_RETRY_TICKS != 0) {
            return;
        }
        if (retryPending) {
            tryRetry();
        } else if (activeKit != null) {
            watchActiveCraft();
        }
        if (!pendingStart) {
            return;
        }
        pendingTicks += START_RETRY_TICKS;
        final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
        if (!(discovery instanceof CrafterGrid.Found found)) {
            // The array broke: Create already dropped the items when the crafter was broken. Nothing to start.
            pendingStart = false;
            setStatus(true, ((CrafterGrid.Problem) discovery).translationKey());
            setChanged();
            return;
        }
        final CrafterGrid grid = found.grid();
        if (!grid.anyItemPresent()) {
            pendingStart = false;
            setStatus(false, "rscreatebridge.status.idle");
            setChanged();
            return;
        }
        if (grid.isRotating()) {
            grid.head().checkCompletedRecipe(true);
        } else if (pendingTicks >= Config.STEP_TIMEOUT_SECONDS.getAsInt() * 20) {
            setStatus(true, "rscreatebridge.crafter.problem.not_rotating");
        }
    }


    // ------------------------------------------------------------------ craft tracking

    private void startTracking(final CrafterGrid grid, final MechanicalPlanner.Plan plan) {
        final Stock kit = new Stock();
        plan.placements().values().forEach(stack -> kit.addItem(stack.copyWithCount(1)));
        activeKit = kit;
        activeOutput = plan.output().copy();
        activeReceived = 0;
        activeIdleTicks = 0;
        activeCrafters.clear();
        grid.crafters().values().forEach(c -> activeCrafters.add(c.getBlockPos().immutable()));
        final MechanicalCrafterBlockEntity head = grid.head();
        activeVerifiable = head.getBlockPos().relative(MechanicalCrafterBlock.getTargetDirection(head.getBlockState()))
            .equals(worldPosition);
        setChanged();
    }

    private void finishTracking() {
        activeKit = null;
        activeOutput = ItemStack.EMPTY;
        activeReceived = 0;
        activeCrafters.clear();
        activeIdleTicks = 0;
        setChanged();
    }

    /** (Part of) the result arrived through the output handler. */
    private void onOutputInserted(final ItemStack inserted) {
        if (activeKit == null || retryPending || !ItemStack.isSameItemSameComponents(inserted, activeOutput)) {
            return;
        }
        activeReceived += inserted.getCount();
        if (activeReceived >= activeOutput.getCount()) {
            finishTracking();
            setStatus(false, "rscreatebridge.status.idle");
        }
    }

    private void watchActiveCraft() {
        // Was a crafter removed? Create already dropped the array's items: the craft was lost.
        for (final BlockPos pos : activeCrafters) {
            if (level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof MechanicalCrafterBlockEntity)) {
                craftLost(pos);
                return;
            }
        }
        final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
        if (!(discovery instanceof CrafterGrid.Found found)) {
            craftLost(worldPosition);
            return;
        }
        final CrafterGrid grid = found.grid();
        if (pendingStart || !grid.isIdleAndEmpty()) {
            activeIdleTicks = 0;
            return;
        }
        // The array is idle and empty.
        activeIdleTicks += START_RETRY_TICKS;
        if (activeIdleTicks < IDLE_GRACE_TICKS) {
            return;
        }
        if (activeVerifiable) {
            // The result should have arrived and didn't (for example Create ejected the grid).
            craftLost(grid.head().getBlockPos());
        } else {
            // The head points elsewhere (chest, belt): it can't be verified; treat it as finished.
            finishTracking();
            setStatus(false, "rscreatebridge.status.idle");
        }
    }

    private void craftLost(final BlockPos where) {
        activeAttempts++;
        pendingStart = false;
        final int max = Config.MAX_RETRIES.getAsInt();
        final Component name = activeOutput.getHoverName();
        if (activeAttempts > max || activeKit == null) {
            setStatusSilently(true, "rscreatebridge.crafter.problem.gave_up", name, activeAttempts);
            announceEvent(Component.translatable("rscreatebridge.crafter.problem.gave_up", name, activeAttempts));
            finishTracking();
            retryPending = false;
            return;
        }
        // A retry doesn't go to chat (it shows with Goggles); only the final give-up does.
        setStatusSilently(true, "rscreatebridge.crafter.problem.lost", name, where.toShortString(), activeAttempts, max);
        logEvent(Component.translatable("rscreatebridge.crafter.problem.lost", name, where.toShortString(),
            activeAttempts, max));
        retryPending = true;
        activeIdleTicks = 0;
        setChanged();
    }

    /** Redoes the lost craft once the array is complete and the network has the ingredients. */
    private void tryRetry() {
        if (activeKit == null) {
            retryPending = false;
            return;
        }
        if (!isNetworkActive()) {
            return;
        }
        final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
        if (discovery instanceof CrafterGrid.Problem problem) {
            setStatus(true, problem.translationKey());
            return;
        }
        final CrafterGrid grid = ((CrafterGrid.Found) discovery).grid();
        if (!grid.isRotating() || !grid.isIdleAndEmpty()) {
            return;
        }
        final List<ResourceAmount> kit = activeKit.asList();
        for (final ResourceAmount r : kit) {
            if (extractFromNetwork(r.resource(), r.amount(), Action.SIMULATE) < r.amount()) {
                setStatus(true, "rscreatebridge.crafter.problem.missing_materials", activeOutput.getHoverName());
                return;
            }
        }
        final MechanicalPlanner.Outcome outcome = MechanicalPlanner.plan(level, grid, kit);
        if (!(outcome instanceof MechanicalPlanner.Planned planned)) {
            setStatus(true, ((MechanicalPlanner.Rejected) outcome).translationKey());
            return;
        }
        for (final ResourceAmount r : kit) {
            final long got = extractFromNetwork(r.resource(), r.amount(), Action.EXECUTE);
            if (got < r.amount()) {
                // The network changed between simulation and extraction: return what was taken and wait.
                insertIntoNetwork(r.resource(), got, Action.EXECUTE);
                return;
            }
        }
        retryPending = false;
        if (execute(grid, planned.plan()) == ExternalPatternSink.Result.ACCEPTED) {
            RSCreateBridge.LOGGER.info("[CrafterInterface @ {}] Retry {} of the {} craft",
                worldPosition.toShortString(), activeAttempts, activeOutput.getHoverName().getString());
        } else {
            // execute() already returned what was inserted to the network: try again later.
            retryPending = true;
        }
    }

    // ------------------------------------------------------------------ output capability

    /** Item capability: only towards faces touching a Mechanical Crafter. */
    @Nullable
    public IItemHandler getOutputHandler(@Nullable final Direction side) {
        if (side == null || level == null) {
            return null;
        }
        return level.getBlockEntity(worldPosition.relative(side)) instanceof MechanicalCrafterBlockEntity
            ? outputHandler
            : null;
    }

    private final class OutputHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            // Straight to the network. If it doesn't fit, hand it back: Create's crafter retries on its own.
            final ItemStack rest = insertStackIntoNetwork(stack, simulate);
            if (!simulate && rest.getCount() < stack.getCount()) {
                onOutputInserted(stack.copyWithCount(stack.getCount() - rest.getCount()));
            }
            return rest;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(final int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return true;
        }
    }

    // ------------------------------------------------------------------ info

    public List<Component> describe() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("block.rscreatebridge.crafter_interface").withStyle(ChatFormatting.GOLD));
        lines.add(describeNetwork());
        if (level != null) {
            final CrafterGrid.Discovery discovery = CrafterGrid.discover(level, worldPosition);
            if (discovery instanceof CrafterGrid.Found found) {
                final CrafterGrid grid = found.grid();
                lines.add(Component.translatable("rscreatebridge.crafter.info.grid", grid.size(), grid.width(),
                    grid.height(), grid.head().getBlockPos().toShortString()).withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable(((CrafterGrid.Problem) discovery).translationKey())
                    .withStyle(ChatFormatting.RED));
            }
        }
        if (activeKit != null) {
            lines.add(Component.translatable(retryPending ? "rscreatebridge.crafter.info.retrying"
                    : "rscreatebridge.crafter.info.active",
                activeOutput.getHoverName(), activeReceived, activeOutput.getCount()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(getStatus().copy().withStyle(isStatusProblem() ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        return lines;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_PENDING_START, pendingStart);
        if (activeKit != null) {
            final CompoundTag active = new CompoundTag();
            active.put("Kit", activeKit.save(registries));
            active.put("Output", activeOutput.save(registries));
            active.putInt("Received", activeReceived);
            active.putInt("Attempts", activeAttempts);
            active.putBoolean("Verifiable", activeVerifiable);
            active.putBoolean("Retry", retryPending);
            final ListTag positions = new ListTag();
            activeCrafters.forEach(pos -> positions.add(LongTag.valueOf(pos.asLong())));
            active.put("Crafters", positions);
            tag.put("Active", active);
        }
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pendingStart = tag.getBoolean(TAG_PENDING_START);
        activeKit = null;
        activeCrafters.clear();
        retryPending = false;
        if (tag.contains("Active")) {
            final CompoundTag active = tag.getCompound("Active");
            activeKit = new Stock();
            activeKit.load(active.getList("Kit", Tag.TAG_COMPOUND), registries);
            activeOutput = ItemStack.parseOptional(registries, active.getCompound("Output"));
            activeReceived = active.getInt("Received");
            activeAttempts = active.getInt("Attempts");
            activeVerifiable = active.getBoolean("Verifiable");
            retryPending = active.getBoolean("Retry");
            for (final Tag raw : active.getList("Crafters", Tag.TAG_LONG)) {
                activeCrafters.add(BlockPos.of(((LongTag) raw).getAsLong()));
            }
        }
        activeIdleTicks = 0;
    }
}
