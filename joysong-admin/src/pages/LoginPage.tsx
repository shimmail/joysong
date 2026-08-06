import { useEffect, useState } from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { LockOutlined, MobileOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import api, { clearAdminToken, getApiErrorMessage, getData, getDefaultManagementPath, setAdminToken } from '../api';
import type { ManagementContext } from '../api';

const { Title, Text } = Typography;

type LoginValues = {
  phone: string;
  password: string;
};

type LoginResult = {
  token: string;
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: { role: string };
  context: ManagementContext;
};

export default function LoginPage() {
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  // 登录页始终从干净会话开始，避免旧管理员令牌导致 401/403 循环。
  useEffect(() => {
    clearAdminToken();
  }, []);

  const onFinish = async (values: LoginValues) => {
    setSubmitting(true);
    try {
      const response = await api.post('/management/login', {
        phone: values.phone.trim(),
        password: values.password,
      });
      const data = getData<LoginResult>(response);
      const accessToken = data?.accessToken || data?.token;
      if (!accessToken || !data?.refreshToken || !data.context || !['ADMIN', 'USER'].includes(data.user?.role)) {
        throw new Error('服务器返回的管理凭证无效');
      }
      setAdminToken(accessToken, data.context, data.refreshToken);
      message.success('登录成功');
      navigate(getDefaultManagementPath(), { replace: true });
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
          <Text type="secondary">管理员、已认证医生或机构法人均可登录</Text>
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
