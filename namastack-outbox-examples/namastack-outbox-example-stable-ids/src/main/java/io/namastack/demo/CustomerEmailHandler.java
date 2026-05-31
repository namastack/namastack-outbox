package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.outbox.annotation.OutboxHandlerId;
import io.namastack.outbox.handler.OutboxRecordMetadata;
import io.namastack.outbox.handler.OutboxTypedHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Interface-based handler. The {@code @OutboxHandlerId} annotation gives it a stable
 * logical {@code handler_id} so the persisted value is decoupled from this class's FQCN.
 *
 * <p>The framework automatically registers the FQCN form as an alias when
 * {@code namastack.outbox.handler.legacy-alias-mode=AUTO} (the default), so in-flight rows
 * written before this annotation was added continue to dispatch without any extra config.
 *
 * <p>The explicit {@code aliases} can be used to absorb rows written with an old <em>logical</em>
 * id if the handler id was renamed (e.g., migrating from {@code "email.sender"} to
 * {@code "handlers.customer.email"}).
 */
@Component
@OutboxHandlerId(
    value = "handlers.customer.email",
    aliases = {"email.sender"}
)
public class CustomerEmailHandler implements OutboxTypedHandler<CustomerRegisteredEvent> {

    private static final Logger logger = LoggerFactory.getLogger(CustomerEmailHandler.class);

    @Override
    public void handle(CustomerRegisteredEvent payload, OutboxRecordMetadata metadata) {
        logger.debug("[CustomerEmailHandler] Sending welcome email to: {}", payload.getEmail());
        ExternalMailService.send(payload.getEmail());
    }
}
