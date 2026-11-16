package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllDataComponents;

import dev.rscreatebridge.Config;
import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.Registration;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Assembly Controller: receives from the Autocrafter everything the pattern asks for (base item,
 * cogwheels, nuggets, lava...) and builds the piece step by step using the Assembly Ports on its
 * own RS network.
 * <p>
 * For each step it looks for a free port whose machine has the right type (deployer, press or spout),
 * loads the machine (the deployer's held item, the spout's lava), places the piece on the depot and
 * takes it back once Create is done. A recipe that only uses the deployer only needs a deployer port.
 * With several ports of the same type, several pieces are built in parallel.
 * <ul>
 *   <li>product: to the RS network (the request receives it, like with an Importer);</li>
 *   <li>scrap: to the network, and it retries with a new base item from the network (up to {@code maxRetries});</li>
 *   <li>when everything is done, leftover materials go back to the network.</li>
 * </ul>
 * The controller and the ports expose no inventory: no double insertion with RS's generic sink.
 */
public class AssemblyControllerBlockEntity extends BridgeNodeBlockEntity {
    private static final int TICK_INTERVAL = 2;
    private static final int MATERIAL_RETRY_TICKS = 100;
    /** If the piece disappears from the depot (someone took it), wait this long before treating it as lost. */
    private static final int LOST_GRACE_TICKS = 40;
    private static final int PORT_COOLDOWN_TICKS = 200;

    enum State {
        /** The piece is in the controller, waiting for a free port of the right type. */
        WAITING_PORT,
        /** The piece is on a port's depot. */
        AT_PORT,
        /** After scrap or a lost piece: a new base item from the network is needed. */
        WAITING_BASE
    }

    /** One piece to build (one product). */
    static final class Job {
        final ItemStack base;
        final ResourceLocation recipe;
        /** The piece (base or intermediate) while the controller holds it; on a depot, what was placed there. */
        ItemStack piece;
        State state = State.WAITING_PORT;
        @Nullable
        BlockPos port;
        long sentAt;
        long missingSince = -1;
        long nextTryAt;
        int attempts;

        Job(final ItemStack base, final ResourceLocation recipe) {
            this.base = base.copyWithCount(1);
            this.recipe = recipe;
            this.piece = this.base.copy();
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    /** Materials the Autocrafter delivered for the steps (reserved by RS for the request). */
    private final Stock materials = new Stock();
    /** To be returned to the network (product, scrap, leftovers). */
    private final Stock returns = new Stock();
    /** Mixer and basin press recipes. */
    private final BasinJobs basinJobs = new BasinJobs(this);
    private int ticks;

    public AssemblyControllerBlockEntity(final BlockPos pos, final BlockState state) {
        super(Registration.ASSEMBLY_CONTROLLER_BE.get(), pos, state, Config.ASSEMBLY_CONTROLLER_ENERGY.getAsInt());
    }

    private long now() {
        return level == null ? 0 : level.getGameTime();
    }

    private List<AssemblyPortBlockEntity> ports() {
        return level == null ? List.of() : AssemblyPortBlockEntity.portsOn(level, getNetwork());
    }

    @Nullable
    AssemblyPortBlockEntity portAt(@Nullable final BlockPos pos) {
        if (pos == null || level == null || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof AssemblyPortBlockEntity port ? port : null;
    }

    // ================================================================== RS sink

    public ExternalPatternSink.Result accept(final Collection<ResourceAmount> resources, final Action action) {
        if (level == null || level.isClientSide()) {
            return ExternalPatternSink.Result.SKIPPED;
        }
        if (!isNetworkActive()) {
            rejectStatus("rscreatebridge.status.no_network");
            return ExternalPatternSink.Result.REJECTED;
        }
        // Candidates: sequenced and basin recipes matching what RS delivered. If there is more than one,
        // the first one whose machines are on the network wins.
        AssemblyRecipeInfo match = null;
        AssemblyRecipeInfo.Split split = null;
        BasinRecipeInfo basinMatch = null;
        List<Stock> basinKits = null;
        AssemblyRecipeInfo.StepType firstMissing = null;
        final Set<AssemblyRecipeInfo.StepType> available = availableMachines();
        for (final AssemblyRecipeInfo info : AssemblyRecipeInfo.all(level)) {
            if (!info.isSupported()) {
                continue;
            }
            final AssemblyRecipeInfo.Split candidate = info.split(resources);
            if (candidate == null) {
                continue;
            }
            final AssemblyRecipeInfo.StepType missing = missingMachine(info, available);
            if (missing == null) {
                match = info;
                split = candidate;
                break;
            }
            if (firstMissing == null) {
                firstMissing = missing;
            }
        }
        if (split == null) {
            // Single-step depot recipes (for example copper ingot -> copper sheet with the press).
            for (final AssemblyRecipeInfo info : AssemblyRecipeInfo.allSingleStep(level)) {
                final AssemblyRecipeInfo.Split candidate = info.split(resources);
                if (candidate == null) {
                    continue;
                }
                final AssemblyRecipeInfo.StepType missing = missingMachine(info, available);
                if (missing == null) {
                    match = info;
                    split = candidate;
                    break;
                }
                if (firstMissing == null) {
                    firstMissing = missing;
                }
            }
        }
        if (split == null) {
            for (final BasinRecipeInfo info : BasinRecipeInfo.all(level)) {
                final List<Stock> kits = info.split(resources);
                if (kits == null) {
                    continue;
                }
                if (available.contains(info.type())) {
                    basinMatch = info;
                    basinKits = kits;
                    break;
                }
                if (firstMissing == null) {
                    firstMissing = info.type();
                }
            }
        }
        if (split == null && basinKits == null) {
            if (firstMissing != null) {
                rejectStatus("rscreatebridge.assembly.problem.no_port", Component.translatable(firstMissing.machineKey()));
            } else {
                rejectStatus("rscreatebridge.assembly.problem.no_recipe");
            }
            return ExternalPatternSink.Result.REJECTED;
        }
        if (basinKits != null) {
            if (!heatAvailable(basinMatch)) {
                rejectStatus("rscreatebridge.assembly.problem.no_heat", basinMatch.name(), basinMatch.heatName());
                return ExternalPatternSink.Result.REJECTED;
            }
            if (jobs.size() + basinJobs.jobs.size() + basinKits.size() > Config.MAX_QUEUED_JOBS.getAsInt()) {
                return ExternalPatternSink.Result.REJECTED;
            }
            if (action == Action.SIMULATE) {
                return ExternalPatternSink.Result.ACCEPTED;
            }
            basinJobs.add(basinMatch, basinKits);
            RSCreateBridge.LOGGER.debug("Controller {}: {} kit(s) of {} queued", worldPosition.toShortString(),
                basinKits.size(), basinMatch.id());
            if (isStatusProblem()) {
                clearProblem();
            }
            setChanged();
            return ExternalPatternSink.Result.ACCEPTED;
        }
        if (jobs.size() + basinJobs.jobs.size() + split.runs() > Config.MAX_QUEUED_JOBS.getAsInt()) {
            return ExternalPatternSink.Result.REJECTED; // queue full: RS retries later
        }
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED;
        }
        for (final ResourceAmount extra : split.extras().asList()) {
            materials.add(extra.resource(), extra.amount());
        }
        for (final ResourceAmount base : split.bases().asList()) {
            final ItemStack unit = ((ItemResource) base.resource()).toItemStack(1);
            for (long i = 0; i < base.amount(); i++) {
                jobs.add(new Job(unit, match.id()));
            }
        }
        RSCreateBridge.LOGGER.debug("Controller {}: {} piece(s) of {} queued", worldPosition.toShortString(),
            split.runs(), match.describe());
        if (isStatusProblem()) {
            clearProblem();
        }
        setChanged();
        return ExternalPatternSink.Result.ACCEPTED;
    }

    /** Machine types that have at least one port on the network. */
    private Set<AssemblyRecipeInfo.StepType> availableMachines() {
        final Set<AssemblyRecipeInfo.StepType> available = EnumSet.noneOf(AssemblyRecipeInfo.StepType.class);
        for (final AssemblyPortBlockEntity port : ports()) {
            final AssemblyRecipeInfo.StepType type = port.machineType();
            if (type != null && port.hasStation()) {
                available.add(type);
            }
        }
        return available;
    }

    /** Does any port with the recipe's machine have the heat it needs? */
    private boolean heatAvailable(final BasinRecipeInfo info) {
        for (final AssemblyPortBlockEntity port : ports()) {
            if (port.machineType() == info.type() && info.heatOk(port.heat())) {
                return true;
            }
        }
        return false;
    }

    /** The first machine type the recipe needs that has no port on the network, or null. */
    @Nullable
    private static AssemblyRecipeInfo.StepType missingMachine(final AssemblyRecipeInfo info,
                                                              final Set<AssemblyRecipeInfo.StepType> available) {
        for (final AssemblyRecipeInfo.Step step : info.steps()) {
            if (!available.contains(step.type())) {
                return step.type();
            }
        }
        return null;
    }

    /** A rejection reason is only shown while the controller is idle. */
    private void rejectStatus(final String key, final Object... args) {
        if (jobs.isEmpty() && basinJobs.jobs.isEmpty()) {
            setStatus(true, key, args);
        }
    }

    public boolean isEmptyForRs() {
        return jobs.isEmpty() && basinJobs.jobs.isEmpty();
    }

    @Nullable
    private java.util.UUID abortRequestedBy;
    private long abortRequestedAt;

    /**
     * First shift + click: remembers the request and returns false. If the same player repeats it within
     * {@code window} ticks, returns true (abort).
     */
    public boolean confirmAbort(final java.util.UUID player, final long now, final long window) {
        if (player.equals(abortRequestedBy) && now - abortRequestedAt <= window) {
            abortRequestedBy = null;
            return true;
        }
        abortRequestedBy = player;
        abortRequestedAt = now;
        return false;
    }

    /** How many jobs are queued (pieces and basin kits). */
    public int pendingCount() {
        return jobs.size() + basinJobs.jobs.size();
    }

    /** How many pieces are currently on port depots (to see parallel work). */
    public int piecesAtPorts() {
        int count = 0;
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT) {
                count++;
            }
        }
        return count + basinJobs.atPorts();
    }

    @Override
    protected boolean hasWork() {
        return !isEmptyForRs();
    }

    // ------------------------------------------------------------------ for BasinJobs

    List<AssemblyPortBlockEntity> workPorts() {
        return ports();
    }

    /** Does the port hold a piece or kit from this controller? */
    boolean isPortBusy(final AssemblyPortBlockEntity port) {
        return busyHere(port) || basinJobs.busy(port);
    }

    void giveBack(final Stock stock) {
        returns.addAll(stock.asList());
    }

    /** Takes exactly that kit from the network (all or nothing). */
    boolean takeKitFromNetwork(final Stock kit) {
        for (final ResourceAmount r : kit.asList()) {
            if (extractFromNetwork(r.resource(), r.amount(), Action.SIMULATE) < r.amount()) {
                return false;
            }
        }
        for (final ResourceAmount r : kit.asList()) {
            extractFromNetwork(r.resource(), r.amount(), Action.EXECUTE);
        }
        return true;
    }

    void basinStatus(final boolean problem, final String key, final Object... args) {
        setStatus(problem, key, args);
    }

    void basinProgress() {
        clearStuck();
    }

    void basinRetry(final Component reason, final int attempt, final int max) {
        setStatusSilently(true, "rscreatebridge.assembly.problem.retry", reason, attempt, max);
        logEvent(Component.translatable("rscreatebridge.assembly.problem.retry", reason, attempt, max));
    }

    void basinGaveUp(final Component name, final int attempts) {
        setStatusSilently(true, "rscreatebridge.assembly.problem.gave_up", name, attempts);
        announceEvent(Component.translatable("rscreatebridge.assembly.problem.gave_up", name, attempts));
        setChanged();
    }

    // ================================================================== tick

    @Override
    public void serverTick() {
        super.serverTick();
        if (level == null || ++ticks % TICK_INTERVAL != 0) {
            return;
        }
        if (!isNetworkActive()) {
            if (hasWork()) {
                setStatus(true, "rscreatebridge.status.no_network");
            }
            return;
        }
        flushReturns();
        watchPorts();
        refillBases();
        dispatch();
        basinJobs.tick(now());
        if (isEmptyForRs()) {
            releaseMaterials();
        }
        updateWorkingStatus();
    }

    // ------------------------------------------------------------------ pieces at the stations

    private void watchPorts() {
        final long now = now();
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != State.AT_PORT) {
                continue;
            }
            final AssemblyPortBlockEntity port = portAt(job.port);
            final IItemHandler depot = port == null ? null : port.depot();
            if (port == null || depot == null) {
                // The port or the depot is gone: the piece is considered lost if it doesn't show up.
                pieceMissing(job, now, null);
                continue;
            }
            port.claim(worldPosition, now);
            final List<ItemStack> present = contents(depot);
            if (present.isEmpty()) {
                // Did the deployer grab it? (an empty-handed deployer picks up what's below it)
                if (recoverFromDeployer(job, port)) {
                    continue;
                }
                pieceMissing(job, now, port);
                continue;
            }
            job.missingSince = -1;
            if (present.size() == 1 && ItemStack.isSameItemSameComponents(present.getFirst(), job.piece)) {
                // Not processed yet.
                final long limit = Config.PIECE_TIMEOUT_SECONDS.getAsInt() * 20L;
                if (now - job.sentAt > limit) {
                    final ItemStack back = takeAll(depot);
                    if (!back.isEmpty()) {
                        job.piece = back.copyWithCount(1);
                        if (back.getCount() > 1) {
                            returns.addItem(back.copyWithCount(back.getCount() - 1));
                        }
                    }
                    port.release(worldPosition);
                    port.coolDown(now + PORT_COOLDOWN_TICKS,
                        Component.translatable("rscreatebridge.port.problem.timeout", Config.PIECE_TIMEOUT_SECONDS.getAsInt()));
                    setStatus(true, "rscreatebridge.assembly.problem.stuck", port.getBlockPos().toShortString());
                    job.state = State.WAITING_PORT;
                    job.port = null;
                    setChanged();
                }
                continue;
            }
            // Create finished the step: take everything off the depot and sort it.
            final List<ItemStack> taken = new ArrayList<>();
            for (int slot = 0; slot < depot.getSlots(); slot++) {
                final ItemStack stack = depot.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    final ItemStack out = depot.extractItem(slot, stack.getCount(), false);
                    if (!out.isEmpty()) {
                        taken.add(out);
                    }
                }
            }
            port.release(worldPosition);
            port.clearPortProblem();
            job.port = null;
            stepDone(job, taken);
            setChanged();
        }
    }

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

    private static ItemStack takeAll(final IItemHandler handler) {
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && result.isEmpty()) {
                result = handler.extractItem(slot, stack.getCount(), false);
            }
        }
        return result;
    }

    private boolean recoverFromDeployer(final Job job, final AssemblyPortBlockEntity port) {
        final IItemHandler hand = port.deployer();
        if (hand == null) {
            return false;
        }
        final ItemStack held = hand.getStackInSlot(0);
        if (held.isEmpty() || port.isLoadedItem(held)) {
            return false;
        }
        final AssemblyRecipeInfo info = AssemblyRecipeInfo.byId(level, job.recipe);
        final boolean ours = ItemStack.isSameItemSameComponents(held, job.piece)
            || (info != null && (info.isIntermediate(held) || info.isResult(held)));
        if (!ours) {
            return false;
        }
        final ItemStack back = hand.extractItem(0, held.getCount(), false);
        if (back.isEmpty()) {
            return false;
        }
        port.release(worldPosition);
        job.port = null;
        job.missingSince = -1;
        if (ItemStack.isSameItemSameComponents(back, job.piece)) {
            // It wasn't processed: back to the queue as is.
            job.piece = back.copyWithCount(1);
            if (back.getCount() > 1) {
                returns.addItem(back.copyWithCount(back.getCount() - 1));
            }
            job.state = State.WAITING_PORT;
        } else {
            stepDone(job, List.of(back));
        }
        setChanged();
        return true;
    }

    private void pieceMissing(final Job job, final long now, @Nullable final AssemblyPortBlockEntity port) {
        if (job.missingSince < 0) {
            job.missingSince = now;
            return;
        }
        if (now - job.missingSince < LOST_GRACE_TICKS) {
            return;
        }
        if (port != null) {
            port.release(worldPosition);
        }
        job.port = null;
        job.missingSince = -1;
        fail(job, Component.translatable("rscreatebridge.assembly.problem.lost_piece"));
    }

    /** Sorts what came out of a station. */
    private void stepDone(final Job job, final List<ItemStack> taken) {
        final AssemblyRecipeInfo info = AssemblyRecipeInfo.byId(level, job.recipe);
        if (info != null && info.isSingleStep()) {
            // Single-step recipe: whatever came out of the machine is the result (including chance outputs).
            for (final ItemStack stack : taken) {
                returns.addItem(stack);
            }
            jobs.remove(job);
            clearStuck();
            return;
        }
        ItemStack next = ItemStack.EMPTY;
        boolean product = false;
        ItemStack scrap = ItemStack.EMPTY;
        for (final ItemStack stack : taken) {
            if (next.isEmpty() && info != null && info.isIntermediate(stack)) {
                next = stack.copyWithCount(1);
                if (stack.getCount() > 1) {
                    returns.addItem(stack.copyWithCount(stack.getCount() - 1));
                }
            } else if (info != null && info.isMainOutput(stack)) {
                product = true;
                returns.addItem(stack);
            } else if (info != null && info.isResult(stack)) {
                scrap = stack;
                returns.addItem(stack);
            } else if (next.isEmpty() && ItemStack.isSameItemSameComponents(stack, job.piece)) {
                next = stack.copyWithCount(1); // came back unchanged: retry the step
                if (stack.getCount() > 1) {
                    returns.addItem(stack.copyWithCount(stack.getCount() - 1));
                }
            } else {
                returns.addItem(stack);
            }
        }
        if (product) {
            jobs.remove(job);
            clearStuck();
            RSCreateBridge.LOGGER.debug("Controller {}: finished {}", worldPosition.toShortString(),
                info == null ? job.recipe : info.mainOutput().getHoverName().getString());
            return;
        }
        if (!next.isEmpty()) {
            job.piece = next;
            job.state = State.WAITING_PORT;
            clearStuck();
            return;
        }
        fail(job, scrap.isEmpty() ? Component.translatable("rscreatebridge.assembly.problem.lost_piece")
            : Component.translatable("rscreatebridge.assembly.problem.scrap_short", scrap.getHoverName()));
    }

    private void clearStuck() {
        if (isStatusProblem()) {
            clearProblem();
        }
    }

    private void fail(final Job job, final Component reason) {
        job.attempts++;
        final int max = Config.MAX_RETRIES.getAsInt();
        final AssemblyRecipeInfo info = AssemblyRecipeInfo.byId(level, job.recipe);
        final Component name = info != null ? info.mainOutput().getHoverName() : job.base.getHoverName();
        if (job.attempts > max) {
            jobs.remove(job);
            // The only message that goes to chat: the request is given up.
            setStatusSilently(true, "rscreatebridge.assembly.problem.gave_up", name, job.attempts);
            announceEvent(Component.translatable("rscreatebridge.assembly.problem.gave_up", name, job.attempts));
            setChanged();
            return;
        }
        setStatusSilently(true, "rscreatebridge.assembly.problem.retry", reason, job.attempts, max);
        logEvent(Component.translatable("rscreatebridge.assembly.problem.retry", reason, job.attempts, max));
        job.state = State.WAITING_BASE;
        job.piece = ItemStack.EMPTY;
        job.nextTryAt = now();
        setChanged();
    }

    // ------------------------------------------------------------------ retries

    /** After scrap: a new base item (from the materials or from the network). */
    private void refillBases() {
        final long now = now();
        for (final Job job : jobs) {
            if (job.state != State.WAITING_BASE || job.nextTryAt > now) {
                continue;
            }
            ItemStack base = materials.takeItem(s -> ItemStack.isSameItemSameComponents(s, job.base), 1);
            if (base.isEmpty() && extractFromNetwork(ItemResource.ofItemStack(job.base), 1, Action.EXECUTE) == 1) {
                base = job.base.copy();
            }
            if (base.isEmpty()) {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS;
                setStatus(true, "rscreatebridge.assembly.problem.missing_materials", job.base.getHoverName());
                continue;
            }
            job.piece = base;
            job.state = State.WAITING_PORT;
            setChanged();
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch() {
        final long now = now();
        List<AssemblyPortBlockEntity> ports = null;
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != State.WAITING_PORT || job.nextTryAt > now || job.piece.isEmpty()) {
                continue;
            }
            final AssemblyRecipeInfo info = AssemblyRecipeInfo.byId(level, job.recipe);
            if (info == null || info.steps().isEmpty()) {
                // The recipe disappeared (datapack reload): the piece goes back to the network and the request is closed.
                returns.addItem(job.piece);
                job.piece = ItemStack.EMPTY;
                job.attempts = Integer.MAX_VALUE - 1;
                fail(job, Component.translatable("rscreatebridge.assembly.problem.no_recipe"));
                continue;
            }
            final AssemblyRecipeInfo.Step step = info.nextStep(job.piece);
            if (ports == null) {
                ports = ports();
            }
            final AssemblyPortBlockEntity port = choosePort(ports, step, now);
            if (port == null) {
                continue;
            }
            if (!prepareMachine(port, step, job)) {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS / 5;
                continue;
            }
            final IItemHandler depot = port.depot();
            if (depot == null) {
                continue;
            }
            final ItemStack rest = depot.insertItem(0, job.piece.copy(), false);
            if (!rest.isEmpty()) {
                continue;
            }
            port.claim(worldPosition, now);
            job.state = State.AT_PORT;
            job.port = port.getBlockPos();
            job.sentAt = now;
            job.missingSince = -1;
            setChanged();
        }
    }

    /** A free port with the right machine; prefers one that already has what's needed loaded. */
    @Nullable
    private AssemblyPortBlockEntity choosePort(final List<AssemblyPortBlockEntity> ports,
                                               final AssemblyRecipeInfo.Step step, final long now) {
        AssemblyPortBlockEntity best = null;
        for (final AssemblyPortBlockEntity port : ports) {
            if (port.machineType() != step.type() || !port.isFreeFor(worldPosition, now) || isPortBusy(port)
                || port.stationProblem() != null || !port.depotEmpty()) {
                continue;
            }
            if (isAlreadyLoaded(port, step)) {
                return port;
            }
            if (best == null) {
                best = port;
            }
        }
        return best;
    }

    private boolean busyHere(final AssemblyPortBlockEntity port) {
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT && port.getBlockPos().equals(job.port)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlreadyLoaded(final AssemblyPortBlockEntity port, final AssemblyRecipeInfo.Step step) {
        if (step.type() == AssemblyRecipeInfo.StepType.DEPLOYING && step.heldItem() != null) {
            final IItemHandler hand = port.deployer();
            return hand != null && step.heldItem().test(hand.getStackInSlot(0));
        }
        if (step.type() == AssemblyRecipeInfo.StepType.FILLING && step.fluid() != null) {
            final IFluidHandler tank = port.spout();
            if (tank == null) {
                return false;
            }
            final FluidStack inside = tank.getFluidInTank(0);
            return step.fluid().ingredient().test(inside) && inside.getAmount() >= step.fluid().amount();
        }
        return true;
    }

    /** Gets the machine ready for the step. Returns false if material is missing (the warning stays). */
    private boolean prepareMachine(final AssemblyPortBlockEntity port, final AssemblyRecipeInfo.Step step, final Job job) {
        if (isAlreadyLoaded(port, step)) {
            return true;
        }
        if (step.type() == AssemblyRecipeInfo.StepType.DEPLOYING && step.heldItem() != null) {
            return loadDeployer(port, step);
        }
        if (step.type() == AssemblyRecipeInfo.StepType.FILLING && step.fluid() != null) {
            return loadSpout(port, step.fluid());
        }
        return true;
    }

    private boolean loadDeployer(final AssemblyPortBlockEntity port, final AssemblyRecipeInfo.Step step) {
        final IItemHandler hand = port.deployer();
        if (hand == null) {
            return false;
        }
        // 1) Get the unit before touching the hand (if there is none, nothing changes).
        ItemStack unit = materials.takeItem(step.heldItem(), 1);
        if (unit.isEmpty()) {
            final ResourceKey fromNetwork = extractMatching(
                key -> key instanceof ItemResource ir && step.heldItem().test(ir.toItemStack(1)), 1);
            if (fromNetwork instanceof ItemResource ir) {
                unit = ir.toItemStack(1);
            }
        }
        if (unit.isEmpty()) {
            final ItemStack[] options = step.heldItem().getItems();
            setStatus(true, "rscreatebridge.assembly.problem.missing_materials",
                options.length > 0 ? options[0].getHoverName() : Component.literal("?"));
            return false;
        }
        // 2) Empty the hand (what it held goes back to the materials; if it wasn't ours, to the network).
        final ItemStack held = hand.getStackInSlot(0);
        if (!held.isEmpty()) {
            final ItemStack out = hand.extractItem(0, held.getCount(), false);
            if (out.getCount() < held.getCount()) {
                materials.addItem(unit);
                materials.addItem(out);
                port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                return false;
            }
            if (port.isLoadedItem(out)) {
                materials.addItem(out);
            } else {
                returns.addItem(out);
            }
        }
        // 3) Load.
        final ItemStack rest = hand.insertItem(0, unit.copy(), false);
        if (!rest.isEmpty()) {
            materials.addItem(rest);
            port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
            return false;
        }
        port.noteLoadedItem(unit);
        return true;
    }

    private boolean loadSpout(final AssemblyPortBlockEntity port, final SizedFluidIngredient fluid) {
        final IFluidHandler tank = port.spout();
        if (tank == null) {
            return false;
        }
        FluidStack inside = tank.getFluidInTank(0);
        if (!inside.isEmpty() && !fluid.ingredient().test(inside)) {
            // Another fluid: drain it (to the materials if it's ours; it goes back to the network at the end anyway).
            final FluidStack drained = tank.drain(inside.getAmount(), IFluidHandler.FluidAction.EXECUTE);
            materials.addFluid(drained);
            inside = tank.getFluidInTank(0);
            if (!inside.isEmpty()) {
                port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                return false;
            }
        }
        final int need = fluid.amount() - inside.getAmount();
        if (need <= 0) {
            return true;
        }
        final FluidStack current = inside;
        FluidStack portion = materials.takeFluid(fs -> fluid.ingredient().test(fs)
            && (current.isEmpty() || FluidStack.isSameFluidSameComponents(fs, current)), need);
        if (portion.isEmpty()) {
            final ResourceKey fromNetwork = extractMatching(key -> key instanceof FluidResource fr
                && fluid.ingredient().test(Stock.toFluidStack(fr, 1))
                && (current.isEmpty() || FluidStack.isSameFluidSameComponents(Stock.toFluidStack(fr, 1), current)), need);
            if (fromNetwork instanceof FluidResource fr) {
                portion = Stock.toFluidStack(fr, need);
            }
        }
        if (portion.isEmpty()) {
            final FluidStack[] options = fluid.getFluids();
            setStatus(true, "rscreatebridge.assembly.problem.missing_fluid",
                options.length > 0 ? options[0].getHoverName() : Component.literal("?"), need);
            return false;
        }
        final int filled = tank.fill(portion.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled < portion.getAmount()) {
            materials.addFluid(portion.copyWithAmount(portion.getAmount() - filled));
            if (filled + inside.getAmount() < fluid.amount()) {
                port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                return false;
            }
        }
        port.noteLoadedFluid();
        return true;
    }

    // ------------------------------------------------------------------ network

    private void flushReturns() {
        if (returns.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (final ResourceAmount r : returns.asList()) {
            final long inserted = insertIntoNetwork(r.resource(), r.amount(), Action.EXECUTE);
            if (inserted > 0) {
                returns.remove(r.resource(), inserted);
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** No jobs left: leftover materials go back to the network. */
    private void releaseMaterials() {
        if (materials.isEmpty()) {
            return;
        }
        for (final ResourceAmount r : materials.asList()) {
            returns.add(r.resource(), r.amount());
        }
        materials.clear();
        flushReturns();
        setChanged();
    }

    private void updateWorkingStatus() {
        if (isEmptyForRs()) {
            if (!isStatusProblem()) {
                setStatus(false, "rscreatebridge.status.idle");
            }
            return;
        }
        if (isStatusProblem()) {
            return; // the warning stays visible (Goggles) until something moves forward
        }
        int atPorts = 0;
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT) {
                atPorts++;
            }
        }
        setStatus(false, "rscreatebridge.assembly.status.working", atPorts + basinJobs.atPorts(),
            jobs.size() + basinJobs.jobs.size() - atPorts - basinJobs.atPorts());
    }

    // ================================================================== abort / break

    /**
     * Returns the pieces (held ones and the ones on depots) and the materials to the network, and
     * clears the queue. If the network refuses something and {@code dropLeftovers} is set, it is dropped in the world.
     */
    public void abortAll(final boolean dropLeftovers) {
        if (level == null) {
            return;
        }
        for (final Job job : jobs) {
            if (job.state == State.AT_PORT) {
                final AssemblyPortBlockEntity port = portAt(job.port);
                final IItemHandler depot = port == null ? null : port.depot();
                if (depot != null) {
                    for (final ItemStack stack : List.copyOf(contents(depot))) {
                        if (ItemStack.isSameItemSameComponents(stack, job.piece)) {
                            final ItemStack out = takeAll(depot);
                            returns.addItem(out);
                            break;
                        }
                    }
                }
                if (port != null) {
                    port.release(worldPosition);
                }
            } else if (!job.piece.isEmpty()) {
                returns.addItem(job.piece);
            }
        }
        jobs.clear();
        basinJobs.abortAll(returns);
        for (final ResourceAmount r : materials.asList()) {
            returns.add(r.resource(), r.amount());
        }
        materials.clear();
        flushReturns();
        if (dropLeftovers && !returns.isEmpty()) {
            for (final ResourceAmount r : returns.asList()) {
                if (r.resource() instanceof ItemResource item) {
                    long left = r.amount();
                    while (left > 0) {
                        final int n = (int) Math.min(left, item.item().getDefaultMaxStackSize());
                        returnOrDrop(item.toItemStack(n));
                        left -= n;
                    }
                } else if (r.resource() instanceof FluidResource) {
                    RSCreateBridge.LOGGER.warn("Controller {}: losing {} mB of fluid the network did not accept.",
                        worldPosition.toShortString(), r.amount());
                }
            }
            returns.clear();
        }
        clearProblem();
        setChanged();
    }

    // ================================================================== info

    @Override
    public List<Component> describe() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("block.rscreatebridge.assembly_controller").withStyle(ChatFormatting.GOLD));
        lines.add(describeNetwork());
        final Map<AssemblyRecipeInfo.StepType, Integer> counts = new EnumMap<>(AssemblyRecipeInfo.StepType.class);
        int ready = 0;
        for (final AssemblyPortBlockEntity port : ports()) {
            final AssemblyRecipeInfo.StepType type = port.machineType();
            if (type != null && port.hasStation()) {
                counts.merge(type, 1, Integer::sum);
                if (port.stationProblem() == null) {
                    ready++;
                }
            }
        }
        if (counts.isEmpty()) {
            lines.add(Component.translatable("rscreatebridge.assembly.info.no_ports").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.translatable("rscreatebridge.assembly.info.ports",
                counts.getOrDefault(AssemblyRecipeInfo.StepType.DEPLOYING, 0),
                counts.getOrDefault(AssemblyRecipeInfo.StepType.PRESSING, 0),
                counts.getOrDefault(AssemblyRecipeInfo.StepType.FILLING, 0), ready).withStyle(ChatFormatting.GREEN));
            if (counts.containsKey(AssemblyRecipeInfo.StepType.MIXING) || counts.containsKey(AssemblyRecipeInfo.StepType.COMPACTING)) {
                lines.add(Component.translatable("rscreatebridge.assembly.info.basin_ports",
                    counts.getOrDefault(AssemblyRecipeInfo.StepType.MIXING, 0),
                    counts.getOrDefault(AssemblyRecipeInfo.StepType.COMPACTING, 0)).withStyle(ChatFormatting.GREEN));
            }
        }
        int atPorts = 0;
        int waiting = 0;
        int noBase = 0;
        for (final Job job : jobs) {
            switch (job.state) {
                case AT_PORT -> atPorts++;
                case WAITING_PORT -> waiting++;
                case WAITING_BASE -> noBase++;
            }
        }
        for (final BasinJobs.Job job : basinJobs.jobs) {
            switch (job.state) {
                case AT_PORT -> atPorts++;
                case WAITING_PORT -> waiting++;
                case WAITING_MATERIALS -> noBase++;
            }
        }
        if (!isEmptyForRs()) {
            lines.add(Component.translatable("rscreatebridge.assembly.info.pieces", atPorts, waiting, noBase)
                .withStyle(ChatFormatting.GRAY));
        }
        if (!materials.isEmpty()) {
            lines.add(Component.translatable("rscreatebridge.assembly.info.materials", materials.toString())
                .withStyle(ChatFormatting.GRAY));
        }
        if (!returns.isEmpty()) {
            lines.add(Component.translatable("rscreatebridge.assembly.info.returns", returns.toString())
                .withStyle(ChatFormatting.YELLOW));
        }
        lines.add(getStatus().copy().withStyle(isStatusProblem() ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        return lines;
    }

    // ================================================================== NBT

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Returns", returns.save(registries));
        tag.put("Materials", materials.save(registries));
        final ListTag list = new ListTag();
        for (final Job job : jobs) {
            final CompoundTag j = new CompoundTag();
            j.put("Base", job.base.save(registries));
            j.putString("Recipe", job.recipe.toString());
            if (!job.piece.isEmpty()) {
                j.put("Piece", job.piece.save(registries));
            }
            j.putString("State", job.state.name());
            if (job.port != null) {
                j.put("Port", NbtUtils.writeBlockPos(job.port));
            }
            j.putInt("Attempts", job.attempts);
            j.putLong("SentAt", job.sentAt);
            j.putLong("NextTryAt", job.nextTryAt);
            list.add(j);
        }
        tag.put("Jobs", list);
        tag.put("BasinJobs", basinJobs.save(registries));
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        returns.load(tag.getList("Returns", Tag.TAG_COMPOUND), registries);
        materials.load(tag.getList("Materials", Tag.TAG_COMPOUND), registries);
        jobs.clear();
        for (final Tag raw : tag.getList("Jobs", Tag.TAG_COMPOUND)) {
            final CompoundTag j = (CompoundTag) raw;
            final ItemStack base = ItemStack.parseOptional(registries, j.getCompound("Base"));
            final ResourceLocation recipe = ResourceLocation.tryParse(j.getString("Recipe"));
            if (base.isEmpty() || recipe == null) {
                continue;
            }
            final Job job = new Job(base, recipe);
            job.piece = j.contains("Piece") ? ItemStack.parseOptional(registries, j.getCompound("Piece")) : ItemStack.EMPTY;
            try {
                job.state = State.valueOf(j.getString("State"));
            } catch (final IllegalArgumentException e) {
                // A job saved by an older version (belt based): what it held goes back to the network and it is redone
                // from a new base item.
                if (j.contains("Holding")) {
                    returns.addItem(ItemStack.parseOptional(registries, j.getCompound("Holding")));
                }
                job.state = State.WAITING_BASE;
                job.piece = ItemStack.EMPTY;
            }
            job.port = NbtUtils.readBlockPos(j, "Port").orElse(null);
            if (job.state == State.AT_PORT && job.port == null) {
                job.state = State.WAITING_BASE;
            }
            job.attempts = j.getInt("Attempts");
            job.sentAt = j.getLong("SentAt");
            job.nextTryAt = j.getLong("NextTryAt");
            jobs.add(job);
        }
        basinJobs.load(tag.getList("BasinJobs", Tag.TAG_COMPOUND), registries);
    }
}
