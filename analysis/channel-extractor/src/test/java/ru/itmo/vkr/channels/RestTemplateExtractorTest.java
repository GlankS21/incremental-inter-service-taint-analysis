package ru.itmo.vkr.channels;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.itmo.vkr.channels.model.Channel;
import ru.itmo.vkr.channels.model.ChannelModel;
import ru.itmo.vkr.channels.model.HttpClientMethod;
import ru.itmo.vkr.channels.model.Unresolved;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RestTemplate requests of a shop service resolved to endpoints of a stock and a billing service
 * (fixture {@code fixtures/rest-template}).
 */
class RestTemplateExtractorTest {

    static ChannelModel model;

    @BeforeAll
    static void extract() throws IOException, URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                RestTemplateExtractorTest.class.getResource("/fixtures/rest-template")).toURI());
        model = new ChannelExtractor(root, null).extract();
    }

    @Test
    void resolvedRequestsBecomeRestTemplateClients() {
        assertThat(model.services()).containsExactlyInAnyOrder("billing-service", "shop-service", "stock-service");
        assertThat(model.httpClients())
                .allSatisfy(c -> assertThat(c.clientKind()).isEqualTo(HttpClientMethod.REST_TEMPLATE))
                .extracting(HttpClientMethod::httpMethod, HttpClientMethod::targetService, HttpClientMethod::path,
                        c -> c.callSites().getFirst().method())
                .containsExactlyInAnyOrder(
                        tuple("GET", "stock-service", "/api/stock/{?}", "item"),
                        tuple("POST", "stock-service", "/api/stock/reserve", "reserve"),
                        tuple("PUT", "stock-service", "/api/stock/audit", "audit"),
                        // the module directory name is an alias of billing-service
                        tuple("PUT", "billing-service", "/api/billing/audit", "audit"),
                        tuple("POST", "billing-service", "/api/invoices", "invoice"),
                        // one client per caller of the private helper
                        tuple("PATCH", "stock-service", "/api/stock/{?}/cancel", "cancel"),
                        tuple("PATCH", "stock-service", "/api/stock/restock", "restock"),
                        tuple("GET", "api.example.com", "/v1/rates", "rates"),
                        tuple("DELETE", "stock-service", "/api/stock/nowhere", "missing"));
        assertThat(model.httpClients())
                .filteredOn(c -> c.path().equals("/api/stock/restock"))
                .extracting(c -> c.declaration().method())
                .containsExactly("send");
    }

    @Test
    void clientsAreLinkedToMatchingEndpoints() {
        assertThat(model.channels())
                .allSatisfy(c -> assertThat(c.attributes()).containsEntry("clientKind", "REST_TEMPLATE"))
                .extracting(Channel::kind, c -> c.attributes().get("clientPath"), Channel::to)
                .containsExactlyInAnyOrder(
                        tuple("HTTP", "/api/stock/{?}", "ep:stock-service:GET /api/stock/{itemId}"),
                        tuple("HTTP", "/api/stock/reserve", "ep:stock-service:POST /api/stock/reserve"),
                        tuple("HTTP", "/api/stock/audit", "ep:stock-service:PUT /api/stock/audit"),
                        tuple("HTTP", "/api/billing/audit", "ep:billing-service:PUT /api/billing/audit"),
                        tuple("HTTP", "/api/invoices", "ep:billing-service:POST /api/invoices"),
                        tuple("HTTP", "/api/stock/{?}/cancel", "ep:stock-service:PATCH /api/stock/{itemId}/cancel"),
                        tuple("HTTP", "/api/stock/restock", "ep:stock-service:PATCH /api/stock/restock"));
    }

    @Test
    void unresolvableRequestsKeepAPreciseReason() {
        assertThat(model.unresolved())
                .extracting(Unresolved::kind, Unresolved::detail, u -> u.site().method())
                .containsExactlyInAnyOrder(
                        tuple("HTTP_CALL", "RestTemplate.getForObject: base URL not statically known in URL {?}/prices",
                                "price"),
                        tuple("HTTP_CALL", "RestTemplate.exchange: HTTP method not statically known in method",
                                "dynamicMethod"),
                        tuple("HTTP_CALL", "RestTemplate.exchange(RequestEntity): URL and method are carried by a "
                                + "RequestEntity, not modelled", "entity"),
                        tuple("HTTP_CALL", "WebClient call: target URL is built at runtime and not modelled",
                                "reactive"),
                        tuple("HTTP_CLIENT", "target service 'api.example.com' is not part of the analysed system",
                                "rates"),
                        tuple("HTTP_CLIENT", "no endpoint of stock-service matches DELETE /api/stock/nowhere",
                                "missing"));
    }

    @Test
    void urlTemplatesAreSplitIntoHostAndPath() {
        String hole = String.valueOf(ValueResolver.HOLE);
        assertThat(SpringWebExtractor.UrlTarget.parse("http://user@svc:8080/a/" + hole + "/b?x=" + hole))
                .isEqualTo(new SpringWebExtractor.UrlTarget("svc", "/a/{?}/b", null));
        assertThat(SpringWebExtractor.UrlTarget.parse("lb://svc"))
                .isEqualTo(new SpringWebExtractor.UrlTarget("svc", "/", null));
        assertThat(SpringWebExtractor.UrlTarget.parse("http://" + hole + "/a").problem())
                .isEqualTo("target host not statically known");
        assertThat(SpringWebExtractor.UrlTarget.parse("http://svc" + hole).problem())
                .isEqualTo("request path not statically known");
        assertThat(SpringWebExtractor.UrlTarget.parse("http://svc/" + hole).path()).isEqualTo("/{?}");
        assertThat(SpringWebExtractor.UrlTarget.parse("/relative/path").problem())
                .isEqualTo("not an absolute http URL");
        assertThat(SpringWebExtractor.UrlTarget.parse(hole + "/x").problem())
                .isEqualTo("base URL not statically known");
        assertThat(SpringWebExtractor.UrlTarget.parse("http://svc" + hole + "/x").problem())
                .isEqualTo("target host not statically known");
    }
}
