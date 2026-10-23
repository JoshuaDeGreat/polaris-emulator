package com.eu.habbo.networking.gameserver.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Presentation only: account tokens, expiry and delivery remain owned by their existing services. */
final class AccountEmailTemplate {
    private static final String TEMPLATE = load();

    private AccountEmailTemplate() {}

    static String passwordReset(String username, String url) {
        return render("Reset your password", "Let’s get you back above the clouds.",
                "Hi " + username + ",", "We received a request to reset your Heaven Hotel password. Choose a new one and make yourself at home again.",
                "Choose a new password", url, "This link expires in 20 minutes and can only be used once.",
                "Didn’t request a password reset? You can safely ignore this email. Your password will stay the same.");
    }

    static String emailVerification(String url) {
        return render("A little welcome to Heaven", "Your world above the clouds is waiting.",
                "Welcome to Heaven Hotel!", "Confirm your email to help keep your account safe. If you ever forget your password, we’ll know where to send your recovery link.",
                "Verify my email", url, "This link expires in 30 minutes and can only be used once.",
                "Didn’t request this email? You can safely ignore it.");
    }

    private static String render(String title, String preview, String greeting, String copy,
            String action, String url, String expiry, String notice) {
        // One replacement pass: user content cannot introduce another template placeholder.
        String[] values = {title, preview, greeting, copy, action, url, expiry, notice};
        String[] names = {"title", "preview", "greeting", "copy", "action", "url", "expiry", "notice"};
        java.util.Map<String, String> replacements = new java.util.HashMap<>();
        for (int i = 0; i < names.length; i++) replacements.put(names[i], escape(values[i]));
        var matcher = java.util.regex.Pattern.compile("\\{\\{([a-z]+)}}").matcher(TEMPLATE);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(replacements.getOrDefault(match.group(1), "")));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String load() {
        try (InputStream stream = AccountEmailTemplate.class.getResourceAsStream("/emails/heaven-account.html")) {
            if (stream == null) throw new IllegalStateException("Missing Heaven account email template");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
