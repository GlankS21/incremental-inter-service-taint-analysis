package demo.orders;

import org.springframework.amqp.core.AmqpTemplate;

@SpringBootApplication
public class OrderMessaging {
    private final AmqpTemplate amqpTemplate;

    public void delayCancel(Long orderId, long delay) {
        amqpTemplate.convertAndSend("order.delay", "order.delay", orderId, message -> {
            message.getMessageProperties().setExpiration(String.valueOf(delay));
            return message;
        });
    }

    public void expire(Expire event) {
        amqpTemplate.convertAndSend("order.delay", "order.expire", event);
    }

    public void payTimeout(String paymentId) {
        amqpTemplate.convertAndSend("pay.ttl", paymentId);
    }

    @RabbitListener(queues = "order.cancel")
    public void onCancel(Long orderId) { }

    @RabbitListener(queues = "order.expire.queue")
    public void onExpire(Expire event) { }

    @RabbitListener(queues = "pay.timeout")
    public void onPayTimeout(String paymentId) { }
}
