package analyzer.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tor üzərindən çıxış (istəyə bağlı) — mackolik arxivinin IP limitini aşmaq üçün.
 * ────────────────────────────────────────────────────────────────────────────
 * NİYƏ: arsiv2.mackolik.com bir IP-yə ~40 sorğu verir, sonra uzun müddət
 * "HTTP 429" qaytarır (arsiv.mackolik.com isə ~10 sorğu, bulud IP-lərinə 403).
 * Analizlərə gündə minlərlə sorğu lazımdır. Hər Tor SOCKS portu ayrıca dövrə
 * (= ayrıca çıxış IP) işlədir; limit dolanda NEWNYM ilə bütün portlar yeni
 * dövrəyə (yeni IP-yə) keçir. Runner-də ölçülüb: 8 port × 40 = 320 sorğu/dövr.
 *
 * Konfiqurasiya (mühit dəyişəni və ya -D sistem xassəsi):
 *   MACKOLIK_TOR_PORTS     / mackolik.tor.ports     "9060-9075" və ya "9050,9051"
 *   MACKOLIK_TOR_CONTROL   / mackolik.tor.control   ControlPort, məs. "9151" (NEWNYM üçün)
 *   MACKOLIK_TOR_PASSWORD  / mackolik.tor.password  ControlPort parolu (yoxdursa boş)
 * Portlar verilməyibsə Tor işlənmir — birbaşa bağlantı (əvvəlki davranış).
 * ────────────────────────────────────────────────────────────────────────────
 */
public final class TorEgress {

    /** Tor NEWNYM-i ən tez 10 saniyədən bir qəbul edir. */
    private static final long NEWNYM_MIN_GAP_MS = 10_500;

    private static final List<Integer> PORTS = parsePorts(cfg("mackolik.tor.ports", "MACKOLIK_TOR_PORTS"));
    private static final int CONTROL_PORT = parseInt(cfg("mackolik.tor.control", "MACKOLIK_TOR_CONTROL"), -1);
    private static final String PASSWORD = nz(cfg("mackolik.tor.password", "MACKOLIK_TOR_PASSWORD"));

    private static final Object LOCK = new Object();
    private static volatile int epoch = 0;
    private static long lastNewnymAt = 0L;
    private static volatile boolean announced = false;

    private TorEgress() {}

    public static boolean enabled()        { return !PORTS.isEmpty(); }
    public static List<Integer> ports()    { return PORTS; }
    public static boolean canRenew()       { return CONTROL_PORT > 0; }
    /** Hər NEWNYM-də artır; "limit doldu" işarələri yalnız öz epoch-unda keçərlidir. */
    public static int epoch()              { return epoch; }

    public static void announce() {
        if (!enabled() || announced) return;
        announced = true;
        System.out.println("🧅 Tor çıxışı aktiv: " + PORTS.size() + " SOCKS portu " + PORTS
                + (canRenew() ? " | NEWNYM: ControlPort " + CONTROL_PORT : " | ControlPort yoxdur (IP dəyişmir)"));
    }

    /**
     * Yeni Tor kimliyi (bütün portlar üçün yeni dövrələr) istəyir.
     *
     * @param seenEpoch çağıranın gördüyü epoch — artıq başqası yeniləyibsə heç nə etmir
     * @param wait      true: 10 san. limitinə görə gözləmək lazımdırsa gözlə;
     *                  false: indi mümkün deyilsə dərhal qayıt
     * @return yeniləmə baş verdisə (bu çağırışla və ya artıq başqası etmişdisə) true
     */
    public static boolean renew(int seenEpoch, boolean wait) {
        if (!canRenew()) return false;
        synchronized (LOCK) {
            if (epoch != seenEpoch) return true;                 // başqa thread artıq yeniləyib
            long since = System.currentTimeMillis() - lastNewnymAt;
            if (since < NEWNYM_MIN_GAP_MS) {
                if (!wait) return false;
                sleep(NEWNYM_MIN_GAP_MS - since);
            }
            boolean ok = sendNewnym();
            lastNewnymAt = System.currentTimeMillis();
            if (ok) {
                epoch++;
                sleep(1_500);                                    // yeni dövrələr qurulsun
            }
            return ok;
        }
    }

    private static boolean sendNewnym() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", CONTROL_PORT), 5_000);
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            out.write(("AUTHENTICATE \"" + PASSWORD.replace("\"", "\\\"") + "\"\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String auth = in.readLine();
            if (auth == null || !auth.startsWith("250")) {
                System.err.println("⚠️ Tor ControlPort AUTHENTICATE alınmadı: " + auth);
                return false;
            }
            out.write("SIGNAL NEWNYM\r\nQUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String r = in.readLine();
            return r != null && r.startsWith("250");
        } catch (Exception e) {
            System.err.println("⚠️ Tor NEWNYM alınmadı: " + e.getMessage());
            return false;
        }
    }

    // ── konfiqurasiya köməkçiləri ───────────────────────────────────────────
    private static String cfg(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null ? null : v.trim();
    }

    static List<Integer> parsePorts(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0) {
                int a = parseInt(part.substring(0, dash), -1), b = parseInt(part.substring(dash + 1), -1);
                if (a > 0 && b >= a && b - a < 256) for (int p = a; p <= b; p++) out.add(p);
            } else {
                int p = parseInt(part, -1);
                if (p > 0) out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void sleep(long ms) {
        try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
