import { useEffect, useState } from 'react';
import { Table, Button, message, Select, Input, Space, Tag, Modal, Descriptions } from 'antd';
import { CheckOutlined, CloseOutlined, EyeOutlined } from '@ant-design/icons';
import api, { getData } from '../api';

const statusLabels: Record<string, string> = {
  PENDING: '待处理',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  COMPLETED: '已完成',
};

const statusColors: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
  COMPLETED: 'blue',
};

const refundTypeLabels: Record<string, string> = {
  FULL: '全额退款',
  PARTIAL: '部分退款',
};

export default function RefundsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string>('PENDING');
  const [keyword, setKeyword] = useState('');

  // 审批确认弹窗
  const [approveVisible, setApproveVisible] = useState(false);
  const [approveRecord, setApproveRecord] = useState<any | null>(null);
  const [approveLoading, setApproveLoading] = useState(false);

  // 拒绝弹窗
  const [rejectVisible, setRejectVisible] = useState(false);
  const [rejectRecord, setRejectRecord] = useState<any | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [rejectLoading, setRejectLoading] = useState(false);

  // 详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<any | null>(null);

  const fetchData = () => {
    setLoading(true);
    api.get('/admin/refunds').then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleApprove = async () => {
    if (!approveRecord) return;
    setApproveLoading(true);
    try {
      await api.put(`/admin/refunds/${approveRecord.id}/status`, { status: 'APPROVED' });
      message.success('退款已批准');
      setApproveVisible(false);
      setApproveRecord(null);
      fetchData();
    } catch (err: any) {
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setApproveLoading(false);
    }
  };

  const handleReject = async () => {
    if (!rejectRecord) return;
    if (!rejectReason.trim()) {
      message.warning('请填写拒绝原因');
      return;
    }
    setRejectLoading(true);
    try {
      await api.put(`/admin/refunds/${rejectRecord.id}/status`, {
        status: 'REJECTED',
        rejectReason: rejectReason.trim(),
      });
      message.success('退款已拒绝');
      setRejectVisible(false);
      setRejectRecord(null);
      setRejectReason('');
      fetchData();
    } catch (err: any) {
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setRejectLoading(false);
    }
  };

  const openApprove = (record: any) => {
    setApproveRecord(record);
    setApproveVisible(true);
  };

  const openReject = (record: any) => {
    setRejectRecord(record);
    setRejectReason('');
    setRejectVisible(true);
  };

  const openDetail = (record: any) => {
    setDetailRecord(record);
    setDetailVisible(true);
  };

  const filteredData = data.filter(item => {
    const matchStatus = statusFilter ? item.status === statusFilter : true;
    const matchKeyword = keyword
      ? (item.orderId?.includes(keyword) || item.reason?.includes(keyword) || item.id?.includes(keyword)
        || item.orderNo?.includes(keyword) || item.projectName?.includes(keyword))
      : true;
    return matchStatus && matchKeyword;
  });

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: string) => v?.slice(0, 8) },
    { title: '订单编号', dataIndex: 'orderNo', width: 140, render: (v: string) => v || '-' },
    { title: '项目', dataIndex: 'projectName', width: 160, ellipsis: true, render: (v: string) => v || '-' },
    { title: '机构', dataIndex: 'institutionName', width: 130, ellipsis: true, render: (v: string) => v || '-' },
    { title: '医生', dataIndex: 'doctorName', width: 100, render: (v: string) => v || '-' },
    {
      title: '退款金额', dataIndex: 'amount', width: 100,
      render: (v: number) => <span style={{ color: '#f5222d', fontWeight: 600 }}>¥{v}</span>,
    },
    {
      title: '订单金额', dataIndex: 'paymentAmount', width: 100,
      render: (v: number) => v ? `¥${v}` : '-',
    },
    {
      title: '退款类型', dataIndex: 'refundType', width: 90,
      render: (v: string) => refundTypeLabels[v] || v || '全额退款',
    },
    { title: '原因', dataIndex: 'reason', width: 180, ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (s: string) => <Tag color={statusColors[s] || 'default'}>{statusLabels[s] ?? s}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
    { title: '处理时间', dataIndex: 'processedAt', width: 160, render: (v: string) => v || '-' },
    {
      title: '操作', key: 'actions', width: 200, fixed: 'right' as const,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button icon={<EyeOutlined />} size="small" onClick={() => openDetail(record)}>详情</Button>
          {record.status === 'PENDING' && (
            <>
              <Button icon={<CheckOutlined />} size="small" type="primary" onClick={() => openApprove(record)}>批准</Button>
              <Button icon={<CloseOutlined />} size="small" danger onClick={() => openReject(record)}>拒绝</Button>
            </>
          )}
        </Space>
      ),
    },
  ];

  const statusOptions = [
    { label: '全部', value: '' },
    ...Object.keys(statusLabels).map(key => ({ label: statusLabels[key], value: key })),
  ];

  return (
    <div>
      <h2>退款管理</h2>
      <Space style={{ marginBottom: 16 }}>
        <Select
          value={statusFilter}
          options={statusOptions}
          style={{ width: 140 }}
          onChange={(value) => setStatusFilter(value)}
        />
        <Input.Search
          placeholder="搜索订单编号、项目、原因、退款ID"
          allowClear
          onSearch={(value) => setKeyword(value)}
          style={{ width: 300 }}
        />
      </Space>
      <Table
        dataSource={filteredData}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
      />

      {/* 批准确认弹窗 */}
      <Modal
        title="确认批准退款"
        open={approveVisible}
        onOk={handleApprove}
        onCancel={() => { setApproveVisible(false); setApproveRecord(null); }}
        confirmLoading={approveLoading}
        okText="确认批准"
        okButtonProps={{ danger: true }}
      >
        {approveRecord && (
          <div>
            <p>确定批准该退款申请吗？</p>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="订单编号">{approveRecord.orderNo || '-'}</Descriptions.Item>
              <Descriptions.Item label="项目">{approveRecord.projectName || '-'}</Descriptions.Item>
              <Descriptions.Item label="退款金额"><span style={{ color: '#f5222d', fontWeight: 600 }}>¥{approveRecord.amount}</span></Descriptions.Item>
              <Descriptions.Item label="退款原因">{approveRecord.reason}</Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Modal>

      {/* 拒绝确认弹窗 */}
      <Modal
        title="拒绝退款申请"
        open={rejectVisible}
        onOk={handleReject}
        onCancel={() => { setRejectVisible(false); setRejectRecord(null); setRejectReason(''); }}
        confirmLoading={rejectLoading}
        okText="确认拒绝"
        okButtonProps={{ danger: true, disabled: !rejectReason.trim() }}
      >
        {rejectRecord && (
          <div>
            <p>拒绝退款：<b>{rejectRecord.orderNo || rejectRecord.orderId}</b>，退款金额 <span style={{ color: '#f5222d' }}>¥{rejectRecord.amount}</span></p>
            <p style={{ marginBottom: 8 }}><b>拒绝原因 <span style={{ color: '#f5222d' }}>*</span></b></p>
            <Input.TextArea
              rows={3}
              placeholder="请填写拒绝原因（必填）"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              maxLength={200}
              showCount
            />
          </div>
        )}
      </Modal>

      {/* 退款详情弹窗 */}
      <Modal
        title="退款详情"
        open={detailVisible}
        onCancel={() => { setDetailVisible(false); setDetailRecord(null); }}
        footer={<Button onClick={() => setDetailVisible(false)}>关闭</Button>}
        width={600}
      >
        {detailRecord && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="退款ID" span={2}>{detailRecord.id}</Descriptions.Item>
            <Descriptions.Item label="订单ID" span={2}>{detailRecord.orderId}</Descriptions.Item>
            <Descriptions.Item label="订单编号">{detailRecord.orderNo || '-'}</Descriptions.Item>
            <Descriptions.Item label="用户ID">{detailRecord.userId}</Descriptions.Item>
            <Descriptions.Item label="项目">{detailRecord.projectName || '-'}</Descriptions.Item>
            <Descriptions.Item label="机构">{detailRecord.institutionName || '-'}</Descriptions.Item>
            <Descriptions.Item label="医生">{detailRecord.doctorName || '-'}</Descriptions.Item>
            <Descriptions.Item label="用户电话">{detailRecord.userPhone || '-'}</Descriptions.Item>
            <Descriptions.Item label="退款金额"><span style={{ color: '#f5222d', fontWeight: 600 }}>¥{detailRecord.amount}</span></Descriptions.Item>
            <Descriptions.Item label="订单金额">{detailRecord.paymentAmount ? `¥${detailRecord.paymentAmount}` : '-'}</Descriptions.Item>
            <Descriptions.Item label="退款类型">{refundTypeLabels[detailRecord.refundType] || detailRecord.refundType || '全额退款'}</Descriptions.Item>
            <Descriptions.Item label="状态"><Tag color={statusColors[detailRecord.status]}>{statusLabels[detailRecord.status] ?? detailRecord.status}</Tag></Descriptions.Item>
            <Descriptions.Item label="退款原因" span={2}>{detailRecord.reason}</Descriptions.Item>
            <Descriptions.Item label="详细说明" span={2}>{detailRecord.description || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{detailRecord.createdAt}</Descriptions.Item>
            <Descriptions.Item label="处理时间">{detailRecord.processedAt || '-'}</Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
}
