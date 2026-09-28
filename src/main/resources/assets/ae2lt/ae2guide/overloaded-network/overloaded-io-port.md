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

Each attempt processes one resource type. The base budget is **1 attempt per round**. The port accepts up to **16 Lightning Collapse Matrices**. Each of the first 15 adds **1 attempt**, reaching **16 attempts per round**. The 16th matrix does not add another attempt. All six input cells share this budget in rotation. Output slots only receive completed cells.

Matrices also raise the per-attempt amount cap: **32,768 native units** initially, multiplied by **8 per matrix**. Items use item counts; fluids and lightning use their native storage units. The 16th matrix raises the amount cap to `Long.MAX_VALUE` (9,223,372,036,854,775,807), while the attempt budget remains 16. Source availability and destination capacity can reduce the actual amount moved.

| Matrices | Maximum attempts per round | Per-type amount cap |
| --- | ---: | ---: |
| 0 | 1 | 32,768 |
| 1 | 2 | 262,144 |
| 2 | 3 | 2,097,152 |
| 15 | 16 | 1,152,921,504,606,846,976 |
| 16 | 16 | Long.MAX_VALUE |

Install matrices in the right slot below the arrow and a filter component in the left slot. Acceleration cards independently shorten the processing interval:

| Acceleration cards | Interval per round |
| --- | ---: |
| 0 | 5 ticks |
| 1 | 4 ticks |
| 2 | 3 ticks |
| 3 | 2 ticks |
| 4 | 1 tick |

Rejected types also spend attempts. Unfinished scans continue in the next round. A cell does not repeat already-scanned types just to spend unused budget, so actual attempts can be below the limit. Resources above the amount cap stay in the source for later rounds.

Repeated alerts and matrix changes cannot bypass the interval, and downtime does not accumulate catch-up batches. The maximum rate requires an active network and free destination space. Blocked ports retry less often. The attempt budget does not adapt to server processing time.

Each attempt admitted for extraction costs **32 AE**, independent of quantity, plus **4 AE/t** idle power. A full round of 16 paid attempts costs **512 AE**. A destination that changes its mind after simulation may still consume that attempt's energy. If the network cannot pay, extraction waits. Power needed for the network's next idle payment is reserved.

## Controls and automation

* **Empty** sends cell contents into the network; **Fill** draws from the network into the cell.
* Eject cells when **empty**, **full**, or after a complete scan finds **no transferable resources**, matching AE2's three fullness settings. Exhausting this tick's work budget does not finish a cell.
* A redstone card enables ignore/high/low signal control. It uses one of the five upgrade slots, leaving room for four acceleration cards for one round per tick, with up to 16 attempts when at least 15 matrices are installed.
* Insert cells through the port's local top/bottom; extract completed cells through the other four sides. Rotate the block to change these directions. The input is restricted to storage cells.
* When output slots are full, completed cells wait in the input slots. Players can remove cells manually.

Items, fluids and LT lightning use their native storage keys. Other cell types work through AE2's storage-cell API, subject to that implementation's permissions and capacity.

## Filter component

Place an **Overloaded Filter Component** in the left slot below the arrow. Configure its resource list in a Cell Workbench. Both **Fill** and **Empty** only transfer matching resources. A fuzzy card enables fuzzy matching; an inverter card turns the list into a blacklist. An absent or unconfigured component allows all resources.

Excluded resources do not consume transfer attempts or batch power. They remain in their original storage. With a filter installed, empty ejection means the cell contains no permitted resources; the cell may move to the output with excluded resources still inside. Destination rejection does not make it empty while permitted resources remain. Without a component or with an empty list, the entire cell must still be empty.

Full ejection continues to use the cell's overall full status. Work-done ejection still means that a complete scan cannot move any resources, including when the destination temporarily rejects them. Swapping the component applies the new rules immediately without resetting the transfer interval.

## Recovery

If a storage rejects resources after accepting simulation, the port first returns the remainder to its source. If that also fails, it retains the remainder and pauses new work until it can return it to the ME network. Changing the filter does not prevent recovery of resources already extracted. Saving and dismantling preserve these resources in the port. Memory cards only copy settings. Matrices persist in world saves and drop as items when the port is dismantled; memory cards do not copy matrices.

Obtain the port through the Lightning Assembly Chamber; JEI displays the recipe.
