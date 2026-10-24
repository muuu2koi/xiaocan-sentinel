<template>
	<view class="page">
		<!-- 头部 -->
		<view class="header">
			<view class="title-row">
				<text class="app-title">小蚕哨兵</text>
				<view class="status-pill" :class="running ? 'on' : 'off'" @click="goSettings">
					<view class="dot" />
					<text class="status-text">{{ running ? '监听中' : '已停止' }}</text>
				</view>
			</view>
			<view class="loc-row">
				<text class="loc-text">📍 {{ locText }}</text>
				<view class="refresh-btn" @click="refresh">
					<text class="refresh-icon" :class="{ spinning: loading }">⟳</text>
					<text class="refresh-text">刷新</text>
				</view>
			</view>
		</view>

		<!-- 通知权限横幅 -->
		<view class="notif-banner" v-if="perms.notif === false" @click="fixNotif">
			<text class="banner-text">🔔 通知权限未开启, 收不到上新提醒 — 点击去开启</text>
		</view>

		<!-- 搜索框 -->
		<view class="search-box">
			<text class="search-icon">🔍</text>
			<input class="search-input" v-model="searchQuery" placeholder="搜索店铺名过滤活动" placeholder-style="color:#C0C0C0" />
			<text class="search-clear" v-if="searchQuery" @click="searchQuery = ''">×</text>
		</view>

		<!-- 状态条 -->
		<view class="info-bar" v-if="!loading && !error">
			<text class="info-text">{{ searchQuery ? '命中 ' : '共 '}}{{ filteredCards.length }} 家店 · {{ searchQuery ? '过滤自 ' : '' }}{{ total }} 个活动</text>
			<text class="info-text" v-if="lastUpdate">更新于 {{ lastUpdate }}</text>
		</view>

		<!-- 加载中 -->
		<view class="center-box" v-if="loading">
			<view class="spinner" />
			<text class="center-text">正在获取附近活动…</text>
		</view>

		<!-- 错误 -->
		<view class="center-box" v-else-if="error">
			<text class="error-icon">⚠️</text>
			<text class="center-text">{{ error }}</text>
			<view class="retry-btn" @click="refresh"><text class="retry-text">重新加载</text></view>
		</view>

		<!-- 卡片流 -->
		<view class="feed" v-else>
				<view class="card" v-for="card in filteredCards" :key="card.key" @click="openXc">
					<view class="card-head">
						<image class="store-icon" :src="card.icon" mode="aspectFill" lazy-load />
					<view class="store-info">
						<view class="name-row">
							<text class="store-name">{{ card.name }}</text>
							<text class="dist">{{ card.distText }}</text>
						</view>
						<view class="meta-row">
							<text class="pf-chip" :class="'pf-' + card.platform">{{ pfName(card.platform) }}</text>
							<text class="act-count">{{ card.acts.length }} 个活动</text>
							<text class="new-tag" v-if="card.isNew">NEW</text>
						</view>
					</view>
				</view>
				<view class="divider" />
				<view class="act-row" v-for="a in card.acts" :key="a.id">
					<view class="rebate-badge">
						<text class="rebate-text">满{{ a.om }}返{{ a.rebate }}</text>
					</view>
					<view class="act-mid">
						<view class="tag-row">
							<text class="tag-green" v-if="a.noReview">无需评价</text>
							<text class="tag-time">{{ a.time }}</text>
						</view>
						<view class="quota-row">
							<view class="quota-bar">
								<view class="quota-fill" :class="{ urgent: a.urgent }" :style="{ width: a.quotaPct + '%' }" />
							</view>
							<text class="quota-text" :class="{ urgent: a.urgent, gone: a.quota === 0 }">{{ a.quotaText }}</text>
						</view>
					</view>
					<text class="new-mini" v-if="a.isNew">NEW</text>
				</view>
			</view>
			<view class="empty" v-if="!filteredCards.length">
				<text class="center-text">附近暂无可抢活动，稍后再看看</text>
			</view>
		</view>
	</view>
</template>

<script>
import { getConfig, loadFeed, refreshLocation, onLocation, openXiaocan, getPermStatus, requestNotifPerm, openAppSettings } from '../../utils/bridge.js';

export default {
	data() {
		return {
			running: false,
			loading: true,
			error: '',
			locText: '定位中…',
			lat: 0,
			lng: 0,
			total: 0,
			lastUpdate: '',
			cards: [],
			searchQuery: '',
			perms: {}
		};
	},
	computed: {
		filteredCards() {
			const q = this.norm(this.searchQuery);
			if (!q) return this.cards;
			return this.cards.filter(c => this.norm(c.name).indexOf(q) >= 0);
		}
	},
	onShow() {
		const c = getConfig();
		this.running = !!c.running;
		this.perms = getPermStatus();
		if (!this.cards.length && !this.loading) this.refresh();
	},
	onLoad() {
		onLocation(this.onLocate);
		const c = getConfig();
		if (c.lat && c.lng) {
			this.lat = parseFloat(c.lat); this.lng = parseFloat(c.lng);
			this.locText = '坐标 ' + Number(c.lat).toFixed(3) + ', ' + Number(c.lng).toFixed(3);
			this.fetch();
		} else {
			this.refresh();
		}
	},
	methods: {
		goSettings() { uni.switchTab({ url: '/pages/settings/settings' }); },
		norm(s) { return (s || '').toLowerCase().replace(/[^\u4e00-\u9fa5a-z0-9]/g, ''); },
		fixNotif() { requestNotifPerm(); setTimeout(() => { this.perms = getPermStatus(); }, 1500); if (!getPermStatus) openAppSettings(); },
		openXc() { openXiaocan(); },
		refresh() {
			this.loading = true;
			this.error = '';
			refreshLocation();
			// 若 2.5s 内定位没回调, 先用已存坐标拉数据
			if (this.locTimer) clearTimeout(this.locTimer);
			this.locTimer = setTimeout(() => {
				if (this.loading && this.lat === 0) { /* 等待定位 */ }
			}, 2500);
		},
		onLocate(res) {
			if (res.ok) {
				this.lat = res.lat; this.lng = res.lng;
				this.locText = '📍 ' + Number(res.lat).toFixed(4) + ', ' + Number(res.lng).toFixed(4) + (res.src ? ' · ' + res.src : '');
				this.fetch();
			} else {
				this.locText = '📍 定位失败 (' + (res.reason || '') + '), 使用默认位置';
				if (res.lat) { this.lat = res.lat; this.lng = res.lng; }
				this.fetch();
			}
		},
		async fetch() {
			if (this.lat === 0 || this.lng === 0) { this.loading = false; this.error = '尚未获得有效定位'; return; }
			this.loading = true; this.error = '';
			const res = await loadFeed(this.lat, this.lng);
			this.loading = false;
			if (!res.ok) { this.error = res.error || '加载失败'; return; }
			this.build(res.items);
			const d = new Date();
			this.lastUpdate = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
		},
		build(items) {
			this.total = items.length;
			const groups = {};
			for (const it of items) {
				const key = it.same_group_id || ('s' + it.store_id);
				if (!groups[key]) groups[key] = [];
				groups[key].push(it);
			}
			const cards = [];
			for (const key of Object.keys(groups)) {
				const acts = groups[key].slice().sort((a, b) => b.user_rebate - a.user_rebate);
				const st = acts[0].store || {};
				let distText = '';
				if (typeof acts[0].distance === 'number') {
					distText = acts[0].distance >= 1000 ? (acts[0].distance / 1000).toFixed(1) + 'km' : acts[0].distance + 'm';
				}
				cards.push({
					key,
					name: st.name || '未知店铺',
					icon: st.icon || '',
					platform: st.store_platform || acts[0].store_platform || 0,
					distText,
					isNew: acts.some(a => a.__new),
					acts: acts.map(a => {
						const left = a.left_number || 0;
						return {
							id: a.promotion_id,
							om: a.order_money % 100 === 0 ? a.order_money / 100 : (a.order_money / 100).toFixed(1),
							rebate: a.user_rebate % 100 === 0 ? a.user_rebate / 100 : (a.user_rebate / 100).toFixed(1),
							noReview: (a.rebate_condition || 0) === 2,
							time: (a.start_time_hour || '00:00') + '-' + (a.end_time_hour || '23:59'),
							quota: left,
							quotaText: left > 0 ? '还剩 ' + left + ' 份' : '已抢光',
							quotaPct: Math.min(100, left * 8),
							urgent: left > 0 && left <= 3,
							isNew: !!a.__new
						};
					})
				});
			}
			cards.sort((a, b) => {
				const da = parseFloat(a.distText) || 99, db = parseFloat(b.distText) || 99;
				return da - db;
			});
			this.cards = cards;
		},
		pfName(pf) {
			const names = { 1: '美团', 2: '饿了么', 3: '京东', 4: '淘宝闪购' };
			return names[pf] || '小蚕';
		}
	}
};
</script>

<style scoped>
.page { min-height: calc(100vh - var(--window-bottom, 0px)); background: #F5F6F8; padding-bottom: 20rpx; }
.header { background: #FFFFFF; padding: 30rpx 30rpx 24rpx; border-radius: 0 0 28rpx 28rpx; border-bottom: 1rpx solid #F0F0F2; }
.title-row { display: flex; align-items: center; justify-content: space-between; }
.app-title { font-size: 40rpx; font-weight: 700; color: #1A1A1A; }
.status-pill { display: flex; align-items: center; padding: 8rpx 20rpx; border-radius: 26rpx; }
.status-pill.on { background: #E8F8F0; }
.status-pill.off { background: #F0F0F2; }
.dot { width: 12rpx; height: 12rpx; border-radius: 6rpx; margin-right: 10rpx; }
.status-pill.on .dot { background: #10B981; }
.status-pill.off .dot { background: #B0B0B0; }
.status-text { font-size: 22rpx; color: #444; }
.loc-row { display: flex; align-items: center; justify-content: space-between; margin-top: 18rpx; }
.loc-text { font-size: 23rpx; color: #8A8A8A; }
.refresh-btn { display: flex; align-items: center; background: #FFF1EA; padding: 10rpx 24rpx; border-radius: 26rpx; }
.refresh-icon { font-size: 26rpx; color: #FF5F2E; margin-right: 6rpx; }
.refresh-icon.spinning { display: inline-block; animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.refresh-text { font-size: 24rpx; color: #FF5F2E; font-weight: 600; }
.notif-banner { background: #FFF7E8; border-bottom: 1rpx solid #FFE3B8; padding: 16rpx 30rpx; }
.banner-text { font-size: 23rpx; color: #B26B00; }
.search-box { display: flex; align-items: center; background: #FFFFFF; margin: 16rpx 24rpx 0; border-radius: 32rpx; padding: 14rpx 26rpx; border: 1rpx solid #EFEFEF; }
.search-icon { font-size: 24rpx; margin-right: 12rpx; }
.search-input { flex: 1; font-size: 26rpx; }
.search-clear { font-size: 34rpx; color: #C0C0C0; padding: 0 8rpx; }
.info-bar { display: flex; justify-content: space-between; padding: 20rpx 30rpx 6rpx; }
.info-text { font-size: 22rpx; color: #9A9A9A; }
.center-box { display: flex; flex-direction: column; align-items: center; padding: 160rpx 60rpx; }
.center-text { font-size: 26rpx; color: #9A9A9A; margin-top: 20rpx; }
.error-icon { font-size: 60rpx; }
.retry-btn { margin-top: 30rpx; background: #FF5F2E; padding: 14rpx 60rpx; border-radius: 34rpx; }
.retry-text { color: #FFFFFF; font-size: 26rpx; font-weight: 600; }
.spinner { width: 52rpx; height: 52rpx; border: 5rpx solid #FFE0D4; border-top-color: #FF5F2E; border-radius: 50%; animation: spin 0.8s linear infinite; margin-top: 60rpx; }
.feed { padding: 16rpx 24rpx; }
.card { background: #FFFFFF; border-radius: 24rpx; padding: 24rpx; margin-bottom: 20rpx; border: 1rpx solid #F0F0F2; }
.card-head { display: flex; }
.store-icon { width: 104rpx; height: 104rpx; border-radius: 16rpx; background: #F0F0F0; flex-shrink: 0; }
.store-info { flex: 1; margin-left: 20rpx; min-width: 0; }
.name-row { display: flex; align-items: center; justify-content: space-between; }
.store-name { font-size: 30rpx; font-weight: 700; color: #1A1A1A; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 440rpx; }
.dist { font-size: 22rpx; color: #9A9A9A; flex-shrink: 0; margin-left: 12rpx; }
.meta-row { display: flex; align-items: center; margin-top: 12rpx; }
.pf-chip { font-size: 20rpx; padding: 4rpx 12rpx; border-radius: 8rpx; background: #FFF4E0; color: #FF9500; }
.pf-2 { background: #EAF3FF; color: #0A6CFF; }
.pf-3 { background: #FFEDEF; color: #E4393C; }
.pf-4 { background: #FFF0F5; color: #FF2E8B; }
.act-count { font-size: 22rpx; color: #9A9A9A; margin-left: 14rpx; }
.new-tag { font-size: 18rpx; background: #FF3B30; color: #FFF; padding: 2rpx 10rpx; border-radius: 6rpx; margin-left: 14rpx; font-weight: 700; }
.divider { height: 1rpx; background: #F2F2F4; margin: 20rpx 0; }
.act-row { display: flex; align-items: center; margin-bottom: 20rpx; }
.act-row:last-child { margin-bottom: 0; }
.rebate-badge { background: linear-gradient(135deg, #FF7A45, #FF4E30); border-radius: 10rpx; padding: 8rpx 14rpx; flex-shrink: 0; }
.rebate-text { color: #FFF; font-size: 24rpx; font-weight: 700; }
.act-mid { flex: 1; margin-left: 18rpx; min-width: 0; }
.tag-row { display: flex; align-items: center; }
.tag-green { font-size: 19rpx; color: #10B981; background: #E8F8F0; border: 1rpx solid #A8E6C4; padding: 2rpx 10rpx; border-radius: 6rpx; margin-right: 12rpx; }
.tag-time { font-size: 20rpx; color: #AAAAAA; }
.quota-row { display: flex; align-items: center; margin-top: 10rpx; }
.quota-bar { flex: 1; height: 10rpx; background: #FFE9DA; border-radius: 5rpx; overflow: hidden; }
.quota-fill { height: 10rpx; background: linear-gradient(90deg, #FFA45B, #FF6D00); border-radius: 5rpx; }
.quota-fill.urgent { background: linear-gradient(90deg, #FF6B5E, #FF3B30); }
.quota-text { font-size: 21rpx; color: #FF6D00; font-weight: 600; margin-left: 14rpx; flex-shrink: 0; }
.quota-text.urgent { color: #FF3B30; }
.quota-text.gone { color: #BBBBBB; font-weight: 400; }
.new-mini { font-size: 18rpx; background: #FF3B30; color: #FFF; padding: 2rpx 8rpx; border-radius: 6rpx; font-weight: 700; margin-left: 10rpx; flex-shrink: 0; }
.empty { padding: 100rpx 0; text-align: center; }
</style>
