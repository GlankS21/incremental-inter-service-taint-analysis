package com.shop.product.service;

/**
 * One review reminder per delivered order: {@code app.reviews.reminder.delay-days} after delivery, the
 * products of the order the customer has not reviewed (and that are still in the catalogue) are sent to
 * notification-service as {@code review.reminder-due}. An order is handled once, whether a reminder was
 * due or not.
 */
public interface ReviewReminderService {

    /**
     * @return number of reminders published by this run
     */
    int publishDueReminders();
}
