import io

def resolve(path, note):
    s=io.open(path,encoding='utf-8').read()
    while '<<<<<<< HEAD' in s:
        start=s.index('<<<<<<< HEAD')
        mid=s.index('=======',start)
        end=s.index('>>>>>>> main',mid)
        ours=s[start+len('<<<<<<< HEAD\n'):mid]
        theirs=s[mid+len('=======\n'):end]
        # our block ends without closing brace in router case; theirs ends with '},'
        ours=ours.rstrip('\n')
        theirs=theirs.rstrip('\n')
        # If our part doesn't end with '},' but theirs does, append '},' to ours
        if theirs.endswith('},') and not ours.endswith('},'):
            if ours.endswith('}'):
                ours = ours + ','
            else:
                ours = ours + '\n      },'
        combo = ours + '\n' + theirs
        s = s[:start] + combo + s[end+len('>>>>>>> main\n'):]
    io.open(path,'w',encoding='utf-8').write(s)
    print("resolved:", path, note)

resolve('frontend/web/src/layouts/MainLayout.vue','menus both kept')
resolve('frontend/web/src/router/index.ts','routes both kept')
