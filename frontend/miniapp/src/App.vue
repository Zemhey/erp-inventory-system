<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { installRouteGuard } from '@/utils/routeGuard'

onLaunch(() => {
  installRouteGuard()
})
</script>

<style>
page {
  background: #f4f5f7;
  color: #1c2230;
  font-family:
    -apple-system, BlinkMacSystemFont, 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 28rpx;
  line-height: 1.5;
}

view,
text,
input,
button,
scroll-view {
  box-sizing: border-box;
}

.page {
  padding: 24rpx 32rpx calc(48rpx + env(safe-area-inset-bottom));
}

.card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 28rpx;
  box-shadow: 0 8rpx 30rpx rgba(20, 28, 45, 0.06);
}

.row {
  display: flex;
  align-items: center;
}

.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.muted {
  color: #8a90a2;
}

.small {
  font-size: 24rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1c2230;
}

.tag {
  display: inline-block;
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  background: #eef1f6;
  color: #586176;
}

.btn-primary {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 92rpx;
  border-radius: 18rpx;
  background: #1f3a63;
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
  border: none;
}

.btn-primary[disabled] {
  opacity: 0.55;
}

.btn-ghost {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 76rpx;
  border-radius: 16rpx;
  background: #ffffff;
  color: #1f3a63;
  font-size: 26rpx;
  border: 1rpx solid #d7dce6;
}

.empty-block,
.error-block,
.loading-block {
  padding: 64rpx 24rpx;
  text-align: center;
  color: #8a90a2;
  font-size: 26rpx;
}

.error-block {
  color: #b4462f;
}
</style>
