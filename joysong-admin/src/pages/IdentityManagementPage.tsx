import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import {
  CheckOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import api, { getApiErrorMessage, getData } from '../api';
import {
  identityRoleLabel,
  identityRoleOptions,
  identityStatusColor,
  identityStatusLabel,
  institutionMemberRoleOptions,
  relationStatusOptions,
} from '../identity';

type InstitutionOption = { label: string; value: string };

type IdentityDocument = {
  fileId: string;
  documentType: string;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  status: string;
};

type IdentityApplication = {
  id: string;
  userId: string;
  userName: string;
  userPhone?: string;
  roleCode: string;
  status: string;
  applicationData: Record<string, unknown>;
  reviewNote: string;
  reviewerName?: string;
  submittedAt?: string;
  reviewedAt?: string;
  documents: IdentityDocument[];
};

type UserRole = {
  userId: string;
  userName: string;
  userPhone?: string;
  accountActive: boolean;
  roleCode: string;
  status: string;
  sourceApplicationId?: string;
  activatedAt?: string;
  revokedAt?: string;
  revokerName?: string;
  revokeReason: string;
};

type InstitutionMembership = {
  id: string;
  userId: string;
  userName: string;
  userPhone?: string;
  institutionId: string;
  institutionName: string;
  memberRole: string;
  status: string;
  confirmerName?: string;
  confirmedAt?: string;
  revokedAt?: string;
  updatedAt?: string;
};

type DoctorPractice = {
  id: string;
  doctorId: string;
  doctorName: string;
  doctorPhone?: string;
  institutionId: string;
  institutionName: string;
  primary: boolean;
  status: string;
  registrationNo: string;
  registrationFileId?: string;
  confirmerName?: string;
  confirmedAt?: string;
  revokedAt?: string;
  updatedAt?: string;
};

function StatusTag({ status }: { status: string }) {
  return <Tag color={identityStatusColor(status)}>{identityStatusLabel(status)}</Tag>;
}

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function ApplicationSection() {
  const [data, setData] = useState<IdentityApplication[]>([]);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<string | undefined>('PENDING');
  const [roleCode, setRoleCode] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [detail, setDetail] = useState<IdentityApplication | null>(null);
  const [reviewTarget, setReviewTarget] = useState<IdentityApplication | null>(null);
  const [reviewDecision, setReviewDecision] = useState<'APPROVED' | 'REJECTED'>('APPROVED');
  const [reviewing, setReviewing] = useState(false);
  const [previewingFileId, setPreviewingFileId] = useState<string>();
  const [reviewForm] = Form.useForm<{ reviewNote: string }>();

  const fetchData = async () => {
    setLoading(true);
    try {
      const response = await api.get('/admin/identity/applications', {
        params: { status, roleCode, keyword: keyword.trim() || undefined },
      });
      setData(getData<IdentityApplication[]>(response));
    } catch (error) {
      message.error(getApiErrorMessage(error, '身份申请加载失败'));
    } finally {
      setLoading(false);
    }
  };

  // 筛选状态变化时自动刷新；关键词只在点击查询或回车时提交。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void fetchData(); }, [status, roleCode]);

  const openReview = (record: IdentityApplication, decision: 'APPROVED' | 'REJECTED') => {
    setReviewTarget(record);
    setReviewDecision(decision);
    reviewForm.resetFields();
  };

  const submitReview = async () => {
    if (!reviewTarget) return;
    const values = await reviewForm.validateFields();
    setReviewing(true);
    try {
      await api.put(`/admin/identity/applications/${reviewTarget.id}/review`, {
        decision: reviewDecision,
        reviewNote: values.reviewNote || '',
      });
      message.success(reviewDecision === 'APPROVED' ? '身份申请已通过' : '身份申请已驳回');
      setReviewTarget(null);
      await fetchData();
    } catch (error) {
      message.error(getApiErrorMessage(error, '审核失败'));
    } finally {
      setReviewing(false);
    }
  };

  const previewDocument = async (document: IdentityDocument) => {
    const previewWindow = window.open('', '_blank');
    if (previewWindow) previewWindow.opener = null;
    setPreviewingFileId(document.fileId);
    try {
      const response = await api.get(`/admin/identity/files/${document.fileId}`, { responseType: 'blob' });
      const blob = new Blob([response.data], { type: document.contentType || 'application/octet-stream' });
      const objectUrl = URL.createObjectURL(blob);
      if (previewWindow) {
        previewWindow.location.href = objectUrl;
      } else {
        const link = window.document.createElement('a');
        link.href = objectUrl;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.click();
      }
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
    } catch (error) {
      previewWindow?.close();
      message.error(getApiErrorMessage(error, '认证材料打开失败'));
    } finally {
      setPreviewingFileId(undefined);
    }
  };

  const columns = [
    {
      title: '申请用户',
      key: 'user',
      width: 220,
      render: (_: unknown, record: IdentityApplication) => (
        <div>
          <div>{record.userName || '未命名用户'}</div>
          <div style={{ color: '#999', fontSize: 12 }}>{record.userPhone || record.userId}</div>
        </div>
      ),
    },
    { title: '申请身份', dataIndex: 'roleCode', width: 180, render: identityRoleLabel },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <StatusTag status={value} /> },
    { title: '提交时间', dataIndex: 'submittedAt', width: 170, render: formatTime },
    { title: '审核人', dataIndex: 'reviewerName', width: 110, render: (value?: string) => value || '-' },
    { title: '审核说明', dataIndex: 'reviewNote', ellipsis: true, render: (value: string) => value || '-' },
    {
      title: '操作',
      key: 'actions',
      width: 230,
      fixed: 'right' as const,
      render: (_: unknown, record: IdentityApplication) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => setDetail(record)}>材料</Button>
          {record.status === 'PENDING' && (
            <>
              <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => openReview(record, 'APPROVED')}>通过</Button>
              <Button size="small" danger icon={<StopOutlined />} onClick={() => openReview(record, 'REJECTED')}>驳回</Button>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card title="身份申请审核" extra={<Tag color="blue">通过后生成可切换职业身份</Tag>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="全部申请状态"
          value={status}
          onChange={setStatus}
          style={{ width: 140 }}
          options={[
            { label: '待审核', value: 'PENDING' },
            { label: '已通过', value: 'APPROVED' },
            { label: '已驳回', value: 'REJECTED' },
            { label: '已撤回', value: 'WITHDRAWN' },
          ]}
        />
        <Select allowClear placeholder="全部身份" value={roleCode} onChange={setRoleCode} style={{ width: 210 }} options={identityRoleOptions} />
        <Input
          allowClear
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          onPressEnter={() => void fetchData()}
          placeholder="用户姓名、手机号或 ID"
          style={{ width: 240 }}
        />
        <Button type="primary" onClick={() => void fetchData()}>查询</Button>
        <Button icon={<ReloadOutlined />} onClick={() => { setKeyword(''); void fetchData(); }}>刷新</Button>
      </Space>
      <Table rowKey="id" dataSource={data} columns={columns} loading={loading} size="small" scroll={{ x: 1100 }} />

      <Modal title="申请材料" open={Boolean(detail)} footer={null} onCancel={() => setDetail(null)} width={760} destroyOnHidden>
        {detail && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="申请人">{detail.userName}</Descriptions.Item>
              <Descriptions.Item label="手机号">{detail.userPhone || '-'}</Descriptions.Item>
              <Descriptions.Item label="用户 ID" span={2}>{detail.userId}</Descriptions.Item>
              <Descriptions.Item label="申请身份">{identityRoleLabel(detail.roleCode)}</Descriptions.Item>
              <Descriptions.Item label="状态"><StatusTag status={detail.status} /></Descriptions.Item>
            </Descriptions>
            <div>
              <h4>申请表单</h4>
              <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 6, overflow: 'auto', whiteSpace: 'pre-wrap' }}>
                {JSON.stringify(detail.applicationData, null, 2)}
              </pre>
            </div>
            <div>
              <h4>认证材料</h4>
              <Table
                rowKey="fileId"
                size="small"
                pagination={false}
                dataSource={detail.documents}
                columns={[
                  { title: '材料类型', dataIndex: 'documentType' },
                  { title: '文件名', dataIndex: 'originalName', render: (value: string) => value || '-' },
                  { title: '格式', dataIndex: 'contentType' },
                  { title: '大小', dataIndex: 'sizeBytes', render: (value: number) => `${Math.ceil(value / 1024)} KB` },
                  { title: '状态', dataIndex: 'status' },
                  {
                    title: '操作',
                    key: 'action',
                    render: (_: unknown, document: IdentityDocument) => (
                      <Button
                        type="link"
                        size="small"
                        icon={<EyeOutlined />}
                        loading={previewingFileId === document.fileId}
                        onClick={() => void previewDocument(document)}
                      >
                        安全查看
                      </Button>
                    ),
                  },
                ]}
              />
            </div>
          </Space>
        )}
      </Modal>

      <Modal
        title={reviewDecision === 'APPROVED' ? '通过身份申请' : '驳回身份申请'}
        open={Boolean(reviewTarget)}
        confirmLoading={reviewing}
        okText={reviewDecision === 'APPROVED' ? '确认通过' : '确认驳回'}
        okButtonProps={{ danger: reviewDecision === 'REJECTED' }}
        onOk={() => void submitReview()}
        onCancel={() => setReviewTarget(null)}
        destroyOnHidden
      >
        <p style={{ marginBottom: 16 }}>
          {reviewTarget?.userName} · {reviewTarget ? identityRoleLabel(reviewTarget.roleCode) : ''}
        </p>
        <Form form={reviewForm} layout="vertical">
          <Form.Item
            name="reviewNote"
            label="审核说明"
            rules={reviewDecision === 'REJECTED' ? [{ required: true, message: '请填写驳回原因' }] : []}
          >
            <Input.TextArea rows={4} maxLength={1000} showCount placeholder={reviewDecision === 'APPROVED' ? '可填写审核备注' : '请说明材料问题或驳回原因'} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}

function RoleSection() {
  const [data, setData] = useState<UserRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<string | undefined>('ACTIVE');
  const [roleCode, setRoleCode] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [revokeTarget, setRevokeTarget] = useState<UserRole | null>(null);
  const [revoking, setRevoking] = useState(false);
  const [revokeForm] = Form.useForm<{ reason: string }>();

  const fetchData = async () => {
    setLoading(true);
    try {
      const response = await api.get('/admin/identity/roles', {
        params: { status, roleCode, keyword: keyword.trim() || undefined },
      });
      setData(getData<UserRole[]>(response));
    } catch (error) {
      message.error(getApiErrorMessage(error, '职业身份加载失败'));
    } finally {
      setLoading(false);
    }
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void fetchData(); }, [status, roleCode]);

  const submitRevoke = async () => {
    if (!revokeTarget) return;
    const { reason } = await revokeForm.validateFields();
    setRevoking(true);
    try {
      await api.put(`/admin/identity/roles/${revokeTarget.userId}/${revokeTarget.roleCode}/revoke`, { reason });
      message.success('职业身份已撤销，账号已回退为普通用户能力');
      setRevokeTarget(null);
      await fetchData();
    } catch (error) {
      message.error(getApiErrorMessage(error, '撤销身份失败'));
    } finally {
      setRevoking(false);
    }
  };

  const columns = [
    {
      title: '用户',
      key: 'user',
      width: 230,
      render: (_: unknown, record: UserRole) => (
        <div>
          <div>{record.userName} {!record.accountActive && <Tag color="red">账号已注销</Tag>}</div>
          <div style={{ color: '#999', fontSize: 12 }}>{record.userPhone || record.userId}</div>
        </div>
      ),
    },
    { title: '职业身份', dataIndex: 'roleCode', width: 190, render: identityRoleLabel },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <StatusTag status={value} /> },
    { title: '生效时间', dataIndex: 'activatedAt', width: 170, render: formatTime },
    { title: '来源申请', dataIndex: 'sourceApplicationId', width: 190, ellipsis: true, render: (value?: string) => value || '历史/人工数据' },
    { title: '撤销原因', dataIndex: 'revokeReason', ellipsis: true, render: (value: string) => value || '-' },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      render: (_: unknown, record: UserRole) => record.status === 'ACTIVE' ? (
        <Button
          size="small"
          danger
          icon={<StopOutlined />}
          onClick={() => { revokeForm.resetFields(); setRevokeTarget(record); }}
        >
          撤销身份
        </Button>
      ) : null,
    },
  ];

  return (
    <Card title="有效职业身份" extra={<Tag>普通用户无需身份记录</Tag>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="全部状态"
          value={status}
          onChange={setStatus}
          style={{ width: 130 }}
          options={[{ label: '有效', value: 'ACTIVE' }, { label: '已撤销', value: 'REVOKED' }]}
        />
        <Select allowClear placeholder="全部身份" value={roleCode} onChange={setRoleCode} style={{ width: 210 }} options={identityRoleOptions} />
        <Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => void fetchData()} placeholder="用户姓名、手机号或 ID" style={{ width: 240 }} />
        <Button type="primary" onClick={() => void fetchData()}>查询</Button>
        <Button icon={<ReloadOutlined />} onClick={() => void fetchData()}>刷新</Button>
      </Space>
      <Table rowKey={(record) => `${record.userId}-${record.roleCode}`} dataSource={data} columns={columns} loading={loading} size="small" scroll={{ x: 1100 }} />
      <Modal
        title="撤销职业身份"
        open={Boolean(revokeTarget)}
        confirmLoading={revoking}
        okText="确认撤销"
        okButtonProps={{ danger: true }}
        onOk={() => void submitRevoke()}
        onCancel={() => setRevokeTarget(null)}
        destroyOnHidden
      >
        <p style={{ marginBottom: 16 }}>
          撤销后，该用户相关机构任职或医生执业关系也会失效，并回退为普通用户。
        </p>
        <Form form={revokeForm} layout="vertical">
          <Form.Item name="reason" label="撤销原因" rules={[{ required: true, message: '请填写撤销原因' }]}>
            <Input.TextArea rows={4} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}

function MembershipSection({ institutionOptions }: { institutionOptions: InstitutionOption[] }) {
  const [data, setData] = useState<InstitutionMembership[]>([]);
  const [activeRoles, setActiveRoles] = useState<UserRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [institutionId, setInstitutionId] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [memberRole, setMemberRole] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createForm] = Form.useForm<{ userId: string; institutionId: string; memberRole: string }>();
  const selectedMemberRole = Form.useWatch('memberRole', createForm);

  const fetchData = async () => {
    setLoading(true);
    try {
      const response = await api.get('/admin/identity/memberships', {
        params: { institutionId, status, memberRole, keyword: keyword.trim() || undefined },
      });
      setData(getData<InstitutionMembership[]>(response));
    } catch (error) {
      message.error(getApiErrorMessage(error, '机构成员关系加载失败'));
    } finally {
      setLoading(false);
    }
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void fetchData(); }, [institutionId, status, memberRole]);

  const openCreate = async () => {
    createForm.resetFields();
    setCreateOpen(true);
    try {
      const response = await api.get('/admin/identity/roles', { params: { status: 'ACTIVE' } });
      setActiveRoles(getData<UserRole[]>(response));
    } catch (error) {
      message.error(getApiErrorMessage(error, '可选职业身份加载失败'));
    }
  };

  const userOptions = useMemo(() => activeRoles
    .filter((role) => role.roleCode === selectedMemberRole && role.accountActive)
    .map((role) => ({
      label: `${role.userName} · ${role.userPhone || '无手机号'} · ${role.userId}`,
      value: role.userId,
    })), [activeRoles, selectedMemberRole]);

  const submitCreate = async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      await api.post('/admin/identity/memberships', values);
      message.success('机构成员关系已创建，等待审核');
      setCreateOpen(false);
      await fetchData();
    } catch (error) {
      message.error(getApiErrorMessage(error, '创建机构成员关系失败'));
    } finally {
      setCreating(false);
    }
  };

  const approve = async (record: InstitutionMembership) => {
    try {
      await api.put(`/admin/identity/memberships/${record.id}/approve`);
      message.success('机构成员关系已通过');
      await fetchData();
    } catch (error) {
      message.error(getApiErrorMessage(error, '审核失败'));
    }
  };

  const revoke = (record: InstitutionMembership) => {
    Modal.confirm({
      title: '撤销机构成员关系',
      content: `确认撤销「${record.userName}」在「${record.institutionName}」的${identityRoleLabel(record.memberRole)}任职关系吗？`,
      okText: '确认撤销',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await api.put(`/admin/identity/memberships/${record.id}/revoke`);
        message.success('机构成员关系已撤销');
        await fetchData();
      },
    });
  };

  const columns = [
    {
      title: '成员用户',
      key: 'user',
      width: 220,
      render: (_: unknown, record: InstitutionMembership) => (
        <div><div>{record.userName}</div><div style={{ color: '#999', fontSize: 12 }}>{record.userPhone || record.userId}</div></div>
      ),
    },
    { title: '机构', dataIndex: 'institutionName', width: 180 },
    { title: '任职身份', dataIndex: 'memberRole', width: 180, render: identityRoleLabel },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <StatusTag status={value} /> },
    { title: '确认人', dataIndex: 'confirmerName', width: 110, render: (value?: string) => value || '-' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: unknown, record: InstitutionMembership) => (
        <Space>
          {record.status === 'PENDING' && <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => void approve(record)}>通过</Button>}
          {record.status !== 'REVOKED' && <Button size="small" danger icon={<StopOutlined />} onClick={() => revoke(record)}>撤销</Button>}
        </Space>
      ),
    },
  ];

  return (
    <Card title="机构成员管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => void openCreate()}>新增任职关系</Button>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select allowClear showSearch optionFilterProp="label" placeholder="全部机构" value={institutionId} onChange={setInstitutionId} style={{ width: 200 }} options={institutionOptions} />
        <Select allowClear placeholder="全部状态" value={status} onChange={setStatus} style={{ width: 130 }} options={relationStatusOptions} />
        <Select allowClear placeholder="全部任职身份" value={memberRole} onChange={setMemberRole} style={{ width: 210 }} options={institutionMemberRoleOptions} />
        <Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => void fetchData()} placeholder="成员姓名、手机号或 ID" style={{ width: 230 }} />
        <Button type="primary" onClick={() => void fetchData()}>查询</Button>
        <Button icon={<ReloadOutlined />} onClick={() => void fetchData()}>刷新</Button>
      </Space>
      <Table rowKey="id" dataSource={data} columns={columns} loading={loading} size="small" scroll={{ x: 1100 }} />
      <Modal
        title="新增机构任职关系"
        open={createOpen}
        confirmLoading={creating}
        okText="创建并进入待审核"
        onOk={() => void submitCreate()}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="memberRole" label="任职身份" rules={[{ required: true, message: '请选择任职身份' }]}>
            <Select options={institutionMemberRoleOptions} placeholder="请选择" />
          </Form.Item>
          <Form.Item name="userId" label="成员用户" rules={[{ required: true, message: '请选择已取得该身份的用户' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={userOptions}
              disabled={!selectedMemberRole}
              placeholder={selectedMemberRole ? '选择已通过身份审核的用户' : '请先选择任职身份'}
            />
          </Form.Item>
          <Form.Item name="institutionId" label="所属机构" rules={[{ required: true, message: '请选择机构' }]}>
            <Select showSearch optionFilterProp="label" options={institutionOptions} placeholder="请选择机构" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}

function DoctorPracticeSection({ institutionOptions }: { institutionOptions: InstitutionOption[] }) {
  const [data, setData] = useState<DoctorPractice[]>([]);
  const [loading, setLoading] = useState(false);
  const [institutionId, setInstitutionId] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');

  const fetchData = async () => {
    setLoading(true);
    try {
      const response = await api.get('/admin/identity/doctor-practices', {
        params: { institutionId, status, keyword: keyword.trim() || undefined },
      });
      setData(getData<DoctorPractice[]>(response));
    } catch (error) {
      message.error(getApiErrorMessage(error, '医生执业关系加载失败'));
    } finally {
      setLoading(false);
    }
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void fetchData(); }, [institutionId, status]);

  const approve = async (record: DoctorPractice) => {
    try {
      await api.put(`/admin/identity/doctor-practices/${record.id}/approve`);
      message.success('医生执业关系已通过');
      await fetchData();
    } catch (error) {
      message.error(getApiErrorMessage(error, '审核失败'));
    }
  };

  const revoke = (record: DoctorPractice) => {
    Modal.confirm({
      title: '撤销医生执业关系',
      content: `确认撤销「${record.doctorName}」在「${record.institutionName}」的执业关系吗？`,
      okText: '确认撤销',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await api.put(`/admin/identity/doctor-practices/${record.id}/revoke`);
        message.success('医生执业关系已撤销');
        await fetchData();
      },
    });
  };

  const columns = [
    {
      title: '医生',
      key: 'doctor',
      width: 220,
      render: (_: unknown, record: DoctorPractice) => (
        <div><div>{record.doctorName}</div><div style={{ color: '#999', fontSize: 12 }}>{record.doctorPhone || record.doctorId}</div></div>
      ),
    },
    { title: '执业机构', dataIndex: 'institutionName', width: 190 },
    { title: '主机构', dataIndex: 'primary', width: 80, render: (value: boolean) => value ? <Tag color="blue">是</Tag> : '-' },
    { title: '执业登记号', dataIndex: 'registrationNo', width: 150, render: (value: string) => value || '-' },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <StatusTag status={value} /> },
    { title: '确认人', dataIndex: 'confirmerName', width: 110, render: (value?: string) => value || '-' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: unknown, record: DoctorPractice) => (
        <Space>
          {record.status === 'PENDING' && <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => void approve(record)}>通过</Button>}
          {record.status !== 'REVOKED' && <Button size="small" danger icon={<StopOutlined />} onClick={() => revoke(record)}>撤销</Button>}
        </Space>
      ),
    },
  ];

  return (
    <Card title="医生执业审核" extra={<Tag color="purple">医生档案中的机构绑定默认进入待审核</Tag>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select allowClear showSearch optionFilterProp="label" placeholder="全部机构" value={institutionId} onChange={setInstitutionId} style={{ width: 210 }} options={institutionOptions} />
        <Select allowClear placeholder="全部状态" value={status} onChange={setStatus} style={{ width: 130 }} options={relationStatusOptions} />
        <Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => void fetchData()} placeholder="医生姓名、手机号或 ID" style={{ width: 240 }} />
        <Button type="primary" onClick={() => void fetchData()}>查询</Button>
        <Button icon={<ReloadOutlined />} onClick={() => void fetchData()}>刷新</Button>
      </Space>
      <Table rowKey="id" dataSource={data} columns={columns} loading={loading} size="small" scroll={{ x: 1100 }} />
    </Card>
  );
}

export default function IdentityManagementPage() {
  const [institutionOptions, setInstitutionOptions] = useState<InstitutionOption[]>([]);

  useEffect(() => {
    api.get('/admin/institutions')
      .then((response) => {
        const institutions = getData<Array<{ id: string; name: string }>>(response);
        setInstitutionOptions(institutions.map((item) => ({ label: item.name, value: item.id })));
      })
      .catch((error) => message.error(getApiErrorMessage(error, '机构选项加载失败')));
  }, []);

  return (
    <div>
      <div style={{ marginBottom: 16, textAlign: 'left' }}>
        <h2>身份与入驻审核</h2>
        <p style={{ color: '#777' }}>普通用户是账户的基础能力；医生、顾问、机构法人和机构客服均需审核后才能切换。</p>
      </div>
      <Tabs
        defaultActiveKey="applications"
        items={[
          { key: 'applications', label: '身份申请', children: <ApplicationSection /> },
          { key: 'roles', label: '职业身份', children: <RoleSection /> },
          { key: 'memberships', label: '机构成员', children: <MembershipSection institutionOptions={institutionOptions} /> },
          { key: 'doctor-practices', label: '医生执业', children: <DoctorPracticeSection institutionOptions={institutionOptions} /> },
        ]}
      />
    </div>
  );
}
