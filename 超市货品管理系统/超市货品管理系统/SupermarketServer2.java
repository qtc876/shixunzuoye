import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class SupermarketServer2 {

    private static final String DATA_FILE = "products.json";
    private static List<Map<String, Object>> products = new ArrayList<>();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter logWriter;

    public static void main(String[] args) throws Exception {
        logWriter = new PrintWriter(new FileWriter("server.log", true), true);
        log("Starting server...");
        
        try {
            loadProducts();
            if (products.isEmpty()) {
                log("No products found, initializing sample data...");
                initSampleData();
                saveProducts();
                log("Sample data initialized: " + products.size() + " products");
            } else {
                log("Loaded " + products.size() + " products");
            }

            HttpServer server = HttpServer.create(new InetSocketAddress(5000), 0);
            server.createContext("/", new RootHandler());
            server.setExecutor(null);
            server.start();
            
            log("Server started successfully on port 5000");
            log("Open http://localhost:5000 in your browser");
            System.out.println("Server started on port 5000");
            System.out.println("Open http://localhost:5000 in your browser");
        } catch (Exception e) {
            log("ERROR starting server: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log(sw.toString());
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private static void log(String msg) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = "[" + timestamp + "] " + msg;
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
    }

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            
            log("Request: " + method + " " + path);
            setCorsHeaders(exchange);
            
            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                if (path.startsWith("/api/products")) {
                    handleProducts(exchange, method, path);
                } else if (path.equals("/api/categories")) {
                    handleCategories(exchange, method);
                } else if (path.equals("/api/stats")) {
                    handleStats(exchange, method);
                } else {
                    serveStaticFile(exchange, path);
                }
            } catch (Exception e) {
                log("ERROR handling request: " + e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log(sw.toString());
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    private static void handleProducts(HttpExchange exchange, String method, String path) throws IOException {
        if (method.equals("GET")) {
            if (path.equals("/api/products")) {
                handleGetProducts(exchange);
            } else {
                handleGetProduct(exchange, path);
            }
        } else if (method.equals("POST")) {
            if (path.endsWith("/sale")) {
                handleSaleProduct(exchange, path);
            } else if (path.endsWith("/restock")) {
                handleRestockProduct(exchange, path);
            } else if (path.equals("/api/products")) {
                handleAddProduct(exchange);
            } else {
                sendError(exchange, 404, "Not Found");
            }
        } else if (method.equals("PUT")) {
            handleUpdateProduct(exchange, path);
        } else if (method.equals("DELETE")) {
            handleDeleteProduct(exchange, path);
        } else {
            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    private static void handleGetProducts(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String query = uri.getQuery();
        String keyword = "";
        String category = "";

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    String key = pair[0];
                    String value = java.net.URLDecoder.decode(pair[1], "UTF-8");
                    if (key.equals("keyword")) keyword = value;
                    if (key.equals("category")) category = value;
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> p : products) {
            boolean matchKeyword = keyword.isEmpty() ||
                p.get("productId").toString().toLowerCase().contains(keyword.toLowerCase()) ||
                p.get("productName").toString().toLowerCase().contains(keyword.toLowerCase());
            boolean matchCategory = category.isEmpty() || p.get("category").equals(category);
            if (matchKeyword && matchCategory) {
                result.add(p);
            }
        }

        log("GET /api/products returning " + result.size() + " products");
        sendJson(exchange, 200, toJsonArray(result));
    }

    private static void handleGetProduct(HttpExchange exchange, String path) throws IOException {
        String productId = extractProductId(path);
        Map<String, Object> product = findProduct(productId);
        if (product != null) {
            sendJson(exchange, 200, toJsonObject(product));
        } else {
            sendError(exchange, 404, "Product not found");
        }
    }

    private static void handleAddProduct(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        log("POST /api/products body: " + body);
        Map<String, Object> data = parseJson(body);

        String newId = data.containsKey("productId") ? (String) data.get("productId") : "";
        if (newId != null && !newId.isEmpty()) {
            if (findProduct(newId) != null) {
                sendError(exchange, 400, "Product ID already exists");
                return;
            }
        } else {
            newId = generateId();
        }

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productId", newId);
        product.put("productName", getString(data, "productName", ""));
        product.put("category", getString(data, "category", "Other"));
        product.put("price", getDouble(data, "price", 0));
        product.put("stock", getInt(data, "stock", 0));
        product.put("soldCount", getInt(data, "soldCount", 0));
        product.put("newArrival", getInt(data, "newArrival", 0));
        product.put("supplier", getString(data, "supplier", ""));
        String now = sdf.format(new Date());
        product.put("createTime", now);
        product.put("updateTime", now);

        products.add(product);
        saveProducts();
        log("Product added: " + newId);
        sendJson(exchange, 201, toJsonObject(product));
    }

    private static void handleUpdateProduct(HttpExchange exchange, String path) throws IOException {
        String productId = extractProductId(path);
        int index = findProductIndex(productId);
        if (index == -1) {
            sendError(exchange, 404, "Product not found");
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> data = parseJson(body);

        Map<String, Object> product = products.get(index);
        if (data.containsKey("productName")) product.put("productName", data.get("productName"));
        if (data.containsKey("category")) product.put("category", data.get("category"));
        if (data.containsKey("price")) product.put("price", getDouble(data, "price", 0));
        if (data.containsKey("stock")) product.put("stock", getInt(data, "stock", 0));
        if (data.containsKey("soldCount")) product.put("soldCount", getInt(data, "soldCount", 0));
        if (data.containsKey("newArrival")) product.put("newArrival", getInt(data, "newArrival", 0));
        if (data.containsKey("supplier")) product.put("supplier", data.get("supplier"));
        product.put("updateTime", sdf.format(new Date()));

        saveProducts();
        log("Product updated: " + productId);
        sendJson(exchange, 200, toJsonObject(product));
    }

    private static void handleDeleteProduct(HttpExchange exchange, String path) throws IOException {
        String productId = extractProductId(path);
        int index = findProductIndex(productId);
        if (index == -1) {
            sendError(exchange, 404, "Product not found");
            return;
        }

        Map<String, Object> deleted = products.remove(index);
        saveProducts();
        log("Product deleted: " + productId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Deleted successfully");
        result.put("product", deleted);
        sendJson(exchange, 200, toJsonObject(result));
    }

    private static void handleSaleProduct(HttpExchange exchange, String path) throws IOException {
        String productId = extractProductIdFromAction(path, "sale");
        int index = findProductIndex(productId);
        if (index == -1) {
            sendError(exchange, 404, "Product not found");
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> data = parseJson(body);
        int quantity = getInt(data, "quantity", 1);

        Map<String, Object> product = products.get(index);
        int stock = ((Number) product.get("stock")).intValue();
        if (stock < quantity) {
            sendError(exchange, 400, "Insufficient stock");
            return;
        }

        product.put("stock", stock - quantity);
        product.put("soldCount", ((Number) product.get("soldCount")).intValue() + quantity);
        product.put("updateTime", sdf.format(new Date()));

        saveProducts();
        log("Product sale: " + productId + " qty=" + quantity);
        sendJson(exchange, 200, toJsonObject(product));
    }

    private static void handleRestockProduct(HttpExchange exchange, String path) throws IOException {
        String productId = extractProductIdFromAction(path, "restock");
        int index = findProductIndex(productId);
        if (index == -1) {
            sendError(exchange, 404, "Product not found");
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> data = parseJson(body);
        int quantity = getInt(data, "quantity", 1);

        Map<String, Object> product = products.get(index);
        int stock = ((Number) product.get("stock")).intValue();
        int newArrival = ((Number) product.get("newArrival")).intValue();

        product.put("stock", stock + quantity);
        product.put("newArrival", newArrival + quantity);
        product.put("updateTime", sdf.format(new Date()));

        saveProducts();
        log("Product restock: " + productId + " qty=" + quantity);
        sendJson(exchange, 200, toJsonObject(product));
    }

    private static void handleCategories(HttpExchange exchange, String method) throws IOException {
        if (!method.equals("GET")) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        Set<String> categories = new TreeSet<>();
        for (Map<String, Object> p : products) {
            categories.add((String) p.get("category"));
        }
        
        sendJson(exchange, 200, toJsonArray(categories));
    }

    private static void handleStats(HttpExchange exchange, String method) throws IOException {
        if (!method.equals("GET")) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        int totalProducts = products.size();
        int totalStock = 0;
        int totalSold = 0;
        double totalValue = 0;
        int lowStock = 0;

        for (Map<String, Object> p : products) {
            int stock = ((Number) p.get("stock")).intValue();
            int sold = ((Number) p.get("soldCount")).intValue();
            double price = ((Number) p.get("price")).doubleValue();
            
            totalStock += stock;
            totalSold += sold;
            totalValue += stock * price;
            if (stock < 10) lowStock++;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProducts", totalProducts);
        stats.put("totalStock", totalStock);
        stats.put("totalSold", totalSold);
        stats.put("totalValue", Math.round(totalValue * 100.0) / 100.0);
        stats.put("lowStock", lowStock);

        sendJson(exchange, 200, toJsonObject(stats));
    }

    private static void serveStaticFile(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) {
            path = "/index.html";
        }
        
        File file = new File("." + path);
        if (file.exists() && !file.isDirectory()) {
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, file.length());
            
            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        } else {
            String response = "404 Not Found";
            exchange.sendResponseHeaders(404, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        }
    }

    private static String extractProductId(String path) {
        path = path.replace("/api/products/", "");
        if (path.contains("/")) {
            path = path.substring(0, path.indexOf("/"));
        }
        return path;
    }

    private static String extractProductIdFromAction(String path, String action) {
        String prefix = "/api/products/";
        String suffix = "/" + action;
        int start = prefix.length();
        int end = path.length() - suffix.length();
        if (start < end) {
            return path.substring(start, end);
        }
        return "";
    }

    private static Map<String, Object> findProduct(String productId) {
        for (Map<String, Object> p : products) {
            if (p.get("productId").equals(productId)) {
                return p;
            }
        }
        return null;
    }

    private static int findProductIndex(String productId) {
        for (int i = 0; i < products.size(); i++) {
            if (products.get(i).get("productId").equals(productId)) {
                return i;
            }
        }
        return -1;
    }

    private static String generateId() {
        int maxNum = 0;
        for (Map<String, Object> p : products) {
            String id = (String) p.get("productId");
            if (id.startsWith("P")) {
                try {
                    int num = Integer.parseInt(id.substring(1));
                    if (num > maxNum) maxNum = num;
                } catch (Exception e) {}
            }
        }
        return String.format("P%03d", maxNum + 1);
    }

    private static void loadProducts() {
        try {
            File file = new File(DATA_FILE);
            if (!file.exists()) return;
            
            String content = new String(Files.readAllBytes(Paths.get(DATA_FILE)), StandardCharsets.UTF_8);
            products = parseJsonArray(content);
        } catch (Exception e) {
            log("Error loading products: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void saveProducts() {
        try (FileWriter fw = new FileWriter(DATA_FILE)) {
            fw.write(toJsonArray(products));
        } catch (IOException e) {
            log("Error saving products: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void initSampleData() {
        String now = sdf.format(new Date());
        
        products.add(createProduct("P001", "Test Product 1", "Drinks", 2.5, 120, 80, 50, "Supplier A", now));
        products.add(createProduct("P002", "Test Product 2", "Snacks", 4.5, 85, 115, 30, "Supplier B", now));
        products.add(createProduct("P003", "Test Product 3", "Dairy", 6.8, 60, 90, 40, "Supplier C", now));
        products.add(createProduct("P004", "Test Product 4", "Snacks", 8.9, 45, 55, 20, "Supplier D", now));
        products.add(createProduct("P005", "Test Product 5", "Condiments", 12.5, 8, 42, 0, "Supplier E", now));
        products.add(createProduct("P006", "Test Product 6", "Fruits", 9.9, 30, 70, 25, "Supplier F", now));
        products.add(createProduct("P007", "Test Product 7", "Drinks", 5.5, 200, 150, 100, "Supplier G", now));
        products.add(createProduct("P008", "Test Product 8", "Snacks", 11.8, 55, 45, 30, "Supplier H", now));
    }

    private static Map<String, Object> createProduct(String id, String name, String category, 
            double price, int stock, int sold, int newArrival, String supplier, String time) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("productId", id);
        p.put("productName", name);
        p.put("category", category);
        p.put("price", price);
        p.put("stock", stock);
        p.put("soldCount", sold);
        p.put("newArrival", newArrival);
        p.put("supplier", supplier);
        p.put("createTime", time);
        p.put("updateTime", time);
        return p;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message);
        sendJson(exchange, code, toJsonObject(error));
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return def; }
    }

    private static double getDouble(Map<String, Object> map, String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return def; }
    }

    private static String toJsonObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(jsonValue(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toJsonArray(Collection<?> collection) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object item : collection) {
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonValue(item));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) return toJsonObject((Map<String, Object>) value);
        if (value instanceof Collection) return toJsonArray((Collection<?>) value);
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Map<String, Object> parseJson(String json) {
        json = json.trim();
        if (json.startsWith("{")) {
            return parseJsonObject(json, new int[]{0});
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> parseJsonArray(String json) {
        List<Map<String, Object>> list = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[")) return list;

        int i = 1;
        while (i < json.length()) {
            skipWhitespace(json, new int[]{i});
            if (json.charAt(i) == ']') break;
            if (json.charAt(i) == '{') {
                int[] pos = new int[]{i};
                Map<String, Object> obj = parseJsonObject(json, pos);
                list.add(obj);
                i = pos[0];
            }
            skipWhitespace(json, new int[]{i});
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
        return list;
    }

    private static Map<String, Object> parseJsonObject(String json, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        int i = pos[0];
        
        if (json.charAt(i) != '{') return map;
        i++;
        
        while (i < json.length()) {
            skipWhitespace(json, new int[]{i});
            if (json.charAt(i) == '}') {
                i++;
                break;
            }
            
            String key = parseString(json, new int[]{i});
            skipWhitespace(json, new int[]{i});
            if (json.charAt(i) == ':') i++;
            skipWhitespace(json, new int[]{i});
            
            Object value = parseValue(json, new int[]{i});
            map.put(key, value);
            
            skipWhitespace(json, new int[]{i});
            if (i < json.length() && json.charAt(i) == ',') {
                i++;
            }
        }
        
        pos[0] = i;
        return map;
    }

    private static Object parseValue(String json, int[] pos) {
        int i = pos[0];
        char c = json.charAt(i);
        
        if (c == '"') {
            return parseString(json, pos);
        }
        if (c == '{') {
            return parseJsonObject(json, pos);
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>();
            i++;
            while (i < json.length()) {
                skipWhitespace(json, new int[]{i});
                if (json.charAt(i) == ']') { i++; break; }
                list.add(parseValue(json, new int[]{i}));
                skipWhitespace(json, new int[]{i});
                if (json.charAt(i) == ',') i++;
            }
            pos[0] = i;
            return list;
        }
        if (c == 't' && json.startsWith("true", i)) { pos[0] = i + 4; return true; }
        if (c == 'f' && json.startsWith("false", i)) { pos[0] = i + 5; return false; }
        if (c == 'n' && json.startsWith("null", i)) { pos[0] = i + 4; return null; }
        
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' 
                || json.charAt(i) == '-' || json.charAt(i) == 'e' || json.charAt(i) == 'E'
                || json.charAt(i) == '+')) {
            sb.append(json.charAt(i));
            i++;
        }
        pos[0] = i;
        String numStr = sb.toString();
        if (numStr.contains(".")) {
            return Double.parseDouble(numStr);
        }
        return Integer.parseInt(numStr);
    }

    private static String parseString(String json, int[] pos) {
        int i = pos[0];
        if (json.charAt(i) != '"') {
            StringBuilder sb = new StringBuilder();
            while (i < json.length() && json.charAt(i) != ':' && json.charAt(i) != ',' 
                    && json.charAt(i) != '}' && json.charAt(i) != ']') {
                sb.append(json.charAt(i));
                i++;
            }
            pos[0] = i;
            return sb.toString().trim();
        }
        
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && json.charAt(i) != '"') {
            if (json.charAt(i) == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(next); break;
                }
            } else {
                sb.append(json.charAt(i));
            }
            i++;
        }
        pos[0] = i + 1;
        return sb.toString();
    }

    private static void skipWhitespace(String json, int[] pos) {
        int i = pos[0];
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        pos[0] = i;
    }
}
