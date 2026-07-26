import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Form, Input, Modal, Space, Spin, Tag, message } from 'antd';
import {
  CloudServerOutlined,
  CloudSyncOutlined,
  ConsoleSqlOutlined,
  FolderOpenOutlined,
  LogoutOutlined,
  SettingOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import LoginPage from './components/LoginPage';
import ServerPanel from './components/ServerPanel';
import FileList from './components/FileList';
import PreviewPane from './components/PreviewPane';
import PreviewTabs from './components/PreviewTabs';
import AdminServers from './components/AdminServers';
import SyncManager from './components/SyncManager';
import UserManager from './components/UserManager';
import SqlWorkbench from './components/SqlWorkbench';
import { api } from './api';
import type { Bookmark, FileEntry, ListResult, Me, ServerSummary } from './types';
import { tabNeighbor } from './utils';

const ROLE_COLORS: Record<string, string> = {
  ADMIN: 'red',
  OPERATOR: 'blue',
  AUDITOR: 'gold',
};

// 预览缓存提醒阈值：超过仅在标签行下方提示，不强制清理
const PREVIEW_CACHE_WARN_BYTES = 32 * 1024 * 1024;

interface SessionState {
  path: string;
  file?: FileEntry;
  tabs?: FileEntry[];
}

interface BmModalState {
  open: boolean;
  mode: 'create' | 'rename';
  id?: number;
  name: string;
  path: string;
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [booting, setBooting] = useState(true);
  const [view, setView] = useState<'files' | 'sql'>('files');

  const [servers, setServers] = useState<ServerSummary[]>([]);
  const [selectedServer, setSelectedServer] = useState<ServerSummary>();
  const [listing, setListing] = useState<ListResult>();
  const [listLoading, setListLoading] = useState(false);
  const [moreLoading, setMoreLoading] = useState(false);
  const [showHidden, setShowHidden] = useState(false);
  const [pageSize, setPageSize] = useState(100);
  const [selectedFile, setSelectedFile] = useState<FileEntry>();
  // 当前服务器打开的文件标签（按打开顺序）
  const [openTabs, setOpenTabs] = useState<FileEntry[]>([]);
  // 全部标签预览缓存的估算字节数（由 PreviewPane 上报）
  const [cacheBytes, setCacheBytes] = useState(0);
  const [bookmarks, setBookmarks] = useState<Bookmark[]>([]);
  // 当前标签路径列表（memo 保持引用稳定，供 PreviewPane 缓存剪枝）
  const tabPaths = useMemo(() => openTabs.map((t) => t.path), [openTabs]);

  const [adminOpen, setAdminOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const [userOpen, setUserOpen] = useState(false);
  const [bm, setBm] = useState<BmModalState>({ open: false, mode: 'create', name: '', path: '' });

  // 记忆每台服务器上次浏览的目录与预览文件
  const sessions = useRef<Record<string, SessionState>>({});
  // 目录导航序号：navigate 自增，await 落地前比对，丢弃切服务器/目录后的过期响应
  const navSeq = useRef(0);
  // 同步守卫，避免高频滚动事件在异步 state 生效前触发重复请求
  const moreLoadingLock = useRef(false);

  useEffect(() => {
    api.me().then(setMe).catch(() => setMe(null)).finally(() => setBooting(false));
  }, []);

  const refreshBookmarks = useCallback(() => {
    api.bookmarks().then(setBookmarks).catch(() => setBookmarks([]));
  }, []);

  const loadServers = useCallback(() => {
    api.servers().then(setServers).catch(() => setServers([]));
    refreshBookmarks();
  }, [refreshBookmarks]);

  useEffect(() => {
    if (me) loadServers();
  }, [me, loadServers]);

  // 加载某目录的第一页
  const navigate = useCallback(
    async (serverId: string, path: string) => {
      const seq = ++navSeq.current;
      setListLoading(true);
      try {
        const r = await api.list(serverId, path, showHidden, 'name', 'asc', 0, pageSize);
        if (seq !== navSeq.current) return; // 已有更新的导航，丢弃过期响应
        setListing(r);
        sessions.current[serverId] = { ...(sessions.current[serverId] ?? {}), path: r.path };
      } catch (e: any) {
        if (seq === navSeq.current) message.error(e?.response?.data?.message ?? '加载目录失败');
      } finally {
        if (seq === navSeq.current) setListLoading(false);
      }
    },
    [showHidden, pageSize],
  );

  // 懒加载下一页并追加
  const loadMore = useCallback(async () => {
    if (!selectedServer || !listing || !listing.hasMore || moreLoading) return;
    if (moreLoadingLock.current) return;
    moreLoadingLock.current = true;
    setMoreLoading(true);
    const seq = navSeq.current;
    try {
      const r = await api.list(selectedServer.id, listing.path, showHidden, 'name', 'asc', listing.entries.length, pageSize);
      if (seq !== navSeq.current) return; // 目录已切换，丢弃过期响应
      // path 变了说明当前列表已是另一个目录，直接丢弃
      setListing((prev) => (prev && prev.path === listing.path ? { ...r, entries: [...prev.entries, ...r.entries] } : prev));
    } catch (e: any) {
      if (seq === navSeq.current) message.error(e?.response?.data?.message ?? '加载更多失败');
    } finally {
      setMoreLoading(false);
      moreLoadingLock.current = false;
    }
  }, [selectedServer, listing, moreLoading, showHidden, pageSize]);

  // 统一同步当前服务器的会话状态（目录 / 激活文件 / 标签组）
  const syncSession = (patch: Partial<SessionState>) => {
    if (!selectedServer) return;
    const prev = sessions.current[selectedServer.id];
    sessions.current[selectedServer.id] = {
      ...prev,
      ...patch,
      path: patch.path ?? prev?.path ?? listing?.path ?? '',
    };
  };

  // 打开文件：按 path 去重加入标签尾部并激活
  const openFile = (f: FileEntry) => {
    setSelectedFile(f);
    const tabs = openTabs.some((t) => t.path === f.path) ? openTabs : [...openTabs, f];
    setOpenTabs(tabs);
    syncSession({ file: f, tabs });
  };

  // 关闭标签：关的是激活标签时激活左邻居（无则右邻居），其缓存由 PreviewPane 剪枝释放
  const closeTab = (path: string) => {
    const tabs = openTabs.filter((t) => t.path !== path);
    const next = tabNeighbor(openTabs.map((t) => t.path), path, selectedFile?.path);
    const file = tabs.find((t) => t.path === next);
    setOpenTabs(tabs);
    setSelectedFile(file);
    syncSession({ file, tabs });
  };

  // 切换标签：仅激活，不重载（预览状态由 PreviewPane 缓存恢复）
  const selectTab = (f: FileEntry) => {
    setSelectedFile(f);
    syncSession({ file: f });
  };

  // 关闭最左侧非激活标签（缓存告警时手动释放）
  const closeOldestTab = () => {
    const oldest = openTabs.find((t) => t.path !== selectedFile?.path);
    if (oldest) closeTab(oldest.path);
  };

  const onSelectServer = async (server: ServerSummary) => {
    navSeq.current++; // 使在途的旧目录请求失效
    setSelectedServer(server);
    setListing(undefined); // 清空旧服务器条目，避免加载期间误点旧路径
    const s = sessions.current[server.id];
    setOpenTabs(s?.tabs ?? []); // 恢复该服务器的标签组
    if (s?.path) {
      setSelectedFile(s.file);
      await navigate(server.id, s.path);
    } else {
      setSelectedFile(undefined);
      await navigate(server.id, server.defaultRoot || '/');
    }
  };

  const onOpenPath = async (serverId: string, path: string) => {
    const server = servers.find((s) => s.id === serverId);
    if (server) setSelectedServer(server);
    setSelectedFile(undefined); // 清激活文件但保留标签
    if (sessions.current[serverId]) sessions.current[serverId].file = undefined;
    // 书签跳转可能切到别的服务器，恢复其标签组（同服务器即当前标签）
    setOpenTabs(sessions.current[serverId]?.tabs ?? []);
    await navigate(serverId, path);
  };

  const onNavigateDir = (path: string) => {
    if (!selectedServer) return;
    setSelectedFile(undefined); // 清激活文件但保留标签
    if (sessions.current[selectedServer.id]) sessions.current[selectedServer.id].file = undefined;
    navigate(selectedServer.id, path);
  };

  const changePageSize = (n: number) => {
    setPageSize(n);
    if (selectedServer && listing) {
      // 用新分页大小重载当前目录
      const seq = navSeq.current;
      setListLoading(true);
      api
        .list(selectedServer.id, listing.path, showHidden, 'name', 'asc', 0, n)
        .then((r) => {
          if (seq === navSeq.current) setListing(r);
        })
        .catch((e) => {
          if (seq === navSeq.current) message.error(e?.response?.data?.message ?? '加载失败');
        })
        .finally(() => {
          if (seq === navSeq.current) setListLoading(false);
        });
    }
  };

  const toggleHidden = (v: boolean) => {
    setShowHidden(v);
    if (selectedServer && listing) {
      const seq = navSeq.current;
      setListLoading(true);
      api
        .list(selectedServer.id, listing.path, v, 'name', 'asc', 0, pageSize)
        .then((r) => {
          if (seq === navSeq.current) setListing(r);
        })
        .catch((e) => {
          if (seq === navSeq.current) message.error(e?.response?.data?.message ?? '加载失败');
        })
        .finally(() => {
          if (seq === navSeq.current) setListLoading(false);
        });
    }
  };

  // ---- 书签：新增/重命名/删除 ----
  const openCreateBookmark = (path: string) => {
    if (!selectedServer) return;
    const name = path.split('/').filter(Boolean).pop() || path;
    setBm({ open: true, mode: 'create', name, path });
  };

  const openRenameBookmark = (b: { id: number; name: string; path: string }) => {
    setBm({ open: true, mode: 'rename', id: b.id, name: b.name, path: b.path });
  };

  const saveBookmark = async (values: { name: string }) => {
    if (!selectedServer) return;
    try {
      if (bm.mode === 'create') {
        await api.createBookmark({
          serverId: selectedServer.id,
          name: values.name,
          path: bm.path,
          scopeType: 'personal',
        });
        message.success('已加入个人书签');
      } else if (bm.id != null) {
        await api.updateBookmark(bm.id, {
          serverId: selectedServer.id,
          name: values.name,
          path: bm.path,
          scopeType: 'personal',
          sortOrder: 0,
        });
        message.success('书签已更新');
      }
      setBm((prev) => ({ ...prev, open: false }));
      refreshBookmarks();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '操作失败');
    }
  };

  const deleteBookmark = (id: number) => {
    Modal.confirm({
      title: '删除书签',
      content: '确定删除该个人书签？',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.deleteBookmark(id);
          message.success('已删除');
          refreshBookmarks();
        } catch (e: any) {
          message.error(e?.response?.data?.message ?? '删除失败');
        }
      },
    });
  };

  const logout = async () => {
    await api.logout();
    setMe(null);
    setServers([]);
    setSelectedServer(undefined);
    setListing(undefined);
    setSelectedFile(undefined);
    setOpenTabs([]);
    setCacheBytes(0);
    sessions.current = {};
  };

  if (booting) {
    return (
      <div className="fp-login">
        <Spin size="large" />
      </div>
    );
  }

  if (!me) {
    return <LoginPage onLoggedIn={setMe} />;
  }

  const isAdmin = me.roles.includes('ADMIN');
  const canSql = isAdmin || me.roles.includes('OPERATOR');

  return (
    <div className="fp-layout">
      <header className="fp-header">
        <div className="fp-header__left">
          <div className="fp-brand">
            <CloudServerOutlined className="fp-brand__logo" />
            平台运维助手
          </div>
          <nav className="fp-nav">
            <div
              className={`fp-nav__item${view === 'files' ? ' fp-nav__item--active' : ''}`}
              onClick={() => setView('files')}
            >
              <FolderOpenOutlined />
              文件预览
            </div>
            {canSql && (
              <div
                className={`fp-nav__item${view === 'sql' ? ' fp-nav__item--active' : ''}`}
                onClick={() => setView('sql')}
              >
                <ConsoleSqlOutlined />
                SQL 工作台
              </div>
            )}
          </nav>
        </div>
        <Space size="middle">
          <span className="fp-user">
            {me.displayName || me.username}
            {me.roles.map((r) => (
              <Tag key={r} color={ROLE_COLORS[r] ?? 'default'} style={{ marginLeft: 8 }}>
                {r}
              </Tag>
            ))}
          </span>
          {isAdmin && (
            <>
              <Button size="small" icon={<TeamOutlined />} onClick={() => setUserOpen(true)}>
                用户管理
              </Button>
              <Button size="small" icon={<CloudSyncOutlined />} onClick={() => setSyncOpen(true)}>
                主备同步
              </Button>
              <Button size="small" icon={<SettingOutlined />} onClick={() => setAdminOpen(true)}>
                服务器管理
              </Button>
            </>
          )}
          <Button size="small" icon={<LogoutOutlined />} onClick={logout}>
            退出
          </Button>
        </Space>
      </header>

      {canSql && (
        <div
          className="fp-view"
          style={{ display: view === 'sql' ? 'flex' : 'none', flex: 1, minHeight: 0 }}
        >
          <SqlWorkbench me={me} />
        </div>
      )}
      <div className="fp-body" style={{ display: view === 'files' ? 'flex' : 'none' }}>
        <div className="fp-col-left">
          <ServerPanel
            servers={servers}
            selectedServer={selectedServer?.id}
            personalBookmarks={bookmarks}
            onSelectServer={onSelectServer}
            onOpenPath={onOpenPath}
            onRenameBookmark={openRenameBookmark}
            onDeleteBookmark={deleteBookmark}
          />
        </div>
        <div className="fp-col-center">
          <FileList
            serverId={selectedServer?.id}
            listing={listing}
            loading={listLoading}
            moreLoading={moreLoading}
            showHidden={showHidden}
            pageSize={pageSize}
            onToggleHidden={toggleHidden}
            onPageSizeChange={changePageSize}
            onLoadMore={loadMore}
            onNavigate={onNavigateDir}
            onOpenFile={openFile}
            onRefresh={() => selectedServer && listing && navigate(selectedServer.id, listing.path)}
            onBookmark={openCreateBookmark}
          />
        </div>
        <div className="fp-col-right">
          <PreviewTabs
            tabs={openTabs}
            activePath={selectedFile?.path}
            cacheBytes={cacheBytes}
            warnBytes={PREVIEW_CACHE_WARN_BYTES}
            onSelect={selectTab}
            onClose={closeTab}
            onCloseOldest={closeOldestTab}
          />
          <PreviewPane
            serverId={selectedServer?.id}
            file={selectedFile}
            tabPaths={tabPaths}
            onCacheBytes={setCacheBytes}
          />
        </div>
      </div>

      <AdminServers open={adminOpen} onClose={() => setAdminOpen(false)} />
      <SyncManager open={syncOpen} onClose={() => setSyncOpen(false)} />
      <UserManager open={userOpen} onClose={() => setUserOpen(false)} currentUserId={me.id} />

      <Modal
        open={bm.open}
        title={bm.mode === 'create' ? '收藏路径' : '重命名书签'}
        okText="保存"
        cancelText="取消"
        destroyOnClose
        onCancel={() => setBm((prev) => ({ ...prev, open: false }))}
        footer={null}
      >
        <Form layout="vertical" initialValues={{ name: bm.name }} onFinish={saveBookmark} key={`${bm.mode}-${bm.id ?? 'new'}-${bm.name}`}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入书签名称' }]}>
            <Input autoFocus placeholder="书签名称" maxLength={64} />
          </Form.Item>
          <Form.Item label="路径">
            <Input value={bm.path} readOnly className="fp-mono" />
          </Form.Item>
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setBm((prev) => ({ ...prev, open: false }))}>取消</Button>
              <Button type="primary" htmlType="submit">
                保存
              </Button>
            </Space>
          </div>
        </Form>
      </Modal>
    </div>
  );
}
