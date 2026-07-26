import { useCallback, useEffect, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import {
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Tree,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  ApiOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FieldStringOutlined,
  ImportOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  TableOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { api } from '../api';
import { filterRows, nextFreeTabNo } from '../utils';
import { nextTabSeq, parseDraft, serializeDraft } from '../sqlDraft';
import type { Me, SqlDataSource, SqlExecResult } from '../types';

interface Props {
  me: Me;
}

interface TabState {
  key: string;
  title: string;
  sql: string;
  database?: string;
  maxRows: number;
  result?: SqlExecResult;
  loading: boolean;
  error?: string;
}

let tabKeySeq = 1;
// key 用单调递增序号保证唯一；标题编号取当前未占用的最小值，关闭标签后编号递补
function newTab(existing: TabState[], database?: string): TabState {
  const no = nextFreeTabNo(existing.map((t) => t.title));
  return { key: `tab-${tabKeySeq++}`, title: `查询 ${no}`, sql: '', database, maxRows: 1000, loading: false };
}

// 树节点 key 约定：db|<database> / tbl|<database>|<table> / col|<database>|<table>|<col>
function dbKey(db: string) {
  return `db|${db}`;
}
function tblKey(db: string, t: string) {
  return `tbl|${db}|${t}`;
}

export default function SqlWorkbench({ me }: Props) {
  const isAdmin = me.roles.includes('ADMIN');

  // 草稿恢复出的数据源：其下恢复/挑选的默认库本就属于该源，[selectedDs] effect 对此源不清库
  const restoredDs = useRef<number | undefined>(undefined);

  const [dataSources, setDataSources] = useState<SqlDataSource[]>([]);
  const [selectedDs, setSelectedDs] = useState<number | undefined>(undefined);
  const [treeData, setTreeData] = useState<DataNode[]>([]);
  const [databases, setDatabases] = useState<string[]>([]);
  const [treeLoading, setTreeLoading] = useState(false);

  const [tabs, setTabs] = useState<TabState[]>(() => [newTab([])]);
  const [activeKey, setActiveKey] = useState(tabs[0].key);
  const editors = useRef<Record<string, any>>({});
  // 最近一次可持久化状态，供卸载时立即保存草稿（不经防抖）
  const draftState = useRef({ tabs, activeKey, selectedDs });
  draftState.current = { tabs, activeKey, selectedDs };

  const [dsModal, setDsModal] = useState<{ open: boolean; editing?: SqlDataSource }>({ open: false });
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState('');
  const [dsForm] = Form.useForm();

  const loadDataSources = useCallback(() => {
    api
      .sqlDataSources()
      .then((list) => {
        setDataSources(list);
        // 草稿恢复的数据源可能已被删除：不在列表中时回退到第一个（列表为空则清空选择）
        if (selectedDs == null || !list.some((d) => d.id === selectedDs)) {
          setSelectedDs(list.length ? list[0].id : undefined);
        }
      })
      .catch((e) => message.error(e?.response?.data?.message ?? '加载数据源失败'));
  }, [selectedDs]);

  useEffect(() => {
    loadDataSources();
  }, []);

  // 选中数据源后加载数据库树根 + 数据库下拉
  useEffect(() => {
    // 切换数据源后旧库名在新数据源上未必存在：清空所有标签的默认库与旧元数据，防止误执行到默认库。
    // 例外：当前源即草稿恢复出的数据源时（含 StrictMode 重放），库名本就属于该源，跳过清空。
    if (selectedDs !== restoredDs.current) {
      setTabs((prev) => prev.map((t) => (t.database ? { ...t, database: undefined } : t)));
    }
    setTreeData([]);
    setDatabases([]);
    if (selectedDs == null) {
      return;
    }
    setTreeLoading(true);
    api
      .sqlDatabases(selectedDs)
      .then((dbs) => {
        setDatabases(dbs);
        setTreeData(
          dbs.map((db) => ({
            key: dbKey(db),
            title: db,
            icon: <DatabaseOutlined />,
          })),
        );
      })
      .catch((e) => {
        setTreeData([]);
        setDatabases([]);
        message.error(e?.response?.data?.message ?? '加载数据库失败');
      })
      .finally(() => setTreeLoading(false));
  }, [selectedDs]);

  // 草稿恢复流程（成功/失败/放弃）是否已完结：未完结前禁止写服务端，
  // 避免 GET 慢于防抖时初始空标签状态覆盖已存草稿
  const draftSettled = useRef(false);

  // ---- 草稿：服务端持久化（属主即当前登录用户），重登自动恢复，15 天未更新由后端清理 ----
  // 挂载后拉取草稿恢复；若用户已开始编辑（不再是初始空标签）则放弃恢复，避免覆盖输入
  useEffect(() => {
    let alive = true;
    api
      .sqlDraft()
      .then(({ content }) => {
        if (!alive) return;
        const draft = parseDraft(content);
        if (!draft) return;
        const s = draftState.current;
        const pristine = s.tabs.length === 1 && s.tabs[0].sql === '' && s.tabs[0].database == null;
        if (!pristine) return;
        // 恢复后把 key 序号推到已恢复 key 的最大值之后，避免新建标签 key 冲突
        tabKeySeq = Math.max(tabKeySeq, nextTabSeq(draft.tabs));
        restoredDs.current = draft.selectedDs; // 先记录，再放 selectedDs，[selectedDs] effect 不清库
        setSelectedDs(draft.selectedDs);
        setTabs(draft.tabs.map((t) => ({ ...t, loading: false })));
        setActiveKey(draft.activeKey);
      })
      .catch(() => {})
      .finally(() => {
        draftSettled.current = true;
      });
    return () => {
      alive = false;
    };
  }, []);

  // 标签组/激活标签/数据源变化后防抖 400ms 写服务端（失败静默，不打断使用）
  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (draftSettled.current) {
        api.saveSqlDraft(JSON.stringify(serializeDraft(selectedDs, activeKey, tabs))).catch(() => {});
      }
    }, 400);
    return () => window.clearTimeout(timer);
  }, [tabs, activeKey, selectedDs]);

  // 组件卸载（含退出登录跳转）时立即写一次，不等防抖
  useEffect(() => {
    return () => {
      if (!draftSettled.current) return;
      const s = draftState.current;
      api.saveSqlDraft(JSON.stringify(serializeDraft(s.selectedDs, s.activeKey, s.tabs))).catch(() => {});
    };
  }, []);

  const onLoadTreeData = async (node: DataNode) => {
    if (selectedDs == null) return;
    const key = String(node.key);
    const parts = key.split('|');
    try {
      if (parts[0] === 'db') {
        const db = parts[1];
        const tables = await api.sqlTables(selectedDs, db);
        updateTreeChildren(key, tables.map((t) => ({
          key: tblKey(db, t.name),
          title: (
            <span>
              {t.name}
              {t.type === 'VIEW' && (
                <Tag bordered={false} style={{ marginLeft: 'var(--fp-space-1)' }}>
                  视图
                </Tag>
              )}
            </span>
          ),
          icon: <TableOutlined />,
        })));
      } else if (parts[0] === 'tbl') {
        const db = parts[1];
        const table = parts[2];
        const cols = await api.sqlColumns(selectedDs, db, table);
        // 三层结构（DBeaver 风格）：数据库 → 表 → 字段（类型/精度显示在字段名旁）
        updateTreeChildren(key, cols.map((c) => ({
          key: `col|${db}|${table}|${c.name}`,
          title: (
            <span className="fp-mono" style={{ fontSize: 'var(--fp-fs-sm)' }}>
              {c.name}
              <span className="fp-text-3" style={{ marginLeft: 'var(--fp-space-2)' }}>
                {c.type}
                {!c.nullable && ' · NOT NULL'}
              </span>
            </span>
          ),
          icon: <FieldStringOutlined />,
          isLeaf: true,
        })));
      }
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '加载元数据失败');
      updateTreeChildren(key, []); // resolve 空 children，结束节点 loading
    }
  };

  const updateTreeChildren = (key: string, children: DataNode[]) => {
    setTreeData((prev) => {
      const walk = (nodes: DataNode[]): DataNode[] =>
        nodes.map((n) => {
          if (String(n.key) === key) return { ...n, children };
          if (n.children) return { ...n, children: walk(n.children) };
          return n;
        });
      return walk(prev);
    });
  };

  // 双击表节点：在当前标签插入 SELECT 语句
  const onTreeDoubleClick = (_: unknown, node: DataNode) => {
    const parts = String(node.key).split('|');
    if (parts[0] === 'tbl') {
      const [, db, table] = parts;
      patchActive({ sql: `SELECT * FROM \`${db}\`.\`${table}\` LIMIT 100;`, database: db });
    }
  };

  // ---- 标签页 ----
  const activeTab = tabs.find((t) => t.key === activeKey) ?? tabs[0];

  const patchActive = (patch: Partial<TabState>) => {
    setTabs((prev) => prev.map((t) => (t.key === activeKey ? { ...t, ...patch } : t)));
  };
  const patchTab = (key: string, patch: Partial<TabState>) => {
    setTabs((prev) => prev.map((t) => (t.key === key ? { ...t, ...patch } : t)));
  };

  const addTab = () => {
    const t = newTab(tabs, activeTab?.database);
    setTabs((prev) => [...prev, t]);
    setActiveKey(t.key);
  };

  const removeTab = (targetKey: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((t) => t.key === targetKey);
      const next = prev.filter((t) => t.key !== targetKey);
      if (next.length === 0) {
        const t = newTab([]);
        setActiveKey(t.key);
        return [t];
      }
      if (targetKey === activeKey) {
        setActiveKey(next[Math.max(0, idx - 1)].key);
      }
      delete editors.current[targetKey];
      return next;
    });
  };

  const runTab = async (tab: TabState) => {
    if (selectedDs == null) {
      message.warning('请先选择数据源');
      return;
    }
    const editor = editors.current[tab.key];
    let sql = tab.sql;
    if (editor) {
      const sel = editor.getModel()?.getValueInRange(editor.getSelection());
      if (sel && sel.trim()) sql = sel; // 有选中则执行选中片段
    }
    if (!sql.trim()) {
      message.warning('请输入要执行的 SQL');
      return;
    }
    patchTab(tab.key, { loading: true, error: undefined });
    try {
      const r = await api.sqlExecute(selectedDs, { database: tab.database, sql, maxRows: tab.maxRows });
      patchTab(tab.key, { result: r, loading: false });
    } catch (e: any) {
      patchTab(tab.key, { error: e?.response?.data?.message ?? '执行失败', result: undefined, loading: false });
    }
  };

  const exportCsv = (tab: TabState) => {
    const r = tab.result;
    if (!r || !r.columns || !r.rows) {
      message.warning('没有可导出的结果集');
      return;
    }
    const esc = (v: string | null) => {
      const s = v == null ? '' : String(v);
      return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lines = [r.columns.map(esc).join(','), ...r.rows.map((row) => row.map(esc).join(','))];
    const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${tab.title}-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // ---- 数据源管理 ----
  const openCreateDs = () => {
    dsForm.resetFields();
    dsForm.setFieldsValue({ port: 3306, dbType: 'mysql' });
    setDsModal({ open: true });
  };
  const openEditDs = (ds: SqlDataSource) => {
    dsForm.resetFields();
    dsForm.setFieldsValue({ ...ds, password: '' });
    setDsModal({ open: true, editing: ds });
  };

  const saveDs = async () => {
    const values = await dsForm.validateFields();
    try {
      if (dsModal.editing) {
        await api.updateSqlDataSource(dsModal.editing.id, values);
        message.success('数据源已更新');
      } else {
        await api.createSqlDataSource(values);
        message.success('数据源已创建');
      }
      setDsModal({ open: false });
      loadDataSources();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '保存失败');
    }
  };

  const deleteDs = (ds: SqlDataSource) => {
    Modal.confirm({
      title: '删除数据源',
      content: `确定删除数据源「${ds.name}」？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.deleteSqlDataSource(ds.id);
          message.success('已删除');
          if (selectedDs === ds.id) setSelectedDs(undefined);
          loadDataSources();
        } catch (e: any) {
          message.error(e?.response?.data?.message ?? '删除失败');
        }
      },
    });
  };

  const testDs = async (ds: SqlDataSource) => {
    const hide = message.loading('正在测试连接…', 0);
    try {
      await api.testSqlDataSource(ds.id);
      hide();
      message.success('连接成功');
    } catch (e: any) {
      hide();
      message.error(e?.response?.data?.message ?? '连接失败');
    }
  };

  const doImport = async () => {
    let items: any[];
    try {
      items = JSON.parse(importText);
      if (!Array.isArray(items)) throw new Error();
    } catch {
      message.error('请粘贴合法的 JSON 数组');
      return;
    }
    try {
      const r = await api.importSqlDataSources(items);
      message.success(`导入完成：成功 ${r.imported} / 共 ${r.total}`);
      setImportOpen(false);
      setImportText('');
      loadDataSources();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '导入失败');
    }
  };

  return (
    <div className="fp-body">
      {/* 左：数据源与数据库树 */}
      <div className="fp-col-left">
        <section className="fp-panel">
          <header className="fp-panel__header">
            <DatabaseOutlined />
            数据源
            <span className="fp-panel__extra">
              <Space size={4}>
                <Tooltip title="刷新">
                  <Button size="small" type="text" icon={<ReloadOutlined />} onClick={loadDataSources} />
                </Tooltip>
                {isAdmin && (
                  <>
                    <Tooltip title="批量导入">
                      <Button size="small" type="text" icon={<ImportOutlined />} onClick={() => setImportOpen(true)} />
                    </Tooltip>
                    <Tooltip title="新增数据源">
                      <Button size="small" type="text" icon={<PlusOutlined />} onClick={openCreateDs} />
                    </Tooltip>
                  </>
                )}
              </Space>
            </span>
          </header>
          <div className="fp-toolbar">
            <Select
              size="small"
              style={{ width: '100%' }}
              placeholder="选择数据源（可输入关键字过滤）"
              value={selectedDs}
              onChange={setSelectedDs}
              showSearch
              optionFilterProp="label"
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={dataSources.map((d) => ({ value: d.id, label: `${d.name} (${d.host}:${d.port})` }))}
            />
          </div>
          {isAdmin && selectedDs != null && (
            <div className="fp-toolbar fp-toolbar--subtle">
              <Tooltip title="测试连接">
                <Button
                  size="small"
                  icon={<ApiOutlined />}
                  onClick={() => {
                    const ds = dataSources.find((d) => d.id === selectedDs);
                    if (ds) testDs(ds);
                  }}
                >
                  测试
                </Button>
              </Tooltip>
              <Tooltip title="编辑数据源">
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    const ds = dataSources.find((d) => d.id === selectedDs);
                    if (ds) openEditDs(ds);
                  }}
                >
                  编辑
                </Button>
              </Tooltip>
              <Tooltip title="删除数据源">
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => {
                    const ds = dataSources.find((d) => d.id === selectedDs);
                    if (ds) deleteDs(ds);
                  }}
                >
                  删除
                </Button>
              </Tooltip>
            </div>
          )}
          <div className="fp-panel__body fp-scroll" style={{ overflow: 'auto' }}>
            {selectedDs == null ? (
              <Empty description="选择或新增数据源" style={{ marginTop: 'var(--fp-space-6)' }} />
            ) : treeData.length === 0 && !treeLoading ? (
              <Empty description="无数据库" style={{ marginTop: 'var(--fp-space-6)' }} />
            ) : (
              <div style={{ minWidth: 'max-content', padding: 'var(--fp-space-2)' }}>
                <Tree
                  showIcon
                  blockNode
                  loadData={onLoadTreeData}
                  treeData={treeData}
                  onDoubleClick={onTreeDoubleClick}
                />
              </div>
            )}
          </div>
        </section>
      </div>

      {/* 右：多标签查询与结果（minWidth:0 是关键：约束 flex 链宽度，结果表格才能在内部横向滚动） */}
      <div className="fp-col-center" style={{ flex: 1, minWidth: 0 }}>
        <section className="fp-panel">
          <header className="fp-panel__header">
            <ThunderboltOutlined />
            查询编辑器
            <span className="fp-panel__extra">
              <Button size="small" icon={<PlusOutlined />} onClick={addTab}>
                新建查询
              </Button>
            </span>
          </header>
          <div className="fp-sql-tabs">
            {tabs.map((t) => (
              <div
                key={t.key}
                className={`fp-sql-tab${t.key === activeKey ? ' fp-sql-tab--active' : ''}`}
                onClick={() => setActiveKey(t.key)}
              >
                <span>{t.title}</span>
                <DeleteOutlined
                  className="fp-sql-tab__close"
                  onClick={(e) => {
                    e.stopPropagation();
                    removeTab(t.key);
                  }}
                />
              </div>
            ))}
          </div>

          {tabs.map((tab) => (
            <div key={tab.key} style={{ display: tab.key === activeKey ? 'flex' : 'none', flex: 1, flexDirection: 'column', minHeight: 0 }}>
              <div className="fp-toolbar">
                <Select
                  size="small"
                  style={{ width: 200 }}
                  placeholder="默认数据库"
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  value={tab.database}
                  onChange={(v) => patchTab(tab.key, { database: v })}
                  options={databases.map((d) => ({ value: d, label: d }))}
                />
                <Tooltip title="最大返回行数">
                  <InputNumber
                    size="small"
                    min={1}
                    max={100000}
                    step={100}
                    value={tab.maxRows}
                    onChange={(v) => patchTab(tab.key, { maxRows: v ?? 1000 })}
                    addonAfter="行"
                    style={{ width: 140 }}
                  />
                </Tooltip>
                <Button
                  size="small"
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  loading={tab.loading}
                  onClick={() => runTab(tab)}
                >
                  执行（选中则执行选中）
                </Button>
                <Button
                  size="small"
                  icon={<DownloadOutlined />}
                  disabled={!tab.result?.rows?.length}
                  onClick={() => exportCsv(tab)}
                >
                  导出 CSV
                </Button>
              </div>

              <div style={{ flex: 1, minHeight: 0, borderBottom: '1px solid var(--fp-border)' }}>
                <Editor
                  height="100%"
                  language="sql"
                  value={tab.sql}
                  onChange={(v) => patchTab(tab.key, { sql: v ?? '' })}
                  onMount={(editor) => {
                    editors.current[tab.key] = editor;
                  }}
                  options={{
                    minimap: { enabled: false },
                    fontSize: 13,
                    fontFamily: 'var(--fp-mono)',
                    scrollBeyondLastLine: false,
                    automaticLayout: true,
                    fixedOverflowWidgets: true,
                  }}
                />
              </div>

              <div className="fp-panel__body fp-scroll" style={{ overflowX: 'hidden', overflowY: 'auto', flex: 1 }}>
                <SqlResult tab={tab} />
              </div>
            </div>
          ))}
        </section>
      </div>

      {/* 数据源新增/编辑 */}
      <Modal
        open={dsModal.open}
        title={dsModal.editing ? '编辑数据源' : '新增数据源'}
        okText="保存"
        cancelText="取消"
        onOk={saveDs}
        onCancel={() => setDsModal({ open: false })}
        destroyOnClose
      >
        <Form form={dsForm} layout="vertical">
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 生产库-订单" maxLength={128} />
          </Form.Item>
          <Space.Compact block>
            <Form.Item label="主机" name="host" rules={[{ required: true, message: '请输入主机' }]} style={{ flex: 1 }}>
              <Input placeholder="10.0.0.1" className="fp-mono" />
            </Form.Item>
            <Form.Item label="端口" name="port" style={{ width: 120, marginLeft: 'var(--fp-space-2)' }}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Form.Item label="默认数据库" name="defaultDatabase">
            <Input placeholder="可留空" className="fp-mono" />
          </Form.Item>
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input className="fp-mono" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={dsModal.editing ? [] : [{ required: true, message: '请输入密码' }]}
            extra={dsModal.editing ? '留空表示不修改密码' : undefined}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item label="连接参数" name="params" extra="可留空使用默认参数">
            <Input placeholder="useSSL=false&characterEncoding=utf8" className="fp-mono" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 批量导入 */}
      <Modal
        open={importOpen}
        title="批量导入数据源"
        okText="导入"
        cancelText="取消"
        onOk={doImport}
        onCancel={() => setImportOpen(false)}
        width={640}
      >
        <p className="fp-text-2">粘贴 JSON 数组，字段：name, host, port, defaultDatabase, username, password, params。重名将自动跳过。</p>
        <Input.TextArea
          rows={12}
          value={importText}
          onChange={(e) => setImportText(e.target.value)}
          className="fp-mono"
          placeholder={'[\n  {"name":"库A","host":"10.0.0.1","port":3306,"username":"root","password":"***"}\n]'}
        />
      </Modal>
    </div>
  );
}

function SqlResult({ tab }: { tab: TabState }) {
  const [kw, setKw] = useState('');
  if (tab.error) {
    return <Empty description={<span className="fp-text-2">{tab.error}</span>} style={{ marginTop: 'var(--fp-space-6)' }} />;
  }
  const r = tab.result;
  if (!r) {
    return <Empty description="执行 SQL 查看结果" style={{ marginTop: 'var(--fp-space-6)' }} />;
  }
  if (r.updateCount >= 0) {
    return (
      <div style={{ padding: 'var(--fp-space-3)' }} className="fp-text-2">
        执行成功，影响 {r.updateCount} 行 · 耗时 {r.elapsedMs} ms
      </div>
    );
  }
  const COL_WIDTH = 180;
  const columns = (r.columns ?? []).map((c, i) => ({
    title: c,
    dataIndex: String(i),
    key: String(i),
    ellipsis: true,
    width: COL_WIDTH,
    render: (v: string | null) => (v == null ? <span className="fp-text-3">NULL</span> : <span className="fp-mono">{v}</span>),
  }));
  // 客户端筛选（DbVisualizer 风格）：关键字模糊匹配所有列
  const matchedRows = filterRows(r.rows ?? [], kw);
  const filtering = kw.trim() !== '';
  const dataSource = matchedRows.map((row, ri) => {
    const obj: Record<string, string | null> = { key: String(ri) } as any;
    row.forEach((v, ci) => (obj[String(ci)] = v));
    return obj;
  });
  return (
    <div>
      <div className="fp-toolbar fp-toolbar--subtle">
        <Tag bordered={false}>{filtering ? `${matchedRows.length} / ${r.rowCount} 行` : `${r.rowCount} 行`}</Tag>
        <span className="fp-text-2">耗时 {r.elapsedMs} ms</span>
        {r.truncated && (
          <Tag color="orange" bordered={false}>
            结果超限，已截断
          </Tag>
        )}
        <Input
          allowClear
          size="small"
          prefix={<SearchOutlined className="fp-text-3" />}
          placeholder="筛选结果（匹配所有列）"
          value={kw}
          onChange={(e) => setKw(e.target.value)}
          style={{ width: 220, marginLeft: 'auto' }}
        />
      </div>
      <Table
        size="small"
        columns={columns}
        dataSource={dataSource}
        pagination={{ defaultPageSize: 100, size: 'small', showSizeChanger: true }}
        scroll={{ x: Math.max(columns.length * COL_WIDTH, 320) }}
        bordered
      />
    </div>
  );
}
