package com.company.filepreview.file;

import com.company.filepreview.file.model.AgentCapabilities;
import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.file.model.FilePreview;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.PreviewOptions;
import com.company.filepreview.file.model.RemoteFileMeta;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.file.model.TailOptions;
import com.company.filepreview.file.model.TailResult;
import com.company.filepreview.file.model.UserContext;

/**
 * Abstraction over remote file access against a target server Agent.
 *
 * <p>Two implementations exist:</p>
 * <ul>
 *   <li>{@code MockRemoteFileClient} - in-memory fixture used to drive the frontend
 *       before real Agents exist.</li>
 *   <li>{@code AgentRemoteFileClient} - calls the Agent over HTTPS + mTLS.</li>
 * </ul>
 *
 * <p>Write operations (upload/delete/rename/edit) are intentionally absent. Adding
 * them later means adding methods here plus the corresponding read-only guard
 * relaxation in the Agent; nothing in the read path assumes mutability.</p>
 */
public interface RemoteFileClient {

    AgentHealth health(String serverId);

    AgentCapabilities capabilities(String serverId);

    ListResult list(String serverId, UserContext user, String path, ListOptions options);

    RemoteFileMeta stat(String serverId, UserContext user, String path);

    FilePreview preview(String serverId, UserContext user, String path, PreviewOptions options);

    TailResult tail(String serverId, UserContext user, String path, TailOptions options);

    SearchResult search(String serverId, UserContext user, String path, SearchOptions options);
}
