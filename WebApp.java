import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class WebApp {

    // 入力フォームのHTML（%s のところに計算結果が入る）
    static final String PAGE = """
        <!DOCTYPE html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>給料計算</title>
          <style>
            body { font-family: sans-serif; max-width: 420px; margin: 24px auto; padding: 0 16px; }
            label { display: block; margin-top: 12px; }
            input { width: 100%%; padding: 8px; font-size: 16px; box-sizing: border-box; }
            button { margin-top: 16px; width: 100%%; padding: 12px; font-size: 16px; }
            .result { margin-top: 20px; padding: 16px; background: #eef6f4; border-radius: 8px; font-size: 20px; }
            .error { margin-top: 20px; color: #b4471f; }
          </style>
        </head>
        <body>
          <h1>ライブハウスバイト給料計算</h1>
          <form action="/" method="get">
            <label>時給（円）<input type="number" name="wage" value="1200" required></label>
            <label>開始時刻<input type="time" name="start" required></label>
            <label>終了時刻<input type="time" name="end" required></label>
            <label>休憩（分）<input type="number" name="break" value="0" min="0"></label>
            <label>休憩の開始時刻（休憩がある場合）<input type="time" name="breakStart"></label>
            <button type="submit">計算する</button>
          </form>
          %s
        </body>
        </html>
        """;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", WebApp::handle);
        server.start();
        System.out.println("サーバーを起動しました: http://localhost:8080");
    }

    // ブラウザからのアクセスを処理する
    static void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String resultHtml = "";

        // フォームが送信されていたら（wage が入っていたら）計算する
        if (params.containsKey("wage")) {
            try {
                int wage = Integer.parseInt(params.get("wage"));
                String start = params.get("start");
                String end = params.get("end");
                int breakMinutes = Integer.parseInt(params.getOrDefault("break", "0"));
                String breakStart = params.getOrDefault("breakStart", "");
                if (breakStart.isEmpty()) {
                    breakStart = "0:00";
                }

                long total = Salary.calculate(wage, start, end, breakMinutes, breakStart, false);

                resultHtml = "<div class=\"result\">" + escape(start) + "〜" + escape(end)
                           + " の給料は <strong>" + String.format("%,d", total) + "円</strong> です</div>";
            } catch (RuntimeException e) {
                resultHtml = "<p class=\"error\">入力内容を確認してください。</p>";
            }
        }

        byte[] body = String.format(PAGE, resultHtml).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // 「wage=1200&start=18%3A00」のような文字列を、名前と値の組（Map）に分ける
    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) {
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