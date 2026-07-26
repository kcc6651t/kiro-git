package com.company.filepreview.sync;

import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.sync.model.SyncReadChunk;
import com.company.filepreview.sync.model.SyncScanResult;
import com.company.filepreview.sync.model.SyncWriteCommand;
import com.company.filepreview.sync.model.SyncWriteResult;

import java.util.List;

/**
 * 主备同步的 Agent 调用抽象。
 *
 * <p>源侧使用 {@link #scan} / {@link #read}（备机也可被 scan 以做增量比较），
 * 备机侧使用 {@link #write}。实现分为通过 mTLS 调用真实 Agent 的
 * {@code AgentSyncClient}，以及供界面/联调用的 {@code MockSyncClient}。</p>
 */
public interface SyncClient {

    SyncScanResult scan(String serverId, UserContext user, String dir,
                        List<String> includes, List<String> excludes,
                        List<String> excludeDirs, boolean recursive);

    SyncReadChunk read(String serverId, UserContext user, String path, long offset, int length);

    SyncWriteResult write(String serverId, UserContext user, SyncWriteCommand command);
}
