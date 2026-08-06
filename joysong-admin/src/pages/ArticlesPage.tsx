import { useEffect, useMemo, useState } from 'react';
import {
  Table, Button, Modal, Form, Input, InputNumber, Select, Space,
  message, Popconfirm
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import api, { getData, debounce, getManagementContext } from '../api';
import ImageUpload from '../components/ImageUpload';
import RichTextEditor from '../components/RichTextEditor';

interface Doctor { id: string; name: string; }

export default function ArticlesPage() {
  const isAdmin = getManagementContext()?.platformRole === 'ADMIN';
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [keyword, setKeyword] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const handleSearch = () => setSearchTerm(keyword);
  const handleReset = () => { setKeyword(''); setSearchTerm(''); };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const debouncedSearch = useMemo(() => debounce((val: string) => setSearchTerm(val), 300), []);
  const handleKeywordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setKeyword(val);
    debouncedSearch(val);
  };

  const fetchData = () => {
    setLoading(true);
    const qs = searchTerm ? `?keyword=${encodeURIComponent(searchTerm)}` : '';
    api.get('/admin/articles' + qs).then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchData();
  }, [searchTerm]);

  useEffect(() => {
    api.get('/admin/doctors').then(res => {
      setDoctors(getData<Doctor[]>(res));
    });
  }, []);

  const handleAuthorChange = (authorName: string) => {
    const doctor = doctors.find(d => d.name === authorName);
    if (doctor) {
      form.setFieldsValue({ doctorId: doctor.id });
    } else {
      form.setFieldsValue({ doctorId: undefined });
    }
  };

  const handleAdd = () => {
    setEditingId(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: any) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await api.delete(`/admin/articles/${id}`);
      message.success('删除成功');
      fetchData();
    } catch (err: any) {
      message.error('删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`/admin/articles/${id}`)));
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
        await api.put(`/admin/articles/${editingId}`, { ...values, id: editingId });
        message.success('更新成功');
      } else {
        await api.post('/admin/articles', values);
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

  const doctorOptions = doctors.map(d => ({ label: d.name, value: d.name }));

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '标题', dataIndex: 'title', width: 250 },
    { title: '作者', dataIndex: 'authorName', width: 100 },
    { title: '发布日期', dataIndex: 'publishDate', width: 120 },
    { title: '阅读数', dataIndex: 'readCount', width: 80 },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: any, record: any) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => handleEdit(record)} title="编辑" />
          <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger title="删除" />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>文章管理</h2>
        <Space>
          <Input placeholder="搜索标题/ID" value={keyword} onChange={handleKeywordChange}
            onPressEnter={handleSearch} allowClear style={{ width: 200 }} prefix={<SearchOutlined />} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          {searchTerm && <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>}
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} style={{ background: '#E8577B' }}>新增文章</Button>
          <Popconfirm title={`确定删除选中的 ${selectedRowKeys.length} 条记录吗？`} onConfirm={handleBatchDelete} disabled={selectedRowKeys.length === 0}>
            <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
              批量删除{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
            </Button>
          </Popconfirm>
        </Space>
      </div>
      <Table dataSource={data} columns={columns} rowKey="id" loading={loading} size="small" scroll={{ x: 'max-content' }}
        rowSelection={{
          selectedRowKeys,
          onChange: keys => setSelectedRowKeys(keys),
        }}
      />
      <Modal title={editingId ? '编辑文章' : '新增文章'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={800} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical" style={{ marginTop: 16, maxHeight: '65vh', overflowY: 'auto', paddingRight: 8 }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="authorName" label="作者（选择医生）">
            <Select
              options={doctorOptions}
              showSearch
              allowClear
              placeholder="请选择医生"
              onChange={handleAuthorChange}
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Form.Item name="doctorId" hidden>
            <Input />
          </Form.Item>
          <Form.Item name="summary" label="摘要">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="coverImage" label="封面图">
            <ImageUpload folder="articles" />
          </Form.Item>
          <Form.Item name="publishDate" label="发布日期">
            <Input placeholder="如 2025-01-01" />
          </Form.Item>
          <Form.Item name="content" label="正文内容">
            <RichTextEditor />
          </Form.Item>
          {isAdmin && (
            <Form.Item name="readCount" label="阅读数">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
