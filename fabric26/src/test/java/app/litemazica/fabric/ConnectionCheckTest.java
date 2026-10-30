package app.litemazica.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks the web app's fences, walls, panes, bars and gates against the game's
 * own rules. The fixtures are grids the app joined up (`npm run
 * connections:fixtures` in Litemapica writes them); for each of those blocks
 * the game works out what it would become on a block update, from the same
 * neighbours, and that has to be the state the app chose. In the whole mazes
 * (the maze-* files) the torches, lanterns, ladders, lichen, signs, levers and
 * buttons have to stay put too, as the game drops them when what holds them
 * can't. In the flooded ones (maze-underwater*) no water or waterlogged block
 * may have open space beside or under it, which its water would run into.
 * Other blocks the game would change are listed, not failed (in the test
 * pieces, which float, plenty would).
 */
class ConnectionCheckTest
{
    private static final Path FIXTURES = Path.of("src/test/resources/connections");

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException
    {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Which blocks are fences or walls comes from tags in the vanilla data
        // pack, which a server loads with its world: load them here instead.
        // Block and fluid tags are all the blocks ask about.
        try (var data = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanillaPack())))
        {
            int blockTags = bindTags(data, BuiltInRegistries.BLOCK);
            bindTags(data, BuiltInRegistries.FLUID);
            assertTrue(blockTags > 0, "the vanilla block tags loaded");
        }
    }

    /**
     * The vanilla data pack. In 26.2 the vanilla pack is one; from 26.3 it
     * hands one out (fullResources), so this asks whichever this build has.
     */
    private static PackResources vanillaPack() throws ReflectiveOperationException
    {
        Object pack = ServerPacksSource.createVanillaPackSource();
        return pack instanceof PackResources resources ? resources : (PackResources) pack.getClass().getMethod("fullResources").invoke(pack);
    }

    /** Loads a registry's tags from the data pack and binds them, then freezes it as a started server would. */
    private static <T> int bindTags(ResourceManager data, Registry<T> registry)
    {
        MappedRegistry<T> mapped = (MappedRegistry<T>) registry;
        var tags = TagLoader.loadTagsForRegistry(data, mapped.key(), TagLoader.ElementLookup.fromWritableRegistry(mapped));
        mapped.bindTags(tags);
        // Tags reach the blocks themselves when the registry freezes, as it would once a server had started.
        mapped.freeze();
        return tags.size();
    }

    @TestFactory
    Stream<DynamicTest> everyFixture() throws IOException
    {
        try (Stream<Path> files = Files.list(FIXTURES))
        {
            List<Path> found = files.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
            return found.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(Grid.read(p))));
        }
    }

    private static boolean isJoined(BlockState state)
    {
        Block block = state.getBlock();
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock || block instanceof FenceGateBlock;
    }

    /** Lights, ladders, signs, levers and buttons: the game drops them when what holds them can't. */
    private static boolean isHeld(BlockState state)
    {
        Block block = state.getBlock();
        return block instanceof BaseTorchBlock || block instanceof LadderBlock || block instanceof GlowLichenBlock
                || block instanceof LanternBlock || block instanceof WallSignBlock || block instanceof FaceAttachedHorizontalDirectionalBlock;
    }

    private static void check(Grid grid)
    {
        LevelAccessor level = grid.level();
        List<String> wrong = new ArrayList<>();
        Map<String, Integer> elsewhere = new TreeMap<>();
        int checked = 0;
        int held = 0;
        // In a whole maze (not the test pieces, which float), what hangs or stands on the walls must stay up too.
        boolean maze = grid.name.startsWith("maze-");
        // In a flooded one, no water (nor a waterlogged block) may have open space beside or under it.
        boolean flooded = grid.name.startsWith("maze-underwater");
        int wet = 0;

        for (int y = 0; y < grid.sizeY; y++)
        {
            for (int z = 0; z < grid.sizeZ; z++)
            {
                for (int x = 0; x < grid.sizeX; x++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState ours = grid.get(pos);
                    if (ours.isAir())
                    {
                        continue;
                    }
                    if (flooded && !ours.getFluidState().isEmpty())
                    {
                        wet++;
                        String leak = leakFrom(grid, pos);
                        if (leak != null)
                        {
                            wrong.add(pos.toShortString() + ": " + ours + " has open " + leak + " of it, so its water would run out");
                        }
                    }
                    boolean asserted = isJoined(ours) || (maze && isHeld(ours));
                    BlockState game;
                    try
                    {
                        game = Block.updateFromNeighbourShapes(ours, level, pos);
                    }
                    catch (RuntimeException e)
                    {
                        // Lava, for one, asks about the dimension, which the stand-in level hasn't got.
                        if (asserted)
                        {
                            throw e;
                        }
                        elsewhere.merge("unchecked " + BuiltInRegistries.BLOCK.getKey(ours.getBlock()).getPath(), 1, Integer::sum);
                        continue;
                    }
                    if (asserted)
                    {
                        if (isJoined(ours))
                        {
                            checked++;
                        }
                        else
                        {
                            held++;
                        }
                        if (!game.equals(ours))
                        {
                            wrong.add(pos.toShortString() + ": app " + ours + ", game " + game + around(grid, pos));
                        }
                    }
                    else if (!game.equals(ours))
                    {
                        elsewhere.merge(BuiltInRegistries.BLOCK.getKey(ours.getBlock()).getPath(), 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println(grid.name + ": " + checked + " joined blocks checked" + (maze ? ", " + held + " held up" : "")
                + (flooded ? ", " + wet + " wet" : "") + ", " + wrong.size() + " wrong"
                + (elsewhere.isEmpty() ? "" : "; other blocks the game would change: " + elsewhere));
        wrong.stream().limit(25).forEach(System.out::println);
        assertEquals(0, wrong.size(), grid.name + ": " + wrong.size() + " blocks the game would change, or water would run out of (listed above)");
    }

    /**
     * The side (or "below") of a wet block where there's open space its water
     * would run into, or null: air, structure void (where the world shows
     * through), or past the edge of the grid. Water never runs up.
     */
    private static String leakFrom(Grid grid, BlockPos pos)
    {
        for (var dir : new net.minecraft.core.Direction[] { net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.DOWN })
        {
            BlockPos next = pos.relative(dir);
            boolean outside = next.getX() < 0 || next.getY() < 0 || next.getZ() < 0
                    || next.getX() >= grid.sizeX || next.getY() >= grid.sizeY || next.getZ() >= grid.sizeZ;
            BlockState there = grid.get(next);
            if (outside || there.isAir() || there.is(Blocks.STRUCTURE_VOID))
            {
                return dir == net.minecraft.core.Direction.DOWN ? "space below" : "space " + dir.getName();
            }
        }
        return null;
    }

    /** What stands round a block, for reading a mismatch. */
    private static String around(Grid grid, BlockPos pos)
    {
        StringBuilder text = new StringBuilder();
        for (var dir : net.minecraft.core.Direction.values())
        {
            BlockState next = grid.get(pos.relative(dir));
            if (!next.isAir())
            {
                text.append("\n    ").append(dir.getName()).append(": ").append(next);
            }
        }
        return text.toString();
    }

    /** A fixture: its size, palette and voxels (y, then z, then x). */
    private static final class Grid
    {
        final String name;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final BlockState[] palette;
        final int[] data;

        private Grid(String name, int sizeX, int sizeY, int sizeZ, BlockState[] palette, int[] data)
        {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.palette = palette;
            this.data = data;
        }

        static Grid read(Path path) throws Exception
        {
            List<String> lines = Files.readAllLines(path);
            String[] size = lines.get(0).split(" ");
            int count = Integer.parseInt(lines.get(1).split(" ")[1]);
            BlockState[] palette = new BlockState[count];
            for (int i = 0; i < count; i++)
            {
                palette[i] = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, lines.get(2 + i), false).blockState();
            }
            byte[] bytes = Base64.getDecoder().decode(lines.get(2 + count).substring("data ".length()));
            int[] data = new int[bytes.length / 2];
            for (int i = 0; i < data.length; i++)
            {
                data[i] = (bytes[2 * i] & 0xff) | (bytes[2 * i + 1] & 0xff) << 8;
            }
            String name = path.getFileName().toString().replace(".txt", "");
            return new Grid(name, Integer.parseInt(size[1]), Integer.parseInt(size[2]), Integer.parseInt(size[3]), palette, data);
        }

        BlockState get(BlockPos pos)
        {
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= sizeX || pos.getY() >= sizeY || pos.getZ() >= sizeZ)
            {
                return Blocks.AIR.defaultBlockState();
            }
            return palette[data[(pos.getY() * sizeZ + pos.getZ()) * sizeX + pos.getX()]];
        }

        /**
         * The grid as a level the blocks can look round, with nothing else in
         * it: no ticks are kept, and anything it isn't asked about is empty.
         */
        LevelAccessor level()
        {
            RandomSource random = RandomSource.create(0);
            InvocationHandler handler = (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getBlockState":
                        return get((BlockPos) args[0]);
                    case "getFluidState":
                        return get((BlockPos) args[0]).getFluidState();
                    case "getRandom":
                        return random;
                    case "isClientSide":
                        return false;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "fixture " + name;
                    default:
                        break;
                }
                if (method.getName().startsWith("scheduleTick"))
                {
                    return null;
                }
                if (method.isDefault())
                {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                return zero(method.getReturnType());
            };
            return (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[] { LevelAccessor.class }, handler);
        }

        private static Object zero(Class<?> type)
        {
            if (type == boolean.class)
            {
                return false;
            }
            if (type == int.class || type == short.class || type == byte.class || type == char.class)
            {
                return type == char.class ? (Object) (char) 0 : (Object) 0;
            }
            if (type == long.class)
            {
                return 0L;
            }
            if (type == float.class)
            {
                return 0f;
            }
            if (type == double.class)
            {
                return 0d;
            }
            return null;
        }
    }
}
