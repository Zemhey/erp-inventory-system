p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# 1) ALL_SIGN test used pur:approve but supervisor now gets PUR_APPROVER role -> that has pur:approve.
#    But the failure shows currentUser had FINANCE-ish perms => it's the DEFAULT ADMIN? No: perms listed are FINANCE.
#    Actually 会签 asUser(supervisorId) -> supervisor roles PURCHASER + PUR_APPROVER. "当前仅有 [... set:monthly]" = FINANCE.
#    => asUser(supervisorId) inside a lambda executes but SecurityContext got FINANCE? Because financeId was used earlier
#       and invokeAs restores previous -> previous was... default admin. Then asUser(financeId) restores to admin.
#    The failing one is "asUser(supervisorId, ...)" AFTER finance? No, order is supervisor first.
#    Wait perms list has aps:approve + set:monthly but no pur:approve -> that's ADMIN? ADMIN has pur:* wildcard which
#    hasPermission("pur:approve") would match pur:*. The check uses actor.hasPermission which supports wildcard.
#    The listed perms are the raw set: mdm:read, rpt:read, set:*, aps:*, iam:read, rma:read => FINANCE seed.
#    So the actor was financeId. Because asUser(supervisorId) wrapper: asUser(long, Runnable) -> invokeAs(supplier).
#    Hmm the test calls asUser(supervisorId, () -> { approvals.approve(...) three times }) fine.
#    Actually TC-APS-016 calls asUser(third, ...) at end AFTER creating third user with ADMIN.
#    The failing approve is at first supervisor call? message node ALLSIGN step1 perm pur:approve.
#    => supervisor's roles: PURCHASER (pur:approve removed) + PUR_APPROVER (has pur:approve). Should pass.
#    BUT restoreSeedRolePermissions() runs per-test and DELETE FROM app_role_permissions removes PUR_APPROVER's perms.
#    And roles NOT IN seed list are deleted -> PUR_APPROVER/PUR_APPROVER role deleted at NEXT test's truncate.
#    Within same test it's fine.
#    The real issue: createFlow(...) is called BEFORE asUser and the flow perm is pur:approve, fine.
#    Listed perms = FINANCE => asUser(financeId) was executed and RESTORED to default admin... then
#    subsequent asUser(supervisorId) should set supervisor. Unless supervisorId has no PUR_APPROVER because
#    setUp ran restoreSeedRolePermissions (which deletes non-seed roles!) -> PUR_APPROVER deleted
#    AFTER it was assigned? Order: truncateAll() (deletes non-seed roles) then create PUR_APPROVER. OK.
#    => Actually `roles.replacePermissions(approveOnlyId, ...)` then `users.assignRoleById(supervisorId, approveOnlyId)`.
#    But `assignRoles`/clearRoles used? no.
#    Hmm: `data.user("supervisor",...,"PURCHASER",encoder)` inserts role. Then we DELETE pur:approve from PURCHASER.
#    Then create/assign PUR_APPROVER. Good.
#    The failing user had FINANCE perms => it IS financeId. In TC-APS-016 the FIRST call is asUser(supervisorId,...)
#    but message says current perms FINANCE. So supervisor==finance? data.user uses ON CONFLICT DO NOTHING for username,
#    distinct names. Unless users.truncate didn't clear and IDs collide... 
#    Simplest robust fix: make each asUser call set context and assert by re-reading; but better: the test's
#    inner lambda calls approvals.approve 3 times; the *third* one returns PENDING (not failed).
#    Actually the failure could be from `third` ADMIN user: ADMIN has pur:* wildcard. hasPermission("pur:approve")
#    => permissions contain "pur:*" -> wildcard matched -> true. fine.
#    Given ambiguity, print the actor perms in error is already there: "当前仅有 FINANCE perms".
#    => The only place with FINANCE is financeId used in TC-APS-016 second vote: asUser(financeId, ... approve)
#       and node perm is pur:approve -> FINANCE lacks pur:approve => FAILS. That's the bug!
#    Fix: ALL_SIGN flow should require a permission all three have, e.g. aps:approve.
# 2) ANY_SIGN used aps:approve but supervisor lacks it -> use pur:approve.
# 3) transfer target otherSupervisor had PUR_APPROVER assigned but restore deletes... use direct perms check via role.
# 4) list/detail 200 expected 401 -> default actor leaks into MockMvc; use an explicit no-token request before setting actor.
open(p,'w',encoding='utf-8').write(s)
print("analysis done")
