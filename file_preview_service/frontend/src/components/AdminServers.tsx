import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, Descriptions, Modal, Space, Table, Tabs, Tag, message } from 'antd';
import { ReloadOutlined, SaveOutlined, SyncOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import { api } from '../api';
import type { AdminServerStatus, ServerConfig } from '../types';

interface Props {
  open: boolean;
  onClose: () => void;
}

const ENV_COLORS: Record<string, string> = {
  prod: 'red',
  test: 'blue',
  staging: 'gold',
};

export default function AdminServers({ open, onClose }: Props) {
  const [servers, setServers] = useState<ServerConfig[]>([]);
  const [statuses, setStatuses] = useState<Record<string, AdminServerStatus>>({});
  const [loading, setLoading] = useState(false);

  const [configContent, setConfigContent] = useState('');
  const [configPath, setConfigPath] = useState('');
  const [configLoading, setConfigLoading] = useState(false);
  const [configSaving, setConfigSaving] = useState(false);

  const loadStatuses = useCallback((list: ServerConfig[]) => {
    list.forEach((s) => {
      api
        .adminServerStatus(s.id)
        .then((st) => setStatuses((prev) => ({ ...prev, [s.id]: st })))
        .catch(() => undefined);
    });
  }, []);

  const load = useCallback(() => {
    setLoading(true);
    api
      .adminServers()
      .then((list) => {
        setServers(list);
        loadStatuses(list);
      })
      .catch((e) => message.error(e?.response?.data?.message ?? '加载失败'))
      .finally(() => setLoading(false));
  }, [loadStatuses]);

  const loadConfig = useCallback(() => {
    setConfigLoading(true);
    api
      .getServerConfig()
      .then((r) => {
        setConfigPath(r.path);
        setConfigContent(r.content);
      })
      .catch((e) => message.error(e?.response?.data?.message ?? '加载配置失败'))
      .finally(() => setConfigLoading(false));
  }, []);

  useEffect(() => {
    if (open) {
      load();
      loadConfig();
    }
  }, [open, load, loadConfig]);

  const reload = async () => {
    try {
      const r = await api.reloadServers();
      message.success(`已重新加载，共 ${r.serverCount} 台服务器`);
      load();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '重新加载失败');
    }
  };

  const saveConfig = async () => {
    setConfigSaving(true);
    try {
      const r = await api.saveServerConfig(configContent);
      message.success(`已保存并生效，共 ${r.serverCount} 台服务器`);
      load();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '保存失败（请检查 YAML 格式）');
    } finally {
      setConfigSaving(false);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 140 },
    { title: '名称', dataIndex: 'name', width: 150 },
    {
      title: '环境',
      dataIndex: 'env',
      width: 76,
      render: (v: string) => (
        <Tag color={ENV_COLORS[v] ?? 'default'} bordered={false}>
          {v}
        </Tag>
      ),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 72,
      render: (v: boolean) => (v ? <Tag color="green" bordered={false}>是</Tag> : <Tag bordered={false}>否</Tag>),
    },
    {
      title: 'Agent 地址',
      dataIndex: ['agent', 'baseUrl'],
      render: (_: any, r: ServerConfig) => <span className="fp-mono">{r.agent?.baseUrl}</span>,
    },
    {
      title: '状态',
      key: 'status',
      width: 96,
      render: (_: any, r: ServerConfig) => {
        const st = statuses[r.id];
        if (!st) return <Badge status="default" text="…" />;
        const up = st.health?.status === 'UP';
        return <Badge status={up ? 'success' : 'error'} text={up ? '在线' : '离线'} />;
      },
    },
    {
      title: '版本',
      key: 'version',
      width: 96,
      render: (_: any, r: ServerConfig) => statuses[r.id]?.capabilities?.version ?? '-',
    },
  ];

  const statusTab = (
    <>
      <div style={{ marginBottom: 'var(--fp-space-2)' }}>
        <Space>
          <Button size="small" icon={<ReloadOutlined />} onClick={load}>
            刷新状态
          </Button>
          <Button size="small" icon={<SyncOutlined />} onClick={reload}>
            重新加载 servers.yml
          </Button>
        </Space>
      </div>
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns as any}
        dataSource={servers}
        pagination={false}
        expandable={{
          expandedRowRender: (r: ServerConfig) => {
            const cap = statuses[r.id]?.capabilities;
            return (
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label="标签">
                  {r.tags?.map((t) => <Tag key={t}>{t}</Tag>)}
                </Descriptions.Item>
                <Descriptions.Item label="默认根路径">
                  <span className="fp-mono">{r.defaultRoot}</span>
                </Descriptions.Item>
                <Descriptions.Item label="allowedRoots">
                  {r.allowedRoots?.map((p) => (
                    <Tag key={p} color="blue" bordered={false} className="fp-mono">
                      {p}
                    </Tag>
                  ))}
                </Descriptions.Item>
                <Descriptions.Item label="deniedPaths">
                  {r.deniedPaths?.map((p) => (
                    <Tag key={p} color="red" bordered={false} className="fp-mono">
                      {p}
                    </Tag>
                  ))}
                </Descriptions.Item>
                <Descriptions.Item label="书签">
                  {r.bookmarks?.map((b) => (
                    <Tag key={b.path} bordered={false}>
                      {b.name}: <span className="fp-mono">{b.path}</span>
                    </Tag>
                  ))}
                </Descriptions.Item>
                <Descriptions.Item label="证书引用">
                  <span className="fp-mono">
                    client={r.agent?.clientCertRef} / ca={r.agent?.caCertRef} / serverName=
                    {r.agent?.serverName}
                  </span>
                </Descriptions.Item>
                {cap && (
                  <Descriptions.Item label="Agent 能力">
                    预览 {cap.maxPreviewLines} 行 / {(cap.maxPreviewBytes / 1024 / 1024).toFixed(0)}MB ·
                    tail {cap.maxTailLines} 行 · 搜索 {cap.searchSupported ? '支持' : '不支持'} · 同步写入{' '}
                    {cap.syncWriteEnabled ? '已开启' : '未开启'}
                  </Descriptions.Item>
                )}
              </Descriptions>
            );
          },
        }}
      />
    </>
  );

  const configTab = (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--fp-space-2)', marginBottom: 'var(--fp-space-2)' }}>
        <Button size="small" type="primary" icon={<SaveOutlined />} loading={configSaving} onClick={saveConfig}>
          保存并生效
        </Button>
        <Button size="small" icon={<ReloadOutlined />} loading={configLoading} onClick={loadConfig}>
          重新读取
        </Button>
        <span className="fp-text-2 fp-mono" style={{ fontSize: 'var(--fp-fs-sm)' }}>
          {configPath}
        </span>
      </div>
      <div style={{ border: '1px solid var(--fp-border)', borderRadius: 'var(--fp-radius-sm)', overflow: 'hidden' }}>
        <Editor
          height="460px"
          language="yaml"
          value={configContent}
          onChange={(v) => setConfigContent(v ?? '')}
          options={{ fontSize: 13, minimap: { enabled: false }, fontFamily: 'var(--fp-mono)', tabSize: 2, automaticLayout: true, fixedOverflowWidgets: true }}
        />
      </div>
      <p className="fp-text-3" style={{ fontSize: 'var(--fp-fs-sm)', marginTop: 'var(--fp-space-2)' }}>
        保存前会校验 YAML 格式；保存后自动热加载并重建同步调度，无需重启服务。
      </p>
    </>
  );

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={1000}
      title="服务器配置管理"
      footer={[
        <Button key="close" type="primary" onClick={onClose}>
          关闭
        </Button>,
      ]}
    >
      <Tabs
        defaultActiveKey="status"
        items={[
          { key: 'status', label: '服务器状态', children: statusTab },
          { key: 'config', label: '编辑配置 (servers.yml)', children: configTab },
        ]}
      />
    </Modal>
  );
}
