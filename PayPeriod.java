import java.time.LocalDate;
import java.time.YearMonth;

// 給料の「締め」の期間と、給料日（支給日）を計算する
// closingMonth は「何月締めの分か」（記録の一覧を開くときの目印にも使う）
public record PayPeriod(YearMonth closingMonth, LocalDate start, LocalDate end, LocalDate payday) {

    // 働いた日が、どの期間（いつ支給される分）に入るかを求める
    static PayPeriod of(LocalDate workDate, Settings s) {
        YearMonth closingMonth = YearMonth.from(workDate);
        if (s.closingDay() != Settings.END_OF_MONTH && workDate.getDayOfMonth() > s.closingDay()) {
            closingMonth = closingMonth.plusMonths(1);  // 締め日を過ぎていたら、翌月締めの分
        }
        return ofClosingMonth(closingMonth, s);
    }

    // 「〇月締め」の期間と給料日を求める
    static PayPeriod ofClosingMonth(YearMonth closingMonth, Settings s) {
        LocalDate end = dayOf(closingMonth, s.closingDay());
        LocalDate start = dayOf(closingMonth.minusMonths(1), s.closingDay()).plusDays(1);
        LocalDate payday = dayOf(closingMonth.plusMonths(s.paydayMonth()), s.paydayDay());
        // 土日・祝日なら、前の平日にずらす
        while (Holidays.isDayOff(payday)) {
            payday = payday.minusDays(1);
        }
        return new PayPeriod(closingMonth, start, end, payday);
    }

    // 指定した月の「〇日」（末日なら、その月の最終日）
    static LocalDate dayOf(YearMonth month, int day) {
        return day == Settings.END_OF_MONTH ? month.atEndOfMonth() : month.atDay(day);
    }

    // その日が、この期間に入っているか
    boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
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
