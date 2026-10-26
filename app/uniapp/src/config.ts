let h5 = false
// #ifdef H5
h5 = true
// #endif

export const IS_H5 = h5

const rawBase = (import.meta.env.VITE_API_BASE_URL ?? '').trim()

/**
 * H5 缺省走 /api/v1（Vite 代理到 8082）；小程序 / Android 没有代理，
 * 必须在构建环境里显式配置绝对地址，否则所有请求都会指向不存在的相对路径。
 */
export const API_BASE_URL = (rawBase || '/api/v1').replace(/\/+$/, '')

export const API_BASE_IS_ABSOLUTE = /^https?:\/\//i.test(API_BASE_URL)

export const API_BASE_HINT =
  '非 H5 端需要在 .env.production.local 里配置 VITE_API_BASE_URL（形如 https://域名/api/v1），' +
  '然后重新执行 npm run build:mp-weixin 或 npm run build:app'
