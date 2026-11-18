package ru.itmo.vkr.channels.model;

import java.util.List;

/**
 * A call target of an HTTP client together with the places that call it: a method of a
 * declarative client ({@code clientKind} {@code FEIGN}) or one programmatic
 * {@code RestTemplate} request whose URL was resolved statically ({@code REST_TEMPLATE}).
 * {@code url} is set when a Feign client targets a fixed address instead of a discovered service.
 */
public record HttpClientMethod(String id, String service, String targetService, String url, String httpMethod,
                               String path, List<Param> params, CodeRef declaration, List<CodeRef> callSites,
                               String clientKind) {

    public static final String FEIGN = "FEIGN";
    public static final String REST_TEMPLATE = "REST_TEMPLATE";
}
