import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  Card, Descriptions, Tabs, Table, Button, Modal, Form,
  Input, InputNumber, Space, message, Popconfirm, Select, Tag
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ArrowLeftOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import { identityRoleLabel, identityStatusColor } from '../identity';

const roleOptions = [
  { label: '普通账号', value: 'USER' },
  { label: '管理员账号', value: 'ADMIN' },
];

interface DiarySectionProps {
  apiPath: string;
  authorName: string;
}

function DiarySection({ apiPath, authorName }: DiarySectionProps) {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    api.get(apiPath).then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [apiPath]);

  const handleAdd = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ authorName });
    setModalOpen(true);
  };

  const handleEdit = (record: any) => {
    setEditingId(record.id);
    form.setFieldsValue({ ...record, authorName });
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    await api.delete(`/admin/diaries/${id}`);
    message.success('删除成功');
    fetchData();
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    if (editingId) {
      await api.put(`/admin/diaries/${editingId}`, { ...values, id: editingId });
      message.success('更新成功');
    } else {
      await api.post('/admin/diaries', values);
      message.success('创建成功');
    }
    setModalOpen(false);
    fetchData();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '标题', dataIndex: 'title', width: 180 },
    { title: '内容摘要', dataIndex: 'content', width: 250, render: (v: string) => v ? `${v.slice(0, 40)}...` : '-' },
    { title: '点赞数', dataIndex: 'likeCount', width: 80 },
    { title: '评论数', dataIndex: 'commentCount', width: 80 },
    { title: '发布日期', dataIndex: 'publishDate', width: 120 },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: any, record: any) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => handleEdit(record)} title="编辑" />
          <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const fields = [
    { key: 'id', label: 'ID', required: true },
    { key: 'title', label: '标题', required: true },
    { key: 'authorName', label: '作者' },
    { key: 'coverImage', label: '封面图链接' },
    { key: 'images', label: '图片链接（逗号分隔）' },
    { key: 'content', label: '正文', type: 'textarea' as const },
    { key: 'tags', label: '标签（逗号分隔）' },
    { key: 'likeCount', label: '点赞数', type: 'number' as const },
    { key: 'commentCount', label: '评论数', type: 'number' as const },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h3>用户日记</h3>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} style={{ background: '#E8577B' }}>新增日记</Button>
      </div>
      <Table dataSource={data} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }} />
      <Modal title={editingId ? '编辑日记' : '新增日记'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={600} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {fields.map(f => (
            <Form.Item key={f.key} name={f.key} label={f.label} rules={f.required ? [{ required: true }] : []}>
              {f.type === 'textarea' ? <Input.TextArea rows={3} /> :
               f.type === 'number' ? <InputNumber style={{ width: '100%' }} /> :
               <Input />}
            </Form.Item>
          ))}
        </Form>
      </Modal>
    </div>
  );
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [user, setUser] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [orders, setOrders] = useState<any[]>([]);
  const [identityRoles, setIdentityRoles] = useState<string[]>([]);

  const fetchUser = () => {
    if (!id) return;
    setLoading(true);
    Promise.all([
      api.get(`/admin/users/${id}`),
      api.get('/admin/identity/roles', { params: { status: 'ACTIVE', keyword: id } }),
    ]).then(([userResponse, rolesResponse]) => {
      setUser(getData<any>(userResponse as any));
      setIdentityRoles(getData<any[]>(rolesResponse as any)
        .filter(role => role.userId === id)
        .map(role => role.roleCode));
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchUser(); }, [id]);

  useEffect(() => {
    if (!id) return;
    api.get(`/admin/users/${id}/orders`).then(res => setOrders(getData(res as any)));
  }, [id]);

  const handleRoleChange = async (role: string) => {
    if (!id) return;
    await api.put(`/admin/users/${id}/role`, { role });
    message.success('后台权限更新成功');
    fetchUser();
  };

  const handleDeactivate = () => {
    if (!id) return;
    Modal.confirm({
      title: '确定注销该用户吗？',
      content: `确定要注销用户「${user.nickname || user.phone}」吗？注销后该用户将无法登录。`,
      okText: '确认注销',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await api.put(`/admin/users/${id}/deactivate`);
        message.success('用户已注销');
        fetchUser();
      },
    });
  };

  const handleReactivate = async () => {
    if (!id) return;
    await api.put(`/admin/users/${id}/reactivate`);
    message.success('用户已恢复');
    fetchUser();
  };

  if (!id) return <div>参数错误</div>;
  if (loading) return <div>加载中...</div>;
  if (!user) return <div>用户不存在</div>;

  const orderColumns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '医生', dataIndex: 'doctorName', width: 180 },
    { title: '项目', dataIndex: 'projectName', width: 180 },
    { title: '机构', dataIndex: 'institutionName', width: 150 },
    { title: '价格', dataIndex: 'price', width: 80 },
    { title: '状态', dataIndex: 'status', width: 100 },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: any, record: any) => (
        <Link to="/orders" state={{ orderId: record.id }}>
          <Button size="small">查看</Button>
        </Link>
      ),
    },
  ];

  return (
    <div>
      <Link to="/users">
        <Button icon={<ArrowLeftOutlined />} style={{ marginBottom: 16 }}>返回用户列表</Button>
      </Link>
      <Card
        title={
          <Space>
            <span>{user.nickname || user.phone}</span>
            {user.deletedAt ? <Tag color="red">已注销</Tag> : <Tag color="green">正常</Tag>}
          </Space>
        }
        extra={
          user.deletedAt ? (
            <Button icon={<CheckCircleOutlined />} onClick={handleReactivate}>恢复用户</Button>
          ) : (
            <Button icon={<StopOutlined />} danger onClick={handleDeactivate}>注销用户</Button>
          )
        }
        style={{ marginBottom: 24 }}
      >
        <Descriptions size="small" column={4}>
          <Descriptions.Item label="手机号">{user.phone || '-'}</Descriptions.Item>
          <Descriptions.Item label="昵称">{user.nickname || '-'}</Descriptions.Item>
          <Descriptions.Item label="城市">{user.city || '-'}</Descriptions.Item>
          <Descriptions.Item label="后台权限">
            <Select value={user.role} options={roleOptions} size="small" style={{ width: 120 }} onChange={handleRoleChange} />
          </Descriptions.Item>
          <Descriptions.Item label="职业身份">
            {identityRoles.length > 0
              ? <Space size={[0, 4]} wrap>{identityRoles.map(role => <Tag color={identityStatusColor('ACTIVE')} key={role}>{identityRoleLabel(role)}</Tag>)}</Space>
              : <Tag>普通用户</Tag>}
          </Descriptions.Item>
          <Descriptions.Item label="注册时间">{user.createdAt || '-'}</Descriptions.Item>
        </Descriptions>
        <p style={{ marginTop: 12, color: '#666' }}>{user.bio || '暂无简介'}</p>
      </Card>
      <Tabs
        defaultActiveKey="diaries"
        items={[
          {
            key: 'diaries',
            label: '用户日记',
            children: <DiarySection apiPath={`/admin/users/${id}/diaries`} authorName={user.nickname} />,
          },
          {
            key: 'orders',
            label: '用户订单',
            children: (
              <div>
                <h3>用户订单</h3>
                <Table dataSource={orders} columns={orderColumns} rowKey="id" size="small" scroll={{ x: 'max-content' }} />
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
