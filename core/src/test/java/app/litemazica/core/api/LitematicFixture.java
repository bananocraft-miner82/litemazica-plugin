package app.litemazica.core.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Builds minimal {@code .litematic} NBT for the parser tests. The plugin ships
 * an {@link app.litemazica.core.nbt.NbtReader} but no writer, so this hand-writes
 * just the tags {@link SchematicParser} reads: one region with a size, a block
 * palette, packed block states, and an (empty) tile-entity list.
 */
public final class LitematicFixture
{
    private LitematicFixture()
    {
    }

    /** A well-formed single-region litematic, gzipped like Litematica writes it. */
    public static byte[] gzipped(String name, int dataVersion, int totalBlocks,
                                 int sizeX, int sizeY, int sizeZ, List<String> palette, long[] blockStates)
    {
        return bytes(name, dataVersion, totalBlocks, sizeX, sizeY, sizeZ, palette, blockStates, true);
    }

    /** The same content, left uncompressed — for the parser's gzip-sniffing path. */
    public static byte[] raw(String name, int dataVersion, int totalBlocks,
                             int sizeX, int sizeY, int sizeZ, List<String> palette, long[] blockStates)
    {
        return bytes(name, dataVersion, totalBlocks, sizeX, sizeY, sizeZ, palette, blockStates, false);
    }

    /** Packs palette indices into Litematica's fixed-width bit stream. */
    public static long[] pack(int[] values, int bits)
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

    private static byte[] bytes(String name, int dataVersion, int totalBlocks,
                                int sizeX, int sizeY, int sizeZ, List<String> palette, long[] blockStates,
                                boolean gzip)
    {
        try
        {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(raw);

            // Root: an unnamed compound.
            o.writeByte(10);
            o.writeUTF("");

            namedInt(o, "MinecraftDataVersion", dataVersion);

            // Metadata { Name, TotalBlocks }
            compoundStart(o, "Metadata");
            namedString(o, "Name", name);
            namedInt(o, "TotalBlocks", totalBlocks);
            end(o);

            // Regions { main { Size, BlockStatePalette, BlockStates, TileEntities } }
            compoundStart(o, "Regions");
            compoundStart(o, "main");

            compoundStart(o, "Size");
            namedInt(o, "x", sizeX);
            namedInt(o, "y", sizeY);
            namedInt(o, "z", sizeZ);
            end(o);

            // BlockStatePalette: list of compounds, each just { Name }.
            o.writeByte(9);
            o.writeUTF("BlockStatePalette");
            o.writeByte(10); // element type: compound
            o.writeInt(palette.size());

            for (String block : palette)
            {
                namedString(o, "Name", block);
                end(o);
            }

            // BlockStates: long array.
            o.writeByte(12);
            o.writeUTF("BlockStates");
            o.writeInt(blockStates.length);

            for (long l : blockStates)
            {
                o.writeLong(l);
            }

            // TileEntities: empty list.
            o.writeByte(9);
            o.writeUTF("TileEntities");
            o.writeByte(10);
            o.writeInt(0);

            end(o); // main
            end(o); // Regions
            end(o); // root
            o.flush();

            byte[] plain = raw.toByteArray();

            if (!gzip)
            {
                return plain;
            }

            ByteArrayOutputStream gz = new ByteArrayOutputStream();

            try (GZIPOutputStream out = new GZIPOutputStream(gz))
            {
                out.write(plain);
            }

            return gz.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e); // ByteArrayOutputStream can't fail
        }
    }

    private static void compoundStart(DataOutputStream o, String name) throws IOException
    {
        o.writeByte(10);
        o.writeUTF(name);
    }

    private static void namedInt(DataOutputStream o, String name, int value) throws IOException
    {
        o.writeByte(3);
        o.writeUTF(name);
        o.writeInt(value);
    }

    private static void namedString(DataOutputStream o, String name, String value) throws IOException
    {
        o.writeByte(8);
        o.writeUTF(name);
        o.writeUTF(value);
    }

    private static void end(DataOutputStream o) throws IOException
    {
        o.writeByte(0);
    }
}
