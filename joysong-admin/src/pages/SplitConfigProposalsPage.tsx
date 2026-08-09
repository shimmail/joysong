import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Form, Input, InputNumber, message, Modal, Select, Space, Table, Tag,
} from 'antd';
import { CheckOutlined, PlusOutlined, StopOutlined, UndoOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData, getManagementContext } from '../api';
import { SplitRateFields } from '../components/SplitRateFields';
import { useOrderSplitPolicy } from '../hooks/useOrderSplitPolicy';
import { calculateDoctorRate } from '../utils/splitRates';

interface Institution { id: string; name: string }
interface Doctor { id: string; name: string; institutions?: Institution[]; institutionId?: string }
interface InstitutionProject { id: string; institutionId: string; effectiveName: string; doctors: Doctor[] }
interface ActiveConfig {
  id: string; doctorId: string; institutionProjectId: string;
  consultationFee: number; commissionRate: number; institutionRate: number;
}
interface Proposal extends ActiveConfig {
  configId?: string;
  doctorName: string;
  institutionId: string;
  institutionName: string;
  projectName: string;
  proposerUserId: string;
  proposerName: string;
  proposerSide: 'DOCTOR' | 'INSTITUTION';
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  doctorConfirmedAt?: string;
  institutionConfirmedAt?: string;
  decisionNote?: string;
  submittedAt: string;
}

const statusColor: Record<Proposal['status'], string> = {
  PENDING: 'orange', APPROVED: 'green', REJECTED: 'red', WITHDRAWN: 'default',
};

export default function SplitConfigProposalsPage() {
  const policyState = useOrderSplitPolicy();
  const context = getManagementContext();
  const isAdmin = context?.platformRole === 'ADMIN';
  const [configs, setConfigs] = useState<ActiveConfig[]>([]);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [institutions, setInstitutions] = useState<Institution[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [projects, setProjects] = useState<InstitutionProject[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [selectedInstitutionId, setSelectedInstitutionId] = useState<string>();
  const [selectedDoctorId, setSelectedDoctorId] = useState<string>();
  const [rejecting, setRejecting] = useState<Proposal | null>(null);
  const [form] = Form.useForm();
  const [rejectForm] = Form.useForm();

  const refresh = async () => {
    setLoading(true);
    try {
      const [configRes, proposalRes, institutionRes, doctorRes, projectRes] = await Promise.all([
        api.get('/admin/doctor-institution-project-configs'),
        api.get('/admin/doctor-institution-project-config-proposals'),
        api.get('/admin/institutions'),
        api.get('/admin/doctors'),
        api.get('/admin/institution-projects'),
      ]);
      setConfigs(getData<ActiveConfig[]>(configRes as any) || []);
      setProposals(getData<Proposal[]>(proposalRes as any) || []);
      setInstitutions(getData<Institution[]>(institutionRes as any) || []);
      setDoctors(getData<Doctor[]>(doctorRes as any) || []);
      setProjects(getData<InstitutionProject[]>(projectRes as any) || []);
    } catch (error) {
      message.error(getApiErrorMessage(error, '分账协商数据加载失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const projectMap = useMemo(() => new Map(projects.map(item => [item.id, item])), [projects]);
  const doctorMap = useMemo(() => new Map(doctors.map(item => [item.id, item.name])), [doctors]);
  const institutionMap = useMemo(() => new Map(institutions.map(item => [item.id, item.name])), [institutions]);
  const filteredDoctors = doctors.filter(doctor => {
    if (context?.doctorId && doctor.id !== context.doctorId && !isAdmin) return false;
    if (!selectedInstitutionId) return true;
    return doctor.institutionId === selectedInstitutionId || doctor.institutions?.some(item => item.id === selectedInstitutionId);
  });
  const filteredProjects = projects.filter(project =>
    (!selectedInstitutionId || project.institutionId === selectedInstitutionId) &&
    (!selectedDoctorId || project.doctors?.some(doctor => doctor.id === selectedDoctorId)),
  );

  const openCreate = () => {
    const ownDoctorId = context?.doctorId;
    setSelectedDoctorId(ownDoctorId);
    setSelectedInstitutionId(undefined);
    form.resetFields();
    form.setFieldsValue({ doctorId: ownDoctorId, consultationFee: 0, commissionRate: 0, institutionRate: 40 });
    setFormOpen(true);
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      const doctorSide = values.doctorId === context?.doctorId;
      const institutionSide = context?.managedInstitutionIds.includes(values.institutionId);
      const proposerSide = doctorSide && institutionSide ? values.proposerSide : doctorSide ? 'DOCTOR' : 'INSTITUTION';
      setSubmitting(true);
      await api.post('/admin/doctor-institution-project-config-proposals', {
        doctorId: values.doctorId,
        institutionProjectId: values.institutionProjectId,
        consultationFee: values.consultationFee,
        commissionRate: values.commissionRate,
        institutionRate: values.institutionRate,
        proposerSide,
      });
      message.success('分账提案已提交，双方确认前不会覆盖当前生效配置');
      setFormOpen(false);
      await refresh();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '分账提案提交失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const confirm = async (proposal: Proposal, side?: 'DOCTOR' | 'INSTITUTION') => {
    try {
      await api.post(`/admin/doctor-institution-project-config-proposals/${proposal.id}/confirm`, { side });
      message.success('确认成功；双方均确认后自动更新生效配置');
      await refresh();
    } catch (error) {
      message.error(getApiErrorMessage(error, '确认失败'));
    }
  };

  const reject = async () => {
    if (!rejecting) return;
    try {
      const values = await rejectForm.validateFields();
      setSubmitting(true);
      await api.post(`/admin/doctor-institution-project-config-proposals/${rejecting.id}/reject`, { note: values.note });
      message.success('分账提案已拒绝');
      setRejecting(null);
      await refresh();
    } catch (error: any) {
      if (!error?.errorFields) message.error(getApiErrorMessage(error, '拒绝提案失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const withdraw = async (proposal: Proposal) => {
    try {
      await api.post(`/admin/doctor-institution-project-config-proposals/${proposal.id}/withdraw`);
      message.success('分账提案已撤回');
      await refresh();
    } catch (error) {
      message.error(getApiErrorMessage(error, '撤回失败'));
    }
  };

  const renderDoctorRate = (item: Pick<ActiveConfig, 'institutionRate' | 'commissionRate'>) => {
    const doctorRate = calculateDoctorRate(
      policyState.policy?.platformRate,
      item.institutionRate,
      item.commissionRate,
    );
    if (doctorRate == null) return '-';
    return doctorRate < 0 ? <Tag color="red">配置无效</Tag> : `${doctorRate}%`;
  };

  const activeColumns = [
    { title: '机构', width: 180, render: (_: unknown, item: ActiveConfig) => institutionMap.get(projectMap.get(item.institutionProjectId)?.institutionId || '') || '-' },
    { title: '医生', dataIndex: 'doctorId', width: 130, render: (id: string) => doctorMap.get(id) || id },
    { title: '项目', dataIndex: 'institutionProjectId', width: 190, render: (id: string) => projectMap.get(id)?.effectiveName || id },
    { title: '面诊金', dataIndex: 'consultationFee', width: 100, render: (value: number) => `$${value}` },
    { title: '医美顾问分账比例', dataIndex: 'commissionRate', width: 140, render: (value: number) => `${value}%` },
    { title: '机构比例', dataIndex: 'institutionRate', width: 100, render: (value: number) => `${value}%` },
    { title: '医生分账比例', width: 120, render: (_: unknown, item: ActiveConfig) => renderDoctorRate(item) },
  ];

  const proposalColumns = [
    { title: '机构', dataIndex: 'institutionName', width: 170 },
    { title: '医生', dataIndex: 'doctorName', width: 120 },
    { title: '项目', dataIndex: 'projectName', width: 180 },
    { title: '面诊金', dataIndex: 'consultationFee', width: 90, render: (value: number) => `$${value}` },
    { title: '医美顾问分账比例', dataIndex: 'commissionRate', width: 140, render: (value: number) => `${value}%` },
    { title: '机构比例', dataIndex: 'institutionRate', width: 100, render: (value: number) => `${value}%` },
    { title: '医生分账比例', width: 120, render: (_: unknown, item: Proposal) => renderDoctorRate(item) },
    { title: '发起方', dataIndex: 'proposerSide', width: 90, render: (side: string) => side === 'DOCTOR' ? '医生' : '机构' },
    {
      title: '确认进度', width: 180,
      render: (_: unknown, item: Proposal) => <Space>
        <Tag color={item.doctorConfirmedAt ? 'green' : 'default'}>医生{item.doctorConfirmedAt ? '已确认' : '待确认'}</Tag>
        <Tag color={item.institutionConfirmedAt ? 'green' : 'default'}>机构{item.institutionConfirmedAt ? '已确认' : '待确认'}</Tag>
      </Space>,
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: Proposal['status']) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: '处理说明', dataIndex: 'decisionNote', width: 180, ellipsis: true },
    {
      title: '操作', width: 260,
      render: (_: unknown, item: Proposal) => item.status === 'PENDING' ? <Space wrap>
        {isAdmin && <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => confirm(item)}>管理员确认生效</Button>}
        {!isAdmin && context?.doctorId === item.doctorId && !item.doctorConfirmedAt && <Button size="small" type="primary" onClick={() => confirm(item, 'DOCTOR')}>医生确认</Button>}
        {!isAdmin && context?.managedInstitutionIds.includes(item.institutionId) && !item.institutionConfirmedAt && <Button size="small" type="primary" onClick={() => confirm(item, 'INSTITUTION')}>机构确认</Button>}
        {(isAdmin || item.proposerUserId !== context?.userId) && <Button size="small" danger icon={<StopOutlined />} onClick={() => { rejectForm.resetFields(); setRejecting(item); }}>拒绝</Button>}
        {(isAdmin || item.proposerUserId === context?.userId) && <Button size="small" icon={<UndoOutlined />} onClick={() => withdraw(item)}>撤回</Button>}
      </Space> : null,
    },
  ];

  const isDualSide = Boolean(
    selectedDoctorId && selectedDoctorId === context?.doctorId &&
    selectedInstitutionId && context?.managedInstitutionIds.includes(selectedInstitutionId),
  );

  return <div>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>分账协商</h2>
      {!isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>发起分账提案</Button>}
    </div>
    <Alert
      style={{ marginBottom: 16 }}
      showIcon
      type="info"
      message="当前生效配置与协商提案相互独立。医生和机构任一方发起修改后，必须由另一方确认；管理员可在争议或纠错场景下强制处理。"
    />
    <h3>当前生效配置</h3>
    <Table rowKey="id" dataSource={configs} columns={activeColumns} loading={loading} size="small" scroll={{ x: 'max-content' }} pagination={false} />
    <h3 style={{ marginTop: 24 }}>协商记录</h3>
    <Table rowKey="id" dataSource={proposals} columns={proposalColumns} loading={loading} size="small" scroll={{ x: 'max-content' }} />

    <Modal
      title="发起分账提案"
      open={formOpen}
      onOk={submit}
      onCancel={() => setFormOpen(false)}
      confirmLoading={submitting}
      okText="提交提案"
      okButtonProps={{ disabled: policyState.loading || Boolean(policyState.error) || !policyState.policy }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="institutionId" label="机构" rules={[{ required: true, message: '请选择机构' }]}>
          <Select
            options={institutions.map(item => ({ label: item.name, value: item.id }))}
            onChange={value => {
              setSelectedInstitutionId(value);
              setSelectedDoctorId(context?.doctorId);
              form.setFieldsValue({ doctorId: context?.doctorId, institutionProjectId: undefined, proposerSide: undefined });
            }}
          />
        </Form.Item>
        <Form.Item name="doctorId" label="医生" rules={[{ required: true, message: '请选择医生' }]}>
          <Select
            disabled={Boolean(context?.doctorId && !isAdmin)}
            options={filteredDoctors.map(item => ({ label: item.name, value: item.id }))}
            onChange={value => { setSelectedDoctorId(value); form.setFieldsValue({ institutionProjectId: undefined }); }}
          />
        </Form.Item>
        <Form.Item name="institutionProjectId" label="机构项目" rules={[{ required: true, message: '请选择医生已加入的机构项目' }]}>
          <Select options={filteredProjects.map(item => ({ label: item.effectiveName, value: item.id }))} />
        </Form.Item>
        {isDualSide && <Form.Item name="proposerSide" label="本次代表哪一方" rules={[{ required: true }]}>
          <Select options={[{ label: '医生方', value: 'DOCTOR' }, { label: '机构方', value: 'INSTITUTION' }]} />
        </Form.Item>}
        <Form.Item name="consultationFee" label="面诊金" rules={[{ required: true }]}><InputNumber min={0} precision={2} prefix="$" style={{ width: '100%' }} /></Form.Item>
        <SplitRateFields form={form} {...policyState} />
      </Form>
    </Modal>

    <Modal title="拒绝分账提案" open={Boolean(rejecting)} onOk={reject} onCancel={() => setRejecting(null)} confirmLoading={submitting} okText="确认拒绝" okButtonProps={{ danger: true }} destroyOnHidden>
      <Form form={rejectForm} layout="vertical">
        <Form.Item name="note" label="拒绝原因" rules={[{ required: true, message: '请填写拒绝原因' }, { max: 1000 }]}>
          <Input.TextArea rows={4} />
        </Form.Item>
      </Form>
    </Modal>
  </div>;
}
