import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Form, Input, message, Modal, Space, Table, Tabs, Tag, Typography,
} from 'antd';
import { CheckOutlined, EditOutlined, LoginOutlined, LogoutOutlined, StopOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import ImageUpload from '../components/ImageUpload';
import MultiImageUpload from '../components/MultiImageUpload';

interface ProjectDoctor {
  id: string;
  name: string;
  serviceDescription?: string;
  serviceTags?: string;
  scheduleNote?: string;
  coverImage?: string;
  images?: string;
}

interface InstitutionProject {
  id: string;
  institutionId: string;
  effectiveName: string;
  price: number;
  isActive: boolean;
  doctors: ProjectDoctor[];
}

interface ProjectRequest {
  id: string;
  doctorId: string;
  doctorName: string;
  institutionId: string;
  institutionName: string;
  institutionProjectId: string;
  projectName: string;
  requestType: 'JOIN' | 'PROFILE_UPDATE' | 'LEAVE';
  serviceDescription: string;
  serviceTags: string;
  scheduleNote: string;
  coverImage: string;
  images: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  submittedBy: string;
  reviewerName?: string;
  reviewNote?: string;
  submittedAt: string;
}

const requestTypeText: Record<ProjectRequest['requestType'], string> = {
  JOIN: '申请加入',
  PROFILE_UPDATE: '资料变更',
  LEAVE: '申请退出',
};

const statusColor: Record<ProjectRequest['status'], string> = {
  PENDING: 'orange', APPROVED: 'green', REJECTED: 'red', WITHDRAWN: 'default',
};

export default function ProjectCollaborationPage() {
  const context = getManagementContext();
  const doctorId = context?.doctorId;
  const canReview = context?.platformRole === 'ADMIN' || (context?.managedInstitutionIds.length || 0) > 0;
  const [projects, setProjects] = useState<InstitutionProject[]>([]);
  const [requests, setRequests] = useState<ProjectRequest[]>([]);
  const [institutionNames, setInstitutionNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [requestOpen, setRequestOpen] = useState(false);
  const [requestProject, setRequestProject] = useState<InstitutionProject | null>(null);
  const [requestType, setRequestType] = useState<'JOIN' | 'PROFILE_UPDATE'>('JOIN');
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewing, setReviewing] = useState<ProjectRequest | null>(null);
  const [reviewDecision, setReviewDecision] = useState<'APPROVED' | 'REJECTED'>('APPROVED');
  const [submitting, setSubmitting] = useState(false);
  const [requestForm] = Form.useForm();
  const [reviewForm] = Form.useForm();

  const refresh = async () => {
    setLoading(true);
    try {
      const [projectRes, requestRes, institutionRes] = await Promise.all([
        api.get('/admin/institution-projects'),
        api.get('/admin/institution-project-requests'),
        api.get('/admin/institutions'),
      ]);
      setProjects(getData<InstitutionProject[]>(projectRes as any) || []);
      setRequests(getData<ProjectRequest[]>(requestRes as any) || []);
      const institutions = getData<any[]>(institutionRes as any) || [];
      setInstitutionNames(Object.fromEntries(institutions.map(item => [item.id, item.name])));
    } catch (error) {
      message.error(getApiErrorMessage(error, '项目协作数据加载失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const pendingByProject = useMemo(() => new Map(
    requests.filter(item => item.doctorId === doctorId && item.status === 'PENDING')
      .map(item => [item.institutionProjectId, item]),
  ), [requests, doctorId]);

  const openRequest = (project: InstitutionProject, type: 'JOIN' | 'PROFILE_UPDATE') => {
    const profile = project.doctors?.find(item => item.id === doctorId);
    setRequestProject(project);
    setRequestType(type);
    requestForm.setFieldsValue({
      serviceDescription: profile?.serviceDescription || '',
      serviceTags: profile?.serviceTags || '',
      scheduleNote: profile?.scheduleNote || '',
      coverImage: profile?.coverImage || '',
      images: profile?.images || '',
    });
    setRequestOpen(true);
  };

  const submitRequest = async () => {
    if (!requestProject) return;
    try {
      const values = await requestForm.validateFields();
      setSubmitting(true);
      await api.post('/admin/institution-project-requests', {
        institutionProjectId: requestProject.id,
        requestType,
        ...values,
      });
      message.success('已提交机构审核，审核通过后生效');
      setRequestOpen(false);
      await refresh();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '项目申请提交失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const requestLeave = (project: InstitutionProject) => {
    Modal.confirm({
      title: '申请退出该机构项目？',
      content: '机构审核通过后，医生将不再出现在该项目的可预约医生列表中，相关待确认分账提案也会撤回。',
      okText: '提交退出申请',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await api.post('/admin/institution-project-requests', {
          institutionProjectId: project.id,
          requestType: 'LEAVE',
        });
        message.success('退出申请已提交');
        await refresh();
      },
    });
  };

  const withdraw = async (id: string) => {
    try {
      await api.post(`/admin/institution-project-requests/${id}/withdraw`);
      message.success('申请已撤回');
      await refresh();
    } catch (error) {
      message.error(getApiErrorMessage(error, '申请撤回失败'));
    }
  };

  const openReview = (record: ProjectRequest, decision: 'APPROVED' | 'REJECTED') => {
    setReviewing(record);
    setReviewDecision(decision);
    reviewForm.resetFields();
    setReviewOpen(true);
  };

  const submitReview = async () => {
    if (!reviewing) return;
    try {
      const values = await reviewForm.validateFields();
      setSubmitting(true);
      await api.post(`/admin/institution-project-requests/${reviewing.id}/review`, {
        decision: reviewDecision,
        reviewNote: values.reviewNote || '',
      });
      message.success(reviewDecision === 'APPROVED' ? '申请已通过并生效' : '申请已驳回');
      setReviewOpen(false);
      await refresh();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '审核失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const projectColumns = [
    { title: '机构', dataIndex: 'institutionId', width: 180, render: (id: string) => institutionNames[id] || id },
    { title: '项目', dataIndex: 'effectiveName', width: 220 },
    { title: '机构价格', dataIndex: 'price', width: 100, render: (value: number) => `¥${value}` },
    { title: '状态', dataIndex: 'isActive', width: 90, render: (active: boolean) => <Tag color={active ? 'green' : 'default'}>{active ? '已上架' : '已下架'}</Tag> },
    {
      title: '我的参与状态', width: 130,
      render: (_: unknown, record: InstitutionProject) => {
        const joined = record.doctors?.some(item => item.id === doctorId);
        const pending = pendingByProject.get(record.id);
        return pending ? <Tag color="orange">{requestTypeText[pending.requestType]}审核中</Tag> : <Tag color={joined ? 'green' : 'default'}>{joined ? '已加入' : '未加入'}</Tag>;
      },
    },
    {
      title: '操作', width: 260,
      render: (_: unknown, record: InstitutionProject) => {
        const joined = record.doctors?.some(item => item.id === doctorId);
        const pending = pendingByProject.has(record.id);
        if (pending) return <Typography.Text type="secondary">等待机构处理</Typography.Text>;
        return joined ? <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openRequest(record, 'PROFILE_UPDATE')}>修改我的资料</Button>
          <Button size="small" danger icon={<LogoutOutlined />} onClick={() => requestLeave(record)}>申请退出</Button>
        </Space> : <Button size="small" type="primary" icon={<LoginOutlined />} disabled={!record.isActive} onClick={() => openRequest(record, 'JOIN')}>申请加入</Button>;
      },
    },
  ];

  const requestColumns = [
    { title: '医生', dataIndex: 'doctorName', width: 120 },
    { title: '机构', dataIndex: 'institutionName', width: 180 },
    { title: '项目', dataIndex: 'projectName', width: 180 },
    { title: '申请类型', dataIndex: 'requestType', width: 110, render: (value: ProjectRequest['requestType']) => requestTypeText[value] },
    { title: '排班说明', dataIndex: 'scheduleNote', width: 180, ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: ProjectRequest['status']) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: '审核说明', dataIndex: 'reviewNote', width: 200, ellipsis: true },
    {
      title: '操作', width: 190,
      render: (_: unknown, record: ProjectRequest) => record.status === 'PENDING' ? <Space>
        {(context?.platformRole === 'ADMIN' || context?.managedInstitutionIds.includes(record.institutionId)) && <>
          <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => openReview(record, 'APPROVED')}>通过</Button>
          <Button size="small" danger icon={<StopOutlined />} onClick={() => openReview(record, 'REJECTED')}>驳回</Button>
        </>}
        {record.submittedBy === context?.userId && <Button size="small" onClick={() => withdraw(record.id)}>撤回</Button>}
      </Space> : null,
    },
  ];

  const tabItems = [] as any[];
  if (doctorId) tabItems.push({
    key: 'projects',
    label: '可参与项目',
    children: <>
      <Alert style={{ marginBottom: 16 }} showIcon type="info" message="医生可以申请加入、退出或修改个人服务资料；项目价格、上下架和机构承诺由机构法人管理。" />
      <Table rowKey="id" dataSource={projects} columns={projectColumns} loading={loading} size="small" scroll={{ x: 'max-content' }} />
    </>,
  });
  tabItems.push({
    key: 'requests',
    label: canReview ? '申请审核' : '我的申请',
    children: <Table rowKey="id" dataSource={requests} columns={requestColumns} loading={loading} size="small" scroll={{ x: 'max-content' }} />,
  });

  return <div>
    <h2>项目协作</h2>
    <Tabs items={tabItems} />

    <Modal
      title={requestType === 'JOIN' ? '申请加入机构项目' : '申请修改个人项目资料'}
      open={requestOpen}
      onOk={submitRequest}
      onCancel={() => setRequestOpen(false)}
      confirmLoading={submitting}
      okText="提交机构审核"
      width={680}
      destroyOnHidden
    >
      <Alert style={{ marginBottom: 16 }} type="warning" showIcon message="以下内容仅代表医生在该项目中的个人服务资料，不会修改机构价格、销量、评分或上下架状态。" />
      <Form form={requestForm} layout="vertical">
        <Form.Item name="serviceDescription" label="个人服务介绍" rules={[{ max: 5000 }]}><Input.TextArea rows={4} /></Form.Item>
        <Form.Item name="serviceTags" label="个人擅长标签" rules={[{ max: 500 }]}><Input placeholder="多个标签使用英文逗号分隔" /></Form.Item>
        <Form.Item name="scheduleNote" label="出诊与排班说明" rules={[{ max: 500 }]}><Input.TextArea rows={3} /></Form.Item>
        <Form.Item name="coverImage" label="个人项目封面"><ImageUpload folder="doctor-project-profiles" /></Form.Item>
        <Form.Item name="images" label="个人案例图集"><MultiImageUpload folder="doctor-project-profiles" /></Form.Item>
      </Form>
    </Modal>

    <Modal
      title={reviewDecision === 'APPROVED' ? '通过项目申请' : '驳回项目申请'}
      open={reviewOpen}
      onOk={submitReview}
      onCancel={() => setReviewOpen(false)}
      confirmLoading={submitting}
      okText={reviewDecision === 'APPROVED' ? '确认通过' : '确认驳回'}
      okButtonProps={{ danger: reviewDecision === 'REJECTED' }}
      destroyOnHidden
    >
      <Form form={reviewForm} layout="vertical">
        <Form.Item
          name="reviewNote"
          label="审核说明"
          rules={reviewDecision === 'REJECTED' ? [{ required: true, message: '请填写驳回原因' }, { max: 1000 }] : [{ max: 1000 }]}
        >
          <Input.TextArea rows={4} placeholder={reviewDecision === 'APPROVED' ? '可填写内部审核备注' : '请说明需要修改的内容'} />
        </Form.Item>
      </Form>
    </Modal>
  </div>;
}
