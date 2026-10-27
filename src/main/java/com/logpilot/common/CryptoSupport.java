package com.logpilot.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密（AES-256-GCM）：用于加密存储在数据库中的 SSH 密码/私钥。
 * 密钥来自配置项 logpilot.crypto-key（任意字符串，SHA-256 派生 256 位密钥）。
 */
@Component
public class CryptoSupport {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoSupport(@Value("${logpilot.crypto-key:logpilot-default}") String keyStr) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest((keyStr == null ? "logpilot-default" : keyStr).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密组件失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "enc:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 兼容历史明文：非 enc: 前缀的值原样返回。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith("enc:")) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(4));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 掩码显示。 */
    public static String mask(String v) {
        if (v == null || v.isEmpty()) return "";
        return "******";
    }
}
