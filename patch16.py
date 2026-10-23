p='frontend/web/src/api/client.ts'
s=open(p,encoding='utf-8').read()

anchor = """// ═══════════════════════ 金额工具（ADR-0005） ═══════════════════════"""

new_types = """// ═══════════════════════ IAM 深化 / 审批（V2-6） ═══════════════════════

/**
 * 账号视图（FR-IAM-05）。
 *
 * ★ 刻意没有 `passwordHash` 字段：后端从不下发它，前端也不该有地方装它。
 *   这类"类型上就装不下"的约束比任何注释都可靠。
 */
export interface UserView {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
  failedAttempts: number
  lockedUntil: string | null
  passwordExpiresAt: string | null
  mustChangePassword: boolean
}

/** 角色视图。 */
export interface RoleView {
  id: number
  code: string
  name: string
  description: string | null
}

/** 数据权限视图：读写同形（每项是 `ALL` / `SELF` / `WAREHOUSE:<id>`）。 */
export interface DataScopeView {
  direct: string[]
  inherited: string[]
}

/** 审批节点视图（FR-APS-01/02）。 */
export interface ApprovalStepView {
  stepNo: number
  name: string
  approverPermission: string
  approveMode: 'SINGLE' | 'ALL_SIGN' | 'ANY_SIGN'
  requiredApprovals: number
  thresholdAmount: number
  status: 'DEFINED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED'
  approvedBy: number[]
  decidedAt: string | null
  decisionBy: number | null
  remark: string | null
}

/** 审批实例视图。`status` 是**审批**的七态，与单据七态无关。 */
export interface ApprovalInstanceView {
  id: number
  flowId: number
  bizType: string
  bizId: number
  bizRefNo: string
  amount: number
  currentStep: number
  status: 'DRAFT' | 'PENDING' | 'TRANSFERRED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'CANCELLED'
  initiatorId: number
  steps: ApprovalStepView[]
}

/** 审批流定义视图（含节点）。 */
export interface ApprovalFlowView {
  id: number
  code: string
  name: string
  bizType: string
  description: string | null
  enabled: boolean
  steps: ApprovalStepView[]
}

/** 用户与角色管理（FR-IAM-05）+ 数据权限配置（FR-IAM-04）。 */
export const iamApi = {
  users: (keyword?: string, page = 1, size = 50) =>
    http.get<never, PageData<UserView>>('/iam/users', { params: { keyword, page, size } }),
  user: (id: number) => http.get<never, UserView>(`/iam/users/${id}`),
  createUser: (body: {
    username: string
    password: string
    displayName?: string
    enabled?: boolean
    roles?: string[]
  }) => http.post<never, UserView>('/iam/users', body),
  updateRoles: (id: number, roles: string[]) =>
    http.put<never, UserView>(`/iam/users/${id}/roles`, { roles }),
  setEnabled: (id: number, enabled: boolean) =>
    http.post<never, UserView>(`/iam/users/${id}/enabled`, { enabled }),
  resetPassword: (id: number, newPassword: string) =>
    http.post<never, { userId: number; mustChangePassword: boolean }>(
      `/iam/users/${id}/reset-password`,
      { newPassword },
    ),

  roles: () => http.get<never, { items: RoleView[] }>('/iam/roles'),
  createRole: (body: { code: string; name?: string; description?: string }) =>
    http.post<never, RoleView>('/iam/roles', body),
  rolePermissions: (id: number) =>
    http.get<never, { role: RoleView; permissions: string[] }>(`/iam/roles/${id}/permissions`),
  replacePermissions: (id: number, permissions: string[]) =>
    http.put<never, { role: RoleView; permissions: string[] }>(
      `/iam/roles/${id}/permissions`,
      { permissions },
    ),

  userScopes: (id: number) => http.get<never, DataScopeView>(`/iam/users/${id}/data-scopes`),
  replaceUserScopes: (id: number, scopes: string[]) =>
    http.put<never, DataScopeView>(`/iam/users/${id}/data-scopes`, { scopes }),
  roleScopes: (id: number) =>
    http.get<never, { scopes: string[] }>(`/iam/roles/${id}/data-scopes`),
  replaceRoleScopes: (id: number, scopes: string[]) =>
    http.put<never, { scopes: string[] }>(`/iam/roles/${id}/data-scopes`, { scopes }),
}

/**
 * 审批流（FR-APS-02）。
 *
 * ★ 前端只暴露"同意/驳回/撤回/转办"，**没有**"改单据状态"的入口 ——
 *   审批通过后单据仍需在单据页点审核。这条边界写在这里，免得有人图省事加一个
 *   `approveAndApplyDocument()`。
 */
export const approvalApi = {
  list: (status?: string, bizType?: string, page = 1, size = 50) =>
    http.get<never, PageData<ApprovalInstanceView>>('/approvals', {
      params: { status, bizType, page, size },
    }),
  detail: (id: number) => http.get<never, ApprovalInstanceView>(`/approvals/${id}`),
  submit: (body: { bizType: string; bizId: number; bizRefNo: string; amount: number }) =>
    http.post<never, ApprovalInstanceView>('/approvals', body),
  decide: (id: number, action: 'approve' | 'reject', remark?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/${action}`, { remark }),
  withdraw: (id: number, reason?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/withdraw`, { remark: reason }),
  transfer: (id: number, targetUserId: number, remark?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/transfer`, { targetUserId, remark }),
  acceptTransfer: (id: number) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/accept-transfer`),
  flows: () => http.get<never, { items: ApprovalFlowView[] }>('/approvals/flows'),
  setFlowEnabled: (id: number, enabled: boolean) =>
    http.post<never, ApprovalFlowView>(`/approvals/flows/${id}/enabled`, null, {
      params: { enabled },
    }),
}

// ═══════════════════════ 金额工具（ADR-0005） ═══════════════════════"""

assert anchor in s
s=s.replace(anchor,new_types,1)
open(p,'w',encoding='utf-8').write(s)
print("added iam/approval api")
