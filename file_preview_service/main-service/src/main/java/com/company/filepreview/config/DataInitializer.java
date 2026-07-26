package com.company.filepreview.config;

import com.company.filepreview.auth.Role;
import com.company.filepreview.auth.UserAccount;
import com.company.filepreview.auth.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Set;

/**
 * Seeds default local users on first startup when the user table is empty.
 * Credentials are configurable via {@code filepreview.bootstrap.*}. Change the
 * default admin password immediately in any real deployment.
 */
@Configuration
@EnableConfigurationProperties(DataInitializer.BootstrapProperties.class)
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner seedUsers(UserAccountRepository repository,
                                       PasswordEncoder encoder,
                                       BootstrapProperties props) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            UserAccount admin = new UserAccount();
            admin.setUsername(props.getAdminUsername());
            admin.setDisplayName("Administrator");
            admin.setPasswordHash(encoder.encode(props.getAdminPassword()));
            Set<Role> adminRoles = new HashSet<>();
            adminRoles.add(Role.ADMIN);
            adminRoles.add(Role.OPERATOR);
            admin.setRoles(adminRoles);
            repository.save(admin);

            UserAccount operator = new UserAccount();
            operator.setUsername("operator");
            operator.setDisplayName("Operator");
            operator.setPasswordHash(encoder.encode(props.getOperatorPassword()));
            Set<Role> opRoles = new HashSet<>();
            opRoles.add(Role.OPERATOR);
            operator.setRoles(opRoles);
            repository.save(operator);

            log.info("Seeded default users '{}' (ADMIN,OPERATOR) and 'operator' (OPERATOR). " +
                    "Change these passwords before production use.", props.getAdminUsername());
        };
    }

    @ConfigurationProperties(prefix = "filepreview.bootstrap")
    public static class BootstrapProperties {
        private String adminUsername = "admin";
        private String adminPassword = "admin123";
        private String operatorPassword = "operator123";

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getOperatorPassword() {
            return operatorPassword;
        }

        public void setOperatorPassword(String operatorPassword) {
            this.operatorPassword = operatorPassword;
        }
    }
}
