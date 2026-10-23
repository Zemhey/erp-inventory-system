/** 把任意异常收敛成可展示的一行文字；后端 message 已经带定位信息，优先原样展示。 */
export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  }
  return '请求失败，请稍后重试'
}
