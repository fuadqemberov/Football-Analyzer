package analyzer.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AYNI EŞLEŞME (rövanş) İŞARETİ.
 *
 * Geçmiş sezondan gelen bir kanıt maçı, bugün oynanacak maçın TAM AYNI iki
 * takımından oluşuyorsa (ev/deplasman sırası önemli değil: X-Y ile Y-X aynı
 * sayılır) o satır 5 yıldızla işaretlenir. Böylece "bu desen rastgele bir
 * maçtan değil, bugünkü rakibin ta kendisinden geliyor" durumu gözden kaçmaz.
 *
 * İsim karşılaştırması sezonlar arasında yazım değiştiği için birebir değil,
 * TemasTakimiAnalyzer'daki ile aynı toleranslı kuralla yapılır.
 */
public final class AyniEslesme {

    /** İşaretli satırların sonuna eklenen damga. */
    public static final String YILDIZ = "⭐⭐⭐⭐⭐";

    private AyniEslesme() {}

    /**
     * Geçmiş maçın çifti, bugünkü maçın çifti ile aynı mı (sıra fark etmez).
     * Herhangi bir isim boş/null ise false.
     */
    public static boolean ayniCift(String gecmisEv, String gecmisDep,
                                   String bugunEv, String bugunDep) {
        if (gecmisEv == null || gecmisDep == null || bugunEv == null || bugunDep == null) return false;
        // Düz sıra: X-Y ↔ X-Y   |   ters sıra: X-Y ↔ Y-X (rövanş)
        return (teamsMatch(gecmisEv, bugunEv)  && teamsMatch(gecmisDep, bugunDep))
            || (teamsMatch(gecmisEv, bugunDep) && teamsMatch(gecmisDep, bugunEv));
    }

    /** Aynı çift ise " ⭐⭐⭐⭐⭐", değilse boş metin — format satırının sonuna eklenir. */
    public static String yildiz(String gecmisEv, String gecmisDep,
                                String bugunEv, String bugunDep) {
        return ayniCift(gecmisEv, gecmisDep, bugunEv, bugunDep) ? "  " + YILDIZ : "";
    }

    /** ID ile karşılaştırma (NowGoal gibi takım ID'si olan kaynaklar için). */
    public static boolean ayniCift(int gecmisEv, int gecmisDep, int bugunEv, int bugunDep) {
        return (gecmisEv == bugunEv && gecmisDep == bugunDep)
            || (gecmisEv == bugunDep && gecmisDep == bugunEv);
    }

    /** ID tabanlı yıldız damgası. */
    public static String yildiz(int gecmisEv, int gecmisDep, int bugunEv, int bugunDep) {
        return ayniCift(gecmisEv, gecmisDep, bugunEv, bugunDep) ? "  " + YILDIZ : "";
    }

    // ─── İsim eşleştirme (TemasTakimiAnalyzer ile aynı kural) ───────────────

    public static boolean teamsMatch(String teamA, String teamB) {
        if (teamA == null || teamB == null) return false;
        String a = normalize(teamA);
        String b = normalize(teamB);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;

        // Biri diğerinin içinde geçiyorsa: "inter" ⊂ "internazionale".
        // 5 karakterlik alt sınır, "man" gibi kısa parçaların her şeye uymasını engeller.
        if (Math.min(a.length(), b.length()) >= 5 && (a.contains(b) || b.contains(a))) return true;

        List<String> tokensA = nameTokens(teamA);
        List<String> tokensB = nameTokens(teamB);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false;

        List<String> shorter = tokensA.size() <= tokensB.size() ? tokensA : tokensB;
        List<String> longer  = (shorter == tokensA) ? tokensB : tokensA;

        for (String token : shorter) {
            boolean matched = false;
            for (String other : longer) {
                if (tokenMatches(token, other)) { matched = true; break; }
            }
            if (!matched) return false;
        }
        return true;
    }

    public static String normalize(String s) {
        if (s == null) return "";
        String ascii = s
                .replace("ı", "i").replace("İ", "i")
                .replace("ğ", "g").replace("Ğ", "g")
                .replace("ş", "s").replace("Ş", "s")
                .replace("ç", "c").replace("Ç", "c")
                .replace("ö", "o").replace("Ö", "o")
                .replace("ü", "u").replace("Ü", "u")
                .replace("é", "e").replace("á", "a")
                .replace("ó", "o").replace("ú", "u")
                .replace("ñ", "n").replace("ã", "a");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final Map<String, String> ISIM_KISALTMALARI = Map.of(
            "utd", "united",
            "wolves", "wolverhampton",
            "spurs", "tottenham");

    /** İsmi anlamlı kelimelere ayırır; 3 harften kısa parçalar ("AŞ", "JK") atılır. */
    private static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String part : name.trim().split("\s+")) {
            String normalized = normalize(part);
            if (normalized.length() < 3) continue;
            tokens.add(ISIM_KISALTMALARI.getOrDefault(normalized, normalized));
        }
        return tokens;
    }

    /** Aynı kelime mi: eşit ya da biri diğerinin başlangıcı ("man" → "manchester"). */
    private static boolean tokenMatches(String a, String b) {
        return a.equals(b) || b.startsWith(a) || a.startsWith(b);
    }
}
