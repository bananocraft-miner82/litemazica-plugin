package app.litemazica.core.platform;

import app.litemazica.core.maze.Region;

import java.util.List;

/**
 * One loaded world, addressed by block coordinate.
 *
 * <p>Blocks cross this boundary as block-state <em>strings</em>
 * ({@code "minecraft:oak_stairs[facing=east,half=bottom]"}) rather than as any
 * platform type. Bukkit parses those with {@code createBlockData}, vanilla with
 * {@code BlockArgumentParser}, so core never needs to know what a block is.
 *
 * <p>Block entities cross it as opaque byte blobs, because the platforms differ
 * completely: Bukkit has no NBT API and needs a per-type workaround, while
 * Fabric/NeoForge can hand over the block entity's real NBT.
 */
public interface WorldAccess
{
    String name();

    /** Lowest buildable Y (inclusive). */
    int minY();

    /** Highest buildable Y (exclusive). */
    int maxY();

    /**
     * Parses a palette once, ahead of the placement loop, rotating every entry
     * by {@code quarterTurnsClockwise} (0–3) so directional blocks keep facing
     * the right way. Parsing per voxel would be ruinous.
     */
    PreparedPalette preparePalette(List<String> palette, int quarterTurnsClockwise);

    /** Writes {@code palette[index]} at the position, without block updates. */
    void setBlock(int x, int y, int z, PreparedPalette palette, int index);

    /**
     * Writes a single block-state string ({@code "minecraft:chest[facing=east]"})
     * at the position, without block updates. For the handful of post-placement
     * trap edits that aren't in the palette (disarming a trapped chest, clearing a
     * pressure plate); the per-voxel body uses {@link #setBlock} instead. A no-op
     * if the string won't parse on this server.
     */
    void setBlockState(int x, int y, int z, String blockState);

    /**
     * Sets a position to air <em>with</em> a neighbour-notifying update — the same
     * thing that happens when a player breaks a block. Used at the edge of a
     * tree-removal flood so the canopy left just beyond it (which we can't reach
     * without editing outside the snapshot) recomputes its distance-to-log and
     * decays on its own, instead of hanging there frozen.
     */
    void clearWithPhysics(int x, int y, int z);

    /**
     * Re-triggers a block/physics update at a position, so a block placed earlier
     * without updates comes back to life: restored water resumes flowing, and an
     * unsupported falling block (sand, gravel …) drops. Used only after a terrain
     * restore — placement stays update-free on purpose.
     */
    void updateBlock(int x, int y, int z);

    /** The block-state string at the position, for snapshotting. */
    String blockStateAt(int x, int y, int z);

    /** Positions of every block entity inside the region. */
    List<BlockPos> blockEntitiesIn(Region region);

    /** Restorable contents at a position, or null if there's nothing worth keeping. */
    byte[] captureBlockEntity(int x, int y, int z);

    /** Re-applies a blob previously produced by {@link #captureBlockEntity}. */
    void restoreBlockEntity(int x, int y, int z, byte[] blob);

    /** Marks a container to roll loot from {@code lootTableId} when first opened. */
    void applyLootTable(int x, int y, int z, String lootTableId, long seed);

    /**
     * Loads a placed dispenser with the given trap items — an arrow volley or a
     * thrown-potion charge. Each {@link DispenserItem} names a slot, item, count,
     * and optional potion. A no-op if the block isn't a dispenser.
     */
    void loadDispenser(int x, int y, int z, List<DispenserItem> items);

    /**
     * Arms a placed mob spawner from {@code config} — the mob it spews and its
     * cadence. A no-op if the block isn't a spawner.
     */
    void configureSpawner(int x, int y, int z, SpawnerConfig config);
}
