import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.TreeSet;

public class Salary {

    // 深夜の時間帯（0時からの分）
    static final int NIGHT_START = 22 * 60;      // 22:00 = 1320分
    static final int NIGHT_END = (24 + 5) * 60;  // 翌5:00 = 1740分
    // 1日の法定労働時間（これを超えたら残業）
    static final int LEGAL_MINUTES = 8 * 60;      // 8時間 = 480分

    // 勤務記録を保存するファイル
    static final String RECORD_FILE = "records.csv";

    // 時給・締め日・扶養ラインは、設定画面で変えられる（Settings.java）

    // "18:30" のような時刻を、0時からの「分」に変換する
    static int toMinutes(String time) {
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        return hour * 60 + minute;
    }

    // 2つの時間帯 [aStart, aEnd] と [bStart, bEnd] が重なっている長さ（分）
    static int overlap(int aStart, int aEnd, int bStart, int bEnd) {
        int length = Math.min(aEnd, bEnd) - Math.max(aStart, bStart);
        return Math.max(length, 0);
    }

    // ある時間帯のうち、深夜帯（22:00〜翌5:00、0:00〜5:00）に入っている長さ
    static int nightOverlap(int from, int to) {
        return overlap(from, to, NIGHT_START, NIGHT_END)
             + overlap(from, to, 0, 5 * 60);
    }

    // 分を「〇時間〇分」の文字にする
    static String format(int minutes) {
        return minutes / 60 + "時間" + String.format("%02d", minutes % 60) + "分";
    }

    // 給料の内訳をまとめて入れておく「入れ物」
    record PayResult(int normalMinutes, long normalPay,
                     int nightMinutes, long nightPay,
                     int overtimeMinutes, long overtimePay,
                     int overtimeNightMinutes, long overtimeNightPay,
                     long total) {
    }

    // 1日分の給料を計算して、内訳をまとめて返す
    static PayResult calculateDetail(int wage, String startText, String endText,
                                     int breakMinutes, String breakStartText) {
        int start = toMinutes(startText);
        int end = toMinutes(endText);

        // 終了が開始より前なら、日付をまたいだとみなして24時間（1440分）足す
        if (end < start) {
            end += 1440;
        }
        int workMinutes = end - start;

        // 休憩の時間帯（勤務の開始より前なら、次の日の時刻とみなす）
        int breakStart = toMinutes(breakStartText);
        if (breakStart < start) {
            breakStart += 1440;
        }
        int breakEnd = breakStart + breakMinutes;

        // 勤務全体・休憩のうち深夜帯にある時間
        int nightAll = nightOverlap(start, end);
        int nightBreak = nightOverlap(breakStart, breakEnd);

        // 実際に働いた深夜時間と通常時間
        int nightMinutes = nightAll - nightBreak;
        int normalMinutes = (workMinutes - nightAll) - (breakMinutes - nightBreak);

        // ---- 残業（1日8時間を超えた分）----
        int overtimeStart = start + LEGAL_MINUTES;
        if (breakMinutes > 0 && breakStart < overtimeStart) {
            overtimeStart += breakMinutes;
        }
        int overtimeMinutes = 0;
        int overtimeNightMinutes = 0;
        if (overtimeStart < end) {
            overtimeMinutes = end - overtimeStart;
            overtimeNightMinutes = nightOverlap(overtimeStart, end);
            if (breakMinutes > 0 && breakStart >= overtimeStart) {
                overtimeMinutes -= breakMinutes;
                overtimeNightMinutes -= nightBreak;
            }
        }
        int overtimeNormalMinutes = overtimeMinutes - overtimeNightMinutes;

        // ---- 4つの区分に分ける ----
        int baseNormal = normalMinutes - overtimeNormalMinutes;  // 通常（1.00倍）
        int baseNight = nightMinutes - overtimeNightMinutes;     // 深夜（1.25倍）

        double pay1 = wage * 1.00 * baseNormal / 60.0;
        double pay2 = wage * 1.25 * baseNight / 60.0;
        double pay3 = wage * 1.25 * overtimeNormalMinutes / 60.0;
        double pay4 = wage * 1.50 * overtimeNightMinutes / 60.0;
        long total = Math.round(pay1 + pay2 + pay3 + pay4);

        return new PayResult(baseNormal, Math.round(pay1),
                             baseNight, Math.round(pay2),
                             overtimeNormalMinutes, Math.round(pay3),
                             overtimeNightMinutes, Math.round(pay4),
                             total);
    }

    // 1日分の給料を計算する。show が true なら内訳も表示する（コンソール版用）
    static long calculate(int wage, String startText, String endText,
                          int breakMinutes, String breakStartText, boolean show) {
        PayResult r = calculateDetail(wage, startText, endText, breakMinutes, breakStartText);
        if (show) {
            System.out.println("通常    : " + format(r.normalMinutes()) + " → " + r.normalPay() + "円");
            System.out.println("深夜    : " + format(r.nightMinutes()) + " → " + r.nightPay() + "円");
            System.out.println("残業    : " + format(r.overtimeMinutes()) + " → " + r.overtimePay() + "円");
            System.out.println("深夜残業: " + format(r.overtimeNightMinutes()) + " → " + r.overtimeNightPay() + "円");
            System.out.println("合計    : " + r.total() + "円");
        }
        return r.total();
    }

    // 勤務を1日分入力して、給料を表示し、ファイルに保存する
    static void recordWork(Scanner sc) {
        // 日付は「2026-10-27」の形で入力してもらう（間違っていたら聞き直す）
        LocalDate date = null;
        while (date == null) {
            System.out.print("日付（例 2026-10-27）: ");
            try {
                date = LocalDate.parse(sc.next());
            } catch (DateTimeParseException e) {
                System.out.println("日付の形が正しくありません。2026-10-27 のように入力してください。");
            }
        }

        System.out.print("時給（円）: ");
        int wage = sc.nextInt();
        System.out.print("開始時刻（例 18:00）: ");
        String startText = sc.next();
        System.out.print("終了時刻（例 23:30）: ");
        String endText = sc.next();
        System.out.print("休憩（分、なしなら0）: ");
        int breakMinutes = sc.nextInt();
        String breakStartText = "0:00";
        if (breakMinutes > 0) {
            System.out.print("休憩の開始時刻（例 23:00）: ");
            breakStartText = sc.next();
        }

        long total = calculate(wage, startText, endText, breakMinutes, breakStartText, true);

        try {
            saveRecord(date, wage, startText, endText, breakMinutes, breakStartText, total);
            System.out.println("記録しました！");
        } catch (IOException e) {
            System.out.println("保存に失敗しました: " + e.getMessage());
        }
    }

    // 勤務1日分を、CSVの1行の形（カンマ区切り）にする
    static String toLine(LocalDate date, int wage, String startText, String endText,
                         int breakMinutes, String breakStartText, long total) {
        return date + "," + wage + "," + startText + "," + endText + ","
             + breakMinutes + "," + breakStartText + "," + total;
    }

    // 勤務1日分を、CSV形式でファイルに1行追記する（コンソール版・Web版で共通）
    static void saveRecord(LocalDate date, int wage, String startText, String endText,
                           int breakMinutes, String breakStartText, long total) throws IOException {
        String line = toLine(date, wage, startText, endText, breakMinutes, breakStartText, total);
        try (FileWriter writer = new FileWriter(RECORD_FILE, true)) {
            writer.write(line + "\n");
        }
    }

    // ---- 記録の一覧・修正・削除 ----

    // 記録1件分の「入れ物」。index はファイルの何行目か（0から数える）、check は確認用の番号
    record Entry(int index, String check, LocalDate date, int wage, String start, String end,
                 int breakMinutes, String breakStart, long total) {
    }

    // 1行の中身から、確認用の番号を作る（中身が1文字でも違えば、ほぼ確実に別の番号になる）
    static String checkOf(String line) {
        return Integer.toHexString(line.hashCode());
    }

    // 記録をすべて読み込む（ファイルの行の順番のまま）
    static List<Entry> loadEntries() throws IOException {
        List<Entry> entries = new ArrayList<>();
        Path path = Path.of(RECORD_FILE);
        if (!Files.exists(path)) {
            return entries;
        }
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(",");
            entries.add(new Entry(i, checkOf(line), LocalDate.parse(c[0]), Integer.parseInt(c[1]),
                    c[2], c[3], Integer.parseInt(c[4]), c[5], Long.parseLong(c[6])));
        }
        return entries;
    }

    // index 行目の記録を1件取り出す。確認用の番号が合わなければ null
    static Entry findEntry(int index, String check) throws IOException {
        for (Entry e : loadEntries()) {
            if (e.index() == index && e.check().equals(check)) {
                return e;
            }
        }
        return null;
    }

    // index 行目を newLine に置き換える（newLine が null なら削除）。
    // 確認用の番号が合わないとき（その間に記録が変わったとき）は何もせず false を返す
    static boolean replaceLine(int index, String check, String newLine) throws IOException {
        Path path = Path.of(RECORD_FILE);
        if (!Files.exists(path)) {
            return false;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        if (index < 0 || index >= lines.size() || !checkOf(lines.get(index)).equals(check)) {
            return false;
        }
        if (newLine == null) {
            lines.remove(index);
        } else {
            lines.set(index, newLine);
        }
        Files.write(path, lines);
        return true;
    }

    // 記録がある「給料をもらった年」の一覧（今年も必ず含める）を、古い順に返す
    static TreeSet<Integer> recordYears() throws IOException {
        int closingDay = Settings.load().closingDay();
        TreeSet<Integer> years = new TreeSet<>();
        years.add(LocalDate.now().getYear());
        for (Entry e : loadEntries()) {
            years.add(PayPeriod.of(e.date(), closingDay).payday().getYear());
        }
        return years;
    }

    // 1年分の集計結果をまとめて入れておく「入れ物」
    // monthTotal[m] などは、その年の m 月に「支給される」分（m 月の給料日にもらえる分）
    record Summary(int year, long[] monthTotal, int[] monthDays, PayPeriod[] periods,
                   long yearTotal, long fuyouLimit, int closingDay) {
        // 扶養ラインまでの残り（マイナスなら超えている）
        long remaining() {
            return fuyouLimit - yearTotal;
        }

        // 今年なら、今のペースで12月支給分まで働いた場合の年収見込み。今年でなければ -1
        long forecast() {
            LocalDate today = LocalDate.now();
            if (year != today.getYear()) {
                return -1;
            }
            // 今日働いた分が何月に支給されるか（＝その月までの分を、すでに働いたことになる）
            PayPeriod now = PayPeriod.of(today, closingDay);
            if (now.payday().getYear() != year) {
                return yearTotal;  // 12月支給分の期間が終わっていれば、年収はもう確定
            }
            return yearTotal * 12 / now.payday().getMonthValue();
        }
    }

    // 記録を読み込んで、指定した年に「支給される」給料を、支給月ごとに集計する（コンソール版・Web版で共通）
    // 扶養は「給料をもらった日」で年を数えるので、働いた日ではなく給料日で年を分ける
    static Summary summarize(int year) throws IOException {
        Settings settings = Settings.load();
        long[] monthTotal = new long[13];  // monthTotal[1]〜[12] に、各月の給料日に支給される合計を入れる
        int[] monthDays = new int[13];     // 各月の支給分の勤務日数
        PayPeriod[] periods = new PayPeriod[13];
        for (int m = 1; m <= 12; m++) {
            periods[m] = PayPeriod.ofClosingMonth(YearMonth.of(year, m), settings.closingDay());
        }

        for (Entry e : loadEntries()) {
            LocalDate payday = PayPeriod.of(e.date(), settings.closingDay()).payday();
            if (payday.getYear() != year) {
                continue;  // ほかの年に支給される分は飛ばす
            }
            monthTotal[payday.getMonthValue()] += e.total();
            monthDays[payday.getMonthValue()]++;
        }

        long yearTotal = 0;
        for (int m = 1; m <= 12; m++) {
            yearTotal += monthTotal[m];
        }
        return new Summary(year, monthTotal, monthDays, periods, yearTotal,
                settings.fuyouLimit(), settings.closingDay());
    }

    // 集計をコンソールに表示する
    static void showSummary(Scanner sc) {
        System.out.print("集計する年（例 2026）: ");
        int year = sc.nextInt();

        Summary s;
        try {
            s = summarize(year);
        } catch (IOException e) {
            System.out.println("記録の読み込みに失敗しました: " + e.getMessage());
            return;
        }

        System.out.println();
        System.out.println("==== " + year + "年に支給される給料の集計 ====");
        for (int m = 1; m <= 12; m++) {
            if (s.monthDays()[m] > 0) {
                System.out.printf("%2d月支給（%s）: %2d日勤務  %,9d円%n",
                        m, s.periods()[m].rangeText(), s.monthDays()[m], s.monthTotal()[m]);
            }
        }
        System.out.printf("年間合計: %,d円%n", s.yearTotal());
        System.out.printf("扶養ライン: %,d円%n", s.fuyouLimit());
        if (s.remaining() < 0) {
            System.out.printf("⚠ 扶養ラインを %,d円 超えています！%n", -s.remaining());
            return;
        }
        System.out.printf("あと %,d円 まで働けます%n", s.remaining());
        if (s.forecast() >= 0) {
            System.out.printf("今のペースだと年収の見込みは %,d円 です%n", s.forecast());
            if (s.forecast() > s.fuyouLimit()) {
                System.out.println("⚠ このペースだと扶養ラインを超えそうです。シフトを調整しましょう。");
            }
        }
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.println();
            System.out.println("==== 給料計算アプリ ====");
            System.out.println("1: 勤務を記録する");
            System.out.println("2: 月・年の合計を見る");
            System.out.println("3: 終了");
            System.out.print("番号を選んでください: ");
            String choice = sc.next();

            if (choice.equals("1")) {
                recordWork(sc);
            } else if (choice.equals("2")) {
                showSummary(sc);
            } else if (choice.equals("3")) {
                System.out.println("終了します。お疲れさまでした！");
                break;
            } else {
                System.out.println("1〜3の番号を入力してください。");
            }
        }
    }
}