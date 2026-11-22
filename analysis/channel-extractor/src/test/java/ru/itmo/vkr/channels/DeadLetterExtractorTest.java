package ru.itmo.vkr.channels;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.itmo.vkr.channels.model.Channel;
import ru.itmo.vkr.channels.model.ChannelModel;
import ru.itmo.vkr.channels.model.DeadLetter;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Delayed messages published to TTL queues and consumed from the queues their dead-letter
 * exchanges route them to (fixture {@code fixtures/dead-letter}).
 */
class DeadLetterExtractorTest {

    static ChannelModel model;

    @BeforeAll
    static void extract() throws IOException, URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                DeadLetterExtractorTest.class.getResource("/fixtures/dead-letter")).toURI());
        model = new ChannelExtractor(root, null).extract();
    }

    @Test
    void deadLetterArgumentsAreReadFromBuildersAndArgumentMaps() {
        assertThat(model.deadLetters())
                .extracting(DeadLetter::queue, DeadLetter::exchange, DeadLetter::routingKey, d -> d.site().method())
                .containsExactlyInAnyOrder(
                        tuple("order.cancel.ttl", "order.dlx", "order.cancel", "cancelTtlQueue"),
                        tuple("order.expire.ttl", "order.dlx", null, "expireTtlQueue"),
                        tuple("pay.ttl", "", "pay.timeout", "payTtlQueue"));
    }

    @Test
    void messagesReachTheListenerOfTheDeadLetterQueue() {
        assertThat(model.channels())
                .allSatisfy(c -> assertThat(c.attributes()).containsEntry("viaDeadLetter", "true"))
                .extracting(c -> c.attributes().get("routingKey"), c -> c.attributes().get("deadLetterQueue"),
                        c -> c.attributes().get("deadLetterExchange"), c -> c.attributes().get("deadLetterRoutingKey"),
                        c -> c.attributes().get("queue"), DeadLetterExtractorTest::handler,
                        c -> c.attributes().get("payloadTypeMatch"))
                .containsExactlyInAnyOrder(
                        tuple("order.delay", "order.cancel.ttl", "order.dlx", "order.cancel", "order.cancel",
                                "onCancel", "EXACT"),
                        // no dead-letter routing key: the original one is kept
                        tuple("order.expire", "order.expire.ttl", "order.dlx", "order.expire", "order.expire.queue",
                                "onExpire", "EXACT"),
                        // default exchange: routed by queue name
                        tuple("pay.ttl", "pay.ttl", "", "pay.timeout", "pay.timeout", "onPayTimeout", "EXACT"));
        assertThat(model.unresolved()).isEmpty();
    }

    private static String handler(Channel channel) {
        return model.listeners().stream()
                .filter(l -> l.id().equals(channel.to()))
                .findFirst().orElseThrow()
                .handler().method();
    }
}
