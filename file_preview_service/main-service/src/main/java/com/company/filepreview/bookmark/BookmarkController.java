package com.company.filepreview.bookmark;

import com.company.filepreview.auth.AppUserDetails;
import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
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
import java.util.List;

/**
 * CRUD for personal and (admin-managed) global bookmarks.
 */
@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final BookmarkService service;

    public BookmarkController(BookmarkService service) {
        this.service = service;
    }

    @GetMapping
    public List<Bookmark> list() {
        return service.visibleTo(userId());
    }

    @PostMapping
    public Bookmark create(@Valid @RequestBody BookmarkRequest body) {
        return service.create(toEntity(body), userId(), isAdmin());
    }

    @PutMapping("/{id}")
    public Bookmark update(@PathVariable Long id, @Valid @RequestBody BookmarkRequest body) {
        return service.update(id, toEntity(body), userId(), isAdmin());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id, userId(), isAdmin());
    }

    private Bookmark toEntity(BookmarkRequest body) {
        Bookmark b = new Bookmark();
        b.setServerId(body.getServerId());
        b.setName(body.getName());
        b.setPath(body.getPath());
        if (body.getScopeType() != null) {
            b.setScopeType(Bookmark.ScopeType.valueOf(body.getScopeType()));
        }
        b.setScopeRef(body.getScopeRef());
        b.setSortOrder(body.getSortOrder());
        return b;
    }

    private String userId() {
        AppUserDetails u = CurrentUser.require();
        return String.valueOf(u.getId());
    }

    private boolean isAdmin() {
        return CurrentUser.roles().contains(Role.ADMIN);
    }

    @lombok.Data
    public static class BookmarkRequest {
        @NotBlank
        private String serverId;
        @NotBlank
        private String name;
        @NotBlank
        private String path;
        /** personal | team | global */
        private String scopeType;
        private String scopeRef;
        private int sortOrder;
    }
}
