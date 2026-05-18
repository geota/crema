/**
 * `$lib/ble` — the Web Bluetooth layer: a transport abstraction plus a DE1
 * manager and a scale manager, mirroring the Android shell's `ble` package.
 *
 * Unlike Android, there is no scanner: Web Bluetooth's `requestDevice()` is
 * both discovery and selection, and must run inside a user gesture — so the
 * managers' `connect()` methods are wired straight to button handlers.
 */

export { De1Uuids } from './de1-uuids';
export {
	BleDevice,
	requestDevice,
	isWebBluetoothSupported,
	type ConnState,
	type BleNotification,
	type NotificationSink
} from './transport';
export { De1Manager, type De1State, type De1ManagerCallbacks } from './de1';
export { ScaleManager, type ScaleState, type ScaleManagerCallbacks } from './scale';
