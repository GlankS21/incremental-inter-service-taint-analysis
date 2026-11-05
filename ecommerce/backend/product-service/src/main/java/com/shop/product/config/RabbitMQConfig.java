package com.shop.product.config;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.messaging.RabbitTopology;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * product-service publishes {@code product.created} on the product exchange (inventory-service declares
 * its own queue), consumes {@code order.delivered} to learn who may review a product (and count its sales)
 * and {@code stock.level-changed} to know which products are in stock.
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange productExchange() {
        return RabbitTopology.topicExchange(MessagingConstants.PRODUCT_EXCHANGE);
    }

    @Bean
    public TopicExchange orderExchange() {
        return RabbitTopology.topicExchange(MessagingConstants.ORDER_EXCHANGE);
    }

    @Bean
    public TopicExchange inventoryExchange() {
        return RabbitTopology.topicExchange(MessagingConstants.INVENTORY_EXCHANGE);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return RabbitTopology.deadLetterExchange();
    }

    @Bean
    public Declarables orderDeliveredQueue() {
        return consumerQueue(MessagingConstants.PRODUCT_ORDER_DELIVERED_QUEUE,
                orderExchange(), MessagingConstants.ORDER_DELIVERED_KEY);
    }

    @Bean
    public Declarables stockLevelChangedQueue() {
        return consumerQueue(MessagingConstants.PRODUCT_STOCK_LEVEL_CHANGED_QUEUE,
                inventoryExchange(), MessagingConstants.STOCK_LEVEL_CHANGED_KEY);
    }

    private Declarables consumerQueue(String name, TopicExchange exchange, String routingKey) {
        Queue queue = RabbitTopology.queueWithDeadLetter(name);
        Queue deadLetterQueue = RabbitTopology.deadLetterQueue(name);
        return new Declarables(
                queue,
                deadLetterQueue,
                BindingBuilder.bind(queue).to(exchange).with(routingKey),
                RabbitTopology.deadLetterBinding(deadLetterQueue, deadLetterExchange()));
    }
}
