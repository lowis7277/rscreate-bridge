package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.processing.recipe.HeatCondition;

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
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Assembly Controller: receives from the Autocrafter everything the pattern asks for and makes the product
 * with the Assembly Ports on its own RS network.
 * <p>
 * Every product is a {@link Job}: a sequenced assembly piece (several depot steps), a single depot step
 * (press, deployer or spout) or a basin kit (mixer or press over a basin). All of them go through the same
 * cycle: pick a free station of the right type, load its machine if needed, place the inputs
 * ({@link StationOps#place}), wait for Create ({@link StationOps#poll}) and sort the outputs:
 * <ul>
 *   <li>product: to the RS network (the request receives it, like with an Importer);</li>
 *   <li>intermediate (sequenced assembly): back to the queue for its next step;</li>
 *   <li>scrap or lost inputs: start over from the job's start resources (up to {@code maxRetries}).</li>
 * </ul>
 * A station in an unloaded chunk pauses its job; a broken Port hands its inputs back. When there are no jobs
 * left, leftover materials go back to the network. The controller and the ports expose no inventory, so
 * RS's generic sink can't insert twice.
 */
public class AssemblyControllerBlockEntity extends BridgeNodeBlockEntity {
    private static final int TICK_INTERVAL = 2;
    private static final int MATERIAL_RETRY_TICKS = 100;
    /** If a station's inputs disappear (someone took them), wait this long before treating them as lost. */
    private static final int LOST_GRACE_TICKS = 40;
    private static final int PORT_COOLDOWN_TICKS = 200;

    /** Positions treated as unloaded; only used to simulate unloaded chunks in tests. */
    @Nullable
    private static Predicate<BlockPos> simulatedUnloaded;

    private final List<Job> jobs = new ArrayList<>();
    /** Materials the Autocrafter delivered for the steps (reserved by RS for the request). */
    private final Stock materials = new Stock();
    /** To be returned to the network (products, scrap, leftovers). */
    private final Stock returns = new Stock();
    @Nullable
    private UUID abortRequestedBy;
    private long abortRequestedAt;
    private int ticks;

    public AssemblyControllerBlockEntity(final BlockPos pos, final BlockState state) {
        super(Registration.ASSEMBLY_CONTROLLER_BE.get(), pos, state, Config.ASSEMBLY_CONTROLLER_ENERGY.getAsInt());
    }

    /** Tests only: positions matching the predicate behave as if their chunk were unloaded. */
    public static void simulateUnloadedStations(@Nullable final Predicate<BlockPos> unloaded) {
        simulatedUnloaded = unloaded;
    }

    private long now() {
        return level == null ? 0 : level.getGameTime();
    }

    private boolean isStationLoaded(final BlockPos pos) {
        return level != null && level.isLoaded(pos) && (simulatedUnloaded == null || !simulatedUnloaded.test(pos));
    }

    private List<AssemblyPortBlockEntity> ports() {
        return level == null ? List.of() : AssemblyPortBlockEntity.portsOn(level, getNetwork());
    }

    @Nullable
    private AssemblyPortBlockEntity portAt(@Nullable final BlockPos pos) {
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
        // Every recipe these resources match (cached), in priority order. The first one whose machines are on
        // the network wins.
        final Set<MachineType> available = availableMachines();
        MachineType firstMissing = null;
        Recipes.Candidate chosen = null;
        for (final Recipes.Candidate candidate : Recipes.matches(level, resources)) {
            final MachineType missing = firstMissing(candidate.recipe().machines(), available);
            if (missing == null) {
                chosen = candidate;
                break;
            }
            firstMissing = firstMissing == null ? missing : firstMissing;
        }
        if (chosen == null) {
            if (firstMissing != null) {
                rejectStatus("rscreatebridge.assembly.problem.no_port", Component.translatable(firstMissing.machineKey()));
            } else {
                rejectStatus("rscreatebridge.assembly.problem.no_recipe");
            }
            return ExternalPatternSink.Result.REJECTED;
        }
        final ProcessRecipe recipe = chosen.recipe();
        if (!heatAvailable(recipe)) {
            rejectStatus("rscreatebridge.assembly.problem.no_heat", recipe.productName(), heatName(recipe.heat()));
            return ExternalPatternSink.Result.REJECTED;
        }
        final List<Job> created = new ArrayList<>();
        for (final Stock start : chosen.match().starts()) {
            created.add(new Job(recipe.kind(), recipe.id(), start));
        }
        if (jobs.size() + created.size() > Config.MAX_QUEUED_JOBS.getAsInt()) {
            return ExternalPatternSink.Result.REJECTED; // queue full: RS retries later
        }
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED;
        }
        materials.addAll(chosen.match().extras().asList());
        jobs.addAll(created);
        RSCreateBridge.LOGGER.debug("Controller {}: {} job(s) of {} queued", worldPosition.toShortString(),
            created.size(), created.getFirst().recipe);
        if (isStatusProblem()) {
            clearProblem();
        }
        setChanged();
        return ExternalPatternSink.Result.ACCEPTED;
    }

    /** Machine types that have at least one port on the network. */
    private Set<MachineType> availableMachines() {
        final Set<MachineType> available = EnumSet.noneOf(MachineType.class);
        for (final AssemblyPortBlockEntity port : ports()) {
            final MachineType type = port.machineType();
            if (type != null && port.hasStation()) {
                available.add(type);
            }
        }
        return available;
    }

    /** Does any port with the recipe's machine have the heat it needs? */
    private boolean heatAvailable(final ProcessRecipe recipe) {
        if (recipe.heat() == HeatCondition.NONE) {
            return true;
        }
        for (final AssemblyPortBlockEntity port : ports()) {
            if (recipe.machines().contains(port.machineType()) && recipe.heat().testBlazeBurner(port.heat())) {
                return true;
            }
        }
        return false;
    }

    private static Component heatName(final HeatCondition heat) {
        return Component.translatable("rscreatebridge.heat_needed." + heat.getSerializedName());
    }

    /** The first needed machine type (in the recipe's order) that has no port on the network, or null. */
    @Nullable
    private static MachineType firstMissing(final Set<MachineType> needed, final Set<MachineType> available) {
        for (final MachineType machine : needed) {
            if (!available.contains(machine)) {
                return machine;
            }
        }
        return null;
    }

    /** A rejection reason is only shown while the controller is idle. */
    private void rejectStatus(final String key, final Object... args) {
        if (jobs.isEmpty()) {
            setStatus(true, key, args);
        }
    }

    public boolean isEmptyForRs() {
        return jobs.isEmpty();
    }

    /**
     * First shift + click: remembers the request and returns false. If the same player repeats it within
     * {@code window} ticks, returns true (abort).
     */
    public boolean confirmAbort(final UUID player, final long now, final long window) {
        if (player.equals(abortRequestedBy) && now - abortRequestedAt <= window) {
            abortRequestedBy = null;
            return true;
        }
        abortRequestedBy = player;
        abortRequestedAt = now;
        return false;
    }

    /** How many jobs are queued. */
    public int pendingCount() {
        return jobs.size();
    }

    /** How many jobs currently have their inputs at a station (to see parallel work). */
    public int piecesAtPorts() {
        int count = 0;
        for (final Job job : jobs) {
            if (job.state == Job.State.AT_STATION) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected boolean hasWork() {
        return !jobs.isEmpty();
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
        watchStations();
        restartJobs();
        dispatch();
        if (jobs.isEmpty()) {
            releaseMaterials();
        }
        updateWorkingStatus();
    }

    // ------------------------------------------------------------------ jobs at the stations

    private void watchStations() {
        final long now = now();
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != Job.State.AT_STATION || job.port == null) {
                continue;
            }
            if (!isStationLoaded(job.port)) {
                // The station's chunk is unloaded: pause (and stop the clock) until it loads again.
                job.sentAt += TICK_INTERVAL;
                job.missingSince = -1;
                continue;
            }
            final AssemblyPortBlockEntity port = portAt(job.port);
            if (port == null) {
                inputsMissing(job, now, null);
                continue;
            }
            port.claim(worldPosition, now);
            final StationOps.Poll poll = StationOps.poll(port, job, held -> belongsTo(job, held));
            switch (poll.status()) {
                case WAITING -> {
                    job.missingSince = -1;
                    if (now - job.sentAt > Config.PIECE_TIMEOUT_SECONDS.getAsInt() * 20L) {
                        timedOut(job, port, now);
                    }
                }
                case UNCHANGED -> {
                    leaveStation(job, port);
                    job.holding.addAll(toStock(poll).asList());
                    job.state = Job.State.WAITING_STATION;
                }
                case DONE -> {
                    leaveStation(job, port);
                    port.clearPortProblem();
                    outputs(job, poll.items(), poll.fluids());
                }
                case EMPTY -> inputsMissing(job, now, port);
            }
        }
    }

    /** Does an item a deployer picked up belong to this job (intermediate or result of its recipe)? */
    private boolean belongsTo(final Job job, final ItemStack held) {
        return Recipes.byId(level, job.recipe) instanceof AssemblyRecipeInfo info
            && (info.isIntermediate(held) || info.isResult(held));
    }

    private void leaveStation(final Job job, final AssemblyPortBlockEntity port) {
        port.release(worldPosition);
        job.port = null;
        job.missingSince = -1;
        setChanged();
    }

    /** The machine didn't process in time: take the inputs back and try another station. */
    private void timedOut(final Job job, final AssemblyPortBlockEntity port, final long now) {
        final Stock back = StationOps.takeBack(port, job);
        leaveStation(job, port);
        job.holding.addAll(back.asList());
        port.coolDown(now + PORT_COOLDOWN_TICKS,
            Component.translatable("rscreatebridge.port.problem.timeout", Config.PIECE_TIMEOUT_SECONDS.getAsInt()));
        setStatus(true, "rscreatebridge.assembly.problem.stuck", port.getBlockPos().toShortString());
        job.state = Job.State.WAITING_STATION;
    }

    private void inputsMissing(final Job job, final long now, @Nullable final AssemblyPortBlockEntity port) {
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
        job.placed.clear();
        fail(job, Component.translatable(job.usesBasin() ? "rscreatebridge.assembly.problem.lost_basin"
            : "rscreatebridge.assembly.problem.lost_piece"));
    }

    /**
     * A Port is being broken (or picked up with a wrench) while one of our jobs is at its station: finish the
     * step if Create already did it, otherwise take the inputs back so nothing is left behind.
     */
    public void portRemoved(final AssemblyPortBlockEntity port) {
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != Job.State.AT_STATION || !port.getBlockPos().equals(job.port)) {
                continue;
            }
            final StationOps.Poll poll = StationOps.poll(port, job, held -> belongsTo(job, held));
            if (poll.status() == StationOps.Status.DONE) {
                leaveStation(job, port);
                outputs(job, poll.items(), poll.fluids());
            } else {
                final Stock back = poll.status() == StationOps.Status.UNCHANGED ? toStock(poll)
                    : StationOps.takeBack(port, job);
                leaveStation(job, port);
                job.holding.addAll(back.asList());
                job.state = job.holding.isEmpty() ? Job.State.WAITING_RESTART : Job.State.WAITING_STATION;
            }
        }
        flushReturns();
    }

    private static Stock toStock(final StationOps.Poll poll) {
        final Stock stock = new Stock();
        for (final ItemStack stack : poll.items()) {
            stock.addItem(stack);
        }
        stock.addAll(poll.fluids().asList());
        return stock;
    }

    // ------------------------------------------------------------------ outputs

    /** Sorts what came out of a station. */
    private void outputs(final Job job, final List<ItemStack> items, final Stock fluids) {
        returns.addAll(fluids.asList());
        if (!(Recipes.byId(level, job.recipe) instanceof AssemblyRecipeInfo info) || info.isSingleStep()) {
            // Single step or basin: everything that came out is the result (including chance outputs).
            for (final ItemStack stack : items) {
                returns.addItem(stack);
            }
            jobs.remove(job);
            clearStuck();
            return;
        }
        ItemStack next = ItemStack.EMPTY;
        boolean product = false;
        ItemStack scrap = ItemStack.EMPTY;
        final ItemStack placed = job.placedPiece();
        for (final ItemStack stack : items) {
            if (next.isEmpty() && info.isIntermediate(stack)) {
                next = stack.copyWithCount(1);
                if (stack.getCount() > 1) {
                    returns.addItem(stack.copyWithCount(stack.getCount() - 1));
                }
            } else if (info.isMainOutput(stack)) {
                product = true;
                returns.addItem(stack);
            } else if (info.isResult(stack)) {
                scrap = stack;
                returns.addItem(stack);
            } else if (next.isEmpty() && ItemStack.isSameItemSameComponents(stack, placed)) {
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
                info.mainOutput().getHoverName().getString());
            return;
        }
        if (!next.isEmpty()) {
            job.setPiece(next);
            job.state = Job.State.WAITING_STATION;
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

    private Component productName(final Job job) {
        final ProcessRecipe recipe = Recipes.byId(level, job.recipe);
        return recipe != null ? recipe.productName() : Component.literal(job.recipe.toString());
    }

    private void fail(final Job job, final Component reason) {
        job.attempts++;
        final int max = Config.MAX_RETRIES.getAsInt();
        if (job.attempts > max) {
            jobs.remove(job);
            returns.addAll(job.holding.asList());
            // The only message that goes to chat: the request is given up.
            final Component name = productName(job);
            setStatusSilently(true, "rscreatebridge.assembly.problem.gave_up", name, job.attempts);
            announceEvent(Component.translatable("rscreatebridge.assembly.problem.gave_up", name, job.attempts));
            setChanged();
            return;
        }
        setStatusSilently(true, "rscreatebridge.assembly.problem.retry", reason, job.attempts, max);
        logEvent(Component.translatable("rscreatebridge.assembly.problem.retry", reason, job.attempts, max));
        returns.addAll(job.holding.asList());
        job.holding.clear();
        job.state = Job.State.WAITING_RESTART;
        job.nextTryAt = now();
        setChanged();
    }

    // ------------------------------------------------------------------ retries

    /** Jobs that start over take their start resources again (materials first, then the network). */
    private void restartJobs() {
        final long now = now();
        for (final Job job : jobs) {
            if (job.state != Job.State.WAITING_RESTART || job.nextTryAt > now) {
                continue;
            }
            if (!takeFromMaterialsOrNetwork(job.start)) {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS;
                if (job.usesBasin()) {
                    setStatus(true, "rscreatebridge.assembly.problem.missing_kit");
                } else {
                    setStatus(true, "rscreatebridge.assembly.problem.missing_materials", job.base().getHoverName());
                }
                continue;
            }
            job.holding.clear();
            job.holding.addAll(job.start.asList());
            job.state = Job.State.WAITING_STATION;
            setChanged();
        }
    }

    /** Takes exactly those resources, from the request materials first and then the network (all or nothing). */
    private boolean takeFromMaterialsOrNetwork(final Stock wanted) {
        for (final ResourceAmount r : wanted.asList()) {
            final long fromMaterials = Math.min(materials.get(r.resource()), r.amount());
            final long rest = r.amount() - fromMaterials;
            if (rest > 0 && extractFromNetwork(r.resource(), rest, Action.SIMULATE) < rest) {
                return false;
            }
        }
        for (final ResourceAmount r : wanted.asList()) {
            final long fromMaterials = materials.remove(r.resource(), r.amount());
            final long rest = r.amount() - fromMaterials;
            if (rest > 0) {
                extractFromNetwork(r.resource(), rest, Action.EXECUTE);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch() {
        final long now = now();
        List<AssemblyPortBlockEntity> ports = null;
        for (final Job job : List.copyOf(jobs)) {
            if (job.state != Job.State.WAITING_STATION || job.nextTryAt > now || job.holding.isEmpty()) {
                continue;
            }
            // The recipe says what this job needs next.
            final ProcessRecipe recipe = Recipes.byId(level, job.recipe);
            final Operation op = recipe == null ? null : recipe.next(job);
            if (op == null) {
                // The recipe disappeared (datapack reload): its inputs go back to the network and the job ends.
                returns.addAll(job.holding.asList());
                job.holding.clear();
                job.attempts = Integer.MAX_VALUE - 1;
                fail(job, Component.translatable("rscreatebridge.assembly.problem.no_recipe"));
                continue;
            }
            if (ports == null) {
                ports = ports();
            }
            final AssemblyPortBlockEntity port = choosePort(ports, op, recipe.productName(), now);
            if (port == null) {
                continue;
            }
            if (!prepareMachine(port, op)) {
                job.nextTryAt = now + MATERIAL_RETRY_TICKS / 5;
                continue;
            }
            if (!StationOps.place(port, job)) {
                if (job.usesBasin()) {
                    port.setPortStatus(true, Component.translatable("rscreatebridge.port.problem.cannot_load"));
                }
                continue;
            }
            port.claim(worldPosition, now);
            job.state = Job.State.AT_STATION;
            job.port = port.getBlockPos();
            job.sentAt = now;
            job.missingSince = -1;
            setChanged();
        }
    }

    /**
     * A free, working, empty station for the operation, with enough heat. Prefers one whose machine already has
     * what the operation needs loaded.
     */
    @Nullable
    private AssemblyPortBlockEntity choosePort(final List<AssemblyPortBlockEntity> ports, final Operation op,
                                               final Component product, final long now) {
        AssemblyPortBlockEntity best = null;
        boolean heatMissing = false;
        for (final AssemblyPortBlockEntity port : ports) {
            if (port.machineType() != op.machine() || !port.isFreeFor(worldPosition, now) || isPortBusy(port)
                || port.stationProblem() != null || !StationOps.isEmpty(port, op.machine().usesBasin())) {
                continue;
            }
            if (!op.heat().testBlazeBurner(port.heat())) {
                heatMissing = true;
                continue;
            }
            if (isAlreadyLoaded(port, op)) {
                return port;
            }
            if (best == null) {
                best = port;
            }
        }
        if (best == null && heatMissing) {
            setStatus(true, "rscreatebridge.assembly.problem.no_heat", product, heatName(op.heat()));
        }
        return best;
    }

    /** Does the port hold inputs from one of this controller's jobs? */
    private boolean isPortBusy(final AssemblyPortBlockEntity port) {
        for (final Job job : jobs) {
            if (job.state == Job.State.AT_STATION && port.getBlockPos().equals(job.port)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ machine loading (depot steps)

    private static boolean isAlreadyLoaded(final AssemblyPortBlockEntity port, final Operation op) {
        if (op.heldItem() != null) {
            final IItemHandler hand = port.deployer();
            return hand != null && op.heldItem().test(hand.getStackInSlot(0));
        }
        if (op.fluid() != null) {
            final IFluidHandler tank = port.spout();
            if (tank == null) {
                return false;
            }
            final FluidStack inside = tank.getFluidInTank(0);
            return op.fluid().ingredient().test(inside) && inside.getAmount() >= op.fluid().amount();
        }
        return true;
    }

    /** Gets the machine ready for the operation. Returns false if material is missing (the warning stays). */
    private boolean prepareMachine(final AssemblyPortBlockEntity port, final Operation op) {
        if (isAlreadyLoaded(port, op)) {
            return true;
        }
        if (op.heldItem() != null) {
            return loadDeployer(port, op.heldItem());
        }
        if (op.fluid() != null) {
            return loadSpout(port, op.fluid());
        }
        return true;
    }

    private boolean loadDeployer(final AssemblyPortBlockEntity port, final Ingredient heldItem) {
        final IItemHandler hand = port.deployer();
        if (hand == null) {
            return false;
        }
        // 1) Get the unit before touching the hand (if there is none, nothing changes).
        ItemStack unit = materials.takeItem(heldItem, 1);
        if (unit.isEmpty()) {
            final ResourceKey fromNetwork = extractMatching(
                key -> key instanceof ItemResource ir && heldItem.test(ir.toItemStack(1)), 1);
            if (fromNetwork instanceof ItemResource ir) {
                unit = ir.toItemStack(1);
            }
        }
        if (unit.isEmpty()) {
            final ItemStack[] options = heldItem.getItems();
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
        returns.addAll(materials.asList());
        materials.clear();
        flushReturns();
        setChanged();
    }

    private void updateWorkingStatus() {
        if (jobs.isEmpty()) {
            if (!isStatusProblem()) {
                setStatus(false, "rscreatebridge.status.idle");
            }
            return;
        }
        if (isStatusProblem()) {
            return; // the warning stays visible (Goggles) until something moves forward
        }
        final int atStations = piecesAtPorts();
        setStatus(false, "rscreatebridge.assembly.status.working", atStations, jobs.size() - atStations);
    }

    // ================================================================== abort / break

    /**
     * Returns everything to the network: what the controller holds, the inputs at the stations and the
     * materials, and clears the queue. If the network refuses something and {@code dropLeftovers} is set, it is
     * dropped in the world.
     */
    public void abortAll(final boolean dropLeftovers) {
        if (level == null) {
            return;
        }
        for (final Job job : jobs) {
            returns.addAll(job.holding.asList());
            if (job.state == Job.State.AT_STATION) {
                final AssemblyPortBlockEntity port = portAt(job.port);
                if (port != null) {
                    returns.addAll(StationOps.takeBack(port, job).asList());
                    port.release(worldPosition);
                }
            }
        }
        jobs.clear();
        returns.addAll(materials.asList());
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
        final Map<MachineType, Integer> counts = new EnumMap<>(MachineType.class);
        int ready = 0;
        for (final AssemblyPortBlockEntity port : ports()) {
            final MachineType type = port.machineType();
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
                counts.getOrDefault(MachineType.DEPLOYING, 0),
                counts.getOrDefault(MachineType.PRESSING, 0),
                counts.getOrDefault(MachineType.FILLING, 0), ready).withStyle(ChatFormatting.GREEN));
            if (counts.containsKey(MachineType.MIXING) || counts.containsKey(MachineType.COMPACTING)) {
                lines.add(Component.translatable("rscreatebridge.assembly.info.basin_ports",
                    counts.getOrDefault(MachineType.MIXING, 0),
                    counts.getOrDefault(MachineType.COMPACTING, 0)).withStyle(ChatFormatting.GREEN));
            }
        }
        if (!jobs.isEmpty()) {
            final Map<Job.State, Integer> byState = new EnumMap<>(Job.State.class);
            for (final Job job : jobs) {
                byState.merge(job.state, 1, Integer::sum);
            }
            lines.add(Component.translatable("rscreatebridge.assembly.info.pieces",
                byState.getOrDefault(Job.State.AT_STATION, 0), byState.getOrDefault(Job.State.WAITING_STATION, 0),
                byState.getOrDefault(Job.State.WAITING_RESTART, 0)).withStyle(ChatFormatting.GRAY));
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
            list.add(job.save(registries));
        }
        tag.put("Jobs", list);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        returns.load(tag.getList("Returns", Tag.TAG_COMPOUND), registries);
        materials.load(tag.getList("Materials", Tag.TAG_COMPOUND), registries);
        jobs.clear();
        for (final Tag raw : tag.getList("Jobs", Tag.TAG_COMPOUND)) {
            final CompoundTag j = (CompoundTag) raw;
            final Job job = j.contains("Kind") ? Job.load(j, registries) : loadOldJob(j, registries, false);
            if (job != null) {
                jobs.add(job);
            }
        }
        for (final Tag raw : tag.getList("BasinJobs", Tag.TAG_COMPOUND)) {
            final Job job = loadOldJob((CompoundTag) raw, registries, true);
            if (job != null) {
                jobs.add(job);
            }
        }
    }

    /**
     * Jobs saved by older versions: they start over from their start resources. Whatever the old job held goes
     * back to the network first, so nothing is lost or duplicated.
     */
    @Nullable
    private Job loadOldJob(final CompoundTag tag, final HolderLookup.Provider registries, final boolean basin) {
        final ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("Recipe"));
        final Stock start = new Stock();
        if (basin) {
            start.load(tag.getList("Kit", Tag.TAG_COMPOUND), registries);
            final Stock holding = new Stock();
            holding.load(tag.getList("Holding", Tag.TAG_COMPOUND), registries);
            returns.addAll(holding.asList());
        } else {
            start.addItem(ItemStack.parseOptional(registries, tag.getCompound("Base")).copyWithCount(1));
            for (final String key : List.of("Piece", "Holding")) {
                if (tag.contains(key)) {
                    returns.addItem(ItemStack.parseOptional(registries, tag.getCompound(key)));
                }
            }
        }
        if (recipe == null || start.isEmpty()) {
            return null;
        }
        final Job job = new Job(basin ? Job.Kind.BASIN : Job.Kind.SEQUENCED, recipe, start);
        job.holding.clear();
        job.state = Job.State.WAITING_RESTART;
        return job;
    }
}
