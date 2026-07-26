import { useMemo, useState } from 'react';
import type { UIEvent } from 'react';
import { Breadcrumb, Button, Empty, Input, Select, Switch, Table, Tag, Tooltip, message } from 'antd';
import {
  CopyOutlined,
  DownOutlined,
  FileOutlined,
  FolderFilled,
  FolderOpenOutlined,
  ReloadOutlined,
  StarOutlined,
} from '@ant-design/icons';
import type { FileEntry, ListResult } from '../types';
import { humanBytes } from '../utils';

interface Props {
  serverId?: string;
  listing?: ListResult;
  loading: boolean;
  moreLoading: boolean;
  showHidden: boolean;
  pageSize: number;
  onToggleHidden: (v: boolean) => void;
  onPageSizeChange: (n: number) => void;
  onLoadMore: () => void;
  onNavigate: (path: string) => void;
  onOpenFile: (entry: FileEntry) => void;
  onRefresh: () => void;
  onBookmark: (path: string) => void;
}

const PAGE_SIZES = [50, 100, 200, 500];

export default function FileList({
  serverId,
  listing,
  loading,
  moreLoading,
  showHidden,
  pageSize,
  onToggleHidden,
  onPageSizeChange,
  onLoadMore,
  onNavigate,
  onOpenFile,
  onRefresh,
  onBookmark,
}: Props) {
  const [filter, setFilter] = useState('');

  const segments = useMemo(() => {
    const path = listing?.path ?? '/';
    const parts = path.split('/').filter(Boolean);
    const crumbs: { label: string; path: string }[] = [{ label: '/', path: '/' }];
    let acc = '';
    for (const p of parts) {
      acc += '/' + p;
      crumbs.push({ label: p, path: acc });
    }
    return crumbs;
  }, [listing?.path]);

  const allEntries = listing?.entries ?? [];
  const entries = filter
    ? allEntries.filter((e) => e.name.toLowerCase().includes(filter.toLowerCase()))
    : allEntries;
  const hasMore = !!listing?.hasMore;

  // 懒加载：滚动接近底部时加载下一页
  const onScroll = (e: UIEvent<HTMLDivElement>) => {
    if (!hasMore || moreLoading || loading || filter) return;
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) {
      onLoadMore();
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      ellipsis: true,
      sorter: (a: FileEntry, b: FileEntry) => a.name.localeCompare(b.name),
      render: (_: string, e: FileEntry) => (
        <span className="fp-clickable" onClick={() => (e.type === 'dir' ? onNavigate(e.path) : onOpenFile(e))}>
          {e.type === 'dir' ? (
            <FolderFilled style={{ color: 'var(--fp-warning)', marginRight: 6 }} />
          ) : (
            <FileOutlined className="fp-text-3" style={{ marginRight: 6 }} />
          )}
          {e.name}
          {e.type === 'symlink' && (
            <Tag bordered={false} style={{ marginLeft: 6 }}>
              链接
            </Tag>
          )}
        </span>
      ),
    },
    {
      title: '大小',
      dataIndex: 'size',
      width: 96,
      align: 'right' as const,
      sorter: (a: FileEntry, b: FileEntry) => a.size - b.size,
      render: (v: number, e: FileEntry) =>
        e.type === 'dir' ? <span className="fp-text-3">—</span> : <span className="fp-mono">{humanBytes(v)}</span>,
    },
    {
      title: '权限',
      dataIndex: 'mode',
      width: 124,
      render: (v: string) => <span className="fp-mono fp-text-2">{v}</span>,
    },
    { title: '属主', dataIndex: 'owner', width: 90, ellipsis: true },
    {
      title: '修改时间',
      dataIndex: 'modifiedAt',
      width: 176,
      sorter: (a: FileEntry, b: FileEntry) => a.modifiedAt.localeCompare(b.modifiedAt),
      render: (v: string) => <span className="fp-mono fp-text-2">{v?.replace('T', ' ').slice(0, 19)}</span>,
    },
    {
      title: '',
      width: 40,
      render: (_: unknown, e: FileEntry) => (
        <Tooltip title="复制完整路径">
          <CopyOutlined
            className="fp-text-3 fp-clickable"
            onClick={() => {
              if (!navigator.clipboard) {
                message.warning('当前环境不支持自动复制（需 HTTPS 或 localhost），请手动复制路径');
                return;
              }
              navigator.clipboard
                .writeText(e.path)
                .then(() => message.success('已复制路径'))
                .catch(() => message.error('复制失败，请手动复制路径'));
            }}
          />
        </Tooltip>
      ),
    },
  ];

  return (
    <section className="fp-panel">
      <header className="fp-panel__header">
        <FolderOpenOutlined />
        目录
        {listing && (
          <span className="fp-panel__extra">
            已加载 {allEntries.length}
            {hasMore ? '+' : ''} 项
          </span>
        )}
      </header>

      {!serverId ? (
        <div className="fp-panel__body" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Empty description="请选择左侧服务器" />
        </div>
      ) : (
        <>
          <div className="fp-breadcrumb">
            <Breadcrumb
              items={segments.map((c) => ({
                title: (
                  <a className="fp-mono" onClick={() => onNavigate(c.path)}>
                    {c.label}
                  </a>
                ),
              }))}
            />
          </div>
          <div className="fp-toolbar">
            <Input.Search
              size="small"
              placeholder="跳转到绝对路径，如 /data/logs"
              allowClear
              enterButton
              style={{ width: 260 }}
              className="fp-mono"
              onSearch={(v) => {
                const p = v.trim();
                if (p) onNavigate(p);
              }}
            />
            <Input
              size="small"
              placeholder="过滤已加载项"
              allowClear
              style={{ width: 150 }}
              onChange={(e) => setFilter(e.target.value)}
            />
            <span className="fp-text-2">
              隐藏文件 <Switch size="small" checked={showHidden} onChange={onToggleHidden} />
            </span>
            <span className="fp-text-2">
              每页
              <Select
                size="small"
                style={{ width: 84, marginLeft: 6 }}
                value={pageSize}
                onChange={onPageSizeChange}
                options={PAGE_SIZES.map((n) => ({ value: n, label: n }))}
              />
            </span>
            <Button size="small" icon={<ReloadOutlined />} onClick={onRefresh}>
              刷新
            </Button>
            {listing && (
              <Button size="small" icon={<StarOutlined />} onClick={() => onBookmark(listing.path)}>
                收藏当前目录
              </Button>
            )}
          </div>
          <div className="fp-list" onScroll={onScroll}>
            <Table
              size="small"
              rowKey="path"
              loading={loading}
              columns={columns as any}
              dataSource={entries}
              pagination={false}
              onRow={(e) => ({
                onDoubleClick: () => (e.type === 'dir' ? onNavigate(e.path) : onOpenFile(e)),
              })}
            />
            <div style={{ textAlign: 'center', padding: 'var(--fp-space-3)' }}>
              {filter ? (
                <span className="fp-text-3">过滤仅作用于已加载项</span>
              ) : hasMore ? (
                <Button size="small" icon={<DownOutlined />} loading={moreLoading} onClick={onLoadMore}>
                  加载更多（每页 {pageSize}）
                </Button>
              ) : (
                listing && allEntries.length > 0 && <span className="fp-text-3">已全部加载</span>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
}
