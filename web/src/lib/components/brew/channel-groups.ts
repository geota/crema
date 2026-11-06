/**
 * The four chart-channel families the Quick Sheet toggles. Each has the
 * channel's Phosphor icon and colour, and a primary and secondary toggle bound
 * to a Settings field. `phoneLabel` is the Android Chart tab's fuller wording,
 * where the rows have room for it.
 */
import type { Settings } from '#lib/settings/index.js';

export interface ChannelToggle {
	key: keyof Settings;
	label: string;
	phoneLabel: string;
	color: string;
}

export interface ChannelGroup {
	icon: string;
	color: string;
	primary: ChannelToggle;
	secondary: ChannelToggle;
}

export const CHANNEL_GROUPS: readonly ChannelGroup[] = [
	{
		icon: 'gauge',
		color: 'var(--tel-pressure)',
		primary: {
			key: 'showPressure',
			label: 'Pressure',
			phoneLabel: 'Pressure',
			color: 'var(--tel-pressure)'
		},
		secondary: {
			key: 'showResistance',
			label: 'Resistance',
			phoneLabel: 'Resistance',
			color: 'var(--tel-pressure-2)'
		}
	},
	{
		icon: 'drop',
		color: 'var(--tel-flow)',
		primary: { key: 'showFlow', label: 'Flow', phoneLabel: 'Flow', color: 'var(--tel-flow)' },
		secondary: {
			key: 'showVolume',
			label: 'Volume',
			phoneLabel: 'Volume',
			color: 'var(--tel-flow-2)'
		}
	},
	{
		icon: 'thermometer',
		color: 'var(--tel-temp)',
		primary: {
			key: 'showHeadTemp',
			label: 'Coffee',
			phoneLabel: 'Coffee temp',
			color: 'var(--tel-temp)'
		},
		secondary: {
			key: 'showMixTemp',
			label: 'Water',
			phoneLabel: 'Water temp',
			color: 'var(--tel-temp-2)'
		}
	},
	{
		icon: 'scales',
		color: 'var(--tel-weight)',
		primary: { key: 'showWeight', label: 'Weight', phoneLabel: 'Weight', color: 'var(--tel-weight)' },
		secondary: {
			key: 'showWeightFlow',
			label: 'Flow',
			phoneLabel: 'Weight flow',
			color: 'var(--tel-weight-2)'
		}
	}
];
