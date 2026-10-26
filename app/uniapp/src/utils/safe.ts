export type Settled<T> = { ok: true; value: T } | { ok: false; error: unknown }

/** Promise.allSettled 的跨端版本（老基础库也能用），避免一个接口失败拖垮整页。 */
export async function safe<T>(promise: Promise<T>): Promise<Settled<T>> {
  try {
    return { ok: true, value: await promise }
  } catch (error) {
    return { ok: false, error }
  }
}
