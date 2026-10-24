import io
p='backend/server/src/test/java/com/erp/arch/ArchitectureTest.java'
s=io.open(p,encoding='utf-8').read()

# Resolve hunk 1 (rules): keep ours then theirs
start=s.index('<<<<<<< HEAD')
mid=s.index('=======',start)
end=s.index('>>>>>>> main',mid)
ours=s[start+len('<<<<<<< HEAD\n'):mid]
theirs=s[mid+len('=======\n'):end]
combo=ours.rstrip('\n')+'\n\n'+theirs.rstrip('\n')+'\n'
s=s[:start]+combo+s[end+len('>>>>>>> main\n'):]
io.open(p,'w',encoding='utf-8').write(s)
print("hunk1 (rules) merged")
