package analyzer.mackolik.patternfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * N maçlık (3, 4 veya 5) bir pattern'i temsil eder.
 *
 * Maçlar kronolojik sıradadır:
 *   matches.get(0)  → en eski maç
 *   matches.get(N-1)→ en yeni maç (bitmiş ama "başlamamış sayılır")
 *
 * Her Match iç sınıfı: ev sahibi, deplasman, skor bilgisini taşır.
 */
public class MatchPattern {

    // ─── İç sınıf ────────────────────────────────────────────────────────────
    public static class Match {
        public final String homeTeam;
        public final String awayTeam;
        public final String score;   // Son maç için "???" olabilir

        public Match(String homeTeam, String awayTeam, String score) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.score    = score;
        }

        @Override
        public String toString() {
            return homeTeam + " vs " + awayTeam + " -> " + score;
        }
    }

    // ─── Alanlar ─────────────────────────────────────────────────────────────
    public final List<Match> matches;   // Kronolojik sıra: [0..N-1]
    public final String teamName;
    public final int size;              // 3, 4 veya 5

    // Eski kodla uyumluluk için kısa yollar ──────────────────────────────────
    public final String score1, score2;
    public final String homeTeam1, awayTeam1;
    public final String homeTeam2, awayTeam2;
    public final String nextHomeTeam, nextAwayTeam;
    public final String middleHomeTeam, middleAwayTeam; // compat

    // ─── Kurucu ──────────────────────────────────────────────────────────────
    public MatchPattern(List<Match> matches, String teamName) {
        if (matches == null || matches.size() < 3 || matches.size() > 5)
            throw new IllegalArgumentException("Pattern boyutu 3-5 arasında olmalı, verilen: "
                    + (matches == null ? "null" : matches.size()));

        this.matches  = new ArrayList<>(matches);
        this.teamName = teamName;
        this.size     = matches.size();

        // Eski compat alanlar (ilk 2 maç + son maç)
        Match m0 = matches.get(0);
        Match m1 = matches.get(1);
        Match mL = matches.get(matches.size() - 1);

        this.score1       = m0.score;
        this.score2       = m1.score;
        this.homeTeam1    = m0.homeTeam;
        this.awayTeam1    = m0.awayTeam;
        this.homeTeam2    = m1.homeTeam;
        this.awayTeam2    = m1.awayTeam;
        this.nextHomeTeam = mL.homeTeam;
        this.nextAwayTeam = mL.awayTeam;
        this.middleHomeTeam = mL.homeTeam;
        this.middleAwayTeam = mL.awayTeam;
    }

    // ─── Yardımcı ─────────────────────────────────────────────────────────────

    /** Pattern'deki tüm skoru aranacak maçlar (son maç hariç) */
    public List<String> getSearchScores() {
        List<String> scores = new ArrayList<>();
        for (int i = 0; i < size - 1; i++) {
            scores.add(matches.get(i).score);
        }
        return scores;
    }

    /** Pattern'deki tüm takım isimlerini döner */
    public Set<String> getAllTeams() {
        Set<String> teams = new HashSet<>();
        for (Match m : matches) {
            teams.add(m.homeTeam);
            teams.add(m.awayTeam);
        }
        return teams;
    }

    /** Son maç (bitmiş ama "başlamamış sayılan") */
    public Match getLastMatch() {
        return matches.get(size - 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            Match m = matches.get(i);
            if (i == size - 1) {
                sb.append(String.format("  Maç %d : %s vs %s -> ??? (aranan)\n",
                        i + 1, m.homeTeam, m.awayTeam));
            } else {
                sb.append(String.format("  Maç %d : %s vs %s -> %s\n",
                        i + 1, m.homeTeam, m.awayTeam, m.score));
            }
        }
        return sb.toString().stripTrailing();
    }
}