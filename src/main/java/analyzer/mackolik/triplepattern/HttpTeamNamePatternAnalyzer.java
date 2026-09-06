package analyzer.mackolik.triplepattern;

import analyzer.util.MackolikHttpFetcher;
import analyzer.util.TeamIdsFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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

    /** Taramanın başladığı sezon = güncel sezon (desen sezon içinde de tekrarlayabilir). */
    private static final int START_YEAR = HttpTeamNamePatternFetcher.CURRENT_SEASON_START_YEAR;
    private static final int END_YEAR   = 2015;  // Reduced from 2010 (10 seasons instead of 15 = 33% faster)
    private static final int NUM_THREADS = 8;
    /** İki istek arasındaki en küçük global boşluk — sunucuyu boğup timeout üretmemek için. */
    private static final long REQUEST_GAP_MS = 40;

    // -----------------------------------------------------------------------

    /** AllInOneTactics ucun tek komandaliq giris noktasi; signal yoxdursa null. */
    public static String analyzeSingleTeam(MackolikHttpFetcher http, int teamId) {
        try {
            return new TeamNamePatternTask(teamId, http).call();
        } catch (Exception e) {
            return null;
        }
    }

    private static class TeamNamePatternTask implements Callable<String> {
        private final int teamId;
        private final MackolikHttpFetcher http;

        TeamNamePatternTask(int teamId, MackolikHttpFetcher http) {
            this.teamId = teamId;
            this.http   = http;
        }

        @Override
        public String call() {
            try {
                // Step 1: build current-season pattern
                TeamNamePattern pattern = HttpTeamNamePatternFetcher.buildCurrentPattern(http, teamId);
                if (pattern == null) return null;

                // Must have at least 1 prev AND the target to be meaningful
                if (pattern.prevOpponents.isEmpty() && pattern.nextOpponents.isEmpty()) {
                    return null;
                }

                StringBuilder output       = new StringBuilder();
                boolean       foundAnything = false;
                int hitCount = 0;

                // Step 2: search past seasons
                for (int year = START_YEAR; year >= END_YEAR; year--) {
                    String season = year + "/" + (year + 1);
                    try {
                        List<TeamNameMatchResult> hits =
                                HttpTeamNamePatternFetcher.searchHistoricalSeason(http, pattern, season, teamId);

                        if (!hits.isEmpty()) {
                            foundAnything = true;
                            hitCount += hits.size();
                            output.append(String.format("\n--- %s Sezonu ---\n", season));
                            for (TeamNameMatchResult hit : hits) {
                                output.append(hit).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Team {} season {} failed: {}", teamId, season, e.toString());
                    }
                }

                if (!foundAnything) return null;

                String header = String.format(
                        "╔══════════════════════════════════════════════╗\n" +
                                "  ✅ Takım: %s (ID: %d) → %d pattern\n" +
                                "  Mevcut Maç  : %s vs %s\n" +
                                "  Son 3 Rakip : %s\n" +
                                "  Sonraki 3   : %s\n" +
                                "╚══════════════════════════════════════════════╝",
                        pattern.teamName, pattern.teamId, hitCount,
                        pattern.targetHomeTeam, pattern.targetAwayTeam,
                        pattern.prevOpponents,
                        pattern.nextOpponents);
                return header + "\n" + output;

            } catch (Exception e) {
                log.error("Team {} failed", teamId, e);
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * SESSİZ ÇALIŞIR: sadece bulunan oyunlar stdout'a basılır.
     * İlerleme, durum, özet ve hata satırları yoktur — hatalar log'a gider.
     */
    public static void main(String[] args) {

        // TeamIdsFetcher ortak bir util ve kendi durum satırlarını basıyor; onu
        // değiştirmeden yalnızca bu çağrı boyunca stdout'u susturuyoruz.
        List<String> teamIds = quietly(TeamIdsFetcher::fetchUnstartedTeamIds);
        if (teamIds.isEmpty()) {
            log.error("Hiç takım ID'si bulunamadı");
            System.exit(1);
        }

        MackolikHttpFetcher http = new MackolikHttpFetcher(NUM_THREADS, REQUEST_GAP_MS);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();

        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                futures.add(executor.submit(new TeamNamePatternTask(teamId, http)));
            } catch (NumberFormatException e) {
                log.warn("Geçersiz team ID: {}", idStr);
            }
        }

        for (Future<String> f : futures) {
            try {
                // Her task için 10 dakika timeout. Ağ katmanı geçici hatalarda backoff'la
                // yeniden denediği için bir takım nadiren de olsa uzayabilir.
                String result = f.get(10, TimeUnit.MINUTES);

                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("════════════════════════════════════════════════════════════════════════════════\n");
                    System.out.flush();
                }

            } catch (TimeoutException e) {
                log.warn("Görev 10 dakikada tamamlanamadı");
                f.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("Execution error", e.getCause());
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        http.close();

        System.exit(0);
    }

    /** Verilen işi stdout kapalıyken çalıştırır (ortak util'lerin durum satırlarını yutar). */
    private static <T> T quietly(Supplier<T> action) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            return action.get();
        } finally {
            System.setOut(original);
        }
    }
}