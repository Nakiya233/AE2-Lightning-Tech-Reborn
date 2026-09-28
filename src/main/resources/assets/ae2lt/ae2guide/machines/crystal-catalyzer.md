---
navigation:
  title: Crystal Catalyzer
  icon: ae2lt:crystal_catalyzer
  parent: machines/machines-index.md
item_ids:
  - ae2lt:crystal_catalyzer
  - ae2lt:pigmee_crystal_catalyzer
---

# Crystal Catalyzer

<Row>
  <BlockImage id="ae2lt:crystal_catalyzer" scale="4" />
  <BlockImage id="ae2lt:pigmee_crystal_catalyzer" scale="4" />
</Row>

The **Crystal Catalyzer** is a specialty processing machine that uses the recipe fluid, FE, lightning from the ME network, and the item in its catalyst slot. It has two operating modes: **Crystal Mode** and **Dust Mode**.

The **Pigmee Crystal Catalyzer** is a simplified variant. Put one full stack (64) of a supported crystal block in its catalyst slot; it keeps the blocks and produces the recipe base output every 5 seconds: one crystal for ordinary crystal block recipes, or eight for AE2CS mother rock recipes. It consumes only 1,000 mB of water, uses no FE at all, and never needs Lightning or a Collapse Matrix.

Both machines share the recipe set, but Pigmee only accepts water recipes. Special-fluid recipes are exclusive to the normal machine. The recipe viewer retains the standard machine's FE, Lightning, quantity and timing data. The Pigmee machine bypasses those energy costs and applies its own fixed stock, duration and base-output rules at runtime; it does not register separate zero-cost recipes.

## Slots and Capacity

| Slot | Capacity | Notes |
|------|----------|-------|
| Catalyst slot | 256 (normal) / 64 (Pigmee) | Holds the item required by the current mode; the item is **not consumed** during processing |
| Matrix slot | 1 | Optional Lightning Collapse Matrix for a yield bonus |
| Output slot | 16,384 normal / 64 Pigmee | Processed output; written by the machine only, no external input accepted |
| Fluid slot | 16,000 mB | Accepts the recipe fluid through pipes or containers; built-in recipes consume 1,000 mB per cycle |
| FE Buffer | 1,000,000 FE | Built-in energy buffer |

## Operating Modes

| Mode | Purpose | Processing Time | Example inputs | Example outputs |
|------|---------|-----------------|----------------|-----------------|
| Crystal Mode | Extract crystals from matching crystal blocks | 1 second | Certus Quartz Block, Fluix Block, Overload Crystal Block | Certus Quartz Crystal, Fluix Crystal, Overload Crystal |
| Dust Mode | Extract crystal dust from matching crystal blocks | 2 seconds | Certus Quartz Block, Fluix Block, Overload Crystal Block | Certus Quartz Dust, Fluix Dust, Overload Crystal Dust |

Both modes share the same catalyst slot, fluid slot, and output slot. The item in the catalyst slot is used for recipe matching and parallel count calculation, but it is not consumed.

## Operating Flow

1. Select Crystal Mode or Dust Mode with the left-side mode button
2. Feed the fluid shown in JEI/EMI into the fluid slot through pipes or containers
3. Put an item matching the selected mode into the catalyst slot
4. Supply FE to the normal machine (the Pigmee variant needs none)
5. Connect the normal machine to an ME network with lightning storage; the Pigmee variant does not need lightning
6. Once a recipe matches, the machine processes automatically
7. Finished output goes into the output slot

## Special Crystal Fluids

These optional integrations run in Crystal Mode, with 100,000 FE and 1 High Voltage Lightning per cycle. The catalyst is retained; each recipe has a base output of one item.

| Crystal | Retained catalyst | Fluid per cycle |
|---------|-------------------|-----------------|
| Oritech Fluxite | Block of Fluxite | 1 B Strange Matter |
| Oritech Uranite Crystal | Uranite Crystal | 1 B Mineral Slurry |
| Just Dire Things Time Crystal | Time Crystal Block | 1 B Time Fluid |

These recipes load only with their corresponding mod. Pigmee cannot run them; Fluxite now requires the normal catalyzer. Existing water recipes retain their costs.

## AE2 Crystal Science Mother Rocks

With an AE2CS version that includes mother rocks, Crystal Mode also supports all 11 families: Nether Quartz, Energized Certus Quartz, Ender Quartz, Energized Fluix, Fluix, Redstone, Resonating, Quantum, Link, Meteor, and Entro.

The mother rock is retained as the catalyst. Products match the corresponding mature clusters: the first ten yield AE2CS purified crystals, while Entro yields the ExtendedAE Entro Crystal and requires ExtendedAE. Each normal cycle uses 1 B water, 100,000 FE and 1 High Voltage Lightning, with a base output of eight items and the usual parallel and matrix bonuses. With a matrix, 256 mother rocks produce 16,384 crystals per second. The normal variant's output slot holds 16,384 items.

Pigmee also accepts these water recipes: 64 matching mother rocks produce eight crystals every 5 seconds for 1 B water, without consuming the rocks. These recipes do not load when AE2CS is absent or its installed version has no mother rocks.

## Lightning Consumption

The normal Crystal Catalyzer consumes lightning from the ME network each time it completes an operation. The type (High Voltage or Extreme High Voltage) and amount of lightning required are defined per recipe. The Pigmee Crystal Catalyzer never consumes lightning.

If the network does not have enough lightning when the operation is ready to complete, the machine will pause and wait until lightning becomes available. No fluid or FE is wasted during this wait.

## Parallel Output and Fluid

Each built-in recipe consumes **1,000 mB of its specified fluid per cycle**. Parallel output and the matrix bonus do not increase this cost. With 256 catalysts and a matrix, a base-output-one recipe produces 2,048 items for the same bucket of fluid.

For the normal machine, parallel count is slot amount / recipe required amount. Recipes with a base output of one item require 1 matching block each, so inserting 64 valid blocks makes the machine calculate 64 parallel outputs per operation. The Pigmee variant requires exactly one full stack (64 blocks) and produces only the recipe base output, without scaling by catalyst count.

The parallel count is locked when processing starts. Adding or removing items from the catalyst slot during processing will not change the already locked output for that operation.

## Lightning Collapse Matrix Bonus

<ItemImage id="ae2lt:lightning_collapse_matrix" scale="2" float="left" />

With a **Lightning Collapse Matrix** installed in the matrix slot, the normal Crystal Catalyzer's per-operation output is increased to **8×**. The matrix is not consumed during processing. The Pigmee variant has no matrix slot effect and always produces only the recipe base output.

Final output = base output × parallel count × matrix multiplier.

## Notes

* The Crystal Catalyzer is powered by **external FE** on its sides, not by AE from the ME network
* Lightning is consumed from the **ME network storage**, so the machine must be connected to a network with lightning available
* The machine itself is also an ME network device — connecting it to the network lets you feed it through AE2 Interfaces or Pattern Providers
* Supports Auto Export; output sides can be configured in the GUI
* The Crystal Catalyzer **does not** support Speed Cards
* Crystal Mode completes in **1 second** minimum; Dust Mode completes in **2 seconds** minimum; Pigmee Crystal Catalyzer cycles take **5 seconds** (no FE required)
