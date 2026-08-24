package analyzer.allinonetactics;

import analyzer.mackolik.dongu.HaftaDonguAnalyzer;
import analyzer.mackolik.patternfinder.OnlyLeagueVirtualThreadedAnalyzer;
import analyzer.mackolik.temas.TemasTakimiAnalyzer;
import analyzer.mackolik.triplepattern.HttpTeamNamePatternAnalyzer;
import analyzer.mackolik.triplepattern.HttpTripleMatchPatternAnalyzer;
import analyzer.mackolik.triplepattern.SezonDenklemiAnalyzer;
import analyzer.mackolik.triplepattern.ZincirSurprizAnalyzer;
import analyzer.mackolik.xthmatch.SeasonXthOfficialMatchAnalyzer;
import analyzer.oranavcisi.OranAvcisiAnalyzer;
import analyzer.util.MackolikHttpFetcher;
import analyzer.util.TeamIdsFetcher;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * HAMISI BİR YERDƏ — bir A–B oyunu üçün repodakı bütün Mackolik taktikalarını
 * işə salır, hansının siqnal verdiyini bir hesabatda toplayır və sonda hər şeyi
 * mətn faylına yazır.
 *
 * <p>Hər analizator öz {@code main}-i ilə çağırılmır. Səbəb: onların bir neçəsi
 * sonda {@code System.exit(0)} edir (bütün toplayıcını öldürərdi), hamısı stdout-a
 * yazır və hamısı günün BÜTÜN oyunlarını tarayır. Bunun əvəzinə hər analizatora
 * mövcud tapşırıq sinfini olduğu kimi çağıran bir sətirlik ictimai giriş nöqtəsi
 * ({@code analyzeSingleTeam} / {@code analyzeSinglePair}) əlavə edilib — məntiq
 * dəyişmir, sadəcə ExecutorService və qlobal fikstür siyahısı yan keçilir.
 *
 * <p><b>İki cür taktika var:</b>
 * <ul>
 *   <li><b>Oyun taktikaları</b> — cütlə işləyir, bir dəfə çağırılır
 *       (Zəncir, Sezon Denklemi, Oran Avcısı)</li>
 *   <li><b>Komanda taktikaları</b> — tək komanda ilə işləyir, ona görə HƏM ev
 *       sahibi, HƏM qonaq üçün ayrıca çağırılır (Temas, Hafta Döngü, X. Rəsmi Maç,
 *       Yalnız Lig, Komanda Adı Qəlibi, Üçlü Qəlib)</li>
 * </ul>
 *
 * <p>Taktikalar paralel işləyir və biri digərini bloklamır: bir analizator xəta
 * versə, o sətir "XƏTA" kimi yazılır, qalanları davam edir.
 *
 * <p>İşlətmə:
 * <pre>
 *   java ... AllInOneTactics 4:451             → Trabzonspor vs Başakşehir (evId:depId)
 *   java ... AllInOneTactics 4:451 15:7        → bir neçə oyun
 *   java ... AllInOneTactics --bugun           → günün BÜTÜN başlamamış oyunları
 *   java ... AllInOneTactics --bugun --liqa="süper lig"
 *   java ... AllInOneTactics 4:451 --cixis=C:/tmp/hesabat.txt
 * </pre>
 */
public class AllInOneTactics {

    private static final int NUM_THREADS      = 10;
    private static final long MIN_REQUEST_GAP = 90L;
    /** Bir taktikanın bir oyun üçün icazəli maksimum vaxtı. */
    private static final int TACTIC_TIMEOUT_MINUTES = 6;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // ═══════════════════════════════════════════════════════════════════════
    //  Model
    // ═══════════════════════════════════════════════════════════════════════

    /** Analiz ediləcək oyun. */
    static final class Fixture {
        final int homeId;
        final String homeName;
        final int awayId;
        final String awayName;
        final String leagueName;
        final String kickoff;

        Fixture(int homeId, String homeName, int awayId, String awayName,
                String leagueName, String kickoff) {
            this.homeId     = homeId;
            this.homeName   = homeName;
            this.awayId     = awayId;
            this.awayName   = awayName;
            this.leagueName = leagueName;
            this.kickoff    = kickoff;
        }

        String title() {
            return homeName + " – " + awayName;
        }
    }

    /** Bir taktikanın bir oyun üçün nəticəsi. */
    static final class TacticResult {
        final String tacticName;
        /** Komanda taktikalarında hansı komanda üçün işlədiyi; oyun taktikalarında boş. */
        final String scope;
        final String output;      // siqnal mətni; siqnal yoxdursa null
        final String error;       // xəta mesajı; xəta yoxdursa null
        final long millis;

        TacticResult(String tacticName, String scope, String output, String error, long millis) {
            this.tacticName = tacticName;
            this.scope      = scope;
            this.output     = output;
            this.error      = error;
            this.millis     = millis;
        }

        boolean matched() {
            return error == null && output != null && !output.isBlank();
        }

        String label() {
            return scope.isEmpty() ? tacticName : tacticName + " [" + scope + "]";
        }
    }

    /**
     * Bir taktikanın çağırılma qaydası. Analizatorların hər biri fərqli HTTP
     * qatından istifadə etdiyi üçün (biri {@link MackolikHttpFetcher}, digəri
     * Apache {@code CloseableHttpClient}) ikisi də ötürülür.
     */
    interface Tactic {
        String name();
        /** Komanda taktikası hər iki komanda üçün ayrıca çağırılır. */
        boolean perTeam();
        String run(Context ctx, Fixture fixture, int teamId);
    }

    /** Paylaşılan HTTP qatları — bütün taktikalar eyni bağlantı hovuzundan keçir. */
    static final class Context {
        final MackolikHttpFetcher fetcher;
        final CloseableHttpClient apache;

        Context(MackolikHttpFetcher fetcher, CloseableHttpClient apache) {
            this.fetcher = fetcher;
            this.apache  = apache;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Taktika kataloqu
    // ═══════════════════════════════════════════════════════════════════════

    private static Tactic pairTactic(String name, PairRunner runner) {
        return new Tactic() {
            @Override public String name() { return name; }
            @Override public boolean perTeam() { return false; }
            @Override public String run(Context ctx, Fixture fixture, int teamId) {
                return runner.run(ctx, fixture);
            }
        };
    }

    private static Tactic teamTactic(String name, TeamRunner runner) {
        return new Tactic() {
            @Override public String name() { return name; }
            @Override public boolean perTeam() { return true; }
            @Override public String run(Context ctx, Fixture fixture, int teamId) {
                return runner.run(ctx, teamId);
            }
        };
    }

    interface PairRunner { String run(Context ctx, Fixture fixture); }
    interface TeamRunner { String run(Context ctx, int teamId); }

    /** Repodakı bütün taktikalar. Yeni analizator əlavə etmək = bura bir sətir. */
    static List<Tactic> catalogue() {
        List<Tactic> tactics = new ArrayList<>();

        // ── Oyun (cüt) taktikaları
        tactics.add(pairTactic("SKOR ZİNCİRİ (sürpriz)",
                (ctx, f) -> ZincirSurprizAnalyzer.analyzeSinglePair(ctx.apache, f.homeId, f.awayId)));
        tactics.add(pairTactic("SEZON DENKLEMİ",
                (ctx, f) -> SezonDenklemiAnalyzer.analyzeSinglePair(ctx.apache, f.homeId, f.awayId)));
        tactics.add(pairTactic("ORAN AVCISI (MAÇ SONU 0)",
                (ctx, f) -> OranAvcisiAnalyzer.analyzeSinglePair(ctx.fetcher, f.homeId, f.awayId)));

        // ── Komanda taktikaları (hər iki komanda üçün ayrıca)
        tactics.add(teamTactic("TEMAS TAKIMI",
                (ctx, id) -> TemasTakimiAnalyzer.analyzeSingleTeam(ctx.fetcher, id)));
        tactics.add(teamTactic("HAFTA DÖNGÜSÜ",
                (ctx, id) -> HaftaDonguAnalyzer.analyzeSingleTeam(ctx.fetcher, id)));
        tactics.add(teamTactic("X. RƏSMİ MAÇ (İY/MS)",
                (ctx, id) -> SeasonXthOfficialMatchAnalyzer.analyzeSingleTeam(ctx.fetcher, id)));
        tactics.add(teamTactic("YALNIZ LİG QƏLİBİ",
                (ctx, id) -> OnlyLeagueVirtualThreadedAnalyzer.analyzeSingleTeam(ctx.apache, id)));
        tactics.add(teamTactic("KOMANDA ADI QƏLİBİ",
                (ctx, id) -> HttpTeamNamePatternAnalyzer.analyzeSingleTeam(ctx.apache, id)));
        tactics.add(teamTactic("ÜÇLÜ MAÇ QƏLİBİ",
                (ctx, id) -> HttpTripleMatchPatternAnalyzer.analyzeSingleTeam(ctx.apache, id)));

        return tactics;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Bir oyunun analizi
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Bir oyun üçün bütün taktikaları paralel işlədir.
     * Komanda taktikaları iki dəfə (ev + qonaq) çağırılır.
     */
    static List<TacticResult> analyzeFixture(Context ctx, Fixture fixture,
                                             List<Tactic> tactics, ExecutorService executor) {
        List<Future<TacticResult>> futures = new ArrayList<>();

        for (Tactic tactic : tactics) {
            if (tactic.perTeam()) {
                futures.add(executor.submit(job(ctx, fixture, tactic, fixture.homeId, fixture.homeName)));
                futures.add(executor.submit(job(ctx, fixture, tactic, fixture.awayId, fixture.awayName)));
            } else {
                futures.add(executor.submit(job(ctx, fixture, tactic, 0, "")));
            }
        }

        List<TacticResult> results = new ArrayList<>();
        for (Future<TacticResult> future : futures) {
            try {
                results.add(future.get(TACTIC_TIMEOUT_MINUTES, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                results.add(new TacticResult("?", "", null, shortError(cause), 0));
            } catch (Exception e) {
                future.cancel(true);
                results.add(new TacticResult("?", "", null, "vaxt aşımı", 0));
            }
        }
        return results;
    }

    private static Callable<TacticResult> job(Context ctx, Fixture fixture,
                                              Tactic tactic, int teamId, String scope) {
        return () -> {
            long start = System.currentTimeMillis();
            try {
                String output = tactic.run(ctx, fixture, teamId);
                return new TacticResult(tactic.name(), scope, output, null,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                return new TacticResult(tactic.name(), scope, null, shortError(e),
                        System.currentTimeMillis() - start);
            }
        };
    }

    private static String shortError(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Hesabat
    // ═══════════════════════════════════════════════════════════════════════

    /** Bir oyunun tam hesabatı — konsola və fayla eyni mətn gedir. */
    static String renderFixtureReport(Fixture fixture, List<TacticResult> results) {
        List<TacticResult> matched = results.stream().filter(TacticResult::matched).toList();
        List<TacticResult> failed  = results.stream().filter(r -> r.error != null).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  %-62s║%n", trim(fixture.title(), 62)));
        if (!fixture.leagueName.isEmpty() || !fixture.kickoff.isEmpty()) {
            sb.append(String.format("║  %-62s║%n",
                    trim((fixture.leagueName + "  " + fixture.kickoff).trim(), 62)));
        }
        sb.append("╚══════════════════════════════════════════════════════════════════╝\n");
        sb.append(String.format("UYĞUN GƏLƏN TAKTİKA: %d / %d  (yoxlanan çağırış sayı)%n",
                matched.size(), results.size()));

        if (matched.isEmpty()) {
            sb.append("→ Bu oyun üçün heç bir taktika siqnal vermədi.\n");
        } else {
            sb.append("\n── XÜLASƏ ──────────────────────────────────────────────────────\n");
            for (TacticResult result : matched) {
                sb.append(String.format("  ✓ %-42s %5.1f sn%n",
                        trim(result.label(), 42), result.millis / 1000.0));
            }

            sb.append("\n── NƏTİCƏLƏR ───────────────────────────────────────────────────\n");
            for (TacticResult result : matched) {
                sb.append("\n▼ ").append(result.label()).append('\n');
                sb.append(result.output.strip()).append('\n');
                sb.append("────────────────────────────────────────────────────────────────\n");
            }
        }

        if (!failed.isEmpty()) {
            sb.append("\n── XƏTA VERƏN TAKTİKALAR ───────────────────────────────────────\n");
            for (TacticResult result : failed) {
                sb.append("  ! ").append(result.label()).append(" → ").append(result.error).append('\n');
            }
        }
        return sb.toString();
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        List<Fixture> manual = new ArrayList<>();
        boolean today = false;
        String leagueFilter = null;
        int max = 0;
        Path output = null;

        for (String arg : args == null ? new String[0] : args) {
            if ("--bugun".equalsIgnoreCase(arg)) {
                today = true;
            } else if (arg.startsWith("--liqa=")) {
                leagueFilter = arg.substring("--liqa=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--max=")) {
                max = parseIntOr(arg.substring("--max=".length()), 0);
            } else if (arg.startsWith("--cixis=")) {
                output = Paths.get(arg.substring("--cixis=".length()).trim());
            } else {
                Fixture fixture = parsePair(arg);
                if (fixture != null) manual.add(fixture);
            }
        }

        // Konsola gedən HƏR ŞEY eyni anda yaddaşda da toplanır: yalnız bu sinfin
        // yazdıqları deyil, ayrı-ayrı analizatorların öz gedişat sətirləri də.
        // Beləliklə fayl konsolun tam surətidir.
        ByteArrayOutputStream transcript = startTranscript();

        List<Tactic> tactics = catalogue();
        emit("╔══════════════════════════════════════════════════════════════════╗");
        emit("║  ALL-IN-ONE TACTICS — bir oyun, bütün taktikalar                 ║");
        emit("╚══════════════════════════════════════════════════════════════════╝");
        emit("Tarix       : " + LocalDateTime.now());
        emit("Taktika sayı: " + tactics.size()
                + "  (oyun: " + tactics.stream().filter(t -> !t.perTeam()).count()
                + ", komanda: " + tactics.stream().filter(Tactic::perTeam).count() + " ×2)");
        for (Tactic tactic : tactics) {
            emit("   " + (tactic.perTeam() ? "[komanda] " : "[oyun]    ") + tactic.name());
        }

        // Əl ilə verilən cütlərdə yalnız ID var; günün proqramında tapılırsa həqiqi
        // ad, liqa və başlama saatı ilə əvəzlənir ki, hesabat oxunaqlı olsun.
        boolean wholeDay = manual.isEmpty() || today;
        List<Fixture> programme = fetchToday(wholeDay ? leagueFilter : null, wholeDay ? max : 0);

        List<Fixture> fixtures = new ArrayList<>(enrich(manual, programme));
        if (wholeDay) {
            Set<String> seen = new LinkedHashSet<>();
            for (Fixture fixture : fixtures) seen.add(fixture.homeId + "-" + fixture.awayId);
            for (Fixture fixture : programme) {
                if (seen.add(fixture.homeId + "-" + fixture.awayId)) fixtures.add(fixture);
            }
        }

        if (fixtures.isEmpty()) {
            emit("\nAnaliz ediləcək oyun yoxdur.");
            emit("İşlətmə:  AllInOneTactics evId:depId   ya da   AllInOneTactics --bugun");
            writeTranscript(transcript, output);
            return;
        }
        emit("Analiz ediləcək oyun: " + fixtures.size());

        MackolikHttpFetcher fetcher = new MackolikHttpFetcher(NUM_THREADS, MIN_REQUEST_GAP);
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS * 2);
        cm.setDefaultMaxPerRoute(NUM_THREADS * 2);
        CloseableHttpClient apache = HttpClients.custom().setConnectionManager(cm).build();
        Context ctx = new Context(fetcher, apache);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        long start = System.currentTimeMillis();
        int totalMatched = 0;
        List<String> overview = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            List<TacticResult> results = analyzeFixture(ctx, fixture, tactics, executor);
            results.sort(Comparator.comparing((TacticResult r) -> !r.matched())
                    .thenComparing(r -> r.tacticName));

            long matched = results.stream().filter(TacticResult::matched).count();
            totalMatched += matched;
            overview.add(String.format("  %-42s %d taktika", trim(fixture.title(), 42), matched));

            emit(renderFixtureReport(fixture, results));
        }

        emit("\n╔══════════════════════════════════════════════════════════════════╗");
        emit("║  ÜMUMİ XÜLASƏ                                                    ║");
        emit("╚══════════════════════════════════════════════════════════════════╝");
        for (String line : overview) emit(line);
        emit("\nOyun sayı        : " + fixtures.size());
        emit("Ümumi siqnal     : " + totalMatched);
        emit("Keçən vaxt       : " + ((System.currentTimeMillis() - start) / 1000) + " sn");
        emit("HTTP             : " + fetcher.statsLine());

        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.MINUTES)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        fetcher.close();
        try {
            apache.close();
        } catch (IOException e) {
            System.err.println("HttpClient bağlanmadı: " + e.getMessage());
        }

        writeTranscript(transcript, output);
        System.exit(0);
    }

    /**
     * {@code System.out}-u ikiyə bölür: ekran + yaddaş. Analizatorlar birbaşa
     * {@code System.out}-a yazdığı üçün onların gedişat sətirlərini yaxalamağın
     * yeganə yolu axının özünü əvəzləməkdir.
     */
    private static ByteArrayOutputStream startTranscript() {
        // Baytlar olduğu kimi toplanır və yalnız sonda UTF-8 kimi açılır: bayt-bayt
        // simvola çevirmək çoxbaytlı hərfləri (ə, ş, İ) pozardı.
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        PrintStream console = System.out;

        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public synchronized void write(int b) {
                console.write(b);
                transcript.write(b);
            }

            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                console.write(bytes, offset, length);
                transcript.write(bytes, offset, length);
            }

            @Override
            public void flush() {
                console.flush();
            }
        }, true, StandardCharsets.UTF_8));
        return transcript;
    }

    /** Konsola yazır — transkript {@link #startTranscript()} vasitəsilə avtomatik dolur. */
    private static void emit(String line) {
        System.out.println(line);
    }

    /** Bütün konsol çıxışını mətn faylına yazır. */
    private static void writeTranscript(ByteArrayOutputStream transcript, Path output) {
        Path target = output != null
                ? output
                : Paths.get("allinone-tactics_" + LocalDateTime.now().format(STAMP) + ".txt");
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, transcript.toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            System.out.println("\n📄 Hesabat yazıldı: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("❌ Hesabat yazılmadı (" + target + "): " + e.getMessage());
        }
    }

    /** Əl ilə verilən cütləri proqramdakı həqiqi ad/liqa/saat ilə tamamlayır. */
    private static List<Fixture> enrich(List<Fixture> manual, List<Fixture> programme) {
        List<Fixture> enriched = new ArrayList<>();
        for (Fixture fixture : manual) {
            Fixture match = programme.stream()
                    .filter(p -> p.homeId == fixture.homeId && p.awayId == fixture.awayId)
                    .findFirst()
                    .orElse(fixture);
            enriched.add(match);
        }
        return enriched;
    }

    /** Günün başlamamış oyunları — ortaq {@link TeamIdsFetcher} qatından. */
    private static List<Fixture> fetchToday(String leagueFilter, int max) {
        List<Fixture> fixtures = new ArrayList<>();
        for (TeamIdsFetcher.Fixture fixture : TeamIdsFetcher.fetchUnstartedFixtures()) {
            if (leagueFilter != null && !leagueFilter.isEmpty()
                    && !fixture.leagueName.toLowerCase(Locale.ROOT).contains(leagueFilter)) {
                continue;
            }
            fixtures.add(new Fixture(fixture.homeId, fixture.homeName,
                    fixture.awayId, fixture.awayName, fixture.leagueName, fixture.kickoff));
            if (max > 0 && fixtures.size() >= max) break;
        }
        return fixtures;
    }

    /** "evId:depId" arqumenti. */
    private static Fixture parsePair(String arg) {
        String[] parts = arg.split("[:\\-]");
        if (parts.length != 2) {
            System.err.println("Keçərsiz arqument (evId:depId gözlənilir): " + arg);
            return null;
        }
        try {
            int homeId = Integer.parseInt(parts[0].trim());
            int awayId = Integer.parseInt(parts[1].trim());
            return new Fixture(homeId, "Ev#" + homeId, awayId, "Dep#" + awayId, "", "");
        } catch (NumberFormatException e) {
            System.err.println("Keçərsiz ID cütü: " + arg);
            return null;
        }
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
