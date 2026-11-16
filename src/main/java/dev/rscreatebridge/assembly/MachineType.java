package dev.rscreatebridge.assembly;

/** The Create machine of a station, and so the kind of operation it can do. */
public enum MachineType {
    /** Deployer over a depot (create:deploying). */
    DEPLOYING("deploying"),
    /** Mechanical press over a depot (create:pressing). */
    PRESSING("pressing"),
    /** Spout over a depot (create:filling). */
    FILLING("filling"),
    /** Mechanical mixer over a basin (create:mixing). */
    MIXING("mixing"),
    /** Mechanical press over a basin (create:compacting). */
    COMPACTING("compacting");

    private final String key;

    MachineType(final String key) {
        this.key = key;
    }

    /** Translation key of the machine's name. */
    public String machineKey() {
        return "rscreatebridge.machine." + key;
    }

    /** Does the station use a basin (instead of a depot)? */
    public boolean usesBasin() {
        return this == MIXING || this == COMPACTING;
    }
}
