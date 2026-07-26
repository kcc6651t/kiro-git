package com.company.filepreview.bookmark;

import com.company.filepreview.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Bookmark business rules. Personal bookmarks belong to their creator; global
 * bookmarks are managed by admins only. Users may only mutate bookmarks they own
 * (or, for admins, global ones).
 */
@Service
public class BookmarkService {

    private final BookmarkRepository repository;

    public BookmarkService(BookmarkRepository repository) {
        this.repository = repository;
    }

    public List<Bookmark> visibleTo(String userId) {
        return repository.visibleTo(userId, Bookmark.ScopeType.global, Bookmark.ScopeType.personal);
    }

    public Bookmark create(Bookmark bookmark, String userId, boolean isAdmin) {
        if (bookmark.getScopeType() == null) {
            bookmark.setScopeType(Bookmark.ScopeType.personal);
        }
        if (bookmark.getScopeType() != Bookmark.ScopeType.personal && !isAdmin) {
            throw ApiException.forbidden("Only admins can manage non-personal bookmarks");
        }
        bookmark.setId(null);
        bookmark.setCreatedBy(userId);
        bookmark.setCreatedAt(Instant.now());
        bookmark.setUpdatedAt(Instant.now());
        return repository.save(bookmark);
    }

    public Bookmark update(Long id, Bookmark changes, String userId, boolean isAdmin) {
        Bookmark existing = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bookmark not found"));
        requireMutable(existing, userId, isAdmin);
        existing.setName(changes.getName());
        existing.setPath(changes.getPath());
        existing.setServerId(changes.getServerId());
        existing.setSortOrder(changes.getSortOrder());
        if (changes.getScopeType() != null) {
            if (changes.getScopeType() != Bookmark.ScopeType.personal && !isAdmin) {
                throw ApiException.forbidden("Only admins can set non-personal scope");
            }
            existing.setScopeType(changes.getScopeType());
            existing.setScopeRef(changes.getScopeRef());
        }
        existing.setUpdatedAt(Instant.now());
        return repository.save(existing);
    }

    public void delete(Long id, String userId, boolean isAdmin) {
        Bookmark existing = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bookmark not found"));
        requireMutable(existing, userId, isAdmin);
        repository.delete(existing);
    }

    private void requireMutable(Bookmark bookmark, String userId, boolean isAdmin) {
        if (bookmark.getScopeType() == Bookmark.ScopeType.personal) {
            if (!userId.equals(bookmark.getCreatedBy())) {
                throw ApiException.forbidden("Cannot modify another user's bookmark");
            }
        } else if (!isAdmin) {
            throw ApiException.forbidden("Only admins can modify shared bookmarks");
        }
    }
}
