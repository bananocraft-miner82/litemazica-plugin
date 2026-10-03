package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.nbt.Snbt;
import app.litemazica.core.platform.WorldAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.litemazica.core.maze.PlacementGeometry.rotX;
import static app.litemazica.core.maze.PlacementGeometry.rotZ;

/**
 * The contents a custom build brings beyond its blocks: block-entity data
 * (sign text, chest and lectern contents, banner patterns …) and entities
 * (item frames, paintings, armour stands). Placement writes bare blocks, and
 * {@link TrapArming} only fills in the maze's own trap and loot data, so this
 * pass hands the rest to the world as exact NBT, rotated with the placement.
 *
 * <p>Everything here was already put through the web app's safety pass by the
 * API (command blocks removed, items cleaned, entities limited to decorations);
 * this pass still refuses any entity kind but those four and skips the block
 * entities other passes own.
 */
final class BuildContents
{
    /** Tag on every entity this plugin spawns, so a rebuild or clear can remove them again. */
    static final String ENTITY_TAG = "litemazica";

    /** The only entities ever spawned: decorations. */
    static final Set<String> ENTITY_KINDS = Set.of(
            "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting", "minecraft:armor_stand");

    /** Block entities another pass owns (TrapArming), or that are never applied from a schematic. */
    private static final Set<String> SKIP_BLOCK_ENTITIES = Set.of(
            "minecraft:mob_spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:dispenser",
            "minecraft:command_block", "minecraft:structure_block", "minecraft:jigsaw");

    /** Keys describing where something is, which the world sets itself. */
    private static final Set<String> POSITION_KEYS = Set.of(
            "id", "x", "y", "z", "Pos", "UUID", "TileX", "TileY", "TileZ", "block_pos", "Motion", "Tags");

    /** Item frames face one of the six 3D directions: 2 north, 3 south, 4 west, 5 east. */
    private static final int[] FACING_3D_CW = {0, 1, 5, 4, 2, 3};

    private BuildContents()
    {
    }

    /** One block entity's data to merge into the block at a world position. */
    record BlockEntityMerge(int x, int y, int z, String snbt)
    {
    }

    /** One entity to spawn at a world position. */
    record EntitySpawn(String id, double x, double y, double z, String snbt)
    {
    }

    /** Merges every build block entity and spawns every build entity. */
    static void apply(WorldAccess world, MazeSchematic maze, int rot, int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        for (BlockEntityMerge m : blockEntityMerges(maze, rot, offX, offZ, baseY, sizeX, sizeZ))
        {
            world.mergeBlockEntity(m.x(), m.y(), m.z(), m.snbt());
        }

        for (EntitySpawn s : entitySpawns(maze, rot, offX, offZ, baseY, sizeX, sizeZ))
        {
            world.summonEntity(s.id(), s.x(), s.y(), s.z(), s.snbt());
        }
    }

    /**
     * The block-entity data to merge: every block entity with something beyond
     * its position, except the kinds other passes own and loot containers
     * (TrapArming rolls their loot table). Package-private for tests.
     */
    static List<BlockEntityMerge> blockEntityMerges(MazeSchematic maze, int rot, int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        List<BlockEntityMerge> out = new ArrayList<>();

        for (Map<String, Object> te : maze.tileEntities())
        {
            if (SKIP_BLOCK_ENTITIES.contains(te.get("id")) || te.get("LootTable") instanceof String)
            {
                continue;
            }

            Map<String, Object> data = withoutPosition(te);

            if (data.isEmpty())
            {
                continue;
            }

            int lx = asInt(te.get("x"));
            int lz = asInt(te.get("z"));
            out.add(new BlockEntityMerge(
                    offX + rotX(lx, lz, rot, sizeX, sizeZ),
                    baseY + asInt(te.get("y")),
                    offZ + rotZ(lx, lz, rot, sizeX, sizeZ),
                    Snbt.write(data)));
        }

        return out;
    }

    /**
     * The entities to spawn, each turned with the placement: its position (a
     * continuous point, so it turns about the box corners), its yaw and the
     * wall it faces. Each is tagged {@link #ENTITY_TAG}. Package-private for tests.
     */
    static List<EntitySpawn> entitySpawns(MazeSchematic maze, int rot, int offX, int offZ, int baseY, int sizeX, int sizeZ)
    {
        List<EntitySpawn> out = new ArrayList<>();

        for (Map<String, Object> e : maze.entities())
        {
            if (!(e.get("id") instanceof String id) || !ENTITY_KINDS.contains(id))
            {
                continue;
            }

            if (!(e.get("Pos") instanceof List<?> pos) || pos.size() < 3)
            {
                continue;
            }

            double px = asDouble(pos.get(0));
            double py = asDouble(pos.get(1));
            double pz = asDouble(pos.get(2));

            int[] tile = attachmentBlock(e);
            if (tile != null)
            {
                // A frame or painting is summoned at the centre of the block it
                // hangs in: the game takes that block from the spawn point and
                // works the exact position out itself. Its stored Pos isn't safe
                // for this, since a two-wide painting's sits on a block edge.
                // Turning the block's centre is the same as turning the block.
                px = tile[0] + 0.5;
                py = tile[1] + 0.5;
                pz = tile[2] + 0.5;
            }

            double wx = switch (rot)
            {
                case 1 -> sizeZ - pz;
                case 2 -> sizeX - px;
                case 3 -> pz;
                default -> px;
            };
            double wz = switch (rot)
            {
                case 1 -> px;
                case 2 -> sizeZ - pz;
                case 3 -> sizeX - px;
                default -> pz;
            };

            Map<String, Object> data = withoutPosition(e);
            turn(id, data, rot);
            data.put("Tags", List.of(ENTITY_TAG));
            out.add(new EntitySpawn(id, offX + wx, baseY + py, offZ + wz, Snbt.write(data)));
        }

        return out;
    }

    /** Turns an entity's yaw and facing by {@code rot} clockwise quarter turns, in place. */
    private static void turn(String id, Map<String, Object> data, int rot)
    {
        if (rot == 0)
        {
            return;
        }

        if (data.get("Rotation") instanceof List<?> rotation && !rotation.isEmpty() && rotation.get(0) instanceof Float yaw)
        {
            // Yaw: 0 faces south, 90 west, 180 north, 270 east; clockwise adds 90.
            List<Object> turned = new ArrayList<>(rotation);
            turned.set(0, (yaw + 90f * rot) % 360f);
            data.put("Rotation", turned);
        }

        if (id.equals("minecraft:painting"))
        {
            // Paintings store a horizontal index: 0 south, 1 west, 2 north, 3 east.
            for (String key : List.of("facing", "Facing"))
            {
                if (data.get(key) instanceof Byte b)
                {
                    data.put(key, (byte) ((b + rot) % 4));
                }
            }
        }
        else if (data.get("Facing") instanceof Byte b && b >= 0 && b < 6)
        {
            int facing = b;
            for (int i = 0; i < rot; i++) facing = FACING_3D_CW[facing];
            data.put("Facing", (byte) facing);
        }
    }

    /**
     * The block a frame or painting hangs in: TileX/Y/Z, or block_pos from
     * 1.21.5 on. Null for other entities, or when it isn't stored.
     */
    private static int[] attachmentBlock(Map<String, Object> e)
    {
        if (e.get("block_pos") instanceof int[] b && b.length == 3)
        {
            return b;
        }

        if (e.get("TileX") instanceof Number x && e.get("TileY") instanceof Number y && e.get("TileZ") instanceof Number z)
        {
            return new int[]{x.intValue(), y.intValue(), z.intValue()};
        }

        return null;
    }

    private static Map<String, Object> withoutPosition(Map<String, Object> source)
    {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : source.entrySet())
        {
            if (!POSITION_KEYS.contains(e.getKey()))
            {
                out.put(e.getKey(), e.getValue());
            }
        }

        return out;
    }

    private static int asInt(Object o)
    {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double asDouble(Object o)
    {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
