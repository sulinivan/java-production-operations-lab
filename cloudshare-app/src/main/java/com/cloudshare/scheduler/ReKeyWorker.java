package com.cloudshare.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("rekey-job")
@RequiredArgsConstructor
@Slf4j
public class ReKeyWorker implements CommandLineRunner {

    private final ReKeyService reKeyService;
    private final ConfigurableApplicationContext context;

    @Value("${rekey.old-version:#{null}}")
    private Integer oldVersionProp;

    @Value("${rekey.new-version:#{null}}")
    private Integer newVersionProp;

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting KEK rotation worker job...");

        Integer oldVer = oldVersionProp;
        if (oldVer == null) {
            String prop = System.getProperty("rekey.oldVersion");
            if (prop != null) {
                oldVer = Integer.parseInt(prop);
            }
        }

        Integer newVer = newVersionProp;
        if (newVer == null) {
            String prop = System.getProperty("rekey.newVersion");
            if (prop != null) {
                newVer = Integer.parseInt(prop);
            }
        }

        if (oldVer == null || newVer == null) {
            throw new IllegalArgumentException("Parameters 'rekey.oldVersion' and 'rekey.newVersion' must be provided (either via properties or system properties).");
        }

        try {
            reKeyService.performReKey(oldVer, newVer);
            log.info("Re-keying job completed successfully. Closing application context.");
        } finally {
            context.close();
        }
    }
}
