/**
 * `#lib/bean/stock.js` — how much is left in a bag, delegated to the core.
 *
 * `de1_domain::bag_stock` (geota/crema#302) owns the rule, the same one
 * Android calls. This module carries it across wasm and holds the wording.
 */

import { bagStockJson } from '#lib/wasm/de1_wasm.js';
import type { BagStock } from '#lib/core/crema-core.js';

/** The two weights the rule reads off a bag. */
export interface BagWeights {
	readonly remaining: number | null;
	readonly bagSize: number | null;
}

/** The bag's stock. `doses` are its brews' doses, newest first. */
export function bagStock(bag: BagWeights, doses: readonly number[] = []): BagStock {
	const json = bagStockJson(bag.remaining ?? 0, bag.bagSize ?? 0, Float32Array.from(doses));
	return JSON.parse(json) as BagStock;
}

/** The Brew card's nudge on a low bag, `null` otherwise. */
export function lowStockHint(stock: BagStock): string | null {
	if (stock.type !== 'Low') return null;
	const { brews } = stock.content;
	if (brews === 0) return 'Less than a brew left — reorder?';
	return `${brews} brew${brews === 1 ? '' : 's'} left — reorder?`;
}

/**
 * Whether Brew shows the empty-bag banner: only on an empty bag, and out of the
 * way while a shot runs. It never gates a shot, which can start from the
 * machine's own button.
 */
export function showsEmptyBagBanner(stock: BagStock, shotInProgress: boolean): boolean {
	return stock.type === 'Empty' && !shotInProgress;
}

/** The empty-bag banner's text. */
export function emptyBagBannerText(name: string): string {
	return `${name.trim() || 'This bag'} is at 0 g. Shots still log to it.`;
}
