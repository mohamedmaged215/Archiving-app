package com.homenet.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class RouterCredentialsStore {
    private static final String PREFS = "homenet_router_credentials";
    private static final String KEY_ALIAS = "homenet_router_login_v1";
    private static final String DEFAULT_ADDRESS = "192.168.0.1";

    static final class Credentials {
        final String address;
        final String username;
        final String password;

        Credentials(String address, String username, String password) {
            this.address = address;
            this.username = username;
            this.password = password;
        }
    }

    private final SharedPreferences preferences;

    RouterCredentialsStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasCredentials() {
        return preferences.contains("password") && !preferences.getString("password", "").isEmpty();
    }

    String savedAddress() {
        return preferences.getString("address", DEFAULT_ADDRESS);
    }

    String savedUsername() {
        return preferences.getString("username", "admin");
    }

    void save(String address, String username, String password) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("التشغيل الآمن في الخلفية يحتاج Android 6 أو أحدث.");
        }
        String normalizedAddress = address == null ? "" : address.trim();
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedAddress.isEmpty() || normalizedUsername.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("أدخل عنوان الراوتر واسم المستخدم وكلمة المرور.");
        }
        preferences.edit()
                .putString("address", normalizedAddress)
                .putString("username", normalizedUsername)
                .putString("password", encrypt(password))
                .apply();
    }

    Credentials load() throws Exception {
        if (!hasCredentials()) throw new IllegalStateException("بيانات دخول الراوتر غير محفوظة.");
        return new Credentials(savedAddress(), savedUsername(), decrypt(preferences.getString("password", "")));
    }

    private String encrypt(String plainText) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        String[] parts = encoded.split("\\.", 2);
        if (parts.length != 2) throw new IllegalStateException("بيانات الراوتر المحفوظة غير صالحة؛ احفظها مرة أخرى.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        );
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
