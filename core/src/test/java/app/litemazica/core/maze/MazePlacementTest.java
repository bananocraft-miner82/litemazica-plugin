package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.WorldAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually reaches the world when a maze is placed: {@code structure_void}
 * voxels are left as-is, and — with a blend band — the terrain above an open-top
 * maze is read and adapted (canopy stripped under sky, capped under rock).
 * Exercised through a fake {@link WorldAccess} that can be pre-seeded with the
 * blocks standing above the maze.
 */
class MazePlacementTest
{
    /** air=0, stone=1, structure_void=2 → 2 bits per block. */
    private static final List<String> PALETTE = List.of(
            "minecraft:air", "minecraft:stone", "minecraft:structure_void");

    // A 1×2×1 maze placed at (0,64,0): floor at world y=63, the voxel above it
    // at y=64, and the ceiling plane (where a blend cap lands) at y=65.
    private static final int FLOOR_Y = 63;
    private static final int CAP_Y = 65;

    @Test
    void structureVoidIsLeftAloneAndTheBodyIsPlaced()
    {
        // Floor stone, structure_void directly above it; no blend band.
        MazeSchematic maze = schematic(new int[]{1, 2}, 0);
        FakeWorld world = new FakeWorld();
        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

        assertEquals("minecraft:stone", world.blockAt(0, FLOOR_Y, 0));
        assertFalse(world.wasWritten(0, FLOOR_Y + 1, 0),
                "structure_void voxel should leave the existing block in place");
    }

    @Test
    void blendFellsATrunkOverTheMazeAndLetsItsCanopyDecay()
    {
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        // A trunk + canopy standing above the maze, open sky beyond it.
        world.seed(0, CAP_Y, 0, "minecraft:oak_log[axis=y]");
        world.seed(0, CAP_Y + 1, 0, "minecraft:oak_log[axis=y]");
        world.seed(0, CAP_Y + 2, 0, "minecraft:oak_leaves[distance=1]");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        // The whole trunk is removed with a neighbour-notifying break, so the canopy
        // it supported decays on its own (leaves are never edited directly).
        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "trunk removed");
        assertEquals("minecraft:air", world.blockAt(0, CAP_Y + 1, 0), "trunk removed");
        assertTrue(world.wasPhysicsCleared(0, CAP_Y, 0), "trunk broken with a neighbour update, to trigger decay");
        assertTrue(world.wasPhysicsCleared(0, CAP_Y + 1, 0), "trunk broken with a neighbour update, to trigger decay");
    }

    @Test
    void blendCapsUnderRockAndLeavesTheGroundAbove()
    {
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        // Solid ground directly above the maze (buried).
        world.seed(0, CAP_Y, 0, "minecraft:stone");
        world.seed(0, CAP_Y + 1, 0, "minecraft:dirt");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        // Capped with the maze's ceiling block; the terrain above is untouched.
        assertEquals("minecraft:stone_bricks", world.blockAt(0, CAP_Y, 0));
        assertEquals("minecraft:dirt", world.blockAt(0, CAP_Y + 1, 0));
    }

    @Test
    void blendLeavesOpenSkyUntouched()
    {
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertFalse(world.wasWritten(0, CAP_Y, 0), "nothing above open sky should be written");
    }

    @Test
    void blendFollowsBranchesButNeverLeaves()
    {
        // Trunk over the maze (x=0) with a branch log reaching into the margin (x=1)
        // and a leaf on that branch (x=1). Connected *logs* are followed and removed
        // (trunk and branch); the leaf is left for decay, never edited directly.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:oak_log[axis=y]");              // trunk over the maze
        world.seed(0, CAP_Y + 1, 0, "minecraft:oak_log[axis=y]");
        world.seed(1, CAP_Y + 1, 0, "minecraft:oak_log[axis=x]");          // branch into the margin
        world.seed(1, CAP_Y + 2, 0, "minecraft:oak_leaves[distance=1]");   // leaf on the branch

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "trunk removed");
        assertEquals("minecraft:air", world.blockAt(1, CAP_Y + 1, 0), "connected branch log removed into the margin");
        assertEquals("minecraft:oak_leaves[distance=1]", world.blockAt(1, CAP_Y + 2, 0),
                "the leaf is left to decay, not edited directly");
    }

    @Test
    void blendLeavesADisconnectedMarginTreeStanding()
    {
        // A tree rooted out in the margin (x=3), its canopy not touching anything
        // over the maze. No wood of it stands over the footprint and nothing
        // connects it to a removal, so it is left completely alone.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(3, CAP_Y, 0, "minecraft:oak_log[axis=y]");            // trunk, out in the margin
        world.seed(3, CAP_Y + 1, 0, "minecraft:oak_log[axis=y]");
        world.seed(3, CAP_Y + 2, 0, "minecraft:oak_leaves[distance=1]");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:oak_log[axis=y]", world.blockAt(3, CAP_Y, 0), "margin trunk left standing");
        assertEquals("minecraft:oak_leaves[distance=1]", world.blockAt(3, CAP_Y + 2, 0), "its canopy left whole");
    }

    @Test
    void blendDoesNotDecapitateATreeWhoseCanopyMerelyOverhangs()
    {
        // A leaf from a beside-the-maze tree hangs directly over the open top, but no
        // wood of that tree crosses the footprint. The tree keeps its head — the
        // overhanging leaf is left, not shaved off, so the margin tree isn't
        // decapitated. (Leaves come off only via a flood seeded by a trunk over the
        // maze.)
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y + 2, 0, "minecraft:oak_leaves[distance=2]"); // overhang, over the open top
        world.seed(3, CAP_Y, 0, "minecraft:oak_log[axis=y]");            // trunk, out in the margin
        world.seed(3, CAP_Y + 1, 0, "minecraft:oak_log[axis=y]");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:oak_leaves[distance=2]", world.blockAt(0, CAP_Y + 2, 0),
                "an overhanging leaf is left, not shaved off");
        assertEquals("minecraft:oak_log[axis=y]", world.blockAt(3, CAP_Y, 0),
                "the tree's trunk out in the margin is left standing");
    }

    @Test
    void blendClearsGroundCoverButNotOverhangingCanopy()
    {
        // Over the open top: loose ground cover (leaf litter, flowers) is cleared,
        // but tree leaves overhanging from elsewhere are not.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:leaf_litter[segment_amount=3]"); // ground litter
        world.seed(0, CAP_Y + 3, 0, "minecraft:birch_leaves[distance=3]");  // overhang above it

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "leaf litter is cleared, not capped");
        assertEquals("minecraft:birch_leaves[distance=3]", world.blockAt(0, CAP_Y + 3, 0),
                "an overhanging leaf above it is left");
    }

    @Test
    void blendCapsAThickBuriedHillAndKeepsTreesOnItsSurface()
    {
        // The maze is tucked under a hill (several solid layers), with a tree on
        // the hill's surface. The corridor is capped, and the hill and its tree
        // are left completely alone.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        for (int d = 0; d < 4; d++) world.seed(0, CAP_Y + d, 0, "minecraft:dirt"); // the hill
        world.seed(0, CAP_Y + 4, 0, "minecraft:oak_log[axis=y]");                   // tree on the hill
        world.seed(0, CAP_Y + 5, 0, "minecraft:oak_leaves[distance=1]");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:stone_bricks", world.blockAt(0, CAP_Y, 0), "corridor capped");
        assertEquals("minecraft:dirt", world.blockAt(0, CAP_Y + 1, 0), "hill kept");
        assertEquals("minecraft:oak_log[axis=y]", world.blockAt(0, CAP_Y + 4, 0), "tree on the hill kept");
        assertEquals("minecraft:oak_leaves[distance=1]", world.blockAt(0, CAP_Y + 5, 0), "its canopy kept");
    }

    @Test
    void blendRemovesALoneFloatingLayerAndAnythingOnIt()
    {
        // A single floating layer over a corridor (a one-block dirt/grass shelf) —
        // capping it would just leave an isolated patch of ceiling, so instead the
        // layer and everything rooted on it are cleared away, opening the top.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:grass_block[snowy=false]"); // lone shelf
        world.seed(0, CAP_Y + 1, 0, "minecraft:oak_log[axis=y]");      // tree on it
        world.seed(0, CAP_Y + 2, 0, "minecraft:oak_leaves[distance=1]");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "the lone shelf is removed");
        assertEquals("minecraft:air", world.blockAt(0, CAP_Y + 1, 0), "and the trunk rooted on it is felled");
        assertEquals("minecraft:oak_leaves[distance=1]", world.blockAt(0, CAP_Y + 2, 0),
                "its canopy is left to decay once the trunk is gone");
    }

    @Test
    void blendRemovesALoneShelfButKeepsGenuineTerrainAbove()
    {
        // A single shelf low down, then a two-block-thick terrain slab higher up:
        // the lone shelf is cleared, but the genuine slab is left in place (not
        // gouged through) — the strip stops at real terrain.
        MazeSchematic maze = schematic(new int[]{1, 1}, 20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:dirt");         // lone shelf over the corridor
        world.seed(0, CAP_Y + 3, 0, "minecraft:stone");    // genuine slab (2 thick) higher up
        world.seed(0, CAP_Y + 4, 0, "minecraft:stone");

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 20, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "lone shelf removed");
        assertEquals("minecraft:stone", world.blockAt(0, CAP_Y + 3, 0), "genuine terrain left in place");
        assertEquals("minecraft:stone", world.blockAt(0, CAP_Y + 4, 0), "…and not gouged through");
    }

    @Test
    void blendRemovesTrunkLogsWithPhysicsAndLeavesOutOfReachCanopy()
    {
        // A trunk reaching to the top of the scan band, with a leaf just beyond reach.
        // Every trunk log is broken with a neighbour update (so the canopy decays),
        // and the out-of-reach leaf is never edited directly.
        int band = 20;
        MazeSchematic maze = schematic(new int[]{1, 1}, band);
        FakeWorld world = new FakeWorld();
        for (int d = 0; d < band; d++) world.seed(0, CAP_Y + d, 0, "minecraft:oak_log[axis=y]"); // up to the band top
        world.seed(0, CAP_Y + band, 0, "minecraft:oak_leaves[distance=1]");                       // just beyond reach

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, band, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y + band - 1, 0), "the reachable trunk is removed");
        assertTrue(world.wasPhysicsCleared(0, CAP_Y + band - 1, 0),
                "trunk logs are broken with a neighbour update, to trigger canopy decay");
        assertEquals("minecraft:oak_leaves[distance=1]", world.blockAt(0, CAP_Y + band, 0),
                "the out-of-reach leaf isn't edited directly (it's left to decay)");
    }

    @Test
    void blendLapsTheCeilingAsAnEaveOverAnOpenCorridor()
    {
        // A two-column maze: column 0 is buried under a slab (capped); column 1 is an
        // open corridor beside it. The ceiling laps one block out over the open
        // corridor, so the buried terrain reads as overhanging rather than ending
        // flush at the wall. Facing EAST makes the maze's two columns land at world
        // x=0 (buried) and x=1 (open).
        MazeSchematic maze = wideSchematic(20);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:stone");      // buried column: terrain overhead
        world.seed(0, CAP_Y + 1, 0, "minecraft:dirt");
        // column 1 (world x=1) is left open — nothing above it.

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 270f, 20, r -> {});

        assertEquals("minecraft:stone_bricks", world.blockAt(0, CAP_Y, 0), "buried column capped");
        assertEquals("minecraft:stone_bricks", world.blockAt(1, CAP_Y, 0),
                "the ceiling laps one block over the open corridor as an eave");
        assertEquals("minecraft:dirt", world.blockAt(0, CAP_Y + 1, 0), "terrain above the buried column is kept");
    }

    @Test
    void blendClearsCanopyButKeepsAFarOverhang()
    {
        MazeSchematic maze = schematic(new int[]{1, 1}, 30);
        FakeWorld world = new FakeWorld();
        world.seed(0, CAP_Y, 0, "minecraft:oak_log[axis=y]"); // low canopy
        world.seed(0, CAP_Y + 25, 0, "minecraft:stone");      // overhang past the 20 cap

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 30, r -> {});

        assertEquals("minecraft:air", world.blockAt(0, CAP_Y, 0), "canopy cleared");
        assertEquals("minecraft:stone", world.blockAt(0, CAP_Y + 25, 0), "far overhang kept, not deleted");
    }

    @Test
    void loadsTrapDispensersFromTheSchematicsBakedItems()
    {
        // A dispenser trap carrying baked Items (the web-app editor's mix). Placement
        // writes the block; the dispenser is then stocked with exactly those items —
        // not a fresh roll.
        List<Object> bakedItems = List.of(
                Map.<String, Object>of("Slot", (byte) 0, "id", "minecraft:arrow", "Count", (byte) 16),
                Map.<String, Object>of("Slot", (byte) 2, "id", "minecraft:tipped_arrow", "Count", (byte) 1,
                        "tag", Map.<String, Object>of("Potion", "minecraft:harming")));
        MazeSchematic maze = new MazeSchematic("test", 3465, 1, 2, 1, 0,
                0, 0, 0, 2, 0,
                PALETTE, pack(new int[]{1, 2}, 2),
                List.of(Map.<String, Object>of("id", "minecraft:dispenser", "x", 0, "y", 0, "z", 0,
                        "Items", bakedItems)),
                0, "minecraft:stone_bricks");
        FakeWorld world = new FakeWorld();

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

        List<DispenserItem> load = world.dispenserAt(0, FLOOR_Y, 0); // TE y=0 → world baseY = FLOOR_Y
        assertNotNull(load, "the dispenser should be stocked from its baked items");
        assertEquals(2, load.size(), "both baked stacks applied, nothing invented");

        DispenserItem arrow = load.get(0);
        assertEquals(0, arrow.slot());
        assertEquals("minecraft:arrow", arrow.itemId());
        assertEquals(16, arrow.count());
        assertNull(arrow.potionId());

        DispenserItem tipped = load.get(1);
        assertEquals(2, tipped.slot());
        assertEquals("minecraft:tipped_arrow", tipped.itemId());
        assertEquals("minecraft:harming", tipped.potionId());
    }

    @Test
    void loadsNothingIntoADispenserWithNoBakedItems()
    {
        // Nothing baked in → the plugin leaves the dispenser as placed (it never
        // invents its own load).
        MazeSchematic maze = new MazeSchematic("test", 3465, 1, 2, 1, 0,
                0, 0, 0, 2, 0,
                PALETTE, pack(new int[]{1, 2}, 2),
                List.of(Map.of("id", "minecraft:dispenser", "x", 0, "y", 0, "z", 0)),
                0, "minecraft:stone_bricks");
        FakeWorld world = new FakeWorld();

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

        assertNull(world.dispenserAt(0, FLOOR_Y, 0), "an empty dispenser is left untouched");
    }

    @Test
    void bakedDispenserItemsReadsBothVersionItemForms()
    {
        // Pre-1.20.5: a Count byte and a tag → Potion compound.
        List<DispenserItem> legacy = TrapArming.bakedDispenserItems(Map.of("Items", List.of(
                Map.<String, Object>of("Slot", (byte) 1, "id", "minecraft:splash_potion", "Count", (byte) 1,
                        "tag", Map.<String, Object>of("Potion", "minecraft:poison")))));
        assertEquals(1, legacy.size());
        assertEquals(1, legacy.get(0).slot());
        assertEquals("minecraft:splash_potion", legacy.get(0).itemId());
        assertEquals(1, legacy.get(0).count());
        assertEquals("minecraft:poison", legacy.get(0).potionId());

        // 1.20.5+: a count int and a components → potion_contents → potion compound.
        List<DispenserItem> modern = TrapArming.bakedDispenserItems(Map.of("Items", List.of(
                Map.<String, Object>of("Slot", 3, "id", "minecraft:lingering_potion", "count", 1,
                        "components", Map.<String, Object>of("minecraft:potion_contents",
                                Map.<String, Object>of("potion", "minecraft:harming"))))));
        assertEquals(1, modern.size());
        assertEquals(3, modern.get(0).slot());
        assertEquals("minecraft:lingering_potion", modern.get(0).itemId());
        assertEquals("minecraft:harming", modern.get(0).potionId());

        // No Items → nothing.
        assertTrue(TrapArming.bakedDispenserItems(Map.of("id", "minecraft:dispenser")).isEmpty());
    }

    @Test
    void armsATrapSpawnerFromTheSchematicsBakedMob()
    {
        // A spawner carrying baked SpawnData (the web-app editor's mob). Placement
        // writes the empty block; the spawner is then armed with exactly that mob.
        MazeSchematic maze = new MazeSchematic("test", 3465, 1, 2, 1, 0,
                0, 0, 0, 2, 0,
                PALETTE, pack(new int[]{1, 2}, 2),
                List.of(Map.<String, Object>of("id", "minecraft:mob_spawner", "x", 0, "y", 0, "z", 0,
                        "SpawnData", Map.<String, Object>of("entity",
                                Map.<String, Object>of("id", "minecraft:witch")))),
                0, "minecraft:stone_bricks");
        FakeWorld world = new FakeWorld();

        MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

        app.litemazica.core.platform.SpawnerConfig config = world.spawnerAt(0, FLOOR_Y, 0);
        assertNotNull(config, "the spawner should be armed from its baked mob");
        assertEquals("minecraft:witch", config.entityId(), "the baked mob, not a re-roll");
        assertTrue(config.spawnCount() >= 1, "spewing at least one mob");
    }

    @Test
    void bakedSpawnerMobReadsBothVersionForms()
    {
        // 1.18+: SpawnData → entity → id.
        assertEquals("minecraft:zombie", TrapArming.bakedSpawnerMob(Map.of(
                "SpawnData", Map.<String, Object>of("entity", Map.<String, Object>of("id", "minecraft:zombie")))));

        // Before 1.18: SpawnData → id.
        assertEquals("minecraft:pillager", TrapArming.bakedSpawnerMob(Map.of(
                "SpawnData", Map.<String, Object>of("id", "minecraft:pillager"))));

        // No SpawnData → null (nothing baked).
        assertNull(TrapArming.bakedSpawnerMob(Map.of("id", "minecraft:mob_spawner")));
    }

    @Test
    void disarmingATrappedChestKeepsItsOrientation()
    {
        // The decoy must face and pair the same way as the trap it replaces, so only
        // the block id changes — every property is carried across untouched.
        assertEquals("minecraft:chest[facing=east,type=single,waterlogged=false]",
                TrapArming.chestFromTrapped("minecraft:trapped_chest[facing=east,type=single,waterlogged=false]"));
        assertEquals("minecraft:chest", TrapArming.chestFromTrapped("minecraft:trapped_chest"));
    }

    @Test
    void trappedChestBaitIsSometimesArmedAndSometimesADecoy()
    {
        // A maze whose only block is a trapped chest carrying a loot table. Across
        // many resets it should sometimes stay a live trapped chest (armed) and
        // sometimes be swapped for a plain chest (decoy) — never one or the other
        // every time.
        List<String> palette = List.of("minecraft:trapped_chest");
        MazeSchematic maze = new MazeSchematic("test", 3465, 1, 1, 1, 0,
                0, 0, 0, 1, 0,
                palette, pack(new int[]{0}, 2),
                List.of(Map.of("id", "minecraft:trapped_chest", "x", 0, "y", 0, "z", 0,
                        "LootTable", "minecraft:chests/simple_dungeon")),
                0, "minecraft:stone_bricks");

        boolean sawArmed = false;
        boolean sawDecoy = false;

        for (int i = 0; i < 200 && !(sawArmed && sawDecoy); i++)
        {
            FakeWorld world = new FakeWorld();
            MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

            String block = world.blockAt(0, 63, 0); // floor of a 1-tall maze at ay=64
            if (block.contains("minecraft:trapped_chest"))
            {
                sawArmed = true;
            }
            else if (block.contains("minecraft:chest"))
            {
                sawDecoy = true;
            }
        }

        assertTrue(sawArmed, "some resets leave the trapped chest live");
        assertTrue(sawDecoy, "some resets swap it for a plain-chest decoy");
    }

    @Test
    void armingAPlateFieldThinsSomeAndLeavesOthersLive()
    {
        // Pinned with a seeded RNG: a field of trap plates comes back with some tiles
        // cleared to air (safe) and some left live, never all-or-nothing.
        FakeWorld world = new FakeWorld();
        List<int[]> plates = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            int[] pos = {i, 63, 0};
            world.seed(pos[0], pos[1], pos[2], "minecraft:heavy_weighted_pressure_plate");
            plates.add(pos);
        }

        TrapArming.armPressurePlates(world, plates, new java.util.Random(1));

        int cleared = 0;
        int live = 0;
        for (int[] p : plates)
        {
            String block = world.blockAt(p[0], p[1], p[2]);
            if (block.equals("minecraft:air"))
            {
                cleared++;
            }
            else if (block.equals("minecraft:heavy_weighted_pressure_plate"))
            {
                live++;
            }
        }

        assertEquals(plates.size(), cleared + live, "every plate is either cleared or left live");
        assertTrue(cleared > 0, "some plates are cleared to air");
        assertTrue(live > 0, "some plates stay live");
    }

    @Test
    void placementThinsTrapPlatesButNeverDecorativeOnes()
    {
        // A row running the maze's depth: four reserved trap plates (heavy weighted)
        // then one decorative stone pressure plate. A 1-wide body with its entrance
        // at the near edge lands unrotated facing south, so local z maps to world z.
        // Across resets the trap tiles vary — sometimes live, sometimes cleared —
        // while the decorative plate is always left in place.
        List<String> palette = List.of(
                "minecraft:heavy_weighted_pressure_plate", "minecraft:stone_pressure_plate");
        // Voxels along z: [trap, trap, trap, trap, decorative].
        MazeSchematic maze = new MazeSchematic("test", 3465, 1, 1, 5, 0,
                0, 0, 0, 5, 0,
                palette, pack(new int[]{0, 0, 0, 0, 1}, 2), List.of(),
                0, "minecraft:stone_bricks");

        boolean sawTrapLive = false;
        boolean sawTrapCleared = false;

        for (int i = 0; i < 200 && !(sawTrapLive && sawTrapCleared); i++)
        {
            FakeWorld world = new FakeWorld();
            MazePlacer.place(new TestScheduler(), world, maze, 0, 64, 0, 0f, 0, r -> {});

            assertEquals("minecraft:stone_pressure_plate", world.blockAt(0, 63, 4),
                    "decorative plates are never thinned");

            for (int z = 0; z < 4; z++)
            {
                String block = world.blockAt(0, 63, z);
                if (block.equals("minecraft:heavy_weighted_pressure_plate"))
                {
                    sawTrapLive = true;
                }
                else if (block.equals("minecraft:air"))
                {
                    sawTrapCleared = true;
                }
            }
        }

        assertTrue(sawTrapLive, "some trap tiles stay live across resets");
        assertTrue(sawTrapCleared, "some trap tiles are cleared across resets");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static MazeSchematic schematic(int[] voxels, int clearAbove)
    {
        return new MazeSchematic("test", 3465, 1, 2, 1, 0,
                0, 0, 0, voxels.length, 0,
                PALETTE, pack(voxels, 2), List.of(), clearAbove, "minecraft:stone_bricks");
    }

    /** A 2×2×1 maze: two side-by-side columns, each a stone floor with open space above. */
    private static MazeSchematic wideSchematic(int clearAbove)
    {
        // Voxels are ordered ((y*sizeZ + z)*sizeX + x): [col0_y0, col1_y0, col0_y1, col1_y1].
        int[] voxels = {1, 1, 2, 2}; // stone floors, structure_void above
        return new MazeSchematic("test", 3465, 2, 2, 1, 0,
                0, 0, 0, voxels.length, 0,
                PALETTE, pack(voxels, 2), List.of(), clearAbove, "minecraft:stone_bricks");
    }

    private static long[] pack(int[] values, int bits)
    {
        long[] out = new long[(int) (((long) values.length * bits + 63) / 64) + 1];
        long mask = (1L << bits) - 1;

        for (int i = 0; i < values.length; i++)
        {
            long bitPos = (long) i * bits;
            int startLong = (int) (bitPos >> 6);
            int startBit = (int) (bitPos & 63);
            long v = values[i] & mask;
            out[startLong] |= v << startBit;

            if (startBit + bits > 64)
            {
                out[startLong + 1] |= v >>> (64 - startBit);
            }
        }

        return out;
    }

}
