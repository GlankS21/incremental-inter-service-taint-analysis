package com.shop.common.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves dead-lettered messages back to the queue they came from, so that a failure lasting longer than
 * the listener retries (database restart, network partition) does not lose a saga step for good.
 * <p>
 * A message goes back to its own queue only (default exchange), never to the original exchange: the
 * other consumers of the event have already handled it. Consumers are idempotent, so a message that is
 * moved back after all is harmless. After {@code maxRedrives} returns a message stays in the DLQ and is
 * reported at ERROR level for manual investigation; it does not block the messages behind it.
 */
@Slf4j
public class DeadLetterRedriver implements SmartLifecycle {

    /** Number of times the message was moved back from the DLQ. */
    public static final String REDRIVE_COUNT_HEADER = "x-redrive-count";

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbitTemplate;
    private final List<String> queues;
    private final DeadLetterRedriveProperties properties;
    private ScheduledExecutorService scheduler;

    /**
     * @param queues every declared queue; only those dead-lettering to their own DLQ are redriven
     */
    public DeadLetterRedriver(RabbitTemplate rabbitTemplate, List<Queue> queues,
                              DeadLetterRedriveProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.queues = queues.stream()
                .filter(queue -> RabbitTopology.deadLetterQueueName(queue.getName())
                        .equals(queue.getArguments().get("x-dead-letter-routing-key")))
                .map(Queue::getName)
                .toList();
        this.properties = properties;
    }

    public List<String> queues() {
        return queues;
    }

    /**
     * Moves back the dead-lettered messages of every queue.
     *
     * @return number of messages moved back
     */
    public int redriveAll() {
        int redriven = 0;
        for (String queue : queues) {
            try {
                redriven += redrive(queue);
            } catch (RuntimeException ex) {
                log.warn("Could not redrive {}: {}", RabbitTopology.deadLetterQueueName(queue), ex.getMessage());
            }
        }
        return redriven;
    }

    /**
     * Moves back up to {@code batchSize} dead-lettered messages of {@code queue}. Messages that were
     * moved back too often are left in the DLQ, in their place.
     */
    public int redrive(String queue) {
        String deadLetterQueue = RabbitTopology.deadLetterQueueName(queue);
        Integer redriven = rabbitTemplate.execute(channel -> {
            List<Long> exhausted = new ArrayList<>();
            int moved = 0;
            try {
                for (int i = 0; i < properties.batchSize(); i++) {
                    GetResponse response = channel.basicGet(deadLetterQueue, false);
                    if (response == null) {
                        break;
                    }
                    long deliveryTag = response.getEnvelope().getDeliveryTag();
                    Map<String, Object> headers = response.getProps().getHeaders() == null
                            ? new HashMap<>() : new HashMap<>(response.getProps().getHeaders());
                    int count = redriveCount(headers);
                    if (count >= properties.maxRedrives()) {
                        exhausted.add(deliveryTag);
                        continue;
                    }
                    // The broker keeps the dead-letter history itself; a copy set by a publisher is not allowed
                    headers.keySet().removeIf(name -> name.startsWith("x-death") || name.startsWith("x-first-death")
                            || name.startsWith("x-last-death"));
                    headers.put(REDRIVE_COUNT_HEADER, count + 1);
                    AMQP.BasicProperties props = response.getProps().builder().headers(headers).build();
                    channel.basicPublish("", queue, props, response.getBody());
                    waitForConfirm(channel);
                    channel.basicAck(deliveryTag, false);
                    moved++;
                }
            } finally {
                for (long deliveryTag : exhausted) {
                    channel.basicNack(deliveryTag, false, true);
                }
            }
            if (!exhausted.isEmpty()) {
                log.error("{} message(s) in {} were redriven {} times and still fail: investigate them",
                        exhausted.size(), deadLetterQueue, properties.maxRedrives());
            }
            return moved;
        });
        if (redriven != null && redriven > 0) {
            log.warn("Moved {} dead-lettered message(s) from {} back to {}", redriven, deadLetterQueue, queue);
        }
        return redriven == null ? 0 : redriven;
    }

    @Override
    public void start() {
        if (queues.isEmpty()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dead-letter-redrive");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::redriveAll, properties.initialDelay().toMillis(),
                properties.interval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Dead-lettered messages of {} are moved back every {}", queues, properties.interval());
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private static int redriveCount(Map<String, Object> headers) {
        return headers.get(REDRIVE_COUNT_HEADER) instanceof Number number ? number.intValue() : 0;
    }

    /** Waits for the broker to take the message when the channel is in publisher-confirm mode. */
    private static void waitForConfirm(Channel channel) throws IOException {
        if (channel.getNextPublishSeqNo() == 0) {
            return;
        }
        try {
            channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the publisher confirm", ex);
        } catch (TimeoutException ex) {
            throw new IOException("No publisher confirm within " + CONFIRM_TIMEOUT_MS + " ms", ex);
        }
    }
}
