package com.company.filepreview.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapts {@link UserAccount} to Spring Security's {@link UserDetails} while keeping
 * the account id, display name and roles accessible to controllers.
 */
public class AppUserDetails implements UserDetails {

    private final UserAccount account;

    public AppUserDetails(UserAccount account) {
        this.account = account;
    }

    public Long getId() {
        return account.getId();
    }

    public String getDisplayName() {
        return account.getDisplayName();
    }

    public Set<Role> getRoles() {
        return account.getRoles();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return account.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority(r.authority()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return account.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return account.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return account.isEnabled();
    }
}
