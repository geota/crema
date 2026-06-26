// See https://svelte.dev/docs/kit/types#app.d.ts for information about these interfaces.
declare global {
	/** Build-time app version, injected by Vite from package.json (see
	 *  vite.config.ts `define`). Backup header source — review #06 F35. */
	const __APP_VERSION__: string;
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}
}

export {};
