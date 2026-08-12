package app.litemazica.core.maze;

import java.util.Locale;

/**
 * Classifies a world block (by its block-state string) into what the
 * "blend top into terrain" pass should do with it when it sits above an
 * open-top maze:
 *
 * <ul>
 *   <li>{@link #AIR} — nothing there; skip.</li>
 *   <li>{@link #CLEARABLE} — tree/plant/liquid (logs, leaves, water, vines,
 *   snow layers …): removed so a canopy or pond doesn't poke into the maze.</li>
 *   <li>{@link #SOLID} — natural ground (stone, dirt, ore …) or anything not
 *   recognised: the maze is capped <em>under</em> it and it's left in place.</li>
 * </ul>
 *
 * <p>Matching is by name substring on the block id, which is coarse but keeps
 * the whole thing platform-neutral (the plugin already works purely on
 * block-state strings). Unknown blocks are treated as {@link #SOLID} on purpose:
 * capping under a block we don't understand is safe, deleting it is not — that
 * keeps a player's build above a maze from being erased.
 */
public enum TerrainCategory
{
    AIR,
    CLEARABLE,
    SOLID;

    /** Substrings that make a block clearable vegetation/liquid. */
    private static final String[] CLEARABLE_PARTS = {
            "log", "wood", "leaves", "sapling", "_stem", "mushroom", "fungus",
            "vine", "roots", "sprouts", "water", "lava", "kelp", "seagrass",
            "sea_pickle", "lily_pad", "bamboo", "sugar_cane", "cactus", "wart",
            "flower", "tulip", "rose", "orchid", "dandelion", "poppy", "allium",
            "cornflower", "lily_of_the_valley", "azalea", "moss_carpet", "fern",
            "grass", "dead_bush", "lichen", "spore_blossom", "dripleaf",
            "pitcher", "berry", "snow", "lilac", "peony", "sunflower", "nether_sprouts",
            "leaf_litter", "pink_petals",
    };

    /**
     * Blocks that contain a {@link #CLEARABLE_PARTS} substring but are really
     * solid ground and must be capped-under, not deleted (e.g. {@code
     * grass_block} contains "grass"; {@code snow_block} contains "snow").
     */
    private static final String[] SOLID_EXCEPTIONS = {
            "grass_block", "snow_block", "wart_block",
    };

    /** Blocks with nothing to place. */
    private static final String[] AIRS = {
            "air", "cave_air", "void_air",
    };

    /**
     * The subset of {@link #CLEARABLE_PARTS} that makes up a tree (for whole-tree
     * flood removal). Note {@code mushroom_block} (a huge-mushroom cap), not the
     * bare {@code mushroom} — a {@code red_mushroom}/{@code brown_mushroom} on the
     * ground is ground cover, cleared block-by-block, not a tree left to the
     * flood. The huge-mushroom stem is already covered by {@code _stem}.
     */
    private static final String[] TREE_PARTS = {
            "log", "wood", "leaves", "_stem", "mushroom_block",
    };

    /** Trunk/stem parts — a tree is removed only when one of these sits over the maze. */
    private static final String[] LOG_PARTS = {"log", "wood", "_stem"};

    /**
     * Whether a block is part of a tree — a log, leaves, or a huge-mushroom
     * stem/cap. The blend flood-fills whole connected trees through these, so an
     * overhanging canopy doesn't leave a half-eaten trunk behind. Ground cover
     * (grass, flowers, water …) is cleared block-by-block instead, so this is
     * deliberately narrow.
     */
    public static boolean isTreePart(String blockData)
    {
        String name = normalize(blockData);

        for (String part : TREE_PARTS)
        {
            if (name.contains(part))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether a block is a tree <em>trunk</em> (log/wood/stem), as opposed to its
     * canopy. Removal is seeded only from trunks that sit over the maze, so a
     * tree merely overhanging from beside the maze is left standing — its whole
     * body, not just the part above the footprint.
     */
    public static boolean isLog(String blockData)
    {
        String name = normalize(blockData);

        for (String part : LOG_PARTS)
        {
            if (name.contains(part))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * A huge-mushroom cap ({@code red_/brown_mushroom_block}). Structurally it's
     * load-bearing like a trunk, but unlike leaves it never decays — so once the
     * stem it sat on is gone it just hangs in the air. The tree flood therefore
     * has to travel into and remove caps explicitly, rather than leaving them to
     * vanilla physics the way it does with leaves. (The stem is an {@link #isLog
     * log} via {@code _stem}, so it's followed already.)
     */
    public static boolean isMushroomCap(String blockData)
    {
        return normalize(blockData).contains("mushroom_block");
    }

    /** Falling blocks (exact ids) that need a physics kick to drop after a restore. */
    private static final String[] FALLING = {
            "sand", "red_sand", "suspicious_sand", "gravel", "suspicious_gravel",
            "dragon_egg", "pointed_dripstone", "scaffolding",
    };

    /**
     * Whether a restored block needs a physics update to come back to life — a
     * fluid that should resume flowing, or a falling block that should drop.
     * Terrain is written without block updates (fast, no cascades mid-write), so
     * these have to be nudged afterwards or they sit frozen.
     */
    public static boolean needsRestoreUpdate(String blockData)
    {
        String name = normalize(blockData);

        if (name.equals("water") || name.equals("lava"))
        {
            return true; // fluids (flowing or source) resume flowing
        }

        if (name.contains("concrete_powder") || name.endsWith("anvil"))
        {
            return true;
        }

        for (String falling : FALLING)
        {
            if (name.equals(falling))
            {
                return true;
            }
        }

        return false;
    }

    /** Strips [properties] and the namespace and lower-cases, for substring matching. */
    private static String normalize(String blockData)
    {
        String name = blockData;
        int bracket = name.indexOf('[');

        if (bracket >= 0)
        {
            name = name.substring(0, bracket);
        }

        int colon = name.indexOf(':');

        if (colon >= 0)
        {
            name = name.substring(colon + 1);
        }

        return name.toLowerCase(Locale.ROOT);
    }

    /** Classifies a block-state string such as {@code "minecraft:oak_log[axis=y]"}. */
    public static TerrainCategory of(String blockData)
    {
        if (blockData == null || blockData.isEmpty())
        {
            return AIR;
        }

        String name = normalize(blockData);

        for (String air : AIRS)
        {
            if (name.equals(air))
            {
                return AIR;
            }
        }

        for (String solid : SOLID_EXCEPTIONS)
        {
            if (name.contains(solid))
            {
                return SOLID;
            }
        }

        for (String part : CLEARABLE_PARTS)
        {
            if (name.contains(part))
            {
                return CLEARABLE;
            }
        }

        return SOLID;
    }
}
