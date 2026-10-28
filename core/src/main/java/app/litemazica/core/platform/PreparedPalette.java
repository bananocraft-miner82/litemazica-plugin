package app.litemazica.core.platform;

import java.util.List;

/**
 * A palette already parsed and rotated into whatever the platform places.
 * Opaque to core, which only ever indexes into it.
 */
public interface PreparedPalette
{
    int size();

    /** The palette's blocks this server doesn't have (placed as air), in palette order. */
    default List<String> unknownBlocks()
    {
        return List.of();
    }
}
