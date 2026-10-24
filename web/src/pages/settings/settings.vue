<template>
	<view class="page">
		<view class="header">
			<text class="title">监听设置</text>
		</view>

		<!-- 运行状态 -->
		<view class="status-card">
			<view class="status-left">
				<view class="big-dot" :class="{ on: running }" />
				<view class="status-info">
					<text class="status-title">{{ running ? '监控运行中' : '监控已停止' }}</text>
					<text class="status-sub">{{ running ? '自动巡检上新, 通知会弹出响铃' : '启动后才会推送上新提醒' }}</text>
				</view>
			</view>
			<view class="run-btn" :class="{ stop: running }" @click="toggleRun">
				<text class="run-btn-text">{{ running ? '停止' : '启动' }}</text>
			</view>
		</view>

		<!-- 权限检查 -->
		<view class="section">
			<text class="sec-title">权限检查（后台监听必读）</text>
			<view class="row">
				<view class="row-info">
					<text class="row-title">通知权限 <text class="perm-dot" :class="perms.notif ? 'ok' : 'bad'">{{ perms.notif ? '✓ 已开启' : '✗ 未开启' }}</text></text>
					<text class="row-sub">不开启将收不到任何上新提醒</text>
				</view>
				<view class="mini-btn" v-if="!perms.notif" @click="fixNotif"><text class="mini-btn-text">去开启</text></view>
			</view>
			<view class="row">
				<view class="row-info">
					<text class="row-title">定位权限 <text class="perm-dot" :class="perms.location ? 'ok' : 'bad'">{{ perms.location ? '✓ 已开启' : '✗ 未开启' }}</text></text>
					<text class="row-sub">用于按手机所在位置巡检附近活动</text>
				</view>
				<view class="mini-btn" v-if="!perms.location" @click="fixLocation"><text class="mini-btn-text">去开启</text></view>
			</view>
			<view class="row">
				<view class="row-info">
					<text class="row-title">电池优化白名单 <text class="perm-dot" :class="perms.battery ? 'ok' : 'bad'">{{ perms.battery ? '✓ 已加入' : '✗ 未加入' }}</text></text>
					<text class="row-sub">防止系统省电策略杀掉后台巡检</text>
				</view>
				<view class="mini-btn" v-if="!perms.battery" @click="fixBattery"><text class="mini-btn-text">去设置</text></view>
			</view>
			<view class="miui-tip">
				<text class="miui-text">📱 小米/MIUI 手机建议再手动开启：系统设置 → 应用设置 → 应用管理 → 小蚕哨兵 → 打开「自启动」「后台弹出界面」「锁屏显示」，省电策略设为无限制，否则后台可能被杀</text>
				<view class="mini-btn" @click="openAppSettings"><text class="mini-btn-text">打开应用设置</text></view>
			</view>
		</view>

		<!-- 提醒规则 -->
		<view class="section">
			<text class="sec-title">提醒规则</text>
			<view class="row">
				<view class="row-info">
					<text class="row-title">全部上新提醒</text>
					<text class="row-sub">巡检发现任何新活动都推送, 信息全但可能较多</text>
				</view>
				<switch :checked="cfg.switch_all" color="#FF5F2E" @change="setSwitchAll" />
			</view>
			<view class="row">
				<view class="row-info">
					<text class="row-title">关键词提醒</text>
					<text class="row-sub">只推送命中关键词的店铺, 还包括补货提醒</text>
				</view>
				<switch :checked="cfg.switch_keyword" color="#FF5F2E" @change="setSwitchKeyword" />
			</view>
		</view>

		<!-- 关键词 -->
		<view class="section">
			<text class="sec-title">我的关键词（可同时监听多家店）</text>
			<view class="chip-box" v-if="keywordList.length">
				<view class="chip" v-for="(k, i) in keywordList" :key="i">
					<text class="chip-text">{{ k }}</text>
					<text class="chip-x" @click="removeKeyword(i)">×</text>
				</view>
			</view>
			<view class="add-row">
				<input class="kw-input" v-model="kwInput" placeholder="输入店铺名, 如: 茶百道" placeholder-style="color:#C0C0C0" @confirm="addKeyword" />
				<view class="add-btn" @click="addKeyword"><text class="add-btn-text">添加</text></view>
			</view>
			<text class="hint">命中店铺名即提醒; 留空则关键词提醒不生效</text>
		</view>

		<!-- 巡检频率 -->
		<view class="section">
			<text class="sec-title">巡检频率</text>
			<view class="slider-row">
				<slider class="slider" :min="1" :max="15" :step="1" :value="interval" activeColor="#FF5F2E" block-size="24" @change="onInterval" />
				<text class="slider-val">{{ interval }} 分钟</text>
			</view>
			<text class="hint">巡检间隔自带随机抖动, 更像真人浏览</text>
		</view>

		<!-- 免打扰 -->
		<view class="section">
			<view class="row">
				<view class="row-info">
					<text class="row-title">夜间免打扰</text>
					<text class="row-sub">静默时段完全不发请求, 也不会响铃</text>
				</view>
				<switch :checked="quietOn" color="#FF5F2E" @change="setQuiet" />
			</view>
			<view class="quiet-times" v-if="quietOn">
				<view class="time-box"><text class="time-label">开始</text><input class="time-input" v-model="qs" @blur="saveQuiet" /><text class="time-label">结束</text><input class="time-input" v-model="qe" @blur="saveQuiet" /></view>
				<text class="hint">格式 HH:mm, 如 02:00 和 07:30, 支持跨午夜</text>
			</view>
		</view>

		<!-- 高级 -->
		<view class="footer">
			<text class="footer-text">巡检坐标自动跟随手机定位 · 只读浏览, 不自动下单 · 连续异常会自动暂停并通知</text>
		</view>
	</view>
</template>

<script>
import { getConfig, setConfig, startMonitor, stopMonitor, toast, getPermStatus, openAppSettings, requestNotifPerm, requestLocationPerm, requestBattery } from '../../utils/bridge.js';

export default {
	data() {
		return {
			running: false,
			cfg: {},
			keywordList: [],
			kwInput: '',
			interval: 3,
			quietOn: true,
			qs: '02:00',
			qe: '07:30',
			perms: { notif: true, location: true, battery: false }
		};
	},
	onShow() {
		this.reload();
	},
	methods: {
		refreshPerms() {
			this.perms = getPermStatus();
		},
		fixNotif() { requestNotifPerm(); setTimeout(() => this.refreshPerms(), 1500); },
		fixLocation() { requestLocationPerm(); setTimeout(() => this.refreshPerms(), 1500); },
		fixBattery() { requestBattery(); setTimeout(() => this.refreshPerms(), 1500); },
		reload() {
			this.refreshPerms();
			const c = getConfig();
			this.running = !!c.running;
			this.cfg = c;
			this.keywordList = (c.keywords || '').split(/[,，\s]+/).filter(x => x);
			this.interval = parseInt(c.interval_min || '3', 10) || 3;
			this.quietOn = !!(c.quiet_start && c.quiet_end);
			this.qs = c.quiet_start || '02:00';
			this.qe = c.quiet_end || '07:30';
		},
		toggleRun() {
			if (this.running) {
				stopMonitor();
				this.running = false;
				toast('监控已停止');
			} else {
				this.persistAll();
				startMonitor();
				this.running = true;
				toast('监控已启动');
			}
		},
		persistAll() {
			setConfig({
				keywords: this.keywordList.join(','),
				interval_min: String(this.interval),
				quiet_start: this.quietOn ? this.qs : '',
				quiet_end: this.quietOn ? this.qe : ''
			});
		},
		setSwitchAll(e) { setConfig({ switch_all: e.detail.value }); toast(e.detail.value ? '已开启全部上新提醒' : '已关闭全部上新提醒'); },
		setSwitchKeyword(e) { setConfig({ switch_keyword: e.detail.value }); toast(e.detail.value ? '已开启关键词提醒' : '已关闭关键词提醒'); },
		addKeyword() {
			const k = (this.kwInput || '').trim();
			if (!k) return;
			if (this.keywordList.includes(k)) { this.kwInput = ''; return; }
			this.keywordList.push(k);
			this.kwInput = '';
			this.persistAll();
			toast('已添加 ' + k);
		},
		removeKeyword(i) {
			this.keywordList.splice(i, 1);
			this.persistAll();
		},
		onInterval(e) {
			this.interval = e.detail.value;
			this.persistAll();
		},
		setQuiet(e) {
			this.quietOn = e.detail.value;
			this.persistAll();
		},
		saveQuiet() {
			this.persistAll();
			toast('免打扰时段已保存');
		},
		saveAdv() {
			this.persistAll();
			toast('已保存');
		}
	}
};
</script>

<style scoped>
.page { min-height: calc(100vh - var(--window-bottom, 0px)); background: #F5F6F8; padding-bottom: 60rpx; }
.header { background: #FFFFFF; padding: 40rpx 30rpx 24rpx; border-radius: 0 0 28rpx 28rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,0.04); }
.title { font-size: 40rpx; font-weight: 700; color: #1A1A1A; }
.status-card { display: flex; align-items: center; justify-content: space-between; background: #FFFFFF; margin: 20rpx 24rpx; border-radius: 24rpx; padding: 28rpx; box-shadow: 0 2rpx 10rpx rgba(0,0,0,0.04); }
.status-left { display: flex; align-items: center; }
.big-dot { width: 24rpx; height: 24rpx; border-radius: 12rpx; background: #C8C8CC; margin-right: 20rpx; }
.big-dot.on { background: #10B981; box-shadow: 0 0 0 8rpx rgba(16,185,129,0.15); }
.status-title { font-size: 30rpx; font-weight: 700; color: #1A1A1A; display: block; }
.status-sub { font-size: 22rpx; color: #9A9A9A; display: block; margin-top: 6rpx; }
.run-btn { background: linear-gradient(135deg, #FF7A45, #FF4E30); padding: 16rpx 48rpx; border-radius: 34rpx; }
.run-btn.stop { background: #F0F0F2; }
.run-btn-text { color: #FFF; font-size: 28rpx; font-weight: 700; }
.run-btn.stop .run-btn-text { color: #666; }
.section { background: #FFFFFF; border-radius: 24rpx; margin: 20rpx 24rpx; padding: 10rpx 28rpx; box-shadow: 0 2rpx 10rpx rgba(0,0,0,0.04); }
.sec-title { display: block; font-size: 26rpx; font-weight: 700; color: #1A1A1A; padding: 24rpx 0 8rpx; }
.row { display: flex; align-items: center; justify-content: space-between; padding: 22rpx 0; }
.row-info { flex: 1; margin-right: 20rpx; }
.row-title { font-size: 28rpx; color: #333; display: block; }
.row-sub { font-size: 21rpx; color: #A0A0A0; display: block; margin-top: 4rpx; }
.chip-box { display: flex; flex-wrap: wrap; padding: 8rpx 0; }
.chip { display: flex; align-items: center; background: #FFF1EA; border: 1rpx solid #FFCDB8; border-radius: 24rpx; padding: 8rpx 18rpx; margin: 8rpx 12rpx 8rpx 0; }
.chip-text { font-size: 24rpx; color: #FF5F2E; margin-right: 10rpx; }
.chip-x { font-size: 26rpx; color: #FF9A7E; font-weight: 700; padding: 0 6rpx; }
.add-row { display: flex; align-items: center; padding: 10rpx 0 6rpx; }
.kw-input { flex: 1; background: #F5F6F8; border-radius: 14rpx; padding: 16rpx 20rpx; font-size: 26rpx; }
.add-btn { background: #FF5F2E; border-radius: 14rpx; padding: 16rpx 34rpx; margin-left: 16rpx; }
.add-btn-text { color: #FFF; font-size: 26rpx; font-weight: 600; }
.hint { display: block; font-size: 21rpx; color: #B0B0B0; padding: 8rpx 0 20rpx; }
.slider-row { display: flex; align-items: center; padding: 10rpx 0 0; }
.slider { flex: 1; }
.slider-val { font-size: 26rpx; color: #FF5F2E; font-weight: 700; width: 140rpx; text-align: right; }
.quiet-times { padding-bottom: 10rpx; }
.time-box { display: flex; align-items: center; }
.time-label { font-size: 24rpx; color: #666; margin-right: 14rpx; }
.time-input { background: #F5F6F8; border-radius: 12rpx; padding: 12rpx 20rpx; font-size: 26rpx; width: 140rpx; margin-right: 30rpx; text-align: center; }
.adv-body { padding-bottom: 20rpx; }
.adv-label { display: block; font-size: 22rpx; color: #888; margin: 16rpx 0 10rpx; }
.token-input { width: 100%; background: #F5F6F8; border-radius: 12rpx; padding: 14rpx 18rpx; font-size: 22rpx; min-height: 80rpx; box-sizing: border-box; }
.city-input { background: #F5F6F8; border-radius: 12rpx; padding: 14rpx 18rpx; font-size: 26rpx; width: 300rpx; }
.arrow { font-size: 28rpx; color: #B0B0B0; }
.footer { padding: 20rpx 50rpx; }
.footer-text { font-size: 20rpx; color: #B8B8B8; line-height: 1.6; }
.perm-dot { font-size: 20rpx; padding: 2rpx 10rpx; border-radius: 6rpx; margin-left: 8rpx; }
.perm-dot.ok { color: #10B981; background: #E8F8F0; }
.perm-dot.bad { color: #FF3B30; background: #FFECEA; }
.mini-btn { background: #FFF1EA; border: 1rpx solid #FFCDB8; padding: 10rpx 24rpx; border-radius: 24rpx; flex-shrink: 0; }
.mini-btn-text { color: #FF5F2E; font-size: 23rpx; font-weight: 600; }
.miui-tip { background: #F7F8FA; border-radius: 14rpx; padding: 18rpx 20rpx; margin: 10rpx 0 24rpx; display: flex; flex-direction: column; }
.miui-text { font-size: 21rpx; color: #808080; line-height: 1.6; margin-bottom: 12rpx; }
</style>
