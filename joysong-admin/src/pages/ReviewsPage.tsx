import { useEffect, useState } from 'react';
import { Table, Button, Popconfirm, message, Input, Space } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import api, { getData } from '../api';

export default function ReviewsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const fetchData = () => {
    setLoading(true);
    api.get('/admin/reviews').then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleDelete = async (id: string) => {
    await api.delete(`/admin/reviews/${id}`);
    message.success('删除成功');
    fetchData();
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`/admin/reviews/${id}`)));
      message.success(`成功删除 ${selectedRowKeys.length} 条记录`);
      setSelectedRowKeys([]);
      fetchData();
    } catch (err: any) {
      message.error('批量删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const filteredData = data.filter(item => {
    return keyword
      ? (item.doctorId?.includes(keyword) || item.content?.includes(keyword) || item.id?.includes(keyword) || item.orderId?.includes(keyword))
      : true;
  });

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '订单ID', dataIndex: 'orderId', width: 160 },
    { title: '用户ID', dataIndex: 'userId', width: 160 },
    { title: '医生ID', dataIndex: 'doctorId', width: 150 },
    { title: '评分', dataIndex: 'rating', width: 80 },
    { title: '内容', dataIndex: 'content', width: 280, ellipsis: true },
    { title: '标签', dataIndex: 'tags', width: 150 },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: any, record: any) => (
        <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
          <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>评价管理</h2>
        <Popconfirm title={`确定删除选中的 ${selectedRowKeys.length} 条记录吗？`} onConfirm={handleBatchDelete} disabled={selectedRowKeys.length === 0}>
          <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
            批量删除{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
          </Button>
        </Popconfirm>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="搜索医生ID、内容、ID"
          allowClear
          onSearch={(value) => setKeyword(value)}
          style={{ width: 300 }}
        />
      </Space>
      <Table dataSource={filteredData} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }}
        rowSelection={{
          selectedRowKeys,
          onChange: keys => setSelectedRowKeys(keys),
        }}
      />
    </div>
  );
}
