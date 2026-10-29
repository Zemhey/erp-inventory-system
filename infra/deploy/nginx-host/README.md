# 宿主 Nginx 站点配置（生产实际生效版）

> 这三个文件是从服务器 `/etc/nginx/conf.d/` 取回来的**线上真实配置**，
> 放在仓库里是为了"可重建"：换一台机器时照着落一遍就能复原入口层。
> 容器内部的 nginx 在 `frontend/web/nginx.conf`，两者不要混：
> 这里管的是「域名 → 127.0.0.1:18080 / 静态目录」这一层。

| 文件 | 域名 | 指向 | 说明 |
|---|---|---|---|
| `zz-xuliu.conf` | `xuliu.zemworld.cn` | `127.0.0.1:18080`（云裳 web 容器） | 含登录限流、管理面 404、`noindex`；**另带 `/dl/` 下载区** → `/var/www/downloads/` |
| `zz-erp-h5.conf` | `m.zemworld.cn` | `/var/www/erp-h5` 静态 + `/api/` 反代 | uni-app H5 产物，任何人浏览器可开 |
| `lancang-demo.conf` | `lancang.zemworld.cn` | `/var/www/lancang-demo` 纯静态 | 瓶装气 Mock SPA + `/tour/` 兜底 |

## 为什么文件名带 `zz-` 前缀

`conf.d/*.conf` 按字母序加载，而 **nginx 取第一个 server 块作为该端口的默认站点**。
主页配置 `zemworld.conf` 排在最后（z-e-m），如果新配置排在它前面，
用 IP 直接访问（`Host: 8.163.96.61`）就会被新站点接管，主页打不开。
所以新增站点一律 `zz-` 前缀，保证排在 `zemworld.conf` 之后、只做精确域名匹配。

## 落位与生效

```bash
sudo cp zz-xuliu.conf zz-erp-h5.conf /etc/nginx/conf.d/
sudo cp lancang-demo.conf /etc/nginx/conf.d/
sudo nginx -t && sudo systemctl reload nginx
```

证书由 acme.sh 签发后安装到 `/etc/nginx/cert/<域名>.pem|.key`，
各域名的 `/.well-known/acme-challenge/` 都指向 `/var/www/letsencrypt`（续期要用，别删）。

签发/安装一条命令链（`m` 域名 2026-10-29 实测走的就是这个）：

```bash
nohup /root/.acme.sh/acme.sh --issue -d m.zemworld.cn -w /var/www/letsencrypt > /tmp/acme-m.log 2>&1 &
# 签完再落地，reloadcmd 先留 true，等 conf 就位再统一 nginx -t && reload
/root/.acme.sh/acme.sh --install-cert -d m.zemworld.cn --ecc \
  --key-file /etc/nginx/cert/m.zemworld.cn.key --fullchain-file /etc/nginx/cert/m.zemworld.cn.pem --reloadcmd true
```

## 两个会咬人的地方

- **`limit_req_zone` 只在 `zz-xuliu.conf` 里定义一次，`zz-erp-h5.conf` 引用同一个 `erp_login` zone**。
  两个域名打的是同一个后端登录接口，限速必须共用一份，否则 `m` 就是第二个爆破入口。
  zone 是在 http 预配置阶段建的，跨文件引用不受加载顺序影响（`nginx -t` 实测通过），
  但删 `zz-xuliu.conf` 之前要先把那行搬走。
- **`gzip_types` 必须同时写 `text/javascript` 和 `application/javascript`**：
  nginx 自带 `mime.types` 把 `.js` 映射成 `text/javascript`，只写 `application/javascript`
  时入口包（259KB）实测原样下发，手机上首屏白等。

## 下载区 `/dl/`

`https://xuliu.zemworld.cn/dl/` → `/var/www/downloads/`，给作品集页面的「下载」区块用。

| 文件 | 是什么 | 怎么重出 |
|---|---|---|
| `yunshang-erp-standalone-v1.zip` | 云裳单机版（源码 + `README-START.md` 入门） | `powershell -File scripts/make-download-bundles.ps1` |
| `yunshang-erp-server-deploy-v1.zip` | 云裳服务器完整部署包（同一份源码 + 服务器入门） | 同上 |
| `yunshang-erp-0.3.0.apk` | 安卓包（界面已内置） | 见 `app/android-demo/README.md` |
| `YunshangERP-0.2.2-portable.exe` | PC 便携包（80 MB，未做代码签名） | `app/desktop` 里 `npm run dist -- --api https://xuliu.zemworld.cn` |
| `bottled-gas-standalone-v1.zip` | 瓶装气单机版（**另一个仓库**出的包） | 见 `瓶装气进销存系统/dl/make-zip.ps1` |

规矩：

- 每个文件旁边放一份同名 `.sha256`，收件人可自验；换文件**必须同时换 sha 文件**；
- 这个目录**公开可读**（`autoindex on`），所以里面只能放成品，
  不要放 `.env`、备份 dump、含口令的脚本 —— 放进去就等于发给全世界；
- 2C2G 机器带宽有限，80 MB 的 PC 包拉满要几分钟，属预期；如果下载量上来把它压垮，
  再把下载区挪到对象存储 + CDN，不要在这台机器上加限流凑合。

## 公口纪律

- 公网只开 22 / 80 / 443；`18080`（web 容器）与 `15435`（PG）**只绑 127.0.0.1**，
  安全组不放行；
- 所有 `location ~ ^/(actuator|swagger-ui|v3/api-docs)` 一律 404 —— 管理面不对公网；
- 所有演示站点带 `X-Robots-Tag: noindex, nofollow`，不被搜索引擎收录。
