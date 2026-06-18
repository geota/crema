package coffee.crema

/**
 * Shared library ordering for the bean + profile lists — used by BOTH their
 * browse pages and their Brew pickers. Pins the ACTIVE (loaded) item to the very
 * top, then FAVOURITES, then the rest. Each group keeps its existing order, so
 * whatever the caller already sorted by (the page's chosen sort, or store order
 * in a picker) becomes the within-group order. Stable.
 *
 * The bean and profile sorts are identical here and differ only in WHICH field
 * is "favourite" (a bean's `favourite` vs a profile's `pinned`) and in their sort
 * keys/filters — so this single grouping lives here and both call it. There is
 * only ever one active item, so its rank (2 / 3) is moot beyond being the unique
 * top; favourites (1) then sit above non-favourites (0).
 */
fun <T> List<T>.pinActiveThenFavourite(
    isActive: (T) -> Boolean,
    isFavourite: (T) -> Boolean,
): List<T> = sortedByDescending { (if (isActive(it)) 2 else 0) + (if (isFavourite(it)) 1 else 0) }
