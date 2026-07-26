package com.company.filepreview.bookmark;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    /**
     * Bookmarks visible to a user: their own personal bookmarks plus all global
     * bookmarks, ordered for display. Enum values are passed as parameters to keep
     * the query portable.
     */
    @Query("select b from Bookmark b where " +
            "b.scopeType = :global " +
            "or (b.scopeType = :personal and b.createdBy = :userId) " +
            "order by b.scopeType asc, b.sortOrder asc, b.name asc")
    List<Bookmark> visibleTo(@Param("userId") String userId,
                             @Param("global") Bookmark.ScopeType global,
                             @Param("personal") Bookmark.ScopeType personal);
}
