import { useEffect, useState } from 'react';
import { Table, Input, Space, Tag } from 'antd';
import api, { getData } from '../api';
import { formatMoney } from '../utils/money';

const methodLabels: Record<string, string> = {
  ALIPAY: '支付宝',
  ALIPAY_PLUS_CASHIER: 'Alipay+ 托管收银台',
  WECHAT: '微信支付',
  CARD: '银行卡',
};

const paymentTypeLabels: Record<string, string> = {
  CONSULTATION_FEE: '面诊金（历史）',
  BALANCE: '尾款（历史）',
  TRAVEL_GROUND_SERVICE_FEE: '旅游地接服务费',
};

const providerLabels: Record<string, string> = {
  ALIPAY_PLUS: 'Alipay+',
};

const statusLabels: Record<string, string> = {
  CREATED: '已创建',
  REQUIRES_ACTION: '待用户操作',
  PROCESSING: '渠道处理中',
  SUCCEEDED: '支付成功',
  FAILED: '支付失败',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
  PARTIALLY_REFUNDED: '部分退款',
  REFUNDED: '已退款',
};

const statusColors: Record<string, string> = {
  CREATED: 'default',
  REQUIRES_ACTION: 'orange',
  PROCESSING: 'blue',
  SUCCEEDED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
  EXPIRED: 'volcano',
  PARTIALLY_REFUNDED: 'gold',
  REFUNDED: 'purple',
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
    {
      title: '支付项目', dataIndex: 'paymentType', width: 160,
      render: (value: string) => paymentTypeLabels[value] ?? value ?? '-',
    },
    {
      title: '金额', dataIndex: 'amountMinor', width: 120,
      render: (value: number, record: any) => formatMoney(value, record.currency, record.amount),
    },
    {
      title: '已退款金额', dataIndex: 'refundedAmountMinor', width: 120,
      render: (value: number, record: any) => formatMoney(value, record.currency),
    },
    {
      title: '渠道', dataIndex: 'provider', width: 110,
      render: (value: string) => providerLabels[value] ?? value ?? '-',
    },
    {
      title: '方式',
      dataIndex: 'paymentMethod',
      width: 150,
      render: (value: string, record: any) => {
        const method = value || record.method;
        return methodLabels[method] ?? method ?? '-';
      },
    },
    {
      title: '状态', dataIndex: 'status', width: 180,
      render: (value: string) => (
        <Tag color={statusColors[value] || 'default'}>
          {statusLabels[value] ? `${statusLabels[value]}（${value}）` : value}
        </Tag>
      ),
    },
    {
      title: '交易号', dataIndex: 'transactionId', width: 160,
      render: (value: string, record: any) => value || record.providerTransactionId || '-',
    },
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
