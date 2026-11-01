package ayla.push;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final String TABLE_SUBS = "push_subscriptions";
    private static final String TABLE_PREFS = "push_notification_preferences";
    private final JdbcTemplate jdbc;
    private final VapidKeys vapid;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PushService(JdbcTemplate jdbc, VapidKeys vapid) { this.jdbc = jdbc; this.vapid = vapid; }

    public String publicKey() { return vapid.publicKeyBase64Url(); }

    public void updatePreference(String user, boolean enabled) {
        jdbc.update("""
            insert into push_notification_preferences(user_id, enabled, updated_at)
            values (?,?,now())
            on conflict (user_id) do update set enabled=excluded.enabled, updated_at=now()
            """, user, enabled);
    }

    public boolean preferenceEnabled(String user) {
        try {
            Boolean value = jdbc.queryForObject("select enabled from " + TABLE_PREFS + " where user_id=?", Boolean.class, user);
            return value == null || value;
        } catch (Exception ignored) {
            return true;
        }
    }

    public void saveSubscription(String user, String endpoint, String p256dh, String auth, String userAgent) {
        jdbc.update("""
            insert into push_subscriptions(user_id, endpoint, p256dh, auth, user_agent, enabled, updated_at)
            values (?,?,?,?,?,true,now())
            on conflict (endpoint) do update set
              user_id=excluded.user_id, p256dh=excluded.p256dh, auth=excluded.auth,
              user_agent=excluded.user_agent, enabled=true, updated_at=now()
            """, user, endpoint, p256dh, auth, userAgent);
    }

    public void deleteSubscription(String user, String endpoint) {
        jdbc.update("delete from " + TABLE_SUBS + " where user_id=? and endpoint=?", user, endpoint);
    }

    public void deleteSubscription(String endpoint) {
        jdbc.update("delete from " + TABLE_SUBS + " where endpoint=?", endpoint);
    }

    public int countSubscriptions(String user) {
        Integer count = jdbc.queryForObject("select count(*) from " + TABLE_SUBS + " where user_id=? and enabled=true", Integer.class, user);
        return count == null ? 0 : count;
    }

    public void sendTest(String user) {
        notify(user, null, "Notificação de teste da Ayla.");
    }

    public void notifyAsync(String user, UUID conversationId, String response) {
        executor.submit(() -> {
            try { notify(user, conversationId, response); }
            catch (Throwable error) { log.warn("Falha ao enviar push para {}: {}", user, error.getMessage()); }
        });
    }

    private void notify(String user, UUID conversationId, String response) {
        if (!preferenceEnabled(user)) return;
        String preview = response == null ? "" : response.replace("\n", " ").trim();
        if (preview.length() > 150) preview = preview.substring(0, 147) + "...";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select endpoint,p256dh,auth from " + TABLE_SUBS + " where user_id=? and enabled=true", user);
        for (Map<String, Object> row : rows) {
            try {
                sendOne(String.valueOf(row.get("endpoint")), String.valueOf(row.get("p256dh")),
                        String.valueOf(row.get("auth")), "Ayla", preview.isBlank() ? "Nova resposta disponível." : preview,
                        conversationId == null ? "/chat" : "/chat");
            } catch (PushExpiredException expired) {
                deleteSubscription(String.valueOf(row.get("endpoint")));
                log.info("Inscrição push expirada removida: {}", row.get("endpoint"));
            } catch (Exception error) {
                log.warn("Falha push {}: {}", row.get("endpoint"), error.getMessage());
            }
        }
    }

    private void sendOne(String endpoint, String clientPublicKeyBase64, String authSecretBase64,
            String title, String body, String url) throws Exception {
        byte[] clientPublicKey = VapidKeys.base64UrlDecode(clientPublicKeyBase64);
        byte[] authSecret = VapidKeys.base64UrlDecode(authSecretBase64);

        // Servidor ECDH efêmero
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair serverPair = generator.generateKeyPair();
        byte[] serverPublicRaw = rawPublicKey((ECPublicKey) serverPair.getPublic());

        // shared secret
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(serverPair.getPrivate());
        ECParameterSpec spec = ((ECPrivateKey) serverPair.getPrivate()).getParams();
        ECPublicKeySpec clientSpec = new ECPublicKeySpec(decodePoint(clientPublicKey), spec);
        PublicKey clientKey = KeyFactory.getInstance("EC").generatePublic(clientSpec);
        agreement.doPhase(clientKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        // IKM RFC 8291
        byte[] ikm = hkdfExpand(hkdfExtract(authSecret, sharedSecret),
                concat("WebPush: info".getBytes(StandardCharsets.UTF_8), new byte[]{0}, clientPublicKey, serverPublicRaw), 32);

        // AES-128-GCM content encryption
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] prk = hkdfExtract(salt, ikm);
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8), 12);

        String json = "{\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body) + "\",\"url\":\"" + escape(url) + "\"}";
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[plaintext.length + 1];
        System.arraycopy(plaintext, 0, padded, 0, plaintext.length);
        padded[plaintext.length] = 0x02;

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(padded);

        ByteBuffer bodyBuffer = ByteBuffer.allocate(16 + 4 + 1 + serverPublicRaw.length + ciphertext.length);
        bodyBuffer.put(salt);
        bodyBuffer.putInt(4096);
        bodyBuffer.put((byte) serverPublicRaw.length);
        bodyBuffer.put(serverPublicRaw);
        bodyBuffer.put(ciphertext);
        byte[] requestBody = bodyBuffer.array();

        URI uri = URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String jwt = createVapidJwt(audience);
        String authorization = "vapid t=" + jwt + ", k=" + vapid.publicKeyBase64Url();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", "3600")
                .header("Urgency", "normal")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status == 410 || status == 404) throw new PushExpiredException(status);
        if (status < 200 || status >= 300) throw new RuntimeException("HTTP " + status);
    }

    private String createVapidJwt(String audience) throws Exception {
        String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String claims = base64Url(("{\"aud\":\"" + audience + "\",\"exp\":" + (now + 43200) +
                ",\"sub\":\"" + escape(vapid.subject()) + "\"}").getBytes(StandardCharsets.UTF_8));
        String input = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(vapid.privateKey());
        signature.update(input.getBytes(StandardCharsets.UTF_8));
        byte[] der = signature.sign();
        byte[] raw = derToJose(der, 64);
        return input + "." + base64Url(raw);
    }

    private static byte[] rawPublicKey(ECPublicKey key) {
        return VapidKeys.rawPublicKey(key);
    }

    private static ECPoint decodePoint(byte[] raw) throws Exception {
        // raw is uncompressed point 0x04 || X || Y
        if (raw.length < 5 || raw[0] != 0x04) throw new IllegalArgumentException("Ponto público inválido");
        int size = (raw.length - 1) / 2;
        java.math.BigInteger x = new java.math.BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + size));
        java.math.BigInteger y = new java.math.BigInteger(1, Arrays.copyOfRange(raw, 1 + size, raw.length));
        // Obtém parâmetros a partir de chave gerada temporária equivalente
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = ((ECPrivateKey) generator.generateKeyPair().getPrivate()).getParams();
        return new ECPoint(x, y);
    }

    // placeholders to compile? Need better
    private static class PushExpiredException extends Exception { PushExpiredException(int status){super(String.valueOf(status));} }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    private static String base64Url(byte[] value) { return VapidKeys.base64Url(value); }

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int counter = 1;
        int pos = 0;
        while (pos < length) {
            mac.reset();
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            byte[] block = mac.doFinal();
            int copy = Math.min(block.length, length - pos);
            System.arraycopy(block, 0, result, pos, copy);
            pos += copy;
            previous = block;
            counter++;
        }
        return result;
    }
    private static byte[] concat(byte[]... arrays) {
        int total = 0; for (byte[] a : arrays) total += a.length;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (byte[] a : arrays) buffer.put(a);
        return buffer.array();
    }
    private static byte[] derToJose(byte[] der, int size) {
        // DER: 30 len 02 len r 02 len s
        int index = 2; // skip 30 len (len one byte assumption)
        // robust parse
        int pos = 0;
        if (der[pos++] != 0x30) throw new IllegalArgumentException("DER inválido");
        int totalLength = der[pos++] & 0xff;
        if ((totalLength & 0x80) != 0) {
            int bytes = totalLength & 0x7f;
            totalLength = 0;
            for (int i = 0; i < bytes; i++) totalLength = (totalLength << 8) | (der[pos++] & 0xff);
        }
        // integer r
        if (der[pos++] != 0x02) throw new IllegalArgumentException("DER inválido");
        int rLen = der[pos++] & 0xff;
        byte[] r = new byte[rLen];
        System.arraycopy(der, pos, r, 0, rLen); pos += rLen;
        if (der[pos++] != 0x02) throw new IllegalArgumentException("DER inválido");
        int sLen = der[pos++] & 0xff;
        byte[] s = new byte[sLen];
        System.arraycopy(der, pos, s, 0, sLen); pos += sLen;
        byte[] result = new byte[size];
        int half = size / 2;
        copyInteger(r, result, 0, half);
        copyInteger(s, result, half, half);
        return result;
    }
    private static void copyInteger(byte[] source, byte[] target, int offset, int length) {
        int start = 0;
        while (start < source.length - 1 && source[start] == 0) start++;
        int srcLen = source.length - start;
        int targetStart = offset + Math.max(0, length - srcLen);
        int copy = Math.min(srcLen, length);
        System.arraycopy(source, source.length - copy, target, offset + length - copy, copy);
    }
}