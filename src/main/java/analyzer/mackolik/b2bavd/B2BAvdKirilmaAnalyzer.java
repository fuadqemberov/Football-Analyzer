package analyzer.mackolik.b2bavd;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * B2B &amp; AVD BAĞLANTISI - KIRILMA NOKTALARI ANALİZÖRÜ
 *
 * 2/1 TANIMI (takım perspektifinden):
 *   - 2/1 KAZANMAK (ALMA): takım ilk yarıyı GERİDE kapatır, maçı KAZANIR (comeback).
 *   - 2/1 KAYBETMEK (VERME): takım ilk yarıyı ÖNDE kapatır, maçı KAYBEDER.
 *
 * AVD (Alma-Verme Dengesi):
 *   Sezon içinde her ALMA bir VERME ile dengelenir (ve tersi). Sayılar eşit değilse
 *   takımın "AVsi açıktır" (devam eder) → dengeyi tamamlayacak yeni bir 2/1 beklenir.
 *   Sayılar eşitse AVD tamamlanmıştır; son maçı 2/1 biten ve dengesi kapanan takım
 *   ("Aralıklı AVD") uzun süre tekrar 2/1 çıkarmaz.
 *
 * ANAHTAR FAKTÖRLER:
 *   1. Bir takımın üst üste (B2B) 2/1 KAZANMA ihtimali çok düşüktür.
 *   2. Sezonda 2/1 kazanan her takım, aynı sezonda verisi olan bir takıma 2/1 kaybeder
 *      (döngü; "glitch" hariç daima gerçekleşir).
 *   3. Son maçı 2/1 biten takımın sonraki maçı (B2B fikstürü) incelenir; bağlantının
 *      tamamlanıp tamamlanmayacağını rakibin AVD'si, güç dengesi ve form belirler.
 *
 * KIRILMA NOKTALARI (son maçı 2/1 kazanılan/kaybedilen takımın B2B fikstürü için):
 *   K1. Rakibin AVD'si açık (AVsi devam eden takım).
 *   K2. İki takımın da bu sezon (farklı ekiplerle) 2/1 teması var.
 *   K3. Kendi aralarında bu sezon 2/1 sonuçlanan maç var.
 *   K4. Son maçı 2/1 KAZANAN takımın rakibi kendinden GÜÇLÜ, daha FORMDA ve AVsi
 *       açıksa → bu maçı 2/1 KAYBETME kırılması.
 *   K5. Son maçı 2/1 KAYBEDEN takımın rakibi kendinden GÜÇSÜZ ise ve diğer şartlar
 *       sağlanıyorsa → bu maçı 2/1 KAZANMA kırılması.
 *
 * Akış:
 *   1. Günün başlamamış maçları (ev ID, dep ID) çiftleri olarak alınır
 *      (vd.mackolik.com/livedata) veya argümanla "evId depId [evId depId ...]" verilir.
 *   2. Her iki takımın güncel sezon LİG fikstürü çekilir, 2/1 profili çıkarılır:
 *      alma/verme listesi, AVD durumu, güç (maç başı puan) ve form (son 5 maç puanı).
 *   3. Son lig maçı 2/1 biten takım(lar) aday olur; rakibiyle kırılma noktaları
 *      kontrol edilir, en az bir kırılma varsa SİNYAL basılır.
 *
 * Kullanım:
 *   java ... B2BAvdKirilmaAnalyzer               → günün başlamamış maç çiftleri
 *   java ... B2BAvdKirilmaAnalyzer 5088 2029     → yalnız verilen (ev, dep) çifti
 */
public class B2BAvdKirilmaAnalyzer {

    // ─── AYARLAR ────────────────────────────────────────────────────────────
    private static final String BASE_URL     = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    /** Yeni futbol sezonunun başladığı ay (Temmuz). */
    private static final int SEASON_START_MONTH = 7;

    /** Güncel sezonun başlangıç yılı sistem tarihinden dinamik hesaplanır. */
    private static final int CURRENT_SEASON_START_YEAR = computeCurrentSeasonStartYear();
    /** Güncel sezon "yyyy/yyyy" (ör. "2026/2027"). */
    private static final String CURRENT_SEASON = CURRENT_SEASON_START_YEAR + "/" + (CURRENT_SEASON_START_YEAR + 1);

    /** Form hesabında bakılan son maç sayısı. */
    private static final int FORM_MAC_SAYISI = 5;

    /** Güç kıyasında "daha güçlü" saymak için gereken maç başı puan farkı. */
    private static final double GUC_FARK_ESIGI = 0.25;

    /** Form kıyasında "daha formda" saymak için gereken puan farkı (son 5 maç). */
    private static final int FORM_FARK_ESIGI = 2;

    private static final int NUM_THREADS = 10;

    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    // ─── MAIN ───────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  B2B & AVD BAĞLANTISI - KIRILMA NOKTALARI             ║");
        System.out.println("║  2/1 ALMA : İY geride → maçı kazanır (comeback)       ║");
        System.out.println("║  2/1 VERME: İY önde   → maçı kaybeder                 ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        long globalStart = System.currentTimeMillis();
        System.out.printf("📅 Güncel sezon: %s%n%n", CURRENT_SEASON);

        List<int[]> ciftler = new ArrayList<>();
        if (args != null && args.length >= 2) {
            for (int i = 0; i + 1 < args.length; i += 2) {
                try {
                    ciftler.add(new int[]{Integer.parseInt(args[i].trim()), Integer.parseInt(args[i + 1].trim())});
                } catch (NumberFormatException e) {
                    System.err.println("   ❌ Geçersiz ID çifti: " + args[i] + " " + args[i + 1]);
                }
            }
        } else {
            System.out.println("🔄 Günün başlamamış maç çiftleri alınıyor...");
            ciftler = fetchUnstartedMatchPairs();
            System.out.println("✅ " + ciftler.size() + " maç çifti bulundu\n");
        }

        if (ciftler.isEmpty()) {
            System.out.println("❌ Analiz edilecek maç yok, program sonlandırılıyor.");
            return;
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);
        CloseableHttpClient http = HttpClients.custom().setConnectionManager(cm).build();

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (int[] cift : ciftler) {
            int evId = cift[0], depId = cift[1];
            futures.add(executor.submit((Callable<String>) () -> {
                try {
                    return analyzePair(http, evId, depId);
                } catch (Exception e) {
                    System.err.println("   ❌ [" + evId + " vs " + depId + "] Hata: " + e.getMessage());
                    return null;
                }
            }));
        }

        int processed = 0, signalCount = 0;
        for (Future<String> f : futures) {
            try {
                String result = f.get(5, TimeUnit.MINUTES);
                processed++;
                if (result != null && !result.isEmpty()) {
                    signalCount++;
                    System.out.println(result);
                }
                System.out.printf("\r⏳ İlerleme: %d/%d  |  Sinyal: %d", processed, futures.size(), signalCount);
            } catch (Exception e) {
                processed++;
                System.err.println("\n   ❌ Görev hatası: " + e.getMessage());
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            http.close();
        } catch (IOException ignored) {
        }

        long sure = (System.currentTimeMillis() - globalStart) / 1000;
        System.out.println("\n\n════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI | %d maç | %d kırılma sinyali | %ds%n", processed, signalCount, sure);
        System.out.println("════════════════════════════════════════════════════════\n");

        System.exit(0);
    }

    // ─── GÜNÜN MAÇ ÇİFTLERİ ─────────────────────────────────────────────────
    /** Başlamamış maçları (evId, depId) çiftleri olarak döndürür. */
    /**
     * Günün başlamamış maç çiftleri. Eskiden burada {@code livedata?group=0} kendi
     * deseniyle okunuyordu; o adres günün programı değil canlı maç tahtası olduğu için
     * sabah saatlerinde HİÇ başlamamış maç döndürmüyordu. Ortak okuma artık
     * {@link TeamIdsFetcher} içinde ({@code group=all} + tarih süzgeci).
     */
    static List<int[]> fetchUnstartedMatchPairs() {
        return TeamIdsFetcher.fetchUnstartedMatchPairs();
    }

    // ─── ANA ANALİZ ─────────────────────────────────────────────────────────
    /** AllInOneTactics ucun tek oyunluq giris noktasi; signal yoxdursa null. */
    public static String analyzeSinglePair(CloseableHttpClient http, int evId, int depId) {
        try {
            return analyzePair(http, evId, depId);
        } catch (Exception e) {
            return null;
        }
    }

    static String analyzePair(CloseableHttpClient http, int evId, int depId) throws IOException {
        TakimProfili ev  = buildProfil(http, evId);
        TakimProfili dep = buildProfil(http, depId);
        if (ev == null || dep == null) return null;

        StringBuilder out = new StringBuilder();

        // Son lig maçı 2/1 biten takım(lar) aday: her iki yön de kontrol edilir.
        String s1 = kirilmaKontrol(ev, dep, true);
        if (s1 != null) out.append(s1);
        String s2 = kirilmaKontrol(dep, ev, false);
        if (s2 != null) out.append(s2);

        return out.length() == 0 ? null : out.toString();
    }

    /**
     * "aday" son maçı 2/1 biten takımdır; B2B fikstüründeki rakibiyle kırılma
     * noktaları kontrol edilir. Kırılma yoksa null döner.
     */
    private static String kirilmaKontrol(TakimProfili aday, TakimProfili rakip, boolean adayEvSahibi) {
        MacYonu son = aday.sonMacYonu();
        if (son == MacYonu.YOK) return null;   // son maç 2/1 değil → B2B bağlantısı yok

        boolean sonAldi  = son == MacYonu.ALMA;    // son maçı 2/1 KAZANDI
        boolean sonVerdi = son == MacYonu.VERME;   // son maçı 2/1 KAYBETTİ

        List<String> kirilmalar = new ArrayList<>();

        // K1: rakibin AVD'si açık mı?
        if (rakip.avdAcik()) {
            kirilmalar.add(String.format("K1  Rakip %s AVD'si AÇIK (%s)", rakip.name, rakip.avdDurum()));
        }

        // K2: iki takımın da bu sezon 2/1 teması var mı?
        if (aday.temasVar() && rakip.temasVar()) {
            kirilmalar.add(String.format("K2  İki takımın da sezon içi 2/1 teması var (%s: %d, %s: %d)",
                    aday.name, aday.temasSayisi(), rakip.name, rakip.temasSayisi()));
        }

        // K3: kendi aralarında bu sezon 2/1 sonuçlanan maç var mı?
        String h2h = aday.ikiliTemas(rakip.name);
        if (h2h != null) {
            kirilmalar.add("K3  Kendi aralarında sezon içi 2/1 maçı: " + h2h);
        }

        // K4/K5: güç + form dengesizliği
        boolean rakipGuclu  = rakip.guc() - aday.guc() >= GUC_FARK_ESIGI;
        boolean rakipZayif  = aday.guc() - rakip.guc() >= GUC_FARK_ESIGI;
        boolean rakipFormda = rakip.form() - aday.form() >= FORM_FARK_ESIGI;

        String tahmin;
        if (sonAldi && rakipGuclu && rakipFormda && rakip.avdAcik()) {
            kirilmalar.add(String.format(
                    "K4  Son maçını 2/1 KAZANAN %s, kendinden güçlü + formda + AVsi açık %s ile oynuyor",
                    aday.name, rakip.name));
            tahmin = String.format("%s bu maçı 2/1 KAYBEDER (İY %s önde, FT %s kazanır) → İY/MS %s",
                    aday.name, aday.name, rakip.name, adayEvSahibi ? "1/2" : "2/1");
        } else if (sonVerdi && rakipZayif && !kirilmalar.isEmpty()) {
            kirilmalar.add(String.format(
                    "K5  Son maçını 2/1 KAYBEDEN %s, kendinden güçsüz %s ile oynuyor (diğer şartlar tamam)",
                    aday.name, rakip.name));
            tahmin = String.format("%s bu maçı 2/1 KAZANIR (İY geride, FT kazanır) → İY/MS %s",
                    aday.name, adayEvSahibi ? "2/1" : "1/2");
        } else if (sonAldi) {
            // Anahtar faktör 1: üst üste 2/1 kazanma ihtimali çok düşük
            tahmin = String.format("%s tekrar 2/1 KAZANMAZ (B2B 2/1 alma ihtimali çok düşük) → izle",
                    aday.name);
        } else {
            tahmin = "Yön belirsiz → kırılma noktalarını izle";
        }

        if (kirilmalar.isEmpty()) return null;

        // Aralıklı AVD: son 2/1 ile denge kapandıysa uzun süre tekrar 2/1 beklenmez
        boolean aralikliAvdTamam = !aday.avdAcik();

        return buildReport(aday, rakip, adayEvSahibi, son, kirilmalar, tahmin, aralikliAvdTamam);
    }

    // ─── RAPOR ──────────────────────────────────────────────────────────────
    private static String buildReport(TakimProfili aday, TakimProfili rakip, boolean adayEvSahibi,
                                      MacYonu son, List<String> kirilmalar, String tahmin,
                                      boolean aralikliAvdTamam) {
        String evAdi  = adayEvSahibi ? aday.name : rakip.name;
        String depAdi = adayEvSahibi ? rakip.name : aday.name;

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n╔════════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║ 🎯 B2B & AVD KIRILMA SİNYALİ: %s vs %s%n", evAdi, depAdi));
        sb.append(String.format("║    Aday: %s [ID:%d] → son lig maçı 2/1 %s%n",
                aday.name, aday.teamId, son == MacYonu.ALMA ? "KAZANDI (ALMA)" : "KAYBETTİ (VERME)"));
        sb.append(String.format("║    Son maç: %s%n", aday.sonMacOzeti()));
        sb.append("╠════════════════════════════════════════════════════════════════════╣\n");

        sb.append(String.format("║    %s  → AVD: %s | güç: %.2f puan/maç | form(son %d): %d puan%n",
                aday.name, aday.avdDurum(), aday.guc(), FORM_MAC_SAYISI, aday.form()));
        sb.append(String.format("║    %s  → AVD: %s | güç: %.2f puan/maç | form(son %d): %d puan%n",
                rakip.name, rakip.avdDurum(), rakip.guc(), FORM_MAC_SAYISI, rakip.form()));

        sb.append("║    ────────────────────────────────────────────────────────────────\n");
        sb.append("║    KIRILMA NOKTALARI:\n");
        for (String k : kirilmalar) {
            sb.append("║      ⭐ ").append(k).append("\n");
        }

        sb.append("║    ────────────────────────────────────────────────────────────────\n");
        sb.append("║    SEZON 2/1 KANITLARI:\n");
        appendTemaslar(sb, aday);
        appendTemaslar(sb, rakip);

        if (aralikliAvdTamam) {
            sb.append("║    ⚠️  Aralıklı AVD TAMAMLANDI: ").append(aday.name)
              .append(" dengede → uzun süre tekrar 2/1 çıkarmayabilir\n");
        }

        String guc = kirilmalar.size() >= 3 ? "ÇOK GÜÇLÜ"
                   : kirilmalar.size() == 2 ? "GÜÇLÜ"
                   : "ORTA";
        sb.append(String.format("║    Güç   : %s (%d kırılma noktası)%n", guc, kirilmalar.size()));
        sb.append(String.format("║    ⁉️  TAHMİN: %s%n", tahmin));
        sb.append("╚════════════════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    private static void appendTemaslar(StringBuilder sb, TakimProfili t) {
        if (t.temaslar.isEmpty()) {
            sb.append(String.format("║      %s: sezon içi 2/1 teması yok%n", t.name));
            return;
        }
        for (Temas temas : t.temaslar) {
            sb.append(String.format("║      %s: %s%n", t.name, temas.ozet()));
        }
    }

    // ─── TAKIM PROFİLİ ──────────────────────────────────────────────────────
    /** Takımın son maçının 2/1 yönü. */
    enum MacYonu { ALMA, VERME, YOK }

    /** Sezon içi tek bir 2/1 teması (alma veya verme). */
    static class Temas {
        final MacYonu yon;
        final MacData mac;
        final String rakip;

        Temas(MacYonu yon, MacData mac, String rakip) {
            this.yon   = yon;
            this.mac   = mac;
            this.rakip = rakip;
        }

        String ozet() {
            return String.format("%s vs %s → %s (İY %s) [%s]",
                    mac.homeTeam, mac.awayTeam, mac.ftScore, mac.htScore,
                    yon == MacYonu.ALMA ? "2/1 ALDI" : "2/1 VERDİ");
        }
    }

    static class TakimProfili {
        final int teamId;
        final String name;
        final List<MacData> matches;       // güncel sezon lig maçları
        final List<Temas> temaslar = new ArrayList<>();

        int almaCount = 0;
        int vermeCount = 0;
        int oynananSayisi = 0;
        int toplamPuan = 0;
        int sonFormPuan = 0;               // son FORM_MAC_SAYISI maçtaki puan
        MacData sonMac = null;             // son oynanan lig maçı
        MacYonu sonYon = MacYonu.YOK;

        TakimProfili(int teamId, String name, List<MacData> matches) {
            this.teamId  = teamId;
            this.name    = name;
            this.matches = matches;
            hesapla();
        }

        private void hesapla() {
            List<Integer> puanlar = new ArrayList<>();
            for (MacData m : matches) {
                if (!m.played) continue;
                oynananSayisi++;
                sonMac = m;

                boolean evde = teamsMatch(m.homeTeam, name);
                int[] ft = parseScore(m.ftScore);
                if (ft == null) continue;
                int biz = evde ? ft[0] : ft[1];
                int o   = evde ? ft[1] : ft[0];
                int puan = biz > o ? 3 : biz == o ? 1 : 0;
                toplamPuan += puan;
                puanlar.add(puan);

                MacYonu yon = macYonu(m, evde);
                sonYon = yon;   // her oynanan maçta güncellenir → en son maçın yönü kalır
                if (yon != MacYonu.YOK) {
                    if (yon == MacYonu.ALMA) almaCount++; else vermeCount++;
                    temaslar.add(new Temas(yon, m, evde ? m.awayTeam : m.homeTeam));
                }
            }
            int from = Math.max(0, puanlar.size() - FORM_MAC_SAYISI);
            for (int i = from; i < puanlar.size(); i++) sonFormPuan += puanlar.get(i);
        }

        /** İY/FT skorlarından takım perspektifiyle 2/1 yönü. */
        private MacYonu macYonu(MacData m, boolean evde) {
            int[] ht = parseScore(m.htScore);
            int[] ft = parseScore(m.ftScore);
            if (ht == null || ft == null) return MacYonu.YOK;
            int htBiz = evde ? ht[0] : ht[1], htO = evde ? ht[1] : ht[0];
            int ftBiz = evde ? ft[0] : ft[1], ftO = evde ? ft[1] : ft[0];
            if (htBiz < htO && ftBiz > ftO) return MacYonu.ALMA;    // İY geride → kazandı
            if (htBiz > htO && ftBiz < ftO) return MacYonu.VERME;   // İY önde  → kaybetti
            return MacYonu.YOK;
        }

        MacYonu sonMacYonu()   { return sonYon; }
        boolean temasVar()     { return !temaslar.isEmpty(); }
        int temasSayisi()      { return temaslar.size(); }
        boolean avdAcik()      { return almaCount != vermeCount; }

        String avdDurum() {
            if (!avdAcik()) return String.format("DENGEDE (%d alma / %d verme)", almaCount, vermeCount);
            return almaCount > vermeCount
                    ? String.format("AÇIK - VERME bekliyor (%d alma / %d verme)", almaCount, vermeCount)
                    : String.format("AÇIK - ALMA bekliyor (%d alma / %d verme)", almaCount, vermeCount);
        }

        /** Maç başı puan → güç göstergesi. */
        double guc() { return oynananSayisi == 0 ? 0 : (double) toplamPuan / oynananSayisi; }

        int form() { return sonFormPuan; }

        String sonMacOzeti() {
            if (sonMac == null) return "-";
            return String.format("%s %s %s (İY %s)",
                    sonMac.homeTeam, sonMac.ftScore, sonMac.awayTeam,
                    sonMac.htScore != null ? sonMac.htScore : "?");
        }

        /** Bu sezon rakiple oynanan maçlardan 2/1 biteni varsa özetini döndürür. */
        String ikiliTemas(String rakipAdi) {
            for (Temas t : temaslar) {
                if (teamsMatch(t.rakip, rakipAdi)) return t.ozet();
            }
            return null;
        }
    }

    private static TakimProfili buildProfil(CloseableHttpClient http, int teamId) throws IOException {
        List<MacData> matches = fetchSeasonMatches(http, teamId, CURRENT_SEASON);
        if (matches.isEmpty()) return null;
        String name = detectTeamNameFromRows(matches);
        if (name == null) return null;
        TakimProfili p = new TakimProfili(teamId, name, matches);
        return p.oynananSayisi == 0 ? null : p;
    }

    // ─── FİKSTÜR ÇEKME / PARSE ──────────────────────────────────────────────
    static class MacData {
        String homeTeam;
        String awayTeam;
        String ftScore;   // oynanmadıysa null
        String htScore;   // yoksa null
        boolean played;
    }

    private static List<MacData> fetchSeasonMatches(CloseableHttpClient http,
                                                    int teamId, String season) throws IOException {
        List<MacData> result = new ArrayList<>();
        String html = fetchHtml(http, String.format(BASE_URL, teamId, season));
        if (html == null) return result;

        Document doc = Jsoup.parse(html);
        Element tbody = doc.selectFirst("#tblFixture > tbody");
        if (tbody == null) return result;

        // Sadece İLK turnuva bloğu (lig) — kupa/hazırlık maçları AVD hesabını bozar
        List<Element> rows = new ArrayList<>();
        boolean inFirstLeague = false;
        for (Element row : tbody.select("tr")) {
            if (row.hasClass("competition")) {
                if (!inFirstLeague) {
                    inFirstLeague = true;
                    continue;
                }
                break;
            }
            if (inFirstLeague) rows.add(row);
        }
        if (rows.isEmpty()) {
            for (Element row : tbody.select("tr")) {
                String home = extractCell(row, "td:nth-child(3)");
                if (home != null && !home.isEmpty()) rows.add(row);
            }
        }

        for (Element row : rows) {
            String home = extractCell(row, "td:nth-child(3)");
            String away = extractCell(row, "td:nth-child(7)");
            if (home == null || home.isEmpty() || away == null || away.isEmpty()) continue;

            MacData md = new MacData();
            md.homeTeam = home;
            md.awayTeam = away;

            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            String score = scoreEl != null ? scoreEl.text().trim() : "";
            int[] ft = parseScore(score);
            if (ft != null) {
                md.played  = true;
                md.ftScore = score.replaceAll("\\s*-\\s*", "-");
                String ht = extractCell(row, "td:nth-child(9)");
                if (ht != null && !ht.isEmpty() && parseScore(ht) != null) {
                    md.htScore = ht.replaceAll("\\s*-\\s*", "-");
                }
            }
            result.add(md);
        }
        return result;
    }

    private static String fetchHtml(CloseableHttpClient http, String url) throws IOException {
        HttpGet req = new HttpGet(url);
        req.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/91.0 Safari/537.36");
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(10000)
                .setSocketTimeout(15000)
                .build();
        req.setConfig(config);

        try (CloseableHttpResponse resp = http.execute(req)) {
            if (resp.getStatusLine().getStatusCode() == 200) {
                return EntityUtils.toString(resp.getEntity());
            }
            return null;
        }
    }

    private static String extractCell(Element row, String cssSelector) {
        Element el = row.selectFirst(cssSelector);
        return el != null ? el.text().trim() : null;
    }

    static int[] parseScore(String score) {
        if (score == null) return null;
        String[] parts = score.replaceAll("\\s*-\\s*", "-").split("-");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ─── TAKIM ADI TESPİTİ / EŞLEŞTİRME ────────────────────────────────────
    /** Fikstür satırlarında en sık geçen isim bizim takımdır (her maçta görünür). */
    private static String detectTeamNameFromRows(List<MacData> matches) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (MacData m : matches) {
            freq.merge(m.homeTeam, 1, Integer::sum);
            freq.merge(m.awayTeam, 1, Integer::sum);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best != null && bestCount >= Math.max(1, matches.size() / 2)) return best;
        return null;
    }

    private static String normalize(String s) {
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

    /** Sezonlar arası kısaltma farklarına dayanıklı isim eşleştirme. */
    static boolean teamsMatch(String teamA, String teamB) {
        if (teamA == null || teamB == null) return false;
        String a = normalize(teamA);
        String b = normalize(teamB);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;

        for (String tA : teamA.trim().split("\\s+")) {
            String nA = normalize(tA);
            if (nA.length() < 3) continue;
            for (String tB : teamB.trim().split("\\s+")) {
                String nB = normalize(tB);
                if (nB.length() < 3) continue;
                if (nA.equals(nB)) return true;
                if (nA.length() >= 4 && nB.startsWith(nA)) return true;
                if (nB.length() >= 4 && nA.startsWith(nB)) return true;
            }
        }
        return false;
    }
}
