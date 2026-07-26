// 通用工具函数：从组件中提取的纯函数，供多处复用

export function humanBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

export function languageForPath(path: string): string {
  const lower = path.toLowerCase();
  if (lower.endsWith('.json')) return 'json';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.xml')) return 'xml';
  if (lower.endsWith('.sh')) return 'shell';
  if (lower.endsWith('.properties') || lower.endsWith('.conf') || lower.endsWith('.ini')) return 'ini';
  if (lower.endsWith('.js')) return 'javascript';
  if (lower.endsWith('.ts')) return 'typescript';
  return 'plaintext';
}

/** 标签命名递补：取 titles 中未被占用的最小正整数编号（如「查询 3」→ 3）。 */
export function nextFreeTabNo(titles: string[]): number {
  const used = new Set<number>();
  for (const t of titles) {
    const m = /^查询 (\d+)$/.exec(t);
    if (m) used.add(Number(m[1]));
  }
  let n = 1;
  while (used.has(n)) n++;
  return n;
}

/** 结果集客户端筛选：任一列包含关键字（大小写不敏感）即保留该行；空关键字原样返回。 */
export function filterRows(rows: (string | null)[][], keyword: string): (string | null)[][] {
  const k = keyword.trim().toLowerCase();
  if (!k) return rows;
  return rows.filter((row) => row.some((v) => v != null && String(v).toLowerCase().includes(k)));
}

export interface HighlightPart {
  text: string;
  hit: boolean;
}

/** 把 text 按 query 切分为高亮片段（indexOf 实现，query 无需正则转义）。 */
export function highlightParts(text: string, query: string, caseSensitive = false): HighlightPart[] {
  if (!query) return [{ text, hit: false }];
  const parts: HighlightPart[] = [];
  const hay = caseSensitive ? text : text.toLowerCase();
  const needle = caseSensitive ? query : query.toLowerCase();
  let i = 0;
  for (;;) {
    const idx = hay.indexOf(needle, i);
    if (idx < 0) break;
    if (idx > i) parts.push({ text: text.slice(i, idx), hit: false });
    parts.push({ text: text.slice(idx, idx + query.length), hit: true });
    i = idx + query.length;
  }
  if (i < text.length) parts.push({ text: text.slice(i), hit: false });
  if (parts.length === 0) parts.push({ text, hit: false });
  return parts;
}

/**
 * 关闭标签后应激活的文件路径：关闭的是激活标签时优先取左邻居，无左邻居取右邻居；
 * 关闭非激活标签（或路径不在列表中）时保持 active 不变。无邻居可激活时返回 undefined。
 */
export function tabNeighbor(paths: string[], closing: string, active?: string): string | undefined {
  const idx = paths.indexOf(closing);
  if (idx < 0 || active !== closing) return active;
  return paths[idx - 1] ?? paths[idx + 1];
}
