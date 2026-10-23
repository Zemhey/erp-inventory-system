p='backend/server/src/main/java/com/erp/iam/domain/PasswordPolicy.java'
s=open(p,encoding='utf-8').read()
old="""    private static final Set<String> COMMON_WEAK = Set.of(
            "password", "passw0rd", "12345678", "123456789", "1234567890",
            "admin123", "admin@123", "administrator", "qwerty", "qwerty123",
            "abc12345", "iloveyou", "11111111", "88888888", "password1",
            "p@ssw0rd", "letmein", "letmein1", "welcome", "welcome1", "changeme",
            "qwerty123", "abc12345", "qwertyuiop");"""
new="""    /**
     * <p><b>★ 用 {@code Set.copyOf(List.of(...))} 而不是 {@code Set.of(...)}</b>：
     * {@code Set.of} 在遇到<b>重复元素</b>时会抛 {@code IllegalArgumentException}，
     * 而这个静态初始化异常会被 JDK 包成 {@code NoClassDefFoundError: Could not
     * initialize class PasswordPolicy} —— 报错信息完全看不出"是字典里有重复项"，
     * 而症状是整片用例一起红（连 {@code PasswordPolicy} 都加载不了）。
     * 第一次跑测试时真实踩到：手写长字典很容易重复，用 List 兜底后重复只是去重，
     * 不会让整个类初始化失败。
     */
    private static final Set<String> COMMON_WEAK = Set.copyOf(List.of(
            "password", "passw0rd", "12345678", "123456789", "1234567890",
            "admin123", "admin@123", "administrator", "qwerty", "qwerty123",
            "abc12345", "iloveyou", "11111111", "88888888", "password1",
            "p@ssw0rd", "letmein", "letmein1", "welcome", "welcome1", "changeme",
            "qwertyuiop", "1qaz2wsx", "zaq12wsx", "abcd1234", "a1b2c3d4"));"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("COMMON_WEAK fixed")
