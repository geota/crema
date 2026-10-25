/**
 * The shot the History detail pane shows: the picked one while it is still in
 * the filtered list, else the first filtered shot. A search or a filter that
 * hides the picked shot hands the pane to the first match instead of keeping
 * a shot the list no longer shows. Mirrors the tablet's rule in
 * `HistoryScreen.kt` (`shots.firstOrNull { it.id == selectedId } ?: shots.firstOrNull()`).
 */
export function selectedShot<T extends { id: string }>(
	filtered: readonly T[],
	selectedId: string | null
): T | undefined {
	return (selectedId ? filtered.find((s) => s.id === selectedId) : undefined) ?? filtered[0];
}
