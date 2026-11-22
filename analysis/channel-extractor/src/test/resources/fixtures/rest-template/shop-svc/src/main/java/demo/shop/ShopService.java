package demo.shop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;

@Service
public class ShopService {
    private static final String STOCK = "http://stock-service:8080";

    private final RestTemplate restTemplate;
    private final WebClient webClient;

    @Value("${pricing.url}")
    private String pricingUrl;

    private String getServiceUrl(String serviceName) {
        return "http://" + serviceName;
    }

    /** GET by method name, base URL from a helper, path variable appended at runtime. */
    public Item item(long id) {
        return restTemplate.getForObject(getServiceUrl("stock-service") + "/api/stock/" + id, Item.class);
    }

    /** exchange with HttpMethod argument, host with port, chained getBody(). */
    public Object reserve(Reservation reservation) {
        return restTemplate.exchange(STOCK + "/api/stock/reserve?force=true", HttpMethod.POST,
                new HttpEntity<>(reservation), Object.class).getBody();
    }

    /** local variable assigned in both branches: two targets. */
    public void audit(boolean stock) {
        String url = "";
        if (stock) {
            url = getServiceUrl("stock-service") + "/api/stock/audit";
        } else {
            url = getServiceUrl("billing") + "/api/billing/audit";
        }
        restTemplate.put(url, new Object());
    }

    /** host named after the module directory, URI.create wrapper. */
    public void invoice(Invoice invoice) {
        restTemplate.postForObject(URI.create("http://billing/api/invoices"), invoice, Object.class);
    }

    /** a private helper resolved once per caller. */
    public void cancel(long id) {
        send("/api/stock/" + id + "/cancel");
    }

    public void restock() {
        send("/api/stock/restock");
    }

    private void send(String path) {
        restTemplate.exchange(STOCK + path, HttpMethod.PATCH, HttpEntity.EMPTY, Void.class);
    }

    // ---------------------------------------------------------------- not resolvable

    public Object price() {
        return restTemplate.getForObject(pricingUrl + "/prices", Object.class);
    }

    public Object dynamicMethod(HttpMethod method) {
        return restTemplate.exchange(STOCK + "/api/stock/reserve", method, HttpEntity.EMPTY, Object.class);
    }

    public Object rates() {
        return restTemplate.getForObject("http://api.example.com/v1/rates", Object.class);
    }

    public Object entity(RequestEntity<Void> request) {
        return restTemplate.exchange(request, Object.class);
    }

    public void missing() {
        restTemplate.delete(STOCK + "/api/stock/nowhere");
    }

    public void configure() {
        restTemplate.setErrorHandler(null);
    }

    public Object reactive() {
        return webClient.get().uri("http://stock-service/api/stock/1").retrieve().bodyToMono(Object.class).block();
    }
}
