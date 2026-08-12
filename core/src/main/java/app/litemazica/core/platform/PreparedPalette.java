package app.litemazica.core.platform;

/**
 * A palette already parsed and rotated into whatever the platform places.
 * Opaque to core, which only ever indexes into it.
 */
public interface PreparedPalette
{
    int size();
}
