package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class Bet365FilterAnalyzer {

    // ═══════════════════════════════════════════════════════════════════════
    // ✏️  BURAYA FİLTRELERİ EKLE / DEĞİŞTİR
    //     Her iç liste = bir filtre seti
    //     Kolon isimleri ALL_ODDS_COLS'daki displayName ile birebir eşleşmeli
    // ═══════════════════════════════════════════════════════════════════════
    private static final List<List<String>> FILTER_SETS = List.of(
            // RUN 2798
            List.of("A/U 5.5 Alt", "HT/FT 1/X", "A/U 2.5 Alt", "A/U 0.5 Üst", "MS Skor 3:3", "2Y A/U 1.5 Alt", "A/U 3.5 Üst"),
            // RUN 3008
            List.of("MS Skor 4:4", "İY Skor 2:3", "2Y 1", "A/U 5.5 Üst", "2Y A/U 1.5 Alt", "İY ÇŞ X2", "KG Hayır", "İY 2", "İY 1", "MS Skor 0:2", "MS Skor 1:1"),
            // RUN 3536
            List.of("İY Skor 0:1", "2Y 1", "MS Skor 0:3", "A/U 2.5 Üst", "MS Skor 2:2", "İY ÇŞ X2", "MS Skor 1:2", "İY Skor 3:0"),
            // RUN 4550
            List.of("MS Skor 2:1", "HT/FT 2/X", "İY Skor 2:1", "İY KG Evet", "MS Skor 4:1", "MS Skor 3:1", "MS Skor 4:4"),
            // RUN 5010
            List.of("MS Skor 2:3", "İY Skor 2:1", "İY KG Evet", "MS Skor 2:4", "HT/FT X/X"),
            // RUN 5895
            List.of("İY Skor 2:3", "MS Skor 4:4", "MS Skor 3:1", "İY Skor 0:3", "MS Skor 4:2", "MS Skor 3:2", "A/U 5.5 Alt"),
            // RUN 6667
            List.of("HT/FT 1/X", "2Y A/U 1.5 Üst", "HT/FT 2/1", "2Y 1", "KG Hayır", "MS Skor 1:1", "A/U 5.5 Alt"),
            // RUN 7131
            List.of("A/U 1.5 Alt", "İY Skor 2:2", "A/U 4.5 Alt", "HT/FT 1/X", "2Y A/U 1.5 Üst", "İY A/U 1.5 Üst", "A/U 4.5 Üst", "İY A/U 2.5 Üst", "A/U 2.5 Alt", "İY A/U 0.5 Üst", "İY A/U 2.5 Alt", "2Y A/U 0.5 Alt", "MS Skor 3:3"),
            // RUN 7330
            List.of("MS Skor 0:1", "MS Skor 0:2", "MS 2", "İY Skor 2:3", "2Y KG Hayır", "MS Skor 4:4"),
            // RUN 7840
            List.of("İY 2", "A/U 5.5 Alt", "HT/FT 2/X", "İY Skor 2:3", "MS Skor 1:3", "İY Skor 2:0", "A/U 3.5 Üst", "MS Skor 4:3", "İY Skor 3:0"),
            // RUN 8516
            List.of("A/U 0.5 Alt", "MS Skor 1:1", "2Y A/U 2.5 Alt", "MS Skor 3:1", "İY ÇŞ 1X", "İY Skor 3:2", "MS Skor 1:3", "İY Skor 2:0", "İY A/U 2.5 Alt"),
            // RUN 8887
            List.of("A/U 0.5 Alt", "İY A/U 2.5 Alt", "MS Skor 3:2", "2Y A/U 2.5 Üst", "HT/FT 1/2", "MS Skor 3:4", "2Y A/U 1.5 Üst"),
            // RUN 9271
            List.of("MS Skor 3:2", "A/U 1.5 Üst", "MS Skor 4:4", "İY A/U 2.5 Üst", "2Y A/U 0.5 Üst", "İY Skor 2:3", "İY Skor 3:0"),
            // RUN 10163
            List.of("ÇŞ 12", "İY 2", "İY A/U 0.5 Alt", "MS 2", "MS Skor 1:3", "MS Skor 1:4"),
            // RUN 11282
            List.of("2Y 1", "2Y A/U 2.5 Alt", "İY Skor 3:2", "MS Skor 4:4", "A/U 0.5 Üst", "KG Hayır", "İY Skor 2:1", "HT/FT X/2", "2Y A/U 1.5 Alt"),
            // RUN 11394
            List.of("İY 2", "MS Skor 4:3", "2Y A/U 1.5 Alt", "İY ÇŞ 1X", "İY Skor 2:0", "MS Skor 4:4", "2Y A/U 2.5 Alt"),
            // RUN 11689
            List.of("İY A/U 2.5 Üst", "KG Evet", "MS Skor 1:1", "MS Skor 3:3", "2Y 1", "MS Skor 0:2", "2Y A/U 1.5 Alt", "HT/FT X/2"),
            // RUN 11719
            List.of("İY Skor 2:0", "MS Skor 1:2", "A/U 4.5 Alt", "İY Skor 3:2", "ÇŞ 1X", "MS Skor 2:3", "MS Skor 4:0"),
            // RUN 12567
            List.of("İY A/U 0.5 Alt", "İY Skor 3:2", "MS Skor 3:2", "A/U 0.5 Alt", "MS 2", "İY Skor 2:0", "İY A/U 2.5 Alt"),
            // RUN 13285
            List.of("MS Skor 4:4", "İY Skor 0:3", "A/U 4.5 Üst", "MS Skor 3:2", "İY A/U 0.5 Üst", "A/U 5.5 Alt"),
            // RUN 15307
            List.of("A/U 1.5 Üst", "MS Skor 4:4", "MS Skor 2:4", "İY Skor 3:0", "HT/FT 2/1", "HT/FT 1/2", "A/U 4.5 Üst", "2Y A/U 0.5 Alt", "İY 1", "MS Skor 4:3"),
            // RUN 15685
            List.of("2Y X", "İY X", "A/U 0.5 Alt", "MS Skor 1:2", "İY 2", "HT/FT 1/X", "İY Skor 2:1", "MS Skor 0:2", "MS Skor 3:0"),
            // RUN 16177
            List.of("2Y KG Hayır", "İY ÇŞ X2", "İY Skor 1:0", "MS Skor 3:1", "2Y 2"),
            // RUN 16743
            List.of("A/U 5.5 Alt", "HT/FT X/2", "HT/FT 2/X", "A/U 4.5 Üst", "İY Skor 0:2"),
            // RUN 16927
            List.of("KG Evet", "HT/FT X/2", "MS Skor 3:2", "MS Skor 4:1"),
            // RUN 17535
            List.of("2Y A/U 2.5 Üst", "A/U 4.5 Alt", "A/U 5.5 Alt", "İY A/U 2.5 Üst", "A/U 2.5 Alt", "ÇŞ 12", "A/U 1.5 Alt", "A/U 3.5 Alt", "İY A/U 0.5 Üst", "İY ÇŞ 12"),
            // RUN 18777
            List.of("2Y A/U 1.5 Üst", "MS Skor 3:3", "KG Evet", "HT/FT X/2", "MS Skor 0:2", "İY Skor 2:1", "İY A/U 0.5 Üst", "A/U 0.5 Üst", "İY KG Evet", "A/U 4.5 Üst", "İY Skor 2:3", "İY A/U 2.5 Alt", "İY 2"),
            // RUN 19233
            List.of("İY Skor 3:0", "MS Skor 3:2", "İY A/U 0.5 Üst", "MS Skor 1:2", "2Y A/U 0.5 Üst", "A/U 3.5 Üst", "MS Skor 3:3"),
            // RUN 20319
            List.of("İY ÇŞ X2", "İY KG Evet", "2Y A/U 1.5 Üst", "İY A/U 0.5 Alt", "İY Skor 1:1", "A/U 5.5 Alt", "2Y A/U 0.5 Alt", "A/U 0.5 Üst"),
            // RUN 21035
            List.of("İY Skor 3:1", "2Y A/U 2.5 Üst", "İY Skor 2:1", "A/U 2.5 Üst", "İY X", "MS Skor 1:4"),
            // RUN 23219
            List.of("MS Skor 0:4", "İY Skor 0:3", "HT/FT 1/X", "MS Skor 4:1", "HT/FT 1/2", "MS 2", "MS Skor 3:1", "İY Skor 1:1"),
            // RUN 23635
            List.of("MS Skor 0:2", "İY Skor 2:0", "ÇŞ 12", "İY ÇŞ 12", "2Y X", "MS Skor 1:2", "MS 1", "A/U 0.5 Alt"),
            // RUN 23897
            List.of("HT/FT X/2", "A/U 3.5 Üst", "A/U 4.5 Üst", "İY Skor 2:3", "MS Skor 4:4", "İY 2", "2Y A/U 2.5 Alt", "MS Skor 4:2", "2Y A/U 0.5 Üst"),
            // RUN 25889
            List.of("ÇŞ 12", "A/U 5.5 Alt", "MS Skor 1:1", "İY A/U 2.5 Üst", "İY Skor 2:2"),
            // RUN 26225
            List.of("2Y A/U 1.5 Alt", "İY Skor 2:2", "KG Evet", "A/U 3.5 Üst", "İY Skor 2:3", "A/U 0.5 Üst", "MS Skor 2:2", "2Y A/U 0.5 Üst", "İY Skor 0:2", "İY 2"),
            // RUN 26536
            List.of("MS Skor 0:3", "ÇŞ 12", "MS Skor 4:1", "A/U 5.5 Üst"),
            // RUN 27889
            List.of("MS Skor 3:3", "A/U 4.5 Alt", "2Y A/U 2.5 Alt", "2Y A/U 2.5 Üst", "A/U 2.5 Üst", "A/U 1.5 Üst", "A/U 5.5 Alt", "A/U 4.5 Üst", "A/U 0.5 Üst"),
            // RUN 28399
            List.of("2Y A/U 1.5 Üst", "KG Hayır", "İY ÇŞ X2", "İY Skor 2:2", "2Y 1", "2Y A/U 2.5 Alt", "MS Skor 4:4", "İY Skor 2:0", "İY A/U 2.5 Alt", "İY 1", "MS Skor 3:3", "İY Skor 1:1", "MS Skor 1:1"),
            // RUN 28516
            List.of("MS Skor 4:4", "İY Skor 2:1", "İY KG Hayır", "HT/FT 2/X", "MS Skor 2:1", "MS Skor 4:1"),
            // RUN 29076
            List.of("2Y A/U 1.5 Üst", "A/U 3.5 Üst", "MS Skor 3:2", "KG Hayır"),
            // RUN 29354
            List.of("MS Skor 4:1", "A/U 1.5 Üst", "MS Skor 4:4", "A/U 5.5 Alt", "MS Skor 4:3"),
            // RUN 30060
            List.of("İY A/U 0.5 Üst", "İY Skor 0:3", "A/U 0.5 Alt", "İY X", "A/U 5.5 Üst", "A/U 4.5 Alt", "İY A/U 2.5 Alt", "MS 1", "MS Skor 0:1"),
            // RUN 30355
            List.of("MS Skor 0:1", "A/U 5.5 Alt", "MS Skor 3:0", "İY A/U 2.5 Alt", "İY 2", "İY A/U 0.5 Üst", "İY Skor 2:1"),
            // RUN 30564
            List.of("İY ÇŞ 1X", "HT/FT 2/1", "A/U 4.5 Üst", "2Y A/U 1.5 Alt", "İY Skor 1:3", "İY 1", "İY Skor 0:2", "İY A/U 0.5 Üst", "MS Skor 2:3", "2Y A/U 1.5 Üst", "İY Skor 2:3", "İY Skor 3:2", "KG Evet", "İY A/U 2.5 Üst", "A/U 0.5 Üst", "A/U 5.5 Üst", "İY Skor 1:1"),
            // RUN 31380
            List.of("İY A/U 0.5 Alt", "A/U 5.5 Alt", "İY 2", "MS Skor 3:2"),
            // RUN 31588
            List.of("MS Skor 4:3", "İY Skor 2:0", "A/U 1.5 Üst", "İY 2", "İY A/U 2.5 Üst", "A/U 4.5 Üst", "İY ÇŞ 1X"),
            // RUN 32758
            List.of("MS Skor 0:4", "MS 2", "İY Skor 2:1", "ÇŞ 12", "İY Skor 2:0"),
            // RUN 32875
            List.of("MS Skor 2:3", "MS 1", "MS Skor 0:1", "A/U 5.5 Alt", "İY Skor 2:2", "MS Skor 1:2", "İY Skor 2:0"),
            // RUN 33983
            List.of("İY Skor 2:3", "İY Skor 1:2", "İY A/U 1.5 Alt", "İY ÇŞ X2", "İY KG Evet"),
            // RUN 34177
            List.of("İY 2", "ÇŞ 1X", "A/U 4.5 Alt", "İY A/U 2.5 Üst", "İY Skor 2:2", "İY A/U 0.5 Üst", "İY Skor 2:0", "İY Skor 0:2", "İY X", "HT/FT 1/2", "ÇŞ 12"),
            // RUN 35104
            List.of("İY KG Evet", "İY Skor 2:0", "HT/FT 1/2", "MS Skor 1:1", "HT/FT X/X", "İY Skor 2:2", "MS Skor 2:4"),
            // RUN 35595
            List.of("İY Skor 3:1", "İY X", "MS Skor 0:4", "2Y A/U 0.5 Alt", "İY ÇŞ 12", "MS Skor 0:1", "A/U 2.5 Üst"),
            // RUN 36049
            List.of("İY Skor 0:3", "İY Skor 2:0", "A/U 5.5 Alt", "MS Skor 4:2", "MS Skor 4:3", "İY Skor 2:3", "MS Skor 0:2"),
            // RUN 36188
            List.of("MS Skor 3:3", "MS Skor 2:2", "MS Skor 3:2", "2Y A/U 0.5 Üst"),
            // RUN 37727
            List.of("İY A/U 2.5 Alt", "İY Skor 3:1", "2Y A/U 1.5 Üst", "MS Skor 0:3", "İY Skor 1:2"),
            // RUN 38583
            List.of("A/U 0.5 Alt", "A/U 2.5 Alt", "İY A/U 2.5 Alt", "İY X", "A/U 4.5 Alt", "İY A/U 1.5 Alt", "İY A/U 0.5 Üst", "2Y A/U 1.5 Alt", "2Y A/U 2.5 Alt", "A/U 3.5 Alt", "İY ÇŞ 12", "HT/FT 1/X", "ÇŞ 12"),
            // RUN 38873
            List.of("İY A/U 1.5 Alt", "A/U 3.5 Alt", "MS Skor 2:3", "MS Skor 1:3", "HT/FT 2/1", "İY Skor 2:0", "İY ÇŞ 12", "İY Skor 2:2"),
            // RUN 38875
            List.of("MS Skor 0:2", "MS 1", "MS Skor 3:2", "KG Hayır", "MS Skor 1:4"),
            // RUN 38958
            List.of("MS Skor 4:4", "MS Skor 3:2", "HT/FT 2/X", "İY Skor 2:2"),
            // RUN 39627
            List.of("İY Skor 1:3", "İY Skor 2:2", "ÇŞ 12", "MS Skor 2:4", "MS 2"),
            // RUN 40888
            List.of("İY A/U 0.5 Alt", "MS Skor 0:2", "MS Skor 4:0", "MS 1", "2Y X", "MS Skor 1:2", "MS Skor 3:0", "A/U 5.5 Alt", "İY Skor 0:2", "ÇŞ 12"),
            // RUN 41186
            List.of("İY Skor 2:0", "ÇŞ 12", "MS 1", "2Y X", "İY ÇŞ 12", "İY A/U 2.5 Alt", "İY Skor 3:0", "İY 2", "MS Skor 0:1"),
            // RUN 42153
            List.of("MS Skor 1:1", "MS Skor 4:2", "MS Skor 4:4", "İY Skor 2:3", "İY A/U 2.5 Üst", "MS Skor 3:1", "İY Skor 0:3", "A/U 4.5 Üst"),
            // RUN 42172
            List.of("MS Skor 0:4", "MS 2", "İY A/U 0.5 Alt", "HT/FT 2/2", "A/U 2.5 Üst", "MS Skor 4:3", "2Y A/U 1.5 Alt", "2Y A/U 2.5 Üst", "HT/FT 2/1", "İY ÇŞ 12", "İY ÇŞ X2", "MS Skor 1:4"),
            // RUN 43866
            List.of("MS Skor 4:3", "MS Skor 1:3", "MS Skor 1:1", "İY Skor 0:3", "MS Skor 4:4", "MS Skor 2:4", "İY 1", "HT/FT 1/2", "İY Skor 2:3"),
            // RUN 44488
            List.of("İY Skor 0:2", "İY A/U 2.5 Üst", "MS Skor 4:1", "MS Skor 2:2", "İY Skor 2:0", "İY Skor 3:1", "MS Skor 0:1"),
            // RUN 44738
            List.of("HT/FT 1/X", "A/U 2.5 Alt", "İY A/U 1.5 Üst", "A/U 1.5 Üst", "2Y A/U 0.5 Alt", "İY A/U 0.5 Üst", "İY A/U 0.5 Alt", "A/U 4.5 Alt", "ÇŞ 12"),
            // RUN 44846
            List.of("İY A/U 0.5 Üst", "ÇŞ 12", "MS Skor 2:4", "HT/FT 2/2", "ÇŞ 1X", "İY Skor 0:3"),
            // RUN 44969
            List.of("MS Skor 2:3", "İY Skor 0:2", "HT/FT X/X", "2Y X", "İY 2"),
            // RUN 45281
            List.of("MS 2", "MS 1", "A/U 5.5 Alt", "HT/FT 2/1", "İY Skor 0:2", "İY 2"),
            // RUN 45508
            List.of("MS Skor 3:2", "İY Skor 2:2", "ÇŞ 12", "İY Skor 2:3", "İY Skor 1:3", "HT/FT 1/X", "ÇŞ 1X"),
            // RUN 46179
            List.of("A/U 4.5 Üst", "MS Skor 3:2", "İY A/U 2.5 Alt", "MS Skor 1:3", "HT/FT 1/2", "MS Skor 3:4", "A/U 1.5 Üst"),
            // RUN 46545
            List.of("İY ÇŞ X2", "İY Skor 1:2", "İY Skor 1:0", "İY A/U 1.5 Alt", "İY Skor 1:3", "İY Skor 3:1"),
            // RUN 47142
            List.of("İY Skor 1:2", "MS Skor 2:4", "İY Skor 1:3", "MS Skor 1:2", "İY Skor 1:1"),
            // RUN 48122
            List.of("MS Skor 4:4", "İY ÇŞ 1X", "HT/FT 2/X", "İY A/U 2.5 Üst", "2Y A/U 1.5 Alt", "A/U 1.5 Üst", "2Y A/U 0.5 Üst", "MS Skor 4:3"),
            // RUN 48607
            List.of("HT/FT 1/1", "MS Skor 1:3", "MS Skor 2:0", "İY Skor 2:1", "MS Skor 2:2"),
            // RUN 49609
            List.of("İY A/U 0.5 Alt", "HT/FT 1/2", "İY Skor 2:3", "İY A/U 1.5 Üst", "İY Skor 1:2", "İY Skor 3:2", "İY ÇŞ X2"),
            // RUN 50671
            List.of("A/U 0.5 Alt", "A/U 5.5 Alt", "İY 2", "HT/FT X/2", "2Y A/U 2.5 Üst", "HT/FT 2/X"),
            // RUN 50836
            List.of("İY Skor 3:0", "A/U 2.5 Alt", "MS Skor 4:4", "MS Skor 3:1", "2Y A/U 0.5 Üst", "İY A/U 2.5 Alt", "İY Skor 3:2", "A/U 4.5 Üst", "MS Skor 0:2", "HT/FT X/2"),
            // RUN 51297
            List.of("İY Skor 0:3", "MS Skor 1:3", "HT/FT X/2", "İY A/U 2.5 Üst", "MS Skor 4:2", "İY A/U 0.5 Üst", "MS Skor 2:3", "A/U 5.5 Alt"),
            // RUN 51740
            List.of("HT/FT 1/2", "İY Skor 1:2", "İY ÇŞ X2", "İY X", "MS Skor 0:1", "ÇŞ X2", "İY ÇŞ 12", "İY Skor 2:2", "İY Skor 3:0", "İY Skor 2:1", "MS Skor 3:1"),
            // RUN 51922
            List.of("İY A/U 1.5 Üst", "A/U 2.5 Alt", "A/U 2.5 Üst", "HT/FT 1/X", "2Y A/U 1.5 Alt", "2Y A/U 2.5 Üst", "A/U 3.5 Alt", "2Y A/U 2.5 Alt", "İY Skor 2:2", "İY A/U 2.5 Alt", "2Y A/U 0.5 Üst", "İY A/U 1.5 Alt", "A/U 4.5 Üst", "A/U 0.5 Alt", "A/U 1.5 Alt", "A/U 3.5 Üst", "2Y A/U 1.5 Üst", "ÇŞ 12"),
            // RUN 54249
            List.of("HT/FT 1/1", "MS Skor 1:2", "HT/FT 2/2", "MS Skor 3:4"),
            // RUN 54763
            List.of("İY Skor 2:0", "MS Skor 4:4", "A/U 1.5 Üst", "MS Skor 4:2", "MS Skor 0:2", "MS Skor 3:4", "MS 2"),
            // RUN 54912
            List.of("İY Skor 0:3", "2Y A/U 2.5 Üst", "MS Skor 3:1", "HT/FT 2/X", "İY A/U 0.5 Üst", "2Y A/U 1.5 Alt", "2Y A/U 0.5 Üst", "İY X", "MS Skor 4:3"),
            // RUN 54946
            List.of("İY Skor 1:1", "A/U 5.5 Üst", "2Y A/U 1.5 Alt", "2Y A/U 0.5 Alt", "İY KG Hayır", "İY Skor 0:2", "MS Skor 1:1", "KG Evet", "HT/FT 1/2", "İY 2", "2Y A/U 2.5 Üst"),
            // RUN 55410
            List.of("KG Hayır", "MS Skor 1:2", "MS Skor 2:2", "A/U 1.5 Üst", "A/U 0.5 Alt", "İY Skor 1:1", "MS Skor 4:4", "2Y A/U 0.5 Üst", "İY 2", "2Y A/U 0.5 Alt", "MS Skor 4:1"),
            // RUN 55603
            List.of("HT/FT 1/X", "2Y A/U 1.5 Üst", "İY KG Evet", "İY 1", "MS Skor 4:4", "İY ÇŞ 12", "2Y A/U 0.5 Alt", "A/U 0.5 Üst", "MS Skor 2:2", "İY Skor 1:1", "2Y 1", "KG Evet"),
            // RUN 55654
            List.of("HT/FT X/2", "İY Skor 1:2", "A/U 4.5 Üst", "2Y A/U 2.5 Üst", "İY X", "2Y A/U 1.5 Üst", "MS Skor 2:2"),
            // RUN 56824
            List.of("İY A/U 1.5 Alt", "İY Skor 1:2", "HT/FT 2/1", "İY ÇŞ X2", "İY A/U 1.5 Üst", "İY KG Evet"),
            // RUN 57542
            List.of("MS Skor 2:4", "ÇŞ 12", "MS 2", "MS Skor 3:4"),
            // RUN 57796
            List.of("İY Skor 1:2", "MS Skor 3:2", "İY Skor 3:0"),
            // RUN 57990
            List.of("İY ÇŞ 1X", "MS Skor 1:3", "İY 2", "İY Skor 1:1", "MS Skor 3:0", "İY Skor 3:0", "İY ÇŞ X2"),
            // RUN 59080
            List.of("İY Skor 3:1", "KG Hayır", "HT/FT 2/X", "İY Skor 1:0", "MS Skor 4:1"),
            // RUN 59131
            List.of("MS 1", "İY Skor 3:2", "ÇŞ 1X", "MS Skor 3:2", "İY Skor 3:0", "İY ÇŞ 1X"),
            // RUN 59348
            List.of("İY Skor 0:1", "İY A/U 0.5 Üst", "MS Skor 3:2", "2Y 2"),
            // RUN 59955
            List.of("MS Skor 3:3", "MS Skor 2:2", "KG Hayır", "2Y A/U 2.5 Alt", "2Y A/U 1.5 Alt", "İY Skor 1:1", "İY Skor 3:2", "İY 1", "A/U 0.5 Üst"),
            // RUN 60036
            List.of("2Y A/U 1.5 Alt", "İY Skor 1:2", "MS Skor 0:3", "A/U 5.5 Üst", "A/U 3.5 Üst", "İY Skor 3:2", "MS Skor 2:3", "İY ÇŞ X2"),
            // RUN 61115
            List.of("A/U 4.5 Alt", "İY A/U 0.5 Üst", "İY Skor 1:1", "MS Skor 3:0", "HT/FT 2/1", "MS Skor 0:1", "İY Skor 2:0"),
            // RUN 61448
            List.of("MS 1", "MS Skor 2:0", "ÇŞ 1X", "İY Skor 0:3"),
            // RUN 61781
            List.of("İY A/U 1.5 Üst", "İY Skor 1:0", "İY 1", "İY Skor 3:0", "İY A/U 0.5 Üst", "2Y KG Evet"),
            // RUN 62128
            List.of("2Y A/U 2.5 Üst", "MS Skor 3:2", "İY A/U 2.5 Üst", "2Y A/U 1.5 Üst", "A/U 0.5 Alt", "HT/FT 2/X", "HT/FT 1/2", "MS Skor 0:2", "İY A/U 0.5 Üst", "A/U 4.5 Üst", "İY ÇŞ 12", "2Y A/U 1.5 Alt", "MS Skor 3:4"),
            // RUN 62674
            List.of("İY 1", "A/U 0.5 Üst", "İY Skor 3:1", "İY ÇŞ X2", "A/U 3.5 Üst", "HT/FT 2/1", "İY A/U 0.5 Alt", "HT/FT X/2", "2Y A/U 1.5 Üst", "2Y A/U 0.5 Alt", "2Y A/U 2.5 Alt", "2Y A/U 0.5 Üst", "İY Skor 1:1"),
            // RUN 63640
            List.of("2Y A/U 2.5 Üst", "A/U 3.5 Alt", "A/U 2.5 Alt", "2Y A/U 1.5 Alt", "İY A/U 1.5 Üst", "A/U 0.5 Alt", "İY ÇŞ 12", "İY A/U 0.5 Üst", "A/U 4.5 Alt", "HT/FT 1/X", "İY X", "2Y A/U 0.5 Alt", "İY A/U 2.5 Üst", "İY A/U 2.5 Alt", "MS Skor 3:3"),
            // RUN 63728
            List.of("2Y 1", "İY 1", "2Y KG Hayır", "İY A/U 1.5 Alt"),
            // RUN 64586
            List.of("İY A/U 0.5 Alt", "A/U 5.5 Alt", "HT/FT X/2", "KG Evet", "A/U 0.5 Üst", "İY Skor 2:2", "2Y A/U 1.5 Alt", "A/U 3.5 Üst", "A/U 5.5 Üst", "İY A/U 0.5 Üst", "İY Skor 2:3", "İY Skor 3:1", "İY ÇŞ 12", "İY KG Evet", "2Y A/U 2.5 Üst", "İY Skor 3:0", "İY Skor 3:2", "İY Skor 1:1"),
            // RUN 64836
            List.of("İY Skor 2:3", "İY Skor 2:0", "İY Skor 3:1", "MS Skor 4:1", "2Y KG Hayır"),
            // RUN 65546
            List.of("İY Skor 1:2", "İY A/U 1.5 Alt", "İY 1"),
            // RUN 65583
            List.of("İY KG Evet", "HT/FT 2/X", "MS Skor 2:1", "İY Skor 2:1", "MS Skor 4:4", "MS Skor 4:1"),
            // RUN 66727
            List.of("2Y A/U 1.5 Üst", "2Y A/U 1.5 Alt", "İY 2", "A/U 0.5 Üst", "İY Skor 2:1", "İY Skor 3:2", "İY A/U 0.5 Üst", "MS Skor 1:1"),
            // RUN 66866
            List.of("2Y 1", "İY Skor 2:0", "MS Skor 3:3", "A/U 5.5 Üst", "A/U 3.5 Üst", "MS Skor 1:1", "İY Skor 0:2", "İY KG Hayır", "2Y A/U 1.5 Alt", "KG Hayır"),
            // RUN 67491
            List.of("İY Skor 2:1", "İY Skor 0:3", "A/U 4.5 Üst", "İY A/U 0.5 Üst", "2Y A/U 1.5 Alt", "İY X", "MS Skor 3:3", "2Y A/U 1.5 Üst", "MS Skor 1:2", "İY Skor 1:1"),
            // RUN 67870
            List.of("MS Skor 2:3", "MS Skor 3:2", "MS Skor 0:4", "KG Evet"),
            // RUN 68589
            List.of("İY Skor 2:0", "HT/FT 2/X", "MS Skor 4:1", "2Y KG Evet"),
            // RUN 68829
            List.of("MS Skor 4:2", "2Y A/U 0.5 Üst", "HT/FT X/2", "İY Skor 0:3", "A/U 3.5 Üst"),
            // RUN 69983
            List.of("MS Skor 1:3", "MS Skor 4:1", "İY 2", "HT/FT 2/X", "A/U 5.5 Alt", "İY Skor 0:2", "A/U 3.5 Üst"),
            // RUN 71990
            List.of("A/U 0.5 Üst", "İY Skor 2:2", "İY Skor 3:0", "MS Skor 2:3", "KG Hayır", "İY 2", "MS Skor 4:4", "KG Evet", "2Y A/U 1.5 Üst"),
            // RUN 72078
            List.of("A/U 3.5 Üst", "HT/FT X/2", "MS Skor 3:1", "HT/FT 2/X", "İY A/U 0.5 Üst", "2Y A/U 0.5 Üst", "İY A/U 0.5 Alt", "İY A/U 2.5 Alt", "İY A/U 2.5 Üst", "MS Skor 4:2", "İY Skor 0:3"),
            // RUN 72278
            List.of("MS Skor 0:4", "HT/FT 1/1", "A/U 3.5 Üst", "MS Skor 1:1"),
            // RUN 72536
            List.of("İY A/U 2.5 Alt", "2Y A/U 2.5 Alt", "A/U 1.5 Üst", "İY Skor 3:0", "HT/FT 2/X", "İY Skor 2:3", "MS Skor 4:4"),
            // RUN 72563
            List.of("İY Skor 0:2", "MS Skor 4:4", "HT/FT 2/X", "A/U 4.5 Üst", "A/U 5.5 Alt"),
            // RUN 72615
            List.of("İY Skor 2:0", "A/U 1.5 Üst", "İY Skor 3:1", "İY 1", "A/U 3.5 Alt", "MS Skor 3:3", "KG Evet", "İY Skor 1:0"),
            // RUN 72638
            List.of("HT/FT 2/X", "İY A/U 2.5 Üst", "İY X", "MS Skor 3:4", "2Y A/U 2.5 Alt", "İY Skor 3:2", "A/U 3.5 Üst", "2Y A/U 1.5 Alt", "A/U 5.5 Alt"),
            // RUN 72743
            List.of("MS Skor 4:1", "MS Skor 2:1", "İY Skor 3:1", "İY Skor 2:0", "2Y KG Hayır"),
            // RUN 72824
            List.of("MS Skor 3:3", "2Y X", "İY Skor 3:2", "MS Skor 1:3", "A/U 4.5 Alt", "İY A/U 0.5 Üst", "MS Skor 0:2", "İY Skor 1:1", "A/U 5.5 Üst", "MS Skor 1:2", "İY X", "2Y A/U 0.5 Alt"),
            // RUN 73708
            List.of("MS Skor 1:3", "İY Skor 1:1", "MS Skor 2:1", "İY Skor 1:3", "A/U 0.5 Üst", "İY ÇŞ 1X"),
            // RUN 74371
            List.of("A/U 2.5 Üst", "A/U 2.5 Alt", "MS Skor 3:3", "HT/FT 1/X", "A/U 3.5 Alt", "A/U 1.5 Alt", "A/U 0.5 Üst", "İY A/U 2.5 Üst"),
            // RUN 75025
            List.of("İY ÇŞ X2", "A/U 3.5 Üst", "İY Skor 1:2", "MS Skor 0:2", "MS Skor 1:1", "2Y A/U 2.5 Üst", "2Y X"),
            // RUN 75133
            List.of("İY X", "A/U 1.5 Üst", "ÇŞ 12", "A/U 1.5 Alt", "A/U 5.5 Alt", "İY A/U 1.5 Üst", "HT/FT 1/X"),
            // RUN 75633
            List.of("İY Skor 3:0", "İY Skor 2:1", "İY 1", "ÇŞ 12", "İY ÇŞ X2", "İY Skor 2:0", "2Y 1"),
            // RUN 77931
            List.of("İY A/U 1.5 Üst", "İY Skor 3:2", "2Y 1", "İY 1", "İY ÇŞ X2", "2Y KG Hayır"),
            // RUN 78298
            List.of("MS Skor 3:3", "MS Skor 0:4", "İY ÇŞ X2", "MS Skor 1:2", "İY Skor 2:2", "2Y 1", "İY A/U 0.5 Üst", "MS Skor 0:2", "MS 2"),
            // RUN 79058
            List.of("İY 1", "ÇŞ 12", "İY Skor 2:2", "ÇŞ 1X", "MS Skor 0:4"),
            // RUN 79979
            List.of("İY ÇŞ X2", "A/U 4.5 Üst", "MS Skor 2:4", "MS Skor 4:3", "A/U 2.5 Üst", "A/U 5.5 Üst"),
            // RUN 81146
            List.of("2Y A/U 1.5 Üst", "2Y A/U 1.5 Alt", "MS Skor 1:1", "İY A/U 0.5 Alt", "HT/FT X/2", "2Y A/U 2.5 Üst", "2Y 1", "İY ÇŞ X2"),
            // RUN 81171
            List.of("HT/FT 2/X", "İY A/U 2.5 Alt", "MS Skor 1:3", "2Y A/U 1.5 Üst", "MS Skor 3:2", "İY Skor 3:2", "A/U 3.5 Üst", "İY ÇŞ 12", "İY Skor 2:0", "MS Skor 3:4"),
            // RUN 81532
            List.of("MS Skor 1:2", "A/U 4.5 Alt", "2Y X", "A/U 5.5 Üst", "İY Skor 2:1", "A/U 4.5 Üst"),
            // RUN 82499
            List.of("İY Skor 1:2", "MS Skor 0:1", "MS Skor 1:2", "ÇŞ 12", "MS Skor 0:2", "İY ÇŞ 12", "MS Skor 4:1", "İY X", "MS Skor 0:3"),
            // RUN 83719
            List.of("İY ÇŞ 12", "MS Skor 0:4", "MS Skor 0:3", "2Y 2", "KG Evet"),
            // RUN 84307
            List.of("İY ÇŞ X2", "2Y A/U 1.5 Üst", "MS Skor 1:1", "A/U 5.5 Üst", "İY 1", "2Y 1", "İY Skor 2:3", "HT/FT 2/1", "KG Evet"),
            // RUN 86308
            List.of("İY A/U 2.5 Üst", "A/U 5.5 Alt", "A/U 3.5 Üst", "A/U 1.5 Üst", "HT/FT 2/X", "MS Skor 0:1", "İY A/U 2.5 Alt", "MS Skor 0:2"),
            // RUN 86864
            List.of("2Y A/U 2.5 Alt", "HT/FT 1/2", "MS Skor 3:4", "MS Skor 4:3", "A/U 0.5 Alt", "A/U 5.5 Alt", "MS Skor 3:2"),
            // RUN 87280
            List.of("İY Skor 3:2", "İY KG Hayır", "MS Skor 3:3", "İY 2", "2Y A/U 1.5 Alt", "2Y A/U 0.5 Üst", "İY ÇŞ 1X", "İY Skor 3:0", "MS Skor 2:2", "MS Skor 1:1", "İY A/U 2.5 Üst", "2Y A/U 2.5 Alt", "İY Skor 2:1", "A/U 0.5 Üst"),
            // RUN 87834
            List.of("İY A/U 1.5 Üst", "A/U 2.5 Üst", "A/U 4.5 Alt", "İY X", "İY A/U 2.5 Alt", "İY A/U 2.5 Üst", "2Y A/U 0.5 Üst", "İY A/U 1.5 Alt", "2Y A/U 1.5 Üst", "HT/FT 1/X", "ÇŞ 12"),
            // RUN 87876
            List.of("HT/FT 1/X", "A/U 3.5 Alt", "İY A/U 1.5 Alt", "2Y A/U 2.5 Alt", "A/U 2.5 Alt", "2Y A/U 1.5 Alt", "A/U 4.5 Üst", "İY A/U 1.5 Üst", "İY A/U 2.5 Üst", "2Y A/U 0.5 Üst", "İY A/U 0.5 Alt", "A/U 3.5 Üst", "A/U 4.5 Alt", "ÇŞ 12"),
            // RUN 88574
            List.of("İY Skor 0:2", "İY KG Evet", "A/U 3.5 Üst", "İY Skor 3:1", "İY 1", "2Y A/U 1.5 Alt", "KG Evet", "İY A/U 2.5 Üst", "İY 2", "MS Skor 0:2", "2Y A/U 2.5 Üst", "HT/FT 2/1", "A/U 0.5 Üst"),
            // RUN 88582
            List.of("MS Skor 1:3", "A/U 4.5 Alt", "MS Skor 0:2", "İY Skor 3:2", "MS Skor 3:2", "MS Skor 3:3"),
            // RUN 88771
            List.of("A/U 4.5 Üst", "MS Skor 1:4", "HT/FT 2/1", "A/U 3.5 Üst", "İY ÇŞ X2", "MS Skor 4:3", "A/U 2.5 Üst", "A/U 5.5 Üst"),
            // RUN 89371
            List.of("İY A/U 0.5 Alt", "MS Skor 1:1", "A/U 5.5 Alt", "ÇŞ 12", "MS Skor 3:3", "A/U 5.5 Üst", "İY A/U 2.5 Üst"),
            // RUN 90213
            List.of("HT/FT 1/X", "A/U 1.5 Üst", "İY A/U 2.5 Alt", "A/U 2.5 Alt", "İY A/U 1.5 Alt", "A/U 5.5 Alt", "İY A/U 1.5 Üst", "2Y A/U 2.5 Alt", "İY A/U 2.5 Üst", "A/U 3.5 Üst", "A/U 4.5 Üst", "2Y A/U 2.5 Üst", "A/U 1.5 Alt", "MS Skor 3:3"),
            // RUN 90476
            List.of("HT/FT 2/X", "İY ÇŞ 12", "İY Skor 0:3", "A/U 2.5 Üst", "A/U 3.5 Üst"),
            // RUN 91407
            List.of("A/U 0.5 Üst", "İY Skor 3:0", "İY Skor 2:2", "2Y 1", "2Y A/U 1.5 Alt", "İY Skor 2:0", "MS Skor 2:3", "HT/FT 1/X", "İY A/U 2.5 Alt"),
            // RUN 92370
            List.of("İY Skor 1:2", "2Y A/U 1.5 Alt", "MS Skor 0:3", "A/U 4.5 Alt"),
            // RUN 92491
            List.of("İY Skor 0:2", "2Y X", "MS Skor 4:4", "A/U 0.5 Alt", "HT/FT X/X"),
            // RUN 92606
            List.of("İY Skor 1:2", "A/U 0.5 Üst", "MS Skor 2:3", "2Y A/U 1.5 Alt", "MS Skor 0:3"),
            // RUN 93319
            List.of("İY ÇŞ 12", "İY A/U 1.5 Alt", "İY X", "A/U 3.5 Alt", "A/U 2.5 Üst", "2Y A/U 0.5 Üst", "İY A/U 0.5 Üst", "A/U 4.5 Üst", "ÇŞ 12"),
            // RUN 93601
            List.of("İY Skor 0:2", "A/U 5.5 Alt", "2Y A/U 0.5 Alt", "HT/FT 1/X", "İY Skor 2:0", "İY 2", "MS Skor 0:2", "2Y A/U 1.5 Üst", "A/U 0.5 Üst", "HT/FT X/2", "İY A/U 2.5 Üst"),
            // RUN 94207
            List.of("A/U 5.5 Alt", "MS Skor 4:4", "MS Skor 4:2", "İY Skor 0:3", "MS Skor 2:3"),
            // RUN 96972
            List.of("İY Skor 0:3", "A/U 2.5 Üst", "İY A/U 2.5 Üst", "MS Skor 1:1", "MS Skor 0:1", "MS Skor 4:4"),
            // RUN 97178
            List.of("MS Skor 3:1", "2Y A/U 1.5 Alt", "İY Skor 1:0", "MS Skor 3:4", "MS Skor 0:1", "A/U 4.5 Alt"),
            // RUN 97254
            List.of("A/U 3.5 Alt", "2Y A/U 0.5 Üst", "İY A/U 1.5 Üst", "İY X", "2Y A/U 2.5 Alt", "İY A/U 0.5 Üst", "A/U 4.5 Üst", "İY A/U 0.5 Alt", "İY ÇŞ 12", "HT/FT 1/X", "A/U 1.5 Üst", "A/U 2.5 Üst", "İY Skor 2:2", "A/U 5.5 Alt", "MS Skor 3:3"),
            // RUN 98025
            List.of("A/U 0.5 Alt", "İY Skor 0:3", "2Y A/U 1.5 Alt", "İY X", "MS Skor 1:3", "HT/FT 2/X", "İY 2", "MS Skor 4:4", "İY Skor 2:0", "2Y A/U 0.5 Üst", "2Y A/U 2.5 Alt"),
            // RUN 99264
            List.of("İY 1", "MS Skor 0:2", "İY A/U 1.5 Alt", "İY Skor 1:2"),
            // RUN 99419
            List.of("İY Skor 1:2", "2Y KG Hayır", "İY Skor 3:0", "İY ÇŞ X2", "İY A/U 0.5 Üst", "2Y 1")
    );

    // ─── kolon tanımları ────────────────────────────────────────────────────
    static class ColumnDef {
        final String sqlColumn, displayName;
        ColumnDef(String s, String d) { sqlColumn = s; displayName = d; }
    }

    private static final List<ColumnDef> ALL_ODDS_COLS = List.of(
            new ColumnDef("ft_1_a","MS 1"), new ColumnDef("ft_x_a","MS X"), new ColumnDef("ft_2_a","MS 2"),
            new ColumnDef("first_1_a","İY 1"), new ColumnDef("first_x_a","İY X"), new ColumnDef("first_2_a","İY 2"),
            new ColumnDef("second_1_a","2Y 1"), new ColumnDef("second_x_a","2Y X"), new ColumnDef("second_2_a","2Y 2"),
            new ColumnDef("bts_ft_yes_a","KG Evet"), new ColumnDef("bts_ft_no_a","KG Hayır"),
            new ColumnDef("bts_first_yes_a","İY KG Evet"), new ColumnDef("bts_first_no_a","İY KG Hayır"),
            new ColumnDef("bts_second_yes_a","2Y KG Evet"), new ColumnDef("bts_second_no_a","2Y KG Hayır"),
            new ColumnDef("dbc_ft_1x_a","ÇŞ 1X"), new ColumnDef("dbc_ft_12_a","ÇŞ 12"), new ColumnDef("dbc_ft_x2_a","ÇŞ X2"),
            new ColumnDef("dbc_first_1x_a","İY ÇŞ 1X"), new ColumnDef("dbc_first_12_a","İY ÇŞ 12"), new ColumnDef("dbc_first_x2_a","İY ÇŞ X2"),
            new ColumnDef("ft_0_5_over_a","A/U 0.5 Üst"), new ColumnDef("ft_0_5_under_a","A/U 0.5 Alt"),
            new ColumnDef("ft_1_5_over_a","A/U 1.5 Üst"), new ColumnDef("ft_1_5_under_a","A/U 1.5 Alt"),
            new ColumnDef("ft_2_5_over_a","A/U 2.5 Üst"), new ColumnDef("ft_2_5_under_a","A/U 2.5 Alt"),
            new ColumnDef("ft_3_5_over_a","A/U 3.5 Üst"), new ColumnDef("ft_3_5_under_a","A/U 3.5 Alt"),
            new ColumnDef("ft_4_5_over_a","A/U 4.5 Üst"), new ColumnDef("ft_4_5_under_a","A/U 4.5 Alt"),
            new ColumnDef("ft_5_5_over_a","A/U 5.5 Üst"), new ColumnDef("ft_5_5_under_a","A/U 5.5 Alt"),
            new ColumnDef("first_0_5_over_a","İY A/U 0.5 Üst"), new ColumnDef("first_0_5_under_a","İY A/U 0.5 Alt"),
            new ColumnDef("first_1_5_over_a","İY A/U 1.5 Üst"), new ColumnDef("first_1_5_under_a","İY A/U 1.5 Alt"),
            new ColumnDef("first_2_5_over_a","İY A/U 2.5 Üst"), new ColumnDef("first_2_5_under_a","İY A/U 2.5 Alt"),
            new ColumnDef("second_0_5_over_a","2Y A/U 0.5 Üst"), new ColumnDef("second_0_5_under_a","2Y A/U 0.5 Alt"),
            new ColumnDef("second_1_5_over_a","2Y A/U 1.5 Üst"), new ColumnDef("second_1_5_under_a","2Y A/U 1.5 Alt"),
            new ColumnDef("second_2_5_over_a","2Y A/U 2.5 Üst"), new ColumnDef("second_2_5_under_a","2Y A/U 2.5 Alt"),
            new ColumnDef("ht_ft_11_a","HT/FT 1/1"), new ColumnDef("ht_ft_1x_a","HT/FT 1/X"), new ColumnDef("ht_ft_12_a","HT/FT 1/2"),
            new ColumnDef("ht_ft_x1_a","HT/FT X/1"), new ColumnDef("ht_ft_xx_a","HT/FT X/X"), new ColumnDef("ht_ft_x2_a","HT/FT X/2"),
            new ColumnDef("ht_ft_21_a","HT/FT 2/1"), new ColumnDef("ht_ft_2x_a","HT/FT 2/X"), new ColumnDef("ht_ft_22_a","HT/FT 2/2"),
            new ColumnDef("first_score_1_0_a","İY Skor 1:0"), new ColumnDef("first_score_2_0_a","İY Skor 2:0"),
            new ColumnDef("first_score_2_1_a","İY Skor 2:1"), new ColumnDef("first_score_3_0_a","İY Skor 3:0"),
            new ColumnDef("first_score_3_1_a","İY Skor 3:1"), new ColumnDef("first_score_3_2_a","İY Skor 3:2"),
            new ColumnDef("first_score_0_0_a","İY Skor 0:0"), new ColumnDef("first_score_1_1_a","İY Skor 1:1"),
            new ColumnDef("first_score_2_2_a","İY Skor 2:2"), new ColumnDef("first_score_0_1_a","İY Skor 0:1"),
            new ColumnDef("first_score_0_2_a","İY Skor 0:2"), new ColumnDef("first_score_1_2_a","İY Skor 1:2"),
            new ColumnDef("first_score_0_3_a","İY Skor 0:3"), new ColumnDef("first_score_1_3_a","İY Skor 1:3"),
            new ColumnDef("first_score_2_3_a","İY Skor 2:3"),
            new ColumnDef("ft_score_1_0_a","MS Skor 1:0"), new ColumnDef("ft_score_2_0_a","MS Skor 2:0"),
            new ColumnDef("ft_score_2_1_a","MS Skor 2:1"), new ColumnDef("ft_score_3_0_a","MS Skor 3:0"),
            new ColumnDef("ft_score_3_1_a","MS Skor 3:1"), new ColumnDef("ft_score_3_2_a","MS Skor 3:2"),
            new ColumnDef("ft_score_4_0_a","MS Skor 4:0"), new ColumnDef("ft_score_4_1_a","MS Skor 4:1"),
            new ColumnDef("ft_score_4_2_a","MS Skor 4:2"), new ColumnDef("ft_score_4_3_a","MS Skor 4:3"),
            new ColumnDef("ft_score_5_0_a","MS Skor 5:0"), new ColumnDef("ft_score_5_1_a","MS Skor 5:1"),
            new ColumnDef("ft_score_5_2_a","MS Skor 5:2"), new ColumnDef("ft_score_0_0_a","MS Skor 0:0"),
            new ColumnDef("ft_score_1_1_a","MS Skor 1:1"), new ColumnDef("ft_score_2_2_a","MS Skor 2:2"),
            new ColumnDef("ft_score_3_3_a","MS Skor 3:3"), new ColumnDef("ft_score_4_4_a","MS Skor 4:4"),
            new ColumnDef("ft_score_0_1_a","MS Skor 0:1"), new ColumnDef("ft_score_0_2_a","MS Skor 0:2"),
            new ColumnDef("ft_score_1_2_a","MS Skor 1:2"), new ColumnDef("ft_score_0_3_a","MS Skor 0:3"),
            new ColumnDef("ft_score_1_3_a","MS Skor 1:3"), new ColumnDef("ft_score_2_3_a","MS Skor 2:3"),
            new ColumnDef("ft_score_0_4_a","MS Skor 0:4"), new ColumnDef("ft_score_1_4_a","MS Skor 1:4"),
            new ColumnDef("ft_score_2_4_a","MS Skor 2:4"), new ColumnDef("ft_score_3_4_a","MS Skor 3:4"),
            new ColumnDef("ft_score_0_5_a","MS Skor 0:5"), new ColumnDef("ft_score_1_5_a","MS Skor 1:5"),
            new ColumnDef("ft_score_2_5_a","MS Skor 2:5")
    );

    // displayName → index map (başlangıçta bir kez build edilir)
    private static final Map<String, Integer> NAME_TO_IDX;
    static {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
            m.put(ALL_ODDS_COLS.get(i).displayName, i);
        NAME_TO_IDX = Collections.unmodifiableMap(m);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MatchRecord
    // ═══════════════════════════════════════════════════════════════════════
    static class MatchRecord {
        String league, date, homeTeam, awayTeam, id;
        int htHome, htAway, ftHome, ftAway;
        double[] odds;

        MatchRecord(int size) { odds = new double[size]; }

        String ftScore()  { return (ftHome >= 0) ? ftHome + "-" + ftAway : "?-?"; }
        String htScore()  { return (htHome >= 0) ? htHome + "-" + htAway : "?-?"; }

        String htFtLabel() {
            if (htHome < 0 || htAway < 0 || ftHome < 0 || ftAway < 0) return "?/?";
            String ht = (htHome > htAway) ? "1" : (htHome < htAway ? "2" : "X");
            String ft = (ftHome > ftAway) ? "1" : (ftHome < ftAway ? "2" : "X");
            return ht + "/" + ft;
        }

        String ftSide() {
            if (ftHome < 0 || ftAway < 0) return "?";
            return (ftHome > ftAway) ? "1" : (ftHome < ftAway ? "2" : "X");
        }

        String fullDetail() {
            return "[" + id + "] " + homeTeam + " vs " + awayTeam
                    + " | İY:" + htScore()
                    + " MS:" + ftScore()
                    + " [HT/FT:" + htFtLabel() + "]"
                    + " [MS:" + ftSide() + "]";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DB
    // ═══════════════════════════════════════════════════════════════════════
    private Connection        conn;
    private List<MatchRecord> allRecords = new ArrayList<>();

    public Bet365FilterAnalyzer() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
            loadAllRecords();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void loadAllRecords() throws SQLException {
        System.out.println("📊 Veriler yükleniyor...");
        StringBuilder sb = new StringBuilder(
                "SELECT country_league,date_time,home_team,away_team,ht_iy,ft_ms,id");
        for (ColumnDef cd : ALL_ODDS_COLS) sb.append(",").append(cd.sqlColumn);
        sb.append(" FROM bet365_matches ORDER BY date_time DESC");

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sb.toString())) {
            while (rs.next()) {
                MatchRecord rec = new MatchRecord(ALL_ODDS_COLS.size());
                rec.league   = rs.getString("country_league");
                rec.date     = rs.getString("date_time");
                rec.homeTeam = rs.getString("home_team");
                rec.awayTeam = rs.getString("away_team");
                rec.id       = rs.getString("id");
                rec.htHome = rec.htAway = rec.ftHome = rec.ftAway = -1;

                String ht = rs.getString("ht_iy");
                if (ht != null && ht.contains("-")) {
                    String[] p = ht.split("-", 2);
                    try { rec.htHome = Integer.parseInt(p[0].trim());
                        rec.htAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                String ft = rs.getString("ft_ms");
                if (ft != null && ft.contains("-")) {
                    String[] p = ft.split("-", 2);
                    try { rec.ftHome = Integer.parseInt(p[0].trim());
                        rec.ftAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                for (int i = 0; i < ALL_ODDS_COLS.size(); i++) {
                    String v = rs.getString(ALL_ODDS_COLS.get(i).sqlColumn);
                    rec.odds[i] = parseOdds(v);
                }
                allRecords.add(rec);
            }
        }
        System.out.println("✅ " + allRecords.size() + " kayıt yüklendi.\n");
    }

    private double parseOdds(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return 0.0;
        try { return Double.parseDouble(s.replace(',', '.')); }
        catch (Exception ignored) { return 0.0; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Yardımcılar
    // ═══════════════════════════════════════════════════════════════════════
    private MatchRecord findById(String id) {
        for (MatchRecord r : allRecords)
            if (id.equals(r.id)) return r;
        return null;
    }

    /** displayName listesini → int[] col indekslerine çevirir */
    private int[] resolveColumns(List<String> names) {
        List<Integer> idxList = new ArrayList<>();
        for (String name : names) {
            Integer idx = NAME_TO_IDX.get(name.trim());
            if (idx == null) {
                System.out.println("  ⚠️  Bilinmeyen kolon adı: \"" + name + "\" — atlandı");
            } else {
                idxList.add(idx);
            }
        }
        return idxList.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Havuz: bu filtredeki kolonlarda target ile birebir eşleşen 1-3 maç */
    private List<MatchRecord> getPool(int[] cols, MatchRecord target) {
        List<MatchRecord> pool = new ArrayList<>();
        double[] tOdds = target.odds;
        for (MatchRecord r : allRecords) {
            if (r == target) continue;
            boolean ok = true;
            for (int col : cols) {
                if (r.odds[col] != tOdds[col]) { ok = false; break; }
            }
            if (ok) {
                pool.add(r);
                if (pool.size() > 3) return Collections.emptyList(); // çok geniş
            }
        }
        return pool;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ANA DÖNGÜ
    // ═══════════════════════════════════════════════════════════════════════
    public void run() {
        // Filtre setlerini başta int[] olarak derle (bir kez)
        List<int[]> compiledFilters = new ArrayList<>();
        System.out.println("📋 Yüklenen filtre setleri:");
        for (int fi = 0; fi < FILTER_SETS.size(); fi++) {
            List<String> names = FILTER_SETS.get(fi);
            int[] cols = resolveColumns(names);
            compiledFilters.add(cols);
            System.out.printf("  Filtre #%d: %s%n", fi + 1,
                    names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", ")));
        }
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("🔎 Maç ID girin (çıkmak için 'q'): ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q")) break;
            if (input.isEmpty()) continue;

            MatchRecord target = findById(input);
            if (target == null) {
                System.out.println("❌ ID bulunamadı: " + input + "\n");
                continue;
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.println("📌 Maç: " + target.fullDetail());
            System.out.printf("   Beklenti → HT/FT: %s | CS: %s | MS: %s%n",
                    target.htFtLabel(), target.ftScore(), target.ftSide());
            System.out.println("=".repeat(70));

            // Her filtre setini bu maça uygula
            for (int fi = 0; fi < compiledFilters.size(); fi++) {
                int[] cols        = compiledFilters.get(fi);
                List<String> names = FILTER_SETS.get(fi);

                System.out.printf("%n--- FİLTRE #%d ---%n", fi + 1);
                System.out.println("    Kolonlar: " +
                        names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", ")));

                if (cols.length == 0) {
                    System.out.println("    ⚠️  Geçerli kolon yok, atlandı.");
                    continue;
                }

                List<MatchRecord> pool = getPool(cols, target);

                if (pool.isEmpty()) {
                    System.out.println("    ❌ Havuz boş (0 veya 4+ eşleşme — filtre geçersiz)");
                    continue;
                }

                // Case kontrolü
                String tHtFt = target.htFtLabel();
                String tCs   = target.ftScore();
                String tMs   = target.ftSide();

                boolean htftOk = true, csOk = true, msOk = true;
                for (MatchRecord m : pool) {
                    if (!m.htFtLabel().equals(tHtFt)) htftOk = false;
                    if (!m.ftScore().equals(tCs))     csOk   = false;
                    if (!m.ftSide().equals(tMs))      msOk   = false;
                }

                // Başarı etiketi
                StringJoiner verdict = new StringJoiner(" | ");
                if (htftOk) verdict.add("HT/FT ✅");
                if (csOk)   verdict.add("Correct Score ✅");
                if (msOk)   verdict.add("MS Taraf ✅");
                String verdictStr = verdict.toString().isEmpty() ? "❌ Hiçbir case geçmedi" : verdict.toString();

                System.out.printf("    Havuz: %d maç  →  %s%n", pool.size(), verdictStr);

                // Havuz tablosu
                System.out.println("    " + "─".repeat(75));
                System.out.printf("    %-22s  %-22s  %-5s  %-5s  %-7s  %-7s  %-5s%n",
                        "Ev Sahibi", "Deplasman", "İY", "MS", "HT/FT", "CS", "MS");
                System.out.println("    " + "─".repeat(75));
                for (MatchRecord m : pool) {
                    System.out.printf("    %-22s  %-22s  %-5s  %-5s  %s %-4s  %s %-4s  %s %-3s%n",
                            trunc(m.homeTeam, 22),
                            trunc(m.awayTeam, 22),
                            m.htScore(), m.ftScore(),
                            m.htFtLabel().equals(tHtFt) ? "✅" : "❌", m.htFtLabel(),
                            m.ftScore().equals(tCs)     ? "✅" : "❌", m.ftScore(),
                            m.ftSide().equals(tMs)      ? "✅" : "❌", m.ftSide());
                }
                System.out.println("    " + "─".repeat(75));
                System.out.printf("    Hedef → HT/FT: %s | CS: %s | MS: %s%n",
                        tHtFt, tCs, tMs);
            }

            System.out.println("\n" + "=".repeat(70) + "\n");
        }

        System.out.println("👋 Çıkılıyor...");
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public static void main(String[] args) {
        new Bet365FilterAnalyzer().run();
    }
}