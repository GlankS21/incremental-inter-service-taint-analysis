package com.shop.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.logging.CorrelationIdPublishPostProcessor;
import com.shop.common.logging.CorrelationIdReceivePostProcessor;
import com.shop.common.messaging.DeadLetterRedriveProperties;
import com.shop.common.messaging.DeadLetterRedriver;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * JSON message conversion and correlation id propagation for RabbitMQ producers and consumers.
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, RabbitAutoConfiguration.class})
@ConditionalOnClass(RabbitTemplate.class)
@EnableConfigurationProperties(DeadLetterRedriveProperties.class)
public class CommonAmqpAutoConfiguration {

    private static final String EVENT_PACKAGE = "com.shop.common.event";

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jacksonMessageConverter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(
                () -> new ObjectMapper().findAndRegisterModules());
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages(EVENT_PACKAGE);
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplateCustomizer correlationIdRabbitTemplateCustomizer() {
        return template -> template.addBeforePublishPostProcessors(new CorrelationIdPublishPostProcessor());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContainerCustomizer<SimpleMessageListenerContainer> correlationIdContainerCustomizer() {
        return container -> container.addAfterReceivePostProcessors(new CorrelationIdReceivePostProcessor());
    }

    /**
     * Moves dead-lettered saga messages back to their queue (see {@link DeadLetterRedriver}).
     */
    @Bean
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.messaging.dead-letter-redrive", name = "enabled", matchIfMissing = true)
    public DeadLetterRedriver deadLetterRedriver(RabbitTemplate rabbitTemplate, ObjectProvider<Queue> queues,
                                                 DeadLetterRedriveProperties properties) {
        return new DeadLetterRedriver(rabbitTemplate, queues.orderedStream().toList(), properties);
    }
}
