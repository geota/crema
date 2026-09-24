/**
 * `$lib/decent` — upload shots to the owner's Decent Espresso account
 * (decentespresso.com shot history + charts; geota/crema#84).
 */
export {
	DEFAULT_DECENT_ACCOUNT,
	getDecentCredentials,
	isDecentLinked,
	linkDecentAccount,
	onDecentAccountChange,
	readDecentAccount,
	setDecentShotState,
	unlinkDecentAccount,
	updateDecentAccount,
	writeDecentAccount,
	type DecentAccountState,
	type DecentLastUpload
} from './account';
export {
	DECENT_BASE,
	DECENT_HISTORY_URL,
	DecentAuthError,
	DecentNetworkError,
	DecentRejectedError,
	decentFetchMachines,
	decentLogin,
	decentShotViewUrl,
	decentUploadShot,
	decentVerify,
	isDecentApiError,
	isDecentRecoverable,
	type DecentApiError,
	type DecentCredentials,
	type DecentMachine,
	type DecentUploadResult
} from './api';
export { DecentRecordError, decentModelName, decentShotRecord, shotDurationSeconds } from './shot-record';
export {
	MIN_SHOT_SECONDS,
	describeDecentDrain,
	isDecentDrainRunning,
	isPulledShot,
	liveMachineIdentity,
	pushShotToDecent,
	reportDecentOutcome,
	retryPendingDecentUploads,
	unsentDecentShots,
	uploadAndReportDecent,
	uploadShotToDecent,
	uploadUnsentDecentShots,
	type DecentBacklogContext,
	type DecentDrainResult,
	type DecentDrainStop,
	type DecentUploadError,
	type DecentUploadOptions,
	type DecentUploadOutcome
} from './upload';
