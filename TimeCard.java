import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// タイムカード：出勤中の状態をファイルに保存しながら、ボタンで打刻する
public class TimeCard {

    // 出勤中の状態を保存するファイル（退勤したら消える）
    static final String PUNCH_FILE = "punch.csv";
    static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    // 今の時刻を知るための時計（テストのときは、決まった時刻の時計に差し替えられる）
    static Clock clock = Clock.systemDefaultZone();

    // 今の状態（出勤日,時給,出勤時刻,休憩開始,休憩終了）。休憩していなければ空文字
    record Punch(String date, int wage, String start, String breakStart, String breakEnd) {
        boolean onBreak() {
            return !breakStart.isEmpty() && breakEnd.isEmpty();
        }
        boolean breakTaken() {
            return !breakEnd.isEmpty();
        }
    }

    // 今の時刻を「18:05」の形で返す
    static String now() {
        return LocalTime.now(clock).format(HHMM);
    }

    // 出勤中の状態を読み込む。出勤していなければ null
    static Punch load() throws IOException {
        Path path = Path.of(PUNCH_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        String[] c = Files.readString(path).trim().split(",", -1);
        return new Punch(c[0], Integer.parseInt(c[1]), c[2], c[3], c[4]);
    }

    static void save(Punch p) throws IOException {
        String line = p.date() + "," + p.wage() + "," + p.start() + "," + p.breakStart() + "," + p.breakEnd();
        Files.writeString(Path.of(PUNCH_FILE), line);
    }

    // 出勤
    static String clockIn(int wage) throws IOException {
        if (load() != null) {
            return "すでに出勤中です。";
        }
        save(new Punch(LocalDate.now(clock).toString(), wage, now(), "", ""));
        Salary.saveWage(wage);  // 次からの初期値にする
        return now() + " に出勤しました。";
    }

    // 休憩開始
    static String startBreak() throws IOException {
        Punch p = load();
        if (p == null) {
            return "まだ出勤していません。";
        }
        if (p.onBreak() || p.breakTaken()) {
            return "休憩は1回までです。";
        }
        save(new Punch(p.date(), p.wage(), p.start(), now(), ""));
        return now() + " から休憩します。";
    }

    // 休憩終了
    static String endBreak() throws IOException {
        Punch p = load();
        if (p == null || !p.onBreak()) {
            return "休憩中ではありません。";
        }
        save(new Punch(p.date(), p.wage(), p.start(), p.breakStart(), now()));
        return now() + " に休憩を終えました。";
    }

    // 退勤：給料を計算して records.csv に保存し、出勤中の状態を消す
    static String clockOut() throws IOException {
        Punch p = load();
        if (p == null) {
            return "まだ出勤していません。";
        }
        String end = now();
        // 休憩中のまま退勤したら、退勤時刻で休憩も終わったことにする
        String breakEnd = p.onBreak() ? end : p.breakEnd();

        int breakMinutes = 0;
        String breakStart = "0:00";
        if (!p.breakStart().isEmpty()) {
            breakStart = p.breakStart();
            breakMinutes = Salary.toMinutes(breakEnd) - Salary.toMinutes(breakStart);
            if (breakMinutes < 0) {
                breakMinutes += 1440;  // 休憩中に日付をまたいだ場合
            }
        }

        Salary.PayResult r = Salary.calculateDetail(p.wage(), p.start(), end, breakMinutes, breakStart);
        Salary.saveRecord(LocalDate.parse(p.date()), p.wage(), p.start(), end, breakMinutes, breakStart, r.total());
        Files.delete(Path.of(PUNCH_FILE));
        return p.start() + "〜" + end + " の勤務を記録しました。給料は "
             + String.format("%,d", r.total()) + "円 です。";
    }
}
