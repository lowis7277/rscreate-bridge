package dev.rscreatebridge.testkit;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Test mod (only for development, never for a server): the OP command {@code /rscbtest demo|clear} and
 * the GameTests.
 */
@Mod(RSCBTestKit.MOD_ID)
public final class RSCBTestKit {
    public static final String MOD_ID = "rscbtestkit";

    public RSCBTestKit(final IEventBus modBus) {
        modBus.addListener(RegisterGameTestsEvent.class, e -> {
            e.register(CrafterGameTests.class);
            e.register(EndToEndGameTests.class);
            e.register(AssemblyGameTests.class);
            e.register(BasinGameTests.class);
            e.register(DemoGameTests.class);
            e.register(CableTiersGameTests.class); // its tests pass right away without Cable Tiers
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> registerCommands(e.getDispatcher()));
    }

    private static void registerCommands(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rscbtest")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("demo").executes(ctx -> {
                final CommandSourceStack source = ctx.getSource();
                final ServerLevel level = source.getLevel();
                final BlockPos origin = DemoBuilder.originFrom(source.getPosition());
                DemoBuilder.build(level, origin);
                source.sendSuccess(() -> Component.literal("[RS Create Bridge] Demo placed at " + origin.toShortString()), true);
                return 1;
            }))
            .then(Commands.literal("clear").executes(ctx -> {
                final CommandSourceStack source = ctx.getSource();
                DemoBuilder.clear(source.getLevel(), DemoBuilder.originFrom(source.getPosition()));
                source.sendSuccess(() -> Component.literal("[RS Create Bridge] Demo removed"), true);
                return 1;
            })));
    }
}
