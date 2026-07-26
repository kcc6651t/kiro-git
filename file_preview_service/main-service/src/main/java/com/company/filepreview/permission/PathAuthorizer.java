package com.company.filepreview.permission;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Central-side path authorization.
 *
 * <p>This is the first of two path checks. The Agent performs the authoritative
 * check (including {@code EvalSymlinks}) on the target server. This class rejects
 * obviously illegal or out-of-scope paths early so we never even call the Agent.</p>
 *
 * <p>Rules enforced:</p>
 * <ul>
 *   <li>Path must be absolute and non-blank.</li>
 *   <li>Path must not contain NUL / control characters.</li>
 *   <li>Path is normalized (resolving {@code .} and {@code ..}); traversal above a
 *       root is rejected.</li>
 *   <li>Normalized path must sit within one of the server's {@code allowedRoots}.</li>
 *   <li>Normalized path must not match any {@code deniedPaths} glob.</li>
 * </ul>
 */
@Component
public class PathAuthorizer {

    /**
     * @return the normalized, validated absolute path.
     * @throws PathAccessException when the path is illegal or out of scope.
     */
    public String authorize(String rawPath, List<String> allowedRoots, List<String> deniedPaths) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new PathAccessException("ILLEGAL_PATH", "Path must not be empty");
        }
        if (!rawPath.startsWith("/")) {
            throw new PathAccessException("ILLEGAL_PATH", "Path must be absolute");
        }
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new PathAccessException("ILLEGAL_PATH", "Path must not contain control characters");
            }
        }

        String normalized = normalize(rawPath);

        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new PathAccessException("NO_ALLOWED_ROOTS", "No allowed roots configured for server");
        }

        boolean withinRoot = false;
        for (String root : allowedRoots) {
            String normRoot = normalize(root);
            if (isWithin(normalized, normRoot)) {
                withinRoot = true;
                break;
            }
        }
        if (!withinRoot) {
            throw new PathAccessException("OUTSIDE_ALLOWED_ROOTS", "Path is outside allowed roots");
        }

        if (deniedPaths != null) {
            for (String denied : deniedPaths) {
                if (matchesGlob(normalized, denied)) {
                    throw new PathAccessException("DENIED_PATH", "Path is explicitly denied");
                }
            }
        }

        return normalized;
    }

    /**
     * Normalize an absolute path, collapsing {@code .} and {@code ..} segments.
     * A {@code ..} that would escape the filesystem root is rejected.
     */
    public String normalize(String path) {
        String[] segments = path.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String seg : segments) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (stack.isEmpty()) {
                    throw new PathAccessException("PATH_TRAVERSAL", "Path escapes filesystem root");
                }
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        return sb.toString();
    }

    /** True when {@code path} equals {@code root} or is a descendant of it. */
    public boolean isWithin(String path, String root) {
        if (root.equals("/")) {
            return true;
        }
        if (path.equals(root)) {
            return true;
        }
        return path.startsWith(root + "/");
    }

    /**
     * Glob matcher supporting {@code *} (single segment), {@code **} (any depth) and
     * {@code ?}. Patterns without a leading slash are treated as {@code **}-prefixed
     * so a pattern like {@code **}/id_rsa matches anywhere.
     */
    public boolean matchesGlob(String path, String glob) {
        if (glob == null || glob.isEmpty()) {
            return false;
        }
        String pattern = glob;
        // 尾斜杠视为目录自身及其子树的拒绝（"/root/" 与 "/root" 同义）
        if (pattern.length() > 1 && pattern.endsWith("/")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        if (!pattern.startsWith("/")) {
            pattern = "/**/" + pattern;
        }
        Pattern regex = globToRegex(pattern);
        if (regex.matcher(path).matches()) {
            return true;
        }
        // A denied directory also denies everything beneath it.
        Pattern subtree = globToRegex(pattern.endsWith("/**") ? pattern : pattern + "/**");
        return subtree.matcher(path).matches();
    }

    private Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = (i + 1 < glob.length() && glob.charAt(i + 1) == '*');
                if (doubleStar) {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        // "**/" 匹配零级或多级目录，后续段须完整匹配（段边界）：
                        // "log" -> "^/(.*/)?log$"，拒 "/x/log" 与 "/log"，不误伤 "/home/user/catalog"
                        sb.append("(.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                    continue;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
            i++;
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}
