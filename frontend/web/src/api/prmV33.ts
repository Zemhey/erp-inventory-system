import http from './client'

/**
 * V3-3 · PRM 价格与促销 API（FR-V3-20/21）。
 *
 * 单独成文件：与 salesV23.ts 同思路，本泳道只追加自己的端点与类型，
 * 避免三条泳道在同一区域改 client.ts 造成冲突。
 *
 * ★ 金额口径：**分**（priceAmount / effectivePrice），折扣是**万分比**
 *   （discountBp，10000 = 不打折），与 sal 报价/出货一致；页面输入用元、
 *   提交前 ×100 转分，展示用 fenToYuan。
 */

export interface PricePlanItem {
  id: number | null
  skuId: number
  priceAmount: number
  discountBp: number
  minQty: number
  effectivePrice?: number
}

export interface PricePlan {
  id: number
  code: string
  name: string
  planType: 'AGREEMENT' | 'CUSTOMER' | 'CUSTOMER_LEVEL'
  customerId: number | null
  customerLevel: string | null
  priority: number
  startDate: string | null
  endDate: string | null
  enabled: boolean
  remark: string | null
  items: PricePlanItem[]
}

export interface PricePlanCreate {
  code: string
  name: string
  planType: PricePlan['planType']
  customerId?: number | null
  customerLevel?: string | null
  priority?: number
  startDate?: string | null
  endDate?: string | null
  enabled?: boolean
  remark?: string
  items: { skuId: number; priceAmount: number; discountBp: number; minQty: number }[]
}

export interface PriceResolution {
  source: 'AGREEMENT_PRICE' | 'CUSTOMER_PRICE' | 'CUSTOMER_LEVEL_PRICE' | 'STANDARD_PRICE'
  planCode: string | null
  planId: number | null
  priceAmount: number
  discountBp: number
  qty: number
}

export interface PromotionRule {
  id: number | null
  conditionJson: string | null
  benefitJson: string | null
}

export interface Promotion {
  id: number
  code: string
  name: string
  promoType: 'TIME_DISCOUNT' | 'FULL_REDUCE' | 'QTY_DISCOUNT'
  startTime: string | null
  endTime: string | null
  priority: number
  status: 'DRAFT' | 'ACTIVE' | 'STOPPED'
  remark: string | null
  rules: PromotionRule[]
}

export interface PromotionCreate {
  code: string
  name: string
  promoType: Promotion['promoType']
  startTime?: string | null
  endTime?: string | null
  priority?: number
  remark?: string
  rules: { conditionJson: string; benefitJson: string }[]
}

export const pricePlanApi = {
  list: (keyword?: string) =>
    http.get<never, { items: PricePlan[]; total: number }>('/prm/price-plans', {
      params: { keyword },
    }),
  get: (id: number) => http.get<never, PricePlan>(`/prm/price-plans/${id}`),
  create: (body: PricePlanCreate) => http.post<never, PricePlan>('/prm/price-plans', body),
  update: (id: number, body: PricePlanCreate) =>
    http.put<never, PricePlan>(`/prm/price-plans/${id}`, body),
  disable: (id: number) => http.post<never, PricePlan>(`/prm/price-plans/${id}/disable`),
  price: (params: { skuId: number; customerId?: number; qty?: number }) =>
    http.get<never, PriceResolution>('/prm/price', { params }),
}

export const promotionApi = {
  list: (params: { status?: string; keyword?: string }) =>
    http.get<never, { items: Promotion[]; total: number }>('/prm/promotions', { params }),
  get: (id: number) => http.get<never, Promotion>(`/prm/promotions/${id}`),
  create: (body: PromotionCreate) => http.post<never, Promotion>('/prm/promotions', body),
  update: (id: number, body: PromotionCreate) =>
    http.put<never, Promotion>(`/prm/promotions/${id}`, body),
  activate: (id: number) => http.post<never, Promotion>(`/prm/promotions/${id}/activate`),
  stop: (id: number) => http.post<never, Promotion>(`/prm/promotions/${id}/stop`),
}
