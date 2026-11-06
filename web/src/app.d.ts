// See https://svelte.dev/docs/kit/types#app.d.ts for information about these interfaces.
declare global {
	/** Build-time app version, injected by Vite from package.json (see
	 *  vite.config.ts `define`). Backup header source — review #06 F35. */
	const __APP_VERSION__: string;
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		interface PageState {
			/** Set on an editor entry opened from the Beans list ($lib/beans/editor-nav). */
			fromBeansList?: boolean;
			/** Phone History: the shot whose detail screen this entry shows ($lib/shell/phone-nav). */
			historyShot?: string;
			/** Phone Beans: the bag whose detail screen this entry shows ($lib/shell/phone-nav). */
			beanDetail?: string;
			/** Phone Settings: a section screen opened from the section list ($lib/shell/phone-nav). */
			fromSettingsList?: boolean;
		}
		// interface Platform {}
	}
}

export {};
