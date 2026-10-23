# ApprovalIT: remove redundant flow enable reset conflicts, ensure asUser is the ONLY wrapper
p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
print("asUser occurrences:", s.count("asUser("))
# print asUser helper body to verify
i=s.index("    private void asUser(long userId, Runnable body) {")
print(s[i:i+700])
