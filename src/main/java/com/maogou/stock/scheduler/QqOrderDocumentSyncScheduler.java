package com.maogou.stock.scheduler;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.QqOrderDocumentSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "maogou.scheduler", name = "enabled", havingValue = "true")
public class QqOrderDocumentSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(QqOrderDocumentSyncScheduler.class);

    private final AppProperties properties;
    private final QqOrderDocumentSyncService syncService;

    public QqOrderDocumentSyncScheduler(AppProperties properties, QqOrderDocumentSyncService syncService) {
        this.properties = properties;
        this.syncService = syncService;
    }

    @Scheduled(cron = "${maogou.scheduler.qq-order-sync-cron}", zone = "Asia/Shanghai")
    public void syncOrders() {
        if (!properties.getScheduler().isQqOrderSyncEnabled()) {
            return;
        }
        try {
            QqOrderDocumentSyncService.SyncResult result = syncService.sync();
            log.info("QQ order document sync completed, title={}, lastSaveTimestamp={}, output={}",
                    result.title(), result.lastSaveTimestamp(), result.output());
        } catch (RuntimeException exception) {
            log.warn("QQ order document sync failed: {}", exception.getMessage(), exception);
        }
    }
}
