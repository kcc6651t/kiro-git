import axios from 'axios';
import type {
  AdminServerStatus,
  AgentHealth,
  BackupView,
  Bookmark,
  FilePreview,
  ListResult,
  Me,
  Page,
  SearchResult,
  ServerConfig,
  ServerDetail,
  ServerSummary,
  SqlColumnInfo,
  SqlDataSource,
  SqlExecResult,
  SqlTableInfo,
  SyncJob,
  TailResult,
  UserAccountView,
} from './types';

// Same-origin in production; the Vite dev server proxies /api to the backend.
const http = axios.create({
  baseURL: '/',
  withCredentials: true,
});

export const api = {
  async login(username: string, password: string): Promise<Me> {
    const res = await http.post<Me>('/api/auth/login', { username, password });
    return res.data;
  },
  async logout(): Promise<void> {
    await http.post('/api/auth/logout');
  },
  async me(): Promise<Me> {
    const res = await http.get<Me>('/api/auth/me');
    return res.data;
  },
  async servers(): Promise<ServerSummary[]> {
    const res = await http.get<ServerSummary[]>('/api/servers');
    return res.data;
  },
  async serverDetail(serverId: string): Promise<ServerDetail> {
    const res = await http.get<ServerDetail>(`/api/servers/${encodeURIComponent(serverId)}`);
    return res.data;
  },
  async health(serverId: string): Promise<AgentHealth> {
    const res = await http.get<AgentHealth>(`/api/servers/${encodeURIComponent(serverId)}/health`);
    return res.data;
  },
  async list(
    serverId: string,
    path: string,
    showHidden: boolean,
    sortBy = 'name',
    sortOrder = 'asc',
    offset = 0,
    limit = 100,
  ): Promise<ListResult> {
    const res = await http.get<ListResult>(`/api/servers/${encodeURIComponent(serverId)}/files`, {
      params: { path, showHidden, sortBy, sortOrder, offset, limit },
    });
    return res.data;
  },
  async preview(serverId: string, path: string, encoding: string, lines = 300): Promise<FilePreview> {
    const res = await http.get<FilePreview>(`/api/servers/${encodeURIComponent(serverId)}/files/preview`, {
      params: { path, mode: 'head', lines, encoding },
    });
    return res.data;
  },
  // 从指定字节偏移继续读取一个窗口，用于预览页向下滚动时的动态加载
  async previewRange(
    serverId: string,
    path: string,
    encoding: string,
    offset: number,
    limitBytes = 262144,
  ): Promise<FilePreview> {
    const res = await http.get<FilePreview>(`/api/servers/${encodeURIComponent(serverId)}/files/preview`, {
      params: { path, mode: 'range', offset, limitBytes, encoding },
    });
    return res.data;
  },
  // 按行号窗口读取，用于文件内搜索直接跳转到目标行的上下文
  async previewLines(
    serverId: string,
    path: string,
    encoding: string,
    fromLine: number,
    lines = 300,
  ): Promise<FilePreview> {
    const res = await http.get<FilePreview>(`/api/servers/${encodeURIComponent(serverId)}/files/preview`, {
      params: { path, mode: 'lines', fromLine, lines, encoding },
    });
    return res.data;
  },
  async search(
    serverId: string,
    path: string,
    q: string,
    caseSensitive: boolean,
    encoding: string,
    maxResults = 200,
  ): Promise<SearchResult> {
    const res = await http.get<SearchResult>(`/api/servers/${encodeURIComponent(serverId)}/files/search`, {
      params: { path, q, caseSensitive, maxResults, encoding },
    });
    return res.data;
  },
  async tail(serverId: string, path: string, lines: number, encoding: string): Promise<TailResult> {
    const res = await http.get<TailResult>(`/api/servers/${encodeURIComponent(serverId)}/files/tail`, {
      params: { path, lines, encoding },
    });
    return res.data;
  },
  async bookmarks(): Promise<Bookmark[]> {
    const res = await http.get<Bookmark[]>('/api/bookmarks');
    return res.data;
  },
  async createBookmark(b: Partial<Bookmark>): Promise<Bookmark> {
    const res = await http.post<Bookmark>('/api/bookmarks', b);
    return res.data;
  },
  async updateBookmark(id: number, b: Partial<Bookmark>): Promise<Bookmark> {
    const res = await http.put<Bookmark>(`/api/bookmarks/${id}`, b);
    return res.data;
  },
  async deleteBookmark(id: number): Promise<void> {
    await http.delete(`/api/bookmarks/${id}`);
  },
  // --- admin: server configuration management ---
  async adminServers(): Promise<ServerConfig[]> {
    const res = await http.get<ServerConfig[]>('/api/admin/servers');
    return res.data;
  },
  async adminServerStatus(serverId: string): Promise<AdminServerStatus> {
    const res = await http.get<AdminServerStatus>(`/api/admin/servers/${encodeURIComponent(serverId)}/status`);
    return res.data;
  },
  async reloadServers(): Promise<{ serverCount: number }> {
    const res = await http.post<{ serverCount: number }>('/api/admin/servers/reload');
    return res.data;
  },
  async getServerConfig(): Promise<{ path: string; content: string }> {
    const res = await http.get<{ path: string; content: string }>('/api/admin/servers/config');
    return res.data;
  },
  async saveServerConfig(content: string): Promise<{ serverCount: number }> {
    const res = await http.put<{ serverCount: number }>('/api/admin/servers/config', { content });
    return res.data;
  },
  // --- admin: primary/standby sync ---
  async syncServers(): Promise<BackupView[]> {
    const res = await http.get<BackupView[]>('/api/admin/sync/servers');
    return res.data;
  },
  async syncJobs(serverId?: string, page = 0, size = 50): Promise<Page<SyncJob>> {
    const res = await http.get<Page<SyncJob>>('/api/admin/sync/jobs', {
      params: { serverId, page, size },
    });
    return res.data;
  },
  async runSync(serverId: string): Promise<SyncJob> {
    const res = await http.post<SyncJob>(`/api/admin/sync/${encodeURIComponent(serverId)}/run`);
    return res.data;
  },
  async syncJobDetail(id: number): Promise<{ detail: string }> {
    const res = await http.get<{ detail: string }>(`/api/admin/sync/jobs/${id}/detail`);
    return res.data;
  },
  // --- admin: user management ---
  async users(): Promise<UserAccountView[]> {
    const res = await http.get<UserAccountView[]>('/api/admin/users');
    return res.data;
  },
  async createUser(body: {
    username: string;
    displayName?: string;
    password: string;
    roles: string[];
    enabled: boolean;
  }): Promise<UserAccountView> {
    const res = await http.post<UserAccountView>('/api/admin/users', body);
    return res.data;
  },
  async updateUser(
    id: string,
    body: { displayName?: string; roles: string[]; enabled: boolean },
  ): Promise<UserAccountView> {
    const res = await http.put<UserAccountView>(`/api/admin/users/${encodeURIComponent(id)}`, body);
    return res.data;
  },
  async resetUserPassword(id: string, password: string): Promise<void> {
    await http.put(`/api/admin/users/${encodeURIComponent(id)}/password`, { password });
  },
  async deleteUser(id: string): Promise<void> {
    await http.delete(`/api/admin/users/${encodeURIComponent(id)}`);
  },
  // --- SQL 工作台 ---
  async sqlDataSources(): Promise<SqlDataSource[]> {
    const res = await http.get<SqlDataSource[]>('/api/sql/datasources');
    return res.data;
  },
  async createSqlDataSource(body: Partial<SqlDataSource> & { password?: string }): Promise<SqlDataSource> {
    const res = await http.post<SqlDataSource>('/api/sql/datasources', body);
    return res.data;
  },
  async updateSqlDataSource(id: number, body: Partial<SqlDataSource> & { password?: string }): Promise<SqlDataSource> {
    const res = await http.put<SqlDataSource>(`/api/sql/datasources/${id}`, body);
    return res.data;
  },
  async deleteSqlDataSource(id: number): Promise<void> {
    await http.delete(`/api/sql/datasources/${id}`);
  },
  async testSqlDataSource(id: number): Promise<void> {
    await http.post(`/api/sql/datasources/${id}/test`);
  },
  async importSqlDataSources(items: Array<Partial<SqlDataSource> & { password?: string }>): Promise<{ imported: number; total: number }> {
    const res = await http.post<{ imported: number; total: number }>('/api/sql/datasources/import', items);
    return res.data;
  },
  async sqlDatabases(id: number): Promise<string[]> {
    const res = await http.get<string[]>(`/api/sql/datasources/${id}/databases`);
    return res.data;
  },
  async sqlTables(id: number, database: string): Promise<SqlTableInfo[]> {
    const res = await http.get<SqlTableInfo[]>(`/api/sql/datasources/${id}/tables`, { params: { database } });
    return res.data;
  },
  async sqlColumns(id: number, database: string, table: string): Promise<SqlColumnInfo[]> {
    const res = await http.get<SqlColumnInfo[]>(`/api/sql/datasources/${id}/columns`, { params: { database, table } });
    return res.data;
  },
  async sqlExecute(id: number, body: { database?: string; sql: string; maxRows?: number }): Promise<SqlExecResult> {
    const res = await http.post<SqlExecResult>(`/api/sql/datasources/${id}/execute`, body);
    return res.data;
  },
  async sqlDraft(): Promise<{ content: string | null; updatedAt?: string }> {
    const res = await http.get<{ content: string | null; updatedAt?: string }>('/api/sql/draft');
    return res.data;
  },
  async saveSqlDraft(content: string): Promise<void> {
    await http.put('/api/sql/draft', { content });
  },
};
