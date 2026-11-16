package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Every recipe the Assembly Controller can make, in priority order: sequenced assembly, single-step depot
 * recipes, basin recipes.
 * <p>
 * Finding which recipe a set of resources belongs to means trying all of them, and RS offers the same
 * resources again and again while a request runs. The result is cached per resource set (until recipes are
 * reloaded), so the search only happens once.
 */
public final class Recipes {
    /** A recipe that matches some resources, with how they split into jobs. */
    public record Candidate(ProcessRecipe recipe, ProcessRecipe.Match match) {
    }

    private static final int CACHE_SIZE = 256;

    @Nullable
    private static RecipeManager cachedManager;
    private static List<ProcessRecipe> all = List.of();
    private static Map<ResourceLocation, ProcessRecipe> byId = Map.of();
    private static final Map<Map<ResourceKey, Long>, List<Candidate>> MATCHES =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Map<ResourceKey, Long>, List<Candidate>> eldest) {
                return size() > CACHE_SIZE;
            }
        };

    private Recipes() {
    }

    private static void refresh(final Level level) {
        final RecipeManager manager = level.getRecipeManager();
        if (manager == cachedManager) {
            return;
        }
        final List<ProcessRecipe> list = new ArrayList<>();
        list.addAll(AssemblyRecipeInfo.all(level));
        list.addAll(AssemblyRecipeInfo.allSingleStep(level));
        list.addAll(BasinRecipeInfo.all(level));
        final Map<ResourceLocation, ProcessRecipe> ids = new HashMap<>();
        for (final ProcessRecipe recipe : list) {
            ids.putIfAbsent(recipe.id(), recipe);
        }
        all = List.copyOf(list);
        byId = Map.copyOf(ids);
        MATCHES.clear();
        cachedManager = manager;
    }

    public static List<ProcessRecipe> all(final Level level) {
        refresh(level);
        return all;
    }

    @Nullable
    public static ProcessRecipe byId(final Level level, final ResourceLocation id) {
        refresh(level);
        return byId.get(id);
    }

    /** Every recipe these resources match, in priority order (cached). */
    public static List<Candidate> matches(final Level level, final Collection<ResourceAmount> resources) {
        refresh(level);
        final Map<ResourceKey, Long> key = new HashMap<>();
        for (final ResourceAmount r : resources) {
            key.merge(r.resource(), r.amount(), Long::sum);
        }
        final List<Candidate> cached = MATCHES.get(key);
        if (cached != null) {
            return cached;
        }
        final List<Candidate> found = new ArrayList<>();
        for (final ProcessRecipe recipe : all) {
            final ProcessRecipe.Match match = recipe.match(resources);
            if (match != null) {
                found.add(new Candidate(recipe, match));
            }
        }
        final List<Candidate> result = List.copyOf(found);
        MATCHES.put(Map.copyOf(key), result);
        return result;
    }
}
