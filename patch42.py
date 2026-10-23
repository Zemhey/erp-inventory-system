p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()
s=s.replace("""     * {@code V14__iam_deepening_and_approval.sql}（iam:*/aps:*）。""",
"""     * {@code V14__iam_deepening_and_approval.sql}（iam 与 aps 两组权限码）。""")
open(p,'w',encoding='utf-8').write(s)
print("javadoc fixed")
