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
        // 「18:30」「8:05」のような形か確かめる（数字1〜2けた ＋ : ＋ 数字2けた）
        if (time == null || !time.matches("\\d{1,2}:\\d{2}")) {
            throw new InputException("時刻は 18:30 のように入力してください。");
        }
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour > 23 || minute > 59) {
            throw new InputException("時刻は 0:00〜23:59 の範囲で入力してください。");
        }
        return hour * 60 + minute;
    }

    // 入力内容がおかしいときに使うエラー。message には、画面にそのまま出せる説明を入れる
    static class InputException extends RuntimeException {
        private static final long serialVersionUID = 1L;  // Javaの決まりごと（気にしなくてOK）

        InputException(String message) {
            super(message);
        }
    }

    // 勤務と休憩の入力をチェックする。おかしければ InputException を投げる
    static void checkShift(int start, int end, int breakMinutes, String breakStartText) {
        int workMinutes = end - start;  // end は日付またぎの調整後
        if (workMinutes == 0) {
            throw new InputException("開始時刻と終了時刻が同じです。");
        }
        if (breakMinutes < 0) {
            throw new InputException("休憩は0分以上で入力してください。");
        }
        if (breakMinutes == 0) {
            return;  // 休憩なし
        }
        if (breakMinutes >= workMinutes) {
            throw new InputException("休憩が勤務時間（" + format(workMinutes) + "）以上になっています。");
        }
        if (breakStartText == null || breakStartText.isEmpty()) {
            throw new InputException("休憩がある場合は、休憩の開始時刻も入力してください。");
        }
        int breakStart = toMinutes(breakStartText);
        if (breakStart < start) {
            breakStart += 1440;  // 日付をまたいだ休憩
        }
        if (breakStart + breakMinutes > end) {
            throw new InputException("休憩の時間帯が、勤務時間の中に収まっていません。");
        }
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
        // 計算する前に、入力がおかしくないか確かめる（おかしければここで止まる）
        checkShift(start, end, breakMinutes, breakStartText);
        if (breakMinutes == 0) {
            breakStartText = "0:00";  // 休憩なしのときは、休憩の開始時刻は使わない
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

        long total;
        try {
            total = calculate(wage, startText, endText, breakMinutes, breakStartText, true);
        } catch (InputException e) {
            System.out.println(e.getMessage() + " 記録しませんでした。");
            return;
        }

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
        if (breakMinutes == 0) {
            breakStartText = "0:00";  // 休憩なしのときは、休憩の開始時刻を空欄にせず 0:00 で保存する
        }
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
        Settings settings = Settings.load();
        TreeSet<Integer> years = new TreeSet<>();
        years.add(LocalDate.now().getYear());
        for (Entry e : loadEntries()) {
            years.add(PayPeriod.of(e.date(), settings).payday().getYear());
        }
        return years;
    }

    // 集計の1行分（1回の給料日の分）
    record Row(PayPeriod period, int days, long total) {
    }

    // 1年分の集計結果をまとめて入れておく「入れ物」
    // rows には、その年に「支給される」給料が、給料日の順に入る
    record Summary(int year, List<Row> rows, long yearTotal, long fuyouLimit) {
        // 扶養ラインまでの残り（マイナスなら超えている）
        long remaining() {
            return fuyouLimit - yearTotal;
        }
    }

    // 記録を読み込んで、指定した年に「支給される」給料を、給料日ごとに集計する（コンソール版・Web版で共通）
    // 扶養は「給料をもらった日」で年を数えるので、働いた日ではなく給料日で年を分ける
    static Summary summarize(int year) throws IOException {
        Settings settings = Settings.load();
        List<Entry> entries = loadEntries();
        List<Row> rows = new ArrayList<>();
        long yearTotal = 0;

        // 前の年の10月締め〜今年の12月締めの中から、給料日がこの年になるものを探す
        YearMonth closing = YearMonth.of(year - 1, 10);
        while (!closing.isAfter(YearMonth.of(year, 12))) {
            PayPeriod period = PayPeriod.ofClosingMonth(closing, settings);
            if (period.payday().getYear() == year) {
                int days = 0;
                long total = 0;
                for (Entry e : entries) {
                    if (period.contains(e.date())) {
                        days++;
                        total += e.total();
                    }
                }
                if (days > 0) {
                    rows.add(new Row(period, days, total));
                    yearTotal += total;
                }
            }
            closing = closing.plusMonths(1);
        }
        return new Summary(year, rows, yearTotal, settings.fuyouLimit());
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
        for (Row r : s.rows()) {
            System.out.printf("%s支給（%s）: %2d日勤務  %,9d円%n",
                    r.period().paydayText(), r.period().rangeText(), r.days(), r.total());
        }
        System.out.printf("年間合計: %,d円%n", s.yearTotal());
        System.out.printf("扶養ライン: %,d円%n", s.fuyouLimit());
        if (s.remaining() < 0) {
            System.out.printf("⚠ 扶養ラインを %,d円 超えています！%n", -s.remaining());
            return;
        }
        System.out.printf("あと %,d円 まで働けます%n", s.remaining());
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