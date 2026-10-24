import io

def resolve_keep_both(path, sep='\n'):
    s=io.open(path,encoding='utf-8').read()
    while '<<<<<<< HEAD' in s:
        start=s.index('<<<<<<< HEAD')
        mid=s.index('=======',start)
        end=s.index('>>>>>>> main',mid)
        ours=s[start+len('<<<<<<< HEAD\n'):mid]
        theirs=s[mid+len('=======\n'):end]
        combo = ours.rstrip('\n') + '\n' + theirs
        s = s[:start] + combo + s[end+len('>>>>>>> main\n'):]
    io.open(path,'w',encoding='utf-8').write(s)
    print("keep-both resolved:", path)

resolve_keep_both('backend/server/src/test/java/com/erp/support/TestDataFactory.java')
resolve_keep_both('backend/server/src/test/java/com/erp/api/ApiContractMappingIT.java')
