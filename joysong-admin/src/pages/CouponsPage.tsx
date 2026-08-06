import { useEffect, useState } from 'react';
import {
  Table, Button, Popconfirm, message, Select, Space, Tag,
  Modal, Form, Input, InputNumber, DatePicker,
} from 'antd';
import { PlusOutlined, EditOutlined, StopOutlined, SendOutlined, EyeOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import dayjs from 'dayjs';

const typeLabels: Record<string, string> = {
  FIXED: '满减',
  PERCENTAGE: '折扣',
};

const statusLabels: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  EXPIRED: '已过期',
};

const statusColors: Record<string, string> = {
  ACTIVE: 'green',
  INACTIVE: 'default',
  EXPIRED: 'red',
};

const statusOptions = Object.keys(statusLabels).map(key => ({ label: statusLabels[key], value: key }));

export default function CouponsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  // 创建/编辑弹窗
  const [formVisible, setFormVisible] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  // 发放弹窗
  const [issueVisible, setIssueVisible] = useState(false);
  const [issueCouponId, setIssueCouponId] = useState<string | null>(null);
  const [issueUserIds, setIssueUserIds] = useState('');
  const [issuing, setIssuing] = useState(false);

  // 详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<any | null>(null);

  const couponType = Form.useWatch('type', form);

  const fetchData = () => {
    setLoading(true);
    const params: any = {};
    if (statusFilter) params.status = statusFilter;
    api.get('/admin/coupons', { params }).then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [statusFilter]);

  // 创建/编辑
  const openCreate = () => {
    setEditingRecord(null);
    form.resetFields();
    setFormVisible(true);
  };

  const openEdit = (record: any) => {
    setEditingRecord(record);
    form.setFieldsValue({
      ...record,
      validFrom: record.validFrom ? dayjs(record.validFrom) : undefined,
      validTo: record.validTo ? dayjs(record.validTo) : undefined,
      applicableProjectIds: record.applicableProjectIds?.join('\n') || '',
      applicableInstitutionIds: record.applicableInstitutionIds?.join('\n') || '',
    });
    setFormVisible(true);
  };

  const handleFormOk = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const payload = {
        ...values,
        validFrom: values.validFrom?.format('YYYY-MM-DD HH:mm:ss'),
        validTo: values.validTo?.format('YYYY-MM-DD HH:mm:ss'),
        applicableProjectIds: values.applicableProjectIds
          ? values.applicableProjectIds.split(/[\n,]+/).map((s: string) => s.trim()).filter(Boolean)
          : [],
        applicableInstitutionIds: values.applicableInstitutionIds
          ? values.applicableInstitutionIds.split(/[\n,]+/).map((s: string) => s.trim()).filter(Boolean)
          : [],
      };
      if (editingRecord) {
        await api.put(`/admin/coupons/${editingRecord.id}`, payload);
        message.success('更新成功');
      } else {
        await api.post('/admin/coupons', payload);
        message.success('创建成功');
      }
      setFormVisible(false);
      fetchData();
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setSubmitting(false);
    }
  };

  // 停用
  const handleDeactivate = async (id: string) => {
    await api.put(`/admin/coupons/${id}/deactivate`);
    message.success('已停用');
    fetchData();
  };

  // 发放
  const openIssue = (id: string) => {
    setIssueCouponId(id);
    setIssueUserIds('');
    setIssueVisible(true);
  };

  const handleIssue = async () => {
    if (!issueCouponId) return;
    const userIds = issueUserIds
      .split(/[\n,]+/)
      .map(s => s.trim())
      .filter(Boolean);
    if (userIds.length === 0) {
      message.warning('请输入至少一个用户ID');
      return;
    }
    setIssuing(true);
    try {
      await api.post(`/admin/coupons/${issueCouponId}/issue`, { userIds });
      message.success(`成功发放给 ${userIds.length} 位用户`);
      setIssueVisible(false);
      fetchData();
    } catch (err: any) {
      message.error('发放失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setIssuing(false);
    }
  };

  // 详情
  const openDetail = (record: any) => {
    setDetailRecord(record);
    setDetailVisible(true);
  };

  const columns = [
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '类型', dataIndex: 'type', width: 80,
      render: (v: string) => typeLabels[v] || v,
    },
    {
      title: '折扣值', dataIndex: 'discountValue', width: 90,
      render: (v: number, record: any) => record.type === 'PERCENTAGE' ? `${v}%` : `¥${v}`,
    },
    {
      title: '最低消费', dataIndex: 'minSpend', width: 100,
      render: (v: number) => v ? `¥${v}` : '-',
    },
    {
      title: '已发放/已使用', width: 130,
      render: (_: any, record: any) => `${record.issuedCount ?? 0} / ${record.usedCount ?? 0}`,
    },
    {
      title: '有效期', width: 200,
      render: (_: any, record: any) => {
        const from = record.validFrom ? record.validFrom.substring(0, 10) : '';
        const to = record.validTo ? record.validTo.substring(0, 10) : '';
        return from && to ? `${from} ~ ${to}` : '-';
      },
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: string) => <Tag color={statusColors[v]}>{statusLabels[v] || v}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 220, fixed: 'right' as const,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button icon={<EyeOutlined />} size="small" onClick={() => openDetail(record)}>详情</Button>
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)}>编辑</Button>
          <Button icon={<SendOutlined />} size="small" type="primary" onClick={() => openIssue(record.id)}>发放</Button>
          {record.status === 'ACTIVE' && (
            <Popconfirm title="确定停用该优惠券吗？" onConfirm={() => handleDeactivate(record.id)}>
              <Button icon={<StopOutlined />} size="small" danger>停用</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>优惠券管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建优惠券</Button>
      </div>

      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="全部状态"
          allowClear
          options={statusOptions}
          style={{ width: 140 }}
          value={statusFilter}
          onChange={(value) => setStatusFilter(value)}
        />
      </Space>

      <Table
        dataSource={data}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
      />

      {/* 创建/编辑弹窗 */}
      <Modal
        title={editingRecord ? '编辑优惠券' : '创建优惠券'}
        open={formVisible}
        onOk={handleFormOk}
        onCancel={() => setFormVisible(false)}
        confirmLoading={submitting}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="优惠券名称" />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select
              placeholder="请选择"
              options={[
                { label: '满减', value: 'FIXED' },
                { label: '折扣', value: 'PERCENTAGE' },
              ]}
            />
          </Form.Item>
          <Form.Item name="discountValue" label="折扣值" rules={[{ required: true, message: '请输入折扣值' }]}>
            <InputNumber min={0} style={{ width: '100%' }} placeholder={couponType === 'PERCENTAGE' ? '如：80 表示8折' : '如：100 表示减100元'} />
          </Form.Item>
          {couponType === 'FIXED' && (
            <Form.Item name="minSpend" label="最低消费" rules={[{ required: true, message: '请输入最低消费' }]}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="满多少可用" />
            </Form.Item>
          )}
          <Form.Item name="totalQuantity" label="发放总量">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="0 表示不限" />
          </Form.Item>
          <Form.Item name="validFrom" label="有效期开始" rules={[{ required: true, message: '请选择开始时间' }]}>
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="validTo" label="有效期结束" rules={[{ required: true, message: '请选择结束时间' }]}>
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="applicableProjectIds" label="适用项目IDs">
            <Input.TextArea rows={2} placeholder="每行一个或逗号分隔，留空表示全部适用" />
          </Form.Item>
          <Form.Item name="applicableInstitutionIds" label="适用机构IDs">
            <Input.TextArea rows={2} placeholder="每行一个或逗号分隔，留空表示全部适用" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 发放弹窗 */}
      <Modal
        title="发放优惠券"
        open={issueVisible}
        onOk={handleIssue}
        onCancel={() => setIssueVisible(false)}
        confirmLoading={issuing}
      >
        <p>请输入要发放的用户ID（每行一个或逗号分隔）：</p>
        <Input.TextArea
          rows={6}
          value={issueUserIds}
          onChange={e => setIssueUserIds(e.target.value)}
          placeholder={"user-id-1\nuser-id-2\nuser-id-3"}
        />
      </Modal>

      {/* 详情弹窗 */}
      <Modal
        title="优惠券详情"
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={<Button onClick={() => setDetailVisible(false)}>关闭</Button>}
        width={520}
      >
        {detailRecord && (
          <div style={{ lineHeight: 2 }}>
            <p><b>名称：</b>{detailRecord.name}</p>
            <p><b>类型：</b>{typeLabels[detailRecord.type] || detailRecord.type}</p>
            <p><b>折扣值：</b>{detailRecord.type === 'PERCENTAGE' ? `${detailRecord.discountValue}%` : `¥${detailRecord.discountValue}`}</p>
            <p><b>最低消费：</b>{detailRecord.minSpend ? `¥${detailRecord.minSpend}` : '无'}</p>
            <p><b>发放总量：</b>{detailRecord.totalQuantity || '不限'}</p>
            <p><b>已发放：</b>{detailRecord.issuedCount ?? 0}</p>
            <p><b>已使用：</b>{detailRecord.usedCount ?? 0}</p>
            <p><b>有效期：</b>{detailRecord.validFrom?.substring(0, 10)} ~ {detailRecord.validTo?.substring(0, 10)}</p>
            <p><b>状态：</b><Tag color={statusColors[detailRecord.status]}>{statusLabels[detailRecord.status] || detailRecord.status}</Tag></p>
            <p><b>适用项目：</b>{detailRecord.applicableProjectIds?.length ? detailRecord.applicableProjectIds.join(', ') : '全部'}</p>
            <p><b>适用机构：</b>{detailRecord.applicableInstitutionIds?.length ? detailRecord.applicableInstitutionIds.join(', ') : '全部'}</p>
          </div>
        )}
      </Modal>
    </div>
  );
}
