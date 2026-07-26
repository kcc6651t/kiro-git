// SQL 工作台草稿：服务端持久化（GET/PUT /api/sql/draft，属主即当前登录用户），
// 重新登录后自动恢复，超 15 天未更新由后端定期清理。
// 本模块只负责草稿的序列化/解析/校验；只持久化数据源/标签组/激活标签，result/loading/error 等瞬态一律剔除。

export interface DraftTab {
  key: string;
  title: string;
  sql: string;
  database?: string;
  maxRows: number;
}

export interface SqlDraft {
  v: 1;
  selectedDs?: number;
  activeKey: string;
  tabs: DraftTab[];
  savedAt: number;
}

/** 从标签状态提取可持久化字段，生成草稿 payload。 */
export function serializeDraft(selectedDs: number | undefined, activeKey: string, tabs: DraftTab[]): SqlDraft {
  return {
    v: 1,
    selectedDs,
    activeKey,
    tabs: tabs.map((t) => ({ key: t.key, title: t.title, sql: t.sql, database: t.database, maxRows: t.maxRows })),
    savedAt: Date.now(),
  };
}

/** 解析并校验草稿；字段形状不合法即视为损坏，返回 undefined 由调用方回退默认标签。 */
export function parseDraft(raw: string | null): SqlDraft | undefined {
  if (!raw) return undefined;
  let p: any;
  try {
    p = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (!p || p.v !== 1 || !Array.isArray(p.tabs) || p.tabs.length === 0) return undefined;
  const tabs: DraftTab[] = [];
  for (const t of p.tabs) {
    if (!t || typeof t.key !== 'string' || !t.key) return undefined;
    if (typeof t.title !== 'string' || typeof t.sql !== 'string') return undefined;
    if (t.database !== undefined && typeof t.database !== 'string') return undefined;
    if (!Number.isFinite(t.maxRows) || t.maxRows <= 0) return undefined;
    tabs.push({ key: t.key, title: t.title, sql: t.sql, database: t.database, maxRows: t.maxRows });
  }
  // 激活标签必须存在于 tabs，否则回退到首个
  const keys = new Set(tabs.map((t) => t.key));
  const activeKey = typeof p.activeKey === 'string' && keys.has(p.activeKey) ? p.activeKey : tabs[0].key;
  return {
    v: 1,
    selectedDs: Number.isFinite(p.selectedDs) ? p.selectedDs : undefined,
    activeKey,
    tabs,
    savedAt: Number.isFinite(p.savedAt) ? p.savedAt : 0,
  };
}

/** 从已恢复标签的 key（tab-N）推算下一个可用序号，避免新建标签 key 冲突。 */
export function nextTabSeq(tabs: { key: string }[]): number {
  let max = 0;
  for (const t of tabs) {
    const m = /^tab-(\d+)$/.exec(t.key);
    if (m) max = Math.max(max, Number(m[1]));
  }
  return max + 1;
}
