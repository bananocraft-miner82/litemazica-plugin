package app.litemazica.core.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal big-endian Java-edition NBT reader covering every tag the
 * Litemazica .litematic writer emits. Verified against the Phase-1 spike
 * (poc/MazeFetchPoc.java): palette, block-entities, and the bit-packed
 * BlockStates all round-trip. Compounds become {@link LinkedHashMap}, lists
 * become {@link List}, and array tags become primitive arrays.
 *
 * <p>Deliberately dependency-free so the plugin needs no shaded NBT library.
 */
public final class NbtReader
{
    private final DataInputStream in;

    public NbtReader(byte[] bytes)
    {
        this.in = new DataInputStream(new ByteArrayInputStream(bytes));
    }

    /** Reads the root tag (an unnamed compound in a .litematic). */
    public Object readRoot() throws IOException
    {
        int type = in.readByte();

        if (type == 0)
        {
            return null;
        }

        in.readUTF(); // root name (empty for litematic)

        return readPayload(type);
    }

    private Object readPayload(int type) throws IOException
    {
        switch (type)
        {
            case 1:  return in.readByte();
            case 2:  return in.readShort();
            case 3:  return in.readInt();
            case 4:  return in.readLong();
            case 5:  return in.readFloat();
            case 6:  return in.readDouble();
            case 7:
            { // TAG_Byte_Array
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8:  return in.readUTF();
            case 9:
            {
                // TAG_List
                int elem = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));

                for (int i = 0; i < len; i++)
                {
                    list.add(readPayload(elem));
                }

                return list;
            }
            case 10:
            {
                // TAG_Compound
                Map<String, Object> map = new LinkedHashMap<>();

                for (;;)
                {
                    int t = in.readByte();

                    if (t == 0)
                    {
                        break; // TAG_End
                    }

                    String name = in.readUTF();
                    map.put(name, readPayload(t));
                }

                return map;
            }
            case 11:
            {
                // TAG_Int_Array
                int[] a = new int[in.readInt()];

                for (int i = 0; i < a.length; i++)
                {
                    a[i] = in.readInt();
                }

                return a;
            }
            case 12:
            {
                // TAG_Long_Array
                long[] a = new long[in.readInt()];

                for (int i = 0; i < a.length; i++)
                {
                    a[i] = in.readLong();
                }

                return a;
            }
            default:
                throw new IOException("unknown NBT tag id " + type);
        }
    }
}
