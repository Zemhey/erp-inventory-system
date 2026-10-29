/**
 * 原生控件的颜色常量。
 *
 * ★ 为什么单独一个文件、而且偏偏是字面量：
 *   `uni.showModal` 的 confirmColor、`<switch>` 的 color 这些是**原生层**参数，
 *   只接受十六进制字符串，拿不到 CSS 变量 —— 深色档下原生弹窗也是系统画的，
 *   我们的 .t-dark 管不到它。所以这里放一份与 App.vue 里 --c-brand / --c-danger
 *   同值的常量，页面里不再各自手写一遍（原来 7 处各写各的，还停留在旧的藏青 #1f3a63）。
 *
 * ★ 改主题色时必须同步改这里，否则弹窗按钮会和其他控件差一个色相。
 */

/** 与 --c-brand 浅色档同值（黛青）。 */
export const BRAND_CONFIRM = '#2e5d63'

/** 与 --c-danger 浅色档同值（胭脂）。 */
export const DANGER_CONFIRM = '#a25f6b'
