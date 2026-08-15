import { useEffect, useState } from 'react';
import { Button, Descriptions, Form, Input, message, Modal, Select, Space, Table, Tag } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import { identityStatusColor, identityStatusLabel } from '../identity';

type ProjectRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';

export interface InstitutionProjectSplit {
  consultationFee: number;
  commissionRate: number;
  institutionRate: number;
  platformRate: number;
  doctorRate: number;
}

export interface ProfessionalProjectRequestResponse {
  id: string;
  requestType: 'PLATFORM' | 'INSTITUTION';
  doctorId: string;
  doctorName: string;
  institutionId: string | null;
  institutionName: string | null;
  projectId: string | null;
  projectName: string | null;
  name: string | null;
  category: string | null;
  description: string | null;
  tags: string[] | null;
  slogan: string | null;
  detailContent: string | null;
  currency: string;
  coverImage: string | null;
  images: string[] | null;
  salesCount: number;
  referencePrice: number | null;
  categoryTags: string[] | null;
  price: number | null;
  originalPrice: number | null;
  isActive: boolean | null;
  institutionSplit: InstitutionProjectSplit | null;
  notes: string | null;
  status: ProjectRequestStatus;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  resultingProjectId: string | null;
  resultingInstitutionProjectId: string | null;
  submittedAt: string;
  updatedAt: string;
}

type ProfessionalProjectRequest = ProfessionalProjectRequestResponse & {
  requestSource: 'PROFESSIONAL';
};

interface JoinProjectRequest {
  id: string;
  requestSource: 'JOIN';
  requestType: 'JOIN';
  doctorId: string;
  doctorName: string;
  institutionId: string;
  institutionName: string;
  institutionProjectId: string;
  projectName: string;
  serviceDescription: string;
  priceSuggestion?: number | null;
  notes?: string | null;
  status: ProjectRequestStatus;
  reviewNote?: string | null;
  submittedAt?: string | null;
}

type ProjectRequest = ProfessionalProjectRequest | JoinProjectRequest;
type ReviewDecision = 'REJECTED' | 'CHANGES_REQUESTED';

const textOrDash = (value?: string | null) => value || '-';
const listOrDash = (values?: string[] | null) => values?.length ? values.join('、') : '-';
const moneyOrDash = (currency: string, value?: number | null) => value == null ? '-' : `${currency} ${value}`;
const imageListOrDash = (values?: string[] | null) => values?.length
  ? <Space orientation="vertical" size={0}>{values.map(value => <span key={value}>{value}</span>)}</Space>
  : '-';

export default function ProjectRequestsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const [requests, setRequests] = useState<ProjectRequest[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<ProjectRequestStatus | 'ALL'>('PENDING');
  const [reviewTarget, setReviewTarget] = useState<ProjectRequest | null>(null);
  const [reviewDecision, setReviewDecision] = useState<ReviewDecision>('REJECTED');
  const [reviewForm] = Form.useForm();

  const refresh = async () => {
    setLoading(true);
    try {
      const [professionalResponse, joinResponse] = await Promise.all([
        api.get(isAdmin ? '/admin/project-requests' : '/management/project-requests'),
        api.get('/admin/institution-project-requests'),
      ]);
      const professional = (getData<ProfessionalProjectRequestResponse[]>(professionalResponse as any) || [])
        .map(item => ({ ...item, requestSource: 'PROFESSIONAL' as const }));
      const joins = (getData<Omit<JoinProjectRequest, 'requestSource'>[]>(joinResponse as any) || [])
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

  const canReview = (request: ProjectRequest) => {
    if (request.status !== 'PENDING') return false;
    if (isAdmin) return true;
    if (request.requestSource === 'PROFESSIONAL') {
      return request.requestType === 'INSTITUTION'
        && managementContext?.canReviewInstitutionProjectRequests === true
        && request.institutionId != null
        && managementContext.managedInstitutionIds.includes(request.institutionId);
    }
    return managementContext?.canReviewInstitutionRequests === true
      && managementContext.managedInstitutionIds.includes(request.institutionId);
  };

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

  const renderActions = (item: ProjectRequest) => {
    if (!canReview(item)) return null;
    return <Space wrap>
      <Button aria-label="通过" size="small" type="primary" icon={<CheckOutlined />} loading={submitting} onClick={() => void approve(item)}>通过</Button>
      {item.requestSource === 'JOIN' && <Button aria-label="要求修改" size="small" icon={<EditOutlined />} onClick={() => openReview(item, 'CHANGES_REQUESTED')}>要求修改</Button>}
      <Button aria-label="驳回" size="small" danger icon={<CloseOutlined />} onClick={() => openReview(item, 'REJECTED')}>驳回</Button>
    </Space>;
  };

  const columns = [
    {
      title: '类型', dataIndex: 'requestType', width: 140,
      render: (value: ProjectRequest['requestType']) => value === 'PLATFORM'
        ? '新增平台项目'
        : value === 'JOIN' ? '加入机构项目' : '新增机构项目',
    },
    { title: '医生', dataIndex: 'doctorName', width: 130 },
    { title: '机构', dataIndex: 'institutionName', width: 160, render: (value?: string) => value || '-' },
    {
      title: '项目名称', width: 180,
      render: (_: unknown, item: ProjectRequest) => item.requestSource === 'PROFESSIONAL'
        ? item.name || item.projectName || '-'
        : item.projectName || '-',
    },
    {
      title: '分类', width: 120,
      render: (_: unknown, item: ProjectRequest) => item.requestSource === 'PROFESSIONAL' ? textOrDash(item.category) : '-',
    },
    {
      title: '提交内容', ellipsis: true,
      render: (_: unknown, item: ProjectRequest) => item.requestSource === 'PROFESSIONAL'
        ? textOrDash(item.description)
        : textOrDash(item.serviceDescription),
    },
    {
      title: '申请价格', width: 120,
      render: (_: unknown, item: ProjectRequest) => item.requestSource === 'PROFESSIONAL'
        ? moneyOrDash(item.currency, item.requestType === 'PLATFORM' ? item.referencePrice : item.price)
        : item.priceSuggestion == null ? '-' : `¥${item.priceSuggestion}`,
    },
    { title: '补充说明', dataIndex: 'notes', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (value: ProjectRequestStatus) => <Tag color={identityStatusColor(value)}>{identityStatusLabel(value)}</Tag>,
    },
    { title: '审核意见', dataIndex: 'reviewNote', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
    { title: '操作', width: 200, render: (_: unknown, item: ProjectRequest) => renderActions(item) },
  ];

  const expandedItems = (item: ProjectRequest) => {
    if (item.requestSource === 'JOIN') {
      return [
        { key: 'description', label: '服务内容', children: textOrDash(item.serviceDescription) },
        { key: 'price', label: '建议价格', children: item.priceSuggestion == null ? '-' : `¥${item.priceSuggestion}` },
        { key: 'notes', label: '补充说明', children: textOrDash(item.notes) },
      ];
    }

    const common = [
      { key: 'id', label: '申请 ID', children: item.id },
      { key: 'doctorId', label: '申请医生 ID', children: item.doctorId },
      { key: 'doctorName', label: '申请医生', children: item.doctorName },
      { key: 'institutionId', label: '目标机构 ID', children: textOrDash(item.institutionId) },
      { key: 'institutionName', label: '目标机构', children: textOrDash(item.institutionName) },
      { key: 'projectId', label: '目标公共项目 ID', children: textOrDash(item.projectId) },
      { key: 'projectName', label: '目标公共项目', children: textOrDash(item.projectName) },
      { key: 'name', label: '项目名称', children: textOrDash(item.name) },
      { key: 'category', label: '分类', children: textOrDash(item.category) },
      { key: 'description', label: '项目说明', children: textOrDash(item.description) },
      { key: 'tags', label: '标签', children: listOrDash(item.tags) },
      { key: 'slogan', label: '宣传语', children: textOrDash(item.slogan) },
      { key: 'detailContent', label: '详情内容', children: textOrDash(item.detailContent) },
      { key: 'currency', label: '币种', children: item.currency },
      { key: 'coverImage', label: '封面图', children: textOrDash(item.coverImage) },
      { key: 'images', label: '项目图集', children: imageListOrDash(item.images) },
      { key: 'salesCount', label: '销量', children: item.salesCount },
      { key: 'notes', label: '补充说明', children: textOrDash(item.notes) },
      { key: 'status', label: '申请状态', children: identityStatusLabel(item.status) },
      { key: 'reviewNote', label: '审核意见', children: textOrDash(item.reviewNote) },
      { key: 'reviewedBy', label: '审核人 ID', children: textOrDash(item.reviewedBy) },
      { key: 'reviewedAt', label: '审核时间', children: textOrDash(item.reviewedAt) },
      { key: 'resultingProjectId', label: '创建的平台项目 ID', children: textOrDash(item.resultingProjectId) },
      { key: 'resultingInstitutionProjectId', label: '创建的机构项目 ID', children: textOrDash(item.resultingInstitutionProjectId) },
      { key: 'submittedAt', label: '提交时间', children: item.submittedAt },
      { key: 'updatedAt', label: '更新时间', children: item.updatedAt },
    ];

    if (item.requestType === 'PLATFORM') {
      return [
        ...common,
        { key: 'referencePrice', label: '参考价格', children: moneyOrDash(item.currency, item.referencePrice) },
        { key: 'categoryTags', label: '分类标签', children: listOrDash(item.categoryTags) },
      ];
    }

    const split = item.institutionSplit;
    return [
      ...common,
      { key: 'price', label: '价格', children: moneyOrDash(item.currency, item.price) },
      { key: 'originalPrice', label: '原价', children: moneyOrDash(item.currency, item.originalPrice) },
      { key: 'isActive', label: '是否上架', children: item.isActive == null ? '-' : item.isActive ? '是' : '否' },
      { key: 'consultationFee', label: '面诊费', children: moneyOrDash(item.currency, split?.consultationFee) },
      { key: 'commissionRate', label: '顾问分成', children: split ? `${split.commissionRate}%` : '-' },
      { key: 'institutionRate', label: '机构分成', children: split ? `${split.institutionRate}%` : '-' },
      { key: 'platformRate', label: '当前平台比例', children: split ? `${split.platformRate}%` : '-' },
      { key: 'doctorRate', label: '按当前平台比例推导的医生净比例', children: split ? `${split.doctorRate}%` : '-' },
    ];
  };

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
        expandedRowRender: item => <Descriptions size="small" column={1} items={expandedItems(item)} />,
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
