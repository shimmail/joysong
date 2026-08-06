import { useEffect, useState } from 'react';
import { Table, Input, Space } from 'antd';
import api, { getData } from '../api';

const methodLabels: Record<string, string> = {
  ALIPAY: '支付宝',
  WECHAT: '微信支付',
  CARD: '银行卡',
};

export default function PaymentsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');

  const fetchData = () => {
    setLoading(true);
    api.get('/admin/payments').then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const filteredData = data.filter(item => {
    return keyword
      ? (item.orderId?.includes(keyword) || item.transactionId?.includes(keyword) || item.id?.includes(keyword))
      : true;
  });

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '订单ID', dataIndex: 'orderId', width: 160 },
    { title: '用户ID', dataIndex: 'userId', width: 160 },
    { title: '金额', dataIndex: 'amount', width: 100 },
    {
      title: '方式',
      dataIndex: 'method',
      width: 120,
      render: (m: string) => methodLabels[m] ?? m,
    },
    { title: '状态', dataIndex: 'status', width: 120 },
    { title: '交易号', dataIndex: 'transactionId', width: 160 },
    { title: '支付时间', dataIndex: 'paidAt', width: 160 },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
  ];

  return (
    <div>
      <h2>支付记录</h2>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="搜索订单ID、交易号"
          allowClear
          onSearch={(value) => setKeyword(value)}
          style={{ width: 300 }}
        />
      </Space>
      <Table dataSource={filteredData} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }} />
    </div>
  );
}
