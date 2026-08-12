package app.litemazica.core.platform;

/**
 * One stack destined for a slot of a trap dispenser — part of an arrow volley or
 * a thrown-potion charge.
 *
 * @param slot     the dispenser inventory slot (0–8)
 * @param itemId   the trap item id: an arrow ({@code minecraft:arrow},
 *                 {@code minecraft:spectral_arrow}, {@code minecraft:tipped_arrow})
 *                 or a thrown potion ({@code minecraft:splash_potion},
 *                 {@code minecraft:lingering_potion})
 * @param count    how many (1–64); potions don't stack, so a potion slot is 1
 * @param potionId for a tipped arrow or a thrown potion, the potion registry id
 *                 ({@code minecraft:poison}, …); {@code null} for a plain or spectral arrow
 */
public record DispenserItem(int slot, String itemId, int count, String potionId)
{
}
