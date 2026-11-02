package ayla.push;

import java.nio.charset.StandardCharsets;
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
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;

    public VapidKeys(@Value("${ayla.vapid.subject:mailto:ayla@localhost}") String subject,
            @Value("${ayla.vapid.public-key:}") String publicKeyBase64,
            @Value("${ayla.vapid.private-key:}") String privateKeyBase64,
            @Value("${ayla.vapid.required:false}") boolean required) {
        this.subject = subject == null || subject.isBlank() ? "mailto:ayla@localhost" : subject.trim();
        if (!this.subject.startsWith("mailto:") && !this.subject.startsWith("https://")) {
            throw new IllegalArgumentException("O VAPID subject deve começar com mailto: ou https://");
        }

        boolean hasPublic = publicKeyBase64 != null && !publicKeyBase64.isBlank();
        boolean hasPrivate = privateKeyBase64 != null && !privateKeyBase64.isBlank();
        if (hasPublic != hasPrivate) {
            throw new IllegalStateException("Configure as duas chaves VAPID (pública e privada) juntas.");
        }

        if (hasPublic) {
            try {
                KeyFactory factory = KeyFactory.getInstance("EC");
                ECPublicKey candidatePublic = (ECPublicKey) factory.generatePublic(
                        new X509EncodedKeySpec(decode(publicKeyBase64)));
                ECPrivateKey candidatePrivate = (ECPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(decode(privateKeyBase64)));
                validatePair(candidatePublic, candidatePrivate);
                this.publicKey = candidatePublic;
                this.privateKey = candidatePrivate;
                return;
            } catch (Exception error) {
                // Nunca imprimir material criptográfico nem valores de ambiente.
                throw new IllegalStateException("Chaves VAPID inválidas ou incompatíveis. Use EC P-256, X.509 DER e PKCS#8 DER em Base64.", error);
            }
        }

        if (required) {
            throw new IllegalStateException("Chaves VAPID obrigatórias ausentes. Configure AYLA_VAPID_PUBLIC_KEY e AYLA_VAPID_PRIVATE_KEY.");
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            this.publicKey = (ECPublicKey) pair.getPublic();
            this.privateKey = (ECPrivateKey) pair.getPrivate();
            log.warn("Par VAPID temporário criado para desenvolvimento. Configure chaves persistentes antes de produção.");
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Não foi possível inicializar chaves VAPID", error);
        }
    }

    private static void validatePair(ECPublicKey publicKey, ECPrivateKey privateKey) throws GeneralSecurityException {
        int publicBits = publicKey.getParams().getCurve().getField().getFieldSize();
        int privateBits = privateKey.getParams().getCurve().getField().getFieldSize();
        if (publicBits != 256 || privateBits != 256 ||
                !publicKey.getParams().getCurve().equals(privateKey.getParams().getCurve()) ||
                !publicKey.getParams().getGenerator().equals(privateKey.getParams().getGenerator()) ||
                !publicKey.getParams().getOrder().equals(privateKey.getParams().getOrder())) {
            throw new InvalidKeyException("VAPID requer um par EC P-256 compatível.");
        }
        byte[] challenge = "ayla-vapid-keypair-check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (!verifier.verify(signature)) throw new InvalidKeyException("As chaves VAPID não pertencem ao mesmo par.");
    }

    public String subject() { return subject; }
    public ECPublicKey publicKey() { return publicKey; }
    public ECPrivateKey privateKey() { return privateKey; }

    public String publicKeyBase64Url() { return base64Url(rawPublicKey(publicKey)); }

    public static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] base64UrlDecode(String value) {
        if (value == null) return new byte[0];
        return decode(value);
    }

    public static byte[] decode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Chave vazia");
        String fixed = value.trim();
        try { return Base64.getDecoder().decode(fixed); }
        catch (IllegalArgumentException ignored) { }
        return Base64.getUrlDecoder().decode(fixed);
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
