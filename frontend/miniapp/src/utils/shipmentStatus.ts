/**
 * 发货单状态镜像（FR-SAL-06，ShipmentStatus 五态）。
 *
 * ★ 又一套**独立**状态机：`sal/domain/ShipmentStatus.java` 的类注释写明
 *   「不是共享单据状态机：发货单不参与收付款/账期统计」。
 *   所以这里既不复用 documentStatus.ts 的七态，也不复用它的色板含义 ——
 *   发货侧关心的是「货扫齐了没有」，不是「单子审核了没有」。
 *
 * 事实源：
 * - 可扫码的状态 = DRAFT / PICKING（ShipmentApplicationService:186 之外一律拒绝）
 * - 可确认发货的状态 = PICKED（同文件 :254，未扫满直接拒）
 * - 权限码：扫码与生成计划要 `sal:pick`，确认发货要 `sal:ship`（ShipmentController:47/71/79）
 */

export const SHIPMENT_STATUSES = ['DRAFT', 'PICKING', 'PICKED', 'COMPLETED', 'VOIDED'] as const
export type ShipmentStatus = (typeof SHIPMENT_STATUSES)[number]

export interface ShipmentStatusMeta {
  label: string
  tone: 'wait' | 'working' | 'ready' | 'done' | 'quiet'
  description: string
  /** 是否还能扫码（后端的两个允许态） */
  scannable: boolean
  /** 是否还能确认发货 */
  confirmable: boolean
}

export const SHIPMENT_STATUS_META: Record<ShipmentStatus, ShipmentStatusMeta> = {
  DRAFT: {
    label: '待拣货',
    tone: 'wait',
    description: '计划已生成，一件还没扫',
    scannable: true,
    confirmable: false,
  },
  PICKING: {
    label: '拣货中',
    tone: 'working',
    description: '部分行已扫，继续扫码直到各行扫满',
    scannable: true,
    confirmable: false,
  },
  PICKED: {
    label: '拣货完成',
    tone: 'ready',
    description: '全部行扫满，可以确认发货',
    scannable: false,
    confirmable: true,
  },
  COMPLETED: {
    label: '已发货',
    tone: 'done',
    description: '已出库并登记应收，终态',
    scannable: false,
    confirmable: false,
  },
  VOIDED: {
    label: '已作废',
    tone: 'quiet',
    description: '发货单作废，不再参与作业',
    scannable: false,
    confirmable: false,
  },
}

export function isShipmentStatus(value: string | null | undefined): value is ShipmentStatus {
  return !!value && (SHIPMENT_STATUSES as readonly string[]).includes(value)
}

/** 未知状态一律按「不可作业」兜底，让后端去拒，而不是移动端自己猜一个可点状态。 */
export function shipmentStatusMeta(status: string | null | undefined): ShipmentStatusMeta {
  if (isShipmentStatus(status)) return SHIPMENT_STATUS_META[status]
  return {
    label: status || '未知状态',
    tone: 'quiet',
    description: '后端返回了移动端未识别的发货状态，请检查发货状态机是否有新增分支',
    scannable: false,
    confirmable: false,
  }
}
