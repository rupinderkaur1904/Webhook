package dev.webhooks.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A controllable webhook receiver for demonstrations and tests. It is a separate application with
 * no dependency on the platform's code, so its scripted failures can never leak into production
 * delivery logic, and it verifies signatures the way an independent receiver would.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
