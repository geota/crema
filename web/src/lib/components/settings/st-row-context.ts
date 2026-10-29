/**
 * `StRow` -> control context: the id of the row's visible title, so a control
 * inside the row (e.g. `StSegment`) can name itself after it ("Theme") without
 * every call site repeating the title (final cohort review 07 N3).
 */
import { createContext } from 'svelte';

export interface StRowContext {
	/** DOM id of the row title element. */
	readonly titleId: string;
}

export const [getStRowContext, setStRowContext, hasStRowContext] = createContext<StRowContext>();
