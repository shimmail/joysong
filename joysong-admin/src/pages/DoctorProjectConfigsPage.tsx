import { useEffect, useState } from 'react';
import {
  Table, Button, Popconfirm, message, Space, Modal, Form,
  InputNumber, Select, Tag,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, InfoCircleOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import { SplitRateFields } from '../components/SplitRateFields';
import { useOrderSplitPolicy } from '../hooks/useOrderSplitPolicy';
import { calculateDoctorRate } from '../utils/splitRates';

export default function DoctorProjectConfigsPage() {
  const policyState = useOrderSplitPolicy();
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  // 创建/编辑弹窗
  const [formVisible, setFormVisible] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [disclaimerVisible, setDisclaimerVisible] = useState(false);

  // 机构、医生和机构项目下拉选项
  const [institutions, setInstitutions] = useState<any[]>([]);
  const [allDoctors, setAllDoctors] = useState<any[]>([]);
  const [selectedInstitutionId, setSelectedInstitutionId] = useState<string | null>(null);
  const [filteredDoctors, setFilteredDoctors] = useState<any[]>([]);
  const [allInstitutionProjects, setAllInstitutionProjects] = useState<any[]>([]);
  const [filteredProjects, setFilteredProjects] = useState<any[]>([]);

  // 用于表格名称映射的全量数据（不受表单级联过滤影响）
  const [allProjectsForMapping, setAllProjectsForMapping] = useState<any[]>([]);

  // ===== 名称映射函数 =====
  const getDoctorName = (doctorId: string) =>
    allDoctors.find(d => d.id === doctorId)?.name || doctorId;

  const getInstitutionName = (institutionId: string) =>
    institutions.find(i => i.id === institutionId)?.name || institutionId;

  const getProjectName = (institutionProjectId: string) => {
    const proj = allProjectsForMapping.find(p => p.id === institutionProjectId);
    return proj?.effectiveName || proj?.projectName || proj?.name || institutionProjectId;
  };

  /** 从机构项目列表中反查 institutionId */
  const getInstitutionIdFromProject = (institutionProjectId: string): string | null => {
    const proj = allProjectsForMapping.find(p => p.id === institutionProjectId);
    return proj?.institutionId || null;
  };

  const fetchData = () => {
    setLoading(true);
    api.get('/admin/doctor-institution-project-configs')
      .then(res => setData(getData<any[]>(res as any) || []))
      .finally(() => setLoading(false));
  };

  const fetchOptions = () => {
    api.get('/admin/institutions').then(res => setInstitutions(getData<any[]>(res as any) || []));
    api.get('/admin/doctors').then(res => setAllDoctors(getData<any[]>(res as any) || []));
    // 加载全量机构项目用于表格名称映射
    api.get('/admin/institution-projects').then(res => setAllProjectsForMapping(getData<any[]>(res as any) || []));
  };

  useEffect(() => { fetchData(); fetchOptions(); }, []);

  const openCreate = () => {
    setEditingRecord(null);
    setSelectedInstitutionId(null);
    setFilteredDoctors([]);
    setFilteredProjects([]);
    setAllInstitutionProjects([]);
    form.resetFields();
    form.setFieldsValue({ institutionRate: 40 });
    setFormVisible(true);
  };

  const openEdit = async (record: any) => {
    setEditingRecord(record);
    form.resetFields();

    // 1. 确定 institutionId：优先从记录直接取，否则通过机构项目反查
    let institutionId = record.institutionId || null;
    if (!institutionId && record.institutionProjectId) {
      // 先确保 allProjectsForMapping 已加载
      let projectMapping = allProjectsForMapping;
      if (!projectMapping || projectMapping.length === 0) {
        try {
          const allProjRes = await api.get('/admin/institution-projects');
          projectMapping = getData<any[]>(allProjRes as any) || [];
          setAllProjectsForMapping(projectMapping);
        } catch {
          // ignore
        }
      }
      const project = projectMapping.find((p: any) => p.id === record.institutionProjectId);
      institutionId = project?.institutionId || null;
    }

    if (!institutionId) {
      message.error('无法获取关联机构信息');
      return;
    }

    // 2. 设置机构选择
    setSelectedInstitutionId(institutionId);

    try {
      // 3. 并行加载该机构的医生列表和机构项目列表
      const [doctorRes, projRes] = await Promise.all([
        api.get('/admin/doctors'),
        api.get(`/admin/institution-projects?institutionId=${institutionId}`),
      ]);

      const allDocs = getData<any[]>(doctorRes as any) || [];
      const filtered = allDocs.filter((d: any) =>
        d.institutionId === institutionId ||
        d.institutions?.some((institution: { id: string }) => institution.id === institutionId) ||
        // 兼容尚未升级的旧接口返回结构。
        d.institutionIds?.includes(institutionId)
      );
      setFilteredDoctors(filtered);

      const projs = getData<any[]>(projRes as any) || [];
      setAllInstitutionProjects(projs);

      // 4. 过滤出关联该医生的机构项目
      if (record.doctorId) {
        const filteredProjs = projs.filter((p: any) =>
          p.doctors?.some((doc: any) => doc.id === record.doctorId)
        );
        setFilteredProjects(filteredProjs);
      } else {
        setFilteredProjects(projs);
      }

      // 5. 先打开弹窗（destroyOnHidden 需要先渲染表单字段），再在下一帧回填表单值
      setFormVisible(true);
      setTimeout(() => {
        form.setFieldsValue({
          institutionId: institutionId,
          doctorId: record.doctorId,
          institutionProjectId: record.institutionProjectId,
          consultationFee: record.consultationFee != null ? Number(record.consultationFee) : undefined,
          commissionRate: record.commissionRate != null ? Number(record.commissionRate) : undefined,
          institutionRate: record.institutionRate != null ? Number(record.institutionRate) : undefined,
        });
      }, 100);
    } catch (err: any) {
      message.error('加载编辑数据失败');
      setFilteredDoctors([]);
      setAllInstitutionProjects([]);
      setFilteredProjects([]);
    }
  };

  const handleFormOk = async () => {
    try {
      const values = await form.validateFields();
      const { institutionId, ...submitValues } = values;
      setSubmitting(true);
      // 后端 POST 接口为 upsert（按 doctorId + institutionProjectId 判重），新增和编辑统一使用 POST
      await api.post('/admin/doctor-institution-project-configs', submitValues);
      message.success(editingRecord ? '更新成功' : '创建成功');
      setFormVisible(false);
      fetchData();
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error('操作失败: ' + (err?.response?.data?.message || err?.message));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    await api.delete(`/admin/doctor-institution-project-configs/${id}`);
    message.success('删除成功');
    fetchData();
  };

  const renderDoctorRate = (record: { institutionRate?: number; commissionRate?: number }) => {
    const doctorRate = calculateDoctorRate(
      policyState.policy?.platformRate,
      record.institutionRate,
      record.commissionRate,
    );
    if (doctorRate == null) return '-';
    return doctorRate < 0 ? <Tag color="red">配置无效</Tag> : `${doctorRate}%`;
  };

  const columns = [
    {
      title: '机构名称', width: 150,
      render: (_: any, record: any) => {
        const instId = record.institutionId || getInstitutionIdFromProject(record.institutionProjectId);
        return instId ? getInstitutionName(instId) : '-';
      },
    },
    {
      title: '医生名称', width: 150,
      render: (_: any, record: any) => record.doctorId ? getDoctorName(record.doctorId) : '-',
    },
    {
      title: '机构项目名称', width: 200,
      render: (_: any, record: any) => record.institutionProjectId ? getProjectName(record.institutionProjectId) : '-',
    },
    {
      title: '面诊金', dataIndex: 'consultationFee', width: 100,
      render: (v: number) => v != null ? `$${v}` : '-',
    },
    {
      title: '医美顾问分账比例', dataIndex: 'commissionRate', width: 140,
      render: (v: number) => v != null ? `${v}%` : '-',
    },
    {
      title: '机构分成比例', dataIndex: 'institutionRate', width: 120,
      render: (v: number) => v != null ? `${v}%` : '-',
    },
    {
      title: '医生分账比例', width: 120,
      render: (_: unknown, record: any) => renderDoctorRate(record),
    },
    {
      title: '操作', key: 'actions', width: 160, fixed: 'right' as const,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确定删除该配置吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Space>
          <h2 style={{ margin: 0 }}>项目分账配置</h2>
          <Button type="link" icon={<InfoCircleOutlined />} onClick={() => setDisclaimerVisible(true)}>
            了解更多
          </Button>
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增配置</Button>
      </div>

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
        title={editingRecord ? '编辑配置' : '新增配置'}
        open={formVisible}
        onOk={handleFormOk}
        onCancel={() => setFormVisible(false)}
        confirmLoading={submitting}
        okText={editingRecord ? '保存修改' : '创建配置'}
        okButtonProps={{ disabled: policyState.loading || Boolean(policyState.error) || !policyState.policy }}
        destroyOnHidden
        width={500}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="institutionId" label="机构" rules={[{ required: true, message: '请选择机构' }]}>
            <Select
              placeholder="请选择机构"
              showSearch
              optionFilterProp="label"
              options={institutions.map((i: any) => ({ label: i.name || i.id, value: i.id }))}
              onChange={(value) => {
                setSelectedInstitutionId(value);
                form.setFieldsValue({ doctorId: undefined, institutionProjectId: undefined });
                setFilteredProjects([]);
                // 前端过滤：只保留关联该机构的医生
                const filtered = allDoctors.filter((d: any) =>
                  d.institutions?.some((institution: { id: string }) => institution.id === value) ||
                  d.institutionIds?.includes(value) || d.institutionId === value
                );
                setFilteredDoctors(filtered);
                api.get(`/admin/institution-projects?institutionId=${value}`)
                  .then(res => {
                    const projects = getData<any[]>(res as any) || [];
                    setAllInstitutionProjects(projects);
                    setFilteredProjects(projects);
                  });
              }}
            />
          </Form.Item>
          <Form.Item name="doctorId" label="医生" rules={[{ required: true, message: '请选择医生' }]}>
            <Select
              placeholder="请先选择机构"
              disabled={!selectedInstitutionId}
              showSearch
              optionFilterProp="label"
              options={filteredDoctors.map((d: any) => ({ label: d.name || d.doctorName || d.id, value: d.id }))}
              onChange={(doctorId) => {
                form.setFieldsValue({ institutionProjectId: undefined });
                const filtered = allInstitutionProjects.filter((p: any) =>
                  p.doctors?.some((doc: any) => doc.id === doctorId)
                );
                setFilteredProjects(filtered);
              }}
            />
          </Form.Item>
          <Form.Item name="institutionProjectId" label="机构项目" rules={[{ required: true, message: '请选择机构项目' }]}>
            <Select
              placeholder="请先选择机构和医生"
              disabled={!selectedInstitutionId}
              showSearch
              optionFilterProp="label"
              options={filteredProjects.map((p: any) => ({
                label: p.effectiveName || p.projectName || p.institutionProjectName || p.id,
                value: p.id,
              }))}
            />
          </Form.Item>
          <Form.Item name="consultationFee" label="面诊金" rules={[{ required: true, message: '请输入面诊金' }]}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} placeholder="请输入面诊金金额" prefix="$" />
          </Form.Item>
          <SplitRateFields form={form} {...policyState} />
        </Form>
      </Modal>

      {/* 免责声明弹窗 */}
      <Modal
        title="平台分账说明与免责声明"
        open={disclaimerVisible}
        onCancel={() => setDisclaimerVisible(false)}
        footer={[<Button key="ok" type="primary" onClick={() => setDisclaimerVisible(false)}>我知道了</Button>]}
        width={600}
      >
        <p>合作医疗机构全责承担项目所含医疗全部耗材成本、项目所含手术执行场地及服务费等一切除项目发布者即医生本人的人力成本外的一切支出。</p>
        <p>该比例及医生与其合作医疗机构之间的其他权责，包括项目执行后出现医疗事故导致的患者后续诊疗和赔偿的全部支出和费用安排等，由医生及其合作医疗机构私下自行协商并确定，与平台无关。</p>
      </Modal>
    </div>
  );
}
