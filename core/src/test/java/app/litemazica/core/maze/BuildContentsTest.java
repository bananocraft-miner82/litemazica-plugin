package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.nbt.Snbt;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildContentsTest
{
    @Test
    void snbtKeepsEveryTypeAndEscapesStrings()
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("Facing", (byte) 3);
        value.put("s", (short) 2);
        value.put("i", 7);
        value.put("L", 9L);
        value.put("f", 1.5f);
        value.put("d", 2.25d);
        value.put("text", "say \"hi\"\\\nbye");
        value.put("odd key", List.of(1, 2));
        value.put("ints", new int[]{1, -2});
        value.put("bytes", new byte[]{1});
        value.put("longs", new long[]{3L});

        assertEquals(
                "{Facing:3b,s:2s,i:7,L:9L,f:1.5f,d:2.25d,text:\"say \\\"hi\\\"\\\\ bye\",\"odd key\":[1,2],"
                        + "ints:[I;1,-2],bytes:[B;1b],longs:[L;3L]}",
                Snbt.write(value));
    }

    private static MazeSchematic maze(List<Map<String, Object>> tileEntities, List<Map<String, Object>> entities)
    {
        // 4 wide (x) × 1 tall × 2 deep (z), all air.
        return new MazeSchematic("test", 3465, 4, 1, 2, 0, 0, 0, 0, 0, 0,
                List.of("minecraft:air"), new long[1], tileEntities, entities, 0, "minecraft:stone_bricks");
    }

    private static Map<String, Object> map(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void mergesBuildBlockEntitiesButLeavesTrapAndLootOnesToTheirOwnPasses()
    {
        MazeSchematic m = maze(List.of(
                map("id", "minecraft:sign", "x", 1, "y", 0, "z", 0, "front_text", map("messages", List.of("\"Welcome\""))),
                map("id", "minecraft:chest", "x", 2, "y", 0, "z", 1, "LootTable", "minecraft:chests/simple_dungeon"),
                map("id", "minecraft:mob_spawner", "x", 3, "y", 0, "z", 0, "SpawnData", map()),
                map("id", "minecraft:bed", "x", 0, "y", 0, "z", 0)), List.of());

        List<BuildContents.BlockEntityMerge> merges = BuildContents.blockEntityMerges(m, 0, 100, 200, 64, 4, 2);

        assertEquals(1, merges.size(), "only the sign: loot, spawner and data-less blocks are skipped");
        assertEquals(new BuildContents.BlockEntityMerge(101, 64, 200, "{front_text:{messages:[\"\\\"Welcome\\\"\"]}}"), merges.get(0));
    }

    @Test
    void spawnsOnlyDecorationsTaggedForCleanup()
    {
        MazeSchematic m = maze(List.of(), List.of(
                map("id", "minecraft:armor_stand", "Pos", List.of(1.5, 0.0, 0.5), "UUID", new int[]{1, 2, 3, 4}),
                map("id", "minecraft:zombie", "Pos", List.of(0.5, 0.0, 0.5)),
                map("id", "minecraft:command_block_minecart", "Pos", List.of(0.5, 0.0, 0.5), "Command", "op @a")));

        List<BuildContents.EntitySpawn> spawns = BuildContents.entitySpawns(m, 0, 10, 20, 5, 4, 2);

        assertEquals(1, spawns.size());
        assertEquals(new BuildContents.EntitySpawn("minecraft:armor_stand", 11.5, 5.0, 20.5, "{Tags:[\"litemazica\"]}"), spawns.get(0));
    }

    @Test
    void turnsEntitiesWithThePlacement()
    {
        MazeSchematic m = maze(List.of(), List.of(
                map("id", "minecraft:item_frame", "Pos", List.of(1.5, 0.5, 0.03125), "Facing", (byte) 3,
                        "TileX", 1, "TileY", 0, "TileZ", 0),
                map("id", "minecraft:armor_stand", "Pos", List.of(3.0, 0.0, 1.0), "Rotation", List.of(180f, 0f)),
                map("id", "minecraft:painting", "Pos", List.of(0.5, 0.5, 0.03), "facing", (byte) 0)));

        // One clockwise turn: (x, z) -> (sizeZ - z, x) for a point in a 4 × 2 box.
        List<BuildContents.EntitySpawn> spawns = BuildContents.entitySpawns(m, 1, 0, 0, 0, 4, 2);

        // Hanging entities spawn at the centre of their (turned) block: (1, 0) -> (1, 1).
        BuildContents.EntitySpawn frame = spawns.get(0);
        assertEquals(1.5, frame.x(), 1e-9);
        assertEquals(1.5, frame.z(), 1e-9);
        assertTrue(frame.snbt().contains("Facing:4b"), "south turns to west: " + frame.snbt());
        assertTrue(!frame.snbt().contains("TileX"), "the attachment block comes from the spawn position");

        assertTrue(spawns.get(1).snbt().contains("Rotation:[270.0f,0.0f]"), spawns.get(1).snbt());
        assertTrue(spawns.get(2).snbt().contains("facing:1b"), "south turns to west: " + spawns.get(2).snbt());
    }

    @Test
    void aTwoWidePaintingSpawnsInItsOwnBlockNotTheEdgeItsPosSitsOn()
    {
        // A 2 × 1 painting on a north wall: its Pos is on the edge between x=1 and
        // x=2, and the block it belongs to is x=1.
        MazeSchematic m = maze(List.of(), List.of(
                map("id", "minecraft:painting", "Pos", List.of(2.0, 0.5, 0.03125), "facing", (byte) 0,
                        "TileX", 1, "TileY", 0, "TileZ", 0)));

        BuildContents.EntitySpawn painting = BuildContents.entitySpawns(m, 0, 0, 0, 0, 4, 2).get(0);

        assertEquals(1.5, painting.x(), 1e-9);
        assertEquals(0.5, painting.y(), 1e-9);
        assertEquals(0.5, painting.z(), 1e-9);
    }

    @Test
    void placementClearsTaggedEntitiesThenMergesAndSpawns()
    {
        FakeWorld world = new FakeWorld();
        MazeSchematic m = new MazeSchematic("test", 3465, 1, 2, 1, 0, 0, 0, 0, 1, 0,
                List.of("minecraft:air", "minecraft:oak_sign"), new long[]{0b0001},
                List.of(map("id", "minecraft:sign", "x", 0, "y", 0, "z", 0, "Text1", "\"Hi\"")),
                List.of(map("id", "minecraft:armor_stand", "Pos", List.of(0.5, 1.0, 0.5))),
                0, "minecraft:stone_bricks");

        MazePlacer.place(new TestScheduler(), world, m, 0, 64, 0, 0f, 0, r -> {});

        assertEquals(1, world.removals.size(), "tagged entities are cleared from the region first");
        assertTrue(world.removals.get(0).startsWith("litemazica "));
        assertEquals(1, world.merges.size());
        assertTrue(world.merges.get(0).endsWith("{Text1:\"\\\"Hi\\\"\"}"), world.merges.get(0));
        assertEquals(1, world.summons.size());
        assertTrue(world.summons.get(0).startsWith("minecraft:armor_stand "));
    }
}
