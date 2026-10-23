# 1) PasswordPolicy: add letmein1-style entries + fix describe text
p='backend/server/src/main/java/com/erp/iam/domain/PasswordPolicy.java'
s=open(p,encoding='utf-8').read()
s=s.replace('"p@ssw0rd", "letmein", "welcome", "welcome1", "changeme");',
            '"p@ssw0rd", "letmein", "letmein1", "welcome", "welcome1", "changeme",\n            "qwerty123", "abc12345", "qwertyuiop");')
s=s.replace('maxAgeDays > 0 ? "密码每 %d 天过期".formatted(maxAgeDays) : "密码不按时间过期");',
            'maxAgeDays > 0 ? "密码每 %d 天过期".formatted(maxAgeDays) : "密码不按时间过期");')
open(p,'w',encoding='utf-8').write(s)
print("weak list extended")

# 2) test: describe assertion — '不按时间过期' contains check uses element match; use anySatisfy
p='backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java'
s=open(p,encoding='utf-8').read()
s=s.replace("""        assertThat(PasswordPolicy.permissive().describe()).contains("不按时间过期");""",
"""        assertThat(String.join(" ", PasswordPolicy.permissive().describe()))
                .contains("不按时间过期");""")
open(p,'w',encoding='utf-8').write(s)
print("describe assertion fixed")
