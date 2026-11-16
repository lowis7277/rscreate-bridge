package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;

import dev.rscreatebridge.common.ResourceMatcher;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.Nullable;

/**
 * A parsed create:sequenced_assembly recipe: base ingredient, steps with what each one consumes,
 * loops, transitional item and results. It also knows which resources a full run consumes, to compare
 * against what an RS pattern delivers. Single-step press/deployer/spout recipes use the same shape with
 * one step and no transitional item.
 */
public record AssemblyRecipeInfo(ResourceLocation id,
                                 Ingredient base,
                                 List<Step> steps,
                                 int loops,
                                 ItemStack transitional,
                                 ItemStack mainOutput,
                                 @Nullable String unsupportedStep,
                                 List<ItemStack> results) implements ProcessRecipe {

    public record Step(MachineType type, @Nullable Ingredient heldItem, boolean keepHeldItem,
                       @Nullable SizedFluidIngredient fluid) {
    }

    public boolean isSupported() {
        return unsupportedStep == null;
    }

    // ------------------------------------------------------------------ ProcessRecipe

    @Override
    public Job.Kind kind() {
        return isSingleStep() ? Job.Kind.SINGLE : Job.Kind.SEQUENCED;
    }

    @Override
    public Set<MachineType> machines() {
        final Set<MachineType> machines = new LinkedHashSet<>(); // in step order
        for (final Step step : steps) {
            machines.add(step.type());
        }
        return machines;
    }

    /** One job per base item; the step ingredients are extras. Recipes with unsupported steps never match. */
    @Nullable
    @Override
    public Match match(final Collection<ResourceAmount> resources) {
        final Split split = isSupported() ? split(resources) : null;
        if (split == null) {
            return null;
        }
        final List<Stock> starts = new ArrayList<>();
        for (final ResourceAmount base : split.bases().asList()) {
            final Stock unit = new Stock();
            unit.add(base.resource(), 1);
            for (long i = 0; i < base.amount(); i++) {
                starts.add(unit);
            }
        }
        return new Match(List.copyOf(starts), split.extras());
    }

    /** The operation for the step the piece is at. */
    @Nullable
    @Override
    public Operation next(final Job job) {
        if (steps.isEmpty()) {
            return null;
        }
        final Step step = nextStep(job.piece());
        return new Operation(step.type(), step.heldItem(), step.fluid(),
            com.simibubi.create.content.processing.recipe.HeatCondition.NONE);
    }

    @Override
    public Component productName() {
        return mainOutput.getHoverName();
    }

    /** What a full run consumes, as requirements for the matcher. */
    public List<ResourceMatcher.Requirement> requirementsPerRun(final long multiplier) {
        final List<ResourceMatcher.Requirement> reqs = new ArrayList<>();
        reqs.add(new ResourceMatcher.Requirement(itemPredicate(base), multiplier));
        for (final Step step : steps) {
            if (step.type() == MachineType.DEPLOYING && step.heldItem() != null) {
                final long perRun = step.keepHeldItem() ? 1 : loops;
                reqs.add(new ResourceMatcher.Requirement(itemPredicate(step.heldItem()), perRun * multiplier));
            } else if (step.type() == MachineType.FILLING && step.fluid() != null) {
                final SizedFluidIngredient fluid = step.fluid();
                reqs.add(new ResourceMatcher.Requirement(
                    key -> key instanceof FluidResource fr && fluid.ingredient().test(Stock.toFluidStack(fr, 1)),
                    (long) fluid.amount() * loops * multiplier));
            }
        }
        return reqs;
    }

    private long itemUnitsPerRun() {
        long units = 1;
        for (final Step step : steps) {
            if (step.type() == MachineType.DEPLOYING && step.heldItem() != null) {
                units += step.keepHeldItem() ? 1 : loops;
            }
        }
        return units;
    }

    private long fluidUnitsPerRun() {
        long units = 0;
        for (final Step step : steps) {
            if (step.type() == MachineType.FILLING && step.fluid() != null) {
                units += (long) step.fluid().amount() * loops;
            }
        }
        return units;
    }

    /**
     * How many full runs (k) the delivered resources are exactly, or 0 if they don't match.
     */
    public int runsFor(final Collection<ResourceAmount> resources) {
        long items = 0;
        long fluids = 0;
        for (final ResourceAmount r : resources) {
            if (r.resource() instanceof ItemResource) {
                items += r.amount();
            } else if (r.resource() instanceof FluidResource) {
                fluids += r.amount();
            } else {
                return 0;
            }
        }
        final long perRunItems = itemUnitsPerRun();
        if (items == 0 || items % perRunItems != 0) {
            return 0;
        }
        final long k = items / perRunItems;
        if (fluids != fluidUnitsPerRun() * k || k > Integer.MAX_VALUE) {
            return 0;
        }
        final List<ResourceAmount> list = List.copyOf(resources);
        return ResourceMatcher.matchExactly(requirementsPerRun(k), list) != null ? (int) k : 0;
    }


    // ------------------------------------------------------------------ one-loop patterns (JEI)

    /** Requirements for a single pass through the sequence, and the factor to reach a full run. */
    private List<ResourceMatcher.Requirement> requirementsPerLoop(final List<Long> factors) {
        final List<ResourceMatcher.Requirement> reqs = new ArrayList<>();
        reqs.add(new ResourceMatcher.Requirement(itemPredicate(base), 1));
        factors.add(1L);
        for (final Step step : steps) {
            if (step.type() == MachineType.DEPLOYING && step.heldItem() != null) {
                reqs.add(new ResourceMatcher.Requirement(itemPredicate(step.heldItem()), 1));
                factors.add(step.keepHeldItem() ? 1L : loops);
            } else if (step.type() == MachineType.FILLING && step.fluid() != null) {
                final SizedFluidIngredient fluid = step.fluid();
                reqs.add(new ResourceMatcher.Requirement(
                    key -> key instanceof FluidResource fr && fluid.ingredient().test(Stock.toFluidStack(fr, 1)),
                    fluid.amount()));
                factors.add((long) loops);
            }
        }
        return reqs;
    }

    // ------------------------------------------------------------------ pattern -> base items + extras

    /**
     * How the resources from RS are split: {@code bases} = one base item per run (the piece to build),
     * {@code extras} = the rest (step ingredients), kept by the controller to load the machines.
     */
    public record Split(int runs, Stock bases, Stock extras) {
    }

    /** Accepts: base items only, a full run (or k), or one JEI-style pass through the sequence (or k). */
    @Nullable
    public Split split(final Collection<ResourceAmount> resources) {
        if (resources.isEmpty()) {
            return null;
        }
        final List<ResourceAmount> list = List.copyOf(resources);
        // 1) Full run (or k runs).
        final int full = runsFor(resources);
        if (full > 0) {
            final long[][] assignment = ResourceMatcher.matchExactly(requirementsPerRun(full), list);
            if (assignment != null) {
                return splitWith(list, assignment[0], full);
            }
        }
        // 2) One pass through the sequence (what JEI builds), or k.
        if (loops > 1) {
            final List<Long> factors = new ArrayList<>();
            final List<ResourceMatcher.Requirement> perLoop = requirementsPerLoop(factors);
            long needed = 0;
            for (final ResourceMatcher.Requirement r : perLoop) {
                needed += r.amount();
            }
            long given = 0;
            for (final ResourceAmount r : list) {
                given += r.amount();
            }
            if (needed > 0 && given % needed == 0) {
                final long k = given / needed;
                final List<ResourceAmount> oneRun = new ArrayList<>();
                boolean divisible = true;
                for (final ResourceAmount r : list) {
                    if (r.amount() % k != 0) {
                        divisible = false;
                        break;
                    }
                    oneRun.add(new ResourceAmount(r.resource(), r.amount() / k));
                }
                if (divisible && k <= Integer.MAX_VALUE) {
                    final long[][] assignment = ResourceMatcher.matchExactly(perLoop, oneRun);
                    if (assignment != null) {
                        final long[] baseRow = new long[list.size()];
                        for (int j = 0; j < list.size(); j++) {
                            baseRow[j] = assignment[0][j] * k;
                        }
                        return splitWith(list, baseRow, (int) k);
                    }
                }
            }
        }
        // 3) Base items only (the simplest pattern: "1 golden sheet -> 1 precision mechanism").
        long bases = 0;
        for (final ResourceAmount r : list) {
            if (!(r.resource() instanceof ItemResource item) || !base.test(item.toItemStack(1))) {
                return null;
            }
            bases += r.amount();
        }
        if (bases <= 0 || bases > Integer.MAX_VALUE) {
            return null;
        }
        final Stock baseStock = new Stock();
        baseStock.addAll(list);
        return new Split((int) bases, baseStock, new Stock());
    }

    private static Split splitWith(final List<ResourceAmount> list, final long[] baseAmounts, final int runs) {
        final Stock bases = new Stock();
        final Stock extras = new Stock();
        for (int j = 0; j < list.size(); j++) {
            bases.add(list.get(j).resource(), baseAmounts[j]);
            extras.add(list.get(j).resource(), list.get(j).amount() - baseAmounts[j]);
        }
        return new Split(runs, bases, extras);
    }

    /** Does this item come out of this recipe (product or scrap)? */
    public boolean isResult(final ItemStack stack) {
        for (final ItemStack result : results) {
            if (ItemStack.isSameItem(stack, result)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ intermediate state

    /** Global step (0..totalSteps) the item is at. A base item hasn't started yet: 0. */
    public int stepOf(final ItemStack stack) {
        final SequencedAssemblyRecipe.SequencedAssembly data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (data == null || !data.id().equals(id)) {
            return 0;
        }
        return data.step();
    }

    public boolean isIntermediate(final ItemStack stack) {
        final SequencedAssemblyRecipe.SequencedAssembly data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        return data != null && data.id().equals(id) && stack.is(transitional.getItem());
    }

    public Step nextStep(final ItemStack stack) {
        return steps.get(stepOf(stack) % steps.size());
    }

    public boolean isMainOutput(final ItemStack stack) {
        return ItemStack.isSameItem(stack, mainOutput) && !stack.has(AllDataComponents.SEQUENCED_ASSEMBLY);
    }

    // ------------------------------------------------------------------ parsing

    private static java.util.function.Predicate<ResourceKey> itemPredicate(final Ingredient ingredient) {
        return key -> key instanceof ItemResource ir && ingredient.test(ir.toItemStack(1));
    }

    @Nullable
    public static AssemblyRecipeInfo byId(final Level level, final ResourceLocation id) {
        final Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(id);
        if (holder.isEmpty()) {
            return null;
        }
        if (holder.get().value() instanceof SequencedAssemblyRecipe recipe) {
            return read(id, recipe);
        }
        if (holder.get().value() instanceof ProcessingRecipe<?, ?> recipe) {
            return readSingle(id, recipe);
        }
        return null;
    }

    /**
     * Is this a single-step recipe (press, deployer or spout on a depot)? It has no transitional item:
     * whatever comes out of the machine is the result.
     */
    public boolean isSingleStep() {
        return transitional.isEmpty();
    }

    /** Single-step recipes done on a depot: press (sheets), deployer and spout. */
    public static List<AssemblyRecipeInfo> allSingleStep(final Level level) {
        final RecipeManager manager = level.getRecipeManager();
        if (manager == cachedSingleManager) {
            return cachedSingle;
        }
        final List<AssemblyRecipeInfo> list = new ArrayList<>();
        for (final RecipeType<?> type : List.of(AllRecipeTypes.PRESSING.getType(), AllRecipeTypes.DEPLOYING.getType(),
            AllRecipeTypes.FILLING.getType())) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            final List<RecipeHolder<?>> holders = (List) manager.getAllRecipesFor((RecipeType) type);
            for (final RecipeHolder<?> holder : holders) {
                if (holder.value() instanceof ProcessingRecipe<?, ?> recipe) {
                    final AssemblyRecipeInfo info = readSingle(holder.id(), recipe);
                    if (info != null) {
                        list.add(info);
                    }
                }
            }
        }
        list.sort(Comparator.comparing(i -> i.id().toString()));
        cachedSingleManager = manager;
        cachedSingle = List.copyOf(list);
        return cachedSingle;
    }

    @Nullable
    private static RecipeManager cachedSingleManager;
    private static List<AssemblyRecipeInfo> cachedSingle = List.of();

    @Nullable
    private static AssemblyRecipeInfo readSingle(final ResourceLocation id, final ProcessingRecipe<?, ?> recipe) {
        if (recipe.getIngredients().isEmpty() || recipe.getRollableResults().isEmpty()) {
            return null;
        }
        final RecipeType<?> type = recipe.getType();
        final Step step;
        if (type == AllRecipeTypes.DEPLOYING.getType() && recipe instanceof ItemApplicationRecipe deploying) {
            step = new Step(MachineType.DEPLOYING, deploying.getRequiredHeldItem(), deploying.shouldKeepHeldItem(), null);
        } else if (type == AllRecipeTypes.PRESSING.getType()) {
            step = new Step(MachineType.PRESSING, null, false, null);
        } else if (type == AllRecipeTypes.FILLING.getType() && recipe instanceof FillingRecipe filling) {
            step = new Step(MachineType.FILLING, null, false, filling.getRequiredFluid());
        } else {
            return null;
        }
        final List<ItemStack> results = new ArrayList<>();
        for (final ProcessingOutput result : recipe.getRollableResults()) {
            results.add(result.getStack().copy());
        }
        // No transitional item (EMPTY): that's how it is told apart from a sequenced recipe.
        return new AssemblyRecipeInfo(id, recipe.getIngredients().getFirst(), List.of(step), 1, ItemStack.EMPTY,
            results.getFirst().copy(), null, List.copyOf(results));
    }

    @SuppressWarnings("unchecked")
    public static List<AssemblyRecipeInfo> all(final Level level) {
        final RecipeManager manager = level.getRecipeManager();
        if (manager == cachedManager) {
            return cached;
        }
        final RecipeType<SequencedAssemblyRecipe> type =
            (RecipeType<SequencedAssemblyRecipe>) (RecipeType<?>) AllRecipeTypes.SEQUENCED_ASSEMBLY.getType();
        final List<AssemblyRecipeInfo> list = new ArrayList<>();
        for (final RecipeHolder<SequencedAssemblyRecipe> holder : manager.getAllRecipesFor(type)) {
            list.add(read(holder.id(), holder.value()));
        }
        list.sort(Comparator.comparing(i -> i.id().toString()));
        cachedManager = manager;
        cached = List.copyOf(list);
        return cached;
    }

    @Nullable
    private static RecipeManager cachedManager;
    private static List<AssemblyRecipeInfo> cached = List.of();

    private static AssemblyRecipeInfo read(final ResourceLocation id, final SequencedAssemblyRecipe recipe) {
        final List<Step> steps = new ArrayList<>();
        String unsupported = null;
        for (final SequencedRecipe<?> sequenced : recipe.getSequence()) {
            final ProcessingRecipe<?, ?> step = sequenced.getRecipe();
            final RecipeType<?> type = step.getType();
            if (type == AllRecipeTypes.DEPLOYING.getType() && step instanceof ItemApplicationRecipe deploying) {
                steps.add(new Step(MachineType.DEPLOYING, deploying.getRequiredHeldItem(), deploying.shouldKeepHeldItem(), null));
            } else if (type == AllRecipeTypes.PRESSING.getType()) {
                steps.add(new Step(MachineType.PRESSING, null, false, null));
            } else if (type == AllRecipeTypes.FILLING.getType() && step instanceof FillingRecipe filling) {
                steps.add(new Step(MachineType.FILLING, null, false, filling.getRequiredFluid()));
            } else if (unsupported == null) {
                unsupported = typeName(step);
            }
        }
        ItemStack output = ItemStack.EMPTY;
        if (!recipe.resultPool.isEmpty()) {
            final ProcessingOutput first = recipe.resultPool.getFirst();
            output = first.getStack().copy();
        }
        if (steps.isEmpty() && unsupported == null) {
            unsupported = "(no steps)";
        }
        final List<ItemStack> results = new ArrayList<>();
        for (final ProcessingOutput result : recipe.resultPool) {
            results.add(result.getStack().copy());
        }
        return new AssemblyRecipeInfo(id, recipe.getIngredient(), List.copyOf(steps), Math.max(1, recipe.getLoops()),
            recipe.getTransitionalItem().copy(), output, unsupported, List.copyOf(results));
    }

    private static String typeName(final Recipe<?> recipe) {
        final ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return key == null ? recipe.getType().toString() : key.toString();
    }

    // ------------------------------------------------------------------ full pattern (JEI)

    /**
     * What the full recipe consumes (all loops), grouped for an RS pattern: each entry is a list of
     * alternatives with its amount. Used by the JEI "+" button in the Pattern Grid.
     */
    public List<List<ResourceAmount>> fullKitForPattern() {
        final java.util.Map<String, KitEntry> grouped = new java.util.LinkedHashMap<>();
        addItems(grouped, base, 1);
        for (final AssemblyRecipeInfo.Step step : steps) {
            if (step.type() == MachineType.DEPLOYING && step.heldItem() != null) {
                addItems(grouped, step.heldItem(), step.keepHeldItem() ? 1 : loops);
            } else if (step.type() == MachineType.FILLING && step.fluid() != null) {
                addFluid(grouped, step.fluid(), (long) step.fluid().amount() * loops);
            }
        }
        final List<List<ResourceAmount>> result = new ArrayList<>();
        for (final KitEntry entry : grouped.values()) {
            final List<ResourceAmount> alternatives = new ArrayList<>();
            for (final Object resource : entry.alternatives) {
                if (resource instanceof ItemResource item) {
                    alternatives.add(new ResourceAmount(item, entry.amount));
                } else if (resource instanceof FluidResource fluid) {
                    alternatives.add(new ResourceAmount(fluid, entry.amount));
                }
            }
            if (!alternatives.isEmpty()) {
                result.add(alternatives);
            }
        }
        return result;
    }

    private static final class KitEntry {
        final List<Object> alternatives = new ArrayList<>();
        long amount;
    }

    private static void addItems(final java.util.Map<String, KitEntry> grouped, final Ingredient ingredient, final long amount) {
        final ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) {
            return;
        }
        final StringBuilder key = new StringBuilder("item:");
        for (final ItemStack stack : stacks) {
            key.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(',');
        }
        final KitEntry entry = grouped.computeIfAbsent(key.toString(), k -> {
            final KitEntry created = new KitEntry();
            for (final ItemStack stack : stacks) {
                final ItemResource resource = ItemResource.ofItemStack(stack);
                if (!created.alternatives.contains(resource)) {
                    created.alternatives.add(resource);
                }
            }
            return created;
        });
        entry.amount += amount;
    }

    private static void addFluid(final java.util.Map<String, KitEntry> grouped, final SizedFluidIngredient fluid, final long amount) {
        final net.neoforged.neoforge.fluids.FluidStack[] stacks = fluid.getFluids();
        if (stacks.length == 0) {
            return;
        }
        final StringBuilder key = new StringBuilder("fluid:");
        for (final net.neoforged.neoforge.fluids.FluidStack stack : stacks) {
            key.append(BuiltInRegistries.FLUID.getKey(stack.getFluid())).append(',');
        }
        final KitEntry entry = grouped.computeIfAbsent(key.toString(), k -> {
            final KitEntry created = new KitEntry();
            for (final net.neoforged.neoforge.fluids.FluidStack stack : stacks) {
                final FluidResource resource = Stock.ofFluidStack(stack);
                if (!created.alternatives.contains(resource)) {
                    created.alternatives.add(resource);
                }
            }
            return created;
        });
        entry.amount += amount;
    }
}
