# GUI and Command Information Architecture

## Research inputs

- Paper recommends custom `InventoryHolder` instances instead of matching
  inventory titles. `Menu` already follows this and `GuiManager` blocks
  click/drag movement by default:
  <https://docs.papermc.io/paper/dev/custom-inventory-holder/>
- Community GUI libraries treat content, static navigation, default click
  cancellation and pagination as separate concerns:
  <https://triumphteam.dev/docs/triumph-gui/pagegui>
  and <https://triumphteam.dev/docs/triumph-gui/features>
- Paper's command guidance models subcommands as a discoverable tree with
  permission requirements and argument suggestions:
  <https://docs.papermc.io/paper/dev/command-api/basics/introduction/>

## Player flow

`/idle -> one recommended action -> domain screen -> detail -> confirm`

The Hub is ordered by the actual play loop:

1. Territory Map
2. Nodes
3. Warehouse
4. Workers
5. Chronicle
6. Social, leaderboard, shop and expedition

The recommendation card has this strict priority:

1. No production node: open Territory Map.
2. Buffer full: open Nodes.
3. Exploration available/completed: open Nodes.
4. No Focused Node: open Chronicle.
5. Otherwise: open the Focused Node.

Every menu should use the final row only for navigation. Back/close occupies
the middle slot, while previous/next occupy the outside slots on paginated
screens. Destructive actions remain behind `ConfirmMenu`.

## Admin flow

`/idle admin -> inspect -> preview/help -> mutation with reason -> audit`

Admin Hub groups work by intent:

- Inspection: current Node, player claims and audit.
- Content: pools, validation, schematics and NPC presentation.
- Operations: events, Node state and maintenance.
- Economy: Coins, Credits and Node caps.
- Danger: irreversible operations require a reason and confirmation screen.

Scoped permissions:

- `idlefarm.admin.operations`
- `idlefarm.admin.content`
- `idlefarm.admin.economy`
- `idlefarm.admin.audit`

`idlefarm.admin` remains the compatibility parent and grants every scope.

### Full-control Admin UI

Admins do not need to type admin commands for normal operations:

- Player picker: online list plus offline-name search.
- Player control: claims, Coins, Credits, Node cap, item grants and filtered
  audit history.
- Claims: inspect and teleport directly to a Node.
- Node control: events, Exploration level, NPC status/refresh/state,
  schematic rebuild and force-unclaim.
- Content control: pool editor, validation report, rollback, reload and the
  position-sensitive schematic authoring workflow.
- System control: seven-day metrics and paginated audit history.

Text input is limited to values that do not fit inventory slots well (player
name, amount, material, IDs and audit reason). The admin never needs to
construct a `/idle admin ...` command. Mutations reuse the existing
permission-checked, audited action handlers internally.

## Command rules

- Bare `/idle` and `/idle admin` open their respective hubs for players.
- `/idle help <category|command>` and `/idle admin help <category|command>`
  are the authoritative fallback documentation.
- `CommandCatalog` is the shared source for help, category names, aliases,
  permission-aware tab completion and future GUI command hints.
- Commands that mutate admin data require a reason where the service already
  supports auditing.
- GUI actions and typed commands route to the same existing handlers/services;
  the refactor does not duplicate economy or game logic.
