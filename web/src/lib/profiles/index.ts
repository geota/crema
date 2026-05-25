/**
 * `$lib/profiles` — the profile library: the working model, the
 * localStorage-backed store, and the curve-editor geometry.
 *
 * Built-in profiles are real (sourced from the wasm core); custom profiles are
 * real and persisted in `localStorage`. See `store.svelte.ts` for the split.
 */

export {
	type CremaProfile,
	type ProfileSegment,
	type SegmentMode,
	type SegmentRamp,
	type SegmentExit,
	type SegmentLimiter,
	type ExitMetric,
	type ExitCompare,
	type TempSensor,
	type Roast,
	type BeverageType,
	uid,
	newProfileId,
	ratioLabel,
	relativeLastUsed,
	totalTime,
	preinfuseSeconds,
	sparkShape,
	segmentToStep,
	fromCoreProfile,
	toCoreProfile,
	defaultSegments,
	blankProfile,
	duplicateProfile
} from './model';

export { ProfileStore, getProfileStore } from './store.svelte';

export { profileFingerprint, type ProfileFingerprintQc } from './fingerprint';

export {
	Y_MAX,
	PAD_X,
	PAD_Y,
	type CurveGeometry,
	type BoundaryDot,
	type CurveSamples,
	geometry,
	xFor,
	yFor,
	timeForX,
	valueForY,
	curvePath,
	boundaryDots,
	activeBand,
	flowGhostPath,
	sampleCurve,
	svgToSegment
} from './curve';
