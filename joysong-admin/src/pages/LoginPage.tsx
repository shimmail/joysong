import { useState } from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { LockOutlined, MobileOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { getApiErrorMessage, loginAdminSession } from '../api';

const { Title, Text } = Typography;

type LoginValues = {
  phone: string;
  password: string;
};

function safeReturnPath(state: unknown) {
  const from = state && typeof state === 'object'
    ? (state as { from?: { pathname?: unknown; search?: unknown; hash?: unknown } }).from
    : undefined;
  const pathname = typeof from?.pathname === 'string' ? from.pathname : '';
  if (!pathname.startsWith('/') || pathname.startsWith('//') || pathname === '/login') return '/';
  const search = typeof from?.search === 'string' && from.search.startsWith('?') ? from.search : '';
  const hash = typeof from?.hash === 'string' && from.hash.startsWith('#') ? from.hash : '';
  return `${pathname}${search}${hash}`;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: LoginValues) => {
    setSubmitting(true);
    try {
      await loginAdminSession(values.phone, values.password);
      message.success('登录成功');
      navigate(safeReturnPath(location.state), { replace: true });
    } catch (error) {
      message.error(getApiErrorMessage(error, '登录失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', background: '#f5f7fa', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
      <Card style={{ width: '100%', maxWidth: 400 }} styles={{ body: { padding: 32 } }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <Title level={3} style={{ marginBottom: 8, color: '#E8577B' }}>娇颜颂管理系统</Title>
          <Text type="secondary">仅限平台管理员登录</Text>
        </div>
        <Form<LoginValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="phone"
            label="手机号"
            normalize={(value: string) => value?.trim()}
            rules={[
              { required: true, message: '请输入手机号' },
              { pattern: /^1\d{10}$/, message: '请输入正确的手机号' },
            ]}
          >
            <Input prefix={<MobileOutlined />} inputMode="numeric" maxLength={11} autoComplete="username" placeholder="请输入手机号" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 8, max: 128, message: '密码长度应为 8-128 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
