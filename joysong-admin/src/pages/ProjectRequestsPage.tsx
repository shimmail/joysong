import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Collapse, Descriptions, Drawer, Empty, Form, Input, message, Modal, Select, Space, Tag } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined, EyeOutlined } from '@ant-design/icons';
import api, { getApiErrorCode, getApiErrorMessage, getData, getManagementContext } from '../api';
import InstitutionProjectPreview from '../components/InstitutionProjectPreview';
import { identityStatusColor, identityStatusLabel } from '../identity';
import {
  adaptCreationRequestPreview, adaptLegacyProjectRequestPreview, adaptV2ProposedProjectPreview,
  isCompleteProfessionalProjectRequest, parseDoctorProjectChangeRequests,
  type DoctorProjectChangeRequestV2, type ParsedDoctorProjectChangeRequest, type ProfessionalProjectRequest,
} from '../types/projectRequests';

type ReviewDecision = 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';
type StatusFilter = 'PENDING' | 'ALL' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED' | 'WITHDRAWN';
type CreationItem = { source: 'CREATION'; request: ProfessionalProjectRequest };
type ChangeItem = { source: 'CHANGE'; request: ParsedDoctorProjectChangeRequest };
type ReviewItem = CreationItem | ChangeItem;

export const REVIEW_ERROR_FORCE_ELIGIBILITY = {
  EDIT_BASE_STALE: false,
  APPROVAL_BASE_STALE: true,
  INHERITANCE_SOURCE_STALE: false,
  PRICING_POLICY_STALE: false,
  FORCE_BASE_STALE: false,
  REQUEST_ALREADY_PENDING: false,
  REQUEST_ALREADY_HANDLED: false,
  CLIENT_UPGRADE_REQUIRED: false,
  FORCE_NOT_APPLICABLE: false,
  PROJECT_PAYLOAD_INVALID: false,
  REQUEST_SNAPSHOT_INVALID: false,
  INSTITUTION_PROJECT_VERSION_STALE: false,
  PERMISSION_DENIED: false,
} as const satisfies Record<string, boolean>;

type HandledReviewErrorCode = keyof typeof REVIEW_ERROR_FORCE_ELIGIBILITY;
const handledReviewErrorCodes = new Set<string>(Object.keys(REVIEW_ERROR_FORCE_ELIGIBILITY));

export function isAdminForceEligible(errorCode: string | null, isAdmin: boolean) {
  return isAdmin && errorCode === 'APPROVAL_BASE_STALE';
}

const money = (currency: string, value: number | null | undefined) => value == null ? '-' : `${currency} ${value.toFixed(2)}`;
const activeLabel = (active: boolean | null | undefined) => active == null ? '-' : active ? '启用' : '停用';
const itemKey = (item: ReviewItem) => `${item.source}:${item.request.id}`;
const changeStatus = (request: ParsedDoctorProjectChangeRequest) => request.kind === 'V1' ? request.status : request.requestStatus;
const itemStatus = (item: ReviewItem) => item.source === 'CREATION' ? item.request.status : changeStatus(item.request);
const changeProjectName = (request: ParsedDoctorProjectChangeRequest) => request.kind === 'V2' ? request.institutionProjectName : request.projectName;
const itemProjectName = (item: ReviewItem) => item.source === 'CREATION'
  ? item.request.name?.trim() || item.request.projectName || '项目名称使用继承值'
  : changeProjectName(item.request);
const reviewLabel = (action: string, item: ReviewItem) => `${action} ${itemProjectName(item)}，申请ID ${item.request.id}`;
const hasNegativeDoctorRate = (item: ReviewItem) => item.source === 'CREATION'
  && item.request.requestType === 'INSTITUTION'
  && item.request.institutionSplit.doctorRate < 0;

function ChangeComparison({ request }: { request: DoctorProjectChangeRequestV2 }) {
  const current = request.currentProject?.effective;
  const proposed = request.proposedProject?.effective;
  const latest = request.latestProject?.effective;
  const imageState = (cover: string | null | undefined, images: string[] | undefined) =>
    `${cover ? '有封面' : '无封面'}，${images?.length ?? 0} 张项目图`;
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Alert
      type={request.sharedChanged ? 'warning' : 'info'}
      showIcon
      title={request.sharedChanged ? '本次申请修改机构共享项目资料，请同时核对其他医生受到的影响。' : '本次申请仅修改该医生的价格或启用状态。'}
    />
    <Descriptions size="small" column={1} items={[
      { key: 'current-name', label: '当前机构项目名称', children: current?.name ?? '-' },
      { key: 'proposed-name', label: '提议机构项目名称', children: proposed?.name ?? '-' },
      { key: 'latest-name', label: '刷新后最新机构项目名称', children: latest?.name ?? '-' },
      { key: 'current-category', label: '当前分类', children: current?.category ?? '-' },
      { key: 'proposed-category', label: '提议分类', children: proposed?.category ?? '-' },
      { key: 'current-description', label: '当前说明', children: current?.description ?? '-' },
      { key: 'proposed-description', label: '提议说明', children: proposed?.description ?? '-' },
      { key: 'current-tags', label: '当前标签', children: current?.tags.join('、') || '-' },
      { key: 'proposed-tags', label: '提议标签', children: proposed?.tags.join('、') || '-' },
      { key: 'current-images', label: '当前图片', children: imageState(current?.coverImage, current?.images) },
      { key: 'proposed-images', label: '提议图片', children: imageState(proposed?.coverImage, proposed?.images) },
      { key: 'current-price', label: '当前医生价格', children: money('USD', request.currentDoctorPrice) },
      { key: 'proposed-price', label: '提议医生价格', children: money('USD', request.proposedDoctorPrice) },
      { key: 'latest-price', label: '刷新后最新医生价格', children: money('USD', request.latestDoctorPrice) },
      { key: 'current-active', label: '当前医生状态', children: activeLabel(request.currentDoctorActive) },
      { key: 'proposed-active', label: '提议医生状态', children: activeLabel(request.proposedDoctorActive) },
      { key: 'latest-active', label: '刷新后最新医生状态', children: activeLabel(request.latestDoctorActive) },
      { key: 'travel', label: '旅游地接服务费（只读）', children: money('USD', request.travelGroundServiceFee) },
    ]} />
  </Space>;
}

export default function ProjectRequestsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const [creations, setCreations] = useState<ProfessionalProjectRequest[]>([]);
  const [changes, setChanges] = useState<ParsedDoctorProjectChangeRequest[]>([]);
  const creationsRef = useRef<ProfessionalProjectRequest[]>([]);
  const changesRef = useRef<ParsedDoctorProjectChangeRequest[]>([]);
  const [creationLoading, setCreationLoading] = useState(false);
  const [changeLoading, setChangeLoading] = useState(false);
  const [creationLoadError, setCreationLoadError] = useState<string | null>(null);
  const [changeLoadError, setChangeLoadError] = useState<string | null>(null);
  const [malformedCreationCount, setMalformedCreationCount] = useState(0);
  const [status, setStatus] = useState<StatusFilter>('PENDING');
  const [detailKey, setDetailKey] = useState<string | null>(null);
  const [reviewTarget, setReviewTarget] = useState<ReviewItem | null>(null);
  const [reviewDecision, setReviewDecision] = useState<Exclude<ReviewDecision, 'APPROVED'>>('REJECTED');
  const [forceTargetId, setForceTargetId] = useState<string | null>(null);
  const [forceOpen, setForceOpen] = useState(false);
  const [conflict, setConflict] = useState<{ code: HandledReviewErrorCode; message: string } | null>(null);
  const inFlightRef = useRef<string | null>(null);
  const [inFlightKey, setInFlightKey] = useState<string | null>(null);
  const [reviewForm] = Form.useForm();
  const [forceForm] = Form.useForm();

  const loadCreations = async () => {
    setCreationLoading(true);
    try {
      const response = await api.get(isAdmin ? '/admin/project-requests' : '/management/project-requests');
      const payload = getData<unknown>(response as never);
      const items = Array.isArray(payload) ? payload : [];
      const parsed = items.filter(isCompleteProfessionalProjectRequest)
        .map(request => ({ ...request, requestSource: 'PROFESSIONAL' as const }));
      creationsRef.current = parsed;
      setCreations(parsed);
      setMalformedCreationCount(Array.isArray(payload) ? items.length - parsed.length : 1);
      setCreationLoadError(null);
      return parsed;
    } catch (error) {
      setCreationLoadError(getApiErrorMessage(error, '创建申请加载失败'));
      return null;
    } finally {
      setCreationLoading(false);
    }
  };

  const loadChanges = async () => {
    setChangeLoading(true);
    try {
      const response = await api.get('/v2/admin/institution-project-requests');
      const parsed = parseDoctorProjectChangeRequests(getData<unknown>(response as never));
      changesRef.current = parsed;
      setChanges(parsed);
      setChangeLoadError(null);
      return parsed;
    } catch (error) {
      setChangeLoadError(getApiErrorMessage(error, '医生项目变更申请加载失败'));
      return null;
    } finally {
      setChangeLoading(false);
    }
  };

  useEffect(() => { void Promise.all([loadCreations(), loadChanges()]); }, []);

  const currentItem = (item: ReviewItem): ReviewItem | null => {
    if (item.source === 'CREATION') {
      const request = creationsRef.current.find(candidate => candidate.id === item.request.id);
      return request ? { source: 'CREATION', request } : null;
    }
    const request = changesRef.current.find(candidate => candidate.id === item.request.id);
    return request ? { source: 'CHANGE', request } : null;
  };

  const canReview = (item: ReviewItem) => {
    if (itemStatus(item) !== 'PENDING') return false;
    if (item.source === 'CHANGE' && !item.request.reviewable) return false;
    if (isAdmin) return true;
    if (item.source === 'CREATION' && item.request.requestType !== 'INSTITUTION') return false;
    return managementContext?.canReviewInstitutionProjectRequests === true
      && Boolean(item.request.institutionId)
      && managementContext.managedInstitutionIds.includes(item.request.institutionId!);
  };

  const beginReview = (item: ReviewItem) => {
    if (inFlightRef.current !== null) return false;
    inFlightRef.current = itemKey(item);
    setInFlightKey(itemKey(item));
    return true;
  };
  const finishReview = (item: ReviewItem) => {
    if (inFlightRef.current !== itemKey(item)) return;
    inFlightRef.current = null;
    setInFlightKey(null);
  };
  const reviewPath = (item: ReviewItem) => item.source === 'CHANGE'
    ? `/v2/admin/institution-project-requests/${item.request.id}/review`
    : item.request.requestType === 'PLATFORM'
      ? `/admin/project-requests/${item.request.id}/review`
      : `/management/project-requests/${item.request.id}/review`;
  const reviewBody = (item: ReviewItem, decision: ReviewDecision, reviewNote: string, force = false, forceBaseRevision: string | null = null) =>
    item.source === 'CHANGE' ? { decision, reviewNote, force, forceBaseRevision } : { decision, reviewNote };

  const handleReviewError = async (error: unknown, item: ReviewItem) => {
    const errorCode = getApiErrorCode(error);
    if (!errorCode || !handledReviewErrorCodes.has(errorCode)) {
      message.error(getApiErrorMessage(error, '审核失败'));
      return;
    }
    const code = errorCode as HandledReviewErrorCode;
    setConflict({ code, message: getApiErrorMessage(error, '审核数据已变化') });
    setDetailKey(null);
    setForceTargetId(null);
    setForceOpen(false);
    if (item.source === 'CREATION') {
      await loadCreations();
      return;
    }
    const refreshed = await loadChanges();
    if (!refreshed || !isAdminForceEligible(code, isAdmin)) return;
    const latest = refreshed.find(request => request.id === item.request.id);
    if (latest?.kind === 'V2' && latest.reviewable && latest.requestStatus === 'PENDING' && latest.latestRevision) setForceTargetId(latest.id);
  };

  const submitDirectApproval = async (item: ReviewItem) => {
    if (!canReview(item) || hasNegativeDoctorRate(item) || !beginReview(item)) return;
    try {
      await api.post(reviewPath(item), reviewBody(item, 'APPROVED', ''));
      message.success('申请已通过');
      setConflict(null);
      if (item.source === 'CREATION') await loadCreations(); else await loadChanges();
    } catch (error) {
      await handleReviewError(error, item);
    } finally {
      finishReview(item);
    }
  };

  const openReview = (item: ReviewItem, decision: Exclude<ReviewDecision, 'APPROVED'>) => {
    if (inFlightRef.current !== null) return;
    reviewForm.resetFields();
    setReviewDecision(decision);
    setReviewTarget(item);
  };
  const submitReview = async () => {
    if (!reviewTarget) return;
    const target = currentItem(reviewTarget);
    if (!target || !canReview(target)) return;
    let started = false;
    try {
      const values = await reviewForm.validateFields();
      started = beginReview(target);
      if (!started) return;
      await api.post(reviewPath(target), reviewBody(target, reviewDecision, values.reviewNote));
      message.success(reviewDecision === 'REJECTED' ? '申请已驳回' : '已要求医生修改申请');
      setReviewTarget(null);
      setConflict(null);
      if (target.source === 'CREATION') await loadCreations(); else await loadChanges();
    } catch (error) {
      if (!(error && typeof error === 'object' && 'errorFields' in error)) await handleReviewError(error, target);
    } finally {
      if (started) finishReview(target);
    }
  };

  const forceTarget = forceTargetId
    ? changes.find(request => request.id === forceTargetId && request.kind === 'V2') as DoctorProjectChangeRequestV2 | undefined
    : undefined;
  const submitForce = async () => {
    if (!forceTarget || !forceTarget.latestRevision) return;
    const item: ChangeItem = { source: 'CHANGE', request: forceTarget };
    let started = false;
    try {
      const values = await forceForm.validateFields();
      started = beginReview(item);
      if (!started) return;
      await api.post(reviewPath(item), reviewBody(item, 'APPROVED', values.reviewNote, true, forceTarget.latestRevision));
      message.success('已按刷新后的最新基线强制通过');
      setForceTargetId(null);
      setForceOpen(false);
      setConflict(null);
      await loadChanges();
    } catch (error) {
      if (!(error && typeof error === 'object' && 'errorFields' in error)) await handleReviewError(error, item);
    } finally {
      if (started) finishReview(item);
    }
  };

  const allItems: ReviewItem[] = [
    ...creations.map(request => ({ source: 'CREATION' as const, request })),
    ...changes.map(request => ({ source: 'CHANGE' as const, request })),
  ];
  const visibleItems = allItems.filter(item => (item.source === 'CHANGE' && item.request.kind === 'DAMAGED')
    || status === 'ALL' || itemStatus(item) === status);
  const platformItems = visibleItems.filter((item): item is CreationItem => item.source === 'CREATION' && item.request.requestType === 'PLATFORM');
  const institutionItems = visibleItems.filter(item => !(item.source === 'CREATION' && item.request.requestType === 'PLATFORM'));
  const groups = useMemo(() => {
    const grouped = new Map<string, { name: string; items: ReviewItem[] }>();
    institutionItems.forEach(item => {
      const id = item.request.institutionId || `damaged-${item.request.id}`;
      const group = grouped.get(id) ?? { name: item.request.institutionName || '机构信息缺失', items: [] };
      group.items.push(item);
      grouped.set(id, group);
    });
    return Array.from(grouped.entries()).map(([id, group]) => ({ id, ...group }));
  }, [institutionItems]);

  const renderSummary = (item: ReviewItem) => {
    if (item.source === 'CREATION') return <Space wrap>
      <span>平台项目：{item.request.projectName || item.request.name || '-'}</span>
      <span>医生：{item.request.doctorName}</span>
      <span>申请价格：{money(item.request.currency, item.request.requestType === 'PLATFORM' ? item.request.referencePrice : item.request.price)}</span>
      <span>申请状态：{identityStatusLabel(item.request.status)}</span>
    </Space>;
    if (item.request.kind === 'DAMAGED') return <Space wrap>
      <span>医生：{item.request.doctorName}</span><Tag color="error">数据损坏：{item.request.parseIssue}</Tag>
    </Space>;
    if (item.request.kind === 'V1') return <Space wrap>
      <span>医生：{item.request.doctorName}</span>
      <span>医生价格：{money('USD', item.request.priceSuggestion ?? item.request.medicalListPrice)}</span>
      <span>申请状态：{identityStatusLabel(item.request.status)}</span>
    </Space>;
    return <Space wrap>
      {item.request.parseIssue && <Tag color="error">数据损坏：{item.request.parseIssue}</Tag>}
      <span>平台项目：{item.request.platformProjectName}</span><span>医生：{item.request.doctorName}</span>
      <span>医生价格：{money('USD', item.request.proposedDoctorPrice)}</span>
      <span>旅游地接服务费：{money('USD', item.request.travelGroundServiceFee)}</span>
      <span>申请状态：{identityStatusLabel(item.request.requestStatus)}</span>
      <span>提议医生状态：{activeLabel(item.request.proposedDoctorActive)}</span>
    </Space>;
  };

  const renderActions = (item: ReviewItem) => {
    const reviewable = canReview(item);
    const disabled = inFlightKey !== null;
    return <Space wrap>
      {(item.source !== 'CHANGE' || item.request.kind !== 'DAMAGED') && <Button size="small" icon={<EyeOutlined />}
        aria-label={reviewLabel('查看详情', item)} disabled={disabled} onClick={() => setDetailKey(itemKey(item))}>查看详情</Button>}
      {reviewable && !hasNegativeDoctorRate(item) && <Button size="small" type="primary" icon={<CheckOutlined />} aria-label={reviewLabel('通过', item)}
        loading={inFlightKey === itemKey(item)} disabled={disabled} onClick={() => void submitDirectApproval(item)}>通过</Button>}
      {reviewable && item.source === 'CHANGE' && <Button size="small" icon={<EditOutlined />} aria-label={reviewLabel('要求修改', item)}
        disabled={disabled} onClick={() => openReview(item, 'CHANGES_REQUESTED')}>要求修改</Button>}
      {reviewable && <Button size="small" danger icon={<CloseOutlined />} aria-label={reviewLabel('驳回', item)}
        disabled={disabled} onClick={() => openReview(item, 'REJECTED')}>驳回</Button>}
    </Space>;
  };
  const renderCard = (item: ReviewItem) => <Card key={itemKey(item)} size="small" title={<Space wrap>
    <span>{itemProjectName(item)}</span>
    <Tag color={identityStatusColor(itemStatus(item))}>{identityStatusLabel(itemStatus(item))}</Tag>
    {item.source === 'CHANGE' && item.request.kind === 'V2' && <Tag color={item.request.proposedDoctorActive ? 'green' : 'default'}>
      {item.request.proposedDoctorActive ? '医生启用' : '医生停用'}
    </Tag>}
  </Space>} extra={renderActions(item)} style={{ marginBottom: 12 }}>
    {hasNegativeDoctorRate(item) && <Alert type="warning" showIcon title="当前分成比例冲突：该创建申请仅可驳回。" style={{ marginBottom: 12 }} />}
    {renderSummary(item)}
  </Card>;

  const detailItem = detailKey ? allItems.find(item => itemKey(item) === detailKey) : undefined;
  const preview = detailItem?.source === 'CREATION' ? adaptCreationRequestPreview(detailItem.request)
    : detailItem?.source === 'CHANGE' && detailItem.request.kind === 'V1' ? adaptLegacyProjectRequestPreview(detailItem.request)
      : detailItem?.source === 'CHANGE' && detailItem.request.kind === 'V2' ? adaptV2ProposedProjectPreview(detailItem.request) : null;

  return <div>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 16 }}>
      <h2 style={{ margin: 0 }}>项目申请审核</h2>
      <Select<StatusFilter> aria-label="申请状态筛选" value={status} style={{ width: 140 }} onChange={setStatus} options={[
        { label: '待审核', value: 'PENDING' }, { label: '全部状态', value: 'ALL' }, { label: '已通过', value: 'APPROVED' },
        { label: '已驳回', value: 'REJECTED' }, { label: '待修改', value: 'CHANGES_REQUESTED' }, { label: '已撤回', value: 'WITHDRAWN' },
      ]} />
    </div>
    {conflict && <Alert type="warning" showIcon title={`审核冲突（${conflict.code}）`}
      description={`${conflict.message}。旧详情已关闭，审核队列已刷新，请核对最新数据。`}
      action={forceTarget && <Button onClick={() => { forceForm.resetFields(); setForceOpen(true); }}>查看最新差异并强制通过</Button>}
      style={{ marginBottom: 16 }} />}

    <section aria-labelledby="platform-creation-heading" style={{ marginBottom: 24 }}>
      <h3 id="platform-creation-heading">平台项目创建申请</h3>
      {creationLoadError && <Alert type="error" showIcon title="创建申请加载失败" description={creationLoadError} style={{ marginBottom: 12 }} />}
      {malformedCreationCount > 0 && <Alert type="error" showIcon title="创建申请快照数据不完整，已禁止审核" style={{ marginBottom: 12 }} />}
      {platformItems.length ? platformItems.map(renderCard) : !creationLoading && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无平台项目创建申请" />}
    </section>
    <section aria-labelledby="institution-review-heading">
      <h3 id="institution-review-heading">按机构审核</h3>
      {changeLoadError && <Alert type="error" showIcon title="医生项目变更申请加载失败" description={changeLoadError} style={{ marginBottom: 12 }} />}
      {groups.length ? <Collapse defaultActiveKey={groups.map(group => group.id)} items={groups.map(group => ({
        key: group.id, label: `${group.name}（${group.items.length}）`,
        children: <section aria-label={`${group.name}审核组`}>{group.items.map(renderCard)}</section>,
      }))} /> : !creationLoading && !changeLoading && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无机构项目申请" />}
    </section>

    <Drawer title={detailItem ? `${itemProjectName(detailItem)}审核详情` : '审核详情'} open={Boolean(detailItem)}
      onClose={() => setDetailKey(null)} size="large" destroyOnHidden>
      {preview && <InstitutionProjectPreview model={preview} />}
      {detailItem?.source === 'CHANGE' && detailItem.request.kind === 'V2' && <ChangeComparison request={detailItem.request} />}
      {detailItem?.source === 'CHANGE' && detailItem.request.kind === 'V1' && <Descriptions size="small" column={1} items={[
        { key: 'schedule', label: '排期说明（旧版）', children: detailItem.request.scheduleNote || '-' },
        { key: 'notes', label: '补充说明', children: detailItem.request.notes || '-' },
      ]} />}
    </Drawer>
    <Modal title={reviewDecision === 'REJECTED' ? '驳回项目申请' : '要求医生修改申请'} open={Boolean(reviewTarget)}
      onOk={() => void submitReview()} onCancel={() => setReviewTarget(null)} confirmLoading={inFlightKey !== null} okText="确认" destroyOnHidden>
      <Form form={reviewForm} layout="vertical"><Form.Item name="reviewNote" label="审核意见"
        rules={[{ required: true, whitespace: true, message: '请填写审核意见' }, { max: 1000 }]}><Input.TextArea rows={4} /></Form.Item></Form>
    </Modal>
    <Modal title="按最新基线强制通过" open={Boolean(forceTarget && forceOpen)}
      onOk={() => void submitForce()} onCancel={() => setForceOpen(false)} confirmLoading={inFlightKey !== null}
      okText="强制通过" destroyOnHidden>
      {forceTarget && <>
        <Alert type="warning" showIcon title="强制通过会以刷新后的最新版本为基线，请说明接受差异的原因。" style={{ marginBottom: 16 }} />
        <ChangeComparison request={forceTarget} />
        <Form form={forceForm} layout="vertical"><Form.Item name="reviewNote" label="强制通过原因"
          rules={[{ required: true, whitespace: true, message: '请填写强制通过原因' }, { max: 1000 }]}><Input.TextArea rows={4} /></Form.Item></Form>
      </>}
    </Modal>
  </div>;
}
