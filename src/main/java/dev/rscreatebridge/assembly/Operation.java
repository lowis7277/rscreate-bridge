package dev.rscreatebridge.assembly;

import com.simibubi.create.content.processing.recipe.HeatCondition;

import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.Nullable;

/**
 * One thing to do at one station: which machine does it, what that machine has to be loaded with, and the
 * heat it needs. Every recipe the controller makes is a list of operations: a single-step or basin recipe is
 * one, a sequenced assembly is one per step.
 *
 * @param machine  the machine that performs it
 * @param heldItem for a deployer: what it has to hold
 * @param fluid    for a spout: the fluid (and amount) it has to contain
 * @param heat     for a basin: the heat needed under it
 */
public record Operation(MachineType machine,
                        @Nullable Ingredient heldItem,
                        @Nullable SizedFluidIngredient fluid,
                        HeatCondition heat) {

    static Operation of(final MachineType machine) {
        return new Operation(machine, null, null, HeatCondition.NONE);
    }
}
