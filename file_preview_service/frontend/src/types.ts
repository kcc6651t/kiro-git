export interface Me {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
}

export interface ServerSummary {
  id: string;
  name: string;
  env: string;
  tags: string[];
  defaultRoot: string;
}

export interface ServerBookmark {
  name: string;
  path: string;
}

export interface ServerDetail extends ServerSummary {
  allowedRoots: string[];
  bookmarks: ServerBookmark[];
}

export interface AgentHealth {
  serverId: string;
  status: string;
  version?: string;
  error?: string;
}

export interface FileEntry {
  name: string;
  path: string;
  type: string;
  size: number;
  mode: string;
  owner: string;
  group: string;
  modifiedAt: string;
  readable: boolean;
  previewable: boolean;
}

export interface ListResult {
  path: string;
  realPath: string;
  entries: FileEntry[];
  hasMore: boolean;
}

export interface FilePreview {
  path: string;
  realPath: string;
  mimeType: string;
  encoding: string;
  size: number;
  truncated: boolean;
  returnedLines: number;
  bytesReturned: number;
  content?: string;
  binary: boolean;
  /** 首行对应的真实文件行号（lines 模式；head 为 1） */
  startLine?: number;
  /** 首字节的文件偏移，startOffset + bytesReturned 用于向下续读 */
  startOffset?: number;
}

export interface SearchMatch {
  lineNumber: number;
  line: string;
}

export interface SearchResult {
  path: string;
  realPath: string;
  matches: SearchMatch[];
  truncated: boolean;
  scannedBytes: number;
}

export interface ServerConfig {
  id: string;
  name: string;
  env: string;
  enabled: boolean;
  tags: string[];
  defaultRoot: string;
  allowedRoots: string[];
  deniedPaths: string[];
  bookmarks: ServerBookmark[];
  agent: {
    baseUrl: string;
    serverName: string;
    connectTimeoutMs: number;
    readTimeoutMs: number;
    caCertRef: string;
    clientCertRef: string;
  };
}

export interface Capabilities {
  serverId: string;
  version: string;
  maxPreviewBytes: number;
  maxPreviewLines: number;
  maxTailLines: number;
  maxDirectoryEntries: number;
  searchSupported: boolean;
  imagePreviewSupported: boolean;
  syncEnabled?: boolean;
  syncWriteEnabled?: boolean;
}

export interface AdminServerStatus {
  health: AgentHealth;
  capabilities?: Capabilities;
}

export interface TailResult {
  path: string;
  realPath: string;
  encoding: string;
  size: number;
  returnedLines: number;
  truncated: boolean;
  content: string;
}

export interface Bookmark {
  id: number;
  serverId: string;
  name: string;
  path: string;
  scopeType: 'personal' | 'team' | 'global';
  scopeRef?: string;
  sortOrder: number;
}

export const ENCODINGS = ['UTF-8', 'GBK', 'GB18030', 'ISO-8859-1'];

export interface SyncRule {
  name: string;
  sourceDir: string;
  targetDir?: string;
  includes: string[];
  excludes: string[];
  excludeDirs?: string[];
  recursive: boolean;
  preservePermissions: boolean;
  preserveOwnership: boolean;
}

export interface SyncJob {
  id: number;
  serverId: string;
  targetServerId: string;
  trigger: string;
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  startedAt: string;
  finishedAt?: string;
  filesTotal: number;
  filesSynced: number;
  filesSkipped: number;
  filesFailed: number;
  bytesTransferred: number;
  message?: string;
}

export interface BackupView {
  serverId: string;
  serverName: string;
  targetServerId: string;
  enabled: boolean;
  schedule?: string;
  running: boolean;
  rules: SyncRule[];
  lastJob?: SyncJob;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
}

export interface UserAccountView {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  enabled: boolean;
  authProvider: string;
  createdAt?: string;
}

export const ALL_ROLES = ['ADMIN', 'OPERATOR', 'AUDITOR'];

// ---- SQL 工作台 ----
export interface SqlDataSource {
  id: number;
  name: string;
  host: string;
  port: number;
  dbType: string;
  defaultDatabase?: string;
  username: string;
  params?: string;
}

export interface SqlTableInfo {
  name: string;
  type: string;
}

export interface SqlColumnInfo {
  name: string;
  type: string;
  nullable: boolean;
}

export interface SqlExecResult {
  columns?: string[];
  rows?: (string | null)[][];
  rowCount: number;
  truncated: boolean;
  updateCount: number;
  elapsedMs: number;
}
