package analyzer.mackolik.multiversion;

import analyzer.util.MackolikHttpFetcher;
import analyzer.util.TeamIdsFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class MultiVersionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MultiVersionAnalyzer.class);
    /** En yeni geçmiş sezon — güncel sezondan dinamik (ör. 2026/2027 sezonundaysak 2025). */
    private static final int START_YEAR = AdvancedScoreScraper.CURRENT_SEASON_START_YEAR - 1;
    private static final int END_YEAR = 2010;
    private static final int[] ANALYSIS_VERSIONS = {1, 2, 3, 4};
    private static final int MIN_COMEBACK_COUNT = 2;
    /** Eşzamanlı takım analizi sayısı — sınırsız virtual thread sunucuyu boğup timeout'a yol açıyordu. */
    private static final int NUM_THREADS = 8;
    /** İki HTTP isteği arasındaki en küçük global boşluk (ms). */
    private static final long REQUEST_GAP_MS = 40;

    /**
     * HT/FT sonucu 1/2 veya 2/1 mi? (Devre arasında önde olan taraf maçı kaybetti = full comeback)
     */
    static boolean isFullComeback(String htScore, String ftScore) {
        int[] ht = parseScore(htScore);
        int[] ft = parseScore(ftScore);
        if (ht == null || ft == null) return false;
        boolean htHomeLeads = ht[0] > ht[1];
        boolean htAwayLeads = ht[1] > ht[0];
        boolean ftHomeWins = ft[0] > ft[1];
        boolean ftAwayWins = ft[1] > ft[0];
        return (htHomeLeads && ftAwayWins)   // HT/FT 1/2
                || (htAwayLeads && ftHomeWins); // HT/FT 2/1
    }

    private static int[] parseScore(String score) {
        if (score == null) return null;
        String cleaned = score.replace("(", "").replace(")", "").trim();
        String[] parts = cleaned.split("-");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private static class MultiVersionTeamProcessorTask implements Callable<MultiVersionResult> {
        private final int teamId;
        private final MackolikHttpFetcher http;

        public MultiVersionTeamProcessorTask(int teamId, MackolikHttpFetcher http) {
            this.teamId = teamId;
            this.http = http;
        }

        @Override
        public MultiVersionResult call() {
            long start = System.currentTimeMillis();
            log.info("[START] Processing Team ID: {} with {} versions", teamId, ANALYSIS_VERSIONS.length);

            MultiVersionResult multiResult = new MultiVersionResult(teamId);

            try {
                // Her versiyon için analiz yap
                for (int version : ANALYSIS_VERSIONS) {
                    try {
                        log.info("[VERSION-{}] Analyzing team ID: {} (sondan {}. maç)", version, teamId, version);

                        // Güncel deseni bul (sondan version. maç)
                        AdvancedMatchPattern currentPattern = AdvancedScoreScraper.findLastMatchPattern(
                                http, teamId, version);

                        if (currentPattern == null) {
                            log.warn("[VERSION-{}] No pattern found for team ID: {}", version, teamId);
                            continue;
                        }

                        log.info("[VERSION-{}] Pattern: {} {} {} (Team: {})",
                                version, currentPattern.homeTeam, currentPattern.score,
                                currentPattern.awayTeam, currentPattern.teamName);

                        // Geçmiş sezonlarda ara (version kadar maç sonrasını göster)
                        Map<Integer, List<AdvancedMatchResult>> foundMatches =
                                searchPastSeasons(http, currentPattern, version);

                        if (!foundMatches.isEmpty()) {
                            int totalMatches = foundMatches.values().stream().mapToInt(List::size).sum();
                            log.info("[VERSION-{}] Found {} matches for team '{}'",
                                    version, totalMatches, currentPattern.teamName);

                            VersionResult versionResult = new VersionResult(version, currentPattern, foundMatches);
                            multiResult.addVersionResult(versionResult);
                        } else {
                            log.info("[VERSION-{}] No historical matches found", version);
                        }

                    } catch (Exception e) {
                        log.error("[VERSION-{}] Error processing team {}: {}", version, teamId, e.getMessage());
                    }
                }

                return multiResult.hasAnyResults() ? multiResult : null;

            } catch (Exception e) {
                log.error("[ERROR] Exception while processing team {}: {}", teamId, e.getMessage(), e);
                return null;
            } finally {
                long duration = System.currentTimeMillis() - start;
                log.info("[END] Team ID: {} processed in {} ms.", teamId, duration);
            }
        }

        private Map<Integer, List<AdvancedMatchResult>> searchPastSeasons(
                MackolikHttpFetcher http,
                AdvancedMatchPattern pattern,
                int matchesForward) {

            Map<Integer, List<AdvancedMatchResult>> found = new TreeMap<>();

            for (int year = START_YEAR; year >= END_YEAR; year--) {
                String season = year + "/" + (year + 1);
                List<AdvancedMatchResult> matches =
                        AdvancedScoreScraper.findHistoricalMatchPatterns(
                                http, pattern, season, teamId, matchesForward);

                if (matches != null && !matches.isEmpty()) {
                    log.debug("Found {} matches in {}", matches.size(), season);
                    found.put(year, matches);
                }
            }

            return found;
        }
    }

    static class VersionResult {
        int version;
        AdvancedMatchPattern pattern;
        Map<Integer, List<AdvancedMatchResult>> matches; // sadece Sonraki Maç'ı HT/FT 1/2 veya 2/1 (full comeback) olanlar

        public VersionResult(int version, AdvancedMatchPattern pattern,
                             Map<Integer, List<AdvancedMatchResult>> matches) {
            this.version = version;
            this.pattern = pattern;
            this.matches = filterFullComebacks(matches);
        }

        private static Map<Integer, List<AdvancedMatchResult>> filterFullComebacks(
                Map<Integer, List<AdvancedMatchResult>> all) {
            Map<Integer, List<AdvancedMatchResult>> filtered = new TreeMap<>();
            for (Map.Entry<Integer, List<AdvancedMatchResult>> entry : all.entrySet()) {
                List<AdvancedMatchResult> comebacks = entry.getValue().stream()
                        .filter(m -> isFullComeback(m.nextHtScore, m.nextScore))
                        .collect(Collectors.toList());
                if (!comebacks.isEmpty()) {
                    filtered.put(entry.getKey(), comebacks);
                }
            }
            return filtered;
        }

        int comebackCount() {
            return matches.values().stream().mapToInt(List::size).sum();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("\n╔══════════════════════════════════════════════════════════════╗\n"));
            sb.append(String.format("║ VERSİYON %d: Sondan %d. Maç | HT/FT 2/1 - 1/2 | %d Comeback  ║\n",
                    version, version, comebackCount()));
            sb.append(String.format("╚══════════════════════════════════════════════════════════════╝\n"));
            sb.append("Aranan Pattern: ").append(pattern.toString()).append("\n");

            for (Map.Entry<Integer, List<AdvancedMatchResult>> entry : matches.entrySet()) {
                sb.append("\n").append(entry.getKey()).append(" Sezonu:\n");
                sb.append("─────────────────────────────────────────────\n");
                String seasonResults = entry.getValue().stream()
                        .map(AdvancedMatchResult::toString)
                        .collect(Collectors.joining("\n\n"));
                sb.append(seasonResults).append("\n");
            }
            return sb.toString();
        }
    }

    static class MultiVersionResult {
        int teamId;
        List<VersionResult> versionResults = new ArrayList<>();

        public MultiVersionResult(int teamId) {
            this.teamId = teamId;
        }

        public void addVersionResult(VersionResult result) {
            versionResults.add(result);
        }

        /**
         * Bir maç (pattern) için en az MIN_COMEBACK_COUNT full comeback bulunan versiyonlar.
         */
        List<VersionResult> printableVersions() {
            return versionResults.stream()
                    .filter(vr -> vr.comebackCount() >= MIN_COMEBACK_COUNT)
                    .collect(Collectors.toList());
        }

        public boolean hasAnyResults() {
            return !printableVersions().isEmpty();
        }

        /**
         * En sonda basılacak özet: bu maça geçmişte kaç adet HT/FT 2/1 - 1/2 bulundu.
         */
        String summaryLine() {
            List<VersionResult> printable = printableVersions();
            if (printable.isEmpty()) return "";
            AdvancedMatchPattern p = printable.get(0).pattern;
            String nextMatch = (p.nextHomeTeam != null && p.nextAwayTeam != null)
                    ? p.nextHomeTeam + " vs " + p.nextAwayTeam
                    : "?";
            String versionCounts = printable.stream()
                    .map(vr -> String.format("V%d: %d adet", vr.version, vr.comebackCount()))
                    .collect(Collectors.joining(" | "));
            return String.format("%s -> Geçmişte %s HT/FT 2/1-1/2 bulundu  [%s]",
                    nextMatch,
                    printable.stream().mapToInt(VersionResult::comebackCount).sum() + " adet",
                    versionCounts);
        }

        @Override
        public String toString() {
            List<VersionResult> printable = printableVersions();
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append("═══════════════════════════════════════════════════════════════════════\n");
            sb.append(String.format("  TAKIM ID: %d - HT/FT FULL COMEBACK (2/1 - 1/2) ANALİZİ\n", teamId));

            if (!printable.isEmpty()) {
                sb.append(String.format("  Takım: %s\n", printable.get(0).pattern.teamName));
            }
            sb.append("═══════════════════════════════════════════════════════════════════════\n");

            for (VersionResult vr : printable) {
                sb.append(vr.toString());
            }

            sb.append("\n═══════════════════════════════════════════════════════════════════════\n");
            return sb.toString();
        }
    }


    static void main(String[] args) {
        List<String> teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
            if (teamIds.isEmpty()) {
                log.warn("No team IDs found. Exiting.");
                return;
            }
            log.info("Loaded {} team IDs for multi-version analysis.", teamIds.size());


        // Paylaşımlı, retry'lı ve throttle'lı HTTP katmanı + sınırlı thread havuzu:
        // eski hali (takım başına ayrı client + sınırsız virtual thread) sunucuyu
        // boğuyor ve "Read timed out" yağmuruna sebep oluyordu.
        MackolikHttpFetcher http = new MackolikHttpFetcher(NUM_THREADS, REQUEST_GAP_MS);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CompletionService<MultiVersionResult> completionService = new ExecutorCompletionService<>(executor);

        long startTime = System.currentTimeMillis();
        int submitted = 0;

        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                completionService.submit(new MultiVersionTeamProcessorTask(teamId, http));
                submitted++;
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid team ID: {}", idStr);
            }
        }

        log.info("{} tasks submitted. Waiting for completion...", submitted);

        int foundCount = 0;
        List<MultiVersionResult> foundResults = new ArrayList<>();
        for (int i = 0; i < submitted; i++) {
            try {
                Future<MultiVersionResult> completed = completionService.take();
                MultiVersionResult result = completed.get();
                if (result != null && result.hasAnyResults()) {
                    synchronized (System.out) {
                        System.out.println(result);
                    }
                    foundResults.add(result);
                    foundCount++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Main thread interrupted.", e);
                break;
            } catch (ExecutionException e) {
                log.error("A task failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }

        if (!foundResults.isEmpty()) {
            System.out.println("\n╔═══════════════════════════════════════════════════════════════╗");
            System.out.println("║  ÖZET İSTATİSTİK: HT/FT 2/1 - 1/2 (FULL COMEBACK)             ║");
            System.out.println("╚═══════════════════════════════════════════════════════════════╝");
            for (MultiVersionResult result : foundResults) {
                System.out.println("  " + result.summaryLine());
            }
            System.out.println();
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Processing complete in {} ms. Found patterns for {} out of {} teams. Requests: {}",
                duration, foundCount, submitted, http.statsLine());

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        http.close();
    }

}