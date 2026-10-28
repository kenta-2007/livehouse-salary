import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// 時給・締め日・給料日・扶養ラインの設定を、ファイルに保存・読み込みする
// paydayMonth：締めた月の何か月後に払うか（0＝当月、1＝翌月）
// paydayDay：給料日（1〜28日、または末日）
public record Settings(int wage, int closingDay, int paydayMonth, int paydayDay, long fuyouLimit) {

    // 設定を保存するファイル（GitHubには上げない）
    static final String SETTINGS_FILE = "settings.properties";
    // 締め日・給料日を「末日」にするときの値
    static final int END_OF_MONTH = 31;

    // まだ設定していないときの初期値（20日締め、当月末払い）
    static final Settings DEFAULT = new Settings(1200, 20, 0, END_OF_MONTH, 1_500_000);

    // 設定を読み込む。ファイルがなければ初期値を使う
    static Settings load() {
        Path path = Path.of(SETTINGS_FILE);
        if (!Files.exists(path)) {
            return migrateOldWage();
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            p.load(reader);
            return new Settings(
                    Integer.parseInt(p.getProperty("wage", String.valueOf(DEFAULT.wage()))),
                    Integer.parseInt(p.getProperty("closingDay", String.valueOf(DEFAULT.closingDay()))),
                    Integer.parseInt(p.getProperty("paydayMonth", String.valueOf(DEFAULT.paydayMonth()))),
                    Integer.parseInt(p.getProperty("paydayDay", String.valueOf(DEFAULT.paydayDay()))),
                    Long.parseLong(p.getProperty("fuyouLimit", String.valueOf(DEFAULT.fuyouLimit()))));
        } catch (IOException | NumberFormatException e) {
            return DEFAULT;
        }
    }

    // 設定をファイルに保存する
    void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("wage", String.valueOf(wage));
        p.setProperty("closingDay", String.valueOf(closingDay));
        p.setProperty("paydayMonth", String.valueOf(paydayMonth));
        p.setProperty("paydayDay", String.valueOf(paydayDay));
        p.setProperty("fuyouLimit", String.valueOf(fuyouLimit));
        try (Writer writer = Files.newBufferedWriter(Path.of(SETTINGS_FILE))) {
            p.store(writer, "livehouse-salary settings");
        }
    }

    // 入力された値が正しい範囲か確認する。おかしければ理由を返し、問題なければ null
    String validate() {
        if (wage < 1 || wage > 100_000) {
            return "時給は1〜100,000円で入力してください。";
        }
        if (!isDayValue(closingDay)) {
            return "締め日は1〜28日、または末日を選んでください。";
        }
        if (paydayMonth != 0 && paydayMonth != 1) {
            return "給料日は「当月」か「翌月」を選んでください。";
        }
        if (!isDayValue(paydayDay)) {
            return "給料日は1〜28日、または末日を選んでください。";
        }
        if (paydayMonth == 0 && closingDay != END_OF_MONTH
                && paydayDay != END_OF_MONTH && paydayDay <= closingDay) {
            return "当月払いの場合、給料日は締め日より後の日にしてください。";
        }
        if (paydayMonth == 0 && closingDay == END_OF_MONTH) {
            return "末日締めの場合、給料日は翌月にしてください。";
        }
        if (fuyouLimit < 0 || fuyouLimit > 100_000_000) {
            return "扶養ラインは0〜1億円で入力してください。";
        }
        return null;
    }

    // 1〜28日、または末日か
    private static boolean isDayValue(int day) {
        return (day >= 1 && day <= 28) || day == END_OF_MONTH;
    }

    // 前のバージョンで保存していた時給（wage.txt）があれば、それを引き継ぐ
    private static Settings migrateOldWage() {
        try {
            int oldWage = Integer.parseInt(Files.readString(Path.of("wage.txt")).trim());
            return new Settings(oldWage, DEFAULT.closingDay(), DEFAULT.paydayMonth(),
                    DEFAULT.paydayDay(), DEFAULT.fuyouLimit());
        } catch (IOException | NumberFormatException e) {
            return DEFAULT;
        }
    }
}
