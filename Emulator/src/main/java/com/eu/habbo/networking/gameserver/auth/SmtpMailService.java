package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.eu.habbo.resilience.RuntimeResilienceRuntime;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmtpMailService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailService.class);

    private SmtpMailService() {}

    public static boolean send(String toAddress, String subject, String body) {
        return RuntimeResilienceRuntime.executeExternal(
                "smtp", () -> sendDirect(toAddress, subject, body), () -> false);
    }

    private static boolean sendWithResend(String toAddress, String subject, String body) throws Exception {
        String key = Emulator.getConfig().getValue("resend.api_key", "").trim();
        String from = Emulator.getConfig().getValue("smtp.from_address", "accounts@heavenhotel.uk").trim();
        String fromName = Emulator.getConfig().getValue("smtp.from_name", "Heaven Hotel").trim();
        if (key.isEmpty() || toAddress == null || toAddress.isBlank() || from.isEmpty()) {
            LOGGER.error("Resend delivery unavailable: missing API key, recipient or sender");
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("from", fromName + " <" + from + ">");
        payload.addProperty("to", toAddress);
        payload.addProperty("subject", subject);
        payload.addProperty("text", body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Never log the key, the recipient address or the full response body.
            LOGGER.warn("Resend email delivery failed with HTTP {}", response.statusCode());
            return false;
        }
        return true;
    }

    private static boolean sendDirect(String toAddress, String subject, String body) throws Exception {
        String provider = Emulator.getConfig().getValue("smtp.provider", "own").toLowerCase();
        if ("resend".equals(provider)) return sendWithResend(toAddress, subject, body);
        String username = Emulator.getConfig().getValue("smtp.username", "");
        String password = Emulator.getConfig().getValue("smtp.password", "");
        String fromAddr = Emulator.getConfig().getValue("smtp.from_address", username);
        String fromName = Emulator.getConfig().getValue("smtp.from_name", "Habbo Hotel");

        if (toAddress == null || toAddress.isEmpty() || fromAddr == null || fromAddr.isEmpty()) {
            LOGGER.warn("SMTP send aborted — missing to/from address (to={}, from={})", toAddress, fromAddr);
            return false;
        }

        String host;
        int port;
        boolean useSsl;
        boolean useTls;

        switch (provider) {
            case "gmail" -> {
                host = "smtp.gmail.com";
                port = 465;
                useSsl = true;
                useTls = false;
            }
            case "sendgrid" -> {
                host = "smtp.sendgrid.net";
                port = 587;
                useSsl = false;
                useTls = true;
            }
            case "mailgun" -> {
                host = "smtp.mailgun.org";
                port = 587;
                useSsl = false;
                useTls = true;
            }
            default -> {
                host = Emulator.getConfig().getValue("smtp.host", "localhost");
                port = Emulator.getConfig().getInt("smtp.port", 587);
                useSsl = Emulator.getConfig().getBoolean("smtp.use_ssl", false);
                useTls = Emulator.getConfig().getBoolean("smtp.use_tls", true);
            }
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(!username.isEmpty()));
        if (useTls) props.put("mail.smtp.starttls.enable", "true");
        if (useSsl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        Session session = username.isEmpty()
                ? Session.getInstance(props)
                : Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddr, fromName, "UTF-8"));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(toAddress));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");

        Transport.send(message);
        return true;
    }
}
