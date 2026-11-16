package dev.rscreatebridge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config (per world: saves/&lt;world&gt;/serverconfig/rscreatebridge-server.toml).
 * Only whoever has access to the server files can change it. There are no commands.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_RETRIES;
    public static final ModConfigSpec.IntValue STEP_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue PIECE_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue MAX_QUEUED_JOBS;
    public static final ModConfigSpec.IntValue CRAFTER_INTERFACE_ENERGY;
    public static final ModConfigSpec.IntValue ASSEMBLY_CONTROLLER_ENERGY;
    public static final ModConfigSpec.IntValue ASSEMBLY_PORT_ENERGY;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("assembly");
        MAX_RETRIES = b
            .comment("How many times a sequenced assembly that produced scrap (or lost its intermediate) is retried before giving up.",
                "Each retry takes a new base item from the network.")
            .defineInRange("maxRetries", 3, 0, 64);
        PIECE_TIMEOUT_SECONDS = b
            .comment("Seconds a piece may sit at a station without the machine processing it.",
                "After that the controller takes it back and tries another station of the same type.")
            .defineInRange("pieceTimeoutSeconds", 30, 5, 3600);
        MAX_QUEUED_JOBS = b
            .comment("Maximum queued pieces per controller. When the queue is full, RS waits.")
            .defineInRange("maxQueuedJobs", 16, 1, 256);
        b.pop();
        b.push("crafters");
        STEP_TIMEOUT_SECONDS = b
            .comment("Seconds with the crafter array stopped and a craft pending before showing a warning.")
            .defineInRange("stepTimeoutSeconds", 60, 5, 3600);
        b.pop();
        b.push("energy");
        CRAFTER_INTERFACE_ENERGY = b
            .comment("RS energy usage per tick of the Crafter Interface.")
            .defineInRange("crafterInterface", 4, 0, 10000);
        ASSEMBLY_CONTROLLER_ENERGY = b
            .comment("RS energy usage per tick of the Assembly Controller.")
            .defineInRange("assemblyController", 8, 0, 10000);
        ASSEMBLY_PORT_ENERGY = b
            .comment("RS energy usage per tick of each Assembly Port.")
            .defineInRange("assemblyPort", 2, 0, 10000);
        b.pop();
        SPEC = b.build();
    }

    private Config() {
    }
}
