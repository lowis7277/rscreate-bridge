# Changelog

## 1.0.0
First public release, for NeoForge 1.21.1, Create 6.0.10+ and Refined Storage 2.0.9+.

- **Crafter Interface**: the RS Autocrafter runs Mechanical Crafter arrays. The interface finds the recipe, fills the
  crafters and sends the result to the network; a craft broken halfway is redone once the array is complete.
- **Assembly Controller + Assembly Ports**: the RS Autocrafter runs Create stations (a depot or basin with a machine
  above it):
  - sequenced assembly, with every loop and retries on scrap;
  - single-step press, deployer (including item application recipes such as casings) and spout recipes;
  - mixer and basin press recipes, with blaze burner heat and fluid results.
- Several ports of the same machine process several pieces at once.
- Status on right click and with Create Goggles: network, stations, work in progress and problems.
- Ponder scenes for the three blocks (hold W).
- JEI: the **+** button builds full-recipe patterns for sequenced assembly. Optional.
- Works with Cable Tiers autocrafters.
- English and Spanish.
