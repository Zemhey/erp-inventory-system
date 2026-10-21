import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

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
