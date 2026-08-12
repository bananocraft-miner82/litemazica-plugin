package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import static app.litemazica.core.maze.TerrainCategory.AIR;
import static app.litemazica.core.maze.TerrainCategory.CLEARABLE;
import static app.litemazica.core.maze.TerrainCategory.SOLID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The block classifier that decides what the "blend into terrain" pass does with
 * a block above an open-top maze. The tricky cases are the ones that share a
 * substring with a plant but are really solid ground (grass_block, snow_block).
 */
class TerrainCategoryTest
{
    @Test
    void treatsAirVariantsAsAir()
    {
        assertEquals(AIR, TerrainCategory.of("minecraft:air"));
        assertEquals(AIR, TerrainCategory.of("minecraft:cave_air"));
        assertEquals(AIR, TerrainCategory.of("minecraft:void_air"));
        assertEquals(AIR, TerrainCategory.of(""));
        assertEquals(AIR, TerrainCategory.of(null));
    }

    @Test
    void clearsTreesPlantsAndLiquids()
    {
        for (String block : new String[]{
                "minecraft:oak_log[axis=y]", "minecraft:birch_wood", "minecraft:spruce_leaves[distance=1]",
                "minecraft:water[level=0]", "minecraft:lava", "minecraft:vine", "minecraft:sugar_cane",
                "minecraft:tall_grass[half=lower]", "minecraft:short_grass", "minecraft:fern",
                "minecraft:snow[layers=3]", "minecraft:kelp", "minecraft:oak_sapling",
                "minecraft:red_mushroom_block", "minecraft:mushroom_stem", "minecraft:mangrove_roots",
                "minecraft:muddy_mangrove_roots", "minecraft:dandelion", "minecraft:cactus",
                // Small ground mushrooms and other tall ground cover that used to
                // be missed by the blend pass.
                "minecraft:red_mushroom", "minecraft:brown_mushroom",
                "minecraft:bamboo[age=0]", "minecraft:bamboo_sapling", "minecraft:sugar_cane[age=3]",
        })
        {
            assertEquals(CLEARABLE, TerrainCategory.of(block), block);
        }
    }

    @Test
    void recognisesTreePartsForWholeTreeRemoval()
    {
        for (String tree : new String[]{
                "minecraft:oak_log[axis=y]", "minecraft:birch_wood", "minecraft:jungle_leaves[distance=3]",
                "minecraft:mushroom_stem", "minecraft:red_mushroom_block",
        })
        {
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.isTreePart(tree), tree);
        }

        for (String notTree : new String[]{
                "minecraft:water", "minecraft:tall_grass[half=lower]", "minecraft:dandelion",
                "minecraft:snow[layers=2]", "minecraft:stone", "minecraft:oak_planks",
                // Small ground mushrooms are ground cover, not a tree the flood
                // should wait on — they're cleared block-by-block.
                "minecraft:red_mushroom", "minecraft:brown_mushroom",
                "minecraft:bamboo[age=0]", "minecraft:sugar_cane", "minecraft:mangrove_roots",
        })
        {
            org.junit.jupiter.api.Assertions.assertFalse(TerrainCategory.isTreePart(notTree), notTree);
        }
    }

    @Test
    void followsHugeMushroomCapsButNotSmallMushroomsInTheFlood()
    {
        // Caps never decay, so the flood must travel into them and pull them out.
        for (String cap : new String[]{
                "minecraft:red_mushroom_block", "minecraft:brown_mushroom_block",
        })
        {
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.isMushroomCap(cap), cap);
        }

        // A bare ground mushroom (and the stem, handled as a log) is not a cap.
        for (String notCap : new String[]{
                "minecraft:red_mushroom", "minecraft:brown_mushroom", "minecraft:mushroom_stem",
                "minecraft:oak_leaves", "minecraft:stone",
        })
        {
            org.junit.jupiter.api.Assertions.assertFalse(TerrainCategory.isMushroomCap(notCap), notCap);
        }
    }

    @Test
    void distinguishesTrunksFromCanopy()
    {
        // Only trunks seed a tree removal; leaves/caps are canopy (still tree
        // parts for the flood to travel through, but not seeds).
        for (String log : new String[]{
                "minecraft:oak_log[axis=y]", "minecraft:stripped_birch_log", "minecraft:jungle_wood",
                "minecraft:warped_stem", "minecraft:mushroom_stem",
        })
        {
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.isLog(log), log);
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.isTreePart(log), log);
        }

        for (String canopy : new String[]{
                "minecraft:oak_leaves[distance=1]", "minecraft:red_mushroom_block",
        })
        {
            org.junit.jupiter.api.Assertions.assertFalse(TerrainCategory.isLog(canopy), canopy);
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.isTreePart(canopy), canopy);
        }
    }

    @Test
    void flagsFluidsAndFallingBlocksForAPostRestoreUpdate()
    {
        for (String block : new String[]{
                "minecraft:water[level=0]", "minecraft:water[level=3]", "minecraft:lava[level=0]",
                "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
                "minecraft:white_concrete_powder", "minecraft:chipped_anvil[facing=north]",
                "minecraft:pointed_dripstone[thickness=tip]", "minecraft:scaffolding[bottom=false]",
        })
        {
            org.junit.jupiter.api.Assertions.assertTrue(TerrainCategory.needsRestoreUpdate(block), block);
        }

        for (String block : new String[]{
                "minecraft:stone", "minecraft:dirt", "minecraft:sandstone", "minecraft:concrete",
                "minecraft:oak_stairs[waterlogged=true]", "minecraft:air",
        })
        {
            org.junit.jupiter.api.Assertions.assertFalse(TerrainCategory.needsRestoreUpdate(block), block);
        }
    }

    @Test
    void keepsNaturalGroundAndLookalikesSolid()
    {
        for (String block : new String[]{
                "minecraft:stone", "minecraft:dirt", "minecraft:gravel", "minecraft:sand",
                "minecraft:deepslate", "minecraft:iron_ore", "minecraft:andesite",
                // Share a substring with a plant, but are solid ground.
                "minecraft:grass_block[snowy=false]", "minecraft:snow_block", "minecraft:nether_wart_block",
                // Unknown / player blocks are capped-under, never deleted.
                "minecraft:some_future_block", "minecraft:oak_planks",
        })
        {
            assertEquals(SOLID, TerrainCategory.of(block), block);
        }
    }
}
