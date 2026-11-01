package ayla.push;

import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VapidKeys {
    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);
    private final String subject;
    private ECPublicKey publicKey;
    private ECPrivateKey privateKey;

    public VapidKeys(@Value("${ayla.vapid.subject:mailto:ayla@localhost}") String subject,
            @Value("${ayla.vapid.public-key:}") String publicKeyBase64,
            @Value("${ayla.vapid.private-key:}") String privateKeyBase64) {
        this.subject = subject == null || subject.isBlank() ? "mailto:ayla@localhost" : subject;
        if (!publicKeyBase64.isBlank() && !privateKeyBase64.isBlank()) {
            try {
                this.publicKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                        new X509EncodedKeySpec(decode(publicKeyBase64)));
                this.privateKey = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(
                        new PKCS8EncodedKeySpec(decode(privateKeyBase64)));
                return;
            } catch (Exception error) {
                log.warn("Chaves VAPID inválidas; gerando par temporário. Erro: {}", error.getMessage());
            }
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            this.publicKey = (ECPublicKey) pair.getPublic();
            this.privateKey = (ECPrivateKey) pair.getPrivate();
            log.warn("Par VAPID gerado temporariamente. Persista as chaves para produção. Public: {} Private: {}",
                    Base64.getEncoder().encodeToString(this.publicKey.getEncoded()),
                    Base64.getEncoder().encodeToString(this.privateKey.getEncoded()));
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível inicializar chaves VAPID", error);
        }
    }

    public String subject() { return subject; }
    public ECPublicKey publicKey() { return publicKey; }
    public ECPrivateKey privateKey() { return privateKey; }

    public String publicKeyBase64Url() {
        return base64Url(rawPublicKey(publicKey));
    }

    public static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] base64UrlDecode(String value) {
        if (value == null) return new byte[0];
        String fixed = value.replace('-', '+').replace('_', '/');
        while (fixed.length() % 4 != 0) fixed += "=";
        return Base64.getDecoder().decode(fixed);
    }

    public static byte[] decode(String value) {
        String fixed = value.trim();
        try { return Base64.getDecoder().decode(fixed); }
        catch (IllegalArgumentException ignored) { }
        try { return Base64.getUrlDecoder().decode(fixed); }
        catch (IllegalArgumentException ignored) { }
        String padded = fixed.replace('-', '+').replace('_', '/');
        while (padded.length() % 4 != 0) padded += "=";
        return Base64.getDecoder().decode(padded);
    }

    public static byte[] rawPublicKey(ECPublicKey key) {
        ECPoint point = key.getW();
        int size = (key.getParams().getOrder().bitLength() + 7) / 8;
        byte[] x = toArray(point.getAffineX(), size);
        byte[] y = toArray(point.getAffineY(), size);
        byte[] raw = new byte[1 + 2 * size];
        raw[0] = 0x04;
        System.arraycopy(x, 0, raw, 1, size);
        System.arraycopy(y, 0, raw, 1 + size, size);
        return raw;
    }

    private static byte[] toArray(java.math.BigInteger value, int size) {
        byte[] source = value.toByteArray();
        if (source.length == size) return source;
        byte[] target = new byte[size];
        int start = Math.max(0, source.length - size);
        int offset = size - Math.min(size, source.length);
        System.arraycopy(source, start, target, offset, Math.min(size, source.length));
        return target;
    }
}