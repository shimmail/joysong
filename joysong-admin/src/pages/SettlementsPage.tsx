import { useEffect, useState } from 'react';
import { Table, Select, Space, Tag } from 'antd';
import api, { getData } from '../api';

const statusLabels: Record<string, string> = {
  PENDING: '待结算',
  COMPLETED: '已完成',
};

const statusColors: Record<string, string> = {
  PENDING: 'orange',
  COMPLETED: 'green',
};

const statusOptions = Object.keys(statusLabels).map(key => ({ label: statusLabels[key], value: key }));

export default function SettlementsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const pageSize = 20;

  const fetchData = () => {
    setLoading(true);
    const params: any = { page, size: pageSize };
    if (statusFilter) params.status = statusFilter;
    api.get('/admin/settlements', { params })
      .then(res => {
        const result = getData(res as any);
        if (Array.isArray(result)) {
          setData(result);
          setTotal(result.length);
        } else if (result?.content) {
          setData(result.content);
          setTotal(result.totalElements ?? result.content.length);
        } else {
          setData([]);
          setTotal(0);
        }
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [page, statusFilter]);

  const columns = [
    { title: '订单号', dataIndex: 'orderId', width: 180 },
    {
      title: '总金额', dataIndex: 'totalAmount', width: 100,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '平台服务费', dataIndex: 'platformFee', width: 110,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '机构分成', dataIndex: 'institutionShare', width: 100,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '医美顾问分账金额', dataIndex: 'consultantCommission', width: 140,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '医生分账金额', dataIndex: 'doctorIncome', width: 120,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Tag color={statusColors[v]}>{statusLabels[v] || v}</Tag>,
    },
    { title: '结算时间', dataIndex: 'settledAt', width: 170, render: (v: string) => v || '-' },
  ];

  return (
    <div>
      <h2>结算管理</h2>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="全部状态"
          allowClear
          options={statusOptions}
          style={{ width: 140 }}
          value={statusFilter}
          onChange={(value) => { setStatusFilter(value); setPage(0); }}
        />
      </Space>
      <Table
        dataSource={data}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </div>
  );
}
