/**
 * `$lib/utils/power-standards` — embedded ISO-3166 alpha-2 → mains
 * voltage / frequency lookup.
 *
 * Used by `MainsConfirmModal` to surface "we think your country expects
 * 230 V / 50 Hz, but you're setting 120 V" warnings when the user lands
 * the heater-voltage (`MMR 0x803834`) or AC-mains-frequency override.
 *
 * The lookup is **advisory only** — the user is the source of truth (they
 * are standing next to the wall outlet). The modal still requires a
 * type-to-confirm regardless of mismatch.
 *
 * ## Omitted fields
 *
 * Four entries deliberately omit `voltage` and/or `hz` because the
 * country has more than one in-use standard:
 *
 * - `BO` (Bolivia) — La Paz uses 115 V; the rest of the country uses
 *   230 V. Both 50 Hz. Both fields omitted to suppress the mismatch
 *   banner; the modal shows a "your region has multiple standards"
 *   note instead.
 * - `BR` (Brazil) — varies by city between 127 V and 220 V; 60 Hz.
 *   Both fields omitted.
 * - `GY` (Guyana) — varies between 110 V and 240 V; uses 60 Hz, but
 *   the voltage variation is wide enough that we omit both to keep
 *   the UX symmetric.
 * - `JP` (Japan) — 100 V everywhere, but Eastern Japan (Tokyo-area)
 *   is 50 Hz and Western (Osaka-area) is 60 Hz. `voltage` is set
 *   (100), `hz` is omitted.
 *
 * Source: Wikipedia's "Mains electricity by country" article + IEC
 * 60038 standards (cross-checked against utility regulator websites
 * where available). Last reconciled 2026-05-23.
 */

/**
 * The mains standard for one country. Either field may be omitted when
 * the country uses multiple standards (see the module docstring for the
 * four cases).
 */
export interface PowerStandard {
	/** Nominal mains voltage, V. */
	voltage?: number;
	/** Nominal mains frequency, Hz. */
	hz?: number;
}

/**
 * ISO-3166 alpha-2 code → mains standard. ~225 entries covering every
 * country / territory recognised by ISO. Missing keys = "no data";
 * callers should treat the absence as "do not surface a mismatch".
 */
export const POWER_STANDARDS: Record<string, PowerStandard> = {
	AD: { voltage: 230, hz: 50 },
	AE: { voltage: 230, hz: 50 },
	AF: { voltage: 220, hz: 50 },
	AG: { voltage: 230, hz: 60 },
	AI: { voltage: 230, hz: 60 },
	AL: { voltage: 230, hz: 50 },
	AM: { voltage: 230, hz: 50 },
	AO: { voltage: 220, hz: 50 },
	AR: { voltage: 220, hz: 50 },
	AS: { voltage: 120, hz: 60 },
	AT: { voltage: 230, hz: 50 },
	AU: { voltage: 230, hz: 50 },
	AW: { voltage: 127, hz: 60 },
	AX: { voltage: 230, hz: 50 },
	AZ: { voltage: 220, hz: 50 },
	BA: { voltage: 230, hz: 50 },
	BB: { voltage: 115, hz: 50 },
	BD: { voltage: 220, hz: 50 },
	BE: { voltage: 230, hz: 50 },
	BF: { voltage: 220, hz: 50 },
	BG: { voltage: 230, hz: 50 },
	BH: { voltage: 230, hz: 50 },
	BI: { voltage: 220, hz: 50 },
	BJ: { voltage: 220, hz: 50 },
	BL: { voltage: 230, hz: 60 },
	BM: { voltage: 120, hz: 60 },
	BN: { voltage: 240, hz: 50 },
	// BO (Bolivia) — La Paz 115 V vs. rest 230 V; both 50 Hz. Omit both.
	BO: {},
	BQ: { voltage: 127, hz: 50 },
	// BR (Brazil) — 127 V or 220 V depending on city; 60 Hz. Omit both.
	BR: {},
	BS: { voltage: 120, hz: 60 },
	BT: { voltage: 230, hz: 50 },
	BW: { voltage: 230, hz: 50 },
	BY: { voltage: 230, hz: 50 },
	BZ: { voltage: 110, hz: 60 },
	CA: { voltage: 120, hz: 60 },
	CD: { voltage: 220, hz: 50 },
	CF: { voltage: 220, hz: 50 },
	CG: { voltage: 230, hz: 50 },
	CH: { voltage: 230, hz: 50 },
	CI: { voltage: 230, hz: 50 },
	CK: { voltage: 240, hz: 50 },
	CL: { voltage: 220, hz: 50 },
	CM: { voltage: 220, hz: 50 },
	CN: { voltage: 220, hz: 50 },
	CO: { voltage: 120, hz: 60 },
	CR: { voltage: 120, hz: 60 },
	CU: { voltage: 110, hz: 60 },
	CV: { voltage: 220, hz: 50 },
	CW: { voltage: 127, hz: 50 },
	CY: { voltage: 230, hz: 50 },
	CZ: { voltage: 230, hz: 50 },
	DE: { voltage: 230, hz: 50 },
	DJ: { voltage: 220, hz: 50 },
	DK: { voltage: 230, hz: 50 },
	DM: { voltage: 230, hz: 50 },
	DO: { voltage: 120, hz: 60 },
	DZ: { voltage: 230, hz: 50 },
	EC: { voltage: 120, hz: 60 },
	EE: { voltage: 230, hz: 50 },
	EG: { voltage: 220, hz: 50 },
	ER: { voltage: 230, hz: 50 },
	ES: { voltage: 230, hz: 50 },
	ET: { voltage: 220, hz: 50 },
	FI: { voltage: 230, hz: 50 },
	FJ: { voltage: 240, hz: 50 },
	FK: { voltage: 240, hz: 50 },
	FM: { voltage: 120, hz: 60 },
	FO: { voltage: 230, hz: 50 },
	FR: { voltage: 230, hz: 50 },
	GA: { voltage: 220, hz: 50 },
	GB: { voltage: 230, hz: 50 },
	GD: { voltage: 230, hz: 50 },
	GE: { voltage: 220, hz: 50 },
	GF: { voltage: 220, hz: 50 },
	GG: { voltage: 230, hz: 50 },
	GH: { voltage: 230, hz: 50 },
	GI: { voltage: 230, hz: 50 },
	GL: { voltage: 230, hz: 50 },
	GM: { voltage: 230, hz: 50 },
	GN: { voltage: 220, hz: 50 },
	GP: { voltage: 230, hz: 50 },
	GQ: { voltage: 220, hz: 50 },
	GR: { voltage: 230, hz: 50 },
	GT: { voltage: 120, hz: 60 },
	GU: { voltage: 120, hz: 60 },
	// GY (Guyana) — 110 V or 240 V in use. Omit both for symmetric UX.
	GY: {},
	HK: { voltage: 220, hz: 50 },
	HN: { voltage: 120, hz: 60 },
	HR: { voltage: 230, hz: 50 },
	HT: { voltage: 110, hz: 60 },
	HU: { voltage: 230, hz: 50 },
	ID: { voltage: 230, hz: 50 },
	IE: { voltage: 230, hz: 50 },
	IL: { voltage: 230, hz: 50 },
	IM: { voltage: 230, hz: 50 },
	IN: { voltage: 230, hz: 50 },
	IQ: { voltage: 230, hz: 50 },
	IR: { voltage: 230, hz: 50 },
	IS: { voltage: 230, hz: 50 },
	IT: { voltage: 230, hz: 50 },
	JE: { voltage: 230, hz: 50 },
	JM: { voltage: 110, hz: 50 },
	JO: { voltage: 230, hz: 50 },
	// JP (Japan) — 100 V everywhere, but Eastern is 50 Hz and Western
	// is 60 Hz. Keep voltage, omit hz.
	JP: { voltage: 100 },
	KE: { voltage: 240, hz: 50 },
	KG: { voltage: 220, hz: 50 },
	KH: { voltage: 230, hz: 50 },
	KI: { voltage: 240, hz: 50 },
	KM: { voltage: 220, hz: 50 },
	KN: { voltage: 230, hz: 60 },
	KP: { voltage: 220, hz: 50 },
	KR: { voltage: 220, hz: 60 },
	KW: { voltage: 240, hz: 50 },
	KY: { voltage: 120, hz: 60 },
	KZ: { voltage: 220, hz: 50 },
	LA: { voltage: 230, hz: 50 },
	LB: { voltage: 220, hz: 50 },
	LC: { voltage: 230, hz: 50 },
	LI: { voltage: 230, hz: 50 },
	LK: { voltage: 230, hz: 50 },
	LR: { voltage: 120, hz: 60 },
	LS: { voltage: 220, hz: 50 },
	LT: { voltage: 230, hz: 50 },
	LU: { voltage: 230, hz: 50 },
	LV: { voltage: 230, hz: 50 },
	LY: { voltage: 230, hz: 50 },
	MA: { voltage: 220, hz: 50 },
	MC: { voltage: 230, hz: 50 },
	MD: { voltage: 230, hz: 50 },
	ME: { voltage: 230, hz: 50 },
	MF: { voltage: 230, hz: 60 },
	MG: { voltage: 220, hz: 50 },
	MH: { voltage: 120, hz: 60 },
	MK: { voltage: 230, hz: 50 },
	ML: { voltage: 220, hz: 50 },
	MM: { voltage: 230, hz: 50 },
	MN: { voltage: 230, hz: 50 },
	MO: { voltage: 220, hz: 50 },
	MP: { voltage: 120, hz: 60 },
	MQ: { voltage: 220, hz: 50 },
	MR: { voltage: 220, hz: 50 },
	MS: { voltage: 230, hz: 60 },
	MT: { voltage: 230, hz: 50 },
	MU: { voltage: 230, hz: 50 },
	MV: { voltage: 230, hz: 50 },
	MW: { voltage: 230, hz: 50 },
	MX: { voltage: 127, hz: 60 },
	MY: { voltage: 240, hz: 50 },
	MZ: { voltage: 220, hz: 50 },
	NA: { voltage: 220, hz: 50 },
	NC: { voltage: 220, hz: 50 },
	NE: { voltage: 220, hz: 50 },
	NF: { voltage: 230, hz: 50 },
	NG: { voltage: 230, hz: 50 },
	NI: { voltage: 120, hz: 60 },
	NL: { voltage: 230, hz: 50 },
	NO: { voltage: 230, hz: 50 },
	NP: { voltage: 230, hz: 50 },
	NR: { voltage: 240, hz: 50 },
	NU: { voltage: 240, hz: 50 },
	NZ: { voltage: 230, hz: 50 },
	OM: { voltage: 240, hz: 50 },
	PA: { voltage: 120, hz: 60 },
	PE: { voltage: 220, hz: 60 },
	PF: { voltage: 220, hz: 60 },
	PG: { voltage: 240, hz: 50 },
	PH: { voltage: 220, hz: 60 },
	PK: { voltage: 230, hz: 50 },
	PL: { voltage: 230, hz: 50 },
	PM: { voltage: 230, hz: 50 },
	PN: { voltage: 230, hz: 50 },
	PR: { voltage: 120, hz: 60 },
	PS: { voltage: 230, hz: 50 },
	PT: { voltage: 230, hz: 50 },
	PW: { voltage: 120, hz: 60 },
	PY: { voltage: 220, hz: 50 },
	QA: { voltage: 240, hz: 50 },
	RE: { voltage: 230, hz: 50 },
	RO: { voltage: 230, hz: 50 },
	RS: { voltage: 230, hz: 50 },
	RU: { voltage: 220, hz: 50 },
	RW: { voltage: 230, hz: 50 },
	SA: { voltage: 230, hz: 60 },
	SB: { voltage: 240, hz: 50 },
	SC: { voltage: 240, hz: 50 },
	SD: { voltage: 230, hz: 50 },
	SE: { voltage: 230, hz: 50 },
	SG: { voltage: 230, hz: 50 },
	SH: { voltage: 240, hz: 50 },
	SI: { voltage: 230, hz: 50 },
	SK: { voltage: 230, hz: 50 },
	SL: { voltage: 230, hz: 50 },
	SM: { voltage: 230, hz: 50 },
	SN: { voltage: 230, hz: 50 },
	SO: { voltage: 220, hz: 50 },
	SR: { voltage: 127, hz: 60 },
	SS: { voltage: 230, hz: 50 },
	ST: { voltage: 220, hz: 50 },
	SV: { voltage: 115, hz: 60 },
	SX: { voltage: 110, hz: 60 },
	SY: { voltage: 220, hz: 50 },
	SZ: { voltage: 230, hz: 50 },
	TC: { voltage: 120, hz: 60 },
	TD: { voltage: 220, hz: 50 },
	TG: { voltage: 220, hz: 50 },
	TH: { voltage: 220, hz: 50 },
	TJ: { voltage: 220, hz: 50 },
	TK: { voltage: 230, hz: 50 },
	TL: { voltage: 220, hz: 50 },
	TM: { voltage: 220, hz: 50 },
	TN: { voltage: 230, hz: 50 },
	TO: { voltage: 240, hz: 50 },
	TR: { voltage: 230, hz: 50 },
	TT: { voltage: 115, hz: 60 },
	TV: { voltage: 220, hz: 50 },
	TW: { voltage: 110, hz: 60 },
	TZ: { voltage: 230, hz: 50 },
	UA: { voltage: 230, hz: 50 },
	UG: { voltage: 240, hz: 50 },
	US: { voltage: 120, hz: 60 },
	UY: { voltage: 230, hz: 50 },
	UZ: { voltage: 220, hz: 50 },
	VA: { voltage: 230, hz: 50 },
	VC: { voltage: 230, hz: 50 },
	VE: { voltage: 120, hz: 60 },
	VG: { voltage: 110, hz: 60 },
	VI: { voltage: 110, hz: 60 },
	VN: { voltage: 220, hz: 50 },
	VU: { voltage: 220, hz: 50 },
	WF: { voltage: 220, hz: 50 },
	WS: { voltage: 230, hz: 50 },
	XK: { voltage: 230, hz: 50 },
	YE: { voltage: 230, hz: 50 },
	YT: { voltage: 230, hz: 50 },
	ZA: { voltage: 230, hz: 50 },
	ZM: { voltage: 230, hz: 50 },
	ZW: { voltage: 220, hz: 50 }
};

/**
 * Human-readable country name for an ISO-3166 alpha-2 code, using the
 * browser's `Intl.DisplayNames` API (broad support since 2021). Falls
 * back to the bare code if the browser lacks the API or returns the
 * code itself.
 */
export function countryName(code: string): string {
	if (typeof Intl === 'undefined' || !('DisplayNames' in Intl)) return code;
	try {
		const display = new Intl.DisplayNames(['en'], { type: 'region' });
		const name = display.of(code);
		return name && name !== code ? name : code;
	} catch {
		return code;
	}
}
