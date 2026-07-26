import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, Descriptions, Modal, Progress, Spin, Table, Tag, Tooltip, message } from 'antd';
import { CloudSyncOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { BackupView, SyncJob } from '../types';
import { humanBytes } from '../utils';

interface Props {
  open: boolean;
  onClose: () => void;
}

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'green',
  PARTIAL: 'orange',
  FAILED: 'red',
  RUNNING: 'blue',
};

function fmtTime(t?: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '-';
}

function statusTag(s: string) {
  return (
    <Tag color={STATUS_COLORS[s] ?? 'default'} bordered={false}>
      {s}
    </Tag>
  );
}

/** 进度百分比：已处理（同步+跳过+失败）/ 总数；总数在扫描后即由后端确定。 */
function progressOf(j: SyncJob): number {
  if (!j.filesTotal) return 0;
  const processed = j.filesSynced + j.filesSkipped + j.filesFailed;
  return Math.min(100, Math.round((processed / j.filesTotal) * 100));
}

/** 单个任务的逐文件明细日志，展开行时才向后端拉取。 */
function JobDetailView({ jobId }: { jobId: number }) {
  const [detail, setDetail] = useState<string>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    api
      .syncJobDetail(jobId)
      .then((r) => alive && setDetail(r.detail || '（无明细日志）'))
      .catch((e) => alive && setDetail(`加载失败：${e?.response?.data?.message ?? '未知错误'}`))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [jobId]);

  if (loading) return <Spin size="small" />;
  return (
    <pre
      className="fp-mono"
      style={{
        margin: 0,
        maxHeight: 260,
        overflow: 'auto',
        padding: '8px 12px',
        fontSize: 'var(--fp-fs-sm)',
        background: 'var(--fp-bg-subtle)',
        border: '1px solid var(--fp-border)',
        borderRadius: 6,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
      }}
    >
      {detail}
    </pre>
  );
}

export default function SyncManager({ open, onClose }: Props) {
  const [servers, setServers] = useState<BackupView[]>([]);
  const [jobs, setJobs] = useState<SyncJob[]>([]);
  const [loading, setLoading] = useState(false);
  const [runningId, setRunningId] = useState<string>();

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([api.syncServers(), api.syncJobs(undefined, 0, 50)])
      .then(([s, j]) => {
        setServers(s);
        setJobs(j.content ?? []);
      })
      .catch((e) => message.error(e?.response?.data?.message ?? '加载失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  const runSync = async (serverId: string) => {
    setRunningId(serverId);
    try {
      await api.runSync(serverId);
      message.success('同步已触发，正在后台执行…');
      load(); // 立即刷新出 RUNNING 状态；随后由轮询自动更新
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '触发同步失败');
    } finally {
      setRunningId(undefined);
    }
  };

  // 存在运行中的任务时自动轮询刷新，直至全部结束
  useEffect(() => {
    if (!open) return;
    const anyRunning = servers.some((s) => s.running || s.lastJob?.status === 'RUNNING');
    if (!anyRunning) return;
    const t = window.setTimeout(load, 3000);
    return () => window.clearTimeout(t);
  }, [open, servers]);

  const backupColumns = [
    {
      title: '主机',
      key: 'host',
      render: (_: unknown, r: BackupView) => (
        <span>
          {r.serverName} <span className="fp-mono fp-text-3">{r.serverId}</span>
        </span>
      ),
    },
    {
      title: '备机',
      dataIndex: 'targetServerId',
      width: 140,
      render: (v: string) => <span className="fp-mono">{v}</span>,
    },
    {
      title: '调度',
      dataIndex: 'schedule',
      width: 130,
      render: (v?: string) => (v ? <span className="fp-mono">{v}</span> : <span className="fp-text-3">手动</span>),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 64,
      render: (v: boolean) => (v ? <Tag color="green" bordered={false}>是</Tag> : <Tag bordered={false}>否</Tag>),
    },
    {
      title: '最近同步',
      key: 'last',
      render: (_: unknown, r: BackupView) => {
        const j = r.lastJob;
        if (!j) return <span className="fp-text-3">从未</span>;
        return (
          <span>
            {statusTag(j.status)}
            {j.status === 'RUNNING' && (
              <span className="fp-text-2" style={{ fontSize: 'var(--fp-fs-sm)' }}>
                {progressOf(j)}% ({j.filesSynced + j.filesSkipped + j.filesFailed}/{j.filesTotal}){' '}
              </span>
            )}
            <span className="fp-text-2" style={{ fontSize: 'var(--fp-fs-sm)' }}>
              {j.filesSynced}↑ / {j.filesSkipped}skip / {j.filesFailed}✗ · {humanBytes(j.bytesTransferred)} ·{' '}
              <span className="fp-mono">{fmtTime(j.finishedAt || j.startedAt)}</span>
            </span>
          </span>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_: unknown, r: BackupView) => (
        <Button
          size="small"
          type="primary"
          icon={<SyncOutlined />}
          loading={runningId === r.serverId || r.running}
          onClick={() => runSync(r.serverId)}
        >
          立即同步
        </Button>
      ),
    },
  ];

  const jobColumns = [
    { title: '开始时间', dataIndex: 'startedAt', width: 168, render: (v: string) => <span className="fp-mono">{fmtTime(v)}</span> },
    { title: '主机', dataIndex: 'serverId', width: 120, render: (v: string) => <span className="fp-mono">{v}</span> },
    { title: '备机', dataIndex: 'targetServerId', width: 120, render: (v: string) => <span className="fp-mono">{v}</span> },
    { title: '触发', dataIndex: 'trigger', width: 90, render: (v: string) => <Tag bordered={false}>{v}</Tag> },
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (v: string, j: SyncJob) => (
        <span>
          {statusTag(v)}
          {v === 'RUNNING' && (
            <Progress
              percent={progressOf(j)}
              size="small"
              strokeWidth={5}
              style={{ width: 104, marginTop: 2 }}
            />
          )}
        </span>
      ),
    },
    { title: '总数', dataIndex: 'filesTotal', width: 64, align: 'right' as const },
    { title: '同步', dataIndex: 'filesSynced', width: 64, align: 'right' as const },
    { title: '跳过', dataIndex: 'filesSkipped', width: 64, align: 'right' as const },
    { title: '失败', dataIndex: 'filesFailed', width: 64, align: 'right' as const },
    { title: '字节', dataIndex: 'bytesTransferred', width: 90, align: 'right' as const, render: (v: number) => <span className="fp-mono">{humanBytes(v)}</span> },
    {
      title: '信息',
      dataIndex: 'message',
      ellipsis: true,
      render: (v?: string) => (v ? <Tooltip title={v}><span className="fp-text-2">{v}</span></Tooltip> : ''),
    },
  ];

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={1040}
      title={
        <span>
          <CloudSyncOutlined /> 主备同步管理
        </span>
      }
      footer={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>,
        <Button key="close" type="primary" onClick={onClose}>
          关闭
        </Button>,
      ]}
    >
      <p className="fp-text-2" style={{ fontSize: 'var(--fp-fs-sm)' }}>
        备机与同步规则来自 <span className="fp-mono">servers.yml</span> 的 <span className="fp-mono">backup</span> 段。
        同步为增量（按大小与修改时间比较），完成后按规则保持备机文件权限位与主机一致。
      </p>

      <div className="fp-section-title">备机配置</div>
      <Table
        rowKey="serverId"
        size="small"
        loading={loading}
        columns={backupColumns as any}
        dataSource={servers}
        pagination={false}
        locale={{ emptyText: '未配置任何备机' }}
        expandable={{
          expandedRowRender: (r: BackupView) => (
            <Descriptions size="small" column={1} bordered>
              {r.rules?.map((rule, i) => (
                <Descriptions.Item key={i} label={rule.name || `规则 ${i + 1}`}>
                  <span className="fp-mono">{rule.sourceDir}</span>
                  {' → '}
                  <span className="fp-mono">{rule.targetDir || rule.sourceDir}</span>
                  {'　'}
                  {rule.includes?.map((p) => (
                    <Tag key={`i-${p}`} color="blue" bordered={false} className="fp-mono">
                      {p}
                    </Tag>
                  ))}
                  {rule.excludes?.map((p) => (
                    <Tag key={`e-${p}`} color="red" bordered={false} className="fp-mono">
                      !{p}
                    </Tag>
                  ))}
                  {rule.excludeDirs?.map((p) => (
                    <Tag key={`d-${p}`} color="orange" bordered={false} className="fp-mono">
                      !{p}/
                    </Tag>
                  ))}
                  {rule.recursive && <Tag bordered={false}>递归</Tag>}
                  {rule.preservePermissions && <Tag color="green" bordered={false}>保持权限</Tag>}
                  {rule.preserveOwnership && <Tag color="gold" bordered={false}>保持属主</Tag>}
                </Descriptions.Item>
              ))}
            </Descriptions>
          ),
        }}
      />

      <div className="fp-section-title" style={{ marginTop: 'var(--fp-space-3)' }}>
        同步历史 <Badge count={jobs.length} showZero color="var(--fp-primary)" />
        <span className="fp-text-3" style={{ fontSize: 'var(--fp-fs-sm)', fontWeight: 'normal' }}>
          （保留 3 天，点击行首 + 展开明细日志）
        </span>
      </div>
      <Table
        rowKey="id"
        size="small"
        columns={jobColumns as any}
        dataSource={jobs}
        pagination={{ pageSize: 10, size: 'small' }}
        locale={{ emptyText: '暂无同步记录' }}
        scroll={{ x: 900 }}
        expandable={{
          expandedRowRender: (j: SyncJob) => <JobDetailView jobId={j.id} />,
        }}
      />
    </Modal>
  );
}
