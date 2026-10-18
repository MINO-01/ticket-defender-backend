package com.ticket.defender_core.config;

import com.ticket.defender_core.security.AdminMembershipLookup;
import com.ticket.defender_core.security.EventScopeLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/** No persistent membership or event directory exists yet; default authorization fails closed. */
@Configuration
public class AuthorizationFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(AdminMembershipLookup.class)
    AdminMembershipLookup noMemberships() {
        return (subject, tenantId) -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean(EventScopeLookup.class)
    EventScopeLookup noEvents() {
        return eventId -> Optional.empty();
    }
}
