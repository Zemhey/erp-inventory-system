import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// Element 自带深色变量（作用域是 html.dark），放在基础样式之后，
// 我们的 theme-dark.css 再在它之后重新映射品牌色 —— 顺序不能反。
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'
// ★ 主题层必须放在 element-plus 的样式之后，
//   否则 Element 的默认变量会把我们的覆盖掉（同权重下后者生效）。
import './styles/theme.css'
// 兼容层：把各页面里遗留的 Element 默认色值统一改指主题令牌。
import './styles/legacy-colors.css'
// 深色主题放最后：html.dark 特异度高于 :root，且需要压过上面的兼容层。
import './styles/theme-dark.css'

/**
 * 应用入口。
 *
 * 顺序说明：ElementPlus 必须在 router 之前安装 ——
 * 路由守卫里若用到 ElMessage 等组件，需要 Element 已注册。
 * 中文语言包显式指定：默认是英文，日期选择器等组件会显示英文月份。
 */
const app = createApp(App)

app.use(createPinia())
app.use(ElementPlus, { locale: zhCn })
app.use(router)

app.mount('#app')
