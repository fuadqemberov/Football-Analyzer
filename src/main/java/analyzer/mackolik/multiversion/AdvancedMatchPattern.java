package analyzer.mackolik.multiversion;


class AdvancedMatchPattern {
    public String homeTeam;
    public String awayTeam;
    public String score;
    public String teamName; // Analiz edilen ana takımın adı
    public String nextHomeTeam;
    public String nextAwayTeam;

    public AdvancedMatchPattern(String homeTeam, String awayTeam, String score, String teamName,
                                String nextHomeTeam, String nextAwayTeam) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.score = score;
        this.teamName = teamName;
        this.nextHomeTeam = nextHomeTeam;
        this.nextAwayTeam = nextAwayTeam;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s %s %s", homeTeam, score, awayTeam));
        if (nextHomeTeam != null && nextAwayTeam != null) {
            sb.append(String.format("\nSıradaki Maç -> %s vs %s (Henüz oynanmadı)", nextHomeTeam, nextAwayTeam));
        }
        return sb.toString();
    }
}

/**
 * Geçmişte bulunan eşleşen bir maçın sonucunu temsil eden sınıf.
 */
class AdvancedMatchResult {
    String homeTeam;
    String awayTeam;
    String score;
    String htScore;
    String previousMatchInfo;
    String nextMatchInfo;
    String nextScore;   // Sonraki maçın maç sonu skoru
    String nextHtScore; // Sonraki maçın ilk yarı skoru
    String season;
    AdvancedMatchPattern originalPattern;

    public AdvancedMatchResult(String homeTeam, String awayTeam, String score, String htScore, String season, AdvancedMatchPattern originalPattern) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.score = score;
        this.htScore = htScore;
        this.season = season;
        this.originalPattern = originalPattern;
    }

    @Override
    public String toString() {
        String prevMatch = previousMatchInfo != null ? previousMatchInfo : "Bilgi Yok";
        String nextMatch = nextMatchInfo != null ? nextMatchInfo : "Bilgi Yok";
        String patternMatch = String.format("%s %s %s (HT: %s)", homeTeam, score, awayTeam, (htScore != null ? htScore : "N/A"));

        return String.format(
                "[%s Sezonu]\n" +
                        "Önceki Maç -> %s\n" +
                        "Bulunan Desen -> %s\n" +
                        "Sonraki Maç -> %s",
                season, prevMatch, patternMatch, nextMatch
        );
    }
}