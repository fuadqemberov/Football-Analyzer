package analyzer.util;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Mackolik arxivi üçün TIMEOUT- və LİMİT-DAYANIKLI ortaq HTTP qatı.
 * Bütün mackolik analizləri bunu işlədir (birbaşa və ya {@link #shared()} ilə).
 *
 * <h3>Timeout düzəlişləri (əvvəldən)</h3>
 * Uzun timeout-lar, backoff + jitter ilə retry, 200 olmayan cavabda gövdənin
 * tükədilməsi, ölü bağlantıların təmizlənməsi, qlobal sorğu aralığı (throttle).
 *
 * <h3>2026-10-19-dan sonrakı bloklar</h3>
 * <ul>
 *   <li>arsiv.mackolik.com bulud (GitHub Actions / Azure) IP-lərinə 403 verir →
 *       {@link MackolikHost} ilə arsiv2.mackolik.com-a keçilir.</li>
 *   <li>arsiv2 bir IP-yə ~40 sorğu verir, sonra uzun müddət 429 qaytarır →
 *       {@link TorEgress} aktivdirsə hər Tor portu ayrıca "zolaq"dır (ayrıca çıxış IP);
 *       429/403 alan zolaq həmin epoch üçün bağlanır, sorğu başqa zolaqla təkrarlanır,
 *       zolaqların yarısı dolanda NEWNYM ilə hamısı yeni IP alır.</li>
 *   <li>Tor yoxdursa (lokal işləmə): 403/429-da növbəti hosta keçilir.</li>
 * </ul>
 *
 * <h3>Disk önbelleği</h3>
 * {@code MACKOLIK_CACHE_DIR} / {@code -Dmackolik.cache.dir} verilibsə DƏYİŞMƏZ
 * (keçmiş sezon) cavablar gzip ilə diskə yazılır; açar kanonik host üzrədir.
 */
public final class MackolikHttpFetcher implements AutoCloseable {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS      = 15_000;
    private static final int SOCKET_TIMEOUT_MS       = 45_000;
    private static final int CONN_REQUEST_TIMEOUT_MS = 60_000;

    /** Keçici xətalar (timeout, 5xx) üçün bir URL-ə ən çox cəhd sayı. */
    private static final int MAX_ATTEMPTS = 8;
    private static final long RETRY_BASE_DELAY_MS = 700;
    private static final long RETRY_MAX_DELAY_MS  = 20_000;

    /** Tor rejimində bir URL üçün ən çox zolaq dəyişmə (429/403) sayı. */
    private static final int MAX_LANE_SWITCHES = 60;
    /** Bu qədər zolaq dəyişmədən sonra növbəti hosta da keçilir. */
    private static final int SWITCHES_PER_HOST = 12;
    /** ControlPort yoxdursa limitə düşən zolaq bu qədər müddət istifadə olunmur. */
    private static final long LANE_BLOCK_MS = TimeUnit.MINUTES.toMillis(15);

    private static final long COOLDOWN_ON_FAILURE_MS = 2_500;

    /** Keçici sayılan və təkrar yoxlanan HTTP kodları (429 ayrıca idarə olunur). */
    private static final Set<Integer> RETRYABLE_STATUS =
            new HashSet<>(Arrays.asList(408, 425, 500, 502, 503, 504));

    /** Önbelleyə yalnız həqiqi data yazılır (fikstür cədvəli və ya FixtureHandler JS massivi). */
    private static final String CACHEABLE_MARKER = "tblFixture";

    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(30);

    // ── Zolaq: bir çıxış yolu (birbaşa və ya bir Tor portu) ────────────────
    private static final class Lane {
        final String name;
        final CloseableHttpClient http;
        final PoolingHttpClientConnectionManager cm;
        volatile int limitedEpoch = -1;
        volatile long limitedAt = 0L;
        final AtomicInteger ok = new AtomicInteger();

        Lane(String name, CloseableHttpClient http, PoolingHttpClientConnectionManager cm) {
            this.name = name; this.http = http; this.cm = cm;
        }

        boolean limited() {
            return limitedEpoch == TorEgress.epoch()
                    && System.currentTimeMillis() - limitedAt < LANE_BLOCK_MS;
        }
    }

    private final List<Lane> lanes = new ArrayList<>();
    private final boolean tor;
    private final AtomicInteger roundRobin = new AtomicInteger();

    private final long minRequestGapMs;
    /** null → disk önbelleği kapalı. */
    private final Path cacheDir;

    private final AtomicInteger fetchOk     = new AtomicInteger();
    private final AtomicInteger fetchRetry  = new AtomicInteger();
    private final AtomicInteger fetchFailed = new AtomicInteger();
    private final AtomicInteger cacheHit    = new AtomicInteger();
    private final AtomicInteger laneSwitch  = new AtomicInteger();
    /** Təkrar yoxlanmayan (qəti) HTTP kodları → say. */
    private final ConcurrentHashMap<Integer, AtomicInteger> permanentStatus = new ConcurrentHashMap<>();
    /** Keçici xəta növləri → say (hər növün ilk görülüşü loglanır). */
    private final ConcurrentHashMap<String, AtomicInteger> transientErrors = new ConcurrentHashMap<>();

    private final Object rateLock = new Object();
    private long nextAllowedRequestAt = 0L;

    private static volatile MackolikHttpFetcher shared;

    /**
     * @param maxConcurrent   eyni anda sorğu (thread) sayı — hovuz buna görə ölçülür
     * @param minRequestGapMs iki sorğu arasında ən kiçik qlobal boşluq, ms
     */
    public MackolikHttpFetcher(int maxConcurrent, long minRequestGapMs) {
        this.minRequestGapMs = Math.max(0, minRequestGapMs);
        this.cacheDir = resolveCacheDir();
        this.tor = TorEgress.enabled();
        int conc = Math.max(1, maxConcurrent);

        if (tor) {
            TorEgress.announce();
            for (int port : TorEgress.ports()) lanes.add(buildLane("tor:" + port, conc, port));
        } else {
            lanes.add(buildLane("direct", conc, -1));
        }
    }

    /**
     * JVM boyu ortaq nümunə — öz HTTP client-i olan köhnə analizlər (SezonDenklemi,
     * LeagueSeasonFetcher, B2B, Zincir ...) eyni host/Tor/önbellek məntiqini işlətsin.
     */
    public static MackolikHttpFetcher shared() {
        MackolikHttpFetcher s = shared;
        if (s == null) {
            synchronized (MackolikHttpFetcher.class) {
                if (shared == null) {
                    shared = new MackolikHttpFetcher(12, 40);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        MackolikHttpFetcher f = shared;
                        if (f != null && f.fetchOk.get() + f.fetchFailed.get() + f.cacheHit.get() > 0) {
                            System.out.println("🌐 Mackolik istek özeti: " + f.statsLine());
                        }
                    }));
                }
                s = shared;
            }
        }
        return s;
    }

    private Lane buildLane(String name, int conc, int socksPort) {
        SSLConnectionSocketFactory ssl;
        PlainConnectionSocketFactory plain;
        if (socksPort > 0) {
            final Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            plain = new PlainConnectionSocketFactory() {
                @Override public Socket createSocket(HttpContext ctx) { return new Socket(proxy); }
            };
            ssl = new SSLConnectionSocketFactory(SSLContexts.createSystemDefault()) {
                @Override public Socket createSocket(HttpContext ctx) { return new Socket(proxy); }
            };
        } else {
            plain = PlainConnectionSocketFactory.getSocketFactory();
            ssl = SSLConnectionSocketFactory.getSocketFactory();
        }
        Registry<ConnectionSocketFactory> reg = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", plain).register("https", ssl).build();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(reg);
        cm.setMaxTotal(conc * 2);
        cm.setDefaultMaxPerRoute(conc * 2);
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

        HttpClientBuilder b = HttpClients.custom()
                .setConnectionManager(cm)
                .setConnectionManagerShared(false)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .disableAutomaticRetries();          // retry-ı özümüz idarə edirik
        if (socksPort > 0) {
            // Bağlantı təkrar istifadə olunmasın: NEWNYM-dən sonra hər yeni sorğu
            // yeni dövrəyə (yeni çıxış IP-sinə) düşsün.
            b.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE);
        } else {
            b.setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE);
        }
        return new Lane(name, b.build(), cm);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════════════

    /** Səhifəni yükləyir və charset-i HTML meta-dan təyin edərək parse edir. Uğursuzsa null. */
    public Document fetchDocument(String url) {
        return fetchDocument(url, false);
    }

    /**
     * @param cacheable səhifə DƏYİŞMƏZdirmi (keçmiş sezon)? true isə disk önbelleği işlənir.
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

    /** Səhifəni UTF-8 String kimi qaytarır; uğursuzsa null. */
    public String fetchHtml(String url) {
        return fetchText(url, false);
    }

    /** UTF-8 mətn (HTML və ya FixtureHandler JS massivi); uğursuzsa null. */
    public String fetchText(String url, boolean cacheable) {
        byte[] body = fetchBytes(url, cacheable);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    public byte[] fetchBytes(String url) {
        return fetchBytes(url, false);
    }

    /** Uğursuzsa null — çağıran tərəf bunu "data yoxdur" kimi qəbul edir. */
    public byte[] fetchBytes(String url, boolean cacheable) {
        boolean useCache = cacheable && cacheDir != null;
        Path cacheFile = useCache ? cachePath(MackolikHost.canonical(url)) : null;
        if (useCache) {
            byte[] cached = readCache(cacheFile);
            if (cached != null) {
                cacheHit.incrementAndGet();
                return cached;
            }
        }

        List<String> hosts = MackolikHost.candidates(url);
        int hostIdx = 0;
        int transientTries = 0;
        int switches = 0;

        while (true) {
            String target = hosts.get(hostIdx % hosts.size());
            Lane lane = pickLane();
            int epochAtStart = TorEgress.epoch();
            throttle();

            HttpGet req = new HttpGet(target);
            req.addHeader("User-Agent", USER_AGENT);
            req.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            req.addHeader("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");

            int code;
            try (CloseableHttpResponse resp = lane.http.execute(req)) {
                code = resp.getStatusLine().getStatusCode();
                HttpEntity entity = resp.getEntity();
                if (code == 200) {
                    byte[] body = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
                    fetchOk.incrementAndGet();
                    lane.ok.incrementAndGet();
                    if (useCache) writeCache(cacheFile, body);
                    return body;
                }
                // 200 olmayan cavabda gövdəni tükət — bağlantı hovuza qayıtsın.
                EntityUtils.consumeQuietly(entity);
            } catch (IOException e) {
                req.abort();
                noteTransient(e.getClass().getSimpleName() + ": " + e.getMessage(), target);
                if (++transientTries >= MAX_ATTEMPTS) return fail(null, target);
                fetchRetry.incrementAndGet();
                cooldown();
                if (tor) continue;                    // Tor-da başqa zolaq dərhal sınansın
                sleepBackoff(transientTries);
                continue;
            } catch (RuntimeException e) {
                req.abort();
                noteTransient(e.getClass().getSimpleName() + ": " + e.getMessage(), target);
                return fail(null, target);
            }

            // ── IP limiti / bloku ──
            if (code == 429 || code == 403) {
                if (tor) {
                    markLimited(lane, epochAtStart, code);
                    switches++;
                    laneSwitch.incrementAndGet();
                    if (switches >= MAX_LANE_SWITCHES) return fail(code, target);
                    if (switches % SWITCHES_PER_HOST == 0) hostIdx++;  // bu host inadkar — o birini sına
                    continue;
                }
                // Birbaşa rejim: IP-ni dəyişə bilmirik → növbəti host
                MackolikHost.markBlocked(target, code);
                if (++hostIdx >= hosts.size()) return fail(code, target);
                continue;
            }

            if (RETRYABLE_STATUS.contains(code)) {
                noteTransient("HTTP " + code, target);
                if (++transientTries >= MAX_ATTEMPTS) return fail(code, target);
                fetchRetry.incrementAndGet();
                cooldown();
                sleepBackoff(transientTries);
                continue;
            }

            return fail(code, target);                // 404 və s. — qəti
        }
    }

    private byte[] fail(Integer code, String url) {
        fetchFailed.incrementAndGet();
        if (code != null && permanentStatus.computeIfAbsent(code, k -> new AtomicInteger())
                                           .getAndIncrement() == 0) {
            System.err.println("⚠️ HTTP " + code + " (vaz keçildi): " + url);
        }
        return null;
    }

    // ── Zolaq seçimi / limit ────────────────────────────────────────────────
    private Lane pickLane() {
        if (!tor) return lanes.get(0);
        while (true) {
            List<Lane> free = new ArrayList<>(lanes.size());
            for (Lane l : lanes) if (!l.limited()) free.add(l);

            // Zolaqların yarısı dolubsa yeni IP-lər istə (mümkün deyilsə və hələ boş
            // zolaq varsa gözləmədən davam et; heç biri yoxdursa gözlə).
            if (free.size() * 2 <= lanes.size() && TorEgress.canRenew()) {
                if (TorEgress.renew(TorEgress.epoch(), free.isEmpty())) continue;
            }
            if (!free.isEmpty()) {
                return free.get(Math.floorMod(roundRobin.getAndIncrement(), free.size()));
            }
            if (!TorEgress.canRenew()) {
                // ControlPort yoxdur — ən tez açılacaq zolağı gözlə
                long wait = LANE_BLOCK_MS;
                for (Lane l : lanes) wait = Math.min(wait, LANE_BLOCK_MS - (System.currentTimeMillis() - l.limitedAt));
                sleepQuietly(Math.max(1_000, wait));
            }
        }
    }

    private void markLimited(Lane lane, int epochAtStart, int code) {
        if (epochAtStart != TorEgress.epoch()) return;   // köhnə dövrənin cavabıdır
        if (lane.limitedEpoch != epochAtStart) {
            lane.limitedAt = System.currentTimeMillis();
            lane.limitedEpoch = epochAtStart;
        }
        noteTransient("HTTP " + code + " (Tor çıxışı dəyişdirilir)", null);
    }

    private void noteTransient(String what, String url) {
        String key = what == null ? "?" : what.replaceAll("\\d+ ?ms", "N ms");
        if (key.length() > 120) key = key.substring(0, 120);
        if (transientErrors.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement() == 0) {
            System.err.println("⚠️ Keçici xəta: " + what + (url == null ? "" : " ← " + url));
        }
    }

    private static void sleepBackoff(int attempt) {
        long delay = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (1L << Math.min(10, attempt - 1)));
        delay += ThreadLocalRandom.current().nextLong(200, 600);
        sleepQuietly(delay);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
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
        if (waitMs > 0) sleepQuietly(waitMs);
    }

    /** Keçici xətadan sonra ortaq əyləc: növbəti sorğu ən tez COOLDOWN sonra çıxsın. */
    private void cooldown() {
        if (tor) return;          // Tor-da hər zolağın IP-si ayrıdır — hamını saxlamağa ehtiyac yoxdur
        synchronized (rateLock) {
            long until = System.currentTimeMillis() + COOLDOWN_ON_FAILURE_MS;
            if (nextAllowedRequestAt < until) nextAllowedRequestAt = until;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Disk önbelleği
    // ═══════════════════════════════════════════════════════════════════════

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
            return null;
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
            return null;
        }
    }

    /** Cavab həqiqi datadırmı? (fikstür cədvəli və ya boş olmayan FixtureHandler JS massivi) */
    static boolean looksCacheable(byte[] body) {
        if (body == null || body.length == 0) return false;
        String s = new String(body, StandardCharsets.ISO_8859_1);
        if (s.contains(CACHEABLE_MARKER)) return true;
        String t = s.trim();
        return t.length() > 4 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']';
    }

    private static void writeCache(Path file, byte[] body) {
        if (!looksCacheable(body)) return;
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(body);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Statistika
    // ═══════════════════════════════════════════════════════════════════════

    public int okCount()       { return fetchOk.get(); }
    public int retryCount()    { return fetchRetry.get(); }
    public int failedCount()   { return fetchFailed.get(); }
    public int cacheHitCount() { return cacheHit.get(); }

    public String statsLine() {
        StringBuilder sb = new StringBuilder(String.format("%d ok / %d retry / %d başarısız",
                fetchOk.get(), fetchRetry.get(), fetchFailed.get()));
        if (cacheDir != null) sb.append(" / ").append(cacheHit.get()).append(" önbellek");
        sb.append(" [host: ").append(MackolikHost.describe());
        if (tor) sb.append(" | Tor: ").append(lanes.size()).append(" zolaq, ")
                   .append(laneSwitch.get()).append(" IP dəyişmə, NEWNYM×").append(TorEgress.epoch());
        sb.append(']');
        if (!transientErrors.isEmpty()) {
            sb.append(" {keçici: ");
            transientErrors.forEach((k, n) -> sb.append(k).append('×').append(n.get()).append("; "));
            sb.setLength(sb.length() - 2);
            sb.append('}');
        }
        if (!permanentStatus.isEmpty()) {
            sb.append(" (");
            permanentStatus.forEach((code, n) -> sb.append("HTTP ").append(code).append('×').append(n.get()).append(' '));
            sb.setLength(sb.length() - 1);
            sb.append(')');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (this == shared) return;          // ortaq nümunə JVM sonuna qədər yaşayır
        for (Lane l : lanes) {
            try { l.http.close(); } catch (IOException ignored) { }
        }
    }
}
