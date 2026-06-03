<script lang="ts" module>
	import type { Component } from 'svelte';
	import ArchiveIcon from 'phosphor-svelte/lib/ArchiveIcon';
	import ArrowDownIcon from 'phosphor-svelte/lib/ArrowDownIcon';
	import ArrowUpIcon from 'phosphor-svelte/lib/ArrowUpIcon';
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	import BluetoothIcon from 'phosphor-svelte/lib/BluetoothIcon';
	import ClockCounterClockwiseIcon from 'phosphor-svelte/lib/ClockCounterClockwiseIcon';
	import CloudArrowUpIcon from 'phosphor-svelte/lib/CloudArrowUpIcon';
	import FileArrowDownIcon from 'phosphor-svelte/lib/FileArrowDownIcon';
	import LinkBreakIcon from 'phosphor-svelte/lib/LinkBreakIcon';
	import MoonIcon from 'phosphor-svelte/lib/MoonIcon';
	import PlugsIcon from 'phosphor-svelte/lib/PlugsIcon';
	import SpinnerGapIcon from 'phosphor-svelte/lib/SpinnerGapIcon';
	import SunIcon from 'phosphor-svelte/lib/SunIcon';
	import WarningIcon from 'phosphor-svelte/lib/WarningIcon';
	import CalendarIcon from 'phosphor-svelte/lib/CalendarIcon';
	import CameraPlusIcon from 'phosphor-svelte/lib/CameraPlusIcon';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import CaretUpIcon from 'phosphor-svelte/lib/CaretUpIcon';
	import ChartLineIcon from 'phosphor-svelte/lib/ChartLineIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import CheckSquareIcon from 'phosphor-svelte/lib/CheckSquareIcon';
	import CircleIcon from 'phosphor-svelte/lib/CircleIcon';
	import ClockIcon from 'phosphor-svelte/lib/ClockIcon';
	import CloudIcon from 'phosphor-svelte/lib/CloudIcon';
	import CloudSlashIcon from 'phosphor-svelte/lib/CloudSlashIcon';
	import CoffeeIcon from 'phosphor-svelte/lib/CoffeeIcon';
	import CoffeeBeanIcon from 'phosphor-svelte/lib/CoffeeBeanIcon';
	import DeviceMobileIcon from 'phosphor-svelte/lib/DeviceMobileIcon';
	import DownloadSimpleIcon from 'phosphor-svelte/lib/DownloadSimpleIcon';
	import DropIcon from 'phosphor-svelte/lib/DropIcon';
	import DropHalfIcon from 'phosphor-svelte/lib/DropHalfIcon';
	import EyeIcon from 'phosphor-svelte/lib/EyeIcon';
	import EyeSlashIcon from 'phosphor-svelte/lib/EyeSlashIcon';
	import FileArrowUpIcon from 'phosphor-svelte/lib/FileArrowUpIcon';
	import FileCodeIcon from 'phosphor-svelte/lib/FileCodeIcon';
	import FileTextIcon from 'phosphor-svelte/lib/FileTextIcon';
	import FileZipIcon from 'phosphor-svelte/lib/FileZipIcon';
	import FireIcon from 'phosphor-svelte/lib/FireIcon';
	import FlameIcon from 'phosphor-svelte/lib/FlameIcon';
	import FunnelIcon from 'phosphor-svelte/lib/FunnelIcon';
	import GaugeIcon from 'phosphor-svelte/lib/GaugeIcon';
	import GearSixIcon from 'phosphor-svelte/lib/GearSixIcon';
	import InfoIcon from 'phosphor-svelte/lib/InfoIcon';
	import ListBulletsIcon from 'phosphor-svelte/lib/ListBulletsIcon';
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import MonitorIcon from 'phosphor-svelte/lib/MonitorIcon';
	import PackageIcon from 'phosphor-svelte/lib/PackageIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import PlugChargingIcon from 'phosphor-svelte/lib/PlugChargingIcon';
	import PlusCircleIcon from 'phosphor-svelte/lib/PlusCircleIcon';
	import ScalesIcon from 'phosphor-svelte/lib/ScalesIcon';
	import ShareNetworkIcon from 'phosphor-svelte/lib/ShareNetworkIcon';
	import SnowflakeIcon from 'phosphor-svelte/lib/SnowflakeIcon';
	import SortAscendingIcon from 'phosphor-svelte/lib/SortAscendingIcon';
	import SortDescendingIcon from 'phosphor-svelte/lib/SortDescendingIcon';
	import SparkleIcon from 'phosphor-svelte/lib/SparkleIcon';
	import SquareIcon from 'phosphor-svelte/lib/SquareIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import TagIcon from 'phosphor-svelte/lib/TagIcon';
	import ThermometerIcon from 'phosphor-svelte/lib/ThermometerIcon';
	import TimerIcon from 'phosphor-svelte/lib/TimerIcon';
	import TrashIcon from 'phosphor-svelte/lib/TrashIcon';
	import WarningCircleIcon from 'phosphor-svelte/lib/WarningCircleIcon';
	import WrenchIcon from 'phosphor-svelte/lib/WrenchIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';

	/**
	 * Name → component map for icons chosen *at runtime* — the call sites that
	 * build the icon from a prop or condition (`ModeChip`, `SortPill`, the
	 * sidebar nav, settings rows, star ratings, …). Static icons do NOT live
	 * here: they import their phosphor component directly so each route chunk
	 * carries only what it renders. This map is the *only* always-bundled icon
	 * set, so it holds strictly the dynamically-referenced names.
	 *
	 * `phosphor-svelte` (v3, tracking Phosphor core) dropped the old
	 * `archive-box` glyph; we map that legacy name to `PackageIcon` (a closed
	 * box — the "archived" state, visually distinct from `archive`).
	 */
	const ICONS: Record<string, Component> = {
		archive: ArchiveIcon,
		'archive-box': PackageIcon,
		'arrow-down': ArrowDownIcon,
		'arrow-up': ArrowUpIcon,
		'arrows-clockwise': ArrowsClockwiseIcon,
		bluetooth: BluetoothIcon,
		calendar: CalendarIcon,
		'camera-plus': CameraPlusIcon,
		'caret-down': CaretDownIcon,
		'caret-up': CaretUpIcon,
		'chart-line': ChartLineIcon,
		check: CheckIcon,
		'check-circle': CheckCircleIcon,
		'check-square': CheckSquareIcon,
		circle: CircleIcon,
		clock: ClockIcon,
		'clock-counter-clockwise': ClockCounterClockwiseIcon,
		'cloud-arrow-up': CloudArrowUpIcon,
		cloud: CloudIcon,
		'cloud-slash': CloudSlashIcon,
		coffee: CoffeeIcon,
		'coffee-bean': CoffeeBeanIcon,
		'device-mobile': DeviceMobileIcon,
		'download-simple': DownloadSimpleIcon,
		drop: DropIcon,
		'drop-half': DropHalfIcon,
		eye: EyeIcon,
		'eye-slash': EyeSlashIcon,
		'file-arrow-down': FileArrowDownIcon,
		'file-arrow-up': FileArrowUpIcon,
		'file-code': FileCodeIcon,
		'file-text': FileTextIcon,
		'file-zip': FileZipIcon,
		fire: FireIcon,
		flame: FlameIcon,
		funnel: FunnelIcon,
		gauge: GaugeIcon,
		'gear-six': GearSixIcon,
		info: InfoIcon,
		'link-break': LinkBreakIcon,
		'list-bullets': ListBulletsIcon,
		'magnifying-glass': MagnifyingGlassIcon,
		monitor: MonitorIcon,
		moon: MoonIcon,
		package: PackageIcon,
		plugs: PlugsIcon,
		'pencil-simple': PencilSimpleIcon,
		'plug-charging': PlugChargingIcon,
		'plus-circle': PlusCircleIcon,
		scales: ScalesIcon,
		'share-network': ShareNetworkIcon,
		snowflake: SnowflakeIcon,
		'sort-ascending': SortAscendingIcon,
		'sort-descending': SortDescendingIcon,
		sparkle: SparkleIcon,
		'spinner-gap': SpinnerGapIcon,
		sun: SunIcon,
		square: SquareIcon,
		star: StarIcon,
		tag: TagIcon,
		thermometer: ThermometerIcon,
		timer: TimerIcon,
		trash: TrashIcon,
		warning: WarningIcon,
		'warning-circle': WarningCircleIcon,
		wrench: WrenchIcon,
		x: XIcon
	};

	type Weight = 'thin' | 'light' | 'regular' | 'bold' | 'fill' | 'duotone';

	/** Parse a legacy Phosphor class string (`"ph-duotone ph-cloud foo"`). */
	function parsePh(cls: string): { name?: string; weight: Weight; extra?: string } {
		let weight: Weight = 'regular';
		let name: string | undefined;
		const extra: string[] = [];
		for (const t of cls.split(/\s+/).filter(Boolean)) {
			if (t === 'ph-duotone') weight = 'duotone';
			else if (t === 'ph-fill') weight = 'fill';
			else if (t === 'ph-bold') weight = 'bold';
			else if (t === 'ph-thin') weight = 'thin';
			else if (t === 'ph-light') weight = 'light';
			else if (t === 'ph') continue;
			else if (t.startsWith('ph-')) name = t.slice(3);
			else extra.push(t);
		}
		return { name, weight, extra: extra.join(' ') || undefined };
	}
</script>

<script lang="ts">
	import type { SvelteHTMLElements } from 'svelte/elements';

	let {
		name,
		weight,
		cls,
		class: klass,
		...rest
	}: {
		/** Phosphor icon name, e.g. `"cloud"`. Mutually exclusive with `cls`. */
		name?: string;
		weight?: Weight;
		/** Legacy full Phosphor class string (`"ph-duotone ph-cloud"`) — parsed. */
		cls?: string;
		class?: string;
	} & Omit<SvelteHTMLElements['svg'], 'class'> = $props();

	const parsed = $derived(cls ? parsePh(cls) : null);
	const resolvedName = $derived(name ?? parsed?.name);
	const resolvedWeight = $derived(weight ?? parsed?.weight ?? 'regular');
	const resolvedClass = $derived([klass, parsed?.extra].filter(Boolean).join(' ') || undefined);
	const Cmp = $derived(resolvedName ? ICONS[resolvedName] : undefined);

	$effect(() => {
		if (resolvedName && !Cmp && import.meta.env.DEV) {
			console.warn(`[Icon] no dynamic mapping for "${resolvedName}" — add it to src/lib/icons/Icon.svelte`);
		}
	});
</script>

{#if Cmp}
	<Cmp weight={resolvedWeight} class={resolvedClass} {...rest} />
{/if}
