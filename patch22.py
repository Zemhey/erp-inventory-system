p='backend/server/src/main/java/com/erp/iam/application/DataScopeAdminService.java'
s=open(p,encoding='utf-8').read()
s=s.replace("import com.erp.iam.repository.DataScopeRepository;\n",
            "import com.erp.iam.repository.DataScopeRepository;\nimport com.erp.iam.repository.RoleRepository;\n",1)
open(p,'w',encoding='utf-8').write(s)
print("re-added RoleRepository import")
