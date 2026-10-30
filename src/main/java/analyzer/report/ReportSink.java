package analyzer.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analiz nəticələrini ortaq JSON-Lines faylına yazır (hər sətir bir obyekt).
 *
 * <p>Fayl {@code ANALYZER_REPORT_FILE} mühit dəyişəni və ya
 * {@code -Danalyzer.report.file} ilə verilir. Verilməyibsə heç nə yazılmır —
 * analizlər əvvəlki kimi yalnız konsola çap edir (lokal işləmə dəyişmir).
 *
 * <p>Bir neçə analiz ardıcıl işləyir və hamısı EYNİ fayla əlavə edir; sonda
 * {@link DailyReport} faylı oxuyub tək hesabat qurur.
 */
public final class ReportSink {

    private static final Path FILE = resolve();
    private static final Object LOCK = new Object();
    private static final Set<String> FIXTURES_SEEN = ConcurrentHashMap.newKeySet();

    private ReportSink() {}

    public static boolean enabled() { return FILE != null; }

    private static Path resolve() {
        String p = System.getProperty("analyzer.report.file");
        if (p == null || p.isBlank()) p = System.getenv("ANALYZER_REPORT_FILE");
        if (p == null || p.isBlank()) return null;
        try {
            Path path = Paths.get(p.trim()).toAbsolutePath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            return path;
        } catch (IOException | RuntimeException e) {
            System.err.println("⚠️ Hesabat faylı açılmadı (" + p + "): " + e.getMessage());
            return null;
        }
    }

    public static void emit(Finding f) {
        if (FILE == null || f == null) return;
        append(Json.write(f.toMap()));
    }

    /**
     * Günün proqramındakı bir oyun (mackolik livedata). Hesabat analiz nəticələrini
     * bu siyahı ilə oyunlara bağlayır (komanda ID-si → oyun).
     */
    public static void fixture(int matchId, int homeId, String home, int awayId, String away,
                               String league, String date, String kickoff) {
        if (FILE == null) return;
        if (!FIXTURES_SEEN.add(homeId + "-" + awayId)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "fixture");
        m.put("source", "mackolik");
        m.put("matchId", matchId);
        m.put("homeId", homeId);
        m.put("home", home);
        m.put("awayId", awayId);
        m.put("away", away);
        m.put("league", league);
        m.put("matchDate", Finding.isoDate(date));
        m.put("kickoff", kickoff);
        append(Json.write(m));
    }

    private static void append(String line) {
        synchronized (LOCK) {
            try {
                Files.writeString(FILE, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("⚠️ Hesabat sətri yazılmadı: " + e.getMessage());
            }
        }
    }
}
