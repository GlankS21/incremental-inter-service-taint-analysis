package ru.itmo.vkr.channels.model;

/**
 * A queue declared with {@code x-dead-letter-exchange}: messages that expire (TTL) or are rejected
 * there are re-published to {@code exchange} with {@code routingKey}, or with their original
 * routing key when {@code routingKey} is {@code null}.
 */
public record DeadLetter(String service, String queue, String exchange, String routingKey, CodeRef site) {
}
