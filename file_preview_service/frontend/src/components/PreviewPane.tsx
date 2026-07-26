import { useEffect, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import { Alert, Button, Empty, Input, InputNumber, List, Segmented, Select, Switch, Tag, message } from 'antd';
import { FileTextOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { api } from '../api';
import { ENCODINGS } from '../types';
import type { FileEntry, SearchMatch } from '../types';
import { languageForPath, highlightParts } from '../utils';

// 预览页初始加载行数；其余内容在下滚时按字节窗口动态加载
const INITIAL_LINES = 300;
// 每次动态加载的字节窗口（256KB）
const RANGE_CHUNK = 262144;
// 搜索跳转：目标行之前的上下文行数与窗口总行数
const JUMP_CONTEXT = 150;
const JUMP_WINDOW = 300;

// 标签预览缓存条目：切换标签时保存/恢复，避免切回重拉
interface CachedTab {
  content: string;
  meta: string;
  truncated: boolean;
  binary: boolean;
  hasMore: boolean;
  baseLine: number;
  mode: 'preview' | 'tail';
  encoding: string;
  tailLines: number;
  nextOffset: number;
  scrollTop?: number;
}

interface Props {
  serverId?: string;
  file?: FileEntry;
  /** 当前服务器打开的标签路径，用于缓存剪枝（关闭标签即释放其缓存） */
  tabPaths?: string[];
  /** 缓存估算字节数变化上报（字符数 ≈ 内存字节数，仅作量级参考） */
  onCacheBytes?: (n: number) => void;
}

export default function PreviewPane({ serverId, file, tabPaths, onCacheBytes }: Props) {
  const [mode, setMode] = useState<'preview' | 'tail'>('preview');
  const [encoding, setEncoding] = useState('UTF-8');
  const [content, setContent] = useState('');
  const [meta, setMeta] = useState<string>('');
  const [truncated, setTruncated] = useState(false);
  const [binary, setBinary] = useState(false);
  const [tailLines, setTailLines] = useState(500);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [loading, setLoading] = useState(false);

  // ---- 预览动态加载状态 ----
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const nextOffset = useRef(0);
  const restoreScrollTop = useRef<number | null>(null);
  // 同步守卫，避免高频滚动事件在异步 state 生效前触发重复请求
  const loadingMoreLock = useRef(false);
  // 加载序号：每次整载自增，await 落地前比对，丢弃切文件后的过期响应
  const loadSeq = useRef(0);
  // 编辑器首行对应的真实文件行号（搜索跳转开窗口后 >1）
  const [baseLine, setBaseLine] = useState(1);
  // 窗口加载完成后待定位的文件行号
  const pendingReveal = useRef<number | null>(null);
  // 命中行的编辑器装饰
  const decorations = useRef<any>();
  // 标签缓存：key 为 `${serverId}|${path}`，切换标签时保存/恢复预览状态
  const tabCache = useRef(new Map<string, CachedTab>());
  // 上一个激活标签的 key，切换时把最新快照落缓存
  const prevKeyRef = useRef<string>();
  // 随渲染更新的最新预览快照，供切换标签时落缓存
  const snapRef = useRef<CachedTab>({
    content: '', meta: '', truncated: false, binary: false, hasMore: false,
    baseLine: 1, mode: 'preview', encoding: 'UTF-8', tailLines: 500, nextOffset: 0,
  });
  // 缓存恢复/搜索跳转引发的 mode/encoding/tailLines 变更不再触发整载（消费一次即失效）
  const skipOptionsLoadOnce = useRef(false);
  // 上一次的 模式/编码/tail 行数，用于识别用户手动变更（首次挂载与缓存恢复不整载）
  const prevOptions = useRef({ mode, encoding, tailLines });
  // 上次上报的缓存字节数，未变化不重复上报
  const lastCacheBytes = useRef(-1);

  const [showSearch, setShowSearch] = useState(false);
  const [query, setQuery] = useState('');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [matches, setMatches] = useState<SearchMatch[]>([]);
  const [searchTruncated, setSearchTruncated] = useState(false);
  const [searching, setSearching] = useState(false);

  const timer = useRef<number>();
  const editorRef = useRef<any>();
  // 始终指向最新的 loadMore，供 editor 滚动回调调用
  const loadMoreRef = useRef<() => void>(() => {});

  const load = async () => {
    const seq = ++loadSeq.current;
    if (!serverId || !file) return;
    setLoading(true);
    setContent(''); // 先清空，避免新文件加载期间仍显示旧文件内容
    setHasMore(false);
    nextOffset.current = 0;
    loadingMoreLock.current = false;
    setBaseLine(1);
    if (decorations.current) {
      decorations.current.clear();
      decorations.current = undefined;
    }
    try {
      if (mode === 'preview') {
        const p = await api.preview(serverId, file.path, encoding, INITIAL_LINES);
        if (seq !== loadSeq.current) return; // 已有更新的加载，丢弃过期响应
        setBinary(p.binary);
        setTruncated(p.truncated);
        setContent(p.binary ? '' : p.content ?? '');
        nextOffset.current = p.bytesReturned;
        setHasMore(!p.binary && p.truncated);
        setMeta(`已加载 ${p.returnedLines} 行 · 共 ${(p.size / 1024 / 1024).toFixed(2)} MB · ${p.encoding}`);
      } else {
        const t = await api.tail(serverId, file.path, tailLines, encoding);
        if (seq !== loadSeq.current) return;
        setBinary(false);
        setTruncated(t.truncated);
        setContent(t.content);
        setMeta(`tail ${t.returnedLines} 行 · ${t.encoding}`);
      }
    } catch (e: any) {
      if (seq === loadSeq.current) message.error(e?.response?.data?.message ?? '加载失败');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  };

  // 向下滚动时按字节窗口追加剩余内容
  const loadMore = async () => {
    if (!serverId || !file || mode !== 'preview' || !hasMore || binary) return;
    if (loadingMoreLock.current) return;
    loadingMoreLock.current = true;
    setLoadingMore(true);
    const seq = loadSeq.current;
    const editor = editorRef.current;
    if (editor) restoreScrollTop.current = editor.getScrollTop();
    try {
      const p = await api.previewRange(serverId, file.path, encoding, nextOffset.current, RANGE_CHUNK);
      if (seq !== loadSeq.current) return; // 文件已切换，丢弃过期响应
      nextOffset.current += p.bytesReturned;
      setHasMore(p.truncated && p.bytesReturned > 0);
      setContent((prev) => prev + (p.content ?? ''));
    } catch (e: any) {
      if (seq === loadSeq.current) message.error(e?.response?.data?.message ?? '加载更多失败');
    } finally {
      setLoadingMore(false);
      loadingMoreLock.current = false;
    }
  };

  const runSearch = async () => {
    if (!serverId || !file || !query) return;
    setSearching(true);
    const seq = loadSeq.current;
    try {
      const r = await api.search(serverId, file.path, query, caseSensitive, encoding);
      if (seq !== loadSeq.current) return; // 文件已切换，丢弃过期响应
      setMatches(r.matches);
      setSearchTruncated(r.truncated);
      if (r.matches.length === 0) message.info('未找到匹配');
    } catch (e: any) {
      if (seq === loadSeq.current) message.error(e?.response?.data?.message ?? '搜索失败');
    } finally {
      if (seq === loadSeq.current) setSearching(false);
    }
  };

  // 定位并整行高亮一个真实文件行号（换算为编辑器内行号）
  const revealAndHighlight = (fileLine: number) => {
    const editor = editorRef.current;
    if (!editor) return;
    const ln = Math.max(1, fileLine - baseLine + 1);
    if (decorations.current) decorations.current.clear();
    decorations.current = editor.createDecorationsCollection([
      {
        range: { startLineNumber: ln, startColumn: 1, endLineNumber: ln, endColumn: 1 },
        options: { isWholeLine: true, className: 'fp-editor-hit-line', marginClassName: 'fp-editor-hit-line' },
      },
    ]);
    editor.revealLineInCenter(ln);
    editor.setPosition({ lineNumber: ln, column: 1 });
    editor.focus();
  };

  const jumpToLine = async (lineNumber: number) => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    // 目标行已在当前预览内容中（tail 模式的行号与文件行号不对应，不走此捷径）：直接定位
    if (mode === 'preview' && editor && model && lineNumber >= baseLine && lineNumber <= baseLine + model.getLineCount() - 1) {
      revealAndHighlight(lineNumber);
      return;
    }
    if (!serverId || !file || binary) return;
    // 目标行未加载：按行号窗口直接拉取其上下文，无需先向下滚动加载
    const fromLine = Math.max(1, lineNumber - JUMP_CONTEXT);
    const seq = loadSeq.current;
    try {
      const p = await api.previewLines(serverId, file.path, encoding, fromLine, JUMP_WINDOW);
      if (seq !== loadSeq.current) return; // 文件已切换，丢弃过期响应
      if (mode !== 'preview') {
        skipOptionsLoadOnce.current = true; // 跳转引发的 mode 切换不再触发整载
        setMode('preview');
      }
      const start = p.startLine ?? fromLine;
      setBinary(!!p.binary);
      setTruncated(p.truncated);
      setContent(p.binary ? '' : p.content ?? '');
      setBaseLine(start);
      // 续读锚点：下滚从窗口结束的字节偏移继续按 range 追加
      nextOffset.current = (p.startOffset ?? 0) + p.bytesReturned;
      setHasMore(!p.binary && p.truncated);
      setMeta(`第 ${start} 行起 · 已加载 ${p.returnedLines} 行 · ${p.encoding}`);
      pendingReveal.current = lineNumber;
    } catch (e: any) {
      if (seq === loadSeq.current) message.error(e?.response?.data?.message ?? '跳转加载失败');
    }
  };

  // 估算全部标签的缓存占用：缓存条目 + 当前内容的字符数（1 字符 ≈ 1 字节粗略估算，仅作内存量级参考）
  const reportCacheBytes = () => {
    let total = snapRef.current.content.length;
    for (const v of tabCache.current.values()) total += v.content.length;
    if (total !== lastCacheBytes.current) {
      lastCacheBytes.current = total;
      onCacheBytes?.(total);
    }
  };

  // 服务器/文件切换：旧标签快照落缓存；新标签命中缓存则恢复并跳过整载，未命中照常整载
  useEffect(() => {
    const key = serverId && file ? `${serverId}|${file.path}` : undefined;
    // 旧标签快照（含滚动锚点）落缓存，供切回时恢复
    if (prevKeyRef.current && prevKeyRef.current !== key) {
      tabCache.current.set(prevKeyRef.current, {
        ...snapRef.current,
        scrollTop: editorRef.current?.getScrollTop(),
      });
    }
    prevKeyRef.current = key;
    if (!key) {
      ++loadSeq.current; // 文件清空：使在途响应失效（与原 load 提前返回语义一致）
      setMatches([]);
    } else {
      const cached = tabCache.current.get(key);
      if (cached) {
        // 命中缓存：恢复全部预览状态，跳过本次整载
        ++loadSeq.current; // 使在途旧响应失效
        // 恢复会改变 模式/编码/tail 行数 时，标记跳过随后的选项整载一次
        if (cached.mode !== mode || cached.encoding !== encoding || cached.tailLines !== tailLines) {
          skipOptionsLoadOnce.current = true;
        }
        setMode(cached.mode);
        setEncoding(cached.encoding);
        setTailLines(cached.tailLines);
        setContent(cached.content);
        setMeta(cached.meta);
        setTruncated(cached.truncated);
        setBinary(cached.binary);
        setHasMore(cached.hasMore);
        setBaseLine(cached.baseLine);
        nextOffset.current = cached.nextOffset;
        loadingMoreLock.current = false;
        restoreScrollTop.current = cached.scrollTop ?? null;
        setMatches([]);
        if (decorations.current) {
          decorations.current.clear();
          decorations.current = undefined;
        }
      } else {
        load();
        setMatches([]);
      }
    }
    reportCacheBytes();
  }, [serverId, file?.path]);

  // 仅用户手动切换 模式/编码/tail 行数 才整载（首次挂载、缓存恢复、搜索跳转均不触发）
  useEffect(() => {
    const prev = prevOptions.current;
    prevOptions.current = { mode, encoding, tailLines };
    if (prev.mode === mode && prev.encoding === encoding && prev.tailLines === tailLines) return;
    if (skipOptionsLoadOnce.current) {
      skipOptionsLoadOnce.current = false;
      return;
    }
    load();
    setMatches([]);
  }, [mode, encoding, tailLines]);

  // 预览快照随每次渲染更新；声明在切换 effect 之后，保证其拿到的是旧标签状态
  useEffect(() => {
    snapRef.current = { content, meta, truncated, binary, hasMore, baseLine, mode, encoding, tailLines, nextOffset: nextOffset.current };
  });

  // 缓存剪枝：释放当前服务器已关闭标签的缓存（其他服务器的条目保留，供切回时恢复）
  useEffect(() => {
    if (!serverId) return;
    const keep = new Set(tabPaths ?? []);
    if (file) keep.add(file.path);
    const prefix = `${serverId}|`;
    let changed = false;
    for (const k of Array.from(tabCache.current.keys())) {
      if (k.startsWith(prefix) && !keep.has(k.slice(prefix.length))) {
        tabCache.current.delete(k);
        changed = true;
      }
    }
    if (changed) reportCacheBytes();
  }, [tabPaths, serverId]);

  // 内容变化后重算并上报缓存占用
  useEffect(() => {
    reportCacheBytes();
  }, [content]);

  // 保持 loadMoreRef 指向最新闭包
  useEffect(() => {
    loadMoreRef.current = loadMore;
  });

  // 追加内容后恢复滚动位置，避免视图跳动
  useEffect(() => {
    if (restoreScrollTop.current != null && editorRef.current) {
      editorRef.current.setScrollTop(restoreScrollTop.current);
      restoreScrollTop.current = null;
    }
  }, [content]);

  // 跳转窗口渲染完成后定位并高亮目标行
  useEffect(() => {
    if (pendingReveal.current != null && editorRef.current) {
      const target = pendingReveal.current;
      pendingReveal.current = null;
      revealAndHighlight(target);
    }
  }, [content]);

  useEffect(() => {
    window.clearInterval(timer.current);
    if (autoRefresh && mode === 'tail') {
      timer.current = window.setInterval(load, 5000);
    }
    return () => window.clearInterval(timer.current);
  }, [autoRefresh, mode, serverId, file?.path, tailLines, encoding]);

  return (
    <section className="fp-panel" style={{ flex: 1, minHeight: 0 }}>
      <header className="fp-panel__header">
        <FileTextOutlined />
        预览
        {mode === 'tail' && truncated && (
          <Tag color="orange" bordered={false} className="fp-panel__extra" style={{ marginLeft: 'auto' }}>
            已截断
          </Tag>
        )}
      </header>

      {!file ? (
        <div className="fp-panel__body" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Empty description="选择文件以预览" />
        </div>
      ) : (
        <>
          <div className="fp-toolbar">
            <Segmented
              size="small"
              value={mode}
              onChange={(v) => setMode(v as 'preview' | 'tail')}
              options={[
                { label: '预览', value: 'preview' },
                { label: '日志 tail', value: 'tail' },
              ]}
            />
            <Select
              size="small"
              style={{ width: 120 }}
              value={encoding}
              onChange={setEncoding}
              options={ENCODINGS.map((e) => ({ value: e, label: e }))}
            />
            {mode === 'tail' && (
              <>
                <InputNumber
                  size="small"
                  min={1}
                  max={5000}
                  value={tailLines}
                  onChange={(v) => setTailLines(v ?? 500)}
                  addonAfter="行"
                  style={{ width: 120 }}
                />
                <span className="fp-text-2">
                  自动刷新 <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
                </span>
              </>
            )}
            <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={load}>
              刷新
            </Button>
            <Button
              size="small"
              icon={<SearchOutlined />}
              type={showSearch ? 'primary' : 'default'}
              onClick={() => setShowSearch((s) => !s)}
            >
              文件内搜索
            </Button>
          </div>

          {showSearch && (
            <div className="fp-toolbar fp-toolbar--subtle">
              <Input
                size="small"
                style={{ width: 220 }}
                placeholder="在整个文件中搜索关键字"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onPressEnter={runSearch}
                allowClear
              />
              <span className="fp-text-2">
                区分大小写 <Switch size="small" checked={caseSensitive} onChange={setCaseSensitive} />
              </span>
              <Button size="small" type="primary" icon={<SearchOutlined />} loading={searching} onClick={runSearch}>
                搜索
              </Button>
              {matches.length > 0 && <Tag bordered={false}>{matches.length} 条匹配</Tag>}
              {searchTruncated && (
                <Tag color="orange" bordered={false}>
                  结果过多，已截断
                </Tag>
              )}
            </div>
          )}

          <div className="fp-meta-line fp-mono">
            {file.path}
            {meta && ` · ${meta}`}
            {mode === 'preview' && loadingMore && ' · 正在加载更多…'}
            {mode === 'preview' && !loadingMore && hasMore && ' · 下滚加载更多'}
            {mode === 'preview' && !hasMore && !binary && content && (baseLine > 1 ? ' · 已到文件尾' : ' · 已全部加载')}
          </div>

          <div className="fp-panel__body" style={{ display: 'flex', overflow: 'hidden' }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              {binary ? (
                <Alert style={{ margin: 'var(--fp-space-3)' }} type="warning" message="二进制文件不支持文本预览" />
              ) : (
                <Editor
                  height="100%"
                  language={mode === 'tail' ? 'plaintext' : languageForPath(file.path)}
                  value={content}
                  onMount={(editor) => {
                    editorRef.current = editor;
                    // 接近底部时触发动态加载
                    editor.onDidScrollChange(() => {
                      const scrollTop = editor.getScrollTop();
                      const height = editor.getLayoutInfo().height;
                      const scrollHeight = editor.getScrollHeight();
                      if (scrollTop + height >= scrollHeight - 200) {
                        loadMoreRef.current();
                      }
                    });
                  }}
                  options={{
                    readOnly: true,
                    minimap: { enabled: false },
                    wordWrap: 'on',
                    fontSize: 13,
                    fontFamily: 'var(--fp-mono)',
                    scrollBeyondLastLine: false,
                    automaticLayout: true,
                    // 让查找框/悬浮等浮层渲染到 body 层，短内容时不被容器裁剪或被上层遮挡
                    fixedOverflowWidgets: true,
                    // 窗口模式下显示真实文件行号（baseLine 为首行行号）
                    lineNumbers: (n: number) => String(baseLine + n - 1),
                  }}
                />
              )}
            </div>
            {showSearch && matches.length > 0 && (
              <div className="fp-scroll" style={{ width: 260, borderLeft: '1px solid var(--fp-border)', overflow: 'auto' }}>
                <List
                  size="small"
                  dataSource={matches}
                  renderItem={(m) => (
                    <div
                      className="fp-row"
                      style={{ padding: 'var(--fp-space-1) var(--fp-space-2)' }}
                      onClick={() => jumpToLine(m.lineNumber)}
                    >
                      <div style={{ fontSize: 'var(--fp-fs-sm)' }}>
                        <Tag color="blue" bordered={false}>
                          {m.lineNumber}
                        </Tag>
                        <span className="fp-mono" style={{ wordBreak: 'break-all' }}>
                          {highlightParts(m.line.slice(0, 120), query, caseSensitive).map((p, i) =>
                            p.hit ? (
                              <mark key={i} className="fp-hit">
                                {p.text}
                              </mark>
                            ) : (
                              <span key={i}>{p.text}</span>
                            ),
                          )}
                        </span>
                      </div>
                    </div>
                  )}
                />
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}
