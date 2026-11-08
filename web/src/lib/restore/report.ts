/**
 * `#lib/restore/report` — a restore plan in words (restore-safety design,
 * "User-visible changes"): the refusal, the report before a restore that needs
 * the user's yes, and the line after.
 */
import { PlanCode, RestoreMode, type PlanNote, type RestorePlan } from '#lib/core/crema-core.js';
import type { RestoreOutcome, RestoreStep } from './engine';

const plural = (n: number, one: string, many = `${one}s`): string => `${n} ${n === 1 ? one : many}`;

/** "12 shots, 2 beans and 1 profile", or `null` when it's all zeros. */
function counts(parts: [number, string][]): string | null {
	const named = parts.filter(([n]) => n > 0).map(([n, what]) => plural(n, what));
	if (named.length === 0) return null;
	return named.length === 1 ? named[0] : `${named.slice(0, -1).join(', ')} and ${named.at(-1)}`;
}

function note(plan: RestorePlan, code: PlanCode): PlanNote | undefined {
	return [...plan.refusals, ...plan.warnings].find((n) => n.code === code);
}

const STORE_NAMES: Record<string, string> = {
	shots: 'shot history',
	beans: 'bean library',
	profiles: 'custom profiles',
	recipes: 'brew recipes',
	customMethods: 'brew methods'
};

/** Why nothing was changed. */
export function refusalMessage(plan: RestorePlan): string {
	const first = plan.refusals[0];
	const replace = plan.mode === RestoreMode.Replace;
	const capacity = 300;
	switch (first?.code) {
		case PlanCode.NotABackup:
			return 'Not a Crema backup file.';
		case PlanCode.ShotsExport:
			return 'This is a shots export: use Import.';
		case PlanCode.NewerFormat:
			return 'This backup needs a newer version of Crema. Nothing was changed.';
		case PlanCode.StoreUnavailable:
		case PlanCode.StoreLoading:
			return `Couldn't read your ${STORE_NAMES[first.store ?? ''] ?? 'data'} just now, so nothing was changed.`;
		case PlanCode.MachineBusy:
			return 'Finish the shot or firmware update first. Nothing was changed.';
		case PlanCode.BadLines:
		case PlanCode.HeaderDamaged:
		case PlanCode.CountMismatch: {
			const bad = note(plan, PlanCode.BadLines)?.count ?? 0;
			const lines = bad > 0 ? ` (${plural(bad, "line")} can't be read)` : '';
			return `This backup is damaged${lines}, so nothing was changed.${replace ? " Merge can still add what's readable." : ''}`;
		}
		case PlanCode.OverCapacity:
			return `This backup has ${first.count ?? 0} shots; Crema keeps ${capacity} for now. Nothing was changed.`;
		case PlanCode.EmptyBackup:
			return 'This backup has no shots, beans or profiles, so Replace would empty this device. Nothing was changed. Use Merge, or Erase all data first.';
		default:
			return 'This backup can’t be restored. Nothing was changed.';
	}
}

function sourceLine(plan: RestorePlan): string {
	const from = plan.source.deviceLabel || 'Crema';
	const at = plan.source.createdAt
		? new Date(plan.source.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
		: '';
	return at ? `${from}, ${at}` : from;
}

/** The warnings a report has to show, in words. */
function warningLines(plan: RestorePlan): string[] {
	const lines: string[] = [];
	for (const w of plan.warnings) {
		switch (w.code) {
			case PlanCode.BadLines:
				lines.push(`${plural(w.count ?? 0, 'line')} in this backup can't be read and will be skipped.`);
				break;
			case PlanCode.HeaderDamaged:
				lines.push("This backup's first line is damaged; what's readable will be added.");
				break;
			case PlanCode.CountMismatch:
				lines.push(`This backup's ${STORE_NAMES[w.store ?? ''] ?? 'data'} isn't all there.`);
				break;
			case PlanCode.NotAdded: {
				const added = plan.shots.add.length;
				lines.push(
					added === 0
						? 'History is full at 300 shots, so no shots will be added. None of yours are removed.'
						: `Adds the newest ${added} of ${added + (w.count ?? 0)} shots: Crema keeps 300 for now. None of yours are removed.`
				);
				break;
			}
			case PlanCode.Omitted:
				lines.push(`This backup left out ${plural(w.count ?? 0, 'item')}.`);
				break;
			default:
				break;
		}
	}
	return lines;
}

/** The report before a restore that needs the user's yes. */
export function confirmText(plan: RestorePlan): { title: string; message: string } {
	if (plan.mode === RestoreMode.Replace) {
		const gone = counts([
			[plan.shots.remove.length, 'shot'],
			[plan.beans.remove.length, 'bean'],
			[plan.profiles.remove.length, 'profile'],
			[plan.recipes.remove.length, 'recipe']
		]);
		const pinned = plan.shots.pinnedRemoved > 0 ? ` (${plan.shots.pinnedRemoved} pinned)` : '';
		const set = counts([
			[plan.shots.add.length + plan.shots.overwrite.length + plan.shots.revive.length, 'shot'],
			[plan.beans.add.length + plan.beans.overwrite.length, 'bean'],
			[plan.profiles.add.length + plan.profiles.overwrite.length, 'profile']
		]);
		return {
			title: 'Replace everything on this device?',
			message: [
				`From ${sourceLine(plan)}.`,
				gone ? `Deletes ${gone}${pinned} that aren't in this backup.` : null,
				set ? `Sets up ${set}.` : null,
				...warningLines(plan),
				"This can't be undone."
			]
				.filter(Boolean)
				.join(' ')
		};
	}
	const adds = counts([
		[plan.shots.add.length + plan.shots.revive.length, 'shot'],
		[plan.beans.add.length, 'bean'],
		[plan.roasters.add.length, 'roaster'],
		[plan.profiles.add.length, 'profile'],
		[plan.recipes.add.length, 'recipe']
	]);
	const keeps = counts([[plan.shots.keep, 'shot'], [plan.beans.keep, 'bean']]);
	const deleted = plan.shots.deletedHere.length;
	return {
		title: `Restore from ${sourceLine(plan)}?`,
		message: [
			adds ? `Adds ${adds}.` : 'Adds nothing new.',
			plan.shots.fill.length > 0 ? `Fills in ${plural(plan.shots.fill.length, 'missing curve')}.` : null,
			keeps ? `Keeps ${keeps} and your settings as they are.` : null,
			...warningLines(plan),
			deleted > 0
				? `${plural(deleted, 'shot')} in this backup ${deleted === 1 ? 'was' : 'were'} deleted here and stay${deleted === 1 ? 's' : ''} deleted.`
				: null
		]
			.filter(Boolean)
			.join(' ')
	};
}

const STEP_NAMES: Record<RestoreStep, string> = {
	marker: 'the restore record',
	tombstones: 'deleted shots',
	photos: 'bean photos',
	recipes: 'recipes',
	beans: 'beans',
	profiles: 'profiles',
	shots: 'shots',
	settings: 'settings'
};

/** The line after a restore, whichever way it ended. */
export function outcomeMessage(outcome: RestoreOutcome): string {
	switch (outcome.kind) {
		case 'busy':
			return 'A restore is already running in another tab.';
		case 'refused':
			return refusalMessage(outcome.plan);
		case 'changed':
			return 'This device changed while you were deciding. Here are the new numbers.';
		case 'unconfirmed':
			return 'Nothing was changed: this restore needs your yes first.';
		case 'stopped': {
			const landed = outcome.landed.filter((s) => s !== 'marker' && s !== 'tombstones').map((s) => STEP_NAMES[s]);
			const done = landed.length > 0 ? ` ${landed.join(', ')} were restored; the rest weren't.` : '';
			return `Restore stopped: storage may be full.${done} Free up space and run it again.`;
		}
		case 'done': {
			const plan = outcome.plan;
			const added = counts([
				[plan.shots.add.length + plan.shots.revive.length + plan.shots.overwrite.length, 'shot'],
				[plan.beans.add.length + plan.beans.overwrite.length, 'bean'],
				[plan.profiles.add.length + plan.profiles.overwrite.length, 'profile'],
				[plan.recipes.add.length + plan.recipes.overwrite.length, 'recipe']
			]);
			const parts = [added ? `Restored ${added}.` : 'Restored: nothing new to add.'];
			if (plan.mode === RestoreMode.Merge && plan.shots.keep > 0) {
				parts.push(`${plural(plan.shots.keep, 'shot')} already here ${plan.shots.keep === 1 ? 'was' : 'were'} kept.`);
			}
			if (plan.shots.fill.length > 0) parts.push(`Filled in ${plural(plan.shots.fill.length, 'curve')}.`);
			if (outcome.curvesFailed > 0) parts.push(`${plural(outcome.curvesFailed, 'curve')} couldn't be saved.`);
			if (outcome.singletonsSkipped) {
				parts.push("A shot was still running, so settings and maintenance weren't changed. Run it again to apply them.");
			}
			return parts.join(' ');
		}
	}
}

/** Shots the backup has that were deleted here: the report can bring them back. */
export function deletedHere(plan: RestorePlan): number {
	return plan.shots.deletedHere.length;
}
