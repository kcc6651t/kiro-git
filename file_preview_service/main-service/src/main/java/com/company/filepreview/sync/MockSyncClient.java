package com.company.filepreview.sync;

import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.sync.model.SyncFileInfo;
import com.company.filepreview.sync.model.SyncReadChunk;
import com.company.filepreview.sync.model.SyncScanResult;
import com.company.filepreview.sync.model.SyncWriteCommand;
import com.company.filepreview.sync.model.SyncWriteResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 内存模拟的同步客户端，启用条件：{@code filepreview.remote-client=mock}（默认）。
 * 用于在没有真实 Agent 时联调界面与统计。源侧返回固定文件清单，备机写入为 no-op。
 */
@Component
@ConditionalOnProperty(name = "filepreview.remote-client", havingValue = "mock", matchIfMissing = true)
public class MockSyncClient implements SyncClient {

    @Override
    public SyncScanResult scan(String serverId, UserContext user, String dir,
                               List<String> includes, List<String> excludes,
                               List<String> excludeDirs, boolean recursive) {
        List<SyncFileInfo> files = new ArrayList<>();
        files.add(file("app.log", 4096, 0640));
        files.add(file("config/app.conf", 512, 0644));
        if (recursive) {
            files.add(file("archive/old.log", 8192, 0640));
        }
        return SyncScanResult.builder()
                .dir(dir)
                .realDir(dir)
                .files(files)
                .truncated(false)
                .build();
    }

    @Override
    public SyncReadChunk read(String serverId, UserContext user, String path, long offset, int length) {
        // 单块返回，标记 eof。
        byte[] content = ("# mock content of " + path + "\n").getBytes(StandardCharsets.UTF_8);
        return SyncReadChunk.builder()
                .path(path)
                .offset(offset)
                .eof(true)
                .contentBase64(Base64.getEncoder().encodeToString(content))
                .build();
    }

    @Override
    public SyncWriteResult write(String serverId, UserContext user, SyncWriteCommand cmd) {
        int len = cmd.getContentBase64() != null
                ? Base64.getDecoder().decode(cmd.getContentBase64()).length : 0;
        return SyncWriteResult.builder()
                .path(cmd.getPath())
                .bytesWritten(len)
                .finalized(cmd.isLast())
                .permissionsSet(cmd.isPreservePermissions())
                .ownershipSet(false)
                .build();
    }

    private SyncFileInfo file(String rel, long size, int mode) {
        return SyncFileInfo.builder()
                .relPath(rel)
                .size(size)
                .mode(mode)
                .modTimeMs(System.currentTimeMillis())
                .uid(1000)
                .gid(1000)
                .owner("app")
                .group("app")
                .build();
    }
}
