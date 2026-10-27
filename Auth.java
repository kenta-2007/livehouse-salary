import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Scanner;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// パスワードを安全に保存・確認するためのクラス
public class Auth {

    // ハッシュ化したパスワードを保存するファイル（GitHubには上げない）
    static final String PASSWORD_FILE = "password.txt";
    // ハッシュ計算をくり返す回数（多いほど総当たり攻撃に強くなる）
    static final int ITERATIONS = 210_000;
    // 予測できない乱数を作る道具
    static final SecureRandom RANDOM = new SecureRandom();

    // パスワードとソルトから、ハッシュ値を計算する（PBKDF2という方式）
    static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // パスワードを設定する：ソルトを作り、ハッシュ値と一緒にファイルに保存する
    static void setPassword(String password) throws IOException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hashed = hash(password, salt);
        Base64.Encoder b64 = Base64.getEncoder();
        Files.writeString(Path.of(PASSWORD_FILE), b64.encodeToString(salt) + ":" + b64.encodeToString(hashed));
    }

    // パスワードが設定済みか
    static boolean hasPassword() {
        return Files.exists(Path.of(PASSWORD_FILE));
    }

    // 入力されたパスワードが正しいか確認する
    static boolean checkPassword(String password) throws IOException {
        if (!hasPassword()) {
            return false;
        }
        String[] parts = Files.readString(Path.of(PASSWORD_FILE)).trim().split(":");
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        // 保存したときと同じソルトでハッシュ化して、一致するか比べる
        return MessageDigest.isEqual(hash(password, salt), expected);
    }

    // パスワードを設定するためのプログラム（ターミナルで java -cp out Auth を実行）
    public static void main(String[] args) throws IOException {
        Console console = System.console();
        String password;
        String confirm;
        if (console != null) {
            // 入力した文字が画面に表示されない
            password = new String(console.readPassword("新しいパスワード（8文字以上）: "));
            confirm = new String(console.readPassword("もう一度入力: "));
        } else {
            System.out.println("※ この画面では入力したパスワードが表示されます。ターミナルでの実行がおすすめです。");
            Scanner sc = new Scanner(System.in);
            System.out.print("新しいパスワード（8文字以上）: ");
            password = sc.nextLine();
            System.out.print("もう一度入力: ");
            confirm = sc.nextLine();
        }

        if (password.length() < 8) {
            System.out.println("パスワードは8文字以上にしてください。");
            return;
        }
        if (!password.equals(confirm)) {
            System.out.println("2回の入力が一致しません。もう一度やり直してください。");
            return;
        }
        setPassword(password);
        System.out.println("パスワードを設定しました（" + PASSWORD_FILE + " に保存）。");
    }
}
