package analyzer.mackolik.triplepattern;

import java.time.LocalDate;

/**
 * A single league match as delivered by Mackolik's fixture feed
 * (AjaxHandlers/FixtureHandler.aspx?command=getMatches).
 *
 * Unlike the team-page scrapers in this package, this model carries team IDs,
 * so a match can be interpreted from either side without any name matching.
 */
class LeagueMatch {

    final int matchId;
    final int week;
    /** Reconstructed from the feed's "dd/MM" plus the season label; may be null. */
    final LocalDate date;

    final int    homeId;
    final String homeName;
    final int    awayId;
    final String awayName;

    final int ftHome;
    final int ftAway;
    /** -1 when the feed carried no half-time score. */
    final int htHome;
    final int htAway;

    final boolean finished;

    LeagueMatch(int matchId, int week, LocalDate date,
                int homeId, String homeName, int awayId, String awayName,
                int ftHome, int ftAway, int htHome, int htAway,
                boolean finished) {
        this.matchId  = matchId;
        this.week     = week;
        this.date     = date;
        this.homeId   = homeId;
        this.homeName = homeName;
        this.awayId   = awayId;
        this.awayName = awayName;
        this.ftHome   = ftHome;
        this.ftAway   = ftAway;
        this.htHome   = htHome;
        this.htAway   = htAway;
        this.finished = finished;
    }

    // ── Team perspective ────────────────────────────────────────────────────

    boolean involves(int teamId) {
        return homeId == teamId || awayId == teamId;
    }

    boolean isHome(int teamId) {
        return homeId == teamId;
    }

    /** Goals scored BY the given team. */
    int goalsFor(int teamId) {
        return isHome(teamId) ? ftHome : ftAway;
    }

    /** Goals conceded BY the given team. */
    int goalsAgainst(int teamId) {
        return isHome(teamId) ? ftAway : ftHome;
    }

    int opponentId(int teamId) {
        return isHome(teamId) ? awayId : homeId;
    }

    String opponentName(int teamId) {
        return isHome(teamId) ? awayName : homeName;
    }

    String teamName(int teamId) {
        return isHome(teamId) ? homeName : awayName;
    }

    /** Score as "scored-conceded" from the given team's point of view: 1-0. */
    String perspectiveScore(int teamId) {
        return goalsFor(teamId) + "-" + goalsAgainst(teamId);
    }

    boolean hasHalfTime() {
        return htHome >= 0 && htAway >= 0;
    }

    String htScoreText() {
        return hasHalfTime() ? htHome + "-" + htAway : "N/A";
    }

    // ── Display ─────────────────────────────────────────────────────────────

    /** "Kasımpaşa 2-1 Gençlerbirliği (İY 0-1)" */
    String displayLine() {
        return homeName + " " + ftHome + "-" + ftAway + " " + awayName
                + " (İY " + htScoreText() + ")";
    }

    @Override
    public String toString() {
        return displayLine();
    }
}
