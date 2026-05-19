/**
 * `$lib/bean` — the **current bean**: the bag of coffee in use right now.
 *
 * Bean identity is decoupled from the profile model (a recipe and a bag of
 * coffee have different lifecycles — see `model.ts`). The current bean is real
 * and persisted in `localStorage`; a snapshot of it is stamped onto each
 * recorded shot by `lib/history`.
 */

export { type Bean, blankBean, daysOffRoast } from './model';

export { BeanStore, getBeanStore } from './store.svelte';
