import io
p='backend/server/src/main/java/com/erp/pur/repository/PurchaseOrderRepository.java'
s=io.open(p,encoding='utf-8').read()
start=s.index('<<<<<<< HEAD')
mid=s.index('=======',start)
end=s.index('>>>>>>> main',mid)
end2=end+len('>>>>>>> main\n')
ours=s[start+len('<<<<<<< HEAD\n'):mid]
theirs=s[mid+len('=======\n'):end]
# resolve: our javadoc (for page) + their new methods, then the page method follows
resolved = ours.rstrip('\n') + '\n' + theirs.rstrip('\n') + '\n'
s = s[:start] + resolved + s[end2:]
io.open(p,'w',encoding='utf-8').write(s)
print("resolved PurchaseOrderRepository")
