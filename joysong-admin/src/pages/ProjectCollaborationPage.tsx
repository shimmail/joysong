import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Divider, Form, Input, InputNumber, message, Modal, Space, Switch, Table, Tabs, Tag, Typography,
} from 'antd';
import { EditOutlined, LoginOutlined, LogoutOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import ImageUpload from '../components/ImageUpload';
import MultiImageUpload from '../components/MultiImageUpload';
import RichTextEditor from '../components/RichTextEditor';
import {
  parseDoctorProjectChangeTarget,
  parseDoctorProjectChangeRequests,
  type DoctorProjectChangeTargetV2,
  type ParsedDoctorProjectChangeRequest,
} from '../types/projectRequests';
import { calculatePercentageFeeMinor } from '../utils/money';

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

const requestTypeText: Record<string, string> = {
  JOIN: '申请加入',
  PROFILE_UPDATE: '资料变更',
  LEAVE: '申请退出',
};

const statusColor: Record<string, string> = {
  PENDING: 'orange', APPROVED: 'green', REJECTED: 'red', CHANGES_REQUESTED: 'orange', WITHDRAWN: 'default',
};

export default function ProjectCollaborationPage() {
  const context = getManagementContext();
  const doctorId = context?.doctorId;
  const [projects, setProjects] = useState<InstitutionProject[]>([]);
  const [requests, setRequests] = useState<ParsedDoctorProjectChangeRequest[]>([]);
  const [profileTargets, setProfileTargets] = useState<DoctorProjectChangeTargetV2[]>([]);
  const [institutionNames, setInstitutionNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [requestOpen, setRequestOpen] = useState(false);
  const [requestProject, setRequestProject] = useState<InstitutionProject | null>(null);
  const [profileTarget, setProfileTarget] = useState<DoctorProjectChangeTargetV2 | null>(null);
  const [requestType, setRequestType] = useState<'JOIN' | 'PROFILE_UPDATE'>('JOIN');
  const [submitting, setSubmitting] = useState(false);
  const [requestForm] = Form.useForm();

  const refresh = async () => {
    setLoading(true);
    try {
      const [projectRes, requestRes, institutionRes, profileTargetRes] = await Promise.all([
        api.get('/admin/institution-projects'),
        api.get('/v2/admin/institution-project-requests'),
        api.get('/admin/institutions'),
        doctorId ? api.get('/v2/admin/institution-project-requests/profile-update-targets') : Promise.resolve(null),
      ]);
      setProjects(getData<InstitutionProject[]>(projectRes as any) || []);
      setRequests(parseDoctorProjectChangeRequests(getData<unknown>(requestRes as any)));
      const rawTargets = profileTargetRes ? getData<unknown>(profileTargetRes as any) : [];
      setProfileTargets(Array.isArray(rawTargets)
        ? rawTargets.map(parseDoctorProjectChangeTarget).filter((item): item is DoctorProjectChangeTargetV2 => item !== null)
        : []);
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
    requests.filter(item => item.doctorId === doctorId && requestStatus(item) === 'PENDING')
      .map(item => [item.institutionProjectId, item]),
  ), [requests, doctorId]);

  const openRequest = (project: InstitutionProject, type: 'JOIN' | 'PROFILE_UPDATE') => {
    const profile = project.doctors?.find(item => item.id === doctorId);
    const target = type === 'PROFILE_UPDATE'
      ? profileTargets.find(item => item.institutionProjectId === project.id)
      : null;
    if (type === 'PROFILE_UPDATE' && !target) {
      message.error('无法读取该项目的资料修改基线，请刷新后重试');
      return;
    }
    setRequestProject(project);
    setRequestType(type);
    setProfileTarget(target || null);
    requestForm.resetFields();
    requestForm.setFieldsValue(type === 'PROFILE_UPDATE' ? {
      name: target?.currentProject.rawOverrides.name ?? '',
      category: target?.currentProject.rawOverrides.category ?? '',
      description: target?.currentProject.rawOverrides.description ?? '',
      tags: target?.currentProject.rawOverrides.tags?.join(', ') ?? '',
      slogan: target?.currentProject.rawOverrides.slogan ?? '',
      detailContent: target?.currentProject.rawOverrides.detailContent ?? '',
      coverImage: target?.currentProject.rawOverrides.coverImage ?? '',
      images: target?.currentProject.rawOverrides.images?.join(',') ?? '',
      price: target?.currentDoctorPrice,
      salesCount: target?.currentProject.effective.salesCount,
      doctorActive: target?.currentDoctorActive,
      notes: '',
    } : {
      serviceDescription: profile?.serviceDescription ?? '',
      priceSuggestion: undefined,
      notes: '',
    });
    setRequestOpen(true);
  };

  const submitRequest = async () => {
    if (!requestProject) return;
    try {
      const values = await requestForm.validateFields();
      setSubmitting(true);
      if (requestType === 'PROFILE_UPDATE') {
        if (!profileTarget) throw new Error('资料修改基线不存在');
        await api.post('/v2/admin/institution-project-requests', {
          requestType: 'PROFILE_UPDATE',
          institutionProjectId: profileTarget.institutionProjectId,
          baseRevision: profileTarget.baseRevision,
          name: toNullableText(values.name),
          category: toNullableText(values.category),
          description: toNullableText(values.description),
          tags: toNullableStringList(values.tags),
          slogan: toNullableText(values.slogan),
          detailContent: toNullableRichText(values.detailContent),
          price: values.price,
          salesCount: values.salesCount,
          doctorActive: values.doctorActive,
          coverImage: toNullableText(values.coverImage),
          images: toNullableStringList(values.images),
          notes: values.notes?.trim() || '',
        });
      } else {
        await api.post('/v2/admin/institution-project-requests', {
          institutionProjectId: requestProject.id,
          requestType: 'JOIN',
          serviceDescription: values.serviceDescription || '',
          priceSuggestion: values.priceSuggestion,
          notes: values.notes || '',
        });
      }
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
        await api.post('/v2/admin/institution-project-requests', {
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
      await api.post(`/v2/admin/institution-project-requests/${id}/withdraw`);
      message.success('申请已撤回');
      await refresh();
    } catch (error) {
      message.error(getApiErrorMessage(error, '申请撤回失败'));
    }
  };

  const projectColumns = [
    { title: '机构', dataIndex: 'institutionId', width: 180, render: (id: string) => institutionNames[id] || id },
    { title: '项目', dataIndex: 'effectiveName', width: 220 },
    { title: '机构价格', dataIndex: 'price', width: 100, render: (value: number) => `$${value}` },
    { title: '状态', dataIndex: 'isActive', width: 90, render: (active: boolean) => <Tag color={active ? 'green' : 'default'}>{active ? '已上架' : '已下架'}</Tag> },
    {
      title: '我的参与状态', width: 130,
      render: (_: unknown, record: InstitutionProject) => {
        const joined = record.doctors?.some(item => item.id === doctorId);
        const pending = pendingByProject.get(record.id);
        return pending ? <Tag color="orange">{requestTypeText[pending.requestType] || pending.requestType}审核中</Tag> : <Tag color={joined ? 'green' : 'default'}>{joined ? '已加入' : '未加入'}</Tag>;
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
    { title: '项目', width: 180, render: (_: unknown, record: ParsedDoctorProjectChangeRequest) => requestProjectName(record) },
    { title: '申请类型', dataIndex: 'requestType', width: 110, render: (value: string) => requestTypeText[value] || value },
    { title: '状态', width: 100, render: (_: unknown, record: ParsedDoctorProjectChangeRequest) => {
      const status = requestStatus(record);
      return <Tag color={statusColor[status] || 'default'}>{status}</Tag>;
    } },
    { title: '审核说明', width: 200, ellipsis: true, render: (_: unknown, record: ParsedDoctorProjectChangeRequest) => requestReviewNote(record) },
    {
      title: '操作', width: 90,
      render: (_: unknown, record: ParsedDoctorProjectChangeRequest) => requestStatus(record) === 'PENDING'
        && Boolean(doctorId) && record.doctorId === doctorId
        ? <Button size="small" onClick={() => withdraw(record.id)}>撤回</Button>
        : null,
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
    label: '我的申请',
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
      <Alert style={{ marginBottom: 16 }} type="warning" showIcon message={requestType === 'JOIN'
        ? '加入申请沿用兼容字段；审核通过后才建立医生与机构项目的协作关系。'
        : '本表单可修改机构项目共享资料以及本人的价格和启用状态；关联机构与公共项目保持只读。'} />
      <Form form={requestForm} layout="vertical">
        {requestType === 'JOIN' ? <>
          <Form.Item name="serviceDescription" label="个人服务介绍" rules={[{ max: 5000 }]}><Input.TextArea rows={4} /></Form.Item>
          <Form.Item
            name="priceSuggestion"
            label="项目价格建议（USD）"
            rules={[
              { required: true, message: '请输入价格建议' },
              {
                validator: (_, value) => typeof value === 'number' &&
                  value <= 99_999_999.99 && Number(value.toFixed(2)) === value &&
                  calculatePercentageFeeMinor(value, 40) != null
                  ? Promise.resolve()
                  : Promise.reject(new Error('价格建议必须为 USD 0.02 至 99,999,999.99，且最多两位小数')),
              },
            ]}
          >
            <InputNumber min={0.02} max={99_999_999.99} precision={2} step={0.01} prefix="$" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="notes" label="补充说明" rules={[{ max: 2000 }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </> : <>
          {profileTarget && <Space orientation="vertical" size={2} style={{ width: '100%', marginBottom: 16 }} aria-label="只读项目关联">
            <Typography.Text>关联机构（只读）</Typography.Text>
            <Typography.Text strong>{profileTarget.institutionName}</Typography.Text>
            <Typography.Text>关联公共项目（只读）</Typography.Text>
            <Typography.Text strong>{profileTarget.platformProjectName}</Typography.Text>
          </Space>}
          <Divider titlePlacement="start">机构项目独立详情</Divider>
          <Button
            style={{ marginBottom: 16 }}
            onClick={() => requestForm.setFieldsValue({
              name: null, category: null, description: null, tags: null,
              slogan: null, detailContent: null, coverImage: null, images: null,
            })}
          >
            全部恢复继承
          </Button>
          <Form.Item
            name="name"
            label="独立名称"
            extra={profileTarget ? `当前继承值：${profileTarget.currentProject.effective.name}` : undefined}
            rules={[{ max: 200 }]}
          >
            <Input allowClear placeholder="留空继承公共项目名称" />
          </Form.Item>
          <Form.Item
            name="category"
            label="独立分类"
            extra={profileTarget ? `当前继承值：${profileTarget.currentProject.effective.category}` : undefined}
            rules={[{ max: 100 }]}
          >
            <Input allowClear placeholder="留空继承公共项目分类" />
          </Form.Item>
          <Form.Item name="description" label="独立简介" rules={[{ max: 5000 }]}>
            <Input.TextArea allowClear rows={4} placeholder="留空继承公共项目简介" />
          </Form.Item>
          <Form.Item name="tags" label="标签" rules={[{ max: 2000 }]}>
            <Input allowClear placeholder="多个标签使用英文逗号分隔；留空继承" />
          </Form.Item>
          <Form.Item name="slogan" label="宣传语" rules={[{ max: 500 }]}>
            <Input allowClear placeholder="留空继承公共项目宣传语" />
          </Form.Item>
          <Form.Item name="detailContent" label="独立详情正文" extra="清空正文后恢复使用公共项目详情">
            <RichTextEditor />
          </Form.Item>
          <Divider titlePlacement="start">图片与医生服务配置</Divider>
          <Form.Item name="coverImage" label="个人项目封面"><ImageUpload folder="doctor-project-profiles" recommendedSize="1200 × 800 px（3:2）" /></Form.Item>
          <Form.Item name="images" label="个人案例图集"><MultiImageUpload folder="doctor-project-profiles" /></Form.Item>
          <Form.Item
            name="price"
            label="医生项目价格（USD）"
            rules={[
              { required: true, message: '请输入医生项目价格' },
              {
                validator: (_, value) => calculatePercentageFeeMinor(value, profileTarget?.platformRate) != null
                  ? Promise.resolve()
                  : Promise.reject(new Error('医生项目价格必须大于 0，且旅游地接服务费至少为 USD 0.01')),
              },
            ]}
          >
            <InputNumber min={0.01} max={99_999_999.99} precision={2} prefix="$" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="salesCount" label="销量" rules={[{ required: true, message: '请输入销量' }]}>
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="doctorActive" label="医生项目是否上架" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="当前旅游地接服务费（服务端计算，只读）">
            <Typography.Text>{profileTarget ? `USD ${profileTarget.travelGroundServiceFee.toFixed(2)}` : '-'}</Typography.Text>
          </Form.Item>
          <Form.Item name="notes" label="申请说明" rules={[{ max: 2000 }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </>}
      </Form>
    </Modal>

  </div>;
}

function requestStatus(request: ParsedDoctorProjectChangeRequest): string {
  return request.kind === 'V1' ? request.status : request.requestStatus;
}

function requestProjectName(request: ParsedDoctorProjectChangeRequest): string {
  return request.kind === 'V2' ? request.institutionProjectName : request.projectName;
}

function requestReviewNote(request: ParsedDoctorProjectChangeRequest): string | null | undefined {
  return request.kind === 'DAMAGED' ? request.parseIssue : request.reviewNote;
}

function toStringList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).map(item => item.trim()).filter(Boolean);
  if (typeof value !== 'string') return [];
  return value.split(',').map(item => item.trim()).filter(Boolean);
}

function toNullableText(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized || null;
}

function toNullableRichText(value: unknown): string | null {
  const normalized = toNullableText(value);
  if (normalized === null) return null;
  if (/<(?:img|video|audio|iframe|object|embed|svg|canvas)\b/i.test(normalized)) return normalized;
  const visibleText = normalized
    .replace(/<br\s*\/?>/gi, '')
    .replace(/<[^>]*>/g, '')
    .replace(/&(?:nbsp|#160|#x0*a0);/gi, ' ')
    .replace(/[\s\u00a0\u200b\ufeff]/g, '');
  return visibleText ? normalized : null;
}

function toNullableStringList(value: unknown): string[] | null {
  const normalized = toStringList(value);
  return normalized.length > 0 ? normalized : null;
}
