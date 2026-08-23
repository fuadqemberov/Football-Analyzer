package analyzer.util;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * arsiv.mackolik.com için TIMEOUT-DAYANIKLI ortak HTTP katmanı.
 *
 * HaftaDonguAnalyzer'ın v2 ağ katmanından çıkarıldı; tüm mackolik
 * analizörleri (temas, xthmatch, dongu, multiversion) bunu kullanır.
 *
 * Neden gerekli ("Read timed out" düzeltmeleri):
 *   1. Uzun socket/connect timeout'lar client seviyesinde tanımlı.
 *   2. Her istek için üstel geri çekilmeli (backoff + jitter) RETRY.
 *   3. 200 olmayan cevaplarda entity KESİNLİKLE tüketiliyor → bağlantı
 *      havuza geri dönüyor (art arda gelen timeout'ların ana sebebi buydu).
 *   4. 408/429/5xx yeniden deneniyor; 404 gibi kalıcı kodlar denenmiyor.
 *   5. Ölü/boşta bağlantılar tahliye ediliyor (evictIdle + validateAfterInactivity).
 *   6. Global istek aralığı (throttle) ile sunucu boğulmuyor — hız değil,
 *      verinin timeout almadan doğru gelmesi önceliklidir.
 *
 * DİSK ÖNBELLEĞİ (isteğe bağlı):
 *   {@code MACKOLIK_CACHE_DIR} ortam değişkeni ya da {@code -Dmackolik.cache.dir}
 *   verilirse GEÇMİŞ SEZON sayfaları gzip'li olarak diske yazılır. Geçmiş sezon
 *   verisi değişmez olduğu için bu doğruluktan hiçbir şey kaybettirmez; buna
 *   karşılık temas/dongu/xthmatch aynı takımların aynı sayfalarını tekrar tekrar
 *   indirmez (aynı iş akışında ~3 kat az istek → çok daha az timeout).
 *   Güncel sezon sayfası gün içinde değiştiği için ASLA önbelleklenmez.
 */
public final class MackolikHttpFetcher implements AutoCloseable {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS      = 15_000;
    private static final int SOCKET_TIMEOUT_MS       = 45_000;
    private static final int CONN_REQUEST_TIMEOUT_MS = 60_000;

    /** Bir URL için toplam deneme sayısı (1 ilk deneme + 7 retry). */
    private static final int MAX_ATTEMPTS = 8;
    private static final long RETRY_BASE_DELAY_MS = 700;
    private static final long RETRY_MAX_DELAY_MS  = 20_000;

    /**
     * Timeout/geçici hata görüldüğünde TÜM thread'lerin bekleyeceği ortak soğuma
     * süresi. Timeout'lar tek tek değil, sunucu yorulunca BURST halinde geliyor;
     * sadece o isteğin backoff'u yetmiyor — herkes kısa süre durup sunucuya
     * nefes aldırınca sonraki denemeler tutuyor.
     */
    private static final long COOLDOWN_ON_FAILURE_MS = 2_500;

    /** Geçici sayılan ve yeniden denenen HTTP durum kodları. */
    private static final Set<Integer> RETRYABLE_STATUS =
            new HashSet<>(Arrays.asList(408, 425, 429, 500, 502, 503, 504));

    /**
     * Yalnızca gerçekten fikstür tablosu içeren cevaplar önbelleğe yazılır.
     * Böylece sunucunun 200 döndürdüğü geçici hata/boş sayfalar önbelleğe
     * yapışıp sonraki analizörleri yanıltmaz.
     */
    private static final String CACHEABLE_MARKER = "tblFixture";

    /** Önbellek girdisinin en fazla ne kadar eski olabileceği. */
    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(30);

    private final CloseableHttpClient http;
    private final long minRequestGapMs;
    /** null → disk önbelleği kapalı. */
    private final Path cacheDir;

    private final AtomicInteger fetchOk     = new AtomicInteger();
    private final AtomicInteger fetchRetry  = new AtomicInteger();
    private final AtomicInteger fetchFailed = new AtomicInteger();
    private final AtomicInteger cacheHit    = new AtomicInteger();

    private final Object rateLock = new Object();
    private long nextAllowedRequestAt = 0L;

    /**
     * @param maxConcurrent   eşzamanlı istek (thread) sayısı — havuz buna göre boyutlanır
     * @param minRequestGapMs iki istek arasındaki en küçük global boşluk, ms
     */
    public MackolikHttpFetcher(int maxConcurrent, long minRequestGapMs) {
        this.minRequestGapMs = Math.max(0, minRequestGapMs);
        this.cacheDir = resolveCacheDir();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConcurrent * 2);
        // Tüm istekler aynı host'a gidiyor → per-route limit thread sayısından KÜÇÜK
        // olmamalı, aksi halde thread'ler bağlantı bekler ve connectionRequest timeout alır.
        cm.setDefaultMaxPerRoute(maxConcurrent * 2);
        // Havuzdaki bağlantı 2sn'den uzun boştaysa kullanmadan önce doğrula
        // (yarı-kapalı soket = read timeout).
        cm.setValidateAfterInactivity(2_000);
        cm.setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(SOCKET_TIMEOUT_MS)
                .setSoKeepAlive(true)
                .setTcpNoDelay(true)
                .build());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONN_REQUEST_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .setCookieSpec(CookieSpecs.STANDARD)
                .setRedirectsEnabled(true)
                .build();

        this.http = HttpClients.custom()
                .setConnectionManager(cm)
                .setConnectionManagerShared(false)
                .setDefaultRequestConfig(requestConfig)
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                .evictExpiredConnections()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .disableAutomaticRetries()   // retry'ı biz yönetiyoruz
                .build();
    }

    /**
     * Sayfayı indirir ve charset'i HTML meta etiketinden tespit ederek parse eder
     * (Türkçe/özel karakterli takım adları bozulmaz).
     *
     * @return parse edilmiş Document; tüm denemeler başarısızsa <b>null</b>
     */
    public Document fetchDocument(String url) {
        return fetchDocument(url, false);
    }

    /**
     * @param cacheable sayfa DEĞİŞMEZ mi (geçmiş sezon)? true ise disk önbelleği
     *                  kullanılır. Güncel sezon için daima false verilmelidir.
     */
    public Document fetchDocument(String url, boolean cacheable) {
        byte[] body = fetchBytes(url, cacheable);
        if (body == null) return null;
        try {
            return Jsoup.parse(new ByteArrayInputStream(body), null, "https://arsiv.mackolik.com/");
        } catch (IOException e) {
            return null;
        }
    }

    /** Sayfayı String olarak döndürür; tüm denemeler başarısızsa null. */
    public String fetchHtml(String url) {
        byte[] body = fetchBytes(url, false);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    public byte[] fetchBytes(String url) {
        return fetchBytes(url, false);
    }

    /**
     * Timeout/geçici hatalarda üstel geri çekilmeyle yeniden dener.
     * Başarısızsa null döner — çağıran taraf bunu "veri yok" olarak ele alır.
     */
    public byte[] fetchBytes(String url, boolean cacheable) {
        boolean useCache = cacheable && cacheDir != null;
        Path cacheFile = useCache ? cachePath(url) : null;
        if (useCache) {
            byte[] cached = readCache(cacheFile);
            if (cached != null) {
                cacheHit.incrementAndGet();
                return cached;
            }
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpGet req = new HttpGet(url);
            req.addHeader("User-Agent", USER_AGENT);
            req.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            req.addHeader("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");
            req.addHeader("Connection", "keep-alive");

            try (CloseableHttpResponse resp = http.execute(req)) {
                int code = resp.getStatusLine().getStatusCode();
                HttpEntity entity = resp.getEntity();

                if (code == 200) {
                    byte[] body = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
                    fetchOk.incrementAndGet();
                    if (useCache) writeCache(cacheFile, body);
                    return body;
                }

                // KRİTİK: 200 olmayan cevaplarda gövdeyi tüket, yoksa bağlantı havuza
                // dönmez ve sonraki istekler "Read timed out" / pool timeout alır.
                EntityUtils.consumeQuietly(entity);

                if (!RETRYABLE_STATUS.contains(code)) {
                    return null;                       // 404 vb. → tekrar denemenin anlamı yok
                }
                cooldown();                            // 429/5xx → sunucu yorgun, herkes beklesin
            } catch (IOException e) {
                // SocketTimeoutException, ConnectTimeoutException, NoHttpResponseException,
                // SocketException("Connection reset") ... hepsi buraya düşer.
                req.abort();
                cooldown();                            // timeout burst'ü → herkes beklesin
            } catch (RuntimeException e) {
                req.abort();
                return null;
            }

            if (attempt < MAX_ATTEMPTS) {
                fetchRetry.incrementAndGet();
                sleepBackoff(attempt);
            }
        }
        fetchFailed.incrementAndGet();
        return null;
    }

    /** Üstel geri çekilme + jitter (sunucuyu daha da boğmamak için). */
    private static void sleepBackoff(int attempt) {
        long delay = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
        delay += ThreadLocalRandom.current().nextLong(200, 600);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void throttle() {
        long waitMs;
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedRequestAt);
            waitMs = slot - now;
            nextAllowedRequestAt = slot + minRequestGapMs;
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Geçici hata sonrası ortak fren: bir sonraki isteğin (hangi thread'den
     * gelirse gelsin) en erken COOLDOWN_ON_FAILURE_MS sonra çıkmasını sağlar.
     */
    private void cooldown() {
        synchronized (rateLock) {
            long until = System.currentTimeMillis() + COOLDOWN_ON_FAILURE_MS;
            if (nextAllowedRequestAt < until) nextAllowedRequestAt = until;
        }
    }

    // ─── DİSK ÖNBELLEĞİ ─────────────────────────────────────────────────────

    /** {@code MACKOLIK_CACHE_DIR} / {@code -Dmackolik.cache.dir}; yoksa önbellek kapalı. */
    private static Path resolveCacheDir() {
        String dir = System.getProperty("mackolik.cache.dir");
        if (dir == null || dir.trim().isEmpty()) dir = System.getenv("MACKOLIK_CACHE_DIR");
        if (dir == null || dir.trim().isEmpty()) return null;
        try {
            Path p = Paths.get(dir.trim());
            Files.createDirectories(p);
            System.out.println("💾 Geçmiş sezon önbelleği: " + p.toAbsolutePath());
            return p;
        } catch (IOException | RuntimeException e) {
            System.err.println("⚠️ Önbellek dizini açılamadı (" + dir + "): " + e.getMessage());
            return null;   // önbellek olmadan da çalış
        }
    }

    private Path cachePath(String url) {
        String hex = sha256Hex(url);
        return cacheDir.resolve(hex.substring(0, 2)).resolve(hex + ".gz");
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }

    /** @return önbellekteki gövde; yoksa, bayatsa ya da okunamazsa null. */
    private static byte[] readCache(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            if (System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > CACHE_TTL_MS) {
                return null;
            }
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            return null;   // bozuk girdi → ağdan çek
        }
    }

    /**
     * Gövdeyi gzip'leyip diske yazar. Yalnızca gerçek fikstür sayfaları yazılır;
     * yazım atomiktir (geçici dosya + move), böylece paralel thread'ler yarım
     * dosya okumaz.
     */
    private static void writeCache(Path file, byte[] body) {
        if (body == null || body.length == 0) return;
        if (!new String(body, StandardCharsets.ISO_8859_1).contains(CACHEABLE_MARKER)) return;
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(body);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Önbellek yazılamazsa analiz akışı bozulmasın.
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }

    public int okCount()     { return fetchOk.get(); }
    public int retryCount()  { return fetchRetry.get(); }
    public int failedCount() { return fetchFailed.get(); }
    public int cacheHitCount() { return cacheHit.get(); }

    public String statsLine() {
        String base = String.format("%d ok / %d retry / %d başarısız",
                fetchOk.get(), fetchRetry.get(), fetchFailed.get());
        return cacheDir == null ? base : base + " / " + cacheHit.get() + " önbellek";
    }

    @Override
    public void close() {
        try {
            http.close();
        } catch (IOException ignored) {
        }
    }
}
