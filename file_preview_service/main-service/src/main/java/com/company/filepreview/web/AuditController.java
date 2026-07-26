package com.company.filepreview.web;

import com.company.filepreview.audit.AuditEvent;
import com.company.filepreview.audit.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Audit query/export. Restricted to ADMIN by the security config
 * ({@code /api/admin/**}). AUDITOR access can be added by widening the matcher.
 */
@RestController
@RequestMapping("/api/admin/audits")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Page<AuditEvent> search(@RequestParam(required = false) String userId,
                                   @RequestParam(required = false) String serverId,
                                   @RequestParam(required = false) String operation,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return auditService.search(userId, serverId, operation, page, size);
    }

    @GetMapping("/export")
    public void export(@RequestParam(required = false) String userId,
                       @RequestParam(required = false) String serverId,
                       @RequestParam(required = false) String operation,
                       HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=audits.csv");
        PrintWriter writer = response.getWriter();
        writer.println("id,requestId,userId,username,clientIp,serverId,operation,path,success,errorCode,agentDurationMs,totalDurationMs,bytesReturned,createdAt");
        // Export up to a bounded number of most-recent records.
        Page<AuditEvent> events = auditService.search(userId, serverId, operation, 0, 500);
        for (AuditEvent e : events.getContent()) {
            writer.println(String.join(",",
                    str(e.getId()), csv(e.getRequestId()), csv(e.getUserId()), csv(e.getUsername()),
                    csv(e.getClientIp()), csv(e.getServerId()), csv(e.getOperation()), csv(e.getPath()),
                    String.valueOf(e.isSuccess()), csv(e.getErrorCode()),
                    str(e.getAgentDurationMs()), str(e.getTotalDurationMs()), str(e.getBytesReturned()),
                    csv(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)));
        }
        writer.flush();
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String csv(String s) {
        if (s == null) {
            return "";
        }
        // 防公式注入：以 = + - @ 开头的值会被 Excel 当公式执行，前缀单引号强制按文本处理
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        String escaped = s.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
