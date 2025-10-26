import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final HttpClient httpClient;
    private final String apiUrl;
    private final ReentrantLock lock;
    private final ConcurrentHashMap<Long, Integer> requests;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, "https://ismp.crpt.ru/api/v3/lk/documents/create");
    }

    public CrptApi(TimeUnit timeUnit, int requestLimit, String apiUrl) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.httpClient = HttpClient.newHttpClient();
        this.apiUrl = apiUrl;
        this.lock = new ReentrantLock();
        this.requests = new ConcurrentHashMap<>();
    }

    public void createDocument(Document document, String signature, String productGroup) 
            throws InterruptedException, IOException {
        waitIfLimitExceeded();
        
        String requestBody = buildRequestBody(document, signature);
        HttpRequest request = buildHttpRequest(requestBody, productGroup);
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("API request failed with status: " + response.statusCode());
        }
    }

    private void waitIfLimitExceeded() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - timeUnit.toMillis(1);
        
        lock.lock();
        try {
            cleanOldRequests(windowStart);
            
            while (getCurrentRequestsCount(windowStart) >= requestLimit) {
                Thread.sleep(100);
                currentTime = System.currentTimeMillis();
                windowStart = currentTime - timeUnit.toMillis(1);
                cleanOldRequests(windowStart);
            }
            
            requests.merge(currentTime, 1, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    private void cleanOldRequests(long windowStart) {
        requests.keySet().removeIf(timestamp -> timestamp < windowStart);
    }

    private int getCurrentRequestsCount(long windowStart) {
        return requests.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private String buildRequestBody(Document document, String signature) {
        String documentJson = document.toJson();
        String base64Document = Base64.getEncoder().encodeToString(documentJson.getBytes(StandardCharsets.UTF_8));
        
        return String.format(
            "{\"document_format\":\"MANUAL\",\"product_document\":\"%s\",\"type\":\"LP_INTRODUCE_GOODS\",\"signature\":\"%s\"}",
            base64Document, signature
        );
    }

    private HttpRequest buildHttpRequest(String requestBody, String productGroup) {
        String url = apiUrl + "?pg=" + productGroup;
        
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + getAuthToken())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private String getAuthToken() {
        // Для примера возвращаем заглушку
        return "auth_token_here";
    }

    public static class Document {
        private final String content;

        public Document(String content) {
            this.content = content;
        }

        public String toJson() {
            return content;
        }
    }
}