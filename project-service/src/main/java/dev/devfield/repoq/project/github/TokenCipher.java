package dev.devfield.repoq.project.github;

import dev.devfield.repoq.project.api.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TokenCipher {
    private final SecureRandom random = new SecureRandom();
    private final String encodedKey;
    private final int version;

    public TokenCipher(@Value("${devfield.project.github.token-key:}") String encodedKey,
                       @Value("${devfield.project.github.token-key-version:1}") int version) {
        this.encodedKey = encodedKey;
        this.version = version;
    }

    public int version() { return version; }

    private SecretKeySpec key() {
        try {
            byte[] raw = Base64.getDecoder().decode(encodedKey);
            if (raw.length != 32 || version < 1) throw new IllegalArgumentException();
            return new SecretKeySpec(raw, "AES");
        } catch (RuntimeException e) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_configuration_unavailable");
        }
    }

    public String encrypt(String value, String context) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_credential_unavailable");
        }
    }

    public String decrypt(String encoded, int storedVersion, String context) {
        if (storedVersion != version) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_key_version_unavailable");
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, combined, 0, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_credential_unavailable");
        }
    }
}
