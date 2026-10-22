import java.util.Scanner;

public class Salary {

    // "18:30" のような時刻を、0時からの「分」に変換する
    static int toMinutes(String time) {
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        return hour * 60 + minute;
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        System.out.print("時給（円）: ");
        int wage = sc.nextInt();
        System.out.print("開始時刻（例 18:00）: ");
        String startText = sc.next();
        System.out.print("終了時刻（例 23:30）: ");
        String endText = sc.next();

        int start = toMinutes(startText);
        int end = toMinutes(endText);
        int workMinutes = end - start;

        System.out.println("勤務時間: " + workMinutes / 60 + "時間" + workMinutes % 60 + "分");
    }
}