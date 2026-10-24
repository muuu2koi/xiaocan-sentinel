// 原生桥封装: H5 运行在原生 WebView 壳内, 通过 window.XcBridge 调用原生能力
export function hasBridge() {
	return typeof window !== 'undefined' && !!window.XcBridge;
}

export function getConfig() {
	if (!hasBridge()) {
		return { lat: '23.020500', lng: '113.751800', running: false, switch_all: true, switch_keyword: true, keywords: '', interval_min: '3', quiet_start: '02:00', quiet_end: '07:30', token: '' };
	}
	try { return JSON.parse(window.XcBridge.getConfig()); } catch (e) { return {}; }
}

export function setConfig(patch) {
	if (hasBridge()) window.XcBridge.setConfig(JSON.stringify(patch));
}

export function startMonitor() { if (hasBridge()) window.XcBridge.startMonitor(); }
export function stopMonitor() { if (hasBridge()) window.XcBridge.stopMonitor(); }
export function openXiaocan() { if (hasBridge()) window.XcBridge.openXiaocan(); }
export function refreshLocation() { if (hasBridge()) window.XcBridge.refreshLocation(); }
export function toast(msg) { if (hasBridge()) window.XcBridge.toast(String(msg)); }

const DEMO = [
	{ promotion_id: 9001, same_group_id: 1001, store_id: 101, __new: true,
		store: { name: '茶百道（万科广场店）', icon: 'https://img.meituan.net/content/dbc92c270258856c76ef2802c112ab4d129941.jpg', store_platform: 1 }, order_money: 2000, user_rebate: 1500, left_number: 2, rebate_condition: 2, start_time_hour: '10:00', end_time_hour: '22:00', distance: 350 },
	{ promotion_id: 9002, same_group_id: 1001, store_id: 101, __new: true,
		store: { name: '茶百道（万科广场店）', icon: 'https://img.meituan.net/content/dbc92c270258856c76ef2802c112ab4d129941.jpg', store_platform: 1 }, order_money: 3500, user_rebate: 2100, left_number: 6, rebate_condition: 2, start_time_hour: '10:00', end_time_hour: '22:00', distance: 350 },
	{ promotion_id: 9003, same_group_id: 1002, store_id: 102, __new: false,
		store: { name: '1點點（石排店）', icon: 'https://img.meituan.net/content/dbc92c270258856c76ef2802c112ab4d129941.jpg', store_platform: 4 }, order_money: 1500, user_rebate: 800, left_number: 0, rebate_condition: 1, start_time_hour: '00:00', end_time_hour: '23:59', distance: 1200 },
	{ promotion_id: 9004, same_group_id: 1003, store_id: 103, __new: false,
		store: { name: '老成都·板凳面（石排店）', icon: 'https://img.meituan.net/content/dbc92c270258856c76ef2802c112ab4d129941.jpg', store_platform: 2 }, order_money: 1500, user_rebate: 1000, left_number: 2, rebate_condition: 2, start_time_hour: '00:00', end_time_hour: '23:59', distance: 3700 }
];

export function loadFeed(lat, lng) {
	return new Promise((resolve) => {
		if (!hasBridge()) { setTimeout(() => resolve({ ok: true, items: DEMO }), 400); return; }
		const timer = setTimeout(() => resolve({ ok: false, error: '请求超时, 请检查网络' }), 30000);
		window.__onFeed = (data) => { clearTimeout(timer); resolve(data); };
		window.XcBridge.loadFeed(lat, lng);
	});
}

export function onLocation(cb) {
	if (typeof window !== 'undefined') window.__onLocation = cb;
}

export function getPermStatus() {
	if (!hasBridge()) return { notif: true, location: true, battery: false };
	try { return JSON.parse(window.XcBridge.getPermStatus()); } catch (e) { return { notif: true, location: true, battery: false }; }
}
export function openAppSettings() { if (hasBridge()) window.XcBridge.openAppSettings(); }
export function requestNotifPerm() { if (hasBridge()) window.XcBridge.requestNotifPerm(); }
export function requestLocationPerm() { if (hasBridge()) window.XcBridge.requestLocationPerm(); }
export function requestBattery() { if (hasBridge()) window.XcBridge.requestBattery(); }
