package io.namastack.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalMailService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalMailService.class);

    public static void send(String email) {
        logger.debug("[ExternalMailService] Sending email to: {}", email);
    }
}
