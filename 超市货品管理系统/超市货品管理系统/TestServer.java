import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;

public class TestServer {
    public static void main(String[] args) throws Exception {
        System.out.println("正在启动测试服务器...");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(5000), 0);
            server.createContext("/test", new HttpHandler() {
                public void handle(HttpExchange exchange) throws IOException {
                    String response = "Hello, this is a test!";
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.getBytes("UTF-8").length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes("UTF-8"));
                    os.close();
                }
            });
            server.setExecutor(null);
            server.start();
            System.out.println("服务器启动成功! 端口: 5000");
            System.out.println("访问 http://localhost:5000/test 测试");
        } catch (Exception e) {
            System.out.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
