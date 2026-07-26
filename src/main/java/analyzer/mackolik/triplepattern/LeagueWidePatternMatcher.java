package analyzer.mackolik.triplepattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches a whole league season for a {@link LeagueWidePattern}.
 *
 * Every team of the league is scanned — not just the team the pattern came from —
 * because different teams meet the same opponents in a different order, which is
 * exactly what makes the league-wide search worthwhile.
 *
 * Pure logic, no networking: can be exercised with hand-built {@link LeagueMatch} lists.
 */
class LeagueWidePatternMatcher {

    /** The four half-time/full-time codes the analysis is looking for. */
    static final List<String> TARGET_HT_FT = List.of("2/1", "1/2", "1/X", "2/X");

    /**
     * How strictly a run has to reproduce the pattern.
     *
     * Worth knowing before choosing: fixture lists are built so that opponent sequences
     * do not repeat, so across 20 Süper Lig seasons the same pair of opponents came up
     * back-to-back only twice, and the same trio never. STRICT therefore returns almost
     * nothing for N=2 and nothing at all for N>=3 — it is the honest reading of
     * "opponent and score must both match", not a useful screen on its own.
     */
    enum MatchMode {
        /** Opponent AND score must match, kept as pairs; order free. */
        STRICT,
        /** Only the score multiset must match; opponents are reported, not filtered. */
        SCORES_ONLY
    }

    private LeagueWidePatternMatcher() {
    }

    /** One occurrence of the pattern inside a season. */
    static class PatternHit {
        final String seasonLabel;
        final int    teamId;
        final String teamName;
        final List<LeagueMatch> window;
        /** Finished match right before the window; null when the window opens the season. */
        final LeagueMatch previousMatch;
        /** Finished match right after the window; null when the window closes the season. */
        final LeagueMatch nextMatch;
        /** İY/MS code of {@link #nextMatch}, e.g. "2/1"; null when unavailable. */
        final String nextHtFt;

        PatternHit(String seasonLabel, int teamId, String teamName, List<LeagueMatch> window,
                   LeagueMatch previousMatch, LeagueMatch nextMatch, String nextHtFt) {
            this.seasonLabel   = seasonLabel;
            this.teamId        = teamId;
            this.teamName      = teamName;
            this.window        = window;
            this.previousMatch = previousMatch;
            this.nextMatch     = nextMatch;
            this.nextHtFt      = nextHtFt;
        }

        /** "── 2019/2020 | Kasımpaşa ──" plus previous / pattern / next lines. */
        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("── ").append(seasonLabel).append(" | ").append(teamName).append(" ──\n");

            sb.append("  Əvvəlki : ")
                    .append(previousMatch != null ? previousMatch.displayLine() : "Bilgi Yok (İlk Maç)")
                    .append('\n');

            for (int i = 0; i < window.size(); i++) {
                sb.append(i == 0 ? "  Pattern : " : "            ")
                        .append(window.get(i).displayLine())
                        .append('\n');
            }

            sb.append("  Sonrakı : ");
            if (nextMatch == null) {
                sb.append("Bilgi Yok (Son Maç)");
            } else {
                sb.append(nextMatch.displayLine());
                if (nextHtFt != null) {
                    sb.append("  → İY/MS ").append(nextHtFt);
                    if (TARGET_HT_FT.contains(nextHtFt)) sb.append(" ★");
                }
            }
            sb.append('\n');
            return sb.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Scan
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Slides a window of {@code pattern.size()} consecutive matches over every team's
     * timeline in the season and reports the windows that satisfy the pattern.
     */
    static List<PatternHit> scanSeason(List<LeagueMatch> seasonMatches,
                                       LeagueWidePattern pattern,
                                       String seasonLabel) {
        return scanSeason(seasonMatches, pattern, seasonLabel, MatchMode.STRICT);
    }

    static List<PatternHit> scanSeason(List<LeagueMatch> seasonMatches,
                                       LeagueWidePattern pattern,
                                       String seasonLabel,
                                       MatchMode mode) {

        List<PatternHit> hits = new ArrayList<>();
        if (seasonMatches == null || seasonMatches.isEmpty()) return hits;

        int windowSize = pattern.size();

        for (Map.Entry<Integer, List<LeagueMatch>> entry : timelinesByTeam(seasonMatches).entrySet()) {
            int teamId = entry.getKey();
            List<LeagueMatch> timeline = entry.getValue();
            if (timeline.size() < windowSize) continue;

            for (int i = 0; i + windowSize <= timeline.size(); i++) {
                List<LeagueMatch> window = timeline.subList(i, i + windowSize);
                if (!windowMatches(window, teamId, pattern, mode)) continue;

                LeagueMatch previous = (i > 0) ? timeline.get(i - 1) : null;
                LeagueMatch next     = (i + windowSize < timeline.size())
                        ? timeline.get(i + windowSize) : null;

                hits.add(new PatternHit(seasonLabel, teamId,
                        window.get(0).teamName(teamId),
                        new ArrayList<>(window),
                        previous, next,
                        next != null ? computeHtFtFull(next) : null));
            }
        }
        return hits;
    }

    /** Finished matches of every team in the season, each list chronologically ordered. */
    static Map<Integer, List<LeagueMatch>> timelinesByTeam(List<LeagueMatch> seasonMatches) {
        Set<Integer> teamIds = new LinkedHashSet<>();
        for (LeagueMatch match : seasonMatches) {
            if (!match.finished) continue;
            teamIds.add(match.homeId);
            teamIds.add(match.awayId);
        }

        Map<Integer, List<LeagueMatch>> timelines = new LinkedHashMap<>();
        for (int teamId : teamIds) {
            timelines.put(teamId, LeagueWidePattern.timelineOf(seasonMatches, teamId));
        }
        return timelines;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Matching — pairs kept together, order free
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * True when each pattern leg can be assigned to a distinct match of the window
     * with the SAME opponent AND the SAME score. The order of the matches is irrelevant,
     * but a leg's opponent and its score must stay together: an opponent met with a
     * different score does not satisfy the leg.
     *
     * Backtracking over at most 4 legs, so worst case is a handful of comparisons.
     */
    static boolean windowMatches(List<LeagueMatch> window, int teamId, LeagueWidePattern pattern) {
        return windowMatches(window, teamId, pattern, MatchMode.STRICT);
    }

    static boolean windowMatches(List<LeagueMatch> window, int teamId,
                                 LeagueWidePattern pattern, MatchMode mode) {
        if (window.size() != pattern.size()) return false;
        return assign(window, teamId, pattern.legs, 0, new boolean[window.size()], mode);
    }

    private static boolean assign(List<LeagueMatch> window, int teamId,
                                  List<LeagueWidePattern.Leg> legs, int legIndex,
                                  boolean[] used, MatchMode mode) {
        if (legIndex == legs.size()) return true;

        LeagueWidePattern.Leg leg = legs.get(legIndex);
        for (int i = 0; i < window.size(); i++) {
            if (used[i]) continue;
            if (!legFits(leg, window.get(i), teamId, mode)) continue;

            used[i] = true;
            if (assign(window, teamId, legs, legIndex + 1, used, mode)) return true;
            used[i] = false;
        }
        return false;
    }

    private static boolean legFits(LeagueWidePattern.Leg leg, LeagueMatch match,
                                   int teamId, MatchMode mode) {
        if (leg.goalsFor != match.goalsFor(teamId)) return false;
        if (leg.goalsAgainst != match.goalsAgainst(teamId)) return false;
        return mode == MatchMode.SCORES_ONLY || sameOpponent(leg, match, teamId);
    }

    /**
     * Opponents are compared by Mackolik team id: it survives renamings
     * ("İstanbul BB" → "Başakşehir FK") and, unlike name comparison, never confuses
     * two different clubs that share a prefix ("Kayserispor" / "Kayseri Erciyesspor").
     * The name comparison used elsewhere in this package is only a fallback for the
     * case where an id is missing from the feed.
     */
    private static boolean sameOpponent(LeagueWidePattern.Leg leg, LeagueMatch match, int teamId) {
        int opponentId = match.opponentId(teamId);
        if (leg.opponentId > 0 && opponentId > 0) return leg.opponentId == opponentId;
        return HttpTeamNamePatternFetcher.teamsMatch(leg.opponentName, match.opponentName(teamId));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Half-time / full-time
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Full İY/MS code in coupon terms — home/away oriented, so "2/1" always means
     * the AWAY side led at the break and the HOME side won.
     *
     * {@link HttpTeamNamePatternFetcher#computeHtFt} only reports 1/2 and 2/1 and other
     * analyzers depend on that, so this is a separate method rather than a change there.
     *
     * @return one of 1/1, 1/X, 1/2, X/1, X/X, X/2, 2/1, 2/X, 2/2; null without a half-time score
     */
    static String computeHtFtFull(LeagueMatch match) {
        if (match == null || !match.hasHalfTime()) return null;
        return outcome(match.htHome, match.htAway) + "/" + outcome(match.ftHome, match.ftAway);
    }

    private static String outcome(int home, int away) {
        if (home > away) return "1";
        if (home < away) return "2";
        return "X";
    }
}
