package app.litemazica.core.maze;

/**
 * A maze built into the world. Carries everything needed to redraw it on a
 * schedule: where its blocks come from ({@link #sourceType} + {@link #shareCode}
 * — a share code for the API, or a file name for a local schematic), the
 * anchor/yaw it was placed at, its current region, and its regeneration
 * settings. Identity fields are final; the region and regen state change as the
 * maze resets, so this is mutable (and persisted by {@link MazeStore}).
 */
public final class PlacedMaze
{
    private final String id;
    private final String worldName;
    private final String sourceType;
    /**
     * The share code (API) or file name (file source) this maze rebuilds from.
     * Mutable because re-editing a maze in the web editor swaps in a new design
     * code — future resets then reproduce the edited layout.
     */
    private String shareCode;
    private final int anchorX;
    private final int anchorY;
    private final int anchorZ;
    private final float yaw;
    private final long placedAtEpochMs;

    private Region region;
    private int regenMinutes;
    private boolean freshLayout;
    private long lastRegenEpochMs;

    public PlacedMaze(String id, String worldName, String sourceType, String shareCode,
                      int anchorX, int anchorY, int anchorZ, float yaw,
                      Region region, int regenMinutes, boolean freshLayout,
                      long placedAtEpochMs, long lastRegenEpochMs)
    {
        this.id = id;
        this.worldName = worldName;
        this.sourceType = sourceType;
        this.shareCode = shareCode;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.yaw = yaw;
        this.region = region;
        this.regenMinutes = regenMinutes;
        this.freshLayout = freshLayout;
        this.placedAtEpochMs = placedAtEpochMs;
        this.lastRegenEpochMs = lastRegenEpochMs;
    }

    public String id()
    {
        return id;
    }

    public String worldName()
    {
        return worldName;
    }

    /** {@link MazeSource#TYPE_API} or {@link MazeSource#TYPE_FILE}. */
    public String sourceType()
    {
        return sourceType;
    }

    /** The share code (API source) or file name (file source) this maze rebuilds from. */
    public String shareCode()
    {
        return shareCode;
    }

    /** Points the maze at a new design code — used when it's re-edited in the web editor. */
    public void setShareCode(String shareCode)
    {
        this.shareCode = shareCode;
    }

    public boolean isFileSource()
    {
        return MazeSource.TYPE_FILE.equals(sourceType);
    }

    public int anchorX()
    {
        return anchorX;
    }

    public int anchorY()
    {
        return anchorY;
    }

    public int anchorZ()
    {
        return anchorZ;
    }

    public float yaw()
    {
        return yaw;
    }

    public long placedAtEpochMs()
    {
        return placedAtEpochMs;
    }

    public Region region()
    {
        return region;
    }

    public void setRegion(Region region)
    {
        this.region = region;
    }

    public int regenMinutes()
    {
        return regenMinutes;
    }

    public void setRegenMinutes(int regenMinutes)
    {
        this.regenMinutes = regenMinutes;
    }

    public boolean freshLayout()
    {
        return freshLayout;
    }

    public void setFreshLayout(boolean freshLayout)
    {
        this.freshLayout = freshLayout;
    }

    public long lastRegenEpochMs()
    {
        return lastRegenEpochMs;
    }

    public void setLastRegenEpochMs(long lastRegenEpochMs)
    {
        this.lastRegenEpochMs = lastRegenEpochMs;
    }

    public boolean isRegenDue(long now)
    {
        return regenMinutes > 0 && now - lastRegenEpochMs >= regenMinutes * 60_000L;
    }

    // Convenience delegates so callers can treat the maze as its bounding box.
    public int minX()
    {
        return region.minX();
    }

    public int minY()
    {
        return region.minY();
    }

    public int minZ()
    {
        return region.minZ();
    }

    public int maxX()
    {
        return region.maxX();
    }

    public int maxY()
    {
        return region.maxY();
    }

    public int maxZ()
    {
        return region.maxZ();
    }

    public boolean contains(int x, int y, int z)
    {
        return region.contains(x, y, z);
    }

    public String sizeString()
    {
        return region.sizeString();
    }

    public String regenSummary()
    {
        return regenMinutes > 0
                ? RegenInterval.labelFor(regenMinutes) + " " + (freshLayout ? "fresh" : "same")
                : "off";
    }
}
