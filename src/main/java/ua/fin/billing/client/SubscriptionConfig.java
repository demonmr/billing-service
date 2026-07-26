package ua.fin.billing.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ua.fin.billing.subscription.client.ApiClient;
import ua.fin.billing.subscription.client.api.SubscriptionsApi;


@Configuration
public class SubscriptionConfig {

    @Value( "${gateway.url}")
    private String gatewayUrl;

    @Bean("subscription-api-client")
    public ApiClient getApiClientForSubscription() {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(gatewayUrl);
        return apiClient;
    }

    @Bean
    public SubscriptionsApi getSubscriptionApi(@Qualifier("subscription-api-client") ApiClient apiClient) {
        return new SubscriptionsApi(apiClient);
    }

}
