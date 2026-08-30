import { useEffect, useRef, useState } from 'react';
import { Alert, Table, Button, message, Select, Input, Space, Tag, Modal, Descriptions } from 'antd';
import { CheckOutlined, CloseOutlined, EyeOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import { formatMoney } from '../utils/money';

const statusLabels: Record<string, string> = {
  PENDING: '待人工审核',
  REFUND_PROCESSING: '渠道处理中',
  APPROVED: '退款成功',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
};

const statusColors: Record<string, string> = {
  PENDING: 'orange',
  REFUND_PROCESSING: 'blue',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'default',
};

const refundTypeLabels: Record<string, string> = {
  FULL: '全额退款',
  PARTIAL: '部分退款',
};

const paymentFlowLabels: Record<string, string> = {
  TRAVEL_GROUND_SERVICE_ONLY: '旅游地接服务费流程',
  LEGACY_MEDICAL: '历史医疗支付流程',
};

const originalStatusLabels: Record<string, string> = {
  SERVICE_ACTIVE: '服务进行中',
  COMPLETED: '已完成',
};

const refundListLoadError = (error: any) =>
  '退款列表加载失败: ' + (error?.response?.data?.message || error?.message || '未知错误');

const requestedRefundAmount = (record: any) =>
  formatMoney(record.requestedAmountMinor, record.currency, record.amount ?? record.refundAmount);

const paidAmount = (record: any) => formatMoney(
  record.paymentAmountMinor ?? (
    record.paymentFlow === 'TRAVEL_GROUND_SERVICE_ONLY' ? record.requestedAmountMinor : undefined
  ),
  record.currency,
  record.paymentAmount,
);

const isRetryableRefund = (record: any) =>
  record?.status === 'REFUND_PROCESSING' && record.items?.some((item: any) => item.status === 'FAILED');

type RefundEvidenceFile = {
  fileId: string;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  position: number;
};

const allowedEvidenceContentTypes = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
  'application/pdf',
]);

const evidenceContentPath = (refundId: string, fileId: string) =>
  `/admin/refunds/${encodeURIComponent(refundId)}/evidence/${encodeURIComponent(fileId)}/content`;

const refundEvidenceFiles = (value: unknown): RefundEvidenceFile[] => {
  if (!Array.isArray(value)) return [];
  return value
    .map((item, index) => {
      if (!item || typeof item !== 'object') return null;
      const file = item as Record<string, unknown>;
      const sizeBytes = file.sizeBytes;
      const position = file.position;
      if (
        typeof file.fileId !== 'string' || !file.fileId.trim()
        || typeof file.originalName !== 'string' || !file.originalName.trim()
        || typeof file.contentType !== 'string' || !allowedEvidenceContentTypes.has(file.contentType.trim())
        || typeof sizeBytes !== 'number' || !Number.isSafeInteger(sizeBytes) || sizeBytes < 0
        || typeof position !== 'number' || !Number.isInteger(position) || position < 0 || position > 4
      ) return null;
      return {
        fileId: file.fileId.trim(),
        originalName: file.originalName.trim(),
        contentType: file.contentType.trim(),
        sizeBytes,
        position,
        index,
      };
    })
    .filter((file): file is RefundEvidenceFile & { index: number } => file !== null)
    .sort((left, right) => left.position - right.position || left.index - right.index)
    .slice(0, 5)
    .map(({ index: _index, ...file }) => file);
};

const legacyEvidenceUrls = (value: unknown): string[] => {
  if (typeof value !== 'string') return [];
  return value
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
    .flatMap(item => {
      try {
        const url = new URL(item);
        return url.protocol === 'http:' || url.protocol === 'https:' ? [url.href] : [];
      } catch {
        return [];
      }
    })
    .slice(0, 5);
};

const formatFileSize = (bytes: number): string => {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

export default function RefundsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
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
  const [reviewingRefundId, setReviewingRefundId] = useState<string | null>(null);
  const [blockedReviewIds, setBlockedReviewIds] = useState<Set<string>>(() => new Set());
  const reviewingRefundIdRef = useRef<string | null>(null);

  // 详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<any | null>(null);
  const [retryingRefundId, setRetryingRefundId] = useState<string | null>(null);
  const retryingRefundIdRef = useRef<string | null>(null);
  const [imagePreview, setImagePreview] = useState<{ url: string; originalName: string } | null>(null);
  const liveObjectUrlsRef = useRef(new Set<string>());
  const imagePreviewUrlRef = useRef<string | null>(null);
  const pdfPreviewUrlRef = useRef<string | null>(null);
  const detailSessionRef = useRef(0);
  const previewRequestRef = useRef(0);
  const mountedRef = useRef(true);

  const revokeObjectUrl = (url: string) => {
    if (!liveObjectUrlsRef.current.delete(url)) return;
    URL.revokeObjectURL(url);
  };

  const revokeAllObjectUrls = () => {
    for (const url of liveObjectUrlsRef.current) URL.revokeObjectURL(url);
    liveObjectUrlsRef.current.clear();
    imagePreviewUrlRef.current = null;
    pdfPreviewUrlRef.current = null;
  };

  const clearImagePreview = () => {
    const imageUrl = imagePreviewUrlRef.current;
    if (imageUrl) revokeObjectUrl(imageUrl);
    imagePreviewUrlRef.current = null;
    setImagePreview(null);
  };

  const clearPdfPreview = () => {
    const pdfUrl = pdfPreviewUrlRef.current;
    if (pdfUrl) revokeObjectUrl(pdfUrl);
    pdfPreviewUrlRef.current = null;
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      detailSessionRef.current += 1;
      previewRequestRef.current += 1;
      revokeAllObjectUrls();
    };
  }, []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const records = getData<any[]>(await api.get('/admin/refunds'));
      setData(records);
      setDetailRecord((current: any | null) => current ? records.find(record => record.id === current.id) ?? current : null);
      setLoadError(null);
    } catch (error) {
      setLoadError(refundListLoadError(error));
      throw error;
    } finally {
      setLoading(false);
    }
  };

  const finishReviewRefresh = async (refundId: string) => {
    try {
      await fetchData();
      setBlockedReviewIds((current) => {
        if (!current.has(refundId)) return current;
        const next = new Set(current);
        next.delete(refundId);
        return next;
      });
    } catch (err: any) {
      setBlockedReviewIds((current) => new Set(current).add(refundId));
      message.error('刷新退款列表失败: ' + (err?.response?.data?.message || err?.message) + '，请刷新页面确认状态');
    } finally {
      reviewingRefundIdRef.current = null;
      setReviewingRefundId(null);
    }
  };

  const loadRefunds = () => {
    void fetchData().catch(() => undefined);
  };

  useEffect(() => { loadRefunds(); }, []);

  const handleApprove = async () => {
    const refundId = approveRecord?.id;
    if (!refundId || reviewingRefundIdRef.current || blockedReviewIds.has(refundId)) return;
    reviewingRefundIdRef.current = refundId;
    setReviewingRefundId(refundId);
    setApproveLoading(true);
    try {
      await api.put(`/admin/refunds/${approveRecord.id}/status`, { status: 'APPROVED' });
      message.success('退款审核已提交，请查看渠道处理状态');
      setApproveVisible(false);
      setApproveRecord(null);
    } catch (err: any) {
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      await finishReviewRefresh(refundId);
      setApproveLoading(false);
    }
  };

  const handleReject = async () => {
    const refundId = rejectRecord?.id;
    if (!refundId || reviewingRefundIdRef.current || blockedReviewIds.has(refundId)) return;
    if (!rejectReason.trim()) {
      message.warning('请填写拒绝原因');
      return;
    }
    reviewingRefundIdRef.current = refundId;
    setReviewingRefundId(refundId);
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
    } catch (err: any) {
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      await finishReviewRefresh(refundId);
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
    detailSessionRef.current += 1;
    previewRequestRef.current += 1;
    revokeAllObjectUrls();
    setImagePreview(null);
    setDetailRecord(record);
    setDetailVisible(true);
  };

  const closeDetail = () => {
    detailSessionRef.current += 1;
    previewRequestRef.current += 1;
    revokeAllObjectUrls();
    setImagePreview(null);
    setDetailVisible(false);
    setDetailRecord(null);
  };

  const requestEvidenceBlob = async (refundId: string, file: RefundEvidenceFile) => {
    const response = await api.get(evidenceContentPath(refundId, file.fileId), { responseType: 'blob' });
    return response.data as Blob;
  };

  const previewEvidence = async (file: RefundEvidenceFile) => {
    const refundId = detailRecord?.id;
    if (!refundId) return;
    const session = detailSessionRef.current;
    const request = previewRequestRef.current + 1;
    previewRequestRef.current = request;
    try {
      const blob = await requestEvidenceBlob(refundId, file);
      if (!mountedRef.current || session !== detailSessionRef.current || request !== previewRequestRef.current) return;
      const objectUrl = URL.createObjectURL(blob);
      liveObjectUrlsRef.current.add(objectUrl);
      if (file.contentType.startsWith('image/')) {
        clearPdfPreview();
        clearImagePreview();
        imagePreviewUrlRef.current = objectUrl;
        setImagePreview({ url: objectUrl, originalName: file.originalName });
      } else if (file.contentType === 'application/pdf') {
        clearImagePreview();
        clearPdfPreview();
        pdfPreviewUrlRef.current = objectUrl;
        try {
          window.open(objectUrl, '_blank', 'noopener,noreferrer');
        } catch (error) {
          clearPdfPreview();
          throw error;
        }
      } else {
        revokeObjectUrl(objectUrl);
        message.error(`预览凭证失败: 不支持预览 ${file.originalName}`);
      }
    } catch (error: any) {
      if (!mountedRef.current || session !== detailSessionRef.current || request !== previewRequestRef.current) return;
      message.error(`预览凭证失败: ${error?.response?.data?.message || error?.message || '未知错误'}`);
    }
  };

  const downloadEvidence = async (file: RefundEvidenceFile) => {
    const refundId = detailRecord?.id;
    if (!refundId) return;
    const session = detailSessionRef.current;
    try {
      const blob = await requestEvidenceBlob(refundId, file);
      if (!mountedRef.current || session !== detailSessionRef.current) return;
      const objectUrl = URL.createObjectURL(blob);
      liveObjectUrlsRef.current.add(objectUrl);
      let anchor: HTMLAnchorElement | null = null;
      try {
        anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = file.originalName;
        document.body.appendChild(anchor);
        anchor.click();
      } finally {
        try {
          anchor?.remove();
        } finally {
          revokeObjectUrl(objectUrl);
        }
      }
    } catch (error: any) {
      if (!mountedRef.current || session !== detailSessionRef.current) return;
      message.error(`下载凭证失败: ${error?.response?.data?.message || error?.message || '未知错误'}`);
    }
  };

  const handleRetry = async (record: any) => {
    if (!isRetryableRefund(record) || retryingRefundIdRef.current) return;
    retryingRefundIdRef.current = record.id;
    setRetryingRefundId(record.id);
    try {
      await api.post(`/admin/refunds/${record.id}/retry`);
      message.success('失败退款项已重新提交，请查看渠道处理状态');
    } catch (err: any) {
      message.error('重试失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      try {
        await fetchData();
      } catch (err: any) {
        message.error('刷新退款列表失败: ' + (err?.response?.data?.message || err?.message));
      } finally {
        retryingRefundIdRef.current = null;
        setRetryingRefundId(null);
      }
    }
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
      title: '支付流程', dataIndex: 'paymentFlow', width: 160,
      render: (value: string) => paymentFlowLabels[value] || value || '-',
    },
    {
      title: '退款前订单状态', dataIndex: 'originalStatus', width: 130,
      render: (value: string) => originalStatusLabels[value] || value || '-',
    },
    {
      title: '申请退款金额', dataIndex: 'requestedAmountMinor', width: 130,
      render: (_: number, record: any) => (
        <span style={{ color: '#f5222d', fontWeight: 600 }}>{requestedRefundAmount(record)}</span>
      ),
    },
    {
      title: '已退金额', dataIndex: 'refundedAmountMinor', width: 120,
      render: (value: number, record: any) => formatMoney(value, record.currency),
    },
    {
      title: '原支付金额', dataIndex: 'paymentAmount', width: 120,
      render: (_: number, record: any) => paidAmount(record),
    },
    {
      title: '退款类型', dataIndex: 'refundType', width: 90,
      render: (v: string) => refundTypeLabels[v] || v || '全额退款',
    },
    {
      title: '凭证', key: 'evidence', width: 70,
      render: (_: unknown, record: any) => refundEvidenceFiles(record.evidenceFiles).length + legacyEvidenceUrls(record.evidenceUrl).length,
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
              <Button icon={<CheckOutlined />} size="small" type="primary" disabled={reviewingRefundId !== null || blockedReviewIds.has(record.id)} onClick={() => openApprove(record)}>批准</Button>
              <Button icon={<CloseOutlined />} size="small" danger disabled={reviewingRefundId !== null || blockedReviewIds.has(record.id)} onClick={() => openReject(record)}>拒绝</Button>
            </>
          )}
          {isRetryableRefund(record) && (
            <Button
              size="small"
              loading={retryingRefundId === record.id}
              disabled={retryingRefundId !== null}
              onClick={() => handleRetry(record)}
            >
              重试失败项
            </Button>
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
      {loadError && (
        <Alert
          type="error"
          showIcon
          title={loadError}
          action={<Button size="small" loading={loading} onClick={loadRefunds}>重新加载</Button>}
          style={{ marginBottom: 16 }}
        />
      )}
      <Space style={{ marginBottom: 16 }}>
        <Select
          aria-label="退款状态"
          virtual={false}
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
        okButtonProps={{ danger: true, disabled: reviewingRefundId !== null || blockedReviewIds.has(approveRecord?.id) }}
      >
        {approveRecord && (
          <div>
            <p>确定批准该退款申请吗？</p>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="订单编号">{approveRecord.orderNo || '-'}</Descriptions.Item>
              <Descriptions.Item label="项目">{approveRecord.projectName || '-'}</Descriptions.Item>
              <Descriptions.Item label="退款金额"><span style={{ color: '#f5222d', fontWeight: 600 }}>{requestedRefundAmount(approveRecord)}</span></Descriptions.Item>
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
        okButtonProps={{ danger: true, disabled: !rejectReason.trim() || reviewingRefundId !== null || blockedReviewIds.has(rejectRecord?.id) }}
      >
        {rejectRecord && (
          <div>
            <p>拒绝退款：<b>{rejectRecord.orderNo || rejectRecord.orderId}</b>，退款金额 <span style={{ color: '#f5222d' }}>{requestedRefundAmount(rejectRecord)}</span></p>
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
        onCancel={closeDetail}
        footer={(
          <Space>
            {isRetryableRefund(detailRecord) && (
              <Button
                loading={retryingRefundId === detailRecord.id}
                disabled={retryingRefundId !== null}
                onClick={() => handleRetry(detailRecord)}
              >
                重试失败项
              </Button>
            )}
            <Button onClick={closeDetail}>关闭</Button>
          </Space>
        )}
        width={600}
      >
        {detailRecord && (
          <>
           <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="退款ID" span={2}>{detailRecord.id}</Descriptions.Item>
            <Descriptions.Item label="订单ID" span={2}>{detailRecord.orderId}</Descriptions.Item>
            <Descriptions.Item label="订单编号">{detailRecord.orderNo || '-'}</Descriptions.Item>
            <Descriptions.Item label="用户ID">{detailRecord.userId}</Descriptions.Item>
            <Descriptions.Item label="项目">{detailRecord.projectName || '-'}</Descriptions.Item>
            <Descriptions.Item label="机构">{detailRecord.institutionName || '-'}</Descriptions.Item>
            <Descriptions.Item label="医生">{detailRecord.doctorName || '-'}</Descriptions.Item>
            <Descriptions.Item label="支付流程">{paymentFlowLabels[detailRecord.paymentFlow] || detailRecord.paymentFlow || '-'}</Descriptions.Item>
            <Descriptions.Item label="申请退款金额"><span style={{ color: '#f5222d', fontWeight: 600 }}>{requestedRefundAmount(detailRecord)}</span></Descriptions.Item>
            <Descriptions.Item label="已退金额">{formatMoney(detailRecord.refundedAmountMinor, detailRecord.currency)}</Descriptions.Item>
            <Descriptions.Item label="原支付金额">{paidAmount(detailRecord)}</Descriptions.Item>
            <Descriptions.Item label="退款类型">{refundTypeLabels[detailRecord.refundType] || detailRecord.refundType || '全额退款'}</Descriptions.Item>
            <Descriptions.Item label="状态" span={2}><Tag color={statusColors[detailRecord.status]}>{statusLabels[detailRecord.status] ?? detailRecord.status}</Tag></Descriptions.Item>
            <Descriptions.Item label="退款原因" span={2}>{detailRecord.reason}</Descriptions.Item>
            <Descriptions.Item label="详细说明" span={2}>{detailRecord.description || '-'}</Descriptions.Item>
            {detailRecord.reviewedBy && <Descriptions.Item label="审核人">{detailRecord.reviewedBy}</Descriptions.Item>}
            {detailRecord.reviewedAt && <Descriptions.Item label="审核时间">{detailRecord.reviewedAt}</Descriptions.Item>}
            {detailRecord.rejectReason && <Descriptions.Item label="拒绝原因" span={2}>{detailRecord.rejectReason}</Descriptions.Item>}
            <Descriptions.Item label="创建时间">{detailRecord.createdAt}</Descriptions.Item>
            <Descriptions.Item label="处理时间">{detailRecord.processedAt || '-'}</Descriptions.Item>
           </Descriptions>
           <div style={{ marginTop: 16 }}>
             <h3>退款凭证</h3>
             {refundEvidenceFiles(detailRecord.evidenceFiles).map((file) => (
               <div key={file.fileId} data-testid="refund-evidence-metadata" style={{ marginBottom: 8 }}>
                 <div>{file.originalName} · {file.contentType} · {formatFileSize(file.sizeBytes)}</div>
                 <Space size="small">
                   <Button size="small" onClick={() => void previewEvidence(file)} aria-label={`预览 ${file.originalName}`}>预览</Button>
                   <Button size="small" onClick={() => void downloadEvidence(file)} aria-label={`下载 ${file.originalName}`}>下载</Button>
                 </Space>
               </div>
             ))}
             {legacyEvidenceUrls(detailRecord.evidenceUrl).map((url) => (
               <div key={url} style={{ marginBottom: 8 }}>
                 <a href={url} target="_blank" rel="noopener noreferrer">旧版凭证（公开链接）</a>
               </div>
             ))}
             {!refundEvidenceFiles(detailRecord.evidenceFiles).length && !legacyEvidenceUrls(detailRecord.evidenceUrl).length && <span>-</span>}
             {imagePreview && (
               <div style={{ marginTop: 12 }}>
                 <img src={imagePreview.url} alt={imagePreview.originalName} style={{ maxWidth: '100%', maxHeight: 360 }} />
               </div>
             )}
           </div>
           <div style={{ marginTop: 16 }}>
            <h3>渠道退款项</h3>
            <Table
              dataSource={detailRecord.items || []}
              rowKey="id"
              size="small"
              pagination={false}
              scroll={{ x: 'max-content' }}
              columns={[
                { title: '支付记录', dataIndex: 'paymentId', width: 130, render: (value: string) => value || '-' },
                { title: '渠道', dataIndex: 'provider', width: 110, render: (value: string) => value || '-' },
                { title: '币种', dataIndex: 'currency', width: 80, render: (value: string) => value || '-' },
                { title: '退款金额', dataIndex: 'amountMinor', width: 120, render: (value: number, item: any) => formatMoney(value, item.currency) },
                { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => value || '-' },
                { title: '渠道退款单号', dataIndex: 'providerRefundId', width: 160, render: (value: string) => value || '-' },
                { title: '失败码', dataIndex: 'failureCode', width: 130, render: (value: string) => value || '-' },
                { title: '失败信息', dataIndex: 'failureMessage', width: 180, render: (value: string) => value || '-' },
                { title: '请求时间', dataIndex: 'requestedAt', width: 170, render: (value: string) => value || '-' },
                { title: '完成时间', dataIndex: 'completedAt', width: 170, render: (value: string) => value || '-' },
                { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (value: string) => value || '-' },
              ]}
            />
          </div>
          </>
        )}
      </Modal>
    </div>
  );
}
