package app.litemazica.core.maze;

/** An inclusive block-space bounding box in a single world. */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
{
    public boolean contains(int x, int y, int z)
    {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** True if this box shares any block with {@code other} (inclusive bounds). */
    public boolean intersects(Region other)
    {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public long volume()
    {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public String sizeString()
    {
        return (maxX - minX + 1) + "×" + (maxY - minY + 1) + "×" + (maxZ - minZ + 1);
    }
}
