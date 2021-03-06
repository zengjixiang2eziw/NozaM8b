package com.medusa.gruul.afs.mp;

import cn.hutool.json.JSONUtil;
import com.medusa.gruul.account.api.constant.AccountExchangeConstant;
import com.medusa.gruul.afs.api.constant.AfsConstant;
import com.medusa.gruul.afs.api.constant.AfsQueueEnum;
import com.medusa.gruul.afs.api.constant.AfsQueueNameConstant;
import com.medusa.gruul.order.api.constant.OrderConstant;
import com.medusa.gruul.order.api.constant.OrderQueueEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * @author alan
 * @Description: RabbitConfig.java
 * @date 2019/10/6 14:01
 */
@Slf4j
@Configuration
public class RabbitConfig implements RabbitListenerConfigurer {
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        registrar.setMessageHandlerMethodFactory(messageHandlerMethodFactory());
    }

    @Bean
    MessageHandlerMethodFactory messageHandlerMethodFactory() {
        DefaultMessageHandlerMethodFactory messageHandlerMethodFactory = new DefaultMessageHandlerMethodFactory();
        messageHandlerMethodFactory.setMessageConverter(consumerJackson2MessageConverter());
        return messageHandlerMethodFactory;
    }

    @Bean
    public MappingJackson2MessageConverter consumerJackson2MessageConverter() {
        return new MappingJackson2MessageConverter();
    }

    @Bean
    public AmqpTemplate amqpTemplate() {
        // ??????jackson ???????????????
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        rabbitTemplate.setEncoding("UTF-8");
        // ???????????????????????????????????????yml???????????? publisher-returns: true
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            log.info("?????????{} ????????????, ????????????{} ?????????{} ?????????: {}  ?????????: {}", JSONUtil.parse(message), replyCode, replyText,
                    exchange, routingKey);
        });
        // ???????????????yml???????????? publisher-confirms: true
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                assert correlationData != null;
                log.info("???????????????exchange??????,id: {}", correlationData.getId());
            } else {
                log.info("???????????????exchange??????,??????: {}", cause);
            }
        });
        return rabbitTemplate;
    }

    /**
     * ?????????????????????????????????????????????
     */
    @Bean
    CustomExchange afsDelayDirect() {
        Map<String, Object> args = new HashMap<>(1);
        args.put("x-delayed-type", "direct");
        return new CustomExchange(AfsConstant.DELAY_EXCHANGE_NAME, "x-delayed-message", true, false, args);
    }

    /**
     * ???????????????????????????????????????????????????
     */
    @Bean
    DirectExchange orderDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(OrderConstant.EXCHANGE_NAME)
                .durable(true)
                .build();
    }

    /**
     * ???????????????????????????????????????????????????
     */
    @Bean
    DirectExchange deliverDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(OrderConstant.DELIVER_EXCHANGE_NAME)
                .durable(true)
                .build();
    }



    @Bean
    DirectExchange accountDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(AccountExchangeConstant.ACCOUNT_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue orderReceipt() {
        return new Queue(AfsQueueNameConstant.ORDER_RECEIPT, true);
    }

    @Bean
    public Queue orderShipped() {
        return new Queue(AfsQueueNameConstant.ORDER_SHIPPED, true);
    }

    @Bean
    public Queue orderCompleted() {
        return new Queue(AfsQueueNameConstant.ORDER_COMPLETED, true);
    }

    @Bean
    public Queue deliverReceipt() {
        return new Queue(AfsQueueNameConstant.DELIVER_RECEIPT, true);
    }

    @Bean
    public Queue afsMerchantAutoConfirm() {
        return new Queue(AfsQueueNameConstant.AFS_MERCHANT_AUTO_CONFIRM, true);
    }

    @Bean
    public Queue afsUserReturnOvertime() {
        return new Queue(AfsQueueNameConstant.AFS_USER_RETURN_OVERTIME, true);
    }

    @Bean
    public Queue afsDataInitQueue() {
        return new Queue(AfsQueueNameConstant.DATA_INIT, true);
    }


    /**
     * ???????????????????????????????????????????????????
     */
    @Bean
    Binding merchantAutoCancelBinding(CustomExchange afsDelayDirect, Queue afsMerchantAutoConfirm) {
        return BindingBuilder
                .bind(afsMerchantAutoConfirm)
                .to(afsDelayDirect)
                .with(AfsQueueEnum.QUEUE_AFS_MERCHANT_AUTO_CONFIRM.getRouteKey()).noargs();
    }


    /**
     * ????????????????????????????????????????????????????????????
     */
    @Bean
    Binding userReturnOvertimeBinding(CustomExchange afsDelayDirect, Queue afsUserReturnOvertime) {
        return BindingBuilder
                .bind(afsUserReturnOvertime)
                .to(afsDelayDirect)
                .with(AfsQueueEnum.QUEUE_AFS_USER_RETURN_OVERTIME.getRouteKey()).noargs();
    }


    /**
     * ???????????????????????????????????????
     */
    @Bean
    Binding deliverReceiptBinding(DirectExchange deliverDirect, Queue deliverReceipt) {
        return BindingBuilder
                .bind(deliverReceipt)
                .to(deliverDirect)
                .with(OrderQueueEnum.QUEUE_DELIVER_RECEIPT.getRouteKey());
    }

    /**
     * ???????????????????????????????????????
     */
    @Bean
    Binding orderReceiptBinding(DirectExchange orderDirect, Queue orderReceipt) {
        return BindingBuilder
                .bind(orderReceipt)
                .to(orderDirect)
                .with(OrderQueueEnum.QUEUE_ORDER_RECEIPT.getRouteKey());
    }

    /**
     * ???????????????????????????????????????
     */
    @Bean
    Binding orderShippedBinding(DirectExchange orderDirect, Queue orderShipped) {
        return BindingBuilder
                .bind(orderShipped)
                .to(orderDirect)
                .with(OrderQueueEnum.QUEUE_ORDER_SHIPPED.getRouteKey());
    }

    /**
     * ???????????????????????????????????????
     */
    @Bean
    Binding orderCompletedBinding(DirectExchange orderDirect, Queue orderCompleted) {
        return BindingBuilder
                .bind(orderCompleted)
                .to(orderDirect)
                .with(OrderQueueEnum.QUEUE_ORDER_COMPLETED.getRouteKey());
    }


    /**
     * ??????????????????????????????????????????
     */
    @Bean
    Binding orderDataInitBinding(DirectExchange accountDirect, Queue afsDataInitQueue) {
        return BindingBuilder
                .bind(afsDataInitQueue)
                .to(accountDirect)
                .with(AfsQueueEnum.QUEUE_DATA_INIT.getRouteKey());
    }

}
