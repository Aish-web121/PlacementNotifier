package PLACEMENT.COM.PLACEMENTNOTIFIER.Config;

import com.google.api.client.googleapis.apache.v2.GoogleApacheHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

@Configuration
@Slf4j
public class AppConfig {

    @Value("${gmail.client-id}")
    private String clientId;

    @Value("${gmail.client-secret}")
    private String clientSecret;

    @Value("${gmail.refresh-token}")
    private String refreshToken;

    @Value("${gmail.application-name}")
    private String applicationName;

    @Bean
    public Gmail gmailClient() throws GeneralSecurityException, IOException {
        log.info("Initializing Gmail API client...");

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();

        HttpTransport httpTransport = GoogleApacheHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        Gmail gmail = new Gmail.Builder(httpTransport, jsonFactory,
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(applicationName)
                .build();

        // ── Diagnostic: log which account this token belongs to ──
        try {
            com.google.api.services.gmail.model.Profile profile =
                    gmail.users().getProfile("me").execute();
            log.info("✅ Gmail authenticated as: {}", profile.getEmailAddress());
        } catch (Exception e) {
            log.error("❌ Could not verify Gmail identity: {}", e.getMessage());
        }

        return gmail;
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024))
                .build();
    }
}
