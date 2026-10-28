import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

// 日本の祝日を判定する（今の「国民の祝日に関する法律」のルールで計算する）
public class Holidays {

    // 土日・祝日（振替休日・国民の休日を含む）か
    static boolean isDayOff(LocalDate date) {
        DayOfWeek w = date.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || isHoliday(date);
    }

    // 祝日（振替休日・国民の休日を含む）か
    static boolean isHoliday(LocalDate date) {
        if (isNamedHoliday(date)) {
            return true;
        }
        // 振替休日：祝日が日曜日のとき、その後の最初の「祝日でない日」が休みになる
        LocalDate d = date.minusDays(1);
        while (isNamedHoliday(d)) {
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return true;
            }
            d = d.minusDays(1);
        }
        // 国民の休日：前の日と次の日がどちらも祝日の日（日曜日を除く）
        return date.getDayOfWeek() != DayOfWeek.SUNDAY
                && isNamedHoliday(date.minusDays(1)) && isNamedHoliday(date.plusDays(1));
    }

    // 名前のある祝日か（振替休日などは含まない）
    static boolean isNamedHoliday(LocalDate date) {
        int y = date.getYear();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        return switch (m) {
            case 1 -> d == 1 || date.equals(nthMonday(y, 1, 2));          // 元日、成人の日
            case 2 -> d == 11 || d == 23;                                  // 建国記念の日、天皇誕生日
            case 3 -> d == vernalEquinox(y);                               // 春分の日
            case 4 -> d == 29;                                             // 昭和の日
            case 5 -> d == 3 || d == 4 || d == 5;                          // 憲法記念日、みどりの日、こどもの日
            case 7 -> date.equals(nthMonday(y, 7, 3));                     // 海の日
            case 8 -> d == 11;                                             // 山の日
            case 9 -> date.equals(nthMonday(y, 9, 3)) || d == autumnalEquinox(y); // 敬老の日、秋分の日
            case 10 -> date.equals(nthMonday(y, 10, 2));                   // スポーツの日
            case 11 -> d == 3 || d == 23;                                  // 文化の日、勤労感謝の日
            default -> false;
        };
    }

    // その月の第n月曜日
    static LocalDate nthMonday(int year, int month, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.MONDAY));
    }

    // 春分の日（1980〜2099年で使える計算式）
    static int vernalEquinox(int year) {
        return (int) (20.8431 + 0.242194 * (year - 1980)) - (year - 1980) / 4;
    }

    // 秋分の日（1980〜2099年で使える計算式）
    static int autumnalEquinox(int year) {
        return (int) (23.2488 + 0.242194 * (year - 1980)) - (year - 1980) / 4;
    }
}
