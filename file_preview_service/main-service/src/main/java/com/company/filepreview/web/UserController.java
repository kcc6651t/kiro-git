package com.company.filepreview.web;

import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
import com.company.filepreview.auth.UserAccount;
import com.company.filepreview.auth.UserAccountRepository;
import com.company.filepreview.common.ApiException;
import lombok.Data;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用内用户管理（仅 ADMIN，路径 /api/admin/**）。用户存于数据库，支持增删改与
 * 重置密码；不再依赖 YAML 初始化。多人使用互不影响：会话、个人书签、审计均按用户隔离。
 *
 * <p>安全护栏：不能删除/停用自己，不能移除最后一个启用中的管理员的管理员角色，
 * 以避免把自己锁在门外。</p>
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private final UserAccountRepository repository;
    private final PasswordEncoder encoder;

    public UserController(UserAccountRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @GetMapping
    public List<UserView> list() {
        return repository.findAll().stream().map(UserView::of).collect(Collectors.toList());
    }

    @PostMapping
    public UserView create(@Valid @RequestBody CreateRequest body) {
        String username = body.getUsername().trim();
        if (repository.findByUsername(username).isPresent()) {
            throw ApiException.badRequest("用户名已存在: " + username);
        }
        Set<Role> roles = parseRoles(body.getRoles());
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setDisplayName(body.getDisplayName() != null ? body.getDisplayName() : username);
        u.setPasswordHash(encoder.encode(body.getPassword()));
        u.setAuthProvider("local");
        u.setEnabled(body.isEnabled());
        u.setRoles(roles);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return UserView.of(repository.save(u));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest body) {
        UserAccount u = repository.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
        Set<Role> newRoles = parseRoles(body.getRoles());

        // 护栏：不得停用自己；不得移除最后一个启用管理员的管理员权限。
        // enabled 为 null 表示不修改启用状态，护栏按生效后的值计算。
        boolean effectiveEnabled = body.getEnabled() != null ? body.getEnabled() : u.isEnabled();
        boolean selfEdit = String.valueOf(id).equals(CurrentUser.context().getId());
        if (selfEdit && Boolean.FALSE.equals(body.getEnabled())) {
            throw ApiException.badRequest("不能停用当前登录的账号");
        }
        boolean wasEnabledAdmin = u.isEnabled() && u.getRoles().contains(Role.ADMIN);
        boolean willBeEnabledAdmin = effectiveEnabled && newRoles.contains(Role.ADMIN);
        if (wasEnabledAdmin && !willBeEnabledAdmin && countEnabledAdmins() <= 1) {
            throw ApiException.badRequest("必须至少保留一个启用中的管理员");
        }

        u.setDisplayName(body.getDisplayName() != null ? body.getDisplayName() : u.getUsername());
        u.setRoles(newRoles);
        if (body.getEnabled() != null) {
            u.setEnabled(body.getEnabled());
        }
        u.setUpdatedAt(Instant.now());
        return UserView.of(repository.save(u));
    }

    @PutMapping("/{id}/password")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest body) {
        UserAccount u = repository.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
        u.setPasswordHash(encoder.encode(body.getPassword()));
        u.setUpdatedAt(Instant.now());
        repository.save(u);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        UserAccount u = repository.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (String.valueOf(id).equals(CurrentUser.context().getId())) {
            throw ApiException.badRequest("不能删除当前登录的账号");
        }
        if (u.isEnabled() && u.getRoles().contains(Role.ADMIN) && countEnabledAdmins() <= 1) {
            throw ApiException.badRequest("必须至少保留一个启用中的管理员");
        }
        repository.delete(u);
    }

    private long countEnabledAdmins() {
        return repository.findAll().stream()
                .filter(a -> a.isEnabled() && a.getRoles().contains(Role.ADMIN))
                .count();
    }

    private Set<Role> parseRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw ApiException.badRequest("至少需要一个角色");
        }
        Set<Role> result = new HashSet<>();
        for (String r : roles) {
            try {
                result.add(Role.valueOf(r.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("无效角色: " + r);
            }
        }
        return result;
    }

    @Data
    public static class UserView {
        private String id;
        private String username;
        private String displayName;
        private Set<String> roles;
        private boolean enabled;
        private String authProvider;
        private String createdAt;

        static UserView of(UserAccount u) {
            UserView v = new UserView();
            v.setId(String.valueOf(u.getId()));
            v.setUsername(u.getUsername());
            v.setDisplayName(u.getDisplayName());
            v.setRoles(u.getRoles().stream().map(Role::name).collect(Collectors.toSet()));
            v.setEnabled(u.isEnabled());
            v.setAuthProvider(u.getAuthProvider());
            v.setCreatedAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            return v;
        }
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        private String username;
        private String displayName;
        @NotBlank
        private String password;
        private List<String> roles;
        private boolean enabled = true;
    }

    @Data
    public static class UpdateRequest {
        private String displayName;
        private List<String> roles;
        /** null 表示不修改启用状态（避免省略该字段时把已停用用户静默重新启用）。 */
        private Boolean enabled;
    }

    @Data
    public static class PasswordRequest {
        @NotBlank
        private String password;
    }
}
