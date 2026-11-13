/**
 * `#lib/restore/flow` — a restore from the user's side: plan, the report
 * where the plan or the mode needs a yes, then the run and how it went. The
 * dialogs and the run are passed in, so the Settings section and the tests
 * drive the same steps.
 */
import { Verdict, type RestorePlan } from '#lib/core/crema-core.js';
import type { RestoreOutcome, WebMode } from './engine';
import { confirmText, deletedHere, outcomeMessage, refusalMessage } from './report';

/** What the flow needs from the app. */
export interface RestoreFlowDeps {
	plan: (reviveDeleted: boolean) => Promise<RestorePlan>;
	run: (reviveDeleted: boolean, expected: RestorePlan) => Promise<RestoreOutcome>;
	/** Two answers and a dismiss (`null`). */
	choose: (o: { title: string; message: string; primaryLabel: string; secondaryLabel: string }) => Promise<
		'primary' | 'secondary' | null
	>;
	confirm: (o: { title: string; message: string; confirmLabel: string }) => Promise<boolean>;
	/** Run a backup and say how it went: Replace's "Back up first". */
	backUp: () => Promise<string>;
	/** A line for the user while it runs. */
	say: (line: string) => void;
}

/**
 * A clean Merge runs at once. Replace offers Back up first. A Merge whose
 * backup has shots deleted here offers to bring them back, and then shows
 * the numbers that would bring before running them. A device that changed
 * while the report was open shows the new numbers and asks again. Returns
 * the line to show after, or the outcome for the caller to finish.
 */
export async function restoreFlow(
	mode: WebMode,
	deps: RestoreFlowDeps
): Promise<{ line: string; outcome: RestoreOutcome | null }> {
	let revive = false;
	let plan = await deps.plan(false);
	let ask = plan.verdict === Verdict.Confirm || deletedHere(plan) > 0;
	for (;;) {
		if (plan.verdict === Verdict.Refused) return { line: refusalMessage(plan), outcome: null };
		if (ask) {
			const { title, message } = confirmText(plan);
			if (mode === 'wipe') {
				const choice = await deps.choose({ title, message, primaryLabel: 'Back up first', secondaryLabel: 'Replace' });
				if (choice === null) return { line: 'Nothing was changed.', outcome: null };
				if (choice === 'primary') {
					deps.say(await deps.backUp());
					plan = await deps.plan(revive);
					continue;
				}
			} else if (deletedHere(plan) > 0 && !revive) {
				const choice = await deps.choose({
					title,
					message,
					primaryLabel: 'Restore',
					secondaryLabel: 'Bring them back too'
				});
				if (choice === null) return { line: 'Nothing was changed.', outcome: null };
				if (choice === 'secondary') {
					// Bringing them back changes the numbers: show those first.
					revive = true;
					plan = await deps.plan(true);
					continue;
				}
			} else if (!(await deps.confirm({ title, message, confirmLabel: 'Restore' }))) {
				return { line: 'Nothing was changed.', outcome: null };
			}
		}
		deps.say('Restoring… Other Crema tabs will reload when this finishes.');
		const outcome = await deps.run(revive, plan);
		if (outcome.kind === 'changed') {
			plan = outcome.plan;
			ask = true;
			deps.say(outcomeMessage(outcome));
			continue;
		}
		return { line: outcomeMessage(outcome), outcome };
	}
}

