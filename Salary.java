import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

public class Salary {

    // 深夜の時間帯（0時からの分）
    static final int NIGHT_START = 22 * 60;      // 22:00 = 1320分
    static final int NIGHT_END = (24 + 5) * 60;  // 翌5:00 = 1740分
    // 1日の法定労働時間（これを超えたら残業）
    static final int LEGAL_MINUTES = 8 * 60;      // 8時間 = 480分

        // 勤務記録を保存するファイル
    static final String RECORD_FILE = "records.csv";

        // 扶養ライン（年収の上限）。自分の家庭の条件に合わせて変えてください
    static final long FUYOU_LIMIT = 1500000;

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

    // 1日分の給料を計算する。show が true なら内訳も表示する
    static long calculate(int wage, String startText, String endText,
                          int breakMinutes, String breakStartText, boolean show) {
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

        if (show) {
            System.out.println("通常    : " + format(baseNormal) + " → " + Math.round(pay1) + "円");
            System.out.println("深夜    : " + format(baseNight) + " → " + Math.round(pay2) + "円");
            System.out.println("残業    : " + format(overtimeNormalMinutes) + " → " + Math.round(pay3) + "円");
            System.out.println("深夜残業: " + format(overtimeNightMinutes) + " → " + Math.round(pay4) + "円");
            System.out.println("合計    : " + total + "円");
        }
        return total;
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

        // CSV形式（カンマ区切り）で1行追記する
        String line = date + "," + wage + "," + startText + "," + endText + ","
                    + breakMinutes + "," + breakStartText + "," + total;
        try (FileWriter writer = new FileWriter(RECORD_FILE, true)) {
            writer.write(line + "\n");
            System.out.println("記録しました！");
        } catch (IOException e) {
            System.out.println("保存に失敗しました: " + e.getMessage());
        }
    }

        // 記録ファイルを読み込んで、月ごと・年間の合計と扶養ラインまでの残りを表示する
    static void showSummary(Scanner sc) {
        Path path = Path.of(RECORD_FILE);
        if (!Files.exists(path)) {
            System.out.println("まだ記録がありません。先に「1: 勤務を記録する」を使ってください。");
            return;
        }

        System.out.print("集計する年（例 2026）: ");
        int year = sc.nextInt();

        long[] monthTotal = new long[13];  // monthTotal[1]〜[12] に各月の合計を入れる
        int[] monthDays = new int[13];     // 各月の勤務日数

        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            System.out.println("記録の読み込みに失敗しました: " + e.getMessage());
            return;
        }

        for (String line : lines) {
            if (line.isBlank()) {
                continue;  // 空行は飛ばす
            }
            // 日付,時給,開始,終了,休憩,休憩開始,給料
            String[] cols = line.split(",");
            LocalDate date = LocalDate.parse(cols[0]);
            if (date.getYear() != year) {
                continue;  // 違う年の記録は飛ばす
            }
            long pay = Long.parseLong(cols[6]);
            monthTotal[date.getMonthValue()] += pay;
            monthDays[date.getMonthValue()]++;
        }

        long yearTotal = 0;
        System.out.println();
        System.out.println("==== " + year + "年の集計 ====");
        for (int m = 1; m <= 12; m++) {
            if (monthDays[m] > 0) {
                System.out.printf("%2d月: %2d日勤務  %,9d円%n", m, monthDays[m], monthTotal[m]);
            }
            yearTotal += monthTotal[m];
        }
        System.out.printf("年間合計: %,d円%n", yearTotal);

        // 扶養ラインまでの残り
        long remaining = FUYOU_LIMIT - yearTotal;
        System.out.printf("扶養ライン: %,d円%n", FUYOU_LIMIT);
        if (remaining < 0) {
            System.out.printf("⚠ 扶養ラインを %,d円 超えています！%n", -remaining);
            return;
        }
        System.out.printf("あと %,d円 まで働けます%n", remaining);

        // 今年の集計なら、今のペースで12月まで働いた場合の見込みも出す
        LocalDate today = LocalDate.now();
        if (year == today.getYear()) {
            int monthsSoFar = today.getMonthValue();
            long forecast = yearTotal * 12 / monthsSoFar;
            System.out.printf("今のペースだと年収の見込みは %,d円 です%n", forecast);
            if (forecast > FUYOU_LIMIT) {
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
                System.out.println("終了します。お疲れさまでした！");
                break;
            } else {
                System.out.println("1〜3の番号を入力してください。");
            }
        }
    }
}