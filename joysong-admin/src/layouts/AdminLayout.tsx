import { useEffect, useState } from 'react';
import { Layout, Menu, Button, Space, Modal, Form, Input, message } from 'antd';
import type { MenuProps } from 'antd';
import {
  AppstoreOutlined,
  BankOutlined,
  BookOutlined,
  CustomerServiceOutlined,
  DashboardOutlined,
  DollarOutlined,
  FileTextOutlined,
  GiftOutlined,
  LinkOutlined,
  LockOutlined,
  LogoutOutlined,
  MessageOutlined,
  MedicineBoxOutlined,
  PictureOutlined,
  ProfileOutlined,
  RollbackOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  SettingOutlined,
  ShoppingCartOutlined,
  TeamOutlined,
  UserOutlined,
  UsergroupAddOutlined,
  WalletOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import api, { clearAdminToken, getApiErrorMessage, getManagementContext, logoutManagementSession } from '../api';
import './AdminLayout.css';

const { Sider, Content, Header } = Layout;

type MenuLeaf = {
  key: string;
  icon: React.ReactNode;
  label: string;
};

type MenuGroup = {
  key: string;
  icon: React.ReactNode;
  label: string;
  children: MenuLeaf[];
};

const dashboardItem: MenuLeaf = {
  key: '/',
  icon: <DashboardOutlined />,
  label: '数据概览',
};

// 按日常处理顺序组织模块：业务操作优先，财务与风控靠后。
const menuGroups: MenuGroup[] = [
  {
    key: 'business',
    icon: <AppstoreOutlined />,
    label: '业务管理',
    children: [
      { key: '/projects', icon: <MedicineBoxOutlined />, label: '项目管理' },
      { key: '/project-requests', icon: <SafetyCertificateOutlined />, label: '项目申请审核' },
      { key: '/institution-projects', icon: <ShoppingCartOutlined />, label: '机构项目管理' },
      { key: '/project-collaboration', icon: <LinkOutlined />, label: '项目协作与审核' },
      { key: '/orders', icon: <FileTextOutlined />, label: '订单管理' },
    ],
  },
  {
    key: 'people',
    icon: <TeamOutlined />,
    label: '组织与人员',
    children: [
      { key: '/users', icon: <UsergroupAddOutlined />, label: '用户管理' },
      { key: '/identity', icon: <SafetyCertificateOutlined />, label: '身份与入驻审核' },
      { key: '/doctors', icon: <UserOutlined />, label: '医生管理' },
      { key: '/institutions', icon: <BankOutlined />, label: '机构管理' },
    ],
  },
  {
    key: 'content',
    icon: <BookOutlined />,
    label: '内容运营',
    children: [
      { key: '/banners', icon: <PictureOutlined />, label: '轮播图管理' },
      { key: '/articles', icon: <BookOutlined />, label: '文章管理' },
      { key: '/diaries', icon: <ProfileOutlined />, label: '日记管理' },
      { key: '/reviews', icon: <MessageOutlined />, label: '评价管理' },
      { key: '/coupons', icon: <GiftOutlined />, label: '优惠券管理' },
    ],
  },
  {
    key: 'finance',
    icon: <WalletOutlined />,
    label: '财务与结算',
    children: [
      { key: '/payments', icon: <DollarOutlined />, label: '支付记录' },
      { key: '/refunds', icon: <RollbackOutlined />, label: '退款管理' },
      { key: '/settlements', icon: <WalletOutlined />, label: '结算管理' },
      { key: '/doctor-project-configs', icon: <SettingOutlined />, label: '项目分账配置' },
      { key: '/split-proposals', icon: <SettingOutlined />, label: '分账协商与确认' },
    ],
  },
  {
    key: 'service',
    icon: <SafetyOutlined />,
    label: '服务与风控',
    children: [
      { key: '/cs', icon: <CustomerServiceOutlined />, label: '客服消息' },
      { key: '/reports', icon: <WarningOutlined />, label: '举报管理' },
    ],
  },
];

const allMenuLeaves = [dashboardItem, ...menuGroups.flatMap((group) => group.children)];

export default function AdminLayout() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [passwordForm] = Form.useForm();

  const canShowMenuItem = (key: string) => {
    if (isAdmin) return true;
    if (key === '/doctors') return managementContext?.canManageDoctors;
    if (key === '/institution-projects') return false;
    if (key === '/project-requests') return managementContext?.canReviewInstitutionProjectRequests;
    if (key === '/project-collaboration') return false;
    if (key === '/orders') return managementContext?.canManageOrders;
    if (key === '/institutions') return (managementContext?.visibleInstitutionIds.length || 0) > 0;
    if (key === '/articles') return managementContext?.canManageArticles;
    if (key === '/split-proposals') return managementContext?.canManageSplitConfigs;
    return false;
  };

  const visibleMenuGroups = menuGroups
    .map((group) => ({ ...group, children: group.children.filter((item) => canShowMenuItem(item.key)) }))
    .filter((group) => group.children.length > 0);

  const menuItems: MenuProps['items'] = [
    ...(canShowMenuItem(dashboardItem.key) ? [dashboardItem] : []),
    ...visibleMenuGroups,
  ];

  const selectedMenuKey = allMenuLeaves
    .filter((item) => item.key !== '/')
    .sort((left, right) => right.key.length - left.key.length)
    .find((item) => location.pathname === item.key || location.pathname.startsWith(`${item.key}/`))?.key
    ?? (location.pathname === '/' ? '/' : '');

  const activeGroupKey = visibleMenuGroups.find((group) =>
    group.children.some((item) => item.key === selectedMenuKey),
  )?.key;
  const [openKeys, setOpenKeys] = useState<string[]>(() => activeGroupKey ? [activeGroupKey] : []);

  useEffect(() => {
    if (activeGroupKey) {
      setOpenKeys((currentKeys) => currentKeys.includes(activeGroupKey)
        ? currentKeys
        : [...currentKeys, activeGroupKey]);
    }
  }, [activeGroupKey]);

  const handleLogout = async () => {
    await logoutManagementSession();
    navigate('/login', { replace: true });
  };

  const handleChangePassword = async (values: { oldPassword: string; newPassword: string }) => {
    setPasswordSubmitting(true);
    try {
      await api.put('/user/password', {
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
      message.success('密码已修改，请重新登录');
      setPasswordOpen(false);
      passwordForm.resetFields();
      clearAdminToken();
      navigate('/login', { replace: true });
    } catch (error) {
      message.error(getApiErrorMessage(error, '密码修改失败'));
    } finally {
      setPasswordSubmitting(false);
    }
  };

  return (
    <Layout className="admin-shell">
      <Sider
        className="admin-sider"
        theme="light"
        width={240}
        collapsedWidth={72}
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
      >
        <div className={`admin-brand${collapsed ? ' admin-brand--collapsed' : ''}`}>
          <span className="admin-brand__mark">娇</span>
          {!collapsed && (
            <span className="admin-brand__text">
              <strong>娇颜颂</strong>
              <small>{isAdmin ? '管理系统' : '专业管理中心'}</small>
            </span>
          )}
        </div>
        <div className="admin-sider__label">{collapsed ? '·' : '功能导航'}</div>
        <Menu
          className="admin-menu"
          mode="inline"
          inlineIndent={18}
          selectedKeys={selectedMenuKey ? [selectedMenuKey] : []}
          openKeys={collapsed ? undefined : openKeys}
          items={menuItems}
          onOpenChange={(keys) => setOpenKeys(keys.map(String))}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="admin-header">
          <Space>
            <Button type="text" icon={<LockOutlined />} onClick={() => setPasswordOpen(true)}>修改密码</Button>
            <Button type="text" icon={<LogoutOutlined />} onClick={() => void handleLogout()}>退出登录</Button>
          </Space>
        </Header>
        <Content className="admin-content">
          <Outlet />
        </Content>
      </Layout>
      <Modal
        title="修改管理员密码"
        open={passwordOpen}
        confirmLoading={passwordSubmitting}
        okText="确认修改"
        cancelText="取消"
        onOk={() => passwordForm.submit()}
        onCancel={() => {
          setPasswordOpen(false);
          passwordForm.resetFields();
        }}
        destroyOnHidden
      >
        <Form form={passwordForm} layout="vertical" onFinish={handleChangePassword}>
          <Form.Item name="oldPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            extra={isAdmin ? '至少 12 位，且包含大写字母、小写字母、数字和特殊字符' : '密码长度至少 8 位'}
            rules={[
              { required: true, message: '请输入新密码' },
              { min: isAdmin ? 12 : 8, max: 128, message: `密码长度应为 ${isAdmin ? 12 : 8}-128 位` },
              ...(isAdmin ? [{ pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).+$/, message: '密码复杂度不足' }] : []),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  return !value || value === getFieldValue('newPassword')
                    ? Promise.resolve()
                    : Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
}
