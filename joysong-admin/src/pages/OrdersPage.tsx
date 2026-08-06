import { useEffect, useState } from 'react';
import { Table, Button, Popconfirm, message, Select, Input, Space, Tag, Modal, Form } from 'antd';
import { DeleteOutlined, SwapOutlined, HistoryOutlined, RollbackOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';

const ORDER_STATUSES = [
  { value: 'PENDING_PAYMENT', label: '待支付面诊金', color: 'orange' },
  { value: 'CONSULTATION_PAID', label: '面诊金已付', color: 'blue' },
  { value: 'VERIFIED', label: '已核验', color: 'cyan' },
  { value: 'BALANCE_PAID', label: '全款已付', color: 'green' },
  { value: 'PENDING_COMPLETION', label: '待确认完成', color: 'purple' },
  { value: 'COMPLETED', label: '已完成', color: 'success' },
  { value: 'PENDING_SETTLEMENT', label: '待结算', color: 'geekblue' },
  { value: 'SETTLED', label: '已结算', color: 'default' },
  { value: 'DISPUTE_MEDIATION', label: '纠纷调解', color: 'red' },
  { value: 'CANCELLED', label: '已取消', color: 'default' },
  { value: 'REFUNDED', label: '已退款', color: 'volcano' },
];

const STATUS_TRANSITIONS: Record<string, string[]> = {
  'PENDING_PAYMENT': ['CONSULTATION_PAID', 'CANCELLED'],
  'CONSULTATION_PAID': ['VERIFIED', 'CANCELLED', 'REFUNDED'],
  'VERIFIED': ['BALANCE_PAID', 'PENDING_COMPLETION', 'CANCELLED', 'REFUNDED'],
  'BALANCE_PAID': ['PENDING_COMPLETION', 'REFUNDED'],
  'PENDING_COMPLETION': ['COMPLETED', 'DISPUTE_MEDIATION'],
  'COMPLETED': ['PENDING_SETTLEMENT'],
  'PENDING_SETTLEMENT': ['SETTLED', 'DISPUTE_MEDIATION'],
  'DISPUTE_MEDIATION': ['PENDING_COMPLETION', 'CANCELLED', 'REFUNDED', 'SETTLED'],
  'SETTLED': [],
  'CANCELLED': [],
  'REFUNDED': [],
};

const statusMap = Object.fromEntries(ORDER_STATUSES.map(s => [s.value, s]));
const statusOptions = ORDER_STATUSES.map(s => ({ label: s.label, value: s.value }));

export default function OrdersPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const navigate = useNavigate();
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  // 状态变更弹窗
  const [transitionVisible, setTransitionVisible] = useState(false);
  const [transitionOrder, setTransitionOrder] = useState<any | null>(null);
  const [transitionTarget, setTransitionTarget] = useState<string | undefined>(undefined);
  const [transitioning, setTransitioning] = useState(false);

  // 状态日志弹窗
  const [logVisible, setLogVisible] = useState(false);
  const [logData, setLogData] = useState<any[]>([]);
  const [logLoading, setLogLoading] = useState(false);
  const [verificationOrder, setVerificationOrder] = useState<any | null>(null);
  const [verificationAction, setVerificationAction] = useState<'verify' | 'request-completion'>('verify');
  const [verificationSubmitting, setVerificationSubmitting] = useState(false);
  const [verificationForm] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    api.get(isAdmin ? '/admin/orders' : '/management/orders')
      .then(res => setData(getData(res as any)))
      .catch(error => message.error(getApiErrorMessage(error, '订单加载失败')))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleDelete = async (id: string) => {
    await api.delete(`/admin/orders/${id}`);
    message.success('删除成功');
    fetchData();
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`/admin/orders/${id}`)));
      message.success(`成功删除 ${selectedRowKeys.length} 条记录`);
      setSelectedRowKeys([]);
      fetchData();
    } catch (err: any) {
      message.error('批量删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  // 打开状态变更弹窗
  const openTransition = (record: any) => {
    setTransitionOrder(record);
    setTransitionTarget(undefined);
    setTransitionVisible(true);
  };

  // 执行状态变更
  const handleTransition = async () => {
    if (!transitionOrder || !transitionTarget) return;
    setTransitioning(true);
    try {
      await api.put(`/admin/orders/${transitionOrder.id}/status`, { status: transitionTarget });
      message.success('状态更新成功');
      setTransitionVisible(false);
      fetchData();
    } catch (err: any) {
      message.error('状态更新失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setTransitioning(false);
    }
  };

  // 查看状态日志
  const openLog = async (orderId: string) => {
    setLogVisible(true);
    setLogLoading(true);
    try {
      const res = await api.get(`/admin/orders/${orderId}/status-logs`);
      setLogData(getData(res as any) || []);
    } catch {
      setLogData([]);
    } finally {
      setLogLoading(false);
    }
  };

  const openVerification = (record: any, action: 'verify' | 'request-completion') => {
    verificationForm.resetFields();
    setVerificationOrder(record);
    setVerificationAction(action);
  };

  const submitVerification = async () => {
    if (!verificationOrder) return;
    try {
      const values = await verificationForm.validateFields();
      setVerificationSubmitting(true);
      await api.post(`/management/orders/${verificationOrder.id}/${verificationAction}`, {
        verificationCode: values.verificationCode,
      });
      message.success(verificationAction === 'verify' ? '首次到店核销成功' : '已提交项目完成申请');
      setVerificationOrder(null);
      fetchData();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '核销失败'));
    } finally {
      setVerificationSubmitting(false);
    }
  };

  const filteredData = data.filter(item => {
    const matchStatus = statusFilter ? item.status === statusFilter : true;
    const matchKeyword = keyword
      ? (item.projectName?.includes(keyword) || item.institutionName?.includes(keyword) || item.id?.includes(keyword))
      : true;
    return matchStatus && matchKeyword;
  });

  // 状态变更弹窗中可选项
  const availableTransitions = transitionOrder
    ? (STATUS_TRANSITIONS[transitionOrder.status] || [])
    : [];
  const transitionOptions = availableTransitions.map(s => {
    const info = statusMap[s];
    return { label: info?.label || s, value: s };
  });

  const logColumns = [
    { title: '变更时间', dataIndex: 'changedAt', width: 170 },
    {
      title: '从状态', dataIndex: 'fromStatus', width: 130,
      render: (v: string) => {
        const info = statusMap[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : (v || '-');
      },
    },
    {
      title: '到状态', dataIndex: 'toStatus', width: 130,
      render: (v: string) => {
        const info = statusMap[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : v;
      },
    },
    { title: '操作人类型', dataIndex: 'operatorType', width: 110 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
  ];

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '项目', dataIndex: 'projectName', width: 180 },
    { title: '机构', dataIndex: 'institutionName', width: 150 },
    { title: '价格', dataIndex: 'price', width: 80 },
    {
      title: '面诊金', dataIndex: 'consultationFee', width: 80,
      render: (v: number) => v != null ? `¥${v}` : '-',
    },
    {
      title: '尾款', dataIndex: 'remainingAmount', width: 80,
      render: (v: number) => v != null ? `¥${v}` : '-',
    },
    {
      title: '优惠金额', dataIndex: 'discountAmount', width: 90,
      render: (v: number) => v != null ? `¥${v}` : '-',
    },
    {
      title: '状态', dataIndex: 'status', width: 130,
      render: (s: string) => {
        const info = statusMap[s];
        return info ? <Tag color={info.color}>{info.label}</Tag> : s;
      },
    },
    {
      title: '退款金额', dataIndex: 'refundAmount', width: 90,
      render: (v: number) => {
        if (v && v > 0) return <Tag color="volcano">¥{v}</Tag>;
        return '-';
      },
    },
    { title: '核验时间', dataIndex: 'verifiedAt', width: 160, render: (v: string) => v || '-' },
    { title: '结算到期', dataIndex: 'settlementAt', width: 160, render: (v: string) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
    { title: '预约时间', dataIndex: 'appointmentTime', width: 160 },
    {
      title: '操作', key: 'actions', width: 200, fixed: 'right' as const,
      render: (_: any, record: any) => (
        <Space size="small">
          {isAdmin && <Button
            icon={<SwapOutlined />}
            size="small"
            onClick={() => openTransition(record)}
            disabled={!STATUS_TRANSITIONS[record.status]?.length}
          >
            变更状态
          </Button>}
          {isAdmin && <Button
            icon={<HistoryOutlined />}
            size="small"
            onClick={() => openLog(record.id)}
          >
            状态日志
          </Button>}
          {isAdmin && record.refundStatus && record.refundStatus !== 'NONE' && (
            <Button
              icon={<RollbackOutlined />}
              size="small"
              onClick={() => navigate('/refunds')}
            >
              查看退款
            </Button>
          )}
          {!isAdmin && record.status === 'CONSULTATION_PAID' && (
            <Button type="primary" size="small" icon={<SafetyCertificateOutlined />} onClick={() => openVerification(record, 'verify')}>
              首次到店核销
            </Button>
          )}
          {!isAdmin && record.status === 'BALANCE_PAID' && (
            <Button type="primary" size="small" icon={<SafetyCertificateOutlined />} onClick={() => openVerification(record, 'request-completion')}>
              申请项目完成
            </Button>
          )}
          {isAdmin && <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
          </Popconfirm>}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{isAdmin ? '订单管理' : '相关订单'}</h2>
        {isAdmin && <Popconfirm title={`确定删除选中的 ${selectedRowKeys.length} 条记录吗？`} onConfirm={handleBatchDelete} disabled={selectedRowKeys.length === 0}>
          <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
            批量删除{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
          </Button>
        </Popconfirm>}
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="全部状态"
          allowClear
          options={statusOptions}
          style={{ width: 160 }}
          value={statusFilter}
          onChange={(value) => setStatusFilter(value)}
        />
        <Input.Search
          placeholder="搜索项目、机构、订单号"
          allowClear
          onSearch={(value) => setKeyword(value)}
          style={{ width: 260 }}
        />
      </Space>
      <Table dataSource={filteredData} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }}
        rowSelection={isAdmin ? {
          selectedRowKeys,
          onChange: keys => setSelectedRowKeys(keys),
        } : undefined}
      />

      {/* 状态变更弹窗 */}
      <Modal
        title="变更订单状态"
        open={transitionVisible}
        onOk={handleTransition}
        onCancel={() => setTransitionVisible(false)}
        confirmLoading={transitioning}
        okButtonProps={{ disabled: !transitionTarget }}
      >
        {transitionOrder && (
          <div>
            <p style={{ marginBottom: 12 }}>
              <b>当前状态：</b>
              <Tag color={statusMap[transitionOrder.status]?.color}>
                {statusMap[transitionOrder.status]?.label || transitionOrder.status}
              </Tag>
            </p>
            {availableTransitions.length > 0 ? (
              <div>
                <p style={{ marginBottom: 8 }}><b>选择目标状态：</b></p>
                <Select
                  placeholder="请选择目标状态"
                  style={{ width: '100%' }}
                  options={transitionOptions}
                  value={transitionTarget}
                  onChange={setTransitionTarget}
                />
              </div>
            ) : (
              <p style={{ color: '#999' }}>当前状态无可用转换</p>
            )}
          </div>
        )}
      </Modal>

      <Modal
        title={verificationAction === 'verify' ? '首次到店核销' : '申请项目完成'}
        open={Boolean(verificationOrder)}
        onOk={() => void submitVerification()}
        onCancel={() => setVerificationOrder(null)}
        confirmLoading={verificationSubmitting}
        okText="确认核销"
        destroyOnHidden
      >
        <p style={{ color: '#777' }}>
          请输入用户订单页展示的 6 位核销码。核销码仅用于本次状态确认，请勿通过聊天索取登录验证码。
        </p>
        <Form form={verificationForm} layout="vertical">
          <Form.Item
            name="verificationCode"
            label="6 位核销码"
            rules={[
              { required: true, message: '请输入核销码' },
              { pattern: /^\d{6}$/, message: '核销码必须为 6 位数字' },
            ]}
          >
            <Input inputMode="numeric" maxLength={6} autoComplete="one-time-code" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 状态日志弹窗 */}
      <Modal
        title="状态变更日志"
        open={logVisible}
        onCancel={() => setLogVisible(false)}
        footer={<Button onClick={() => setLogVisible(false)}>关闭</Button>}
        width={720}
      >
        <Table
          dataSource={logData}
          columns={logColumns}
          rowKey={(r, i) => `${r.changedAt}-${i}`}
          loading={logLoading}
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
      </Modal>
    </div>
  );
}
