package analyzer.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mackolik'in GÜNLÜK PROGRAMINDAN henüz başlamamış maçları okur.
 * Tüm mackolik analizörlerinin ortak giriş noktasıdır.
 *
 * <h3>Neden {@code group=all}, {@code group=0} değil</h3>
 * {@code livedata?group=0} günün programı DEĞİL, canlı ve az önce bitmiş maçların
 * tahtasıdır. Akşam oynanacak maçlar sabah saatlerinde bu tahtaya henüz düşmez:
 * 23.08.2026 saat 11:00'de tahtada 838 maç vardı ve <b>hiçbiri</b> başlamamış
 * durumda değildi (hepsi 4/"MS", 3/canlı veya 9/"Ert."). Bu yüzden eski desen
 * hiçbir şey yakalamıyor, analizörler "0 maç" yazıp çıkıyordu. GitHub iş akışı
 * 06:30 UTC'de çalıştığı için tam da bu pencereye denk geliyordu.
 *
 * <p>{@code group=all} ise günün tamamını verir — aynı anda aynı tahtada 584
 * başlamamış maç. Ek olarak her satırda tarih, saat ve lig bloğu (ülke, lig adı,
 * seasonId, sezon etiketi) da bulunur.
 *
 * <h3>Tarih süzgeci</h3>
 * {@code group=all} bugünü VE yarını birlikte döndürür. Süzgeç olmadan yarınki
 * maçlar bugünkü gibi analiz edilir; bu yüzden varsayılan olarak yalnızca bugün
 * alınır ({@link #fetchUnstartedFixtures(String)} ile başka bir gün seçilebilir).
 *
 * <p>Satır düzeni:
 * {@code [matchId, evId, "evAd", depId, "depAd", status, "statusMetni", ...,
 * "19:00", 0, "1.30", "4.52", "5.40", "2.52", "1.29", ..., "23/08/2026",
 * [ülkeId, "Ülke", ligId, "Lig", seasonId, "2026/2027", ...]]}
 * — satır uzunluğu turnuvaya göre değiştiği için saat, tarih ve lig bloğu sabit
 * indisle değil, türlerine göre taranarak bulunur.
 */
public class TeamIdsFetcher {

    private static final String LIVEDATA_URL = "https://vd.mackolik.com/livedata?group=all";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** Başlamamış maçın durum kodu. */
    private static final int STATUS_UNSTARTED = 0;

    private static final Pattern DATE_TEXT = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    private static final Pattern TIME_TEXT = Pattern.compile("\\d{1,2}:\\d{2}");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private TeamIdsFetcher() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Model
    // ═══════════════════════════════════════════════════════════════════════

    /** Programdaki başlamamış bir maç. */
    public static final class Fixture {
        public final int matchId;
        public final int homeId;
        public final String homeName;
        public final int awayId;
        public final String awayName;
        /** "19:00"; bilinmiyorsa boş. */
        public final String kickoff;
        /** "23/08/2026"; bilinmiyorsa boş. */
        public final String dateText;
        /** "Türkiye · Süper Lig"; bilinmiyorsa boş. */
        public final String leagueName;
        /** Puan durumu / fikstür servislerinde kullanılan sezon kimliği; 0 = bilinmiyor. */
        public final int seasonId;
        /** "2026/2027" ya da takvim yılı ligleri için "2026". */
        public final String seasonLabel;

        Fixture(int matchId, int homeId, String homeName, int awayId, String awayName,
                String kickoff, String dateText, String leagueName, int seasonId, String seasonLabel) {
            this.matchId     = matchId;
            this.homeId      = homeId;
            this.homeName    = homeName;
            this.awayId      = awayId;
            this.awayName    = awayName;
            this.kickoff     = kickoff;
            this.dateText    = dateText;
            this.leagueName  = leagueName;
            this.seasonId    = seasonId;
            this.seasonLabel = seasonLabel;
        }

        @Override
        public String toString() {
            return homeName + " (" + homeId + ") vs " + awayName + " (" + awayId + ")"
                    + (leagueName.isEmpty() ? "" : " | " + leagueName)
                    + (kickoff.isEmpty() ? "" : " " + dateText + " " + kickoff);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Genel API
    // ═══════════════════════════════════════════════════════════════════════

    /** Bugünün başlamamış maçları. */
    public static List<Fixture> fetchUnstartedFixtures() {
        return fetchUnstartedFixtures(LocalDate.now().format(DAY_FORMAT));
    }

    /**
     * Başlamamış maçlar.
     *
     * @param day "dd/MM/yyyy"; <b>null</b> verilirse feed'in döndürdüğü tüm günler
     *            (bugün + yarın) alınır
     */
    public static List<Fixture> fetchUnstartedFixtures(String day) {
        List<Fixture> fixtures = new ArrayList<>();

        String body = fetchBody();
        if (body == null) return fixtures;

        JSONArray rows;
        try {
            rows = new JSONObject(body).optJSONArray("m");
        } catch (RuntimeException e) {
            System.err.println("❌ livedata ayrıştırılamadı: " + e.getMessage());
            return fixtures;
        }
        if (rows == null) {
            System.err.println("❌ livedata cevabında \"m\" dizisi yok.");
            return fixtures;
        }

        int unstarted = 0;
        for (int i = 0; i < rows.length(); i++) {
            Fixture fixture = toFixture(rows.optJSONArray(i));
            if (fixture == null) continue;
            unstarted++;
            if (day == null || day.equals(fixture.dateText)) fixtures.add(fixture);
        }

        System.out.println("Programda " + unstarted + " başlamamış maç var; "
                + (day == null ? "tüm günler" : day) + " için " + fixtures.size() + " maç alındı.");
        return fixtures;
    }

    /**
     * Bugünün başlamamış maçlarındaki benzersiz takım ID'leri.
     * (Eski davranışla aynı imza — mevcut analizörler değişmeden çalışır.)
     */
    public static List<String> fetchUnstartedTeamIds() {
        Set<String> teamIds = new LinkedHashSet<>();
        for (Fixture fixture : fetchUnstartedFixtures()) {
            teamIds.add(String.valueOf(fixture.homeId));
            teamIds.add(String.valueOf(fixture.awayId));
        }
        System.out.println("Başlamamış maçlardan toplam " + teamIds.size() + " adet benzersiz Takım ID'si alındı.");
        return new ArrayList<>(teamIds);
    }

    /** Bugünün başlamamış maçları, { evId, depId } çiftleri olarak. */
    public static List<int[]> fetchUnstartedMatchPairs() {
        List<int[]> pairs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Fixture fixture : fetchUnstartedFixtures()) {
            if (seen.add(fixture.homeId + "-" + fixture.awayId)) {
                pairs.add(new int[]{fixture.homeId, fixture.awayId});
            }
        }
        return pairs;
    }

    /** Bugünün başlamamış maçlarının maç ID'leri (Head2Head / Mac sayfaları için). */
    public static List<String> fetchUnstartedMatchIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Fixture fixture : fetchUnstartedFixtures()) {
            if (fixture.matchId > 0) ids.add(String.valueOf(fixture.matchId));
        }
        return new ArrayList<>(ids);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ayrıştırma
    // ═══════════════════════════════════════════════════════════════════════

    private static Fixture toFixture(JSONArray row) {
        if (row == null || row.length() < 7) return null;
        if (row.optInt(5, -1) != STATUS_UNSTARTED) return null;

        int matchId = row.optInt(0, 0);
        int homeId  = row.optInt(1, 0);
        int awayId  = row.optInt(3, 0);
        if (homeId <= 0 || awayId <= 0) return null;

        String homeName = row.optString(2, "");
        String awayName = row.optString(4, "");
        if (homeName.isEmpty() || awayName.isEmpty()) return null;

        String kickoff  = "";
        String dateText = "";
        JSONArray league = null;

        for (int i = 6; i < row.length(); i++) {
            Object value = row.opt(i);
            if (value instanceof JSONArray array) {
                if (league == null && array.length() >= 6) league = array;
            } else if (value instanceof String text) {
                if (dateText.isEmpty() && DATE_TEXT.matcher(text).matches()) dateText = text;
                else if (kickoff.isEmpty() && TIME_TEXT.matcher(text).matches()) kickoff = text;
            }
        }

        String leagueName  = "";
        int seasonId       = 0;
        String seasonLabel = "";
        if (league != null) {
            String country = league.optString(1, "");
            String name    = league.optString(3, "");
            leagueName  = country.isEmpty() ? name : country + " · " + name;
            seasonId    = league.optInt(4, 0);
            seasonLabel = league.optString(5, "");
        }

        return new Fixture(matchId, homeId, homeName, awayId, awayName,
                kickoff, dateText, leagueName, seasonId, seasonLabel);
    }

    private static String fetchBody() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LIVEDATA_URL))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("❌ livedata HTTP " + response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("❌ API Hatası: istek kesildi");
            return null;
        } catch (Exception e) {
            System.err.println("❌ API Hatası: " + e.getMessage());
            return null;
        }
    }
}
