package io.namastack.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalBroker {

    private static final Logger logger = LoggerFactory.getLogger(ExternalBroker.class);

    public static void publish(Object payload, String key) {
        logger.debug("[ExternalBroker] Publishing {} with key: {}", payload.getClass().getSimpleName(), key);
    }
}
