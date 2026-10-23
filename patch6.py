p='backend/server/src/main/java/com/erp/sal/application/SalesOrderApplicationService.java'
s=open(p,encoding='utf-8').read()

s=s.replace("import com.erp.shared.period.PeriodGuard;",
"import com.erp.shared.approval.ApprovalGate;\nimport com.erp.shared.period.PeriodGuard;\nimport com.erp.shared.security.ActorContext;",1)

old="""    private final PeriodGuard periodGuard;"""
assert old in s
new="""    private final PeriodGuard periodGuard;
    /** 审批闸门（V2-6 FR-IAM-07）：只输出可否，不改单据状态。 */
    private final ApprovalGate approvalGate;
    /** 当前操作人（数据权限"按本人"）。 */
    private final ActorContext actors;"""
s=s.replace(old,new,1)

import re
# constructor: add params before closing ') {'
m=re.search(r'public SalesOrderApplicationService\(([^)]*)\)\s*\{', s, re.S)
params=m.group(1)
if 'ApprovalGate approvalGate' not in params:
    new_params = params.rstrip()
    if not new_params.endswith(','):
        new_params += ','
    new_params += "\n                                           ApprovalGate approvalGate,\n                                           ActorContext actors "
    s = s[:m.start(1)] + new_params + s[m.end(1):]
    s = re.sub(r'(this\.periodGuard = periodGuard;)', r'\1\n        this.approvalGate = approvalGate;\n        this.actors = actors;', s, count=1)
open(p,'w',encoding='utf-8').write(s)
print("patched SO service ctor")
