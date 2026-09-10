package dev.demo.selection.api;

import dev.demo.selection.Settings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    UserDetailsService demoUsers(Settings settings) {
        return username -> {
            if ("ops".equals(username)) {
                return User.withUsername("ops").password("{noop}" + settings.demoPassword()).roles("OPS").build();
            }
            long id;
            try {
                id = Long.parseLong(username);
            } catch (NumberFormatException e) {
                throw new UsernameNotFoundException(username);
            }
            if (id < settings.studentFrom() || id > settings.studentTo()) {
                throw new UsernameNotFoundException(username);
            }
            return User.withUsername(username).password("{noop}" + settings.demoPassword()).roles("STUDENT").build();
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPS")
                        .requestMatchers("/api/**").hasRole("STUDENT").anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
