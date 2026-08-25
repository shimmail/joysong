import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Form, Input, message, Modal, Select, Space, Table, Tag } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import { identityStatusColor, identityStatusLabel } from '../identity';
import {
  isCompleteProfessionalProjectRequest,
  type DoctorProjectChangeStatus,
  type ProfessionalProjectRequest,
} from '../types/projectRequests';

type ProjectRequestStatus = DoctorProjectChangeStatus;

interface DoctorProjectChangeRequest {
  id: string;
  requestSource: 'DOCTOR_PROJECT_CHANGE';
  requestType: 'JOIN' | 'PROFILE_UPDATE' | 'LEAVE';
  doctorId: string;
  doctorName: string;
  institutionId: string;
  institutionName: string;
  institutionProjectId: string;
  projectName: string;
  serviceDescription: string;
  serviceTags?: string[];
  scheduleNote?: string | null;
  coverImage?: string | null;
  images?: string[];
  priceSuggestion?: number | null;
  notes?: string | null;
  consultationFee?: number | null;
  commissionRate?: number | null;
  institutionRate?: number | null;
  medicalListPrice?: number | null;
  platformRate?: number | null;
  doctorRate?: number | null;
  currentPrice?: number | null;
  currentServiceDescription?: string | null;
  currentServiceTags?: string[] | null;
  currentScheduleNote?: string | null;
  currentCoverImage?: string | null;
  currentImages?: string[] | null;
  currentConsultationFee?: number | null;
  currentCommissionRate?: number | null;
  currentInstitutionRate?: number | null;
  currentMedicalListPrice?: number | null;
  currentPlatformRate?: number | null;
  currentDoctorRate?: number | null;
  forceProcessed?: boolean;
  status: DoctorProjectChangeStatus;
  reviewNote?: string | null;
  submittedAt?: string | null;
}

type ProjectRequest = ProfessionalProjectRequest | DoctorProjectChangeRequest;
type ReviewDecision = 'REJECTED' | 'CHANGES_REQUESTED';

const textOrDash = (value?: string | null) => value || '-';
const listOrDash = (values?: string[] | null) => values?.length ? values.join('、') : '-';
const moneyOrDash = (currency: string, value?: number | null) => value == null ? '-' : `${currency} ${value}`;
const imageListOrDash = (values?: string[] | null) => values?.length
  ? <Space orientation="vertical" size={0}>{values.map(value => <span key={value}>{value}</span>)}</Space>
  : '-';

const isRecord = (value: unknown): value is Record<string, unknown> => value != null && typeof value === 'object' && !Array.isArray(value);

function getHttpResponseStatus(error: unknown) {
  if (!isRecord(error) || !isRecord(error.response)) return null;
  return typeof error.response.status === 'number' ? error.response.status : null;
}

const requestKey = (request: ProjectRequest) => `${request.requestSource}:${request.id}`;
const hasNegativeDoctorRate = (request: ProjectRequest) => request.requestSource === 'PROFESSIONAL'
  && request.requestType === 'INSTITUTION'
  && request.institutionSplit.doctorRate < 0;
const reviewAriaLabel = (action: string, request: ProjectRequest) => {
  const name = request.requestSource === 'DOCTOR_PROJECT_CHANGE'
    ? request.projectName
    : request.name?.trim() || '项目名称留空，使用继承值';
  return `${action} ${name}，申请ID ${request.id}`;
};

export default function ProjectRequestsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const [requests, setRequests] = useState<ProjectRequest[]>([]);
  const requestsRef = useRef<ProjectRequest[]>([]);
  const [loading, setLoading] = useState(false);
  const [malformedProfessionalCount, setMalformedProfessionalCount] = useState(0);
  const staleReviewDataRef = useRef(false);
  const [staleReviewData, setStaleReviewData] = useState(false);
  const inFlightReviewRef = useRef<string | null>(null);
  const [reviewingRequestKey, setReviewingRequestKey] = useState<string | null>(null);
  const [status, setStatus] = useState<ProjectRequestStatus | 'ALL'>('PENDING');
  const [reviewTarget, setReviewTarget] = useState<ProjectRequest | null>(null);
  const [reviewDecision, setReviewDecision] = useState<ReviewDecision>('REJECTED');
  const [reviewForm] = Form.useForm();

  const updateStaleReviewData = (stale: boolean) => {
    staleReviewDataRef.current = stale;
    setStaleReviewData(stale);
  };

  const refresh = async (): Promise<boolean> => {
    setLoading(true);
    try {
      const [professionalResponse, joinResponse] = await Promise.all([
        api.get(isAdmin ? '/admin/project-requests' : '/management/project-requests'),
        api.get('/admin/institution-project-requests'),
      ]);
      const professionalPayload = getData<unknown>(professionalResponse as any);
      const professionalItems = Array.isArray(professionalPayload) ? professionalPayload : [];
      const professional = professionalItems
        .filter(isCompleteProfessionalProjectRequest)
        .map(item => ({ ...item, requestSource: 'PROFESSIONAL' as const }));
      const projectChanges = (getData<Omit<DoctorProjectChangeRequest, 'requestSource'>[]>(joinResponse as any) || [])
        .filter(item => ['JOIN', 'PROFILE_UPDATE', 'LEAVE'].includes(item.requestType))
        .map(item => ({ ...item, requestSource: 'DOCTOR_PROJECT_CHANGE' as const }));
      setMalformedProfessionalCount(
        Array.isArray(professionalPayload)
          ? professionalItems.length - professional.length
          : 1,
      );
      const refreshedRequests = [...professional, ...projectChanges];
      requestsRef.current = refreshedRequests;
      setRequests(refreshedRequests);
      setReviewTarget(currentTarget => {
        if (!currentTarget) return null;
        return refreshedRequests.find(item => requestKey(item) === requestKey(currentTarget)) ?? currentTarget;
      });
      updateStaleReviewData(false);
      return true;
    } catch (error) {
      updateStaleReviewData(true);
      message.error(getApiErrorMessage(error, '平台项目申请加载失败'));
      return false;
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const submitting = reviewingRequestKey !== null;

  const beginReview = (request: ProjectRequest) => {
    if (staleReviewDataRef.current || inFlightReviewRef.current !== null) return false;
    const key = requestKey(request);
    inFlightReviewRef.current = key;
    setReviewingRequestKey(key);
    return true;
  };

  const finishReview = (request: ProjectRequest) => {
    const key = requestKey(request);
    if (inFlightReviewRef.current !== key) return;
    inFlightReviewRef.current = null;
    setReviewingRequestKey(null);
  };

  const handleReviewError = async (error: unknown) => {
    if (getHttpResponseStatus(error) === 409) {
      updateStaleReviewData(true);
      const refreshed = await refresh();
      if (refreshed) {
        message.warning('审核状态或审批基线已变化，已刷新最新数据，请核对当前比例和申请状态后重试');
      }
      return;
    }
    message.error(getApiErrorMessage(error, '审核失败'));
  };

  const canReview = (request: ProjectRequest) => {
    if (request.status !== 'PENDING') return false;
    if (isAdmin) return true;
    if (request.requestSource === 'PROFESSIONAL') {
      return request.requestType === 'INSTITUTION'
        && managementContext?.canReviewInstitutionProjectRequests === true
        && request.institutionId != null
        && managementContext.managedInstitutionIds.includes(request.institutionId);
    }
    return managementContext?.canReviewInstitutionProjectRequests === true
      && managementContext.managedInstitutionIds.includes(request.institutionId);
  };

  const reviewPath = (request: ProjectRequest) => {
    if (request.requestSource === 'DOCTOR_PROJECT_CHANGE') {
      return `/admin/institution-project-requests/${request.id}/review`;
    }
    return request.requestType === 'PLATFORM'
      ? `/admin/project-requests/${request.id}/review`
      : `/management/project-requests/${request.id}/review`;
  };

  const approve = async (request: ProjectRequest) => {
    if (hasNegativeDoctorRate(request) || !beginReview(request)) return;
    try {
      await api.post(reviewPath(request), request.requestSource === 'DOCTOR_PROJECT_CHANGE'
        ? { decision: 'APPROVED', reviewNote: '', force: false }
        : { decision: 'APPROVED', reviewNote: '' });
      message.success(request.requestType === 'PLATFORM'
        ? '申请已通过，平台项目已创建'
        : request.requestSource === 'DOCTOR_PROJECT_CHANGE'
          ? '机构项目变更申请已通过'
          : '申请已通过，机构项目已创建');
      await refresh();
    } catch (error) {
      await handleReviewError(error);
    } finally {
      finishReview(request);
    }
  };

  const openReview = (request: ProjectRequest, decision: ReviewDecision) => {
    if (staleReviewDataRef.current || inFlightReviewRef.current !== null) return;
    reviewForm.resetFields();
    setReviewTarget(request);
    setReviewDecision(decision);
  };

  const submitReview = async () => {
    if (!reviewTarget || staleReviewDataRef.current) return;
    const target = requestsRef.current.find(item => requestKey(item) === requestKey(reviewTarget));
    if (!target || !canReview(target)) return;
    const decision = reviewDecision;
    let started = false;
    try {
      const values = await reviewForm.validateFields();
      started = beginReview(target);
      if (!started) return;
      await api.post(reviewPath(target), target.requestSource === 'DOCTOR_PROJECT_CHANGE'
        ? { decision, reviewNote: values.reviewNote, force: false }
        : { decision, reviewNote: values.reviewNote });
      message.success(decision === 'REJECTED' ? '申请已驳回' : '已要求医生修改申请');
      setReviewTarget(null);
      await refresh();
    } catch (error: unknown) {
      if (!isRecord(error) || !Array.isArray(error.errorFields)) await handleReviewError(error);
    } finally {
      if (started) finishReview(target);
    }
  };

  const filteredRequests = status === 'ALL'
    ? requests
    : requests.filter(item => item.status === status);
  const hasReviewableSplitConflict = filteredRequests.some(item => canReview(item) && hasNegativeDoctorRate(item));
  const currentReviewTarget = reviewTarget
    ? requests.find(item => requestKey(item) === requestKey(reviewTarget)) ?? null
    : null;
  const reviewTargetChanged = reviewTarget !== null
    && (currentReviewTarget === null || !canReview(currentReviewTarget));

  const renderActions = (item: ProjectRequest) => {
    if (!canReview(item)) return null;
    return <Space wrap>
      <Button
        aria-label={reviewAriaLabel('通过', item)}
        size="small"
        type="primary"
        icon={<CheckOutlined />}
        loading={reviewingRequestKey === requestKey(item)}
        disabled={submitting || staleReviewData || hasNegativeDoctorRate(item)}
        onClick={() => void approve(item)}
      >通过</Button>
      {item.requestSource === 'DOCTOR_PROJECT_CHANGE' && <Button
        aria-label={reviewAriaLabel('要求修改', item)}
        size="small"
        icon={<EditOutlined />}
        disabled={submitting || staleReviewData}
        onClick={() => openReview(item, 'CHANGES_REQUESTED')}
      >要求修改</Button>}
      <Button
        aria-label={reviewAriaLabel('驳回', item)}
        size="small"
        danger
        icon={<CloseOutlined />}
        disabled={submitting || staleReviewData}
        onClick={() => openReview(item, 'REJECTED')}
      >驳回</Button>
    </Space>;
  };

  const columns = [
    {
      title: '类型', dataIndex: 'requestType', width: 140,
      render: (value: ProjectRequest['requestType']) => value === 'PLATFORM'
        ? '新增平台项目'
        : value === 'INSTITUTION' ? '新增机构项目'
          : value === 'JOIN' ? '加入机构项目'
            : value === 'PROFILE_UPDATE' ? '资料变更' : '退出机构项目',
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
    if (item.requestSource === 'DOCTOR_PROJECT_CHANGE') {
      const items = [
        { key: 'description', label: '服务内容', children: textOrDash(item.serviceDescription) },
        { key: 'price', label: '建议价格', children: item.priceSuggestion == null ? '-' : `¥${item.priceSuggestion}` },
        { key: 'notes', label: '补充说明', children: textOrDash(item.notes) },
      ];
      if (item.requestType === 'PROFILE_UPDATE') {
        items.push(
          { key: 'medicalListPrice', label: '医疗套餐优惠前金额（提议）', children: moneyOrDash('USD', item.medicalListPrice) },
          { key: 'currentMedicalListPrice', label: '医疗套餐优惠前金额（当前）', children: moneyOrDash('USD', item.currentMedicalListPrice) },
          { key: 'platformRate', label: '平台服务比例（提议，只读）', children: item.platformRate == null ? '-' : `${item.platformRate}%` },
          { key: 'currentPlatformRate', label: '平台服务比例（当前，只读）', children: item.currentPlatformRate == null ? '-' : `${item.currentPlatformRate}%` },
        );
      }
      return items;
    }

    const common = [
      { key: 'id', label: '申请 ID', children: item.id },
      { key: 'doctorId', label: '申请医生 ID（不可变）', children: item.doctorId },
      { key: 'doctorName', label: '当前医生名称', children: item.doctorName },
      { key: 'institutionId', label: '目标机构 ID（不可变）', children: textOrDash(item.institutionId) },
      { key: 'institutionName', label: '当前机构名称', children: textOrDash(item.institutionName) },
      { key: 'projectId', label: '目标平台项目 ID（不可变）', children: textOrDash(item.projectId) },
      { key: 'projectName', label: '当前平台项目名称', children: textOrDash(item.projectName) },
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
          { label: '已撤回', value: 'WITHDRAWN' },
        ]}
      />
    </div>
    {malformedProfessionalCount > 0 && <Alert
      type="error"
      showIcon
      title="申请快照数据不完整，已禁止审核"
      description="请刷新页面；若问题持续存在，请联系技术人员。"
      style={{ marginBottom: 16 }}
    />}
    {staleReviewData && <Alert
      type="error"
      showIcon
      title="审核状态已变化，但最新项目申请刷新失败"
      description="当前页面数据可能已过期，所有审核操作已禁用。请重新加载后再审核。"
      action={<Button size="small" loading={loading} onClick={() => void refresh()}>重新加载申请</Button>}
      style={{ marginBottom: 16 }}
    />}
    {hasReviewableSplitConflict && <Alert
      type="warning"
      showIcon
      title="当前分成比例冲突：按当前平台比例推导的医生净比例为负数，该申请仅可驳回。"
      style={{ marginBottom: 16 }}
    />}
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
      okButtonProps={{ danger: reviewDecision === 'REJECTED', disabled: staleReviewData || reviewTargetChanged }}
      destroyOnHidden
    >
      {reviewTargetChanged && <Alert
        type="warning"
        showIcon
        title="审核目标已变化"
        description="最新列表中该申请已不再处于待审核状态，请关闭窗口并核对最新数据。"
        style={{ marginBottom: 16 }}
      />}
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
