package analyzer.mackolik.triplepattern;

import analyzer.mackolik.triplepattern.LeagueSeasonFetcher.LeagueRef;
import analyzer.mackolik.triplepattern.LeagueWidePatternMatcher.PatternHit;
import analyzer.util.TeamIdsFetcher;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Takes a team's last 2, 3 and 4 league matches and looks for the same run
 * across the last 20 seasons of that team's league — scanning EVERY club in the
 * league, not just the team itself.
 *
 * A run matches when each (opponent, score) pair of the pattern is reproduced;
 * the order of the matches is free and the score is read from the scanning team's
 * point of view, so an away 0-1 win equals a home 1-0 win.
 *
 * For every occurrence the surrounding finished matches are printed
 * (previous → pattern → next) and the İY/MS code of the following match is
 * collected, ending in a 2/1 · 1/2 · 1/X · 2/X frequency summary.
 *
 * Usage:
 *   java ... LeagueWidePatternAnalyzer 3 1 2            → only those team IDs
 *   java ... LeagueWidePatternAnalyzer                  → TeamIdsFetcher (teams with an upcoming match)
 *   java ... LeagueWidePatternAnalyzer --mode=scores 3  → match on scores alone
 *
 * The default mode requires opponent AND score to match. Be aware that fixture lists
 * never repeat an opponent sequence, so this is effectively unsatisfiable for N>=3;
 * --mode=scores drops the opponent condition and still prints which clubs were involved.
 */
public class LeagueWidePatternAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LeagueWidePatternAnalyzer.class);

    private static final String CURRENT_SEASON = "2026/2027";
    /** How far back the league page is looked up when the current season's page does not exist yet. */
    private static final int MAX_ANCHOR_FALLBACK = 3;
    /** The current season must have at least this many finished matches before analysis starts. */
    private static final int MIN_PATTERN_MATCHES = 2;
    private static final int SEASONS_TO_SCAN     = 20;
    private static final int[] PATTERN_SIZES     = {2, 3, 4};
    private static final int NUM_THREADS         = 10;

    // ═══════════════════════════════════════════════════════════════════════
    //  Per-team task
    // ═══════════════════════════════════════════════════════════════════════

    /** AllInOneTactics ucun tek komandaliq giris noktasi; signal yoxdursa null. */
    public static String analyzeSingleTeam(CloseableHttpClient http, int teamId,
                                           LeagueWidePatternMatcher.MatchMode mode) {
        try {
            return new TeamTask(teamId, http, mode).call();
        } catch (Exception e) {
            return null;
        }
    }

    private static class TeamTask implements Callable<String> {

        private final int teamId;
        private final CloseableHttpClient http;
        private final LeagueWidePatternMatcher.MatchMode mode;

        TeamTask(int teamId, CloseableHttpClient http, LeagueWidePatternMatcher.MatchMode mode) {
            this.teamId = teamId;
            this.http   = http;
            this.mode   = mode;
        }

        @Override
        public String call() {
            try {
                return analyze();
            } catch (Exception e) {
                log.error("Team {} failed: {}", teamId, e.getMessage(), e);
                return null;
            }
        }

        private String analyze() throws IOException {
            LeagueRef league = resolveLeagueWithFallback();
            if (league == null) {
                log.warn("Team {}: no league could be resolved", teamId);
                return null;
            }

            Map<String, Integer> seasonIndex = LeagueSeasonFetcher.fetchSeasonIndex(http, league.seasonId);
            if (seasonIndex.isEmpty()) {
                log.warn("Team {}: empty season index for league '{}'", teamId, league.leagueName);
                return null;
            }

            List<String> labels = sortedLabelsNewestFirst(seasonIndex);
            String teamName = LeagueSeasonFetcher.fetchTeamName(http, teamId, league.seasonLabel);

            // ── The season the pattern is taken from: strictly the current season ──
            int patternSeasonPos = labels.indexOf(CURRENT_SEASON);
            if (patternSeasonPos < 0) {
                log.info("Team {} ({}): current season {} not listed — keçildi",
                        teamId, teamName, CURRENT_SEASON);
                return null;
            }

            List<LeagueMatch> patternSeason =
                    LeagueSeasonFetcher.fetchLeagueSeason(http, seasonIndex.get(CURRENT_SEASON), CURRENT_SEASON);

            // At least MIN_PATTERN_MATCHES finished matches must exist this season, else pass the team.
            int playedThisSeason = LeagueWidePattern.timelineOf(patternSeason, teamId).size();
            if (playedThisSeason < MIN_PATTERN_MATCHES) {
                log.info("Team {} ({}): {} sezonunda yalnız {} oyun oynanıb (min {}) — keçildi",
                        teamId, teamName, CURRENT_SEASON, playedThisSeason, MIN_PATTERN_MATCHES);
                return null;
            }

            String patternLabel = CURRENT_SEASON;

            // ── Seasons to scan: the 20 preceding the pattern season ────────
            List<String> historySeasons = new ArrayList<>();
            for (int i = patternSeasonPos + 1;
                 i < labels.size() && historySeasons.size() < SEASONS_TO_SCAN;
                 i++) {
                historySeasons.add(labels.get(i));
            }

            StringBuilder report = new StringBuilder();
            boolean anythingFound = false;

            for (int size : PATTERN_SIZES) {
                LeagueWidePattern pattern = LeagueWidePattern.build(
                        patternSeason, teamId, teamName, patternLabel, size);
                if (pattern == null) continue;

                List<PatternHit> hits = new ArrayList<>();
                for (String seasonLabel : historySeasons) {
                    List<LeagueMatch> season =
                            LeagueSeasonFetcher.fetchLeagueSeason(http, seasonIndex.get(seasonLabel), seasonLabel);
                    hits.addAll(LeagueWidePatternMatcher.scanSeason(season, pattern, seasonLabel, mode));
                }

                if (hits.isEmpty()) {
                    log.info("Team {} ({}) N={}: no occurrences in {} seasons",
                            teamId, teamName, size, historySeasons.size());
                    continue;
                }

                anythingFound = true;
                report.append(renderBlock(teamName, league, pattern, hits, historySeasons.size(), mode));
            }

            return anythingFound ? report.toString() : null;
        }

        /**
         * The newest season page may not exist yet (a season that has not kicked off),
         * so a few seasons are tried before giving up.
         */
        private LeagueRef resolveLeagueWithFallback() throws IOException {
            int startYear = Integer.parseInt(CURRENT_SEASON.split("/")[0]);
            for (int back = 0; back < MAX_ANCHOR_FALLBACK; back++) {
                String label = (startYear - back) + "/" + (startYear - back + 1);
                LeagueRef ref = LeagueSeasonFetcher.resolveLeague(http, teamId, label);
                if (ref != null) return ref;
            }
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Output
    // ═══════════════════════════════════════════════════════════════════════

    private static String renderBlock(String teamName, LeagueRef league, LeagueWidePattern pattern,
                                      List<PatternHit> hits, int scannedSeasons,
                                      LeagueWidePatternMatcher.MatchMode mode) {

        StringBuilder sb = new StringBuilder();
        sb.append("\n════════ ").append(teamName)
                .append(" (ID:").append(pattern.teamId).append(") | ")
                .append(league.leagueName)
                .append(" | N=").append(pattern.size())
                .append(" ════════\n");
        sb.append(pattern.describe());
        sb.append("Uyğunluq rejimi: ")
                .append(mode == LeagueWidePatternMatcher.MatchMode.STRICT
                        ? "rəqib + skor (cütlük bağlı)"
                        : "yalnız skor")
                .append('\n');
        sb.append("Skan edilən sezon sayı: ").append(scannedSeasons).append('\n');
        sb.append("Tapılan hal: ").append(hits.size()).append("\n\n");

        for (PatternHit hit : hits) {
            sb.append(hit.render()).append('\n');
        }

        sb.append(renderSummary(pattern.size(), hits));
        return sb.toString();
    }

    /** 2/1 · 1/2 · 1/X · 2/X frequencies of the match that FOLLOWED the pattern. */
    private static String renderSummary(int size, List<PatternHit> hits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String code : LeagueWidePatternMatcher.TARGET_HT_FT) counts.put(code, 0);

        int withNext = 0;
        int other    = 0;

        for (PatternHit hit : hits) {
            if (hit.nextMatch == null || hit.nextHtFt == null) continue;
            withNext++;
            if (counts.containsKey(hit.nextHtFt)) {
                counts.merge(hit.nextHtFt, 1, Integer::sum);
            } else {
                other++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ÖZET (N=").append(size).append("): ")
                .append(withNext).append(" halda pattern sonrası oyun məlumdur\n");

        if (withNext == 0) {
            sb.append("  Pattern sonrası oyun tapılmadı — təxmin çıxarıla bilmir.\n");
            return sb.toString();
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append(String.format("  %-5s: %d (%.1f%%)%s%n",
                    entry.getKey(), entry.getValue(), percent(entry.getValue(), withNext),
                    entry.getValue() > 0 ? " ★" : ""));
        }
        sb.append(String.format("  %-5s: %d (%.1f%%)%n", "digər", other, percent(other, withNext)));

        int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best == 0) {
            sb.append("  >>> TƏXMİN: yoxdur (heç bir haldan sonra 2/1, 1/2, 1/X, 2/X gəlməyib)\n");
        } else {
            List<String> leaders = counts.entrySet().stream()
                    .filter(e -> e.getValue() == best)
                    .map(Map.Entry::getKey)
                    .toList();
            sb.append("  >>> TƏXMİN: ").append(String.join(" və ya ", leaders))
                    .append(String.format("  (%d/%d, %.1f%%)%n", best, withNext, percent(best, withNext)));
        }
        return sb.toString();
    }

    private static double percent(int part, int total) {
        return total == 0 ? 0.0 : (100.0 * part) / total;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Season label helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** "2025/2026", "2024/2025", … — newest first. */
    private static List<String> sortedLabelsNewestFirst(Map<String, Integer> seasonIndex) {
        List<String> labels = new ArrayList<>(seasonIndex.keySet());
        labels.sort(Comparator.comparingInt(LeagueWidePatternAnalyzer::startYear).reversed());
        return labels;
    }

    private static int startYear(String label) {
        try {
            return Integer.parseInt(label.split("/")[0].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        LeagueWidePatternMatcher.MatchMode mode = LeagueWidePatternMatcher.MatchMode.STRICT;
        List<String> teamIds = new ArrayList<>();

        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--mode=")) {
                    String value = arg.substring("--mode=".length()).trim().toLowerCase();
                    mode = value.startsWith("score")
                            ? LeagueWidePatternMatcher.MatchMode.SCORES_ONLY
                            : LeagueWidePatternMatcher.MatchMode.STRICT;
                } else {
                    teamIds.add(arg);
                }
            }
        }

        if (teamIds.isEmpty()) teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();

        log.info("Uyğunluq rejimi: {}", mode);

        if (teamIds.isEmpty()) {
            log.warn("Analiz ediləcək komanda yoxdur.");
            return;
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);

        CloseableHttpClient http = HttpClients.custom()
                .setConnectionManager(cm)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();

        for (String raw : teamIds) {
            try {
                futures.add(executor.submit(new TeamTask(Integer.parseInt(raw.trim()), http, mode)));
            } catch (NumberFormatException e) {
                log.warn("Keçərsiz komanda ID: {}", raw);
            }
        }

        log.info("{} komanda üçün tapşırıq göndərildi.", futures.size());

        int found = 0;
        for (Future<String> future : futures) {
            try {
                String result = future.get();
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("══════════════════════════════════════════════\n");
                    found++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Gözləmə kəsildi.", e);
            } catch (ExecutionException e) {
                log.error("Tapşırıq xətası: {}", e.getCause().getMessage(), e.getCause());
            }
        }

        log.info("Bitdi. {} komandada uyğun pattern tapıldı.", found);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            http.close();
        } catch (IOException e) {
            log.error("HttpClient bağlanmadı: {}", e.getMessage());
        }

        System.exit(0);
    }
}
