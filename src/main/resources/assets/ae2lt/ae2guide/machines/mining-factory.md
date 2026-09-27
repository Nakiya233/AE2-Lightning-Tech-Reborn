---
navigation:
  title: Mining Factory
  icon: ae2lt:mining_factory
  parent: machines/machines-index.md
item_ids:
  - ae2lt:mining_factory
---

# Mining Factory

<BlockImage id="ae2lt:mining_factory" scale="4" />

Put block items into the upper input slot and a durability tool or an AE2 Annihilation Plane into the lower tool slot. Connect an ME network containing HV Lightning, supply FE and extract the mining drops from the nine output slots. The factory evaluates the blocks' current loot tables with the installed tool; it never places or breaks blocks in the world.

## Processing and power

**Each batch takes exactly 5 ticks (0.25 seconds at 20 TPS).** Loot is rolled and blocks, durability, FE and one HV Lightning are charged only on the fifth tick. Refilling the same block or extracting outputs preserves progress; changing the block type, tool or matrix parallel capacity restarts it. Insufficient power or lightning, invalid inputs or blocked output clears unpaid progress. Unloading and reloading preserves an unfinished cycle.

Parallel capacity follows the **Overload Processing Factory's Lightning Collapse Matrix rules**: no matrix allows one block per batch; by default one matrix enables 8, eight matrices enable 64, and a full stack of 32 enables 256. The matrix slot accepts at most 32. Both factories use `overloadProcessingFactory.parallelPerMatrix`.

Loot sampling has a separate default budget of **8 independent rolls per batch**. A 256-block batch uses eight samples, each representing 32 blocks. Uneven batches distribute the remainder across samples. Small batches use one sample per block. This preserves expected yield for fixed loot conditions, but produces more variation than independently mining every block. Samples are fresh for each batch, with no persistent loot cache. Set `miningFactory.lootSamplesPerTick` at least as high as the installed parallel capacity for independent rolls.

Tools consume one durability per processed block with nonzero hardness; swords consume two. Unbreaking is evaluated for every block. Processing stops when the tool breaks, retaining the unprocessed input. Fortune and Silk Touch apply through loot tables. The default power cost is **256 FE per block**.

An Annihilation Plane uses AE2's diamond-tier pickaxe/axe/shovel/hoe selection and carries its enchantments into the loot context. It is reusable and costs an additional **768 FE per block**, for a default total of **1,024 FE per block**. Insufficient tool tier retains the input.

## Inventory and automation

- Input capacity: 4,096 blocks. Tool capacity: one item. Matrix capacity: 32 matrices.
- Output capacity: nine slots of 4,096 items each.
- Internal buffer: 1,000,000 FE. FE can enter from any side. Applied Flux power can also be supplied through the connected ME network or bound frequency.
- Each completed batch consumes one HV Lightning from the ME network, independent of parallel count. Connect the network by cable or bound frequency. Speed cards are not accepted.
- Pipes may insert blocks, tools and matrices and extract outputs from any side. Installed tools and matrices are excluded from automated extraction and automatic export.
- When output is blocked, processing pauses. Any already-rolled overflow is saved with the machine and is drained before processing more inputs. Removing the machine also drops this pending output.

## Automatic export and configuration

The compact layout, progress arrow and energy bar follow the Overload Processing Factory. Hover the upper-right status icon for the current state, progress, five-tick batch duration, parallel capacity, energy, available network lightning and batch lightning cost.

The left toolbar provides the same **Auto Export** switch and **Configure Output Sides** screen as the Overload Processing Factory. Export is initially disabled, with no output faces selected. Enable it and select one or more faces to send items to adjacent inventories. Faces are relative to the machine's orientation and rotate with it. A full target retains the remainder; replacing a neighboring container refreshes the target.

Sneak-use the factory while holding matrices to install as many as fit. A memory card copies auto-export, output faces, frequency and the desired matrix count. In survival, restoring a matrix count consumes actual matrices from the player's inventory; excess matrices are returned. Inputs, tools and products are not copied by the memory card.

## Compatibility

The factory supports ordinary block items and durable tools supporting Forge digging actions. Blocks with block entities, including containers, are rejected. Energy-only tools need a dedicated cost adapter. Loot-table changes from data packs apply normally; modded loot conditions may require world context that virtual processing cannot provide. Apotheosis stoneforming uses its upstream loot modifier. Forge 1.20.1 Boon of the Earth has a 1% chance per enchantment level to select an item from the apotheosis:boon_drops tag, using the same sample weights. Blood Magic Fortune anointments stack with Fortune enchantments and consume one use per actual processed block. Anointment expiry ends the current batch; remaining input uses the updated tool in the next batch.

These integrations do not post real break/drop events. Tool `mineBlock` callbacks, area mining, chainsaw tree felling, world entity spawning and experience are not simulated.

<RecipeFor id="ae2lt:mining_factory" />
