---
navigation:
  title: Overloaded IO Port
  icon: ae2lt:overloaded_io_port
  parent: overloaded-network/overloaded-network-index.md
item_ids:
  - ae2lt:overloaded_io_port
---

# Overloaded IO Port

<BlockImage id="ae2lt:overloaded_io_port" scale="4" />

Transfers resources between storage cells and the connected ME network. Six input and six output slots use the familiar AE2 IO Port controls. Requires a channel and network power.

## Bulk transfer

A batch moves one resource type up to the amount that the source can extract and the destination can accept. The port has no additional item or fluid quantity throttle. Native storage calls use `long` quantities; an individual call cannot exceed 9,223,372,036,854,775,807 native units. Larger inventories continue in later batches.

| Acceleration cards | Interval per type |
| --- | ---: |
| 0 | 5 ticks |
| 1 | 4 ticks |
| 2 | 3 ticks |
| 3 | 2 ticks |
| 4 | 1 tick |

At most one type is attempted per interval, shared by all six cells in rotation. Rejected types also count, and unfinished scans continue at the next processing opportunity. Repeated alerts cannot bypass the interval, and downtime does not accumulate catch-up batches. A million identical items can move in one batch; a million different types still need many batches. The maximum rate assumes an active network and free destination space. Blocked ports retry less often.

Each batch admitted for extraction costs **32 AE**, independent of quantity, plus **4 AE/t** idle power. A destination that changes its mind after simulation may still consume that batch's energy. If the network cannot pay, the batch waits. Power needed for the network's next idle payment is reserved.

## Controls and automation

* **Empty** sends cell contents into the network; **Fill** draws from the network into the cell.
* Eject cells when **empty**, **full**, or after a complete scan finds **no transferable resources**, matching AE2's three fullness settings. Exhausting this tick's work budget does not finish a cell.
* A redstone card enables ignore/high/low signal control. It uses one of the five upgrade slots, leaving room for four acceleration cards at the maximum rate of one type per tick.
* Insert cells through the port's local top/bottom; extract completed cells through the other four sides. Rotate the block to change these directions. The input is restricted to storage cells.
* When output slots are full, completed cells wait in the input slots. Players can remove cells manually.

Items, fluids and LT lightning use their native storage keys. Other cell types work through AE2's storage-cell API, subject to that implementation's permissions and capacity.

## Recovery

If a storage rejects resources after accepting simulation, the port first returns the remainder to its source. If that also fails, it retains the remainder and pauses new work until it can return it to the ME network. Saving and dismantling preserve these resources in the port. Memory cards only copy settings.

Obtain the port through the Lightning Assembly Chamber; JEI displays the recipe.
