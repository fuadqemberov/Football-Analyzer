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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 */
public final class MackolikHttpFetcher implements AutoCloseable {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS      = 15_000;
    private static final int SOCKET_TIMEOUT_MS       = 30_000;
    private static final int CONN_REQUEST_TIMEOUT_MS = 20_000;

    /** Bir URL için toplam deneme sayısı (1 ilk deneme + 4 retry). */
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BASE_DELAY_MS = 700;
    private static final long RETRY_MAX_DELAY_MS  = 10_000;

    /** Geçici sayılan ve yeniden denenen HTTP durum kodları. */
    private static final Set<Integer> RETRYABLE_STATUS =
            new HashSet<>(Arrays.asList(408, 425, 429, 500, 502, 503, 504));

    private final CloseableHttpClient http;
    private final long minRequestGapMs;

    private final AtomicInteger fetchOk     = new AtomicInteger();
    private final AtomicInteger fetchRetry  = new AtomicInteger();
    private final AtomicInteger fetchFailed = new AtomicInteger();

    private final Object rateLock = new Object();
    private long nextAllowedRequestAt = 0L;

    /**
     * @param maxConcurrent   eşzamanlı istek (thread) sayısı — havuz buna göre boyutlanır
     * @param minRequestGapMs iki istek arasındaki en küçük global boşluk, ms
     */
    public MackolikHttpFetcher(int maxConcurrent, long minRequestGapMs) {
        this.minRequestGapMs = Math.max(0, minRequestGapMs);

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
        byte[] body = fetchBytes(url);
        if (body == null) return null;
        try {
            return Jsoup.parse(new ByteArrayInputStream(body), null, "https://arsiv.mackolik.com/");
        } catch (IOException e) {
            return null;
        }
    }

    /** Sayfayı String olarak döndürür; tüm denemeler başarısızsa null. */
    public String fetchHtml(String url) {
        byte[] body = fetchBytes(url);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Timeout/geçici hatalarda üstel geri çekilmeyle yeniden dener.
     * Başarısızsa null döner — çağıran taraf bunu "veri yok" olarak ele alır.
     */
    public byte[] fetchBytes(String url) {
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
                    return body;
                }

                // KRİTİK: 200 olmayan cevaplarda gövdeyi tüket, yoksa bağlantı havuza
                // dönmez ve sonraki istekler "Read timed out" / pool timeout alır.
                EntityUtils.consumeQuietly(entity);

                if (!RETRYABLE_STATUS.contains(code)) {
                    return null;                       // 404 vb. → tekrar denemenin anlamı yok
                }
            } catch (IOException e) {
                // SocketTimeoutException, ConnectTimeoutException, NoHttpResponseException,
                // SocketException("Connection reset") ... hepsi buraya düşer.
                req.abort();
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
        if (minRequestGapMs <= 0) return;
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

    public int okCount()     { return fetchOk.get(); }
    public int retryCount()  { return fetchRetry.get(); }
    public int failedCount() { return fetchFailed.get(); }

    public String statsLine() {
        return String.format("%d ok / %d retry / %d başarısız",
                fetchOk.get(), fetchRetry.get(), fetchFailed.get());
    }

    @Override
    public void close() {
        try {
            http.close();
        } catch (IOException ignored) {
        }
    }
}
