package com.eu.habbo.networking.gameserver.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TurnstileVerifierResponseTest {

    @Test
    void verificationBodyDoesNotSendTheAuthenticationProxyAsTheVisitorIp() {
        assertEquals("secret=sample-secret&response=sample-token",
                TurnstileVerifier.verificationBody("sample-secret", "sample-token"));
    }

    @Test
    void verificationBodyEncodesTokensWithoutAddingFormFields() {
        assertEquals("secret=a%26b%3Dc&response=x%2By%2Fz%3D",
                TurnstileVerifier.verificationBody("a&b=c", "x+y/z="));
    }

    @Test
    void successfulCloudflareVerificationIsAccepted() throws Exception {
        assertTrue(TurnstileVerifier.parseVerificationResponse(200, "{\"success\":true}", "100.64.0.1"));
    }

    @Test
    void nonSuccessHttpStatusIsReportedAsDependencyFailure() {
        assertThrows(IOException.class, () -> TurnstileVerifier.parseVerificationResponse(503, "{}", "127.0.0.1"));
    }

    @Test
    void validRejectionResponseRemainsACompletedDependencyCall() throws Exception {
        assertFalse(TurnstileVerifier.parseVerificationResponse(200, "{\"success\":false}", "127.0.0.1"));
    }
}
