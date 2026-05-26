<script lang="ts">
	/**
	 * `BeanImportDialog` — drop / pick a Beanconqueror `.zip` export, parse
	 * it with `fflate`, hand the merged main JSON to the core's
	 * `importBeanconquerorJson` wasm fn, and apply the resulting plan to
	 * Crema's bean library + shot history.
	 *
	 * The mapping (BC field → Crema field, defensive enum handling,
	 * espresso-only filter on brews, duplicate-by-fingerprint dedup of
	 * roasters) lives in `core/de1-domain/src/beanconqueror.rs` — the
	 * Android shell will reach the same algorithm via UniFFI. This
	 * component is the web-shell adapter: unzip the archive, build the
	 * merged JSON, render the preview, commit on confirm.
	 *
	 * Deliberately lossy: cupping form, frozen-group portions, EAN,
	 * roast curves, attachments etc. don't survive. The diagnostics
	 * surface every dropped category so the user knows what was
	 * skipped. Brews whose preparation isn't ESPRESSO are counted +
	 * skipped (V60 / AeroPress / etc. — no Crema surface for them).
	 */
	import { unzip, strFromU8 } from 'fflate';
	import {
		importBeanconquerorJson as wasmImportBeanconquerorJson,
		importCremaJsonl as wasmImportCremaJsonl
	} from '$lib/wasm/de1_wasm';
	import {
		getBeanStore,
		getBeanImageStore,
		beanImageRefFor,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import { getHistoryStore, type StoredShot, type ShotBean } from '$lib/history';

	let { onClose }: { onClose: () => void } = $props();

	const library = getBeanStore();
	const history = getHistoryStore();
	const imageStore = getBeanImageStore();

	type Step = 'pick' | 'parsing' | 'preview' | 'committing' | 'done' | 'error';
	let step = $state<Step>('pick');
	let errorMessage = $state('');
	let preview = $state<Preview | null>(null);
	let committed = $state<{
		beansImported: number;
		shotsImported: number;
		duplicatesSkipped: number;
		photosStored: number;
	} | null>(null);
	/**
	 * Per-bean commit progress. `total` is the number of beans the
	 * commit pass will process (after dedups); `done` increments as
	 * each one's image + record lands. Drives the progress bar shown
	 * in the `committing` step.
	 */
	let progress = $state<{ done: number; total: number }>({ done: 0, total: 0 });
	/**
	 * Photo files dropped alongside the ZIP. BC's "full with photos"
	 * export emits the ZIP plus per-bean `photo_<uuid>.jpg` siblings
	 * (not inside the ZIP — verified by inspecting actual exports).
	 * Captured here so the IndexedDB image-storage pipeline (when it
	 * lands) can match by filename against
	 * `diagnostics.bagPhotoFilenames`.
	 */
	let droppedPhotoFiles = $state<File[]>([]);

	/**
	 * The plan shape the core's `importBeanconquerorJson` returns. Mirrors
	 * `de1_domain::beanconqueror::ImportPlan` (camelCase via the wasm
	 * serialiser). We treat it as JSON-shaped data; the fields below are
	 * the ones the UI consumes.
	 */
	/**
	 * Recursively rewrite an object's keys from `snake_case` to
	 * `camelCase`. Leaves arrays, scalars, and the special `metadata`
	 * field (which carries opaque user JSON whose keys we must not
	 * touch) alone.
	 *
	 * Crema's web `Bean` / `Roaster` types use camelCase
	 * (`bagSize`, `roastLevel`, …); the Rust core's `Bean` /
	 * `Roaster` JSON serialize as snake_case (`bag_size_g`,
	 * `roast_level`, …) — they intentionally don't carry
	 * `rename_all = "camelCase"` because the Visualizer sync layer
	 * expects snake_case Roaster keys on the wire. So at the wasm
	 * boundary we translate to match the shell's persisted shape.
	 */
	function snakeToCamel<T>(value: unknown): T {
		if (Array.isArray(value)) {
			return value.map((v) => snakeToCamel(v)) as unknown as T;
		}
		if (value === null || typeof value !== 'object') {
			return value as T;
		}
		const out: Record<string, unknown> = {};
		for (const [k, v] of Object.entries(value)) {
			if (k === 'metadata') {
				// User-opaque JSON — keys must round-trip verbatim.
				out[k] = v;
				continue;
			}
			const camel = k.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
			out[camel] = snakeToCamel(v);
		}
		return out as T;
	}

	interface CorePlan {
		readonly beans: BeanWireSnake[];
		readonly roasters: RoasterWireSnake[];
		readonly shots: CoreImportedShot[];
		readonly diagnostics: {
			readonly nonEspressoBrewsSkipped: number;
			readonly brewsDanglingPreparation: number;
			readonly brewsDanglingBean: number;
			readonly beansImported: number;
			readonly roastersCreated: number;
			readonly shotsImported: number;
			readonly droppedBeanCategories: ReadonlyArray<readonly [string, number]>;
			readonly bagPhotosReferenced: number;
			readonly bagPhotoFilenames: string[];
		};
	}

	/**
	 * Marker types — the wasm output is snake_case JSON; we cast it
	 * through these placeholders before `snakeToCamel` projects it
	 * onto the shell's camelCase `Bean` / `Roaster`. The types are
	 * deliberately opaque (`unknown`) so we never accidentally read
	 * a snake_case field via the camelCase interface.
	 */
	type BeanWireSnake = Record<string, unknown>;
	type RoasterWireSnake = Record<string, unknown>;

	/** One BC brew mapped to a Rust-shape StoredShot + the resolved snapshot strings. */
	interface CoreImportedShot {
		readonly storedShot: RustStoredShotWire;
		readonly beanId: string | null;
		readonly beanName: string;
		readonly roasterName: string | null;
		readonly roastedOn: string | null;
		readonly roastLevel: number | null;
		readonly grinderModel: string | null;
	}

	/** The Rust StoredShot's wire shape (just the fields the shell uses). */
	interface RustStoredShotWire {
		readonly recorded_at: number;
		readonly record: {
			readonly duration: { secs: number; nanos: number };
		};
		readonly metadata: {
			readonly dose?: number | null;
			readonly yield_out?: number | null;
			readonly rating?: number | null;
			readonly notes?: string | null;
			readonly grinder_setting?: string | null;
		};
	}

	interface Preview {
		filename: string;
		newBeans: Bean[];
		newRoasters: Roaster[];
		newShots: PreparedShot[];
		duplicatesSkipped: number;
		droppedCategories: string[];
		nonEspressoBrewsSkipped: number;
		brewsDanglingPreparation: number;
		/** Photos referenced in `BEANS[*].attachments` (from the core). */
		photosReferenced: number;
		/** Photo files actually present alongside the ZIP in the drop. */
		photosMatched: number;
	}

	/** A shell-ready StoredShot pre-built from a core ImportedShot. */
	interface PreparedShot {
		shot: StoredShot;
	}

	async function onFileChosen(event: Event): Promise<void> {
		const input = event.currentTarget as HTMLInputElement;
		const files = input.files ? [...input.files] : [];
		input.value = '';
		await ingestFiles(files);
	}

	async function onDrop(event: DragEvent): Promise<void> {
		event.preventDefault();
		const files = event.dataTransfer?.files ? [...event.dataTransfer.files] : [];
		await ingestFiles(files);
	}

	/**
	 * Entry point for both file-picker and drag-drop. Locates the BC
	 * ZIP among the dropped files (case-insensitive, by `.zip`
	 * extension) and captures sibling photo files (anything else with
	 * an image-y name pattern that matches BC's `photo_<uuid>.<ext>`
	 * convention or just `image/*` MIME types).
	 */
	async function ingestFiles(files: File[]): Promise<void> {
		if (files.length === 0) return;
		// Crema-native JSONL takes precedence — same-app round-trip
		// is lossless; BC is the lossy migration path. If both kinds
		// of files are dropped, the JSONL wins.
		const jsonl = files.find((f) => f.name.toLowerCase().endsWith('.jsonl'));
		if (jsonl) {
			droppedPhotoFiles = [];
			await ingestCremaJsonl(jsonl);
			return;
		}
		const zip = files.find(
			(f) => f.name.toLowerCase().endsWith('.zip')
		);
		if (!zip) {
			step = 'error';
			errorMessage = 'Pick the Beanconqueror .zip or a Crema .jsonl export. Drop the whole export folder for BC with photos.';
			return;
		}
		droppedPhotoFiles = files.filter(
			(f) =>
				f !== zip &&
				(f.type.startsWith('image/') ||
					/\.(jpe?g|png|webp|heic|gif)$/i.test(f.name))
		);
		await ingest(zip);
	}

	async function ingestCremaJsonl(file: File): Promise<void> {
		step = 'parsing';
		errorMessage = '';
		try {
			const text = await file.text();
			let planJson: string;
			try {
				planJson = wasmImportCremaJsonl(text);
			} catch (e) {
				step = 'error';
				errorMessage = `Core parser rejected the file: ${e instanceof Error ? e.message : String(e)}`;
				return;
			}
			const plan = JSON.parse(planJson) as CorePlan;
			preview = buildPreview(plan, file.name);
			step = 'preview';
		} catch (e) {
			step = 'error';
			errorMessage =
				e instanceof Error ? e.message : 'Could not read the file.';
		}
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
			const mergedJson = mergeBcArchive(entries);
			if (mergedJson === null) {
				step = 'error';
				errorMessage =
					'No Beanconqueror.json found in the archive. Expected a Beanconqueror .zip export.';
				return;
			}

			// Core does the mapping. Returns the full plan (beans,
			// roasters, espresso-shots, diagnostics) as JSON.
			let planJson: string;
			try {
				planJson = wasmImportBeanconquerorJson(mergedJson, Date.now());
			} catch (e) {
				step = 'error';
				errorMessage = `Core parser rejected the archive: ${e instanceof Error ? e.message : String(e)}`;
				return;
			}
			const plan = JSON.parse(planJson) as CorePlan;
			preview = buildPreview(plan, file.name);
			step = 'preview';
		} catch (e) {
			step = 'error';
			errorMessage =
				e instanceof Error ? e.message : 'Could not read the file.';
		}
	}

	/**
	 * Merge the main `Beanconqueror.json` with any chunked
	 * `Beanconqueror_{Beans,Brews}_N.json` files into one JSON object
	 * the core can parse. Returns `null` when no main file is found.
	 *
	 * BC's writer (`src/services/uiExportImportHelper.ts`) puts the
	 * first 500 BREWS / BEANS inline in the main file and overflows
	 * into chunk files; the merge here is the inverse — concatenate
	 * the chunk arrays back onto the main entries.
	 */
	function mergeBcArchive(entries: Record<string, Uint8Array>): string | null {
		// Locate the main file. BC names it `Beanconqueror.json`; tolerate
		// case variation for archives produced by hand-edited tooling.
		const mainKey = Object.keys(entries).find(
			(k) => k.toLowerCase().endsWith('beanconqueror.json')
		);
		if (mainKey === undefined) return null;
		let mainObj: Record<string, unknown>;
		try {
			mainObj = JSON.parse(strFromU8(entries[mainKey])) as Record<string, unknown>;
		} catch {
			return null;
		}

		// Walk chunk files in numeric order, appending to BREWS / BEANS.
		// Pattern: `Beanconqueror_(Brews|Beans)_N.json` per BC's
		// `chunkFileName(file, idx)`. Sort by N so we preserve order.
		interface Chunk {
			key: 'BREWS' | 'BEANS';
			index: number;
			name: string;
		}
		const chunks: Chunk[] = [];
		const re = /Beanconqueror_(Brews|Beans)_(\d+)\.json$/i;
		for (const name of Object.keys(entries)) {
			const m = name.match(re);
			if (!m) continue;
			const key = m[1].toLowerCase() === 'brews' ? 'BREWS' : 'BEANS';
			chunks.push({ key, index: Number(m[2]), name });
		}
		chunks.sort((a, b) => (a.key === b.key ? a.index - b.index : a.key < b.key ? -1 : 1));

		for (const c of chunks) {
			let arr: unknown;
			try {
				arr = JSON.parse(strFromU8(entries[c.name]));
			} catch {
				continue;
			}
			if (!Array.isArray(arr)) continue;
			const existing = Array.isArray(mainObj[c.key]) ? (mainObj[c.key] as unknown[]) : [];
			mainObj[c.key] = [...existing, ...arr];
		}

		return JSON.stringify(mainObj);
	}

	/**
	 * Build the preview object the UI renders. Three dedups happen
	 * here, all against the shell library's current state:
	 *
	 *  1. **BC-uuid skip (resumability)**: if a bean's
	 *     `beanconquerorId` already exists in the library, the bean
	 *     and its photo were imported on a previous (possibly
	 *     interrupted) run; skip silently. This is what makes
	 *     re-importing the same `.zip` after a partial run pick up
	 *     where it left off.
	 *  2. **(roaster, name, roastedOn) triple**: dedup against
	 *     hand-created Crema beans the user may have entered
	 *     manually that line up with a BC bag.
	 *  3. **Roaster re-point**: if an incoming bean references a
	 *     freshly-minted roaster whose NAME matches an existing
	 *     library roaster, rewrite the bean's `roasterId` to the
	 *     existing roaster's id (and drop the duplicate roaster from
	 *     the add-set). Otherwise the bean would land with a dangling
	 *     pointer because we filter the duplicate-name roaster out
	 *     of `newRoasters`.
	 *
	 * Shots are not dedup'd — their UUID makes them unique by
	 * construction. (Could be tightened by a `beanconquerorId`
	 * snapshot on `StoredShot` in a follow-up if users start
	 * re-importing the same history.)
	 */
	function buildPreview(plan: CorePlan, filename: string): Preview {
		// snake_case → camelCase on the wasm-returned beans / roasters
		// so they match the shell's persisted shape (`bagSize`,
		// `roasterId`, …) before we touch anything else.
		const wireBeans = plan.beans.map((b) => snakeToCamel<Bean>(b));
		const wireRoasters = plan.roasters.map((r) => snakeToCamel<Roaster>(r));

		const existingRoasterByLc = new Map<string, Roaster>();
		for (const r of library.roasters) {
			existingRoasterByLc.set(r.name.trim().toLowerCase(), r);
		}
		const existingBeanconquerorIds = new Set<string>();
		for (const b of library.beans) {
			if (b.beanconquerorId) existingBeanconquerorIds.add(b.beanconquerorId);
		}

		// Build a roaster lookup from the wire beans' BC-side ids so we
		// can resolve the new bean's roasterId → roaster name once.
		const wireRoasterById = new Map<string, Roaster>();
		for (const r of wireRoasters) wireRoasterById.set(r.id, r);

		const newBeans: Bean[] = [];
		let duplicatesSkipped = 0;
		// Roasters that ended up referenced by a kept bean — we'll
		// emit only these in `newRoasters` (dropping any name-collision
		// roaster and rewriting the bean's pointer to the live one).
		const keptNewRoasterIds = new Set<string>();
		for (const bean of wireBeans) {
			// Resumability: same BC uuid as a bean already in the library
			// → skip. This lets a re-import of the same .zip pick up
			// where a previous run left off without producing duplicates.
			if (
				bean.beanconquerorId &&
				existingBeanconquerorIds.has(bean.beanconquerorId)
			) {
				duplicatesSkipped += 1;
				continue;
			}

			// Roaster lookup + repoint. The bean's roasterId currently
			// points at a freshly-minted roaster from the core; if the
			// library already has a roaster with the same name, rewrite
			// the pointer to use it.
			let resolvedBean = bean;
			const wireRoaster = bean.roasterId
				? wireRoasterById.get(bean.roasterId) ?? null
				: null;
			const roasterName = wireRoaster?.name ?? '';
			const libRoaster = roasterName
				? existingRoasterByLc.get(roasterName.toLowerCase())
				: null;
			if (libRoaster && wireRoaster && wireRoaster.id !== libRoaster.id) {
				resolvedBean = { ...bean, roasterId: libRoaster.id };
			} else if (wireRoaster) {
				keptNewRoasterIds.add(wireRoaster.id);
			}

			// Soft dedup: same (roaster, name, roastedOn) triple as a
			// library bean → skip. Catches hand-entered Crema beans
			// that match a BC import.
			const isTripleDupe =
				libRoaster &&
				library.beans.some(
					(existing) =>
						existing.roasterId === libRoaster.id &&
						existing.name.trim().toLowerCase() === bean.name.trim().toLowerCase() &&
						(existing.roastedOn ?? null) === (bean.roastedOn ?? null)
				);
			if (isTripleDupe) {
				duplicatesSkipped += 1;
				continue;
			}

			newBeans.push(resolvedBean);
		}

		const newRoasters = wireRoasters.filter((r) => keptNewRoasterIds.has(r.id));

		const newShots: PreparedShot[] = plan.shots.map((imp) => ({
			shot: prepareShot(imp)
		}));

		const droppedCategories = plan.diagnostics.droppedBeanCategories
			.filter((entry) => entry[1] > 0)
			.map(([cat, n]) => `${dropCategoryLabel(cat)} (${n})`);

		// Match the referenced photo filenames against the files the
		// user dropped alongside the ZIP. Filenames are typically
		// `photo_<uuid>.<ext>` and unique, so a Set lookup is enough.
		const droppedNames = new Set(
			droppedPhotoFiles.map((f) => f.name)
		);
		const photosReferenced = plan.diagnostics.bagPhotoFilenames.length;
		const photosMatched = plan.diagnostics.bagPhotoFilenames.filter(
			(name) => droppedNames.has(name)
		).length;

		return {
			filename,
			newBeans,
			newRoasters,
			newShots,
			duplicatesSkipped,
			droppedCategories,
			nonEspressoBrewsSkipped: plan.diagnostics.nonEspressoBrewsSkipped,
			brewsDanglingPreparation: plan.diagnostics.brewsDanglingPreparation,
			photosReferenced,
			photosMatched
		};
	}

	/** Translate the core's category key into a user-facing label. */
	function dropCategoryLabel(key: string): string {
		switch (key) {
			case 'cupping':
				return 'cupping form';
			case 'cupped_flavor':
				return 'flavor wheel tags';
			case 'bean_roast_information':
				return 'self-roast curve';
			case 'attachments':
				return 'bag photos';
			default:
				return key;
		}
	}

	/**
	 * Take one core-shaped `ImportedShot` and build the shell's enriched
	 * `StoredShot` shape — telemetry stays empty (BC's `flow_profile`
	 * sidecar parse is deferred), but the bean snapshot + grinder model +
	 * metadata all come through so a History row renders without needing
	 * the wasm result again.
	 */
	function prepareShot(imp: CoreImportedShot): StoredShot {
		const dur = imp.storedShot.record.duration;
		const durationMs = dur.secs * 1000 + dur.nanos / 1_000_000;
		const bean: ShotBean | null = imp.beanName
			? {
					beanId: imp.beanId,
					roaster: imp.roasterName ?? '',
					type: imp.beanName,
					roastedOn: imp.roastedOn,
					roastLevel: imp.roastLevel
				}
			: null;
		const meta = imp.storedShot.metadata;
		return {
			id: `shot:bc:${crypto.randomUUID()}`,
			completedAt: imp.storedShot.recorded_at,
			profileName: null,
			duration: durationMs,
			dose: meta.dose ?? null,
			peakWeight: null,
			finalWeight: meta.yield_out ?? null,
			peakPressure: 0,
			peakTemp: 0,
			series: [],
			bean,
			grinderModel: imp.grinderModel,
			rating: meta.rating ?? 0,
			notes: meta.notes ?? '',
			tags: []
		};
	}

	async function commit(): Promise<void> {
		if (!preview || step !== 'preview') return;
		// Lock the step BEFORE awaiting anything so a double-click on
		// the Import button can't queue a second pass while the first
		// is in-flight. The button is disabled by the template too,
		// but we belt-and-suspenders here in case the click slipped
		// through (focus → space, drive-by ondblclick handlers, etc).
		const snapshot = preview;
		step = 'committing';
		try {
			// Phase 1 — fast, atomic-ish: bulk-add beans + roasters
			// + shots. Lands them in localStorage in a single envelope
			// write. A crash AFTER this point still leaves a consistent
			// library; the photo loop below picks up on re-import via
			// the resume-photos scan.
			library.bulkAdd(snapshot.newBeans, snapshot.newRoasters);
			for (const prep of snapshot.newShots) {
				history.insertPulled(prep.shot);
			}

			// Phase 2 — photos. Slow (IDB transactions per file +
			// localStorage upsert per bean), so this drives the
			// progress bar. Includes:
			//   (a) newly-added beans whose photos are in `dropped`,
			//   (b) RESUME: beans already in the library (skipped from
			//       newBeans via the BC-uuid dedup) that still have no
			//       `imageRef` and have a matching dropped photo. This
			//       is what makes re-importing the same .zip after a
			//       partial run actually finish the photos for beans
			//       that landed last time but didn't get their image.
			const dropped = new Map(droppedPhotoFiles.map((f) => [f.name, f]));
			const photoTasks: { beanId: string; file: File }[] = [];
			const taskedIds = new Set<string>();
			for (const bean of snapshot.newBeans) {
				const photos = readPhotoFilenames(bean);
				const file = photos
					.map((n) => dropped.get(n))
					.find((f) => f != null);
				if (file) {
					photoTasks.push({ beanId: bean.id, file });
					taskedIds.add(bean.id);
				}
			}
			for (const bean of library.beans) {
				if (bean.imageRef != null || taskedIds.has(bean.id)) continue;
				const photos = readPhotoFilenames(bean);
				if (photos.length === 0) continue;
				const file = photos
					.map((n) => dropped.get(n))
					.find((f) => f != null);
				if (file) {
					photoTasks.push({ beanId: bean.id, file });
					taskedIds.add(bean.id);
				}
			}

			progress = { done: 0, total: photoTasks.length };
			let photosStored = 0;
			for (const task of photoTasks) {
				const ref = beanImageRefFor(task.beanId);
				try {
					await imageStore.put(ref, task.file);
					library.updateBean(task.beanId, { imageRef: ref });
					photosStored += 1;
				} catch {
					// IndexedDB failed (private mode, quota, …) —
					// the bean stays photo-less; a future import of the
					// same .zip can retry via the resume-photos path.
				}
				progress = { done: progress.done + 1, total: progress.total };
			}

			committed = {
				beansImported: snapshot.newBeans.length,
				shotsImported: snapshot.newShots.length,
				duplicatesSkipped: snapshot.duplicatesSkipped,
				photosStored
			};
			step = 'done';
		} catch (e) {
			step = 'error';
			errorMessage =
				e instanceof Error ? e.message : 'Import failed mid-commit.';
		}
	}

	/**
	 * Pull the BC-attached `photo_filenames` list off a bean's open
	 * metadata blob. The core's mapper writes it under
	 * `metadata.beanconqueror.photo_filenames` (an array of strings).
	 * Returns an empty array if the field is missing or wrong-shaped.
	 */
	function readPhotoFilenames(bean: Bean): string[] {
		const meta = bean.metadata as unknown;
		if (!meta || typeof meta !== 'object') return [];
		const bc = (meta as Record<string, unknown>).beanconqueror;
		if (!bc || typeof bc !== 'object') return [];
		const names = (bc as Record<string, unknown>).photo_filenames;
		if (!Array.isArray(names)) return [];
		return names.filter((n): n is string => typeof n === 'string');
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
		<h2 class="bd-title" id="bd-title">Import from Beanconqueror</h2>
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
			<div class="bd-drop-title">Drop a Crema or Beanconqueror export</div>
			<div class="bd-drop-sub">
				A Crema <code>.jsonl</code> round-trips losslessly. A
				Beanconqueror <code>.zip</code> imports the high-value
				subset; drop the photo files alongside for the
				"with photos" variant. Crema is espresso-only — BC
				V60 / AeroPress brews are skipped + counted.
			</div>
			<label class="bd-pickbtn">
				<input
					type="file"
					accept=".zip,.jsonl,application/zip,application/jsonl,image/*"
					multiple
					onchange={onFileChosen}
				/>
				<span>Choose files…</span>
			</label>
		</div>
	{:else if step === 'parsing'}
		<div class="bd-status">
			<i class="ph ph-spinner-gap bd-spinner" aria-hidden="true"></i>
			Parsing your archive…
		</div>
	{:else if step === 'committing'}
		<div class="bd-status bd-status-progress">
			<i class="ph ph-spinner-gap bd-spinner" aria-hidden="true"></i>
			<div class="bd-progress-text">
				{#if progress.total > 0}
					Saving photo {progress.done} of {progress.total}…
				{:else}
					Saving import…
				{/if}
			</div>
			{#if progress.total > 0}
				<div
					class="bd-progress-bar"
					role="progressbar"
					aria-valuemin="0"
					aria-valuemax={progress.total}
					aria-valuenow={progress.done}
				>
					<div
						class="bd-progress-fill"
						style:width="{Math.round((progress.done / progress.total) * 100)}%"
					></div>
				</div>
			{/if}
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
				<div class="bd-stat-n">{preview.newBeans.length}</div>
				<div class="bd-stat-label">beans to import</div>
			</div>
			<div class="bd-stat">
				<div class="bd-stat-n">{preview.newRoasters.length}</div>
				<div class="bd-stat-label">new roasters</div>
			</div>
			<div class="bd-stat">
				<div class="bd-stat-n">{preview.newShots.length}</div>
				<div class="bd-stat-label">espresso shots</div>
			</div>
			{#if preview.photosMatched > 0}
				<div class="bd-stat">
					<div class="bd-stat-n">{preview.photosMatched}</div>
					<div class="bd-stat-label">bag photos</div>
				</div>
			{/if}
			{#if preview.duplicatesSkipped > 0}
				<div class="bd-stat">
					<div class="bd-stat-n">{preview.duplicatesSkipped}</div>
					<div class="bd-stat-label">skipped (duplicates)</div>
				</div>
			{/if}
		</div>
		{#if preview.nonEspressoBrewsSkipped > 0 || preview.brewsDanglingPreparation > 0 || preview.droppedCategories.length > 0 || preview.photosReferenced > preview.photosMatched}
			<div class="bd-dropped">
				<i class="ph ph-info" aria-hidden="true"></i>
				<div>
					<div class="bd-dropped-title">Not migrated:</div>
					<ul>
						{#if preview.nonEspressoBrewsSkipped > 0}
							<li>
								{preview.nonEspressoBrewsSkipped} non-espresso brew{preview.nonEspressoBrewsSkipped === 1 ? '' : 's'}
								(V60 / AeroPress / etc.)
							</li>
						{/if}
						{#if preview.brewsDanglingPreparation > 0}
							<li>
								{preview.brewsDanglingPreparation} brew{preview.brewsDanglingPreparation === 1 ? '' : 's'}
								with missing preparation reference
							</li>
						{/if}
						{#if preview.photosReferenced > 0 && preview.photosMatched < preview.photosReferenced}
							<li>
								{preview.photosReferenced - preview.photosMatched} bag photo{preview.photosReferenced - preview.photosMatched === 1 ? '' : 's'}
								not found alongside the ZIP — drop the photo files together next time
							</li>
						{/if}
						{#each preview.droppedCategories as cat (cat)}
							<li>{cat}</li>
						{/each}
					</ul>
				</div>
			</div>
		{/if}
		<div class="bd-foot">
			<button class="bd-btn" onclick={onClose}>Cancel</button>
			<button
				class="bd-btn bd-btn-primary"
				onclick={commit}
				disabled={step !== 'preview'}
			>
				Import {preview.newBeans.length + preview.newShots.length} item{preview.newBeans.length + preview.newShots.length === 1 ? '' : 's'}
			</button>
		</div>
	{:else if step === 'done' && committed}
		<div class="bd-status">
			<i class="ph-fill ph-check-circle" aria-hidden="true"></i>
			<div>
				<div class="bd-status-title">
					Imported {committed.beansImported} bean{committed.beansImported === 1 ? '' : 's'}
					{#if committed.shotsImported > 0}
						and {committed.shotsImported} shot{committed.shotsImported === 1 ? '' : 's'}
					{/if}
					{#if committed.photosStored > 0}
						(plus {committed.photosStored} photo{committed.photosStored === 1 ? '' : 's'})
					{/if}
				</div>
				{#if committed.duplicatesSkipped > 0}
					<div>Skipped {committed.duplicatesSkipped} duplicate bean{committed.duplicatesSkipped === 1 ? '' : 's'}.</div>
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
	.bd-status-progress {
		flex-direction: column;
		align-items: stretch;
		gap: 10px;
		padding: 28px 24px;
	}
	.bd-status-progress i {
		align-self: center;
	}
	.bd-progress-text {
		text-align: center;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.bd-progress-bar {
		height: 6px;
		border-radius: 999px;
		background: rgba(var(--tint-rgb), 0.12);
		overflow: hidden;
	}
	.bd-progress-fill {
		height: 100%;
		background: var(--copper-400);
		transition: width 120ms var(--ease);
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
