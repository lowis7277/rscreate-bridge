# RS Create Bridge

A **NeoForge 1.21.1** addon that connects the **Refined Storage 2 Autocrafter** to **Create** machines. The
Autocrafter hands the ingredients to one of this mod's blocks, that block runs real Create machines, and the result
goes back into the network, just like with any other machine.

- **Mechanical Crafters** (crushing wheel, extendo grip, potato cannon…) through the **Crafter Interface**.
- **Sequenced assembly** (precision mechanism, sturdy sheet, tracks), **single-step** press, deployer and spout
  recipes (sheets, etc.) and **basin** recipes (mixer and press: brass, andesite alloy…) through the
  **Assembly Controller** and **Assembly Ports**.

Tested with NeoForge 21.1.265, Refined Storage 2.0.9 and Create 6.0.10. JEI is optional. It also works with the
Cable Tiers autocrafters.

## Blocks

### Crafter Interface
```
[Autocrafter] → [Crafter Interface] ⇄ Mechanical Crafter array
```
- Place it against any crafter of the array. Ideally the head crafter points at the interface, so the result goes
  straight back into the network.
- Use a processing pattern with the ingredients of one craft (the JEI **+** button builds it that way).
- The interface figures out the recipe, puts each item in its crafter and starts the array (no crafter slot covers
  needed).
- If a crafter breaks in the middle of a craft, the craft is redone with materials from the network once the array is
  complete again.

### Assembly Controller and Assembly Ports
```
 [Autocrafter]
      |
 [Controller]--cable--+----------------+----------------+
                      |                |                |
                  [Port]→depot     [Port]→depot     [Port]→basin
                         deployer         press            mixer      (2 blocks above)
```
- **Station**: a Create depot or basin with a machine 2 blocks above it.
  - Over a **depot**: deployer facing down, mechanical press or spout.
  - Over a **basin**: mechanical mixer or press. If the recipe needs heat, put a blaze burner under the basin.
- **Assembly Port**: placed against the depot or basin (place it by clicking the side of that block and it will face
  it) and connected to the network with a cable. The Controller only uses ports on its own network.
- The Autocrafter gives the Controller **everything** the pattern asks for (base item, cogwheels, lava…), so RS shows
  and reserves it. The Controller takes each step to the right machine, loads what it needs (the deployer's held item,
  the spout's lava) and moves the result to the network.
- Only the machines a recipe uses are needed: the precision mechanism, for example, only needs a deployer.
- With several ports of the same type, several pieces are processed at once.
- In the Pattern Grid, the JEI **+** button builds sequenced assembly patterns with the amounts of the full recipe
  (all loops), even though JEI shows a single loop.

Supported sequenced assembly steps: deployer, press and spout. A recipe with any other step (a saw, for example) is
rejected.

### Status and controls
- **Create Goggles** or **right click** with an empty hand: network, ports, pieces in progress and status. Chat is only
  used when a request is given up (out of retries).
- **Shift + right click** on the Controller: abort. Repeat it within 3 seconds to confirm. Everything goes back to the
  network. Only whoever placed the block or an operator.
- **Create wrench**: click rotates the Port or the Controller; shift + click picks it up.
- The Controller and the Ports have no inventory: everything goes through the RS network.

## Recipes
- **Crafter Interface**: 4 brass sheets, 2 precision mechanisms, 2 mechanical crafters and 1 RS interface.
- **Assembly Controller**: 4 brass sheets, 2 precision mechanisms, 1 deployer, 1 RS construction core and
  1 electron tube.
- **Assembly Port**: 4 iron sheets, 2 electron tubes, 1 andesite casing, 1 chute and 1 RS basic processor.

## Configuration
Per world, in `serverconfig/rscreatebridge-server.toml`: retries after scrap, maximum time per station, queue size and
energy usage of each block. The mod has no commands.

## Translations
English and Spanish (Argentina, Spain, Mexico).

## Building
Requires JDK 21. Create, Refined Storage and JEI are downloaded from Maven (the Modrinth release jars), so no extra
setup is needed:
```bash
./gradlew build
```
The jar ends up in `build/libs/`. Both the server and the clients need it (it adds blocks).

Block textures are generated with `python tools/make_textures.py .` (requires Pillow).

## Use of AI tools
AI tools were used during the development of this mod.

## License
MIT.
