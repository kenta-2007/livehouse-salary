import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class WebApp {

    // すべてのページで共通の枠（%s のところに各ページの中身が入る）
    static final String LAYOUT = """
        <!DOCTYPE html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>給料計算</title>
          <style>
            body { font-family: sans-serif; max-width: 460px; margin: 24px auto; padding: 0 16px; }
            nav { display: flex; gap: 16px; margin-bottom: 8px; }
            label { display: block; margin-top: 12px; }
            input { width: 100%%; padding: 8px; font-size: 16px; box-sizing: border-box; }
            .buttons { display: flex; gap: 8px; margin-top: 16px; }
            button { flex: 1; padding: 12px; font-size: 16px; }
            .result { margin-top: 20px; padding: 16px; background: #eef6f4; border-radius: 8px; font-size: 20px; }
            .error { margin-top: 20px; color: #b4471f; }
            .warn { margin-top: 12px; padding: 12px; background: #fbe7dd; color: #8a2e10; border-radius: 8px; }
            table { width: 100%%; margin-top: 12px; border-collapse: collapse; }
            th, td { padding: 8px; border-bottom: 1px solid #ccd; text-align: left; }
            td.num, th.num { text-align: right; }
          </style>
        </head>
        <body>
          <nav><a href="/">計算・記録</a><a href="/summary">集計</a></nav>
          %s
        </body>
        </html>
        """;

    // 入力フォーム（%s のところに今日の日付と、計算結果が入る）
    static final String FORM = """
        <h1>ライブハウスバイト給料計算</h1>
        <form action="/" method="get">
          <label>日付（記録するとき）<input type="date" name="date" value="%s"></label>
          <label>時給（円）<input type="number" name="wage" value="1200" required></label>
          <label>開始時刻<input type="time" name="start" required></label>
          <label>終了時刻<input type="time" name="end" required></label>
          <label>休憩（分）<input type="number" name="break" value="0" min="0"></label>
          <label>休憩の開始時刻（休憩がある場合）<input type="time" name="breakStart"></label>
          <div class="buttons">
            <button type="submit">計算する</button>
            <button type="submit" formaction="/record" formmethod="post">記録する</button>
          </div>
        </form>
        %s
        """;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", WebApp::handleCalc);
        server.createContext("/record", WebApp::handleRecord);
        server.createContext("/summary", WebApp::handleSummary);
        server.start();
        System.out.println("サーバーを起動しました: http://localhost:8080");
    }

    // 「/」：フォームを表示し、送信されていたら計算結果も表示する
    static void handleCalc(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String resultHtml = "";
        if (params.containsKey("wage")) {
            try {
                resultHtml = resultHtml(params, calc(params));
            } catch (RuntimeException e) {
                resultHtml = "<p class=\"error\">入力内容を確認してください。</p>";
            }
        }
        send(exchange, String.format(FORM, LocalDate.now(), resultHtml));
    }

    // 「/record」：計算して、ファイルに記録する（データを書き換えるので POST で受け取る）
    static void handleRecord(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, "<p class=\"error\">記録はフォームの「記録する」ボタンから行ってください。</p>");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = parseQuery(body);

        String resultHtml;
        try {
            LocalDate date = LocalDate.parse(params.get("date"));
            Salary.PayResult r = calc(params);
            Salary.saveRecord(date, Integer.parseInt(params.get("wage")), params.get("start"), params.get("end"),
                    breakMinutes(params), breakStart(params), r.total());
            resultHtml = "<p class=\"result\">" + date + " の勤務を記録しました！</p>" + resultHtml(params, r);
        } catch (RuntimeException e) {
            resultHtml = "<p class=\"error\">入力内容を確認してください（記録するときは日付も必要です）。</p>";
        }
        send(exchange, String.format(FORM, LocalDate.now(), resultHtml));
    }

    // 「/summary」：指定した年の月ごとの合計と、扶養ラインまでの残りを表示する
    static void handleSummary(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        int year = LocalDate.now().getYear();
        try {
            year = Integer.parseInt(params.getOrDefault("year", String.valueOf(year)));
        } catch (NumberFormatException e) {
            // 年がおかしいときは今年を表示する
        }
        Salary.Summary s = Salary.summarize(year);

        StringBuilder html = new StringBuilder();
        html.append("<h1>").append(year).append("年の集計</h1>");
        html.append("<form action=\"/summary\" method=\"get\" class=\"buttons\">")
            .append("<input type=\"number\" name=\"year\" value=\"").append(year).append("\">")
            .append("<button type=\"submit\">表示</button></form>");

        html.append("<table><tr><th>月</th><th class=\"num\">勤務日数</th><th class=\"num\">合計</th></tr>");
        for (int m = 1; m <= 12; m++) {
            if (s.monthDays()[m] > 0) {
                html.append("<tr><td>").append(m).append("月</td><td class=\"num\">")
                    .append(s.monthDays()[m]).append("日</td><td class=\"num\">")
                    .append(String.format("%,d", s.monthTotal()[m])).append("円</td></tr>");
            }
        }
        html.append("</table>");

        html.append("<div class=\"result\">年間合計 <strong>")
            .append(String.format("%,d", s.yearTotal())).append("円</strong></div>");
        html.append("<p>扶養ライン: ").append(String.format("%,d", Salary.FUYOU_LIMIT)).append("円</p>");
        if (s.remaining() < 0) {
            html.append("<p class=\"warn\">⚠ 扶養ラインを ")
                .append(String.format("%,d", -s.remaining())).append("円 超えています！</p>");
        } else {
            html.append("<p>あと <strong>").append(String.format("%,d", s.remaining()))
                .append("円</strong> まで働けます</p>");
            if (s.forecast() >= 0) {
                html.append("<p>今のペースだと年収の見込みは ")
                    .append(String.format("%,d", s.forecast())).append("円 です</p>");
                if (s.forecast() > Salary.FUYOU_LIMIT) {
                    html.append("<p class=\"warn\">⚠ このペースだと扶養ラインを超えそうです。シフトを調整しましょう。</p>");
                }
            }
        }
        send(exchange, html.toString());
    }

    // フォームの値から給料を計算する
    static Salary.PayResult calc(Map<String, String> params) {
        int wage = Integer.parseInt(params.get("wage"));
        return Salary.calculateDetail(wage, params.get("start"), params.get("end"),
                breakMinutes(params), breakStart(params));
    }

    static int breakMinutes(Map<String, String> params) {
        String value = params.getOrDefault("break", "");
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    static String breakStart(Map<String, String> params) {
        String value = params.getOrDefault("breakStart", "");
        return value.isEmpty() ? "0:00" : value;
    }

    // 計算結果（合計と内訳の表）のHTMLを作る
    static String resultHtml(Map<String, String> params, Salary.PayResult r) {
        return "<div class=\"result\">" + escape(params.get("start")) + "〜" + escape(params.get("end"))
             + " の給料は <strong>" + String.format("%,d", r.total()) + "円</strong> です</div>"
             + "<table>"
             + "<tr><th>区分</th><th>時間</th><th class=\"num\">金額</th></tr>"
             + row("通常", r.normalMinutes(), r.normalPay())
             + row("深夜（×1.25）", r.nightMinutes(), r.nightPay())
             + row("残業（×1.25）", r.overtimeMinutes(), r.overtimePay())
             + row("深夜残業（×1.50）", r.overtimeNightMinutes(), r.overtimeNightPay())
             + "</table>";
    }

    // 内訳の表の1行分のHTMLを作る
    static String row(String label, int minutes, long pay) {
        return "<tr><td>" + label + "</td><td>" + Salary.format(minutes)
             + "</td><td class=\"num\">" + String.format("%,d", pay) + "円</td></tr>";
    }

    // ページの中身を共通の枠に入れて、ブラウザに送る
    static void send(HttpExchange exchange, String content) throws IOException {
        byte[] body = String.format(LAYOUT, content).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // 「wage=1200&start=18%3A00」のような文字列を、名前と値の組（Map）に分ける
    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    // HTMLで特別な意味を持つ文字を無害な形に変える（XSS対策）
    static String escape(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
