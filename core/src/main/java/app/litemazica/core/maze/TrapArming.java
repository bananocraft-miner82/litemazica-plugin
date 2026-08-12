package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.SpawnerConfig;
import app.litemazica.core.platform.WorldAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static app.litemazica.core.maze.PlacementGeometry.rotX;
import static app.litemazica.core.maze.PlacementGeometry.rotZ;

/**
 * The post-placement passes that turn a freshly-written maze into a live one:
 * trapped-chest bait, loot tables, dispenser and spawner arming, and pressure-plate
 * thinning. Split out from {@link MazePlacer} because these are maze-semantics —
 * independent of the geometry and the write loop, and re-rolled on every reset —
 * and the version-form parsers are worth pinning on their own ({@link
 * MazePlacementTest}).
 *
 * <p>Every method reads the block-entity list from the schematic and rotates each
 * local coordinate into the world with the same transform placement used, so the
 * edits land on the blocks that were just written.
 */
final class TrapArming
{
    /**
     * The one plate type treated as a randomised trap marker. Authors lay a field
     * of these over hidden dispensers or TNT; every other plate type is décor and
     * is left exactly as placed. Iron (heavy weighted) is an uncommon decorative
     * choice, so reserving it rarely clashes.
     */
    static final String TRAP_PLATE = "minecraft:heavy_weighted_pressure_plate";

    private TrapArming()
    {
    }

    /**
     * Runs every post-placement pass in order: disarm trapped-chest bait (before
     * loot, so it lands on the final block), fill loot containers, stock dispensers,
     * arm spawners, then thin the trap-plate field ({@code trapPlates} gathered by
     * the write loop as it placed them).
     */
    static void applyAll(WorldAccess world, MazeSchematic maze, int rot,
                         int offX, int offZ, int baseY, int sizeX, int sizeZ, List<int[]> trapPlates)
    {
        Random rng = new Random();
        disarmTrappedChests(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ, rng);
        applyLootTables(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ);
        loadDispensers(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ);
        configureSpawners(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ);
        armPressurePlates(world, trapPlates, rng);
    }

    // ── trapped-chest bait ────────────────────────────────────────────────────

    /**
     * Randomly disarms trapped-chest bait. A trapped chest emits redstone when
     * opened — the schematic wires that to TNT or dispensers — so half the time it
     * is swapped for a plain chest: a decoy that's safe to open. The other half
     * stay live. Re-rolled on every reset, so players can't learn which chests
     * bite. Runs before {@link #applyLootTables} so the loot lands on the final
     * block, filling armed and decoy alike (the bait is the same either way).
     */
    private static void disarmTrappedChests(WorldAccess world, MazeSchematic maze, int rot,
                                            int offX, int offZ, int baseY, int sizeX, int sizeZ, Random rng)
    {
        for (Map<String, Object> te : maze.tileEntities())
        {
            if (!"minecraft:trapped_chest".equals(te.get("id")))
            {
                continue;
            }

            if (rng.nextInt(100) < 50)
            {
                continue; // armed: leave the trapped chest live
            }

            int lx = asInt(te.get("x"));
            int ly = asInt(te.get("y"));
            int lz = asInt(te.get("z"));
            int wx = offX + rotX(lx, lz, rot, sizeX, sizeZ);
            int wy = baseY + ly;
            int wz = offZ + rotZ(lx, lz, rot, sizeX, sizeZ);

            // Read the placed state (with its rotated facing) and swap only the id.
            String current = world.blockStateAt(wx, wy, wz);

            if (current != null && current.contains("minecraft:trapped_chest"))
            {
                world.setBlockState(wx, wy, wz, chestFromTrapped(current));
            }
        }
    }

    /**
     * Swaps a trapped chest's block id for a plain chest while keeping its state.
     * The two blocks share the same properties (facing, type, waterlogged), so the
     * decoy faces the same way and pairs into a double the same as the trap it
     * replaces. Package-private so the swap can be pinned in a test.
     */
    static String chestFromTrapped(String trappedChestState)
    {
        return trappedChestState.replace("minecraft:trapped_chest", "minecraft:chest");
    }

    // ── loot ─────────────────────────────────────────────────────────────────

    private static void applyLootTables(WorldAccess world, MazeSchematic maze, int rot,
                                        int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        for (Map<String, Object> te : maze.tileEntities())
        {
            if (!(te.get("LootTable") instanceof String lootTable))
            {
                continue; // only loot containers need post-placement handling
            }

            int lx = asInt(te.get("x"));
            int ly = asInt(te.get("y"));
            int lz = asInt(te.get("z"));
            long seed = te.get("LootTableSeed") instanceof Number n ? n.longValue() : 0L;

            world.applyLootTable(
                    offX + rotX(lx, lz, rot, sizeX, sizeZ),
                    baseY + ly,
                    offZ + rotZ(lx, lz, rot, sizeX, sizeZ),
                    lootTable, seed);
        }
    }

    // ── dispenser traps ────────────────────────────────────────────────────────

    /**
     * Stocks every trap dispenser the schematic placed with the exact ammunition
     * the schematic baked into it — the web-app editor's seed-deterministic mix of
     * arrows and thrown potions. Placement writes the block but applies no
     * block-entity contents, so the dispenser is stocked here from the schematic's
     * own {@code Items} rather than re-rolled — the same load every reset. A
     * dispenser with nothing baked in is left as placed.
     */
    private static void loadDispensers(WorldAccess world, MazeSchematic maze, int rot,
                                       int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        for (Map<String, Object> te : maze.tileEntities())
        {
            if (!"minecraft:dispenser".equals(te.get("id")))
            {
                continue;
            }

            List<DispenserItem> items = bakedDispenserItems(te);

            if (items.isEmpty())
            {
                continue; // nothing baked in — leave the dispenser as placed
            }

            int lx = asInt(te.get("x"));
            int ly = asInt(te.get("y"));
            int lz = asInt(te.get("z"));

            world.loadDispenser(
                    offX + rotX(lx, lz, rot, sizeX, sizeZ),
                    baseY + ly,
                    offZ + rotZ(lx, lz, rot, sizeX, sizeZ),
                    items);
        }
    }

    /**
     * Parses a dispenser's baked {@code Items} list into {@link DispenserItem}s. The
     * item NBT differs by the version the schematic was authored for — a stack count
     * is a {@code Count} byte before 1.20.5 and a {@code count} int after, and a
     * potion rides in a {@code tag} compound before and a {@code components} one
     * after — so both forms are read; the running server's own item form is rebuilt
     * later by {@code WorldAccess.loadDispenser}. Package-private so the parse can be
     * pinned in a test.
     */
    static List<DispenserItem> bakedDispenserItems(Map<String, Object> te)
    {
        List<DispenserItem> out = new ArrayList<>();

        if (!(te.get("Items") instanceof List<?> items))
        {
            return out;
        }

        for (Object entry : items)
        {
            if (!(entry instanceof Map<?, ?> item) || !(item.get("id") instanceof String id))
            {
                continue;
            }

            int slot = asInt(item.get("Slot"));
            int count = item.get("count") instanceof Number n ? n.intValue() : asInt(item.get("Count"));
            out.add(new DispenserItem(slot, id, Math.max(1, count), bakedPotionId(item)));
        }

        return out;
    }

    /** The potion registry id a baked item carries, from either NBT form, or null. */
    private static String bakedPotionId(Map<?, ?> item)
    {
        // 1.20.5+: components → "minecraft:potion_contents" → potion
        if (item.get("components") instanceof Map<?, ?> components
                && components.get("minecraft:potion_contents") instanceof Map<?, ?> contents
                && contents.get("potion") instanceof String potion)
        {
            return potion;
        }

        // Before 1.20.5: tag → Potion
        if (item.get("tag") instanceof Map<?, ?> tag && tag.get("Potion") instanceof String potion)
        {
            return potion;
        }

        return null;
    }

    // ── spawner rooms ───────────────────────────────────────────────────────────

    /**
     * Arms every spawner the schematic placed with the mob the schematic baked into
     * it — the web-app editor's seed-deterministic choice — on a fixed cadence.
     * Placement writes the (empty) spawner block but applies no block-entity data,
     * so the mob is set here from the schematic's own {@code SpawnData} rather than
     * re-rolled — the same monster every reset. A spawner with no baked mob is left
     * as placed.
     */
    private static void configureSpawners(WorldAccess world, MazeSchematic maze, int rot,
                                          int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        for (Map<String, Object> te : maze.tileEntities())
        {
            Object id = te.get("id");

            // The block is minecraft:spawner; its block entity is minecraft:mob_spawner.
            // Accept either, so it doesn't matter which form the schematic stored.
            if (!"minecraft:mob_spawner".equals(id) && !"minecraft:spawner".equals(id))
            {
                continue;
            }

            String mob = bakedSpawnerMob(te);

            if (mob == null)
            {
                continue; // nothing baked in — leave the spawner as placed
            }

            int lx = asInt(te.get("x"));
            int ly = asInt(te.get("y"));
            int lz = asInt(te.get("z"));

            world.configureSpawner(
                    offX + rotX(lx, lz, rot, sizeX, sizeZ),
                    baseY + ly,
                    offZ + rotZ(lx, lz, rot, sizeX, sizeZ),
                    spawnerConfigFor(mob));
        }
    }

    /**
     * The mob a spawner's baked {@code SpawnData} names, from either NBT form (the
     * entity id nested under {@code entity} from 1.18, flat before), or null if none
     * is baked. Package-private so the parse can be pinned in a test.
     */
    static String bakedSpawnerMob(Map<String, Object> te)
    {
        if (!(te.get("SpawnData") instanceof Map<?, ?> spawnData))
        {
            return null;
        }

        // 1.18+: SpawnData → entity → id
        if (spawnData.get("entity") instanceof Map<?, ?> entity && entity.get("id") instanceof String id)
        {
            return id;
        }

        // Before 1.18: SpawnData → id
        if (spawnData.get("id") instanceof String id)
        {
            return id;
        }

        return null;
    }

    /**
     * A spawner arming for the given mob on vanilla-standard cadence — the schematic
     * bakes only which mob, not the timing. Package-private for tests.
     */
    static SpawnerConfig spawnerConfigFor(String mob)
    {
        // Vanilla mob-spawner defaults: 4 at a time, cap 6 nearby, 16-block trigger,
        // 10–40 s between waves, 4-block spread.
        return new SpawnerConfig(mob, 4, 6, 16, 200, 800, 4);
    }

    // ── pressure-plate traps ──────────────────────────────────────────────────

    /**
     * Thins out a placed field of trap plates, clearing about half to air so the
     * live tiles shuffle. Re-rolled on every reset (a fresh field is laid and
     * re-thinned), so the safe path across the field changes each time. Only the
     * reserved {@link #TRAP_PLATE} is ever touched. Package-private so the roll can
     * be pinned in a test.
     */
    static void armPressurePlates(WorldAccess world, List<int[]> plates, Random rng)
    {
        for (int[] p : plates)
        {
            if (rng.nextInt(100) < 50)
            {
                world.setBlockState(p[0], p[1], p[2], "minecraft:air");
            }
        }
    }

    private static int asInt(Object o)
    {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
