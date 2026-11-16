package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import dev.rscreatebridge.common.Stock;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A Create recipe the Assembly Controller can make. It answers three questions:
 * <ol>
 *   <li>do these resources from RS match me, and how are they split into jobs? ({@link #match})</li>
 *   <li>which machines do I need? ({@link #machines})</li>
 *   <li>what is the next operation for this job? ({@link #next})</li>
 * </ol>
 * Adding support for a new kind of Create recipe means adding an implementation (and, for a new machine, a
 * {@link MachineType} and its station handling in {@link StationOps}).
 */
public interface ProcessRecipe {
    /**
     * How the resources delivered by RS turn into jobs.
     *
     * @param starts one entry per job: what that job starts from (base item or basin kit)
     * @param extras the rest (step ingredients), kept by the controller to load the machines
     */
    record Match(List<Stock> starts, Stock extras) {
    }

    ResourceLocation id();

    Job.Kind kind();

    /** Every machine this recipe needs, in the order the recipe uses them. */
    Set<MachineType> machines();

    /** Heat needed under the basin (only basin recipes need any). */
    default HeatCondition heat() {
        return HeatCondition.NONE;
    }

    /** How these resources split into jobs, or null if they are not exactly what this recipe needs. */
    @Nullable
    Match match(Collection<ResourceAmount> resources);

    /** The next operation for this job, or null if the recipe can't continue. */
    @Nullable
    Operation next(Job job);

    /** The product's name, for status messages. */
    Component productName();
}
