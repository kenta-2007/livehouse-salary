import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

// 給料の「締め」の期間と、給料日（支給日）を計算する
// ルール：締め日で区切り、締めた月の月末に支給。月末が土日・祝日なら、その前の平日
public record PayPeriod(LocalDate start, LocalDate end, LocalDate payday) {

    // 働いた日が、どの期間（いつ支給される分）に入るかを求める
    static PayPeriod of(LocalDate workDate, int closingDay) {
        // その日を含む期間の「締めの月」を求める
        YearMonth closingMonth = YearMonth.from(workDate);
        if (closingDay != Settings.END_OF_MONTH && workDate.getDayOfMonth() > closingDay) {
            closingMonth = closingMonth.plusMonths(1);  // 締め日を過ぎていたら、翌月締めの分
        }
        return ofClosingMonth(closingMonth, closingDay);
    }

    // 「〇月締め」の期間を求める
    static PayPeriod ofClosingMonth(YearMonth closingMonth, int closingDay) {
        LocalDate end = closingDate(closingMonth, closingDay);
        LocalDate start = closingDate(closingMonth.minusMonths(1), closingDay).plusDays(1);
        LocalDate payday = adjustToWeekday(closingMonth.atEndOfMonth());
        return new PayPeriod(start, end, payday);
    }

    // 指定した月の締め日（末日締めなら、その月の最終日）
    static LocalDate closingDate(YearMonth month, int closingDay) {
        return closingDay == Settings.END_OF_MONTH ? month.atEndOfMonth() : month.atDay(closingDay);
    }

    // 土日・祝日なら、前の平日にずらす
    static LocalDate adjustToWeekday(LocalDate date) {
        while (isHoliday(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    // 土日か、月末に来る可能性がある祝日か
    // （月末にかかる祝日は、4/29（昭和の日）が日曜日のときの、4/30の振替休日だけ）
    static boolean isHoliday(LocalDate date) {
        DayOfWeek w = date.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) {
            return true;
        }
        boolean showaDayOnSunday = LocalDate.of(date.getYear(), 4, 29).getDayOfWeek() == DayOfWeek.SUNDAY;
        return date.getMonthValue() == 4 && date.getDayOfMonth() == 30 && showaDayOnSunday;
    }

    // 「9/21〜10/20」のような表示
    String rangeText() {
        return start.getMonthValue() + "/" + start.getDayOfMonth() + "〜"
             + end.getMonthValue() + "/" + end.getDayOfMonth();
    }

    // 「10月30日(金)」のような表示
    String paydayText() {
        String[] youbi = {"月", "火", "水", "木", "金", "土", "日"};
        return payday.getMonthValue() + "月" + payday.getDayOfMonth() + "日("
             + youbi[payday.getDayOfWeek().getValue() - 1] + ")";
    }
}
