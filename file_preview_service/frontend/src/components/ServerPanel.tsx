import { useEffect, useRef, useState } from 'react';
import { Badge, Empty, Input, List, Select, Tag, Tooltip } from 'antd';
import { ClusterOutlined, DeleteOutlined, EditOutlined, FolderOpenOutlined, StarOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { AgentHealth, Bookmark, ServerDetail, ServerSummary } from '../types';

interface Props {
  servers: ServerSummary[];
  selectedServer?: string;
  personalBookmarks: Bookmark[];
  onSelectServer: (server: ServerSummary) => void;
  onOpenPath: (serverId: string, path: string) => void;
  onRenameBookmark: (b: { id: number; name: string; path: string }) => void;
  onDeleteBookmark: (id: number) => void;
}

const ENV_COLORS: Record<string, string> = {
  prod: 'red',
  test: 'blue',
  staging: 'gold',
};

export default function ServerPanel({
  servers,
  selectedServer,
  personalBookmarks,
  onSelectServer,
  onOpenPath,
  onRenameBookmark,
  onDeleteBookmark,
}: Props) {
  const [envFilter, setEnvFilter] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [health, setHealth] = useState<Record<string, AgentHealth>>({});
  const [detail, setDetail] = useState<ServerDetail>();
  // 详情请求序号：快速切换服务器时丢弃旧服务器的迟到响应
  const detailSeq = useRef(0);

  const envs = Array.from(new Set(servers.map((s) => s.env))).filter(Boolean);

  useEffect(() => {
    servers.forEach((s) => {
      api
        .health(s.id)
        .then((h) => setHealth((prev) => ({ ...prev, [s.id]: h })))
        .catch(() => setHealth((prev) => ({ ...prev, [s.id]: { serverId: s.id, status: 'DOWN' } })));
    });
  }, [servers]);

  useEffect(() => {
    const seq = ++detailSeq.current;
    if (selectedServer) {
      api
        .serverDetail(selectedServer)
        .then((d) => {
          if (seq === detailSeq.current) setDetail(d);
        })
        .catch(() => {
          if (seq === detailSeq.current) setDetail(undefined);
        });
    } else {
      setDetail(undefined);
    }
  }, [selectedServer]);

  const filtered = servers.filter((s) => {
    if (envFilter && s.env !== envFilter) return false;
    if (keyword) {
      const k = keyword.toLowerCase();
      return (
        s.name.toLowerCase().includes(k) ||
        s.id.toLowerCase().includes(k) ||
        s.tags.some((t) => t.toLowerCase().includes(k))
      );
    }
    return true;
  });

  const serverBookmarks = detail?.bookmarks ?? [];
  const personalForServer = personalBookmarks.filter((b) => b.serverId === selectedServer);
  const bookmarkItems: { id?: number; name: string; path: string; kind: '全局' | '个人' }[] = [
    ...serverBookmarks.map((b) => ({ id: undefined, name: b.name, path: b.path, kind: '全局' as const })),
    ...personalForServer.map((b) => ({ id: b.id, name: b.name, path: b.path, kind: '个人' as const })),
  ];

  return (
    <section className="fp-panel">
      <header className="fp-panel__header">
        <ClusterOutlined />
        服务器
        <span className="fp-panel__extra">{servers.length}</span>
      </header>

      <div className="fp-panel__section" style={{ display: 'flex', gap: 'var(--fp-space-2)' }}>
        <Select
          allowClear
          placeholder="环境"
          size="small"
          style={{ width: 92 }}
          value={envFilter}
          onChange={setEnvFilter}
          options={envs.map((e) => ({ value: e, label: e }))}
        />
        <Input.Search
          size="small"
          placeholder="搜索名称 / ID / 标签"
          allowClear
          onChange={(e) => setKeyword(e.target.value)}
        />
      </div>

      <div className="fp-panel__body">
        <List
          size="small"
          dataSource={filtered}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无服务器" /> }}
          renderItem={(s) => {
            const h = health[s.id];
            const up = h?.status === 'UP';
            return (
              <div
                className={`fp-row${s.id === selectedServer ? ' fp-row--active' : ''}`}
                style={{ padding: 'var(--fp-space-2) var(--fp-space-3)', margin: '0 var(--fp-space-1)' }}
                onClick={() => onSelectServer(s)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--fp-space-1)' }}>
                  <Badge status={up ? 'success' : 'error'} />
                  <span style={{ fontWeight: 500 }}>{s.name}</span>
                  {h?.version && (
                    <Tooltip title={`Agent ${h.version}`}>
                      <Tag bordered={false} style={{ marginLeft: 'auto' }}>
                        {h.version}
                      </Tag>
                    </Tooltip>
                  )}
                </div>
                <div className="fp-text-3" style={{ fontSize: 'var(--fp-fs-sm)', marginTop: 2 }}>
                  <span className="fp-mono">{s.id}</span>
                  {'  '}
                  {s.env && (
                    <Tag color={ENV_COLORS[s.env] ?? 'default'} bordered={false}>
                      {s.env}
                    </Tag>
                  )}
                  {s.tags.map((t) => (
                    <Tag key={t} bordered={false}>
                      {t}
                    </Tag>
                  ))}
                </div>
              </div>
            );
          }}
        />

        {selectedServer && (detail?.allowedRoots?.length ?? 0) > 0 && (
          <>
            <div className="fp-section-title">
              <FolderOpenOutlined /> 许可根路径
            </div>
            {detail!.allowedRoots.map((root) => (
              <div
                key={root}
                className="fp-row"
                style={{ padding: 'var(--fp-space-1) var(--fp-space-3)', margin: '0 var(--fp-space-1)' }}
                onClick={() => onOpenPath(selectedServer, root)}
                title={`进入 ${root}`}
              >
                <FolderOpenOutlined style={{ color: 'var(--fp-warning)', marginRight: 'var(--fp-space-2)' }} />
                <span className="fp-mono fp-ellipsis">{root}</span>
              </div>
            ))}
          </>
        )}

        {selectedServer && (
          <>
            <div className="fp-section-title">
              <StarOutlined /> 书签
            </div>
            {bookmarkItems.length === 0 ? (
              <div className="fp-text-3" style={{ padding: '0 var(--fp-space-3) var(--fp-space-3)', fontSize: 'var(--fp-fs-sm)' }}>
                暂无书签
              </div>
            ) : (
              bookmarkItems.map((b, i) => (
                <div
                  key={`${b.kind}-${b.id ?? i}`}
                  className="fp-row fp-bm-row"
                  style={{ padding: 'var(--fp-space-1) var(--fp-space-3)', margin: '0 var(--fp-space-1)', display: 'flex', alignItems: 'center', gap: 'var(--fp-space-2)' }}
                  onClick={() => onOpenPath(selectedServer, b.path)}
                >
                  <Tag color={b.kind === '全局' ? 'blue' : 'green'} bordered={false}>
                    {b.kind}
                  </Tag>
                  <Tooltip title={b.path}>
                    <span className="fp-ellipsis" style={{ flex: 1 }}>
                      {b.name}
                    </span>
                  </Tooltip>
                  {b.kind === '个人' && b.id != null && (
                    <span className="fp-bm-actions" style={{ display: 'flex', gap: 'var(--fp-space-2)' }}>
                      <Tooltip title="重命名">
                        <EditOutlined
                          className="fp-text-3"
                          onClick={(e) => {
                            e.stopPropagation();
                            onRenameBookmark({ id: b.id!, name: b.name, path: b.path });
                          }}
                        />
                      </Tooltip>
                      <Tooltip title="删除">
                        <DeleteOutlined
                          className="fp-text-3"
                          onClick={(e) => {
                            e.stopPropagation();
                            onDeleteBookmark(b.id!);
                          }}
                        />
                      </Tooltip>
                    </span>
                  )}
                </div>
              ))
            )}
          </>
        )}
      </div>
    </section>
  );
}
