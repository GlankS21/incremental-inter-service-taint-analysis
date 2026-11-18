package ru.itmo.vkr.channels;

import ru.itmo.vkr.channels.model.Channel;
import ru.itmo.vkr.channels.model.DeadLetter;
import ru.itmo.vkr.channels.model.Endpoint;
import ru.itmo.vkr.channels.model.HttpClientMethod;
import ru.itmo.vkr.channels.model.Listener;
import ru.itmo.vkr.channels.model.Publisher;
import ru.itmo.vkr.channels.model.QueueBinding;
import ru.itmo.vkr.channels.model.Unresolved;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Connects the two ends of every inter-service interaction: client methods to the endpoints they
 * call, and publishers to the listeners their messages are routed to, also through dead-letter
 * exchanges (a message published to a TTL queue reaches the listener of the queue its dead-letter
 * exchange routes it to).
 */
final class ChannelLinker {

    record Result(List<Channel> channels, List<Unresolved> unresolved) {
    }

    private static final int MAX_DEAD_LETTER_HOPS = 4;

    Result link(List<String> services, List<Endpoint> endpoints, List<HttpClientMethod> clients,
                List<Publisher> publishers, List<QueueBinding> bindings, List<DeadLetter> deadLetters,
                List<Listener> listeners) {
        List<Channel> channels = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        linkHttp(services, endpoints, clients, channels, unresolved);
        linkAmqp(publishers, bindings, deadLetters, listeners, channels, unresolved);
        return new Result(channels, unresolved);
    }

    private static void linkHttp(List<String> services, List<Endpoint> endpoints, List<HttpClientMethod> clients,
                                 List<Channel> channels, List<Unresolved> unresolved) {
        for (HttpClientMethod client : clients) {
            if (client.url() != null && !client.url().isBlank()) {
                unresolved.add(new Unresolved(client.service(), "HTTP_CLIENT",
                        "fixed url " + client.url() + ", treated as an external system", client.declaration()));
                continue;
            }
            if (!services.contains(client.targetService())) {
                unresolved.add(new Unresolved(client.service(), "HTTP_CLIENT",
                        "target service '" + client.targetService() + "' is not part of the analysed system",
                        client.declaration()));
                continue;
            }
            List<Endpoint> matches = endpoints.stream()
                    .filter(endpoint -> endpoint.service().equals(client.targetService()))
                    .filter(endpoint -> methodsMatch(endpoint.httpMethod(), client.httpMethod()))
                    .filter(endpoint -> Patterns.pathTemplatesMatch(endpoint.path(), client.path()))
                    .toList();
            int closest = matches.stream()
                    .mapToInt(endpoint -> Patterns.segmentKindMismatches(endpoint.path(), client.path()))
                    .min().orElse(0);
            for (Endpoint endpoint : matches) {
                if (Patterns.segmentKindMismatches(endpoint.path(), client.path()) == closest) {
                    Map<String, String> attributes = new LinkedHashMap<>();
                    attributes.put("httpMethod", client.httpMethod());
                    attributes.put("clientPath", client.path());
                    attributes.put("endpointPath", endpoint.path());
                    attributes.put("clientKind", client.clientKind());
                    channels.add(new Channel("HTTP", client.id(), endpoint.id(), attributes));
                }
            }
            if (matches.isEmpty()) {
                unresolved.add(new Unresolved(client.service(), "HTTP_CLIENT",
                        "no endpoint of " + client.targetService() + " matches " + client.httpMethod() + " "
                                + client.path(), client.declaration()));
            }
        }
    }

    private static boolean methodsMatch(String endpointMethod, String clientMethod) {
        return endpointMethod.equals("ANY") || clientMethod.equals("ANY") || endpointMethod.equals(clientMethod);
    }

    private static void linkAmqp(List<Publisher> publishers, List<QueueBinding> bindings, List<DeadLetter> deadLetters,
                                 List<Listener> listeners, List<Channel> channels, List<Unresolved> unresolved) {
        for (Publisher publisher : publishers) {
            boolean linked = false;
            Map<String, String> queues = route(publisher.exchange(), publisher.routingKey(), bindings);
            for (Map.Entry<String, String> queue : queues.entrySet()) {
                linked |= deliver(publisher, queue.getKey(), queue.getValue(), Map.of(), listeners, channels);
            }
            // messages expiring or rejected in a queue with a dead-letter exchange are re-published there
            Deque<DeadLetterHop> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>(queues.keySet());
            queues.keySet().forEach(q -> pending.add(new DeadLetterHop(q, publisher.routingKey(), List.of())));
            while (!pending.isEmpty()) {
                DeadLetterHop hop = pending.poll();
                for (DeadLetter deadLetter : deadLetters) {
                    if (!deadLetter.queue().equals(hop.queue())) {
                        continue;
                    }
                    String key = deadLetter.routingKey() != null ? deadLetter.routingKey() : hop.routingKey();
                    List<String> path = new ArrayList<>(hop.path());
                    path.add(hop.queue());
                    Map<String, String> via = new LinkedHashMap<>();
                    via.put("viaDeadLetter", "true");
                    via.put("deadLetterQueue", String.join(" -> ", path));
                    via.put("deadLetterExchange", deadLetter.exchange());
                    via.put("deadLetterRoutingKey", key);
                    for (Map.Entry<String, String> queue : route(deadLetter.exchange(), key, bindings).entrySet()) {
                        linked |= deliver(publisher, queue.getKey(), queue.getValue(), via, listeners, channels);
                        if (visited.add(queue.getKey()) && path.size() < MAX_DEAD_LETTER_HOPS) {
                            pending.add(new DeadLetterHop(queue.getKey(), key, path));
                        }
                    }
                }
            }
            if (!linked) {
                unresolved.add(new Unresolved(publisher.service(), "PUBLISHER",
                        "no listener receives " + publisher.payloadType() + " sent to " + publisher.exchange()
                                + " with routing key " + publisher.routingKey(), publisher.origin()));
            }
        }
    }

    private record DeadLetterHop(String queue, String routingKey, List<String> path) {
    }

    /**
     * Queues an exchange routes a message with this key to, each with the binding key that matched.
     */
    private static Map<String, String> route(String exchange, String routingKey, List<QueueBinding> bindings) {
        Map<String, String> queues = new LinkedHashMap<>();
        if (exchange.equals(AmqpExtractor.DEFAULT_EXCHANGE)) {
            queues.put(routingKey, "(default exchange)");
        } else {
            for (QueueBinding binding : bindings) {
                if (binding.exchange().equals(exchange) && Patterns.topicMatches(binding.routingKey(), routingKey)) {
                    queues.putIfAbsent(binding.queue(), binding.routingKey());
                }
            }
        }
        return queues;
    }

    private static boolean deliver(Publisher publisher, String queue, String bindingKey, Map<String, String> via,
                                   List<Listener> listeners, List<Channel> channels) {
        boolean linked = false;
        for (Listener listener : listeners) {
            if (!listener.queue().equals(queue)) {
                continue;
            }
            String typeMatch = typeMatch(publisher.payloadType(), listener.payloadType());
            // a @RabbitHandler is only invoked for its own payload type
            if (listener.dispatch().equals("TYPE") && typeMatch.equals("DIFFERENT")) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("exchange", publisher.exchange());
            attributes.put("routingKey", publisher.routingKey());
            attributes.put("bindingKey", bindingKey);
            attributes.put("queue", queue);
            attributes.put("payloadType", publisher.payloadType());
            attributes.put("payloadTypeMatch", typeMatch);
            attributes.putAll(via);
            channels.add(new Channel("AMQP", publisher.id(), listener.id(), attributes));
            linked = true;
        }
        return linked;
    }

    private static String typeMatch(String sent, String received) {
        if (sent == null || received == null) {
            return "UNKNOWN";
        }
        return Objects.equals(sent, received) ? "EXACT" : "DIFFERENT";
    }
}
