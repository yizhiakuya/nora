package com.nora.common.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.notification.service.NotificationStore;

/** 通知直接落库，独立事务失败只记录日志，不回滚已完成的业务。 */
@Service
public class NotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);
    private final NotificationStore store;
    private final TransactionTemplate tx;

    public NotificationPublisher(NotificationStore store, PlatformTransactionManager manager) {
        this.store = store;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(5);
    }

    public void publish(String event, String title, String detail) {
        if (title == null || title.isBlank()) return;
        try { tx.executeWithoutResult(status -> store.save(event, title, detail, "nora")); }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
            log.warn("notification not saved ({}): {}", event, e.getMessage());
        }
    }
}
