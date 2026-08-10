import { useEffect, useState } from 'react';
import { Button, Descriptions, Form, Input, message, Modal, Select, Space, Table, Tag } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import { identityStatusColor, identityStatusLabel } from '../identity';

type ProjectRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';

interface ProjectRequest {
  id: string;
  requestSource: 'PROFESSIONAL' | 'JOIN';
  requestType: 'PLATFORM' | 'INSTITUTION' | 'JOIN';
  doctorId: string;
  doctorName: string;
  institutionName?: string;
  projectName?: string;
  name?: string;
  category?: string;
  description?: string;
  serviceContent?: string;
  serviceDescription?: string;
  priceSuggestion?: number;
  notes?: string;
  status: ProjectRequestStatus;
  reviewNote?: string;
  submittedAt?: string;
}

type ReviewDecision = 'REJECTED' | 'CHANGES_REQUESTED';

export default function ProjectRequestsPage() {
  const isAdmin = getManagementContext()?.platformRole !== 'USER';
  const [requests, setRequests] = useState<ProjectRequest[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<ProjectRequestStatus | 'ALL'>('PENDING');
  const [reviewTarget, setReviewTarget] = useState<ProjectRequest | null>(null);
  const [reviewDecision, setReviewDecision] = useState<ReviewDecision>('CHANGES_REQUESTED');
  const [reviewForm] = Form.useForm();

  const refresh = async () => {
    setLoading(true);
    try {
      const [professionalResponse, joinResponse] = await Promise.all([
        api.get(isAdmin ? '/admin/project-requests' : '/management/project-requests'),
        api.get('/admin/institution-project-requests'),
      ]);
      const professional = (getData<Omit<ProjectRequest, 'requestSource'>[]>(professionalResponse as any) || [])
        .map(item => ({ ...item, requestSource: 'PROFESSIONAL' as const }));
      const joins = (getData<Omit<ProjectRequest, 'requestSource'>[]>(joinResponse as any) || [])
        .filter(item => item.requestType === 'JOIN')
        .map(item => ({ ...item, requestSource: 'JOIN' as const }));
      setRequests([...professional, ...joins]);
    } catch (error) {
      message.error(getApiErrorMessage(error, '平台项目申请加载失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const reviewPath = (request: ProjectRequest) => {
    if (request.requestSource === 'JOIN') {
      return `/admin/institution-project-requests/${request.id}/review`;
    }
    return request.requestType === 'PLATFORM'
      ? `/admin/project-requests/${request.id}/review`
      : `/management/project-requests/${request.id}/review`;
  };

  const approve = async (request: ProjectRequest) => {
    setSubmitting(true);
    try {
      await api.post(reviewPath(request), {
        decision: 'APPROVED',
        reviewNote: '',
      });
      message.success(request.requestType === 'PLATFORM'
        ? '申请已通过，平台项目已创建'
        : request.requestType === 'JOIN'
          ? '申请已通过，医生已加入机构项目'
          : '申请已通过，机构项目已创建');
      await refresh();
    } catch (error) {
      message.error(getApiErrorMessage(error, '审核失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const openReview = (request: ProjectRequest, decision: ReviewDecision) => {
    reviewForm.resetFields();
    setReviewTarget(request);
    setReviewDecision(decision);
  };

  const submitReview = async () => {
    if (!reviewTarget) return;
    try {
      const values = await reviewForm.validateFields();
      setSubmitting(true);
      await api.post(reviewPath(reviewTarget), {
        decision: reviewDecision,
        reviewNote: values.reviewNote,
      });
      message.success(reviewDecision === 'REJECTED' ? '申请已驳回' : '已要求医生修改申请');
      setReviewTarget(null);
      await refresh();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '审核失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const filteredRequests = status === 'ALL'
    ? requests
    : requests.filter(item => item.status === status);

  const columns = [
    {
      title: '类型', dataIndex: 'requestType', width: 140,
      render: (value: ProjectRequest['requestType']) => value === 'PLATFORM'
        ? '新增平台项目'
        : value === 'JOIN' ? '加入机构项目' : '新增机构项目',
    },
    { title: '医生', dataIndex: 'doctorName', width: 130 },
    { title: '机构', dataIndex: 'institutionName', width: 160, render: (value?: string) => value || '-' },
    { title: '项目名称', width: 180, render: (_: unknown, item: ProjectRequest) => item.name || item.projectName || '-' },
    { title: '分类', dataIndex: 'category', width: 120, render: (value?: string) => value || '-' },
    { title: '提交内容', ellipsis: true, render: (_: unknown, item: ProjectRequest) => item.description || item.serviceContent || item.serviceDescription || '-' },
    { title: '建议价格', dataIndex: 'priceSuggestion', width: 110, render: (value?: number) => value == null ? '-' : `¥${value}` },
    { title: '补充说明', dataIndex: 'notes', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (value: ProjectRequestStatus) => <Tag color={identityStatusColor(value)}>{identityStatusLabel(value)}</Tag>,
    },
    { title: '审核意见', dataIndex: 'reviewNote', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '操作', width: 240,
      render: (_: unknown, item: ProjectRequest) => item.status === 'PENDING' ? <Space wrap>
        <Button aria-label="通过" size="small" type="primary" icon={<CheckOutlined />} loading={submitting} onClick={() => void approve(item)}>通过</Button>
        <Button aria-label="要求修改" size="small" icon={<EditOutlined />} onClick={() => openReview(item, 'CHANGES_REQUESTED')}>要求修改</Button>
        <Button aria-label="驳回" size="small" danger icon={<CloseOutlined />} onClick={() => openReview(item, 'REJECTED')}>驳回</Button>
      </Space> : null,
    },
  ];

  return <div>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 16 }}>
      <h2 style={{ margin: 0 }}>项目申请审核</h2>
      <Select
        value={status}
        style={{ width: 140 }}
        onChange={setStatus}
        options={[
          { label: '待审核', value: 'PENDING' },
          { label: '全部状态', value: 'ALL' },
          { label: '已通过', value: 'APPROVED' },
          { label: '已驳回', value: 'REJECTED' },
          { label: '待修改', value: 'CHANGES_REQUESTED' },
        ]}
      />
    </div>
    <Table
      rowKey={item => `${item.requestSource}-${item.id}`}
      dataSource={filteredRequests}
      columns={columns}
      loading={loading}
      size="small"
      scroll={{ x: 'max-content' }}
      expandable={{
        expandedRowRender: item => <Descriptions size="small" column={1} items={[
          { key: 'description', label: item.requestType === 'PLATFORM' ? '项目说明' : '服务内容', children: item.description || item.serviceContent || item.serviceDescription || '-' },
          { key: 'price', label: '建议价格', children: item.priceSuggestion == null ? '-' : `¥${item.priceSuggestion}` },
          { key: 'notes', label: '补充说明', children: item.notes || '-' },
        ]} />,
      }}
    />

    <Modal
      title={reviewDecision === 'REJECTED' ? '驳回项目申请' : '要求医生修改申请'}
      open={Boolean(reviewTarget)}
      onOk={() => void submitReview()}
      onCancel={() => setReviewTarget(null)}
      confirmLoading={submitting}
      okText="确认"
      okButtonProps={{ danger: reviewDecision === 'REJECTED' }}
      destroyOnHidden
    >
      <Form form={reviewForm} layout="vertical">
        <Form.Item
          name="reviewNote"
          label="审核意见"
          rules={[{ required: true, whitespace: true, message: '请填写审核意见' }, { max: 1000 }]}
        >
          <Input.TextArea rows={4} />
        </Form.Item>
      </Form>
    </Modal>
  </div>;
}
