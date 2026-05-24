package analyzer.mackolik.patternfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Geçmiş sezonda bulunan bir pattern eşleşmesini temsil eder.
 *
 * matchType: "İDEAL" → maçlar sıralı eşleşti
 *            "HAVUZ" → maçlar sırasız eşleşti
 *
 * patternMatches: eşleşen penceredeki N maç (kronolojik sıra)
 * targetMatch   : penceredeki son maç (pattern'in "aradığı" 3./4./5. maç)
 */
public class MatchResult {

    // ─── Eşleşme tipi ────────────────────────────────────────────────────────
    public String matchType;           // "İDEAL" veya "HAVUZ"
    public String season;

    // ─── Penceredeki tüm maçlar (N adet) ─────────────────────────────────────
    public final List<PatternMatch> patternMatches = new ArrayList<>();

    // ─── Pencere öncesi ve sonrası maçlar ────────────────────────────────────
    public String previousMatchLine;   // "HomeTeam X-Y AwayTeam (HT: ...)"
    public String nextMatchLine;       // "HomeTeam X-Y AwayTeam (HT: ...)"

    // ─── Orijinal pattern referansı ──────────────────────────────────────────
    public MatchPattern originalPattern;

    // ─── Eski compat alanlar (kullanılmaya devam edilebilir) ─────────────────
    public String homeTeam, awayTeam, score;   // penceredeki 1. maç
    public String firstMatchHTScore;
    public String secondMatchHomeTeam, secondMatchScore,
            secondMatchAwayTeam, secondMatchHTScore;
    public String thirdMatchHomeTeam, thirdMatchScore,
            thirdMatchAwayTeam, thirdMatchHTScore;
    public String previousMatchScore, previousHTScore;
    public String nextMatchScore, nextHTScore;
    public String middleHomeTeam, middleAwayTeam, middleScore, middleHTScore;

    // ─── İç sınıf: Tek bir maçın bilgisi ─────────────────────────────────────
    public static class PatternMatch {
        public final String homeTeam;
        public final String awayTeam;
        public final String score;
        public final String htScore;    // null olabilir
        public final String comeback;   // null, "2/1" veya "1/2"

        public PatternMatch(String homeTeam, String awayTeam,
                            String score, String htScore, String comeback) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.score    = score;
            this.htScore  = htScore;
            this.comeback = comeback;
        }

        public String toLine() {
            String ht = htScore != null ? htScore : "N/A";
            String cb = comeback != null ? " 🔥" + comeback : "";
            return homeTeam + " " + score + " " + awayTeam
                    + " (HT: " + ht + cb + ")";
        }
    }

    // ─── Kurucu ──────────────────────────────────────────────────────────────
    public MatchResult(String season, String matchType, MatchPattern originalPattern) {
        this.season          = season;
        this.matchType       = matchType;
        this.originalPattern = originalPattern;
    }

    // ─── toString ────────────────────────────────────────────────────────────
    @Override
    public String toString() {
        String header = "İDEAL".equals(matchType)
                ? "★  İDEAL EŞLEŞME"
                : "◈  HAVUZ EŞLEŞME";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s]  %s  —  %s Sezonu\n",
                header, originalPattern.teamName, season));
        sb.append("  ─────────────────────────────────────────\n");

        // Önceki maç
        sb.append(String.format("  %-14s-> %s\n", "önceki maç",
                nvl(previousMatchLine)));

        // Pattern maçları
        for (int i = 0; i < patternMatches.size(); i++) {
            PatternMatch pm = patternMatches.get(i);
            boolean isLast = (i == patternMatches.size() - 1);
            String label = String.format("%d. pat. maç", i + 1);
            String line  = pm.toLine() + (isLast ? "   ◄ SONUÇ" : "");
            sb.append(String.format("  %-14s-> %s\n", label, line));
        }

        // Sonraki maç
        sb.append(String.format("  %-14s-> %s\n", "sonraki maç",
                nvl(nextMatchLine)));

        return sb.toString().stripTrailing();
    }

    private String nvl(String s) { return s != null ? s : "Bilgi Yok"; }

    /** Herhangi bir maçta comeback var mı? */
    public boolean hasComeback() {
        return patternMatches.stream()
                .anyMatch(pm -> pm.comeback != null);
    }
}