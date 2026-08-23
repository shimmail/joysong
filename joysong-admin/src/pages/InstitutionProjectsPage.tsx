import { useEffect, useState } from 'react';
import {
  Table, Button, Modal, Form, Input, InputNumber, Select, Switch, Space,
  message, Popconfirm, Tag, Alert, Divider, Typography
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import api, { getData, getManagementContext } from '../api';
import ImageUpload from '../components/ImageUpload';
import MultiImageUpload from '../components/MultiImageUpload';
import RichTextEditor from '../components/RichTextEditor';
import { useSearchParams } from 'react-router-dom';
import { useOrderSplitPolicy } from '../hooks/useOrderSplitPolicy';
import { calculatePercentageFeeMinor, formatMoney } from '../utils/money';

interface Institution { id: string; name: string }
interface Project {
  id: string; name: string; category?: string; description?: string; rating?: number;
  reviewCount?: number; tags?: string; slogan?: string; detailContent?: string;
  coverImage?: string; images?: string;
}
interface Doctor {
  id: string;
  name: string;
  title: string;
  /** 兼容旧接口：医生的主展示机构。 */
  institutionId: string;
  /** 医生全部出诊机构；机构项目配置必须以此为准。 */
  institutions?: { id: string; name: string }[];
  price?: number;
}
interface InstitutionProjectRecord {
  id: string; institutionId: string; projectId: string; projectName: string; baseProjectName: string;
  name?: string | null; category?: string | null; description?: string | null; rating?: number | null;
  reviewCount?: number | null; tags?: string | null; slogan?: string | null; detailContent?: string | null;
  effectiveName: string; effectiveCategory: string; effectiveDescription: string; effectiveRating: number;
  effectiveReviewCount: number; effectiveTags: string; effectiveSlogan: string; effectiveDetailContent?: string | null;
  price: number; originalPrice?: number | null; coverImage: string; images: string;
  effectiveCoverImage: string; effectiveImages: string; salesCount: number; isActive: boolean;
  doctors: Doctor[];
}

export default function InstitutionProjectsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const [searchParams] = useSearchParams();
  const [data, setData] = useState<InstitutionProjectRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [institutions, setInstitutions] = useState<Institution[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [filterProjectId, setFilterProjectId] = useState<string | undefined>();
  const [filterInstitutionId, setFilterInstitutionId] = useState<string | undefined>(searchParams.get('institutionId') || undefined);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [selectedInstitutionId, setSelectedInstitutionId] = useState<string | undefined>();
  const policyState = useOrderSplitPolicy();
  const selectedProjectId = Form.useWatch('projectId', form);
  const selectedProject = projects.find(p => p.id === selectedProjectId);

  const fetchData = () => {
    setLoading(true);
    const params = new URLSearchParams();
    if (filterProjectId) params.append('projectId', filterProjectId);
    if (filterInstitutionId) params.append('institutionId', filterInstitutionId);
    const query = params.toString();
    api.get(`/admin/institution-projects${query ? `?${query}` : ''}`)
      .then(res => setData(getData(res as any)))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [filterProjectId, filterInstitutionId]);

  useEffect(() => {
    api.get('/admin/institutions').then(res => setInstitutions(getData<Institution[]>(res as any)));
    api.get('/admin/projects').then(res => setProjects(getData<Project[]>(res as any)));
    api.get('/admin/doctors').then(res => setDoctors(getData<Doctor[]>(res as any)));
  }, []);

  const institutionOptions = institutions.map(i => ({ label: i.name, value: i.id }));
  const projectOptions = projects.map(p => ({ label: p.name, value: p.id }));
  const filteredDoctorOptions = doctors
      .filter(d => !selectedInstitutionId ||
        d.institutions?.some(institution => institution.id === selectedInstitutionId) ||
        d.institutionId === selectedInstitutionId
      )
      .map(d => ({ label: `${d.name}${d.title ? `（${d.title}）` : ''}`, value: d.id }));

  const handleAdd = () => {
    setEditingId(null);
    setSelectedInstitutionId(undefined);
    form.resetFields();
    form.setFieldsValue({ price: 0, originalPrice: null, salesCount: 0, isActive: true, doctorBindings: [] });
    setModalOpen(true);
  };

  const handleEdit = (record: InstitutionProjectRecord) => {
    setEditingId(record.id);
    setSelectedInstitutionId(record.institutionId);
    form.setFieldsValue({
      ...record,
      originalPrice: record.originalPrice ?? null,
      doctorBindings: record.doctors?.map((doctor) => ({ doctorId: doctor.id, price: doctor.price })) || [],
    });
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await api.delete(`/admin/institution-projects/${id}`);
      message.success('删除成功');
      fetchData();
    } catch (err: any) {
      message.error('删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`/admin/institution-projects/${id}`)));
      message.success(`成功删除 ${selectedRowKeys.length} 条记录`);
      setSelectedRowKeys([]);
      fetchData();
    } catch (err: any) {
      message.error('批量删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      if (!policyState.policy) {
        message.error('分账策略不可用，暂不能保存');
        return;
      }
      const { doctorBindings = [], ...rest } = values;
      const payload = {
        ...rest,
        // InputNumber 清空后可能返回 undefined；显式提交 null 才能清除已有原价。
        originalPrice: rest.originalPrice ?? null,
        doctorBindings,
      };
      if (editingId) {
        payload.id = editingId;
        await api.put(`/admin/institution-projects/${editingId}`, payload);
        message.success('更新成功');
      } else {
        await api.post('/admin/institution-projects', payload);
        message.success('创建成功');
      }
      setModalOpen(false);
      fetchData();
    } catch (err: any) {
      if (err?.errorFields) return;
      const msg = err?.response?.data?.message || err?.message || '操作失败';
      message.error(msg);
    }
  };

  const getInstitutionName = (id: string) => institutions.find(i => i.id === id)?.name || id;
  const getProjectName = (id: string) => projects.find(p => p.id === id)?.name || id;

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '机构', dataIndex: 'institutionId', width: 150, render: (v: string) => getInstitutionName(v) },
    { title: '机构项目名称', dataIndex: 'effectiveName', width: 180, render: (v: string, record: InstitutionProjectRecord) => <Space><span>{v}</span>{record.name ? <Tag color="pink">自定义</Tag> : <Tag>继承</Tag>}</Space> },
    { title: '公共项目', dataIndex: 'projectId', width: 150, render: (v: string) => getProjectName(v) },
    { title: '分类', dataIndex: 'effectiveCategory', width: 110 },
    { title: '价格', dataIndex: 'price', width: 90 },
    { title: '原价', dataIndex: 'originalPrice', width: 90, render: (v: number | null | undefined) => v ?? '—' },
    { title: '销量', dataIndex: 'salesCount', width: 70 },
    { title: '上架', dataIndex: 'isActive', width: 70, render: (v: boolean) => v ? '是' : '否' },
    {
      title: '关联医生', dataIndex: 'doctors', width: 280,
      render: (recordDoctors: Doctor[]) => recordDoctors?.length ? recordDoctors.map((doctor) => {
        const feeMinor = calculatePercentageFeeMinor(doctor.price, policyState.policy?.platformRate);
        return <Tag key={doctor.id}>{doctor.name} · USD {doctor.price?.toFixed(2) ?? '-'} · 旅游地接服务费 {feeMinor == null ? '-' : formatMoney(feeMinor, 'USD')}</Tag>;
      }) : '-',
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: unknown, record: InstitutionProjectRecord) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => handleEdit(record)} title="编辑" />
          {isAdmin && <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
          </Popconfirm>}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>机构项目管理</h2>
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} style={{ background: '#E8577B' }}>新增机构项目</Button>
          {isAdmin && <Popconfirm title={`确定删除选中的 ${selectedRowKeys.length} 条记录吗？`} onConfirm={handleBatchDelete} disabled={selectedRowKeys.length === 0}>
            <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
              批量删除{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
            </Button>
          </Popconfirm>}
        </Space>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Select
          style={{ width: 200 }}
          placeholder="按项目筛选"
          options={projectOptions}
          showSearch
          allowClear
          value={filterProjectId}
          onChange={v => setFilterProjectId(v)}
        />
        <Select
          style={{ width: 200 }}
          placeholder="按机构筛选"
          options={institutionOptions}
          showSearch
          allowClear
          value={filterInstitutionId}
          onChange={v => setFilterInstitutionId(v)}
        />
      </Space>
      <Table dataSource={data} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }}
        rowSelection={isAdmin ? {
          selectedRowKeys,
          onChange: keys => setSelectedRowKeys(keys),
        } : undefined}
      />
      <Modal title={editingId ? '编辑机构项目' : '新增机构项目'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={900} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical" style={{ marginTop: 16, maxHeight: '65vh', overflowY: 'auto', paddingRight: 8 }}>
          <Form.Item name="institutionId" label="所属机构" rules={[{ required: true }]}>
            <Select disabled={!!editingId} options={institutionOptions} showSearch placeholder="请选择机构" onChange={(v) => { setSelectedInstitutionId(v); form.setFieldsValue({ doctorBindings: [] }); }} />
          </Form.Item>
          <Form.Item name="projectId" label="关联项目" rules={[{ required: true }]}>
            <Select disabled={!!editingId} options={projectOptions} showSearch placeholder="请选择项目" />
          </Form.Item>
          <Alert type="info" showIcon message="以下详情均为可选项；留空时自动展示关联公共项目的对应内容。" />
          <Divider titlePlacement="start">机构项目独立详情</Divider>
          <Space style={{ marginBottom: 16 }}>
            <Button onClick={() => form.setFieldsValue({ name: null, category: null, description: null, rating: null, reviewCount: null, tags: null, slogan: null, detailContent: null })}>全部恢复继承</Button>
            {selectedProject && <Typography.Text type="secondary">当前继承来源：{selectedProject.name}</Typography.Text>}
          </Space>
          <Form.Item name="name" label={`独立名称${selectedProject?.name ? `（留空继承：${selectedProject.name}）` : ''}`} rules={[{ max: 200 }]}>
            <Input allowClear placeholder="留空继承公共项目名称" />
          </Form.Item>
          <Form.Item name="category" label={`独立分类${selectedProject?.category ? `（留空继承：${selectedProject.category}）` : ''}`} rules={[{ max: 100 }]}>
            <Input allowClear placeholder="留空继承公共项目分类" />
          </Form.Item>
          <Form.Item name="description" label="独立简介" rules={[{ max: 5000 }]} extra={selectedProject?.description ? `继承内容：${selectedProject.description}` : undefined}>
            <Input.TextArea allowClear rows={4} placeholder="留空继承公共项目简介" />
          </Form.Item>
          {isAdmin && <Space size="large" style={{ display: 'flex' }} align="start">
            <Form.Item name="rating" label={`评分${selectedProject?.rating != null ? `（继承：${selectedProject.rating}）` : ''}`}>
              <InputNumber min={0} max={5} step={0.1} precision={1} placeholder="继承" />
            </Form.Item>
            <Form.Item name="reviewCount" label={`评价数${selectedProject?.reviewCount != null ? `（继承：${selectedProject.reviewCount}）` : ''}`}>
              <InputNumber min={0} precision={0} placeholder="继承" />
            </Form.Item>
          </Space>}
          <Form.Item name="tags" label={`标签${selectedProject?.tags ? `（继承：${selectedProject.tags}）` : ''}`} rules={[{ max: 500 }]}>
            <Input allowClear placeholder="多个标签使用英文逗号分隔；留空继承" />
          </Form.Item>
          <Form.Item name="slogan" label={`宣传语${selectedProject?.slogan ? `（继承：${selectedProject.slogan}）` : ''}`} rules={[{ max: 500 }]}>
            <Input allowClear placeholder="留空继承公共项目宣传语" />
          </Form.Item>
          <Form.Item name="detailContent" label="独立详情正文" extra="清空正文后恢复使用公共项目详情">
            <RichTextEditor />
          </Form.Item>
          <Divider titlePlacement="start">价格、图片与服务配置</Divider>
          <Form.Item name="price" label="价格" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={0} /></Form.Item>
          <Form.Item name="originalPrice" label="原价" extra="可留空；留空后客户端不显示划线原价">
            <InputNumber style={{ width: '100%' }} min={0} placeholder="留空表示无原价" />
          </Form.Item>
          <Form.Item name="coverImage" label="封面图"><ImageUpload folder="projects" recommendedSize="1200 × 800 px（3:2）" /></Form.Item>
          <Form.Item name="images" label="图集"><MultiImageUpload folder="projects" /></Form.Item>
          {isAdmin && <Form.Item name="salesCount" label="销量"><InputNumber style={{ width: '100%' }} min={0} /></Form.Item>}
          <Form.Item name="isActive" label="是否上架" valuePropName="checked"><Switch /></Form.Item>
          <Divider titlePlacement="start">医生项目价格</Divider>
          <Form.List
            name="doctorBindings"
            rules={[
              {
                validator: async (_, bindings: Array<{ doctorId?: string }> = []) => {
                  const doctorIds = bindings.map(binding => binding?.doctorId).filter(Boolean);
                  if (new Set(doctorIds).size !== doctorIds.length) throw new Error('同一医生只能配置一次');
                },
              },
            ]}
          >
            {(fields, { add, remove }, { errors }) => <>
              {fields.map((field) => {
                const { key, ...fieldProps } = field;
                return <Space key={key} align="start" style={{ display: 'flex' }}>
                  <Form.Item {...fieldProps} name={[field.name, 'doctorId']} label="医生" rules={[{ required: true, message: '请选择医生' }]}>
                    <Select style={{ width: 260 }} options={filteredDoctorOptions} showSearch placeholder="请选择可执行该机构项目的医生" />
                  </Form.Item>
                  <Form.Item
                    {...fieldProps}
                    name={[field.name, 'price']}
                    label="医生项目价格（USD）"
                    rules={[
                      { required: true, message: '请输入医生项目价格' },
                      {
                        validator: (_, value) => calculatePercentageFeeMinor(value, policyState.policy?.platformRate) != null
                          ? Promise.resolve()
                          : Promise.reject(new Error(policyState.policy ? '医生项目价格必须大于 0，且旅游地接服务费至少为 USD 0.01' : '分账策略不可用，暂不能保存')),
                      },
                    ]}
                  >
                    <InputNumber min={0} max={99_999_999.99} precision={2} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item label="旅游地接服务费">
                    <Form.Item noStyle shouldUpdate>
                      {() => {
                        const feeMinor = calculatePercentageFeeMinor(form.getFieldValue(['doctorBindings', field.name, 'price']), policyState.policy?.platformRate);
                        return <Typography.Text>{feeMinor == null ? '-' : formatMoney(feeMinor, 'USD')}</Typography.Text>;
                      }}
                    </Form.Item>
                  </Form.Item>
                  <Button danger onClick={() => remove(field.name)}>删除</Button>
                </Space>;
              })}
              <Form.ErrorList errors={errors} />
              <Button onClick={() => add()} type="dashed">添加医生</Button>
            </>}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
}
