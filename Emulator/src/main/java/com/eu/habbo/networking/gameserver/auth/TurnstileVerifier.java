package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import com.eu.habbo.resilience.RuntimeResilienceRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TurnstileVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnstileVerifier.class);
    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private TurnstileVerifier() {}

    public static boolean isEnabled() {
        return Emulator.getConfig() != null && Emulator.getConfig().getBoolean("login.turnstile.enabled", false);
    }

    public static boolean verify(String token, String remoteIp) {
        if (!isEnabled()) return true;

        if (token == null || token.isEmpty()) return false;

        String secret = Emulator.getConfig().getValue("login.turnstile.secretkey", "");
        if (secret.isEmpty()) {
            LOGGER.warn("login.turnstile.enabled=1 but login.turnstile.secretkey is empty — refusing the request");
            return false;
        }

        return RuntimeResilienceRuntime.executeExternal(
                "turnstile", () -> verifyRemote(verificationBody(secret, token), remoteIp), () -> false);
    }

    static String verificationBody(String secret, String token) {
        // remoteip is optional. The authentication peer may be a reverse proxy
        // (for example Railway's 100.64.0.0/10 network), not the visitor who
        // solved the challenge. Do not submit that address or trust arbitrary
        // forwarded headers just for Turnstile. Token validation remains mandatory.
        return "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static boolean verifyRemote(String form, String remoteIp) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VERIFY_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return parseVerificationResponse(response.statusCode(), response.body(), remoteIp);
    }

    static boolean parseVerificationResponse(int statusCode, String body, String remoteIp) throws IOException {
        if (statusCode != 200) {
            throw new IOException("Turnstile siteverify returned HTTP " + statusCode + " for ip=" + remoteIp);
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        boolean success = json.has("success") && json.get("success").getAsBoolean();
        if (!success) {
            LOGGER.info("Turnstile token rejected for ip={} body={}", remoteIp, body);
        }
        return success;
    }
}
