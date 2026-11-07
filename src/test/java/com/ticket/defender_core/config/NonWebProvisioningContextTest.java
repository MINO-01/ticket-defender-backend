package com.ticket.defender_core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures the operator-only non-web bootstrap does not require Servlet security infrastructure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:a3-nonweb-provisioning;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ticket-defender.a3-synthetic-import-provisioning.enabled=false"
})
class NonWebProvisioningContextTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void nonWebContextStartsWithoutServletSecurityBeans() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        assertThat(context.containsBean("adminJwtDecoder")).isFalse();
        assertThat(context.containsBean("analysisJwtDecoder")).isFalse();
        assertThat(context.getBeansOfType(A3SyntheticReservationImportProvisioningRunner.class)).hasSize(1);
    }
}
