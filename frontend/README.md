# frontend/ · 全部客户端

> 本目录是**全部前端代码**。后端在 [`../backend/`](../backend/)。
>
> 约定：凡是"给用户看的界面"都放这里。Web、小程序、Android App 都是客户端，
> 它们本质同类，因此归在同一目录下，各自一个子目录。

## 当前进度

| 子目录 | 端 | 状态 |
|---|---|---|
| [`web/`](web/) | Web（Vue3 + TypeScript + Element Plus） | 🚧 M3 进行中（骨架 + 登录 + 工作台已完成） |
| `miniapp/` | 微信小程序（uni-app） | ⬜ M4 待开始 |
| `android/` | Android（uni-app 出包） | ⬜ M5 待开始 |

## 技术选型（为什么是 Vue3 + Element Plus）

- **Element Plus**：国内 ERP/后台系统最主流的组件库，表格、表单、弹窗、日期选择器齐全，
  不从零造轮子。换 React 的话 Ant Design 等价，但本机与团队更熟 Vue。
- **TypeScript**：接口字段有类型约束，后端改字段名时前端能在编译期发现，
  而不是等到运行时白屏。实测有效：M3 首个下午就靠类型检查抓到
  `size`（尺码）与 `size`（分页条数）命名冲突。
- **Vite**：启动 <1s，热更新即时。

## `web/` 目录结构

```
web/
├── src/
│   ├── api/
│   │   └── client.ts      ★ 所有接口封装（含 token 注入、幂等键、错误处理、金额换算）
│   ├── views/             页面
│   │   ├── LoginView.vue          登录页（已完成）
│   │   ├── DashboardView.vue      工作台（已完成，真实读取后端数据）
│   │   ├── _Placeholder.vue       未实现页面的统一占位组件
│   │   ├── mdm/                   商品 / 供应商 / 客户 / 仓库
│   │   ├── inv/                   库存查询
│   │   ├── pur/                   进货单
│   │   └── sal/                   出货单
│   ├── layouts/
│   │   └── MainLayout.vue 侧边栏 + 顶栏（菜单按权限自动过滤）
│   ├── router/
│   │   └── index.ts       路由表 + 登录守卫 + 权限守卫
│   ├── stores/
│   │   └── session.ts     登录会话（token / 角色 / 权限）
│   ├── styles/global.css  全局样式
│   ├── App.vue
│   ├── main.ts            入口
│   └── env.d.ts           .vue 模块类型声明
├── index.html
├── vite.config.ts         构建配置 + /api 代理到后端 8080
├── tsconfig.json
└── package.json
```

## 常用命令

```powershell
cd frontend\web

npm install        # 首次：装依赖
npm run dev        # 开发服务器 → http://127.0.0.1:3000
npm run build      # 生产构建（含类型检查）→ dist/
npm run type-check # 只做类型检查
```

## 三个容易踩的点

1. **端口是 3000，不是 Vite 默认的 5173。**
   本机实测 5173 / 5174 无法绑定（`listen EACCES`），
   换 3000 可解决。见 `vite.config.ts` 注释。

2. **`/api` 请求走 Vite 代理到后端 8080。**
   因此开发时前端代码里写 `http.get('/products')` 即可，
   **不要**写 `http://localhost:8080/api/v1/...` ——
   那样会触发浏览器跨域拦截，且生产环境（Nginx 反代）路径也不一致。

3. **金额在前后端之间一律是「整数分」。**
   只在**展示时**用 `fenToYuan()` 转成元。
   把元回传后端会产生 `0.1 + 0.2 = 0.30000000000000004` 这类浮点误差（ADR-0005）。

## 与后端的关系

- **接口契约**：[`../packages/api-contract/openapi.yaml`](../packages/api-contract/openapi.yaml) 是单一事实源。
- **权限码一致**：前端路由 `meta.permission` 与后端 `@RequirePermission` 用同一套码
  （`mdm:read` / `pur:create` / `sal:ship` …），避免各写一套逐渐对不上。
- **幂等键自动生成**：`api/client.ts` 给所有写操作自动加 `X-Request-Id`，
  页面代码不需要关心。这是照着后端契约来的（缺该头会返回 400）。

> ⚠️ 前端权限判断只是**体验层**的提前拦截（不想让用户点进去才看到 403）。
> **真正的一致性由后端保证**，前端判断绝不能当作安全边界。
