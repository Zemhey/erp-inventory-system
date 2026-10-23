p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=open(p,encoding='utf-8').read()

# Fix pattern: .content(json.writeValueAsString(Map.of(...)))
#                .andExpect(  --> ) before .andExpect
fixes = [
# block1: change-password
("""                        .content(json.writeValueAsString(Map.of(
                                "oldPassword", "Exp1!ired", "newPassword", "N3w!passw0rd")))
                .andExpect(status().isOk());""",
 """                        .content(json.writeValueAsString(Map.of(
                                "oldPassword", "Exp1!ired", "newPassword", "N3w!passw0rd"))))
                .andExpect(status().isOk());"""),
# block2: P-LIVE-1
("""                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-1", "name", "授权前")))
                .andExpect(status().isForbidden());""",
 """                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-1", "name", "授权前"))))
                .andExpect(status().isForbidden());"""),
# block3: P-LIVE-2
("""                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-2", "name", "授权后")))
                .andExpect(status().isCreated())""",
 """                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-2", "name", "授权后"))))
                .andExpect(status().isCreated())"""),
# block4: P-LIVE-3
("""                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-3", "name", "撤权后")))
                .andExpect(status().isForbidden());""",
 """                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-3", "name", "撤权后"))))
                .andExpect(status().isForbidden());"""),
# block5: data-scopes PUT (viewer)
("""                        .content(json.writeValueAsString(Map.of("scopes", List.of("ALL"))))
                .andExpect(status().isForbidden())""",
 """                        .content(json.writeValueAsString(Map.of("scopes", List.of("ALL")))))
                .andExpect(status().isForbidden())"""),
# block6: data-scopes PUT (admin)
("""                        .content(json.writeValueAsString(Map.of("scopes", List.of("SELF"))))
                .andExpect(status().isOk())""",
 """                        .content(json.writeValueAsString(Map.of("scopes", List.of("SELF")))))
                .andExpect(status().isOk())"""),
]
for old,new in fixes:
    assert old in s, old[:80]
    s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("fixed parens in IamDeepeningIT")
