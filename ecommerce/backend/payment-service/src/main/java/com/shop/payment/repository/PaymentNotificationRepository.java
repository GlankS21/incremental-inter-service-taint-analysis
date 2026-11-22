package com.shop.payment.repository;

import com.shop.payment.entity.NotificationStatus;
import com.shop.payment.entity.PaymentNotification;
import com.shop.payment.entity.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, UUID> {

    boolean existsByProviderAndEventAndObjectIdAndStatus(PaymentProvider provider, String event, String objectId,
                                                        NotificationStatus status);
}
