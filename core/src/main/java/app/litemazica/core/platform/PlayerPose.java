package app.litemazica.core.platform;

/** Where a player is right now: world, block position, and facing. */
public record PlayerPose(String worldName, int x, int y, int z, float yaw)
{
}
