import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, Modal, Select, Space, Switch, Table, Tag, message } from 'antd';
import { DeleteOutlined, EditOutlined, KeyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api';
import { ALL_ROLES } from '../types';
import type { UserAccountView } from '../types';

interface Props {
  open: boolean;
  onClose: () => void;
  currentUserId: string;
}

const ROLE_COLORS: Record<string, string> = {
  ADMIN: 'red',
  OPERATOR: 'blue',
  AUDITOR: 'gold',
};

type EditState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; user: UserAccountView }
  | { open: true; mode: 'password'; user: UserAccountView };

export default function UserManager({ open, onClose, currentUserId }: Props) {
  const [users, setUsers] = useState<UserAccountView[]>([]);
  const [loading, setLoading] = useState(false);
  const [edit, setEdit] = useState<EditState>({ open: false });

  const load = useCallback(() => {
    setLoading(true);
    api
      .users()
      .then(setUsers)
      .catch((e) => message.error(e?.response?.data?.message ?? '加载用户失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  const submit = async (values: any) => {
    try {
      if (edit.open && edit.mode === 'create') {
        await api.createUser({
          username: values.username,
          displayName: values.displayName,
          password: values.password,
          roles: values.roles,
          enabled: values.enabled ?? true,
        });
        message.success('用户已创建');
      } else if (edit.open && edit.mode === 'edit') {
        await api.updateUser(edit.user.id, {
          displayName: values.displayName,
          roles: values.roles,
          enabled: values.enabled,
        });
        message.success('用户已更新');
      } else if (edit.open && edit.mode === 'password') {
        await api.resetUserPassword(edit.user.id, values.password);
        message.success('密码已重置');
      }
      setEdit({ open: false });
      load();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '操作失败');
    }
  };

  const remove = (u: UserAccountView) => {
    Modal.confirm({
      title: `删除用户 ${u.username}`,
      content: '删除后不可恢复，确定继续？',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.deleteUser(u.id);
          message.success('已删除');
          load();
        } catch (e: any) {
          message.error(e?.response?.data?.message ?? '删除失败');
        }
      },
    });
  };

  const columns = [
    { title: '用户名', dataIndex: 'username', width: 150, render: (v: string) => <span className="fp-mono">{v}</span> },
    { title: '显示名', dataIndex: 'displayName', width: 150 },
    {
      title: '角色',
      dataIndex: 'roles',
      render: (roles: string[]) =>
        roles?.map((r) => (
          <Tag key={r} color={ROLE_COLORS[r] ?? 'default'} bordered={false}>
            {r}
          </Tag>
        )),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (v: boolean) => (v ? <Tag color="green" bordered={false}>启用</Tag> : <Tag bordered={false}>停用</Tag>),
    },
    { title: '来源', dataIndex: 'authProvider', width: 90 },
    {
      title: '操作',
      key: 'action',
      width: 190,
      render: (_: unknown, u: UserAccountView) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => setEdit({ open: true, mode: 'edit', user: u })}>
            编辑
          </Button>
          <Button size="small" icon={<KeyOutlined />} onClick={() => setEdit({ open: true, mode: 'password', user: u })}>
            改密
          </Button>
          <Button
            size="small"
            danger
            icon={<DeleteOutlined />}
            disabled={u.id === currentUserId}
            onClick={() => remove(u)}
          />
        </Space>
      ),
    },
  ];

  const editing = edit.open ? edit : null;
  const editUser = editing && (editing.mode === 'edit' || editing.mode === 'password') ? editing.user : undefined;

  return (
    <>
      <Modal
        open={open}
        onCancel={onClose}
        width={860}
        title="用户管理"
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            关闭
          </Button>,
        ]}
      >
        <div style={{ marginBottom: 'var(--fp-space-2)' }}>
          <Space>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setEdit({ open: true, mode: 'create' })}>
              新建用户
            </Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
          </Space>
        </div>
        <Table rowKey="id" size="small" loading={loading} columns={columns as any} dataSource={users} pagination={false} />
        <p className="fp-text-3" style={{ fontSize: 'var(--fp-fs-sm)', marginTop: 'var(--fp-space-2)' }}>
          用户存于数据库，多人使用互不影响（会话、个人书签、审计均按用户隔离）。为避免锁定，系统会保留至少一个启用中的管理员。
        </p>
      </Modal>

      <Modal
        open={!!editing}
        destroyOnClose
        title={
          editing?.mode === 'create' ? '新建用户' : editing?.mode === 'password' ? `重置密码 - ${editUser?.username}` : `编辑用户 - ${editUser?.username}`
        }
        footer={null}
        onCancel={() => setEdit({ open: false })}
      >
        <Form
          layout="vertical"
          preserve={false}
          key={editing ? `${editing.mode}-${editUser?.id ?? 'new'}` : 'closed'}
          initialValues={
            editing?.mode === 'edit'
              ? { displayName: editUser?.displayName, roles: editUser?.roles, enabled: editUser?.enabled }
              : { enabled: true, roles: ['OPERATOR'] }
          }
          onFinish={submit}
        >
          {editing?.mode === 'create' && (
            <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input autoFocus className="fp-mono" placeholder="登录用户名" />
            </Form.Item>
          )}
          {(editing?.mode === 'create' || editing?.mode === 'edit') && (
            <>
              <Form.Item label="显示名" name="displayName">
                <Input placeholder="显示名称（可选）" />
              </Form.Item>
              <Form.Item label="角色" name="roles" rules={[{ required: true, message: '请选择至少一个角色' }]}>
                <Select mode="multiple" options={ALL_ROLES.map((r) => ({ value: r, label: r }))} placeholder="选择角色" />
              </Form.Item>
              <Form.Item label="启用" name="enabled" valuePropName="checked">
                <Switch />
              </Form.Item>
            </>
          )}
          {(editing?.mode === 'create' || editing?.mode === 'password') && (
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '至少 6 位' }]}
            >
              <Input.Password placeholder="密码（至少 6 位）" />
            </Form.Item>
          )}
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setEdit({ open: false })}>取消</Button>
              <Button type="primary" htmlType="submit">
                保存
              </Button>
            </Space>
          </div>
        </Form>
      </Modal>
    </>
  );
}
