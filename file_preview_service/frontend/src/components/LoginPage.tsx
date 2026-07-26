import { useState } from 'react';
import { Button, Card, Form, Input, message } from 'antd';
import { CloudServerOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { Me } from '../types';

interface Props {
  onLoggedIn: (me: Me) => void;
}

export default function LoginPage({ onLoggedIn }: Props) {
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const me = await api.login(values.username, values.password);
      onLoggedIn(me);
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fp-login">
      <Card className="fp-login__card">
        <div className="fp-login__brand">
          <div className="fp-login__logo">
            <CloudServerOutlined />
          </div>
          <div className="fp-login__title">平台运维助手</div>
        </div>
        <Form layout="vertical" size="large" onFinish={onFinish} requiredMark={false}>
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined className="fp-text-3" />} autoFocus placeholder="用户名" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined className="fp-text-3" />} placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
