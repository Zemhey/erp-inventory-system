package com.erp.sys.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 敏感配置的对称加解密（AES-256-GCM）。
 *
 * <p><b>为什么必须加密</b>：模型 API Key 一旦明文落在 {@code sys_config}，
 * 任何能连数据库的人（包括 pgAdmin、备份文件、只读账号）都能直接拿走，
 * 拿去刷别人的额度。加密后即使整库泄露，没有密钥也解不出 key。
 *
 * <p><b>密钥从哪来</b>：环境变量 {@code ERP_NL_SECRET}。
 * 没配时退化为"应用内置常量派生"的密钥 —— 这<b>不是真安全</b>，
 * 它只能挡住"翻数据库直接看到"这一层。生产部署必须显式配置该变量，
 * 且**多实例必须一致**，否则 A 实例写的 key 在 B 实例上解不开（表现为
 * "保存成功但一调用就 401"）。
 *
 * <p><b>为什么是 GCM 而不是 CBC</b>：GCM 自带完整性校验，密文被改动会解密失败，
 * 而不是解出一段垃圾去当 key 用。代价是同一密钥下 IV 绝不能复用，
 * 所以这里每次加密都随机 12 字节 IV 并存进密文前缀。
 *
 * <p><b>格式</b>：{@code enc:v1:} + Base64(IV‖密文+tag)。
 * 没有该前缀的值按明文处理 —— 这样"手工写库/老数据"不会因为没加密就报错，
 * 只是下次保存时才变成密文。
 */
@Component
public class SecretBox {

    private static final String PREFIX = "enc:v1:";
    private static final String FALLBACK_SEED = "erp-nl-config-local-default-seed";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${erp.nl.secret:}") String secret) {
        String material = (secret == null || secret.isBlank()) ? FALLBACK_SEED : secret.trim();
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    /** 加密。空值原样返回（空 key 没有加密的意义，也便于"未配置"判定）。 */
    public String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            // 加密失败不能让"保存配置"这个动作静默成功：宁可报错，也不要明文落库。
            throw new IllegalStateException("加密配置值失败：" + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 解密。无前缀视为明文直接返回；前缀存在但解不开时返回空串而不是抛异常 ——
     * 密钥被换掉（换机器/换 ERP_NL_SECRET）属于运维事件，此时应表现为
     * "key 失效，请重新填写"，而不是让整个设置页 500。
     */
    public String open(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length <= IV_BYTES) {
                return "";
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[raw.length - IV_BYTES];
            System.arraycopy(raw, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化配置加密密钥", ex);
        }
    }
}
