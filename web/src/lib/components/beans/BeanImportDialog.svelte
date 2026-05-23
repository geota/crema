<script lang="ts">
	/**
	 * `BeanImportDialog` — drop / pick a Beanconqueror `.zip` export, parse it
	 * with `fflate`, and preview / commit the import into Crema's library.
	 *
	 * Per the coordinator's mid-flight adjustment (and docs/28 §import-flow),
	 * the importer is **deliberately lossy**: only the high-value subset of
	 * Beanconqueror's ~40 bean fields lands as first-class Crema fields, and
	 * anything we don't model (cupping form, mill, roast curves, EAN, CO2e,
	 * frozen-group portions…) is dropped. We surface the dropped categories
	 * in the preview and again in a one-time banner after the commit so the
	 * user knows what was skipped.
	 *
	 * Conflict resolution v1: skip on duplicate by case-insensitive
	 * `(roasterName, name, roastedOn)` triple. No field-level merge UI.
	 */
	import { unzip, strFromU8 } from 'fflate';
	import {
		getBeanStore,
		blankBean,
		blankRoaster,
		type Bean,
		type Roaster
	} from '$lib/bean';

	let { onClose }: { onClose: () => void } = $props();

	const library = getBeanStore();

	type Step = 'pick' | 'parsing' | 'preview' | 'done' | 'error';
	let step = $state<Step>('pick');
	let errorMessage = $state('');
	let preview = $state<{
		beans: Bean[];
		roasters: Roaster[];
		skipped: number;
		droppedCategories: string[];
		filename: string;
	} | null>(null);
	let committed = $state<{ imported: number; skipped: number } | null>(null);

	/** Beanconqueror's 13-band roast enum → Crema's 1..10 scale. */
	const BC_ROAST_MAP: Record<string, number> = {
		CINNAMON: 1,
		LIGHT: 2,
		MEDIUM_LIGHT: 3,
		MEDIUM: 4,
		CITY: 4,
		'CITY_+': 5,
		MEDIUM_DARK: 6,
		FULL_CITY: 6,
		VIENNA: 7,
		FRENCH: 8,
		ITALIAN: 9,
		SPANISH: 10,
		UNKNOWN: 0
	};

	/** Beanconqueror's mix enum → Crema's lowercase wire string. */
	function bcMix(value: unknown): 'single' | 'blend' | null {
		if (value === 'SINGLE_ORIGIN') return 'single';
		if (value === 'BLEND') return 'blend';
		return null;
	}

	function bcRoastLevel(raw: unknown): number | null {
		if (typeof raw !== 'string') return null;
		const v = BC_ROAST_MAP[raw];
		return typeof v === 'number' && v > 0 ? v : null;
	}

	async function onFileChosen(event: Event): Promise<void> {
		const input = event.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		await ingest(file);
	}

	async function onDrop(event: DragEvent): Promise<void> {
		event.preventDefault();
		const file = event.dataTransfer?.files?.[0];
		if (!file) return;
		await ingest(file);
	}

	async function ingest(file: File): Promise<void> {
		step = 'parsing';
		errorMessage = '';
		try {
			const buf = new Uint8Array(await file.arrayBuffer());
			const entries = await new Promise<Record<string, Uint8Array>>(
				(resolve, reject) =>
					unzip(buf, (err, data) => (err ? reject(err) : resolve(data)))
			);
			// Walk every JSON entry inside; tolerate Bc's "Beanconqueror.json"
			// and chunked "Beanconqueror_Beans_*.json" layout.
			const allBcBeans: unknown[] = [];
			for (const [name, bytes] of Object.entries(entries)) {
				if (!name.toLowerCase().endsWith('.json')) continue;
				let parsed: unknown;
				try {
					parsed = JSON.parse(strFromU8(bytes));
				} catch {
					continue;
				}
				const beans = extractBeans(parsed);
				if (beans.length > 0) allBcBeans.push(...beans);
			}
			if (allBcBeans.length === 0) {
				step = 'error';
				errorMessage =
					'No beans found in the archive. Expected a Beanconqueror .zip export.';
				return;
			}
			// Build the preview. Dedup the bean list by stable key, dedup
			// roasters case-insensitively, skip duplicates already present
			// in the library by (roasterName, name, roastedOn).
			const builtRoasters = new Map<string, Roaster>(); // key: lowercase name
			const builtBeans: Bean[] = [];
			const droppedCounts = new Map<string, number>();
			let skipped = 0;
			for (const raw of allBcBeans) {
				if (typeof raw !== 'object' || raw === null) continue;
				const bc = raw as Record<string, unknown>;
				const result = mapBcBean(bc, builtRoasters);
				if (result === 'duplicate') {
					skipped += 1;
					continue;
				}
				builtBeans.push(result.bean);
				for (const cat of result.dropped) {
					droppedCounts.set(cat, (droppedCounts.get(cat) ?? 0) + 1);
				}
			}
			preview = {
				beans: builtBeans,
				roasters: [...builtRoasters.values()],
				skipped,
				droppedCategories: [...droppedCounts.entries()]
					.sort((a, b) => b[1] - a[1])
					.map(([cat, n]) => `${cat} (${n})`),
				filename: file.name
			};
			step = 'preview';
		} catch (e) {
			step = 'error';
			errorMessage =
				e instanceof Error ? e.message : 'Could not read the file.';
		}
	}

	/**
	 * Walk a parsed JSON value and extract Beanconqueror bean records, which
	 * may be either at the top level (`{beans: [...]}` / `{BEANS: [...]}`) or
	 * nested under chunked exports.
	 */
	function extractBeans(parsed: unknown): unknown[] {
		if (Array.isArray(parsed)) {
			return parsed.filter(
				(v) =>
					typeof v === 'object' &&
					v !== null &&
					('name' in v || 'config' in v || 'roastingDate' in v)
			);
		}
		if (typeof parsed === 'object' && parsed !== null) {
			const obj = parsed as Record<string, unknown>;
			for (const key of ['BEANS', 'beans', 'data']) {
				const v = obj[key];
				if (Array.isArray(v)) return extractBeans(v);
			}
		}
		return [];
	}

	function mapBcBean(
		bc: Record<string, unknown>,
		roasterMap: Map<string, Roaster>
	):
		| 'duplicate'
		| { bean: Bean; dropped: string[] } {
		const name = typeof bc.name === 'string' ? bc.name.trim() : '';
		const roasterName = typeof bc.roaster === 'string' ? bc.roaster.trim() : '';
		const roastedOn =
			typeof bc.roastingDate === 'string' ? bc.roastingDate.slice(0, 10) : null;

		// Duplicate check against existing library beans.
		const libRoasterId =
			(() => {
				const r = library.findRoasterByName(roasterName);
				return r?.id ?? null;
			})();
		if (libRoasterId) {
			const isDupe = library.beans.some(
				(existing) =>
					existing.roasterId === libRoasterId &&
					existing.name.trim().toLowerCase() === name.toLowerCase() &&
					(existing.roastedOn ?? null) === roastedOn
			);
			if (isDupe) return 'duplicate';
		}

		// Resolve / create the roaster row in the preview's working set.
		let roaster: Roaster | null = null;
		if (roasterName) {
			const key = roasterName.toLowerCase();
			roaster = roasterMap.get(key) ?? null;
			if (!roaster) {
				// Reuse the library roaster if one already exists by name.
				const existingLib = library.findRoasterByName(roasterName);
				if (existingLib) {
					roaster = existingLib;
				} else {
					roaster = blankRoaster(roasterName);
					roasterMap.set(key, roaster);
				}
			}
		}

		const bean = blankBean();
		bean.name = name || 'Imported bean';
		bean.roasterId = roaster?.id ?? null;
		bean.roastedOn = roastedOn;
		bean.openedOn =
			typeof bc.openDate === 'string' ? bc.openDate.slice(0, 10) : null;
		bean.frozenOn =
			typeof bc.frozenDate === 'string' ? bc.frozenDate.slice(0, 10) : null;
		bean.defrostedOn =
			typeof bc.unfrozenDate === 'string' ? bc.unfrozenDate.slice(0, 10) : null;
		bean.roastLevel = bcRoastLevel(bc.roast);
		bean.mix = bcMix(bc.beanMix);
		bean.decaf = bc.decaffeinated === true;
		bean.notes = typeof bc.note === 'string' ? bc.note : '';
		bean.rating =
			typeof bc.rating === 'number' && bc.rating >= 0 && bc.rating <= 5
				? Math.round(bc.rating)
				: 0;
		bean.favourite = bc.favourite === true;
		bean.url = typeof bc.url === 'string' ? bc.url : null;
		bean.bagSizeG =
			typeof bc.weight === 'number' && bc.weight > 0 ? bc.weight : 0;
		bean.remainingG = bean.bagSizeG; // assume full bag on import
		if (bc.finished === true) bean.archivedAt = Date.now();
		bean.beanconquerorId =
			typeof (bc.config as Record<string, unknown> | undefined)?.uuid === 'string'
				? ((bc.config as Record<string, unknown>).uuid as string)
				: null;

		// Origin — Beanconqueror's `bean_information` array, take [0].
		const info = bc.bean_information;
		if (Array.isArray(info) && info.length > 0) {
			const first = info[0] as Record<string, unknown>;
			bean.origin = {
				country: typeof first.country === 'string' ? first.country : null,
				region: typeof first.region === 'string' ? first.region : null,
				farm: typeof first.farm === 'string' ? first.farm : null,
				farmer: typeof first.farmer === 'string' ? first.farmer : null,
				variety: typeof first.variety === 'string' ? first.variety : null,
				elevation:
					typeof first.elevation === 'string' ? first.elevation : null,
				processing:
					typeof first.processing === 'string' ? first.processing : null,
				harvestTime:
					typeof first.harvest_time === 'string' ? first.harvest_time : null
			};
		}

		// Quality / cupping points — Visualizer maps these to free-text. Take
		// `cupping_points` if it exists; ignore the full 10-axis form.
		if (typeof bc.cupping_points === 'string' || typeof bc.cupping_points === 'number') {
			bean.qualityScore = String(bc.cupping_points);
		}

		// Track the dropped categories so the preview surfaces them.
		const dropped: string[] = [];
		if (bc.cupping && typeof bc.cupping === 'object') dropped.push('cupping form');
		if (bc.cupped_flavor && Array.isArray(bc.cupped_flavor) && bc.cupped_flavor.length > 0)
			dropped.push('flavor wheel tags');
		if (bc.bean_roast_information && typeof bc.bean_roast_information === 'object')
			dropped.push('self-roast curve');
		if (bc.ean_article_number) dropped.push('EAN barcode');
		if (bc.co2e_kg) dropped.push('CO2e per kg');
		if (bc.cost != null) dropped.push('cost');
		if (bc.qr_code || bc.internal_share_code) dropped.push('QR / share code');
		if (Array.isArray(bc.attachments) && bc.attachments.length > 0)
			dropped.push('bag photos');

		// Lossy escape valve: stash anything else that's trivially
		// serialisable into metadata.beanconqueror so it round-trips through
		// localStorage even if we never render it (yet).
		const meta: Record<string, unknown> = {};
		if (bc.aromatics) meta.aromatics = bc.aromatics;
		if (bc.bestDate) meta.bestDate = bc.bestDate;
		if (bc.buyDate) meta.buyDate = bc.buyDate;
		if (bc.cost != null) meta.cost = bc.cost;
		if (bc.ean_article_number) meta.ean = bc.ean_article_number;
		if (Object.keys(meta).length > 0) {
			bean.metadata = { beanconqueror: meta };
		}

		return { bean, dropped };
	}

	function commit(): void {
		if (!preview) return;
		// Add only roasters that aren't already in the library — those were
		// detected during preview building and reused.
		const newRoasters = preview.roasters.filter(
			(r) => !library.findRoasterByName(r.name)
		);
		library.bulkAdd(preview.beans, newRoasters);
		committed = { imported: preview.beans.length, skipped: preview.skipped };
		step = 'done';
	}
</script>

<div
	class="bd-scrim"
	onclick={onClose}
	onkeydown={(e) => e.key === 'Escape' && onClose()}
	role="button"
	tabindex="-1"
	aria-label="Close import"
></div>

<div class="bd-modal" role="dialog" aria-labelledby="bd-title">
	<header class="bd-head">
		<h2 class="bd-title" id="bd-title">Import beans</h2>
		<button class="bd-close" onclick={onClose} aria-label="Close">
			<i class="ph ph-x" aria-hidden="true"></i>
		</button>
	</header>

	{#if step === 'pick'}
		<div
			class="bd-drop"
			ondragover={(e) => e.preventDefault()}
			ondrop={onDrop}
			role="region"
			aria-label="Drop a Beanconqueror .zip file"
		>
			<i class="ph-duotone ph-file-zip" aria-hidden="true"></i>
			<div class="bd-drop-title">Drop a Beanconqueror .zip export</div>
			<div class="bd-drop-sub">
				The importer takes the high-value subset — name, roaster, roast date,
				origin, notes, bag size — and skips anything Crema doesn't model
				(cupping form, roast curves, mill data, attachments).
			</div>
			<label class="bd-pickbtn">
				<input type="file" accept=".zip,application/zip" onchange={onFileChosen} />
				<span>Choose file…</span>
			</label>
		</div>
	{:else if step === 'parsing'}
		<div class="bd-status">
			<i class="ph ph-spinner-gap bd-spinner" aria-hidden="true"></i>
			Parsing your archive…
		</div>
	{:else if step === 'error'}
		<div class="bd-status bd-status-err">
			<i class="ph ph-warning" aria-hidden="true"></i>
			<div>
				<div class="bd-status-title">Couldn't read that file</div>
				<div>{errorMessage}</div>
			</div>
			<button class="bd-btn" onclick={() => (step = 'pick')}>Try again</button>
		</div>
	{:else if step === 'preview' && preview}
		<div class="bd-preview">
			<div class="bd-stat">
				<div class="bd-stat-n">{preview.beans.length}</div>
				<div class="bd-stat-label">beans to import</div>
			</div>
			<div class="bd-stat">
				<div class="bd-stat-n">{preview.roasters.length}</div>
				<div class="bd-stat-label">new roasters</div>
			</div>
			{#if preview.skipped > 0}
				<div class="bd-stat">
					<div class="bd-stat-n">{preview.skipped}</div>
					<div class="bd-stat-label">skipped (duplicates)</div>
				</div>
			{/if}
		</div>
		{#if preview.droppedCategories.length > 0}
			<div class="bd-dropped">
				<i class="ph ph-info" aria-hidden="true"></i>
				<div>
					<div class="bd-dropped-title">Not migrated (Crema doesn't model these):</div>
					<ul>
						{#each preview.droppedCategories as cat (cat)}
							<li>{cat}</li>
						{/each}
					</ul>
				</div>
			</div>
		{/if}
		<div class="bd-foot">
			<button class="bd-btn" onclick={onClose}>Cancel</button>
			<button class="bd-btn bd-btn-primary" onclick={commit}>
				Import {preview.beans.length} bean{preview.beans.length === 1 ? '' : 's'}
			</button>
		</div>
	{:else if step === 'done' && committed}
		<div class="bd-status">
			<i class="ph-fill ph-check-circle" aria-hidden="true"></i>
			<div>
				<div class="bd-status-title">Imported {committed.imported} bean{committed.imported === 1 ? '' : 's'}</div>
				{#if committed.skipped > 0}
					<div>Skipped {committed.skipped} duplicate{committed.skipped === 1 ? '' : 's'}.</div>
				{/if}
			</div>
			<button class="bd-btn bd-btn-primary" onclick={onClose}>Done</button>
		</div>
	{/if}
</div>

<style>
	.bd-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
		z-index: 70;
	}
	.bd-modal {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(560px, calc(100vw - 32px));
		max-height: calc(100vh - 64px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-lg, 14px);
		z-index: 71;
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}
	.bd-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 16px 20px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.bd-title {
		font-family: var(--font-sans);
		font-size: 16px;
		font-weight: 600;
		margin: 0;
		color: var(--fg-1);
	}
	.bd-close {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		padding: 4px;
	}
	.bd-drop {
		padding: 40px 24px;
		text-align: center;
		display: flex;
		flex-direction: column;
		gap: 12px;
		border: 2px dashed rgba(var(--tint-rgb), 0.16);
		border-radius: var(--radius-lg, 14px);
		margin: 20px;
	}
	.bd-drop i {
		font-size: 48px;
		color: var(--copper-400);
	}
	.bd-drop-title {
		font-family: var(--font-sans);
		font-size: 15px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.bd-drop-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		max-width: 380px;
		margin: 0 auto;
		line-height: 1.5;
	}
	.bd-pickbtn {
		display: inline-flex;
		position: relative;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		border-radius: var(--radius-pill);
		padding: 8px 18px;
		cursor: pointer;
		margin: 6px auto 0;
		max-width: 200px;
	}
	.bd-pickbtn input[type='file'] {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
	}
	.bd-status {
		padding: 24px;
		display: flex;
		gap: 12px;
		align-items: center;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.bd-status i {
		font-size: 24px;
		color: var(--copper-400);
	}
	.bd-status-err i {
		color: var(--danger);
	}
	.bd-status-title {
		font-weight: 600;
	}
	.bd-spinner {
		animation: bd-spin 1.1s linear infinite;
	}
	@keyframes bd-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
	.bd-preview {
		display: flex;
		gap: 24px;
		padding: 20px 24px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.bd-stat {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bd-stat-n {
		font-family: var(--font-mono);
		font-size: 28px;
		font-weight: 700;
		color: var(--fg-1);
	}
	.bd-stat-label {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
	}
	.bd-dropped {
		display: flex;
		gap: 8px;
		padding: 14px 24px;
		background: rgba(var(--tint-rgb), 0.04);
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.bd-dropped i {
		color: var(--warning);
		font-size: 16px;
	}
	.bd-dropped-title {
		font-weight: 600;
		margin-bottom: 4px;
	}
	.bd-dropped ul {
		margin: 0;
		padding-left: 18px;
		font-size: 11px;
	}
	.bd-foot {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		padding: 16px 20px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.bd-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 16px;
		cursor: pointer;
	}
	.bd-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
		border-color: transparent;
	}
	.bd-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bd-btn-primary:hover {
		background: var(--copper-600);
	}
</style>
