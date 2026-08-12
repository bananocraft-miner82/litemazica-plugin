# Manual test script

Covers the things unit tests can't: real block placement, rotation, scheduling
and persistence on a live server. Run top to bottom on a throwaway world.

## Setup

1. `./gradlew build`, copy `bukkit/build/libs/Litemazica.jar` to `plugins/`.
2. Start a Paper 1.20.1 server, then stop it (generates `plugins/Litemazica/config.yml`).
3. Set `api-base-url` to your deployment (or `http://localhost:8787` with
   `npm run preview` in the app repo). Start the server.
4. Generate a share code in the Litemazica app. Keep a **small** one (~20×20,
   1 level) for most tests and one **large** one (~80×80) for the TPS check.
5. Use a superflat creative world — placement is much easier to eyeball.

Below, `<code>` is your small share code.

## Checklist

| # | Step | Expected |
| --- | --- | --- |
| 1 | Server start | Log shows the plugin enabling and the API base URL. No stack traces. |
| 2 | `/litemazica` (no args) | Help/usage listing the subcommands (including `place` and `files`). |
| 3 | As a non-op: `/litemazica generate <code>` | Refused, permission message. No blocks placed. |
| 4 | `/litemazica generate` (no code) | Usage message, not an error. |
| 4a | Tab-complete `/litemazica ` | Lists subcommands, filtered by your permissions (a non-op sees only `start`). |
| 4b | Tab-complete `/litemazica generate ` | Offers `<litemazica-code>` — **not a blank prompt**. |
| 4c | Tab-complete `/litemazica generate <code> ` | Offers your current X coordinate and `<name>`. |
| 4d | Tab through `/litemazica generate <code> <x> <y> ` | Offers your current Y then Z — tabbing through fills in where you're standing. |
| 4e | Tab-complete `/litemazica generate <code> 100 64 100 ` | Offers `<name>`. |
| 4f | Tab-complete `/litemazica start ` with no mazes placed | Offers `<maze-id>` rather than nothing. |
| 5 | `/litemazica generate not-a-real-code` | Clean "could not decode" style error. Server stays up. |
| 6 | Stand on flat ground facing **south**, `/litemazica generate <code>` | Maze builds; **entrance opening is at your feet**; body extends **south** (away from you). You can walk straight in. |
| 7 | Look at the floor you're standing on | The maze floor is level with the ground you were standing on — no 1-block step up/down at the entrance. |
| 8 | Walk the maze | Walls/floor intact, no floating or missing blocks, torches/lanterns attached correctly (not popped off). |
| 9 | `/litemazica remove <id>`, then repeat step 6 facing **east**, **north**, **west** | Each time the maze extends in the direction you face, entrance still at your feet. Directional blocks (stairs, ladders, wall torches, chests) stay correctly oriented — **this is the rotation check**. |
| 10 | If the code has loot chests: open one | Loot generates on first open (dungeon/whatever table the code specifies). |
| 10b | Generate a maze with **dispenser traps**, then break/open several trap dispensers | Each is **stocked with the exact ammunition the web-app editor baked in** (arrows and/or splash/lingering potions) — the plugin applies the schematic's own `Items`, not a fresh roll. Trigger one and it fires. After `regen`, each dispenser holds the **same** load again (faithful to the schematic, not re-rolled). |
| 10c | Generate a maze with **trapped-chest bait** wired to TNT/dispensers, place it several times (`regen`) and open the bait chest each time | Some resets it's a live **trapped chest** (opening springs the wired trap); others it's a plain **chest** (safe). Facing and loot are the same either way. It shouldn't be the same outcome every reset. |
| 10d | Generate a maze with a **spawner room** (dark room, spawner in the middle), stand in it; `regen` and repeat | The spawner spews the **mob the web-app editor baked in** (the plugin applies the schematic's `SpawnData`, not a fresh pick). After `regen` it's the **same** mob again — faithful to the schematic. |
| 10e | Generate a maze with a **heavy (iron) weighted pressure-plate field** over hidden dispensers/TNT; note which tiles are plated, then `regen` | About half the field is cleared to air (safe) and the live tiles shuffle between resets. Decorative plates of any other type are left untouched. |
| 11 | `/litemazica list` | Shows the maze: id, world, region size, regen setting (`off` initially). |
| 12 | `/litemazica generate <code> 100 64 100` | Builds at those coords instead of at you. |
| 13 | Try to generate a maze overlapping an existing one | **Refused** with a message naming the maze it would hit. Nothing placed. |
| 13b | Have a second player stand ~10 blocks in front of you, then `/litemazica generate <code>` | **Refused**, naming that player. Nothing placed — they aren't buried. |
| 13c | Same again once they walk away | Succeeds, even though *you* are standing in the region (you're in the entrance). |
| 13d | Stand somewhere, then `/litemazica generate <code> <x> <y> <z>` with coords putting the maze on top of yourself | **Refused**, naming you — the exemption only applies when building at your feet. |
| 14 | Generate the **large** code, watch `/tps` while it builds | TPS stays healthy (placement is batched per tick); may take several seconds. |
| N1 | `/litemazica generate <code> arena` | Builds as `arena`; `/litemazica list` shows that id rather than `m1`. |
| N2 | `/litemazica generate <code> 100 64 100 arena2` | Name works alongside explicit coordinates. |
| N3 | `/litemazica start ARENA` / `remove Arena` | Case-insensitive — finds `arena`. Messages use the name as originally typed. |
| N4 | `/litemazica generate <code> arena` again | Refused, name already taken — **and refused instantly**, before any API call. |
| N5 | `/litemazica generate <code> my maze` | Refused: spaces aren't allowed. Try `maze.dat`, `a/b` too. |
| N6 | `/litemazica generate <code> 100` | Refused: an all-digit name would read as coordinates. |
| N7 | `/litemazica generate <code> 100 64` | Refused: coordinates need all three. |
| N8 | Name a maze, remove it, check `plugins/Litemazica/snapshots/` | Snapshot is a folder named `arena/` and is deleted (folder and all) on removal. |
| T1 | Walk far away, then `/litemazica start` (one maze placed) | Teleported to the entrance, standing on the threshold, **facing into** the maze — walk forward and you're in. |
| T2 | As a **non-op** player: `/litemazica start` | **Allowed** — `litemazica.start` defaults to everyone, unlike the admin commands. |
| T3 | As a non-op: `/litemazica generate <code>` | Still refused — the two permissions are independent. |
| T4 | Place a second maze, then `/litemazica start` with no id | Lists the ids and asks which one. |
| T5 | `/litemazica start <id>` / tab-complete after `start` | Teleports to that maze; tab completion offers the placed ids. |
| T6 | `/litemazica start nosuchmaze` | Clean "no placed maze with id" message. |
| T7 | From console: `litemazica start m1` | Refused — only a player can be teleported. |
| T8 | `/litemazica regen <id> now`, then immediately `/litemazica start <id>` | Refused while it's resetting; works again once finished. |
| T9 | `/litemazica start`, stay inside, wait past a due scheduled reset | Reset defers while you're in there — being teleported in counts as being inside. |
| T10 | Try the aliases: `/litemaze list`, `/lmz list`, `/maze list` | All work identically. |
| 15 | `/litemazica remove <id>` while standing outside it | Maze goes, **original terrain comes back** (see the snapshot section below). |
| 16 | Generate again, stand **inside**, `/litemazica remove <id>` | **Refused**, asks for confirmation. |
| 17 | `/litemazica remove <id> confirm` while inside | Removes it anyway. |

## Local schematics (`/litemazica place`)

Export a small maze (or any structure) from Litematica into
`plugins/Litemazica/schematics/` — e.g. `test.litematic`. Confirm the folder was
created automatically on first start.

| # | Step | Expected |
| --- | --- | --- |
| L1 | `/litemazica files` with the folder empty | Says none found, points at the schematics folder. |
| L2 | Drop `test.litematic` in, `/litemazica files` | Lists `test.litematic`. |
| L3 | Tab-complete `/litemazica place ` | Offers the **actual file name(s)** in the folder. |
| L4 | Stand facing **south**, `/litemazica place test` (no extension) | Builds; entrance at your feet, body extends the way you face — same anchoring as `generate`. Extension optional. |
| L5 | `/litemazica place test 100 64 100 arena` | Builds at those coords, named `arena`. Same coord/name rules as `generate`. |
| L6 | `/litemazica place nope` | Clean "no schematic named …" error, suggests `/litemazica files`. Server stays up. |
| L7 | `/litemazica place ../secret` or `place a/b` | Refused — a schematic name can't contain a path. |
| L8 | `/litemazica list` on a placed file maze | Shows it with regen `off`; regen summary uses `same` (never `fresh`). |
| L9 | `/litemazica regen <id> now`, or a scheduled reset | Rebuilds the **same** layout: loot chests re-roll, any blocks players broke are restored. |
| L10 | `/litemazica regen <id> hourly fresh` on a file maze | Message says file mazes always reset to the same layout; stored as `same`. |
| L11 | `/litemazica remove <id>` | Terrain restores from the snapshot, exactly like an API maze. |
| L12 | Export a schematic with a chest given a **loot table**, place it, open the chest | Fills from the loot table. (Items placed literally in the chest are **not** written — expected.) |
| L13 | Place a schematic built on a **different MC version** with an unknown block | Unknown block becomes air with a log warning; the rest builds. |

## Terrain snapshots

Best done on **natural terrain**, not superflat — hills and trees make a botched
restore obvious at a glance.

| # | Step | Expected |
| --- | --- | --- |
| S1 | Note the landscape (screenshot it), then generate a maze into a hillside | Maze builds, terrain carved away. A `plugins/Litemazica/snapshots/<id>/` folder appears with a `region.dat` and per-chunk files. |
| S2 | `/litemazica remove <id>` | Hill, trees, grass and water return exactly as they were. Snapshot file is deleted. |
| S3 | Place a chest with items in it, a written sign, and a spawner; generate a maze over them; then remove it | Chest returns **with its items**, sign with its text, spawner with its mob type. |
| S4 | Generate over a region, then `/litemazica list` and restart the server, then remove | Terrain still restores — snapshots survive restart. |
| S5 | Set `snapshot: false`, generate, remove | Falls back to clearing to air, and the message says so. |
| S6 | Set `snapshot-max-volume: 1000`, generate a normal maze | Warns that terrain won't be restorable; maze still builds; remove clears to air. |
| S7 | Set `regen 1 fresh`, let it reset a few times, then remove | Terrain restores cleanly with no leftover maze walls, even though the footprint shifted between resets. |
| S8 | Corrupt the `region.dat` manifest (edit a few bytes), then remove that maze | Warns the snapshot is unreadable, falls back to air, server unaffected. (A corrupt single chunk file is skipped with a warning; the rest still restores.) |
| S11 | Generate a **very large** maze on natural terrain (near `max-volume`), watch `/tps` during generate and remove | Capture and restore stream chunk-by-chunk — memory stays flat and TPS stays healthy even though the snapshot is huge. |
| S9 | Generate a large maze on natural terrain, watch `/tps` | Capture is batched like placement — TPS stays healthy. |
| S10 | Generate a maze through a **pond/river** (or with flowing water/lava beside it), then `/litemazica remove <id>` | The restored water/lava **resumes flowing** — it isn't left as frozen still blocks. Restored sand/gravel that lost its support **falls**. |

## Terrain blending (structure_void + headroom)

| # | Step | Expected |
| --- | --- | --- |
| T1 | Place a schematic whose basement filler is `minecraft:structure_void` (with real-air drop-trap shafts) into solid ground | The ground under the maze **stays** — no hollow box. The trap shafts are still carved down through it. |
| T2 | `/litemazica remove <id>` on the T1 maze | Terrain restores as normal; the untouched ground was never disturbed. |
| T3 | Set `blend-top-reach: 40` (or the editor's **Blend top** toggle), generate an **open-top** maze into a **forest** | A tree whose wood stands over the maze is **felled** — its trunk is broken and the canopy it supported **decays** shortly after (like a cut tree), leaving no half-trunks. Ground cover (grass, leaf litter, flowers) over the open top is cleared. A tree rooted beyond the maze is **left standing and not decapitated**, even where its canopy overhangs the open top. |
| T3b | Generate an open-top maze where a **single dirt/grass shelf** floats over a corridor (e.g. a thin ledge of a hill) | The lone layer and anything on it (flowers, leaf litter, a tree) are **removed** — no floating shelves or platforms left over the open top. A slab **two or more blocks thick** is treated as genuine terrain and kept (see T4). |
| T4 | Same settings, generate an open-top maze into a **hillside / under an overhang** | Every buried corridor (genuine terrain ≥2 thick above it) is capped with the ceiling material as a **clean, uniform roof** — no grass-and-ceiling checkerboard at the overlap edge. The roof **tucks one block out** under the surrounding terrain (not flush at the wall). The hill, its surface, and **any trees growing on it are left untouched**. The wall meets the ceiling — no air gap. No hole gouged up through the hill. |
| T5 | Generate an open-top maze (blend on) into a forest/hillside, then `/litemazica remove <id>` | The world comes back **as it was**: stripped trees are **replaced** and the stone-brick ceiling caps are **gone** — the blend's edits are all inside the snapshot. |
| T6 | With `blend-top-reach: 40`, generate a maze that **has a ceiling** | Nothing above it is touched — blend only applies to open-top mazes. |
| T7 | Generate an open-top maze under **shallow water** | Water directly above is cleared; note water may flow back from beyond the maze edge (expected). |

## Regeneration

| # | Step | Expected |
| --- | --- | --- |
| 18 | `/litemazica regen <id> daily fresh` | Confirms the schedule. `/litemazica list` shows `daily fresh`. Try `hourly`, `weekly`, `monthly` too. |
| 18b | Tab-complete `/litemazica regen <id> ` | Offers `off, hourly, daily, weekly, monthly, now`. |
| 18c | `/litemazica regen <id> yearly` | Rejected, listing the valid options. |
| 19 | Break a few walls, stand **outside**, `/litemazica regen <id> now` | Resets immediately; your damage is gone; **layout differs** (fresh), same footprint and entrance location. |
| 20 | `/litemazica regen <id> now same`, break walls, repeat | Resets to the **identical** layout each time, and the stored schedule is unchanged (`/litemazica list`). |
| 21 | Stand **inside** the maze, `/litemazica regen <id> now` | **Refused**, naming you. Nothing changes. |
| 22 | Walk out, `/litemazica regen <id> now` | Succeeds. |
| 23 | `/litemazica regen <id> 1 fresh` (raw minutes), stand inside over the boundary, wait 2–3 min | Scheduled reset is **deferred** silently — nothing changes under your feet. Walk out and it fires at the next check. |
| 24 | `/litemazica regen <id> off` | Regeneration turns off; `/litemazica list` shows `off`. |
| 24 | Stop the server, inspect `plugins/Litemazica/mazes.properties` | Contains the maze(s): id, world, anchor, yaw, region, regen settings. |
| 25 | Restart the server, `/litemazica list` | Mazes still listed. Schedules resume (set one to `1m` before restarting and confirm it still fires). |
| 26 | Edit `api-base-url`, `/litemazica reload` | New value picked up (point it somewhere invalid, confirm generate fails; point it back, confirm it works). |

## Concurrent operations

These exercise the in-flight guard (`busy`), which stops two block-rewrites from
running over the same maze at once. A big maze on slow terrain widens the window
and makes them easier to hit; `regen-check-seconds: 5` helps too.

| # | Step | Expected |
| --- | --- | --- |
| C1 | `regen <id> now`, then immediately `regen <id> now` again | Second says **"busy right now"**. Only one reset runs; the maze is intact afterwards. |
| C2 | `regen <id> now`, then immediately `remove <id>` | Remove says **"busy right now"**. It only succeeds once the reset finishes. |
| C3 | `regen <id> now`, then immediately `start <id>` | Teleport says **"busy right now"** rather than dropping you into a half-cleared maze. |
| C4 | Generate a **large** maze; while it is still building, `remove <id>` (and try `regen <id> now`, `start <id>`) | All say **"busy right now"**. The build finishes cleanly with no interleaved air pockets. |
| C5 | Generate a large maze; while building, `generate <same code>` at an **overlapping** spot | Refused for **overlap** — the first maze is registered before its blocks finish, so the second sees it. |
| C6 | Generate two mazes with the **same name** in quick succession | Second refused: name already exists. No orphaned half-maze. |
| C7 | A maze on a short schedule comes due while you spam `regen <id> now` | Never double-resets; extra triggers report busy. `/litemazica list` shows one clean maze. |

## Editor & re-editing (`/litemazica editor`, `/litemazica edit`)

Needs a Litemazica API that supports the editor bridge **and** session seeding
(the web app change that ships alongside this). Works the same on Bukkit,
Fabric and NeoForge.

| # | Step | Expected |
| --- | --- | --- |
| E1 | `/litemazica editor`, design something, **Apply to server** | Maze builds where you stand, as before — the plain editor path is unaffected. |
| E2 | Generate/editor-build a maze `m1`, then `/litemazica edit m1` | Browser opens **already showing m1's settings** (not the defaults). The chat says it'll rebuild m1 in place. |
| E3 | In that edit session, change the layout/size and **Apply** | m1 is rewritten **at its original entrance and facing** — no new maze, no teleport. Message says "updated". `/litemazica list` still shows one maze, same id. |
| E4 | Make the edit a **different size**, Apply, then `/litemazica remove m1` on natural terrain | Terrain restores cleanly over the new (larger/smaller) footprint — the snapshot was re-taken on edit. |
| E5 | `/litemazica edit m1`, Apply, then let a scheduled reset fire | The reset reproduces the **edited** layout — the stored design was updated. |
| E6 | `/litemazica edit <file-maze-id>` (one placed via `/litemazica place`) | Refused: file mazes have no editor design; message points you to editing the file. |
| E7 | `/litemazica edit m1` while standing **inside** m1, then Apply | Rebuild refused — players inside; nothing changes. Walk out and Apply works. |
| E8 | `/litemazica edit m1`, and before pressing Apply, `/litemazica remove m1` | After Apply, a clean "maze is gone — removed while editing" message; no orphaned blocks. |
| E9 | `/litemazica edit m1`, Apply, and immediately `regen m1 now` / `start m1` | The concurrent op says **"busy right now"** until the in-place rebuild finishes. |

## Failure handling

| # | Step | Expected |
| --- | --- | --- |
| 27 | Stop the API (or set a bogus URL), `/litemazica generate <code>` | Clean error to the player, warning in console, **no partial maze**, server unaffected. |
| E10 | During `/litemazica edit m1`, stop the API, then Apply | Clean error; m1 is left standing as it was; console warns. (The new code is committed only after a good fetch.) |
| 28 | Same, but on a **scheduled regen** | Existing maze is left intact (not cleared), warning logged, retried next interval. |
| 29 | Set `max-volume` low (e.g. `1000`), generate | Refused as too large, nothing placed. |

## Notes

- Placement writes blocks with physics off, so nothing should cascade or pop off.
- Everything is `plugins/Litemazica/` — delete that folder to reset state.
