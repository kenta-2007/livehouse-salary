import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// 時給・締め日・扶養ラインなどの設定を、ファイルに保存・読み込みする
public record Settings(int wage, int closingDay, long fuyouLimit) {

    // 設定を保存するファイル（GitHubには上げない）
    static final String SETTINGS_FILE = "settings.properties";
    // 締め日を「末日」にするときの値
    static final int END_OF_MONTH = 31;

    // まだ設定していないときの初期値
    static final Settings DEFAULT = new Settings(1200, 20, 1_500_000);

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
        if (!(closingDay >= 1 && closingDay <= 28) && closingDay != END_OF_MONTH) {
            return "締め日は1〜28日、または末日を選んでください。";
        }
        if (fuyouLimit < 0 || fuyouLimit > 100_000_000) {
            return "扶養ラインは0〜1億円で入力してください。";
        }
        return null;
    }

    // 前のバージョンで保存していた時給（wage.txt）があれば、それを引き継ぐ
    private static Settings migrateOldWage() {
        try {
            int oldWage = Integer.parseInt(Files.readString(Path.of("wage.txt")).trim());
            return new Settings(oldWage, DEFAULT.closingDay(), DEFAULT.fuyouLimit());
        } catch (IOException | NumberFormatException e) {
            return DEFAULT;
        }
    }
}
