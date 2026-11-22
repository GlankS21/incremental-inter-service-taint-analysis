package demo.orders;

import org.springframework.amqp.core.*;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitConfig {
    static final String DELAY_EXCHANGE = "order.delay";
    static final String DLX = "order.dlx";
    static final String CANCEL_TTL_QUEUE = "order.cancel.ttl";
    static final String CANCEL_QUEUE = "order.cancel";

    // ---- 1: withArgument, explicit dead-letter routing key
    @Bean
    DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    Queue cancelTtlQueue() {
        return QueueBuilder.durable(CANCEL_TTL_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "order.cancel")
                .build();
    }

    @Bean
    Queue cancelQueue() {
        return new Queue(CANCEL_QUEUE);
    }

    @Bean
    Binding cancelTtlBinding(Queue cancelTtlQueue, DirectExchange delayExchange) {
        return BindingBuilder.bind(cancelTtlQueue).to(delayExchange).with("order.delay");
    }

    @Bean
    Binding cancelBinding(Queue cancelQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(cancelQueue).to(deadLetterExchange).with("order.cancel");
    }

    // ---- 2: deadLetterExchange() without routing key: the original key is kept
    @Bean
    Queue expireTtlQueue() {
        return QueueBuilder.durable("order.expire.ttl").deadLetterExchange(DLX).ttl(60000).build();
    }

    @Bean
    Binding expireTtlBinding() {
        return BindingBuilder.bind(expireTtlQueue()).to(delayExchange()).with("order.expire");
    }

    @Bean
    Binding expireBinding() {
        return BindingBuilder.bind(new Queue("order.expire.queue")).to(deadLetterExchange()).with("order.expire");
    }

    // ---- 3: arguments map, dead-lettered to the default exchange
    @Bean
    Queue payTtlQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 30000);
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", "pay.timeout");
        return new Queue("pay.ttl", true, false, false, args);
    }
}
