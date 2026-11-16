package dev.rscreatebridge.compat.jei;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import dev.rscreatebridge.RSCreateBridge;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI plugin (optional: only loaded when JEI is installed). Registers the RS Pattern Grid "+" for
 * sequenced assembly recipes with the amounts of the full recipe.
 */
@JeiPlugin
public final class RSCBJeiPlugin implements IModPlugin {
    /** Create's category (same id and class Create registers in its JEI plugin). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final RecipeType<RecipeHolder<SequencedAssemblyRecipe>> SEQUENCED_ASSEMBLY =
        RecipeType.createRecipeHolderType(ResourceLocation.fromNamespaceAndPath("create", "sequenced_assembly"));

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RSCreateBridge.MOD_ID, "jei");
    }

    @Override
    public void registerRecipeTransferHandlers(final IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new SequencedAssemblyPatternTransfer(), SEQUENCED_ASSEMBLY);
    }
}
