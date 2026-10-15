package dev.webhooks.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    // Test vector published by the Standard Webhooks project.
    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final String TIMESTAMP = "1614265330";
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";
    private static final Instant AT = Instant.ofEpochSecond(1614265330);

    @Test
    void acceptsPublishedTestVector() {
        assertThat(SignatureVerifier.verify(SECRET, ID, TIMESTAMP, SIGNATURE, BODY, AT, Duration.ofMinutes(5)))
                .isEqualTo(SignatureVerifier.Result.VALID);
    }

    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        String header = "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= " + SIGNATURE;
        assertThat(SignatureVerifier.verify(SECRET, ID, TIMESTAMP, header, BODY, AT, Duration.ofMinutes(5)))
                .isEqualTo(SignatureVerifier.Result.VALID);
    }

    @Test
    void rejectsTamperedBody() {
        byte[] tampered = "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8);
        assertThat(SignatureVerifier.verify(SECRET, ID, TIMESTAMP, SIGNATURE, tampered, AT, Duration.ofMinutes(5)))
                .isEqualTo(SignatureVerifier.Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsTimestampOutsideTolerance() {
        assertThat(SignatureVerifier.verify(SECRET, ID, TIMESTAMP, SIGNATURE, BODY, AT.plusSeconds(301),
                Duration.ofMinutes(5))).isEqualTo(SignatureVerifier.Result.TIMESTAMP_OUT_OF_TOLERANCE);
    }

    @Test
    void rejectsMissingHeaders() {
        assertThat(SignatureVerifier.verify(SECRET, ID, null, SIGNATURE, BODY, AT, Duration.ofMinutes(5)))
                .isEqualTo(SignatureVerifier.Result.MISSING_HEADERS);
    }
}
