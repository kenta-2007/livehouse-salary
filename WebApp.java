import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class WebApp {
    public static void main(String[] args) throws IOException {
        // 8080番ポートで待ち受けるサーバーを作る
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // 「/」にアクセスされたら、このページを返す
        server.createContext("/", exchange -> {
            String html = """
                <!DOCTYPE html>
                <html lang="ja">
                <head><meta charset="UTF-8"><title>給料計算</title></head>
                <body>
                  <h1>ライブハウスバイト給料計算</h1>
                  <p>Webサーバーが動きました！</p>
                </body>
                </html>
                """;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.start();
        System.out.println("サーバーを起動しました: http://localhost:8080");
    }
}
