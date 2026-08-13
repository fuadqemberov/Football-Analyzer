package analyzer.flashscore;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class DatabaseService {

    // Kendi veritabanı bilgilerinizi buraya girin
    private static final String URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String USER = "postgres";
    private static final String PASSWORD = "fuad123";

    // DB'deki tam 107 kolon listesi
    private static final String[] COLUMNS = {
            "code_kod", "ht_iy", "ft_ms", "home_team", "away_team",
            "ft_1_a", "ft_x_a", "ft_2_a", "first_1_a", "first_x_a", "first_2_a",
            "second_1_a", "second_x_a", "second_2_a", "bts_ft_yes_a", "bts_ft_no_a",
            "bts_first_yes_a", "bts_first_no_a", "bts_second_yes_a", "bts_second_no_a",
            "dbc_ft_1x_a", "dbc_ft_12_a", "dbc_ft_x2_a", "dbc_first_1x_a", "dbc_first_12_a", "dbc_first_x2_a",
            "ft_0_5_over_a", "ft_0_5_under_a", "ft_1_5_over_a", "ft_1_5_under_a", "ft_2_5_over_a", "ft_2_5_under_a",
            "ft_3_5_over_a", "ft_3_5_under_a", "ft_4_5_over_a", "ft_4_5_under_a", "ft_5_5_over_a", "ft_5_5_under_a",
            "first_0_5_over_a", "first_0_5_under_a", "first_1_5_over_a", "first_1_5_under_a", "first_2_5_over_a", "first_2_5_under_a",
            "second_0_5_over_a", "second_0_5_under_a", "second_1_5_over_a", "second_1_5_under_a", "second_2_5_over_a", "second_2_5_under_a",
            "ht_ft_11_a", "ht_ft_1x_a", "ht_ft_12_a", "ht_ft_x1_a", "ht_ft_xx_a", "ht_ft_x2_a", "ht_ft_21_a", "ht_ft_2x_a", "ht_ft_22_a",
            "first_score_1_0_a", "first_score_2_0_a", "first_score_2_1_a", "first_score_3_0_a", "first_score_3_1_a", "first_score_3_2_a",
            "first_score_0_0_a", "first_score_1_1_a", "first_score_2_2_a", "first_score_0_1_a", "first_score_0_2_a", "first_score_1_2_a",
            "first_score_0_3_a", "first_score_1_3_a", "first_score_2_3_a",
            "ft_score_1_0_a", "ft_score_2_0_a", "ft_score_2_1_a", "ft_score_3_0_a", "ft_score_3_1_a", "ft_score_3_2_a",
            "ft_score_4_0_a", "ft_score_4_1_a", "ft_score_4_2_a", "ft_score_4_3_a", "ft_score_5_0_a", "ft_score_5_1_a", "ft_score_5_2_a",
            "ft_score_0_0_a", "ft_score_1_1_a", "ft_score_2_2_a", "ft_score_3_3_a", "ft_score_4_4_a",
            "ft_score_0_1_a", "ft_score_0_2_a", "ft_score_1_2_a", "ft_score_0_3_a", "ft_score_1_3_a", "ft_score_2_3_a",
            "ft_score_0_4_a", "ft_score_1_4_a", "ft_score_2_4_a", "ft_score_3_4_a",
            "ft_score_0_5_a", "ft_score_1_5_a", "ft_score_2_5_a",
            "country_league", "date_time"
    };

    public static void insertToDatabase(List<MatchData> data) {
        if (data == null || data.isEmpty()) return;

        AppLogger.log("Veritabanı (PostgreSQL) insert işlemi başlatılıyor...");

        StringBuilder sqlBuilder = new StringBuilder("INSERT INTO bet365_matches (");
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) sqlBuilder.append(", ");
            sqlBuilder.append(COLUMNS[i]);
        }
        sqlBuilder.append(") VALUES (");
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) sqlBuilder.append(", ");
            sqlBuilder.append("?");
        }
        sqlBuilder.append(")");

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {

            conn.setAutoCommit(false);
            int count = 0;
            int batchSize = 1000;

            for (MatchData md : data) {
                // Sadece oranı olan maçları ekle
                if (md.oddsMap.isEmpty()) continue;

                // 1 ile 5 arası: Metin sütunları (İlk 5 kolon)
                pstmt.setString(1, md.matchId);
                pstmt.setString(2, md.htScore);
                pstmt.setString(3, md.ftScore);
                pstmt.setString(4, md.homeTeam);
                pstmt.setString(5, md.awayTeam);

                // 6. sıradan başlayıp oranları ekliyoruz
                int paramIndex = 6;
                for (ScraperConstants.ColumnDef def : ScraperConstants.COLUMN_DEFS) {
                    String val = md.oddsMap.getOrDefault(def.dataKey, "");

                    if (val.isEmpty() || val.equals("-")) {
                        pstmt.setNull(paramIndex, Types.NUMERIC);
                    } else {
                        try {
                            BigDecimal bd = new BigDecimal(val.replace(',', '.'));
                            pstmt.setBigDecimal(paramIndex, bd);
                        } catch (NumberFormatException e) {
                            pstmt.setNull(paramIndex, Types.NUMERIC);
                        }
                    }
                    paramIndex++;
                }

                // Oranlar bittikten sonra dinamik olarak sıradaki indexe Ülke ve Tarihi ekle (106 ve 107)
                pstmt.setString(paramIndex++, "");       // country_league
                pstmt.setString(paramIndex, md.date);    // date_time

                pstmt.addBatch();
                count++;

                // Her 1000 satırda bir veritabanına yaz
                if (count % batchSize == 0) {
                    pstmt.executeBatch();
                    conn.commit();
                }
            }

            // Kalan verileri yaz ve tamamla
            pstmt.executeBatch();
            conn.commit();
            AppLogger.log("✅ DB İŞLEMİ TAMAM! Toplam " + count + " satır veritabanına eklendi.");

        } catch (Exception e) {
            AppLogger.log("❌ DB HATA: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DB'deki en güncel maç tarihi ile bugün arasındaki gecikme.
     * Scraper her zaman "dün"den geriye doğru tarama yaptığı için ulaşılabilecek
     * en taze veri dünün verisidir; bu yüzden fark hesabından 1 gün düşülür.
     * daysBehind = 0 -> veri güncel.
     */
    public record DataFreshness(LocalDate latestDate, long daysBehind, String error) {

        public boolean isAvailable() {
            return error == null && latestDate != null;
        }

        public String message() {
            if (error != null) return "⚠ Veri güncellik kontrolü yapılamadı: " + error;
            if (latestDate == null) return "⚠ DB'de tarihi olan hiç maç yok.";
            String suffix = (daysBehind == 0)
                    ? "Veri güncel."
                    : "Veri " + daysBehind + " gün geride.";
            return "DB'deki en güncel tarih: " + latestDate
                    + " | Bugün: " + LocalDate.now()
                    + " | " + suffix;
        }
    }

    public static DataFreshness checkDataFreshness() {
        // date_time metin (varchar) kolon; boş ve bozuk kayıtlar var, sadece
        // yyyy-MM-dd ile başlayanları dikkate alıyoruz.
        String sql = "SELECT MAX(LEFT(date_time, 10)) FROM bet365_matches "
                + "WHERE date_time ~ '^\\d{4}-\\d{2}-\\d{2}'";

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            String maxDate = rs.next() ? rs.getString(1) : null;
            if (maxDate == null || maxDate.isBlank()) {
                return new DataFreshness(null, -1, null);
            }

            LocalDate latest = LocalDate.parse(maxDate);
            long behind = ChronoUnit.DAYS.between(latest, LocalDate.now()) - 1;
            if (behind < 0) behind = 0;
            return new DataFreshness(latest, behind, null);

        } catch (Exception e) {
            return new DataFreshness(null, -1, e.getMessage());
        }
    }
}