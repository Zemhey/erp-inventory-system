import { computed, ref } from 'vue'
import { mdmApi, type Product, type Sku, type Warehouse } from '@/api/client'
import { hasPermission } from '@/stores/session'
import { safe } from '@/utils/safe'

/**
 * 主数据名称字典。
 *
 * 后端余额 / 流水只返回 skuId / warehouseId（M3 已登记的后端缺口 G7），
 * 移动端必须自己取一份字典做**显示映射** —— 这不是业务计算。
 * 取不到名字时退回 ID：宁可不完整，也不要显示空白让人以为没数据。
 *
 * 字典放在模块级缓存：工作台与库存页共用，避免来回切 tab 重复拉三次。
 * 停用仓库也要能显示名字，因此 warehouses 用 onlyEnabled=false。
 */

const PAGE_LIMIT = 200

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
const truncated = ref(false)
const warning = ref('')
const loaded = ref(false)

let inFlight: Promise<void> | null = null

async function doLoad(): Promise<void> {
  if (!hasPermission('mdm:read')) {
    warning.value = '当前账号没有主数据读权限，名称列显示为 ID'
    loaded.value = true
    return
  }

  const [wh, sku, prod] = await Promise.all([
    safe(mdmApi.listWarehouses(false)),
    safe(mdmApi.listSkus({ page: 1, size: PAGE_LIMIT })),
    safe(mdmApi.listProducts({ page: 1, size: PAGE_LIMIT })),
  ])

  if (wh.ok) warehouses.value = wh.value
  if (sku.ok) skus.value = sku.value.items
  if (prod.ok) products.value = prod.value.items

  truncated.value =
    (sku.ok && sku.value.total > sku.value.items.length) ||
    (prod.ok && prod.value.total > prod.value.items.length)

  const failed = !wh.ok || !sku.ok || !prod.ok
  warning.value = truncated.value
    ? '主数据超过 200 条，名称字典只覆盖前 200 条'
    : failed
      ? '部分主数据加载失败，缺少名称的条目显示为 ID'
      : ''

  // 有任一失败就不锁定 loaded，下次进入页面还能自动重试
  loaded.value = !failed
}

export function useDictionary() {
  async function loadDictionary(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (inFlight) return inFlight
    inFlight = doLoad().finally(() => {
      inFlight = null
    })
    return inFlight
  }

  const warehouseNames = computed(() => {
    const map = new Map<number, string>()
    for (const warehouse of warehouses.value) {
      map.set(warehouse.id, warehouse.enabled ? warehouse.name : `${warehouse.name}（已停用）`)
    }
    return map
  })

  const skuNames = computed(() => {
    const productNames = new Map(products.value.map((product) => [product.id, product.name]))
    const map = new Map<number, string>()
    for (const sku of skus.value) {
      const productName = productNames.get(sku.productId) ?? `商品#${sku.productId}`
      map.set(sku.id, `${productName} · ${sku.color}-${sku.size}`)
    }
    return map
  })

  function warehouseText(id: number): string {
    return warehouseNames.value.get(id) ?? `仓库#${id}`
  }

  function skuText(id: number): string {
    return skuNames.value.get(id) ?? `SKU#${id}`
  }

  return {
    warehouses,
    skus,
    products,
    truncated,
    warning,
    loadDictionary,
    warehouseNames,
    skuNames,
    warehouseText,
    skuText,
  }
}
