import { useEffect, useMemo, useState } from 'react';
import {
  Table, Button, Modal, Form, Input, InputNumber, Space,
  message, Popconfirm
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import api, { getData, debounce } from '../api';
import ImageUpload from '../components/ImageUpload';
import MultiImageUpload from '../components/MultiImageUpload';

export default function ProjectsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
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
    api.get('/admin/projects' + qs).then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [searchTerm]);

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
      await api.delete(`/admin/projects/${id}`);
      message.success('删除成功');
      fetchData();
    } catch (err: any) {
      message.error('删除失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      await Promise.all(selectedRowKeys.map(id => api.delete(`/admin/projects/${id}`)));
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
        await api.put(`/admin/projects/${editingId}`, { ...values, id: editingId });
        message.success('更新成功');
      } else {
        await api.post('/admin/projects', values);
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

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '项目名称', dataIndex: 'name', width: 150 },
    { title: '参考均价', dataIndex: 'referencePrice', width: 90 },
    { title: '宣传语', dataIndex: 'slogan', width: 200, ellipsis: true },
    { title: '分类', dataIndex: 'category', width: 100 },
    { title: '评分', dataIndex: 'rating', width: 70 },
    { title: '销量', dataIndex: 'salesCount', width: 70 },
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
        <h2>项目管理</h2>
        <Space>
          <Input placeholder="搜索项目名/ID" value={keyword} onChange={handleKeywordChange}
            onPressEnter={handleSearch} allowClear style={{ width: 200 }} prefix={<SearchOutlined />} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          {searchTerm && <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>}
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} style={{ background: '#E8577B' }}>新增项目</Button>
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
      <Modal title={editingId ? '编辑项目' : '新增项目'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={640} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical" style={{ marginTop: 16, maxHeight: '65vh', overflowY: 'auto', paddingRight: 8 }}>
          <Form.Item name="name" label="项目名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="referencePrice" label="参考均价"><InputNumber style={{ width: '100%' }} min={0} /></Form.Item>
          <Form.Item name="slogan" label="宣传语"><Input placeholder="简短宣传语" /></Form.Item>
          <Form.Item name="salesCount" label="销量"><InputNumber style={{ width: '100%' }} min={0} /></Form.Item>
          <Form.Item name="coverImage" label="封面图"><ImageUpload folder="projects" recommendedSize="1200 × 800 px（3:2）" /></Form.Item>
          <Form.Item name="images" label="项目图集"><MultiImageUpload folder="projects" /></Form.Item>
          <Form.Item name="category" label="分类"><Input /></Form.Item>
          <Form.Item name="description" label="项目介绍"><Input.TextArea rows={3} /></Form.Item>
          <Form.Item name="detailContent" label="详情内容(HTML)"><Input.TextArea rows={9} placeholder="支持HTML格式的详情内容" /></Form.Item>
          <Form.Item name="rating" label="评分"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reviewCount" label="评价数"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="tags" label="标签（逗号分隔）"><Input /></Form.Item>
          <Form.Item name="categoryTags" label="分类标签（逗号分隔）"><Input /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
