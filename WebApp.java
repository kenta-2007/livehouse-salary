import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebApp {

    // ログイン中の人の「合言葉（セッションID）」と、その有効期限
    static final Map<String, Instant> SESSIONS = new ConcurrentHashMap<>();
    static final long SESSION_SECONDS = 7 * 24 * 60 * 60;  // 7日間
    static final SecureRandom RANDOM = new SecureRandom();

    // すべてのページで共通の枠（%s のところに各ページの中身が入る）
    static final String LAYOUT = """
        <!DOCTYPE html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>給料計算</title>
          <style>
            html, body { overflow-x: hidden; }
            body { font-family: sans-serif; max-width: 460px; margin: 24px auto; padding: 0 16px; }
            nav { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; margin-bottom: 8px; }
            nav a, nav button { white-space: nowrap; }
            label { display: block; margin-top: 12px; }
            input, select { width: 100%%; max-width: 100%%; min-width: 0; padding: 8px; font-size: 16px; box-sizing: border-box; }
            /* iPhoneでは日付・時刻の入力欄が画面からはみ出すことがあるので、幅を固定する */
            input[type="date"], input[type="time"] { -webkit-appearance: none; appearance: none; display: block; min-height: 2.6em; text-align: left; background: #fff; border: 1px solid #ccd; border-radius: 4px; color: inherit; }
            .buttons select { flex: 2; }
            .table-wrap { overflow-x: auto; }
            .buttons { display: flex; gap: 8px; margin-top: 16px; }
            button { flex: 1; padding: 12px; font-size: 16px; }
            .result { margin-top: 20px; padding: 16px; background: #eef6f4; border-radius: 8px; font-size: 20px; }
            .error { margin-top: 20px; color: #b4471f; }
            .warn { margin-top: 12px; padding: 12px; background: #fbe7dd; color: #8a2e10; border-radius: 8px; }
            table { width: 100%%; margin-top: 12px; border-collapse: collapse; }
            th, td { padding: 8px 6px; border-bottom: 1px solid #ccd; text-align: left; }
            td.num, th.num { text-align: right; white-space: nowrap; }
            .nw { white-space: nowrap; }
            td.ops { white-space: nowrap; text-align: right; }
            td.ops a { display: block; padding: 4px 0; }
            .danger { background: #b4471f; color: #fff; border: none; border-radius: 4px; }
            .status { margin-top: 12px; padding: 16px; border: 2px solid #8aa; border-radius: 8px; font-size: 18px; }
            .punch button { padding: 20px; font-size: 18px; }
            button.link { padding: 0; font-size: 14px; background: none; border: none; color: #06c; text-decoration: underline; cursor: pointer; }
          </style>
        </head>
        <body>
          <nav><a href="/punch">タイムカード</a><a href="/">計算・記録</a><a href="/summary">集計</a><a href="/settings">設定</a>
            <form action="/logout" method="post" style="margin-left:auto"><button class="link">ログアウト</button></form></nav>
          %s
        </body>
        </html>
        """;

    // 入力フォーム（%s のところに入力欄の初期値と、計算結果が入る）
    static final String FORM = """
        <h1>給料計算</h1>
        <form action="/" method="get">
          <label>日付（記録するとき）<input type="date" name="date" value="%s"></label>
          <label>時給（円）<input type="number" name="wage" value="%s" required></label>
          <label>開始時刻<input type="time" name="start" value="%s" required></label>
          <label>終了時刻<input type="time" name="end" value="%s" required></label>
          <label>休憩（分）<input type="number" name="break" value="%s" min="0"></label>
          <label>休憩の開始時刻（休憩がある場合）<input type="time" name="breakStart" value="%s"></label>
          <div class="buttons">
            <button type="submit">計算する</button>
            <button type="submit" formaction="/record" formmethod="post">記録する</button>
          </div>
        </form>
        %s
        """;

    // フォームに、前回入力した値（なければ初期値）を入れてHTMLを作る
    static String form(Map<String, String> params, String resultHtml) {
        String wage = params.getOrDefault("wage", String.valueOf(Settings.load().wage()));
        return String.format(FORM,
                escape(params.getOrDefault("date", LocalDate.now().toString())),
                escape(wage),
                escape(params.getOrDefault("start", "")),
                escape(params.getOrDefault("end", "")),
                escape(params.getOrDefault("break", "0")),
                escape(params.getOrDefault("breakStart", "")),
                resultHtml);
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        // ログインとログアウトは、ログインしていなくても開ける
        server.createContext("/login", WebApp::handleLogin);
        server.createContext("/logout", WebApp::handleLogout);
        // それ以外のページは、ログインしている人だけが開ける
        server.createContext("/", loginRequired(WebApp::handleCalc));
        server.createContext("/record", loginRequired(WebApp::handleRecord));
        server.createContext("/summary", loginRequired(WebApp::handleSummary));
        server.createContext("/punch", loginRequired(WebApp::handlePunch));
        server.createContext("/records", loginRequired(WebApp::handleRecords));
        server.createContext("/edit", loginRequired(WebApp::handleEdit));
        server.createContext("/delete", loginRequired(WebApp::handleDelete));
        server.createContext("/settings", loginRequired(WebApp::handleSettings));
        server.start();
        System.out.println("サーバーを起動しました: http://localhost:8080");
    }

    // ---- ログイン関係 ----

    // ログインしているか確認してから、本来の処理を行う「門番」を作る
    static HttpHandler loginRequired(HttpHandler handler) {
        return exchange -> {
            if (isLoggedIn(exchange)) {
                handler.handle(exchange);
            } else {
                redirect(exchange, "/login");
            }
        };
    }

    // ブラウザから送られてきたクッキーの中に、有効なセッションIDがあるか
    static boolean isLoggedIn(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) {
            return false;
        }
        Instant expires = SESSIONS.get(token);
        if (expires == null || expires.isBefore(Instant.now())) {
            SESSIONS.remove(token);  // 期限切れなら消す
            return false;
        }
        return true;
    }

    // 「Cookie: a=1; session=xxxx」から session の値を取り出す
    static String sessionToken(HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies.split(";")) {
            String[] kv = cookie.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("session")) {
                return kv[1];
            }
        }
        return null;
    }

    // ログインページのHTML（%s のところにメッセージが入る）
    static final String LOGIN_PAGE = """
        <h1>ログイン</h1>
        %s
        <form action="/login" method="post">
          <label>パスワード<input type="password" name="password" required autofocus></label>
          <div class="buttons"><button type="submit">ログイン</button></div>
        </form>
        """;

    // 「/login」：GETならログイン画面を表示、POSTならパスワードを確認する
    static void handleLogin(HttpExchange exchange) throws IOException {
        if (!Auth.hasPassword()) {
            sendPage(exchange, "<h1>ログイン</h1><p class=\"error\">パスワードがまだ設定されていません。"
                    + "ターミナルで <code>java -cp out Auth</code> を実行して設定してください。</p>");
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            sendPage(exchange, String.format(LOGIN_PAGE, ""));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String password = parseQuery(body).getOrDefault("password", "");
        if (Auth.checkPassword(password)) {
            // 推測できないランダムなセッションIDを作って、ブラウザに渡す
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            SESSIONS.put(token, Instant.now().plusSeconds(SESSION_SECONDS));
            exchange.getResponseHeaders().add("Set-Cookie", "session=" + token
                    + "; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=" + SESSION_SECONDS);
            redirect(exchange, "/punch");
        } else {
            // 間違えたら1秒待たせる（パスワードを大量に試す攻撃を遅くする）
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sendPage(exchange, String.format(LOGIN_PAGE, "<p class=\"error\">パスワードが違います。</p>"));
        }
    }

    // 「/logout」：セッションIDを消して、ログイン画面に戻す
    static void handleLogout(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("POST")) {
            String token = sessionToken(exchange);
            if (token != null) {
                SESSIONS.remove(token);
            }
            exchange.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0");
        }
        redirect(exchange, "/login");
    }

    // 別のページへ移動させる
    static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    // ---- 各ページ ----

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
        send(exchange, form(params, resultHtml));
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
            int wage = Integer.parseInt(params.get("wage"));
            Salary.saveRecord(date, wage, params.get("start"), params.get("end"),
                    breakMinutes(params), breakStart(params), r.total());
            resultHtml = "<p class=\"result\">" + date + " の勤務を記録しました！</p>" + resultHtml(params, r);
        } catch (RuntimeException e) {
            resultHtml = "<p class=\"error\">入力内容を確認してください（記録するときは日付も必要です）。</p>";
        }
        send(exchange, form(params, resultHtml));
    }

    // 「/summary」：指定した年に支給される給料を、給料日ごとに集計して、扶養ラインまでの残りを表示する
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
        html.append("<h1>集計</h1>");
        html.append(paydayInfoHtml());

        // 年は、記録がある年から選ぶ
        html.append("<h2>").append(year).append("年に支給される給料</h2>");
        html.append("<form action=\"/summary\" method=\"get\" class=\"buttons\">")
            .append("<select name=\"year\" onchange=\"this.form.submit()\">");
        for (int y : Salary.recordYears().descendingSet()) {
            html.append("<option value=\"").append(y).append("\"")
                .append(y == year ? " selected" : "")
                .append(">").append(y).append("年</option>");
        }
        html.append("</select><button type=\"submit\">表示</button></form>");

        html.append("<div class=\"table-wrap\"><table><tr><th>給料日（働いた期間）</th>")
            .append("<th class=\"num\">日数</th><th class=\"num\">合計</th></tr>");
        for (int m = 1; m <= 12; m++) {
            if (s.monthDays()[m] > 0) {
                PayPeriod p = s.periods()[m];
                html.append("<tr><td><a class=\"nw\" href=\"/records?year=").append(year).append("&month=").append(m)
                    .append("\">").append(p.paydayText()).append("</a><br><small class=\"nw\">")
                    .append(p.rangeText()).append("</small></td><td class=\"num\">")
                    .append(s.monthDays()[m]).append("日</td><td class=\"num\">")
                    .append(String.format("%,d", s.monthTotal()[m])).append("円</td></tr>");
            }
        }
        html.append("</table></div>");
        html.append("<p>給料日をタップすると、その期間の記録の一覧が見られます。</p>");

        html.append("<div class=\"result\">").append(year).append("年の支給額の合計 <strong>")
            .append(String.format("%,d", s.yearTotal())).append("円</strong></div>");
        html.append("<p>扶養ライン: ").append(String.format("%,d", s.fuyouLimit())).append("円")
            .append("<br><small>扶養は、働いた日ではなく<strong>給料をもらった日</strong>で年を数えます。</small></p>");
        if (s.remaining() < 0) {
            html.append("<p class=\"warn\">⚠ 扶養ラインを ")
                .append(String.format("%,d", -s.remaining())).append("円 超えています！</p>");
        } else {
            html.append("<p>あと <strong>").append(String.format("%,d", s.remaining()))
                .append("円</strong> まで働けます</p>");
            if (s.forecast() >= 0) {
                html.append("<p>今のペースだと、").append(year).append("年の支給額の見込みは ")
                    .append(String.format("%,d", s.forecast())).append("円 です</p>");
                if (s.forecast() > s.fuyouLimit()) {
                    html.append("<p class=\"warn\">⚠ このペースだと扶養ラインを超えそうです。シフトを調整しましょう。</p>");
                }
            }
        }
        send(exchange, html.toString());
    }

    // 「次の給料日」と「今働いている期間」の金額を表示するHTML
    static String paydayInfoHtml() throws IOException {
        int closingDay = Settings.load().closingDay();
        LocalDate today = LocalDate.now();
        PayPeriod current = PayPeriod.of(today, closingDay);
        PayPeriod previous = PayPeriod.ofClosingMonth(YearMonth.from(current.end()).minusMonths(1), closingDay);

        long previousTotal = 0;
        long currentTotal = 0;
        for (Salary.Entry e : Salary.loadEntries()) {
            if (!e.date().isBefore(previous.start()) && !e.date().isAfter(previous.end())) {
                previousTotal += e.total();
            } else if (!e.date().isBefore(current.start()) && !e.date().isAfter(current.end())) {
                currentTotal += e.total();
            }
        }

        StringBuilder html = new StringBuilder("<div class=\"status\">");
        // 締めが終わったけど、まだ給料日が来ていない分があれば、それが「次の給料日」
        if (!previous.payday().isBefore(today)) {
            html.append("次の給料日 <strong>").append(previous.paydayText()).append("</strong><br>")
                .append("<strong>").append(String.format("%,d", previousTotal)).append("円</strong>")
                .append("<br><small>").append(previous.rangeText()).append("の勤務分</small><hr>");
        }
        html.append("今の期間（").append(current.rangeText()).append("）<br>ここまで <strong>")
            .append(String.format("%,d", currentTotal)).append("円</strong>")
            .append("<br><small>").append(current.paydayText()).append("に支給予定</small>")
            .append("<br><small>※給料日が土日・祝日の場合は前の平日になります</small></div>");
        return html.toString();
    }

    // 「/punch」：タイムカード。ボタンが押されたら（POST）打刻して、今の状態を表示する
    static void handlePunch(HttpExchange exchange) throws IOException {
        String message = "";
        if (exchange.getRequestMethod().equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQuery(body);
            try {
                message = switch (params.getOrDefault("action", "")) {
                    case "in" -> TimeCard.clockIn(Settings.load().wage());
                    case "breakStart" -> TimeCard.startBreak();
                    case "breakEnd" -> TimeCard.endBreak();
                    case "out" -> TimeCard.clockOut();
                    default -> "不明な操作です。";
                };
            } catch (RuntimeException e) {
                message = "入力内容を確認してください。";
            }
        }

        TimeCard.Punch p = TimeCard.load();
        StringBuilder html = new StringBuilder("<h1>タイムカード</h1>");
        if (!message.isEmpty()) {
            html.append("<p class=\"result\">").append(message).append("</p>");
        }
        html.append("<form action=\"/punch\" method=\"post\" class=\"punch\">");
        if (p == null) {
            html.append("<div class=\"status\">今は勤務していません</div>")
                .append("<p>時給 ").append(String.format("%,d", Settings.load().wage()))
                .append("円（<a href=\"/settings\">設定</a>で変更できます）</p>")
                .append("<div class=\"buttons\"><button name=\"action\" value=\"in\">出勤</button></div>");
        } else if (p.onBreak()) {
            html.append("<div class=\"status\">休憩中（").append(p.breakStart()).append("〜）</div>")
                .append("<div class=\"buttons\"><button name=\"action\" value=\"breakEnd\">休憩終了</button>")
                .append("<button name=\"action\" value=\"out\">退勤</button></div>");
        } else {
            html.append("<div class=\"status\">勤務中（").append(p.start()).append("〜、時給")
                .append(p.wage()).append("円）");
            if (p.breakTaken()) {
                html.append("<br>休憩: ").append(p.breakStart()).append("〜").append(p.breakEnd());
            }
            html.append("</div><div class=\"buttons\">");
            if (!p.breakTaken()) {
                html.append("<button name=\"action\" value=\"breakStart\">休憩開始</button>");
            }
            html.append("<button name=\"action\" value=\"out\">退勤</button></div>");
        }
        html.append("</form>");
        html.append(paydayInfoHtml());
        send(exchange, html.toString());
    }

    // ---- 記録の一覧・修正・削除 ----

    // 「/records?year=2026&month=10」：その月の記録を一覧で表示する
    static void handleRecords(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        int year;
        int month;
        try {
            year = Integer.parseInt(params.get("year"));
            month = Integer.parseInt(params.get("month"));
        } catch (RuntimeException e) {
            redirect(exchange, "/summary");
            return;
        }
        send(exchange, recordsHtml(year, month, ""));
    }

    // 「year年month月の給料日に支給される分」の記録一覧のHTMLを作る（message は上に出すお知らせ）
    static String recordsHtml(int year, int month, String message) throws IOException {
        PayPeriod period = PayPeriod.ofClosingMonth(YearMonth.of(year, month), Settings.load().closingDay());
        StringBuilder html = new StringBuilder();
        html.append("<h1>").append(period.paydayText()).append("支給分の記録</h1>");
        html.append("<p>働いた期間：").append(period.start()).append("〜").append(period.end()).append("</p>");
        html.append(message);
        html.append("<div class=\"table-wrap\"><table><tr><th>日付</th><th>勤務</th><th class=\"num\">給料</th><th></th></tr>");
        int count = 0;
        long sum = 0;
        // 日付の順に並べて表示する（行番号はそのまま持っておく）
        List<Salary.Entry> entries = Salary.loadEntries();
        entries.sort(Comparator.comparing(Salary.Entry::date).thenComparing(Salary.Entry::start));
        for (Salary.Entry e : entries) {
            if (e.date().isBefore(period.start()) || e.date().isAfter(period.end())) {
                continue;  // この期間に働いた分だけを表示する
            }
            count++;
            sum += e.total();
            String ids = "line=" + e.index() + "&check=" + e.check();
            html.append("<tr><td>").append(e.date().getMonthValue()).append("/").append(e.date().getDayOfMonth())
                .append("</td><td><span class=\"nw\">").append(escape(e.start())).append("〜")
                .append(escape(e.end())).append("</span>");
            if (e.breakMinutes() > 0) {
                html.append("<br><small><span class=\"nw\">休憩").append(e.breakMinutes()).append("分</span>・")
                    .append("<span class=\"nw\">時給").append(e.wage()).append("円</span></small>");
            } else {
                html.append("<br><small><span class=\"nw\">時給").append(e.wage()).append("円</span></small>");
            }
            html.append("</td><td class=\"num\">").append(String.format("%,d", e.total())).append("円</td>")
                .append("<td class=\"ops\"><a href=\"/edit?").append(ids).append("\">修正</a>")
                .append("<a href=\"/delete?").append(ids).append("\">削除</a></td></tr>");
        }
        html.append("</table></div>");
        if (count == 0) {
            html.append("<p>この月の記録はありません。</p>");
        } else {
            html.append("<div class=\"result\">").append(count).append("日勤務・合計 <strong>")
                .append(String.format("%,d", sum)).append("円</strong></div>");
        }
        html.append("<p><a href=\"/summary?year=").append(year).append("\">← 集計に戻る</a></p>");
        return html.toString();
    }

    // 「/delete」：GETなら確認画面、POSTなら本当に削除する
    static void handleDelete(HttpExchange exchange) throws IOException {
        boolean isPost = exchange.getRequestMethod().equals("POST");
        Map<String, String> params = isPost
                ? parseQuery(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                : parseQuery(exchange.getRequestURI().getRawQuery());

        Salary.Entry e = findEntry(params);
        if (e == null) {
            send(exchange, notFoundHtml());
            return;
        }
        YearMonth pay = payMonth(e.date());
        int year = pay.getYear();
        int month = pay.getMonthValue();

        if (!isPost) {
            // 確認画面（ここではまだ削除しない）
            send(exchange, "<h1>記録の削除</h1>"
                    + "<p>次の記録を削除します。よろしいですか？</p>"
                    + "<div class=\"status\">" + e.date() + "　" + escape(e.start()) + "〜" + escape(e.end())
                    + "<br>" + String.format("%,d", e.total()) + "円</div>"
                    + "<form action=\"/delete\" method=\"post\" class=\"buttons\">"
                    + hidden(e)
                    + "<button type=\"submit\" class=\"danger\">削除する</button></form>"
                    + "<p><a href=\"/records?year=" + year + "&month=" + month + "\">やめる</a></p>");
            return;
        }

        if (Salary.replaceLine(e.index(), e.check(), null)) {
            send(exchange, recordsHtml(year, month, "<p class=\"result\">記録を削除しました。</p>"));
        } else {
            send(exchange, notFoundHtml());
        }
    }

    // 修正フォーム（%s のところに入力欄の値などが入る）
    static final String EDIT_FORM = """
        <h1>記録の修正</h1>
        %s
        <form action="/edit" method="post">
          %s
          <label>日付<input type="date" name="date" value="%s" required></label>
          <label>時給（円）<input type="number" name="wage" value="%s" required></label>
          <label>開始時刻<input type="time" name="start" value="%s" required></label>
          <label>終了時刻<input type="time" name="end" value="%s" required></label>
          <label>休憩（分）<input type="number" name="break" value="%s" min="0"></label>
          <label>休憩の開始時刻（休憩がある場合）<input type="time" name="breakStart" value="%s"></label>
          <div class="buttons"><button type="submit">保存する</button></div>
        </form>
        <p><a href="/records?year=%s&month=%s">やめる</a></p>
        """;

    // 「/edit」：GETなら今の内容が入った修正フォーム、POSTなら計算し直して保存する
    static void handleEdit(HttpExchange exchange) throws IOException {
        boolean isPost = exchange.getRequestMethod().equals("POST");
        Map<String, String> params = isPost
                ? parseQuery(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                : parseQuery(exchange.getRequestURI().getRawQuery());

        Salary.Entry e = findEntry(params);
        if (e == null) {
            send(exchange, notFoundHtml());
            return;
        }

        if (!isPost) {
            // 休憩がない記録は、休憩の開始時刻の欄を空にしておく
            String breakStart = e.breakMinutes() > 0 ? e.breakStart() : "";
            send(exchange, String.format(EDIT_FORM, "", hidden(e),
                    e.date(), e.wage(), escape(e.start()), escape(e.end()), e.breakMinutes(), escape(breakStart),
                    payMonth(e.date()).getYear(), payMonth(e.date()).getMonthValue()));
            return;
        }

        try {
            LocalDate date = LocalDate.parse(params.get("date"));
            int wage = Integer.parseInt(params.get("wage"));
            Salary.PayResult r = calc(params);
            String newLine = Salary.toLine(date, wage, params.get("start"), params.get("end"),
                    breakMinutes(params), breakStart(params), r.total());
            if (Salary.replaceLine(e.index(), e.check(), newLine)) {
                send(exchange, recordsHtml(payMonth(date).getYear(), payMonth(date).getMonthValue(),
                        "<p class=\"result\">記録を修正しました（" + String.format("%,d", r.total()) + "円）。</p>"));
            } else {
                send(exchange, notFoundHtml());
            }
        } catch (RuntimeException ex) {
            // 入力がおかしいときは、入力した内容のままフォームを出し直す
            send(exchange, String.format(EDIT_FORM, "<p class=\"error\">入力内容を確認してください。</p>", hidden(e),
                    escape(params.getOrDefault("date", "")), escape(params.getOrDefault("wage", "")),
                    escape(params.getOrDefault("start", "")), escape(params.getOrDefault("end", "")),
                    escape(params.getOrDefault("break", "0")), escape(params.getOrDefault("breakStart", "")),
                    payMonth(e.date()).getYear(), payMonth(e.date()).getMonthValue()));
        }
    }

    // その日に働いた分が、何年何月の給料日に支給されるか
    static YearMonth payMonth(LocalDate workDate) {
        return YearMonth.from(PayPeriod.of(workDate, Settings.load().closingDay()).payday());
    }

    // フォームの line と check から、記録を探す（見つからなければ null）
    static Salary.Entry findEntry(Map<String, String> params) throws IOException {
        try {
            return Salary.findEntry(Integer.parseInt(params.get("line")), params.getOrDefault("check", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // どの記録かを送るための、見えない入力欄
    static String hidden(Salary.Entry e) {
        return "<input type=\"hidden\" name=\"line\" value=\"" + e.index() + "\">"
             + "<input type=\"hidden\" name=\"check\" value=\"" + e.check() + "\">";
    }

    static String notFoundHtml() {
        return "<h1>記録が見つかりません</h1>"
             + "<p class=\"error\">記録が見つからないか、ほかの画面で変更されています。"
             + "もう一度、一覧から選び直してください。</p>"
             + "<p><a href=\"/summary\">集計に戻る</a></p>";
    }

    // ---- 設定 ----

    // 「/settings」：GETなら設定画面、POSTなら設定を保存する
    static void handleSettings(HttpExchange exchange) throws IOException {
        String message = "";
        Settings s = Settings.load();
        if (exchange.getRequestMethod().equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQuery(body);
            try {
                Settings input = new Settings(Integer.parseInt(params.get("wage")),
                        Integer.parseInt(params.get("closingDay")), Long.parseLong(params.get("fuyouLimit")));
                String error = input.validate();
                if (error == null) {
                    input.save();
                    s = input;
                    message = "<p class=\"result\">設定を保存しました。</p>";
                } else {
                    message = "<p class=\"error\">" + error + "</p>";
                }
            } catch (RuntimeException e) {
                message = "<p class=\"error\">入力内容を確認してください。</p>";
            }
        }

        StringBuilder days = new StringBuilder();
        for (int d = 1; d <= 28; d++) {
            days.append("<option value=\"").append(d).append("\"").append(d == s.closingDay() ? " selected" : "")
                .append(">").append(d).append("日</option>");
        }
        days.append("<option value=\"").append(Settings.END_OF_MONTH).append("\"")
            .append(s.closingDay() == Settings.END_OF_MONTH ? " selected" : "").append(">末日</option>");

        send(exchange, "<h1>設定</h1>" + message
                + "<form action=\"/settings\" method=\"post\">"
                + "<label>時給（円）<input type=\"number\" name=\"wage\" value=\"" + s.wage() + "\" min=\"1\" required></label>"
                + "<p><small>時給を変えても、これまでの記録の給料は変わりません。</small></p>"
                + "<label>締め日<select name=\"closingDay\">" + days + "</select></label>"
                + "<p><small>給料日は、締めた月の月末です（土日・祝日の場合は前の平日）。</small></p>"
                + "<label>扶養ライン（円）<input type=\"number\" name=\"fuyouLimit\" value=\"" + s.fuyouLimit()
                + "\" min=\"0\" step=\"10000\" required></label>"
                + "<p><small>金額は家庭の条件によって違うので、家族に確認してください。</small></p>"
                + "<div class=\"buttons\"><button type=\"submit\">保存する</button></div>"
                + "</form>");
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
             + "<div class=\"table-wrap\"><table>"
             + "<tr><th>区分</th><th>時間</th><th class=\"num\">金額</th></tr>"
             + row("通常", r.normalMinutes(), r.normalPay())
             + row("深夜（×1.25）", r.nightMinutes(), r.nightPay())
             + row("残業（×1.25）", r.overtimeMinutes(), r.overtimePay())
             + row("深夜残業（×1.50）", r.overtimeNightMinutes(), r.overtimeNightPay())
             + "</table></div>";
    }

    // 内訳の表の1行分のHTMLを作る
    static String row(String label, int minutes, long pay) {
        return "<tr><td>" + label + "</td><td>" + Salary.format(minutes)
             + "</td><td class=\"num\">" + String.format("%,d", pay) + "円</td></tr>";
    }

    // ページの中身を共通の枠（メニュー付き）に入れて、ブラウザに送る
    static void send(HttpExchange exchange, String content) throws IOException {
        sendHtml(exchange, String.format(LAYOUT, content));
    }

    // ログイン画面用（メニューなし）
    static void sendPage(HttpExchange exchange, String content) throws IOException {
        sendHtml(exchange, String.format(LAYOUT, content).replaceAll("(?s)<nav>.*?</nav>", ""));
    }

    static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
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
