package analyzer.mackolik.triplepattern;

import java.util.ArrayList;
import java.util.List;

/**
 * The pattern being searched for: a team's last N league matches, reduced to
 * (opponent, goals scored, goals conceded) from that team's own point of view.
 *
 * Recording the score from the team's perspective is deliberate — an away 0-1 win and
 * a home 1-0 win are the same result, so both satisfy the same leg.
 */
class LeagueWidePattern {

    /** One match of the pattern, as seen by the pattern's team. */
    static class Leg {
        /** Mackolik team id — stable across renamings, so this is what identifies the opponent. */
        final int    opponentId;
        final String opponentName;
        final int    goalsFor;
        final int    goalsAgainst;

        Leg(int opponentId, String opponentName, int goalsFor, int goalsAgainst) {
            this.opponentId   = opponentId;
            this.opponentName = opponentName;
            this.goalsFor     = goalsFor;
            this.goalsAgainst = goalsAgainst;
        }

        String scoreText() {
            return goalsFor + "-" + goalsAgainst;
        }

        @Override
        public String toString() {
            return opponentName + " " + scoreText();
        }
    }

    final int       teamId;
    final String    teamName;
    final String    sourceSeasonLabel;
    final List<Leg> legs;
    /** The matches the pattern was built from, kept for printing the header. */
    final List<LeagueMatch> sourceMatches;

    private LeagueWidePattern(int teamId, String teamName, String sourceSeasonLabel,
                              List<Leg> legs, List<LeagueMatch> sourceMatches) {
        this.teamId            = teamId;
        this.teamName          = teamName;
        this.sourceSeasonLabel = sourceSeasonLabel;
        this.legs              = legs;
        this.sourceMatches     = sourceMatches;
    }

    int size() {
        return legs.size();
    }

    /**
     * Builds the pattern from the team's last {@code size} finished matches of the season.
     *
     * @param seasonMatches every match of the league season, chronologically ordered
     * @return null when the team does not have {@code size} finished matches in that season
     */
    static LeagueWidePattern build(List<LeagueMatch> seasonMatches, int teamId,
                                   String teamName, String seasonLabel, int size) {

        if (size < 2 || size > 4) {
            throw new IllegalArgumentException("Pattern ölçüsü 2-4 arasında olmalıdır: " + size);
        }

        List<LeagueMatch> own = timelineOf(seasonMatches, teamId);
        if (own.size() < size) return null;

        List<LeagueMatch> last = new ArrayList<>(own.subList(own.size() - size, own.size()));

        List<Leg> legs = new ArrayList<>(size);
        for (LeagueMatch match : last) {
            legs.add(new Leg(match.opponentId(teamId),
                    match.opponentName(teamId),
                    match.goalsFor(teamId),
                    match.goalsAgainst(teamId)));
        }

        return new LeagueWidePattern(teamId, teamName, seasonLabel, legs, last);
    }

    /** The finished matches of one team, in order. */
    static List<LeagueMatch> timelineOf(List<LeagueMatch> seasonMatches, int teamId) {
        List<LeagueMatch> own = new ArrayList<>();
        for (LeagueMatch match : seasonMatches) {
            if (match.finished && match.involves(teamId)) own.add(match);
        }
        return own;
    }

    /** Header block listing what is being searched for. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Aranan pattern (").append(sourceSeasonLabel)
                .append(" son ").append(size()).append(" lig maçı, komanda gözü ilə):\n");
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            sb.append(String.format("  %d) %-28s %s%n", i + 1, leg.opponentName, leg.scoreText()));
            sb.append(String.format("     %s%n", sourceMatches.get(i).displayLine()));
        }
        return sb.toString();
    }
}
