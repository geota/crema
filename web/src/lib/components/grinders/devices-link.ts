/**
 * `devices-link` — where the Devices page lives (#488): today's Scale route,
 * with `?tab=grinder` for the Grinder tab. One place, so Brew's picker, the
 * phone swap sheet, the bag editor and Settings link the same way.
 */
import { resolve } from '$app/paths';

export type DevicesTab = 'scale' | 'grinder';

/** The Devices page, on `tab`. */
export function devicesHref(tab: DevicesTab = 'scale'): string {
	const base = resolve('/scale');
	return tab === 'grinder' ? `${base}?tab=grinder` : base;
}

/** The tab a Devices URL names: `?tab=grinder`, else Scale. */
export function devicesTabOf(params: { get(name: string): string | null }): DevicesTab {
	return params.get('tab') === 'grinder' ? 'grinder' : 'scale';
}
