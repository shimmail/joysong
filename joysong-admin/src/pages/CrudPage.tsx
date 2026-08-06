import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Switch, Select, Space, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import ImageUpload from '../components/ImageUpload';
import MultiImageUpload from '../components/MultiImageUpload';

interface Option { label: string; value: string; disabled?: boolean }

interface Field {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'textarea' | 'switch' | 'select' | 'multi-select' | 'image' | 'multi-image';
  options?: Option[];
  required?: boolean;
  folder?: string;
  disabledOnEdit?: boolean;
  help?: string;
  placeholder?: string;
  disabled?: boolean;
}

interface CrudPageProps {
  title: string;
  apiPath: string;
  fields: Field[];
  columns: any[];
  extraActions?: (record: any, refresh: () => void) => React.ReactNode;
  extraHeader?: React.ReactNode;
  queryParams?: Record<string, string>;
  onDataChange?: (data: any[]) => void;
  allowCreate?: boolean;
  allowEdit?: boolean;
  allowDelete?: boolean;
  canEdit?: (record: any) => boolean;
  canDelete?: (record: any) => boolean;
}

export default function CrudPage({
  title, apiPath, fields, columns, extraActions, extraHeader, queryParams, onDataChange,
  allowCreate = true, allowEdit = true, allowDelete = true, canEdit, canDelete,
}: CrudPageProps) {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const fetchData = () => {
    setLoading(true);
    const qs = queryParams
      ? '?' + Object.entries(queryParams).filter(([, v]) => v).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
      : '';
    api.get(apiPath + qs).then(res => {
      const list = getData(res as any);
      setData(list);
      onDataChange?.(list);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [JSON.stringify(queryParams)]);

  const handleAdd = () => {
    setEditingId(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: any) => {
    setEditingId(record.id);
    form.resetFields();
    setModalOpen(true);
    // 确保 Modal 和 Form.Item 挂载后再设值，避免部分字段未回填
    setTimeout(() => {
      form.setFieldsValue({
        ...record,
        institutionIds: record.institutionIds || record.institutions?.map((item: any) => item.id),
        primaryInstitutionId: record.primaryInstitutionId || record.primaryInstitution?.id,
      });
    }, 0);
  };

  const handleDelete = async (id: string) => {
    try {
      await api.delete(`${apiPath}/${id}`);
      message.success('删除成功');
      fetchData();
    } catch (err: any) {
      message.error('删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`${apiPath}/${id}`)));
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
      if (editingId) {
        await api.put(`${apiPath}/${editingId}`, { ...values, id: editingId });
        message.success('更新成功');
      } else {
        await api.post(apiPath, values);
        message.success('创建成功');
      }
      setModalOpen(false);
      fetchData();
    } catch (err: any) {
      if (err?.errorFields) return; // 表单验证失败，antd 会自动显示错误提示
      const msg = err?.response?.data?.message || err?.message || '操作失败';
      message.error(msg);
    }
  };

  const actionColumn = {
    title: '操作',
    key: 'actions',
    fixed: 'right' as const,
    width: 112,
    render: (_: any, record: any) => (
      <Space>
        {allowEdit && (canEdit?.(record) ?? true) && <Button icon={<EditOutlined />} size="small" onClick={() => handleEdit(record)} title="编辑" />}
        {allowDelete && (canDelete?.(record) ?? true) && (
          <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
          </Popconfirm>
        )}
        {extraActions?.(record, fetchData)}
      </Space>
    ),
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{title}</h2>
        <Space>
          {extraHeader}
          {allowCreate && <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} style={{ background: '#E8577B' }}>新增</Button>}
          {allowDelete && (
            <Popconfirm title={`确定删除选中的 ${selectedRowKeys.length} 条记录吗？`} onConfirm={handleBatchDelete} disabled={selectedRowKeys.length === 0}>
              <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
                批量删除{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
              </Button>
            </Popconfirm>
          )}
        </Space>
      </div>
      <Table dataSource={data} columns={[...columns, actionColumn]} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }}
        rowSelection={allowDelete ? {
          selectedRowKeys,
          onChange: keys => setSelectedRowKeys(keys),
        } : undefined}
      />
      <Modal title={editingId ? `编辑${title}` : `新增${title}`} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={640} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical" style={{ marginTop: 16, maxHeight: '65vh', overflowY: 'auto', paddingRight: 8 }}>
          {fields.map(f => (
            <Form.Item key={f.key} name={f.key} label={f.label} extra={f.help} rules={f.required ? [{ required: true }] : []}>
              {f.type === 'textarea' ? <Input.TextArea rows={3} disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} placeholder={f.placeholder} /> :
               f.type === 'number' ? <InputNumber style={{ width: '100%' }} disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} placeholder={f.placeholder} /> :
               f.type === 'switch' ? <Switch disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} /> :
               f.type === 'select' ? <Select options={f.options} showSearch optionFilterProp="label" allowClear style={{ width: '100%' }} disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} placeholder={f.placeholder} /> :
               f.type === 'multi-select' ? <Select mode="multiple" options={f.options} showSearch optionFilterProp="label" allowClear style={{ width: '100%' }} disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} placeholder={f.placeholder} /> :
               f.type === 'image' ? <ImageUpload folder={f.folder || f.key} /> :
               f.type === 'multi-image' ? <MultiImageUpload folder={f.folder || f.key} /> :
               <Input disabled={f.disabled || Boolean(editingId && f.disabledOnEdit)} placeholder={f.placeholder} />}
            </Form.Item>
          ))}
        </Form>
      </Modal>
    </div>
  );
}
