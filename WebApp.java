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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebApp {

    // ログイン中の人の「合言葉（セッションID）」と、その有効期限
    static final Map<String, Instant> SESSIONS = new ConcurrentHashMap<>();
    static final long SESSION_SECONDS = 7 * 24 * 60 * 60;  // 7日間
    static final SecureRandom RANDOM = new SecureRandom();

    // すべてのページで共通の枠（%s のところに各ページの中身と、下のメニューが入る）
    // デザインは iPhone の「設定」アプリ風：灰色の背景に、白いグループ（行の集まり）を並べる
    static final String LAYOUT = """
        <!DOCTYPE html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
          <title>給料計算</title>
          <style>
            /* ---- アプリ全体で使う色 ---- */
            :root { --tint: #1a7f6b; --bg: #f2f2f7; --text: #000; --sub: #6c6c70; --sep: #c6c6c8;
                    --red: #c4372b; --amber: #f2a93b; }

            /* ---- ページ全体 ---- */
            html, body { overflow-x: hidden; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Hiragino Kaku Gothic ProN",
                                "Noto Sans JP", sans-serif;
                   color: var(--text); background: var(--bg); -webkit-text-size-adjust: 100%%;
                   max-width: 520px; margin: 0 auto; padding: 8px 16px 0;
                   /* 下のメニューに中身が隠れないように、下に余白をとる */
                   padding-bottom: calc(96px + env(safe-area-inset-bottom)); }
            h1 { font-size: min(32px, 8vw); font-weight: 700; line-height: 1.2; margin: 14px 4px 20px; }
            a, a:visited { color: var(--tint); text-decoration: none; }
            small, .muted { color: var(--sub); }
            .back { display: inline-block; margin-top: 4px; padding: 8px 4px 0; font-size: 17px; }

            /* ---- グループ（白い箱）と、その中の行 ---- */
            .sec { display: flex; justify-content: space-between; align-items: center; min-height: 22px;
                   margin: 0 4px 6px 16px; font-size: 13px; color: var(--sub); }
            .group { background: #fff; border-radius: 10px; overflow: hidden; margin: 0 0 26px; }
            .foot { margin: -18px 16px 26px; font-size: 13px; color: var(--sub); }
            .row { display: flex; align-items: center; justify-content: space-between; gap: 12px;
                   min-height: 46px; margin: 0 0 0 16px; padding: 6px 16px 6px 0; box-sizing: border-box;
                   font-size: 17px; color: var(--text); }
            /* 行と行の間の線（左を少しあける） */
            .row + .row { border-top: .5px solid var(--sep); }
            a.row, a.row:visited { color: var(--text); }
            .row > span:first-child { flex: none; }
            .row .l { display: flex; flex-direction: column; min-width: 0; flex: 1; }
            .row .l small { font-size: 13px; margin-top: 2px; }
            .row .v { color: var(--sub); text-align: right; white-space: nowrap; }
            .row .r { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
            .row.total { font-weight: 600; }
            .row.warn { color: var(--red); }
            .amount { font-size: 26px; font-weight: 600; color: var(--tint); white-space: nowrap; }
            /* 右向きの矢印（タップできる行の目印） */
            .chev { flex: none; width: 8px; height: 8px; margin-right: 2px;
                    border-top: 2px solid #c4c4c7; border-right: 2px solid #c4c4c7; transform: rotate(45deg); }

            /* ---- 行の中の入力欄（右寄せ・枠なし） ---- */
            .row input, .row select { flex: 1; width: 100%%; min-width: 0; min-height: 34px; margin: 0; padding: 0;
                    font: inherit; font-size: 17px; color: var(--sub); text-align: right; text-align-last: right;
                    background: transparent; border: 0; -webkit-appearance: none; appearance: none; }
            .row input:focus, .row select:focus { color: var(--text); }
            .row input::-webkit-date-and-time-value { text-align: right; }
            .row select { padding-right: 18px; background-repeat: no-repeat; background-position: right center;
                    background-image: url("data:image/svg+xml,%%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='14'%%3E%%3Cpath d='M2 5l3-3 3 3M2 9l3 3 3-3' stroke='%%238e8e93' stroke-width='1.6' fill='none'/%%3E%%3C/svg%%3E"); }
            .row .two { display: flex; justify-content: flex-end; gap: 16px; flex: 1; }
            .row .two select { flex: none; width: auto; }
            .sec select { min-height: 32px; padding: 0 2px; font: inherit; font-size: 15px; color: var(--tint);
                    background: transparent; border: 0; }

            /* ---- ボタン ---- */
            .buttons { display: flex; gap: 10px; margin: 0 0 26px; }
            button { flex: 1; min-height: 50px; padding: 12px; font: inherit; font-size: 17px; font-weight: 600;
                     color: #fff; background: var(--tint); border: 0; border-radius: 12px; cursor: pointer;
                     -webkit-appearance: none; appearance: none; }
            button.secondary { color: var(--tint); background: #fff; }
            button.danger, button.out { background: var(--red); }
            button.break { background: var(--amber); color: #3b2a07; }
            .punch button { min-height: 64px; font-size: 19px; }
            /* グループの中に置く、赤い文字だけのボタン（ログアウト・削除） */
            .rowbtn, a.rowbtn:visited { display: block; width: 100%%; min-height: 46px; padding: 12px; box-sizing: border-box;
                      font-size: 17px; font-weight: 400; text-align: center; color: var(--red);
                      background: #fff; border-radius: 0; }
            .sub { text-align: center; font-size: 15px; margin: 4px 0 26px; }
            .sub a, .sub a:visited { color: var(--sub); }

            /* ---- お知らせ ---- */
            .result { margin: 0 0 20px; padding: 12px 16px; border-radius: 10px; background: #e2f1ec; color: #0f4d40; }
            .error { margin: 0 0 20px; padding: 12px 16px; border-radius: 10px; background: #fdecea; color: #a1281c; }

            /* ---- 集計：次の給料日（大きく表示） ---- */
            .hero { display: flex; flex-direction: column; align-items: center; text-align: center;
                    margin: 0 0 26px; padding: 16px 16px 14px; background: #fff; border-radius: 10px; }
            .hero .hl { display: flex; flex-wrap: wrap; justify-content: center; align-items: baseline; gap: 0 6px; }
            .hero .day { font-size: 28px; font-weight: 800; color: var(--tint); }
            .hero .yen { font-size: 40px; font-weight: 800; color: var(--tint); line-height: 1.15; }
            .hero .suf { font-size: 16px; font-weight: 700; }
            .hero .big { font-size: 30px; font-weight: 800; color: var(--tint); line-height: 1.3; }
            .hero .big.off { color: var(--sub); }
            .hero .big.rest { color: #96600a; }
            .hero .big.alert { color: var(--red); }
            .pill { margin-top: 8px; padding: 4px 12px; font-size: 13px; color: var(--sub);
                    background: var(--bg); border-radius: 999px; }

            /* ---- 集計：扶養のバー ---- */
            .row.stack { display: block; padding-top: 12px; padding-bottom: 14px; }
            .line { display: flex; justify-content: space-between; gap: 12px; }
            .meter { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
            .meter span { font-size: 12px; color: var(--sub); white-space: nowrap; }
            .meter b { font-size: 14px; color: var(--tint); }
            .meter.over b { color: var(--red); }
            .bar { flex: 1; height: 10px; border-radius: 999px; background: #e5e5ea; overflow: hidden; }
            .bar i { display: block; height: 100%%; border-radius: 999px; background: var(--tint); }
            .meter.over .bar i { background: var(--red); }

            /* ---- 集計：給料日ごとのカード ---- */
            .legend { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 0 0 8px 16px; font-size: 12px; color: var(--sub); }
            .legend span { display: flex; align-items: center; gap: 6px; }
            .legend i { width: 5px; height: 14px; border-radius: 2px; background: var(--tint); }
            .legend i.plan { background: #c7c7cc; }
            .cards { display: flex; flex-direction: column; gap: 8px; margin: 0 0 26px; }
            .card { display: flex; align-items: center; gap: 10px; min-height: 54px; padding: 6px 12px;
                    box-sizing: border-box; background: #fff; border-radius: 8px; border-left: 6px solid var(--tint); }
            a.card, a.card:visited { color: var(--text); }
            .card.plan { border-left-color: #c7c7cc; }
            .card .l { flex: 1; min-width: 0; display: flex; flex-direction: column; line-height: 1.3; }
            .card .l b { font-size: 16px; font-weight: 600; }
            .card .l small { font-size: 12px; }
            .days { flex: none; width: 40px; height: 40px; border-radius: 50%%; border: 1.5px solid #e5e5ea;
                    box-sizing: border-box; display: flex; flex-direction: column; align-items: center;
                    justify-content: center; line-height: 1.1; }
            .days small { font-size: 9px; }
            .days b { font-size: 12px; }
            .card .amt { font-size: 16px; font-weight: 600; white-space: nowrap; }
            .card.sum { justify-content: space-between; min-height: 46px; padding: 0 16px; border-left: 0;
                        font-size: 16px; font-weight: 600; }

            /* ---- 下のメニュー ---- */
            nav { position: fixed; left: 0; right: 0; bottom: 0; z-index: 10; display: flex;
                  background: rgba(249, 249, 249, .94); border-top: .5px solid var(--sep);
                  -webkit-backdrop-filter: blur(20px); backdrop-filter: blur(20px);
                  padding: 6px 0 calc(8px + env(safe-area-inset-bottom)); }
            nav a, nav a:visited { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px;
                    font-size: 10px; color: #8e8e93; text-decoration: none; white-space: nowrap; }
            nav svg { width: 24px; height: 24px; stroke: currentColor; fill: none; stroke-width: 1.8;
                      stroke-linecap: round; stroke-linejoin: round; }
            nav a.on { color: var(--tint); }
          </style>
        </head>
        <body>
          %s
          %s
        </body>
        </html>
        """;

    // 計算・記録の入力フォーム（%s のところに入力欄の初期値と、計算結果が入る）
    static final String FORM = """
        <h1>給料計算</h1>
        <form action="/" method="get">
          <div class="group">
            <label class="row"><span>勤務日</span><input type="date" name="date" value="%s"></label>
            <label class="row"><span>開始</span><input type="time" name="start" value="%s" required></label>
            <label class="row"><span>終了</span><input type="time" name="end" value="%s" required></label>
          </div>
          <div class="group">
            <label class="row"><span>休憩（分）</span><input type="number" name="break" value="%s" min="0" inputmode="numeric"></label>
            <label class="row"><span>休憩の開始</span><input type="time" name="breakStart" value="%s"></label>
          </div>
          <p class="foot">休憩の開始は、休憩がある場合だけ入力します。</p>
          <div class="buttons">
            <button type="submit" class="secondary">計算する</button>
            <button type="submit" formaction="/record" formmethod="post">記録する</button>
          </div>
        </form>
        %s
        """;

    // フォームに、前回入力した値（なければ初期値）を入れてHTMLを作る
    static String form(Map<String, String> params, String resultHtml) {
        return String.format(FORM,
                escape(params.getOrDefault("date", LocalDate.now().toString())),
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
          <div class="group">
            <label class="row"><span>パスワード</span><input type="password" name="password" required autofocus></label>
          </div>
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
        if (params.containsKey("start")) {
            try {
                resultHtml = resultHtml(params, calc(params, Settings.load().wage()));
            } catch (RuntimeException e) {
                resultHtml = "<p class=\"error\">" + errorText(e, "入力内容を確認してください。") + "</p>";
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
            int wage = Settings.load().wage();  // 時給は設定の値を使う
            Salary.PayResult r = calc(params, wage);
            Salary.saveRecord(date, wage, params.get("start"), params.get("end"),
                    breakMinutes(params), breakStart(params), r.total());
            resultHtml = "<p class=\"result\">" + date + " の勤務を記録しました。</p>" + resultHtml(params, r);
        } catch (RuntimeException e) {
            resultHtml = "<p class=\"error\">"
                    + errorText(e, "入力内容を確認してください（記録するときは勤務日も必要です）。") + "</p>";
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

        // ---- 扶養（今年の合計と、ラインまでのバー） ----
        long limit = s.fuyouLimit();
        long percent = limit > 0 ? s.yearTotal() * 100 / limit : 100;  // 文字で出す割合（100%を超えることもある）
        double width = limit > 0 ? Math.min(100.0, s.yearTotal() * 100.0 / limit) : 100.0;  // バーの長さ
        boolean over = s.remaining() < 0;
        html.append("<div class=\"sec\">扶養（").append(year).append("年）</div>")
            .append("<div class=\"group\"><div class=\"row stack\"><div class=\"line\"><span>今年の合計</span><span>")
            .append(yen(s.yearTotal())).append("</span></div>")
            .append("<div class=\"meter").append(over ? " over" : "").append("\"><div class=\"bar\"><i style=\"width:")
            .append(String.format(Locale.ROOT, "%.1f", width)).append("%\"></i></div>")
            .append("<span>扶養ラインの <b>").append(percent).append("%</b></span></div></div>");
        if (over) {
            html.append("<div class=\"row warn\"><span>超えた金額</span><span>")
                .append(yen(-s.remaining())).append("</span></div>");
        } else {
            html.append("<div class=\"row\"><span>あと</span><span class=\"v\">")
                .append(yen(s.remaining())).append("</span></div>");
        }
        html.append("</div><p class=\"foot\">上限 ").append(yen(limit)).append("</p>");

        // ---- 給料日ごとの一覧（年は、記録がある年から選ぶ） ----
        html.append("<div class=\"sec\"><span>給料日ごと</span>")
            .append("<form action=\"/summary\" method=\"get\">")
            .append("<select name=\"year\" aria-label=\"年\" onchange=\"this.form.submit()\">");
        for (int y : Salary.recordYears().descendingSet()) {
            html.append("<option value=\"").append(y).append("\"")
                .append(y == year ? " selected" : "")
                .append(">").append(y).append("年</option>");
        }
        html.append("</select></form></div>");

        if (s.rows().isEmpty()) {
            html.append("<div class=\"group\"><div class=\"row\"><span class=\"muted\">この年の記録はありません</span></div></div>");
            send(exchange, html.toString());
            return;
        }
        // 左端の色の意味（締め日を過ぎた期間は「確定」、まだ働いている期間は「予定」）
        html.append("<div class=\"legend\"><span><i></i>確定（締め済み）</span>")
            .append("<span><i class=\"plan\"></i>予定（集計中）</span></div>");

        LocalDate today = LocalDate.now();
        html.append("<div class=\"cards\">");
        for (Salary.Row row : s.rows()) {
            PayPeriod p = row.period();
            boolean plan = !p.end().isBefore(today);  // 締め日がまだ来ていなければ「予定」
            html.append("<a class=\"card").append(plan ? " plan" : "").append("\" href=\"/records?closing=")
                .append(p.closingMonth()).append("\">")
                .append("<span class=\"l\"><b>").append(p.paydayText()).append("</b>")
                .append("<small>").append(p.rangeText()).append(plan ? "・予定" : "").append("</small></span>")
                .append("<span class=\"days\"><small>勤務</small><b>").append(row.days()).append("日</b></span>")
                .append("<span class=\"amt\">").append(yen(row.total())).append("</span><i class=\"chev\"></i></a>");
        }
        html.append("<div class=\"card sum\"><span>").append(year).append("年の合計</span><span>")
            .append(yen(s.yearTotal())).append("</span></div></div>");
        send(exchange, html.toString());
    }

    // 「次の給料日」と、その日にもらえる金額を表示するHTML
    static String paydayInfoHtml() throws IOException {
        Settings settings = Settings.load();
        LocalDate today = LocalDate.now();
        PayPeriod current = PayPeriod.of(today, settings);
        PayPeriod previous = PayPeriod.ofClosingMonth(current.closingMonth().minusMonths(1), settings);

        // 締めが終わったけど、まだ給料日が来ていない分があれば、それが「次の給料日」。
        // もう支給済みなら、今働いている期間の給料日が「次の給料日」
        PayPeriod next = previous.payday().isBefore(today) ? current : previous;

        long total = 0;
        for (Salary.Entry e : Salary.loadEntries()) {
            if (next.contains(e.date())) {
                total += e.total();
            }
        }

        return "<div class=\"hero\"><small>次の給料日</small>"
             + "<div class=\"hl\"><span class=\"day\">" + next.paydayText() + "</span><span class=\"suf\">に</span></div>"
             + "<div class=\"hl\"><span class=\"yen\">" + yen(total) + "</span><span class=\"suf\">入ります</span></div>"
             + "<span class=\"pill\">" + next.rangeText() + " の勤務分</span></div>";
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
                    case "outAt" -> TimeCard.clockOutAt(params.getOrDefault("end", ""));
                    case "cancel" -> TimeCard.cancel();
                    default -> "不明な操作です。";
                };
            } catch (RuntimeException e) {
                message = errorText(e, "入力内容を確認してください。");
            }
        } else {
            // 「出勤を取り消す」のリンクから来たときは、確認画面を出す（ここではまだ取り消さない）
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            TimeCard.Punch p = TimeCard.load();
            if ("cancel".equals(query.get("confirm")) && p != null) {
                send(exchange, "<a class=\"back\" href=\"/punch\">‹ タイムカード</a>"
                        + "<h1>出勤の取り消し</h1>"
                        + "<div class=\"sec\">この出勤を取り消します。記録は残りません。</div>"
                        + "<div class=\"group\"><div class=\"row\"><span>出勤</span><span class=\"v\">"
                        + p.date() + " " + p.start() + "</span></div></div>"
                        + "<form action=\"/punch\" method=\"post\" class=\"buttons\">"
                        + "<button name=\"action\" value=\"cancel\" class=\"danger\">取り消す</button></form>");
                return;
            }
        }

        TimeCard.Punch p = TimeCard.load();
        StringBuilder html = new StringBuilder("<h1>タイムカード</h1>");
        if (!message.isEmpty()) {
            html.append("<p class=\"result\">").append(message).append("</p>");
        }
        html.append("<form action=\"/punch\" method=\"post\" class=\"punch\">");
        // 今の状態を、上に大きく表示する
        if (p == null) {
            html.append(hero("今の状態", "<span class=\"big off\">勤務していません</span>"))
                .append("<div class=\"buttons\"><button name=\"action\" value=\"in\">出勤</button></div>");
        } else if (p.forgotClockOut()) {
            // 退勤の押し忘れ：退勤時刻を入力してもらう
            html.append(hero("今の状態", "<span class=\"big alert\">退勤の押し忘れ</span>",
                        p.date() + " " + p.start() + " に出勤"))
                .append("<div class=\"group\"><label class=\"row\"><span>退勤時刻</span>")
                .append("<input type=\"time\" name=\"end\" required></label></div>")
                .append("<div class=\"buttons\"><button name=\"action\" value=\"outAt\" class=\"out\">この時刻で退勤</button></div>");
        } else if (p.onBreak()) {
            html.append(hero("今の状態", "<span class=\"big rest\">休憩中</span><span class=\"suf\">"
                        + p.breakStart() + "から</span>", p.start() + " に出勤"))
                .append("<div class=\"buttons\"><button name=\"action\" value=\"breakEnd\" class=\"break\">休憩終了</button>")
                .append("<button name=\"action\" value=\"out\" class=\"out\">退勤</button></div>");
        } else {
            String big = "<span class=\"big\">勤務中</span><span class=\"suf\">" + p.start() + "から</span>";
            html.append(p.breakTaken()
                    ? hero("今の状態", big, "休憩 " + p.breakStart() + "〜" + p.breakEnd())
                    : hero("今の状態", big));
            html.append("<div class=\"buttons\">");
            if (!p.breakTaken()) {
                html.append("<button name=\"action\" value=\"breakStart\" class=\"break\">休憩開始</button>");
            }
            html.append("<button name=\"action\" value=\"out\" class=\"out\">退勤</button></div>");
        }
        html.append("</form>");
        if (p != null) {
            // 間違えて出勤したとき用の、目立たないリンク
            html.append("<p class=\"sub\"><a href=\"/punch?confirm=cancel\">出勤を取り消す</a></p>");
        }
        send(exchange, html.toString());
    }

    // 画面の上に置く、大きな表示の箱
    // label：上の小さい見出し、main：大きく出す部分、pill：下の丸い枠の文字（なければ出さない）
    static String hero(String label, String main, String pill) {
        return "<div class=\"hero\"><small>" + label + "</small><div class=\"hl\">" + main + "</div>"
             + (pill == null ? "" : "<span class=\"pill\">" + pill + "</span>") + "</div>";
    }

    static String hero(String label, String main) {
        return hero(label, main, null);
    }

    // 「左に項目名、右に値」の1行
    static String infoRow(String label, String value) {
        return "<div class=\"row\"><span>" + label + "</span><span class=\"v\">" + value + "</span></div>";
    }

    // ---- 記録の一覧・修正・削除 ----

    // 「/records?closing=2026-10」：10月締めの期間の記録を一覧で表示する
    static void handleRecords(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        YearMonth closing;
        try {
            closing = YearMonth.parse(params.get("closing"));
        } catch (RuntimeException e) {
            redirect(exchange, "/summary");
            return;
        }
        send(exchange, recordsHtml(closing, ""));
    }

    // 「closing月締めの期間」の記録一覧のHTMLを作る（message は上に出すお知らせ）
    // 行をタップすると修正画面へ（削除は修正画面の一番下から）
    static String recordsHtml(YearMonth closing, String message) throws IOException {
        PayPeriod period = PayPeriod.ofClosingMonth(closing, Settings.load());
        StringBuilder html = new StringBuilder();
        html.append("<a class=\"back\" href=\"/summary?year=").append(period.payday().getYear()).append("\">‹ 集計</a>");
        html.append("<h1>").append(period.paydayText()).append("支給分</h1>");
        html.append(message);

        int count = 0;
        long sum = 0;
        boolean plan = !period.end().isBefore(LocalDate.now());  // 締め日がまだ来ていなければ「予定」
        StringBuilder cards = new StringBuilder();
        // 日付の順に並べて表示する（行番号はそのまま持っておく）
        List<Salary.Entry> entries = Salary.loadEntries();
        entries.sort(Comparator.comparing(Salary.Entry::date).thenComparing(Salary.Entry::start));
        for (Salary.Entry e : entries) {
            if (!period.contains(e.date())) {
                continue;  // この期間に働いた分だけを表示する
            }
            count++;
            sum += e.total();
            cards.append("<a class=\"card").append(plan ? " plan" : "").append("\" href=\"/edit?").append(ids(e))
                .append("\"><span class=\"l\"><b>").append(e.date().getMonthValue()).append("/")
                .append(e.date().getDayOfMonth()).append("　").append(escape(e.start())).append("〜")
                .append(escape(e.end())).append("</b><small>");
            if (e.breakMinutes() > 0) {
                cards.append("休憩").append(e.breakMinutes()).append("分・");
            }
            cards.append("時給").append(e.wage()).append("円</small></span>")
                .append("<span class=\"amt\">").append(yen(e.total())).append("</span><i class=\"chev\"></i></a>");
        }

        // 上に、この給料日の合計を大きく表示する
        html.append(hero(period.rangeText() + " の勤務分",
                "<span class=\"yen\">" + yen(sum) + "</span>",
                count + "日勤務" + (plan ? "・予定" : "")));
        if (count == 0) {
            html.append("<div class=\"group\"><div class=\"row\"><span class=\"muted\">この期間の記録はありません</span></div></div>");
        } else {
            html.append("<div class=\"sec\">勤務した日</div><div class=\"cards\">").append(cards).append("</div>");
        }
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
        YearMonth closing = closingMonth(e.date());

        if (!isPost) {
            // 確認画面（ここではまだ削除しない）
            send(exchange, "<a class=\"back\" href=\"/edit?" + ids(e) + "\">‹ 修正</a>"
                    + "<h1>記録の削除</h1>"
                    + "<div class=\"sec\">次の記録を削除します。よろしいですか？</div>"
                    + "<div class=\"group\">" + infoRow("日付", e.date().toString())
                    + infoRow("時間", escape(e.start()) + "〜" + escape(e.end()))
                    + infoRow("給料", yen(e.total())) + "</div>"
                    + "<form action=\"/delete\" method=\"post\" class=\"buttons\">"
                    + hidden(e)
                    + "<button type=\"submit\" class=\"danger\">削除する</button></form>");
            return;
        }

        if (Salary.replaceLine(e.index(), e.check(), null)) {
            send(exchange, recordsHtml(closing, "<p class=\"result\">記録を削除しました。</p>"));
        } else {
            send(exchange, notFoundHtml());
        }
    }

    // 修正フォーム（%s のところに戻り先・入力欄の値などが入る）
    static final String EDIT_FORM = """
        <a class="back" href="/records?closing=%s">‹ 一覧</a>
        <h1>記録の修正</h1>
        %s
        <form action="/edit" method="post">
          %s
          <div class="group">
            <label class="row"><span>日付</span><input type="date" name="date" value="%s" required></label>
            <label class="row"><span>時給（円）</span><input type="number" name="wage" value="%s" min="1" max="100000" inputmode="numeric" required></label>
            <label class="row"><span>開始</span><input type="time" name="start" value="%s" required></label>
            <label class="row"><span>終了</span><input type="time" name="end" value="%s" required></label>
          </div>
          <div class="group">
            <label class="row"><span>休憩（分）</span><input type="number" name="break" value="%s" min="0" inputmode="numeric"></label>
            <label class="row"><span>休憩の開始</span><input type="time" name="breakStart" value="%s"></label>
          </div>
          <div class="buttons"><button type="submit">保存する</button></div>
        </form>
        <div class="group"><a class="rowbtn" href="/delete?%s">この記録を削除</a></div>
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
            send(exchange, String.format(EDIT_FORM, closingMonth(e.date()), "", hidden(e),
                    e.date(), e.wage(), escape(e.start()), escape(e.end()),
                    e.breakMinutes(), escape(breakStart), ids(e)));
            return;
        }

        try {
            LocalDate date = LocalDate.parse(params.get("date"));
            // 修正画面でだけ、時給も直せる（設定の時給は変わらない）
            int wage = Integer.parseInt(params.get("wage"));
            if (wage < 1 || wage > 100_000) {
                throw new IllegalArgumentException("時給の範囲外");
            }
            Salary.PayResult r = calc(params, wage);
            String newLine = Salary.toLine(date, wage, params.get("start"), params.get("end"),
                    breakMinutes(params), breakStart(params), r.total());
            if (Salary.replaceLine(e.index(), e.check(), newLine)) {
                send(exchange, recordsHtml(closingMonth(date),
                        "<p class=\"result\">記録を修正しました（" + yen(r.total()) + "）。</p>"));
            } else {
                send(exchange, notFoundHtml());
            }
        } catch (RuntimeException ex) {
            // 入力がおかしいときは、入力した内容のままフォームを出し直す
            send(exchange, String.format(EDIT_FORM, closingMonth(e.date()),
                    "<p class=\"error\">" + errorText(ex, "入力内容を確認してください。") + "</p>", hidden(e),
                    escape(params.getOrDefault("date", "")), escape(params.getOrDefault("wage", "")),
                    escape(params.getOrDefault("start", "")), escape(params.getOrDefault("end", "")),
                    escape(params.getOrDefault("break", "0")), escape(params.getOrDefault("breakStart", "")),
                    ids(e)));
        }
    }

    // その日に働いた分が、何月締めの期間に入るか
    static YearMonth closingMonth(LocalDate workDate) {
        return PayPeriod.of(workDate, Settings.load()).closingMonth();
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

    // どの記録かをアドレスで伝えるための「line=3&check=abcd」
    static String ids(Salary.Entry e) {
        return "line=" + e.index() + "&check=" + e.check();
    }

    static String notFoundHtml() {
        return "<a class=\"back\" href=\"/summary\">‹ 集計</a>"
             + "<h1>記録が見つかりません</h1>"
             + "<p class=\"error\">記録が見つからないか、ほかの画面で変更されています。"
             + "もう一度、一覧から選び直してください。</p>";
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
                        Integer.parseInt(params.get("closingDay")),
                        Integer.parseInt(params.get("paydayMonth")),
                        Integer.parseInt(params.get("paydayDay")),
                        Long.parseLong(params.get("fuyouLimit")));
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

        send(exchange, "<h1>設定</h1>" + message
                + "<form action=\"/settings\" method=\"post\">"
                + "<div class=\"group\"><label class=\"row\"><span>時給（円）</span><input type=\"number\" name=\"wage\" value=\""
                + s.wage() + "\" min=\"1\" inputmode=\"numeric\" required></label></div>"
                + "<p class=\"foot\">時給を変えても、これまでの記録の給料は変わりません。</p>"
                + "<div class=\"group\">"
                + "<label class=\"row\"><span>締め日</span><select name=\"closingDay\">" + dayOptions(s.closingDay()) + "</select></label>"
                + "<div class=\"row\"><span>給料日</span><span class=\"two\">"
                + "<select name=\"paydayMonth\" aria-label=\"給料日の月\">"
                + "<option value=\"0\"" + (s.paydayMonth() == 0 ? " selected" : "") + ">当月</option>"
                + "<option value=\"1\"" + (s.paydayMonth() == 1 ? " selected" : "") + ">翌月</option></select>"
                + "<select name=\"paydayDay\" aria-label=\"給料日の日\">" + dayOptions(s.paydayDay()) + "</select></span></div>"
                + "</div>"
                + "<p class=\"foot\">土日・祝日の場合は、前の平日になります。</p>"
                + "<div class=\"group\"><label class=\"row\"><span>扶養ライン（円）</span><input type=\"number\" name=\"fuyouLimit\" value=\""
                + s.fuyouLimit() + "\" min=\"0\" step=\"10000\" inputmode=\"numeric\" required></label></div>"
                + "<div class=\"buttons\"><button type=\"submit\">保存する</button></div>"
                + "</form>"
                + "<form action=\"/logout\" method=\"post\" class=\"group\">"
                + "<button type=\"submit\" class=\"rowbtn\">ログアウト</button></form>");
    }

    // 「1日」〜「28日」と「末日」の選択肢（selected の日を選んだ状態にする）
    static String dayOptions(int selected) {
        StringBuilder html = new StringBuilder();
        for (int d = 1; d <= 28; d++) {
            html.append("<option value=\"").append(d).append("\"").append(d == selected ? " selected" : "")
                .append(">").append(d).append("日</option>");
        }
        html.append("<option value=\"").append(Settings.END_OF_MONTH).append("\"")
            .append(selected == Settings.END_OF_MONTH ? " selected" : "").append(">末日</option>");
        return html.toString();
    }

    // フォームの値と時給から給料を計算する
    static Salary.PayResult calc(Map<String, String> params, int wage) {
        return Salary.calculateDetail(wage, params.get("start"), params.get("end"),
                breakMinutes(params), breakStart(params));
    }

    static int breakMinutes(Map<String, String> params) {
        String value = params.getOrDefault("break", "");
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    static String breakStart(Map<String, String> params) {
        return params.getOrDefault("breakStart", "");
    }

    // エラーの説明文。入力チェックで止まったときはその理由を、それ以外は決まった文を出す
    static String errorText(RuntimeException e, String fallback) {
        return e instanceof Salary.InputException ? escape(e.getMessage()) : fallback;
    }

    // 金額を「12,345円」の形にする
    static String yen(long amount) {
        return String.format("%,d円", amount);
    }

    // 計算結果（合計と内訳）のHTMLを作る
    static String resultHtml(Map<String, String> params, Salary.PayResult r) {
        return hero(escape(params.get("start")) + "〜" + escape(params.get("end")) + " の給料",
                    "<span class=\"yen\">" + yen(r.total()) + "</span>")
             + "<div class=\"sec\">内訳</div><div class=\"group\">"
             + row("通常", r.normalMinutes(), r.normalPay())
             + row("深夜（×1.25）", r.nightMinutes(), r.nightPay())
             + row("残業（×1.25）", r.overtimeMinutes(), r.overtimePay())
             + row("深夜残業（×1.50）", r.overtimeNightMinutes(), r.overtimeNightPay())
             + "</div>";
    }

    // 内訳の1行分のHTMLを作る（左に区分と時間、右に金額）
    static String row(String label, int minutes, long pay) {
        return "<div class=\"row\"><span class=\"l\">" + label + "<small>" + Salary.format(minutes)
             + "</small></span><span class=\"v\">" + yen(pay) + "</span></div>";
    }

    // ページの中身を共通の枠（メニュー付き）に入れて、ブラウザに送る
    static void send(HttpExchange exchange, String content) throws IOException {
        sendHtml(exchange, String.format(LAYOUT, content, navHtml(exchange.getRequestURI().getPath())));
    }

    // ログイン画面用（メニューなし）
    static void sendPage(HttpExchange exchange, String content) throws IOException {
        sendHtml(exchange, String.format(LAYOUT, content, ""));
    }

    // 下のメニューのHTMLを作る。今開いているページのタブを緑色にする
    static String navHtml(String path) {
        // ページのアドレスから、どのタブのページかを決める
        String active = switch (path) {
            case "/punch" -> "punch";
            case "/summary", "/records", "/edit", "/delete" -> "summary";
            case "/settings" -> "settings";
            default -> "calc";  // 「/」と「/record」
        };
        return "<nav>"
             + tab("/punch", "タイムカード", active.equals("punch"),
                   "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/>")
             + tab("/", "計算・記録", active.equals("calc"),
                   "<rect x=\"5\" y=\"3\" width=\"14\" height=\"18\" rx=\"2\"/>"
                   + "<path d=\"M8 7h8M8 11h2M12 11h2M8 15h2M12 15h2\"/>")
             + tab("/summary", "集計", active.equals("summary"),
                   "<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>")
             + tab("/settings", "設定", active.equals("settings"),
                   "<circle cx=\"12\" cy=\"12\" r=\"3\"/>"
                   + "<path d=\"M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2\"/>")
             + "</nav>";
    }

    // タブ1つ分（アイコン＋文字）
    static String tab(String href, String label, boolean on, String icon) {
        return "<a href=\"" + href + "\"" + (on ? " class=\"on\" aria-current=\"page\"" : "") + ">"
             + "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" + icon + "</svg>" + label + "</a>";
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
