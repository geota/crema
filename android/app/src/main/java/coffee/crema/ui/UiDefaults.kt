package coffee.crema.ui

/**
 * Default scale beeper-volume step shown before the first live reading.
 *
 * The scale's true volume is decoded live from every Bookoo weight
 * notification (`Event.ScaleReading.device_volume`) and projected into
 * [MainUiState.scaleVolume] as soon as it arrives — this default is only the
 * fallback step the control shows in the brief window before that first
 * reading. The control's range is taken from `ScaleCapabilities.volume`.
 */
const val DEFAULT_SCALE_VOLUME: Int = 3

/**
 * Default scale auto-standby timeout (minutes) shown before the first live
 * reading.
 *
 * Like [DEFAULT_SCALE_VOLUME], this is only the fallback shown until the first
 * `Event.ScaleReading.device_standby` arrives, after which the control
 * tracks the scale's real value. The range comes from
 * `ScaleCapabilities.standby_minutes`.
 */
const val DEFAULT_SCALE_STANDBY_MINUTES: Int = 15

/**
 * Battery percentage at or below which the one-per-connection "charge your
 * scale" warning fires (issue #29) — early enough to charge before a session,
 * late enough not to nag. Web parity: `SCALE_LOW_BATTERY_PCT` in
 * `app.svelte.ts`.
 */
const val SCALE_LOW_BATTERY_PCT = 25

/** Minimum gap between two automatic release-check attempts (issue #36). */
const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
