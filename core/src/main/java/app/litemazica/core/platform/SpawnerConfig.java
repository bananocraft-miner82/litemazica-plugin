package app.litemazica.core.platform;

/**
 * How to arm a trap spawner: which mob it spews, and how fast and hard. The
 * fields map straight onto the vanilla mob-spawner block entity.
 *
 * @param entityId            spawned mob id ({@code minecraft:zombie}, …)
 * @param spawnCount          mobs released per activation
 * @param maxNearbyEntities   stop spawning once this many are already nearby
 * @param requiredPlayerRange activation radius, in blocks
 * @param minSpawnDelay       shortest gap between activations, in ticks
 * @param maxSpawnDelay       longest gap between activations, in ticks
 * @param spawnRange          how far from the spawner mobs may appear, in blocks
 */
public record SpawnerConfig(String entityId, int spawnCount, int maxNearbyEntities,
                            int requiredPlayerRange, int minSpawnDelay, int maxSpawnDelay,
                            int spawnRange)
{
}
