# 宿主 Nginx 站点配置（生产实际生效版）

> 这三个文件是从服务器 `/etc/nginx/conf.d/` 取回来的**线上真实配置**，
> 放在仓库里是为了"可重建"：换一台机器时照着落一遍就能复原入口层。
> 容器内部的 nginx 在 `frontend/web/nginx.conf`，两者不要混：
> 这里管的是「域名 → 127.0.0.1:18080 / 静态目录」这一层。

| 文件 | 域名 | 指向 | 说明 |
|---|---|---|---|
| `zz-xuliu.conf` | `xuliu.zemworld.cn` | `127.0.0.1:18080`（云裳 web 容器） | 含登录限流、管理面 404、`noindex` |
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

## 公口纪律

- 公网只开 22 / 80 / 443；`18080`（web 容器）与 `15435`（PG）**只绑 127.0.0.1**，
  安全组不放行；
- 所有 `location ~ ^/(actuator|swagger-ui|v3/api-docs)` 一律 404 —— 管理面不对公网；
- 所有演示站点带 `X-Robots-Tag: noindex, nofollow`，不被搜索引擎收录。
