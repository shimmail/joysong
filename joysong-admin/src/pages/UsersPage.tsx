import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Table, Button, Select, Space, message, Modal, Tag, Input } from 'antd';
import { EyeOutlined, StopOutlined, CheckCircleOutlined, SearchOutlined } from '@ant-design/icons';
import api, { getData, debounce, getApiErrorMessage } from '../api';
import { identityRoleLabel, identityStatusColor } from '../identity';

const roleOptions = [
  { label: '普通账号', value: 'USER' },
  { label: '管理员账号', value: 'ADMIN' },
];

export default function UsersPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [searchValue, setSearchValue] = useState('');
  const [activeRolesByUser, setActiveRolesByUser] = useState<Record<string, string[]>>({});

  const fetchData = async (search?: string) => {
    setLoading(true);
    const params = search ? { keyword: search } : {};
    try {
      const [usersResponse, rolesResponse] = await Promise.all([
        api.get('/admin/users', { params }),
        api.get('/admin/identity/roles', { params: { status: 'ACTIVE' } }),
      ]);
      setData(getData(usersResponse as any));
      const grouped = getData<any[]>(rolesResponse as any).reduce<Record<string, string[]>>((result, role) => {
        result[role.userId] = [...(result[role.userId] || []), role.roleCode];
        return result;
      }, {});
      setActiveRolesByUser(grouped);
    } catch (error) {
      message.error(getApiErrorMessage(error, '用户列表加载失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const handleSearch = () => {
    setKeyword(searchValue);
    fetchData(searchValue || undefined);
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const debouncedFetch = useMemo(() => debounce((val: string) => {
    setKeyword(val);
    fetchData(val || undefined);
  }, 300), []);

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setSearchValue(val);
    // 清空时立即恢复全部列表
    if (!val) {
      setKeyword('');
      fetchData();
    } else {
      debouncedFetch(val);
    }
  };

  const handleRoleChange = async (id: string, role: string) => {
    await api.put(`/admin/users/${id}/role`, { role });
    message.success('后台权限更新成功');
    fetchData();
  };

  const handleDeactivate = async (record: any) => {
    Modal.confirm({
      title: '确定注销该用户吗？',
      content: `确定要注销用户「${record.nickname || record.phone}」吗？注销后该用户将无法登录。`,
      okText: '确认注销',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await api.put(`/admin/users/${record.id}/deactivate`);
        message.success('用户已注销');
        fetchData();
      },
    });
  };

  const handleReactivate = async (id: string) => {
    await api.put(`/admin/users/${id}/reactivate`);
    message.success('用户已恢复');
    fetchData();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 200 },
    { title: '手机号', dataIndex: 'phone', width: 150 },
    { title: '昵称', dataIndex: 'nickname', width: 150 },
    { title: '城市', dataIndex: 'city', width: 120 },
    { title: '简介', dataIndex: 'bio', width: 250 },
    {
      title: '后台权限',
      dataIndex: 'role',
      width: 130,
      render: (role: string, record: any) => (
        <Select
          value={role}
          options={roleOptions}
          size="small"
          style={{ width: 100 }}
          onChange={(value) => handleRoleChange(record.id, value)}
        />
      ),
    },
    {
      title: '职业身份',
      key: 'identityRoles',
      width: 260,
      render: (_: unknown, record: any) => {
        const roles = activeRolesByUser[record.id] || [];
        return roles.length > 0
          ? <Space size={[0, 4]} wrap>{roles.map(role => <Tag color={identityStatusColor('ACTIVE')} key={role}>{identityRoleLabel(role)}</Tag>)}</Space>
          : <Tag>普通用户</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'deletedAt',
      width: 100,
      render: (deletedAt: string | null) => (
        deletedAt ? <Tag color="red">已注销</Tag> : <Tag color="green">正常</Tag>
      ),
    },
    { title: '注册时间', dataIndex: 'createdAt', width: 180 },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_: any, record: any) => (
        <Space>
          <Link to={`/users/${record.id}`}>
            <Button icon={<EyeOutlined />} size="small" title="查看详情" />
          </Link>
          {record.deletedAt ? (
            <Button
              icon={<CheckCircleOutlined />}
              size="small"
              title="恢复用户"
              onClick={() => handleReactivate(record.id)}
            >
              恢复
            </Button>
          ) : (
            <Button
              icon={<StopOutlined />}
              size="small"
              danger
              title="注销用户"
              onClick={() => handleDeactivate(record)}
            >
              注销
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <h2>用户管理</h2>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
        <Input
          placeholder="搜索昵称、手机号或邮箱"
          value={searchValue}
          onChange={handleSearchChange}
          onPressEnter={handleSearch}
          allowClear
          style={{ width: 280 }}
          prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
          搜索
        </Button>
        {keyword && (
          <Tag closable onClose={() => { setSearchValue(''); setKeyword(''); fetchData(); }}>
            搜索：{keyword}
          </Tag>
        )}
      </div>
      <Table dataSource={data} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }} />
    </div>
  );
}
