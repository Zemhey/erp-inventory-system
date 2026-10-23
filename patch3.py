p='backend/server/src/main/java/com/erp/pur/application/PurchaseOrderApplicationService.java'
s=open(p,encoding='utf-8').read()
old="""                // 数据权限"按本人"（FR-IAM-04）依赖这一列；系统任务无上下文时为 null
                actors.currentUserId().isPresent()
                        ? actors.currentUserId().getAsLong() : null);"""
new="""                // 数据权限"按本人"（FR-IAM-04）依赖这一列；系统任务无上下文时为 null
                actors.current().map(com.erp.shared.security.ActorContext.Actor::id).orElse(null));"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("fixed")
