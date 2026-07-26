package com.company.filepreview.web;

import com.company.filepreview.auth.AppUserDetails;
import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
import com.company.filepreview.common.ApiException;
import lombok.Data;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session-based local authentication endpoints.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.getUsername(), body.getPassword()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            // Persist into the session so subsequent requests are authenticated.
            request.getSession(true);
            // 防 session fixation：认证成功后更换会话 ID，作废旧会话标识
            request.changeSessionId();
            contextRepository.saveContext(context, request, response);
            return me();
        } catch (BadCredentialsException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "BAD_CREDENTIALS", "Invalid username or password");
        } catch (org.springframework.security.authentication.AccountStatusException e) {
            // 禁用/锁定账号登录时映射为 401，而非 500
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "ACCOUNT_DISABLED", "账号已被禁用或锁定");
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public MeResponse me() {
        AppUserDetails user = CurrentUser.require();
        MeResponse resp = new MeResponse();
        resp.setId(String.valueOf(user.getId()));
        resp.setUsername(user.getUsername());
        resp.setDisplayName(user.getDisplayName());
        resp.setRoles(user.getRoles().stream().map(Role::name).collect(Collectors.toSet()));
        return resp;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class MeResponse {
        private String id;
        private String username;
        private String displayName;
        private Set<String> roles;
    }
}
