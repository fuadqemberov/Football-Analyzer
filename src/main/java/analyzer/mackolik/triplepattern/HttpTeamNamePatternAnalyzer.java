package analyzer.mackolik.triplepattern;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Analyzer that searches for TEAM NAME SEQUENCE patterns around unstarted matches.
 *
 * For each team:
 *   1. Finds the first unstarted match in the current season.
 *   2. Collects the last 3 opponent names (prev) and next 3 opponent names (next).
 *   3. Searches past seasons (2014–2024) for the same opponent sequence in various
 *      combinations (PREV3, PREV3+NEXT1, PREV2+NEXT2, etc.).
 *   4. Prints ONLY results where the historical target match had HT/FT = 1/2 or 2/1.
 */
public class HttpTeamNamePatternAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(HttpTeamNamePatternAnalyzer.class);

    private static final int START_YEAR = 2025;
    private static final int END_YEAR   = 2015;  // Reduced from 2010 (10 seasons instead of 15 = 33% faster)
    private static final int NUM_THREADS = 10;

    // -----------------------------------------------------------------------

    private static class TeamNamePatternTask implements Callable<String> {
        private final int teamId;
        private final CloseableHttpClient http;

        TeamNamePatternTask(int teamId, CloseableHttpClient http) {
            this.teamId = teamId;
            this.http   = http;
        }

        @Override
        public String call() {
            long taskStart = System.currentTimeMillis();
            System.out.println("   ⏳ [ID:" + teamId + "] Başladı...");
            try {
                // Step 1: build current-season pattern
                long step1Start = System.currentTimeMillis();
                TeamNamePattern pattern = HttpTeamNamePatternFetcher.buildCurrentPattern(http, teamId);
                long step1Time = System.currentTimeMillis() - step1Start;

                if (pattern == null) {
                    System.out.println("   ⚠️  [ID:" + teamId + "] Pattern bulunamadı (" + step1Time + "ms)");
                    return null;
                }

                System.out.println("   ✓ [ID:" + teamId + "] Pattern: " + pattern.teamName +
                        " (Prev:" + pattern.prevOpponents.size() + ", Next:" + pattern.nextOpponents.size() +
                        ") [" + step1Time + "ms]");

                // Must have at least 1 prev AND the target to be meaningful
                if (pattern.prevOpponents.isEmpty() && pattern.nextOpponents.isEmpty()) {
                    System.out.println("   ⚠️  [ID:" + teamId + "] Skip - no surrounding opponents");
                    return null;
                }

                StringBuilder output       = new StringBuilder();
                boolean       foundAnything = false;
                int hitCount = 0;

                // Step 2: search past seasons
                System.out.println("   🔍 [ID:" + teamId + "] Tarihçe taranıyor (" + (START_YEAR - END_YEAR + 1) + " sezon)...");
                for (int year = START_YEAR; year >= END_YEAR; year--) {
                    String season = year + "/" + (year + 1);
                    try {
                        long seasonStart = System.currentTimeMillis();
                        List<TeamNameMatchResult> hits =
                                HttpTeamNamePatternFetcher.searchHistoricalSeason(http, pattern, season, teamId);
                        long seasonTime = System.currentTimeMillis() - seasonStart;

                        if (!hits.isEmpty()) {
                            foundAnything = true;
                            hitCount += hits.size();
                            System.out.println("       ⭐ [ID:" + teamId + "] " + season + " → " + hits.size() +
                                    " pattern(ler) bulundu [" + seasonTime + "ms]");
                            output.append(String.format("\n--- %s Sezonu ---\n", season));
                            for (TeamNameMatchResult hit : hits) {
                                output.append(hit).append("\n");
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("   ❌ [ID:" + teamId + "] IO hatası " + season + ": " + e.getMessage());
                    } catch (Exception e) {
                        System.err.println("   ❌ [ID:" + teamId + "] Hata " + season + ": " + e.getMessage());
                    }
                }

                long taskTime = System.currentTimeMillis() - taskStart;
                if (foundAnything) {
                    String header = String.format(
                            "╔══════════════════════════════════════════════╗\n" +
                                    "  ✅ Takım: %s (ID: %d) → %d pattern\n" +
                                    "  Mevcut Maç  : %s vs %s\n" +
                                    "  Son 3 Rakip : %s\n" +
                                    "  Sonraki 3   : %s\n" +
                                    "  Süre: %dms\n" +
                                    "╚══════════════════════════════════════════════╝",
                            pattern.teamName, pattern.teamId, hitCount,
                            pattern.targetHomeTeam, pattern.targetAwayTeam,
                            pattern.prevOpponents,
                            pattern.nextOpponents,
                            taskTime);
                    System.out.println("   ✅ [ID:" + teamId + "] TAMAMLANDI - " + hitCount + " pattern bulundu [Toplam: " + taskTime + "ms]");
                    return header + "\n" + output;
                } else {
                    System.out.println("   ℹ️  [ID:" + teamId + "] Patern bulunamadı [" + taskTime + "ms]");
                    return null;
                }

            } catch (Exception e) {
                System.err.println("   ❌ [ID:" + teamId + "] FATAL ERROR: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("\n╔════════════════════════════════════════════════╗");
        System.out.println("║  Football Analyzer - HTTP Team Name Pattern    ║");
        System.out.println("║  Fast Pattern Recognition System                ║");
        System.out.println("╚════════════════════════════════════════════════╝\n");

        long globalStart = System.currentTimeMillis();

        System.out.println("🔄 [STEP 1/4] Başlamamış maçlardan takım ID'leri alınıyor...");
        long startTime = System.currentTimeMillis();
        List<String> teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
        long step1Time = System.currentTimeMillis() - startTime;
        System.out.println("✅ [STEP 1/4] " + teamIds.size() + " takım bulundu (" + step1Time + "ms)\n");

        if (teamIds.isEmpty()) {
            System.out.println("❌ ERROR: Hiç takım ID'si bulunamadı!");
            System.exit(1);
        }

        System.out.println("🔄 [STEP 2/4] HTTP connection pool konfigüre ediliyor (" + NUM_THREADS + " workers)...");
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);

        CloseableHttpClient http = HttpClients.custom()
                .setConnectionManager(cm)
                .build();
        System.out.println("✅ [STEP 2/4] Pool hazır\n");

        System.out.println("🔄 [STEP 3/4] " + teamIds.size() + " takım için görev başlatılıyor...");
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();

        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                futures.add(executor.submit(new TeamNamePatternTask(teamId, http)));
            } catch (NumberFormatException e) {
                System.err.println("   ❌ Geçersiz team ID: " + idStr);
            }
        }

        System.out.println("✅ [STEP 3/4] " + futures.size() + " görev executor'a gönderildi\n");
        System.out.println("🔄 [STEP 4/4] Sonuçlar bekleniyor...\n");
        System.out.println(String.format("%-50s | %-8s | %-20s", "Takım", "Durum", "İşlem Süresi"));
        System.out.println("─".repeat(85));

        int total = 0, found = 0;
        int processed = 0;
        for (Future<String> f : futures) {
            try {
                // Her task için 5 dakika timeout (seson başına 30 saniye ~17 sezon)
                String result = f.get(5, TimeUnit.MINUTES);
                total++;
                processed++;

                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("════════════════════════════════════════════════════════════════════════════════\n");
                    found++;
                }

                // Progress bar
                double progress = (processed * 100.0) / futures.size();
                System.out.printf("\r⏳ İlerleme: %3d/%d (%5.1f%%)  |  Bulunan: %d/%d",
                        processed, futures.size(), progress, found, total);

            } catch (TimeoutException e) {
                System.err.println("\n   ⏱️  TIMEOUT: Görev 5 dakikada tamamlanamadı");
                f.cancel(true);
                total++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("\n   ⚠️  Interrupted");
            } catch (ExecutionException e) {
                System.err.println("\n   ❌ Execution error: " + e.getCause().getMessage());
                total++;
            }
        }

        long globalTime = System.currentTimeMillis() - globalStart;

        System.out.println("\n\n");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("✅ TARAMA TAMAMLANDI");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println(String.format(
                "   📊 Toplam Takım    : %d\n" +
                "   ⭐ Bulunan Pattern : %d\n" +
                "   📈 Başarı Oranı    : %.1f%%\n" +
                "   ⏱️  Toplam Süre     : %s\n",
                total, found, (total > 0 ? (found * 100.0 / total) : 0),
                formatTime(globalTime)));
        System.out.println("════════════════════════════════════════════════════════════════════════════════\n");

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                System.out.println("⏱️  Executor shutdown timeout, forcing...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            http.close();
        } catch (IOException e) {
            System.err.println("❌ HttpClient kapatma hatası: " + e.getMessage());
        }

        System.exit(0);
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }
}