package com.company.filepreview.permission;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathAuthorizerTest {

    private final PathAuthorizer authorizer = new PathAuthorizer();
    private final List<String> allowedRoots = Arrays.asList("/opt/apps", "/data/logs");
    private final List<String> deniedPaths = Arrays.asList(
            "/etc/shadow", "/root", "/home/*/.ssh/**", "/**/id_rsa", "/**/*.key", "/proc");

    @Test
    void acceptsPathWithinAllowedRoot() {
        String result = authorizer.authorize("/data/logs/app.log", allowedRoots, deniedPaths);
        assertEquals("/data/logs/app.log", result);
    }

    @Test
    void normalizesRedundantSegments() {
        String result = authorizer.authorize("/data/logs/./sub/../app.log", allowedRoots, deniedPaths);
        assertEquals("/data/logs/app.log", result);
    }

    @Test
    void rejectsTraversalOutsideRoot() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/../../etc/shadow", allowedRoots, deniedPaths));
        // Either traversal collapses to /etc/shadow (outside roots / denied)
        assertEquals(true, Arrays.asList("OUTSIDE_ALLOWED_ROOTS", "DENIED_PATH").contains(ex.getCode()));
    }

    @Test
    void rejectsRelativePath() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("data/logs/app.log", allowedRoots, deniedPaths));
        assertEquals("ILLEGAL_PATH", ex.getCode());
    }

    @Test
    void rejectsEmptyPath() {
        assertThrows(PathAccessException.class,
                () -> authorizer.authorize("  ", allowedRoots, deniedPaths));
    }

    @Test
    void rejectsControlCharacters() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/\u0000evil", allowedRoots, deniedPaths));
        assertEquals("ILLEGAL_PATH", ex.getCode());
    }

    @Test
    void rejectsPathOutsideAllowedRoots() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/var/www/index.html", allowedRoots, deniedPaths));
        assertEquals("OUTSIDE_ALLOWED_ROOTS", ex.getCode());
    }

    @Test
    void rejectsExactDeniedPath() {
        // Make /etc allowed to prove the deny rule (not the root check) triggers.
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/etc/shadow", Collections.singletonList("/etc"), deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
    }

    @Test
    void rejectsDoubleStarGlob() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/keys/server.key", allowedRoots, deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
    }

    @Test
    void rejectsIdRsaAnywhere() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/opt/apps/secret/id_rsa", allowedRoots, deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
    }

    @Test
    void deniesSubtreeOfDeniedDirectory() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/proc/1/status", Collections.singletonList("/proc"), deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
    }

    @Test
    void failsWhenNoAllowedRoots() {
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/app.log", Collections.emptyList(), deniedPaths));
        assertEquals("NO_ALLOWED_ROOTS", ex.getCode());
    }

    @Test
    void rejectsDeniedDirectoryWithTrailingSlash() {
        // 尾斜杠的 deny 规则应同时拒绝目录本身与其子树
        List<String> roots = Collections.singletonList("/");
        List<String> denied = Collections.singletonList("/root/");
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/root", roots, denied));
        assertEquals("DENIED_PATH", ex.getCode());
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/root/secret/file", roots, denied));
        assertEquals("DENIED_PATH", ex.getCode());
    }

    @Test
    void nameGlobMatchesOnlyWholeSegments() {
        // 名称 glob 只匹配完整路径段：拒 "log"，不误伤以 log 结尾的 "catalog"
        List<String> denied = Collections.singletonList("log");
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/log", allowedRoots, denied));
        assertEquals("DENIED_PATH", ex.getCode());
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/sub/log", allowedRoots, denied));
        assertEquals("DENIED_PATH", ex.getCode());
        // 根目录下的同名文件同样被拒
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/log", Collections.singletonList("/"), denied));
        assertEquals("DENIED_PATH", ex.getCode());
        // 名为 log 的目录其子树也被拒
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/log/inside.txt", allowedRoots, denied));
        assertEquals("DENIED_PATH", ex.getCode());
        // catalog 仅后缀相同、不是独立段，不得误拒
        String ok = authorizer.authorize("/data/logs/catalog", allowedRoots, denied);
        assertEquals("/data/logs/catalog", ok);
    }

    @Test
    void existingGlobBehaviorUnchanged() {
        // 原有 glob 行为不回归：**/*.key 任意深度（含根目录下一级）、/proc 本身、id_rsa 任意位置
        PathAccessException ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/a/b/c.key", allowedRoots, deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/data/logs/top.key", allowedRoots, deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
        ex = assertThrows(PathAccessException.class,
                () -> authorizer.authorize("/proc", Collections.singletonList("/proc"), deniedPaths));
        assertEquals("DENIED_PATH", ex.getCode());
    }
}
