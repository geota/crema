/**
 * Shared library ordering for the bean + profile lists — used by BOTH their
 * browse pages and the Brew picker. Pins the ACTIVE (loaded) item to the very
 * top, then FAVOURITES, then the rest. Each group keeps its incoming order, so
 * whatever the caller already sorted by (the page's chosen sort, or store order
 * in a picker) becomes the within-group order. Stable (`Array.sort` is stable).
 *
 * Mirrors the Android `coffee.crema.pinActiveThenFavourite`. The bean + profile
 * sorts are identical here and differ only in WHICH field is "favourite" (a
 * bean's `favourite` vs a profile's `pinned`), so this one helper serves both.
 * There is only ever one active item, so its rank (2 / 3) is moot beyond being
 * the unique top; favourites (1) then sit above non-favourites (0).
 */
export function pinActiveThenFavourite<T extends { id: string }>(
	items: readonly T[],
	activeId: string | null | undefined,
	isFavourite: (it: T) => boolean
): T[] {
	const rank = (it: T): number => (it.id === activeId ? 2 : 0) + (isFavourite(it) ? 1 : 0);
	return [...items].sort((a, b) => rank(b) - rank(a));
}
