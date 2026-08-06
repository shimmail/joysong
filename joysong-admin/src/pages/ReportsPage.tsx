import { useEffect, useState } from 'react';
import { Table, Button, Popconfirm, message, Tag, Space, Tabs, Modal, Input, Form } from 'antd';
import { DeleteOutlined, CloseOutlined, EditOutlined, UndoOutlined, EyeOutlined } from '@ant-design/icons';
import api, { getData } from '../api';

// 举报原因中英文映射
const reasonMap: Record<string, string> = {
  'Spam / Advertising': '垃圾广告',
  'Pornographic / Vulgar': '色情低俗',
  'Verbal Abuse / Harassment': '辱骂攻击',
  'False / Misleading Info': '虚假信息',
  'Politically Sensitive': '涉政敏感',
  'Copyright Infringement': '侵权抄袭',
  'Other': '其它',
  '垃圾广告': '垃圾广告',
  '色情低俗': '色情低俗',
  '辱骂攻击': '辱骂攻击',
  '虚假信息': '虚假信息',
  '涉政敏感': '涉政敏感',
  '侵权抄袭': '侵权抄袭',
  '其它': '其它',
};

function toChineseReason(reason: string): string {
  return reasonMap[reason] || reason;
}

export default function ReportsPage() {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [showDeleted, setShowDeleted] = useState(false);

  // 日记编辑弹窗
  const [diaryModalOpen, setDiaryModalOpen] = useState(false);
  const [diaryLoading, setDiaryLoading] = useState(false);
  const [diaryData, setDiaryData] = useState<any>(null);
  const [diaryForm] = Form.useForm();

  const fetchData = () => {
    setLoading(true);
    let url = '/admin/reports?';
    if (showDeleted) {
      url += 'deleted=true';
    } else if (statusFilter) {
      url += `status=${statusFilter}`;
    }
    api.get(url).then(res => setData(getData(res as any))).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [statusFilter, showDeleted]);

  const handleUpdateStatus = async (id: string, status: string) => {
    await api.put(`/admin/reports/${id}/status`, { status });
    message.success('状态更新成功');
    fetchData();
  };

  const handleRestoreReport = async (id: string) => {
    await api.put(`/admin/reports/${id}/restore`);
    message.success('已撤销删除');
    fetchData();
  };

  const handleDeleteTarget = async (record: any) => {
    await api.delete(`/admin/reports/target/${record.targetType}/${record.targetId}`);
    await api.put(`/admin/reports/${record.id}/status`, { status: 'resolved' });
    message.success('已删除被举报内容');
    fetchData();
  };

  const handleUndoStatus = async (id: string) => {
    await api.put(`/admin/reports/${id}/status`, { status: 'pending' });
    message.success('已撤回操作');
    fetchData();
  };

  const handleOpenDiary = async (diaryId: string) => {
    setDiaryModalOpen(true);
    setDiaryLoading(true);
    try {
      const listRes = await api.get('/admin/diaries');
      const list = getData(listRes as any) as any[];
      const diary = list.find((d: any) => d.id === diaryId);
      if (diary) {
        setDiaryData(diary);
        diaryForm.setFieldsValue(diary);
      } else {
        message.error('未找到该日记');
      }
    } catch {
      message.error('获取日记失败');
    } finally {
      setDiaryLoading(false);
    }
  };

  const handleSaveDiary = async () => {
    if (!diaryData) return;
    try {
      const values = await diaryForm.validateFields();
      await api.put(`/admin/diaries/${diaryData.id}`, { ...values, id: diaryData.id });
      message.success('日记已更新');
      setDiaryModalOpen(false);
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error('保存失败: ' + (err?.response?.data?.message || err?.message));
    }
  };

  const statusColorMap: Record<string, string> = {
    pending: 'orange',
    resolved: 'green',
    ignored: 'default',
  };
  const statusTextMap: Record<string, string> = {
    pending: '待处理',
    resolved: '已处理',
    ignored: '已忽略',
  };
  const typeTextMap: Record<string, string> = {
    diary: '日记',
    review: '评价',
    comment: '评论',
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '类型', dataIndex: 'targetType', width: 70,
      render: (v: string) => typeTextMap[v] || v,
    },
    { title: '目标ID', dataIndex: 'targetId', width: 140, ellipsis: true },
    {
      title: '举报原因', dataIndex: 'reason', width: 100,
      render: (v: string) => toChineseReason(v),
    },
    { title: '补充说明', dataIndex: 'description', width: 180, ellipsis: true, render: (v: string) => v || '—' },
    { title: '举报人', dataIndex: 'userId', width: 140, ellipsis: true },
    {
      title: '内容摘要', dataIndex: 'targetSummary', width: 260, ellipsis: true,
      render: (v: any, record: any) => {
        if (!v) return '内容已删除';
        if (record.targetType === 'diary') return `${v.title || ''} — ${v.content || ''}`;
        if (record.targetType === 'review') return `${v.content || ''}（评分 ${v.rating || ''}）`;
        if (record.targetType === 'comment') return v.content || '';
        return JSON.stringify(v);
      },
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: string) => <Tag color={statusColorMap[v] || 'default'}>{statusTextMap[v] || v}</Tag>,
    },
    { title: '举报时间', dataIndex: 'createdAt', width: 155 },
    {
      title: '操作', key: 'actions', width: showDeleted ? 120 : 300, fixed: 'right' as const,
      render: (_: any, record: any) => {
        if (showDeleted) {
          return (
            <Popconfirm title="撤销删除？" onConfirm={() => handleRestoreReport(record.id)}>
              <Button icon={<UndoOutlined />} size="small" type="link">撤销删除</Button>
            </Popconfirm>
          );
        }
        return (
          <Space size="small" wrap>
            {record.targetType === 'diary' && record.targetSummary && (
              <Button icon={<EditOutlined />} size="small" onClick={() => handleOpenDiary(record.targetId)}>编辑日记</Button>
            )}
            {record.status === 'pending' && (
              <>
                <Popconfirm title="确定删除被举报内容？" onConfirm={() => handleDeleteTarget(record)}>
                  <Button size="small" danger icon={<DeleteOutlined />}>删除内容</Button>
                </Popconfirm>
                <Popconfirm title="确定忽略该举报？" onConfirm={() => handleUpdateStatus(record.id, 'ignored')}>
                  <Button icon={<CloseOutlined />} size="small">忽略</Button>
                </Popconfirm>
              </>
            )}
            {(record.status === 'resolved' || record.status === 'ignored') && (
              <Popconfirm title="撤回操作，恢复为待处理？" onConfirm={() => handleUndoStatus(record.id)}>
                <Button icon={<UndoOutlined />} size="small" type="link">撤回</Button>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  const tabItems = showDeleted
    ? [{ key: '', label: '已删除' }]
    : [
        { key: '', label: '全部' },
        { key: 'pending', label: '待处理' },
        { key: 'resolved', label: '已处理' },
        { key: 'ignored', label: '已忽略' },
      ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>举报管理</h2>
        <Button
          icon={showDeleted ? <EyeOutlined /> : <DeleteOutlined />}
          onClick={() => { setShowDeleted(!showDeleted); setStatusFilter(''); }}
        >
          {showDeleted ? '查看正常记录' : '查看已删除'}
        </Button>
      </div>
      <Tabs
        activeKey={statusFilter}
        onChange={setStatusFilter}
        items={tabItems}
        style={{ marginBottom: 16 }}
      />
      <Table
        dataSource={data}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
      />

      {/* 日记编辑弹窗 */}
      <Modal
        title={`编辑日记${diaryData?.title ? ' — ' + diaryData.title : ''}`}
        open={diaryModalOpen}
        onOk={handleSaveDiary}
        onCancel={() => setDiaryModalOpen(false)}
        width={640}
        okText="保存"
        cancelText="取消"
        confirmLoading={diaryLoading}
      >
        <Form form={diaryForm} layout="vertical" style={{ marginTop: 16, maxHeight: '65vh', overflowY: 'auto' }}>
          <Form.Item name="title" label="标题" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="content" label="内容">
            <Input.TextArea rows={6} />
          </Form.Item>
          <Form.Item name="tags" label="标签（逗号分隔）">
            <Input />
          </Form.Item>
          <Form.Item name="coverImage" label="封面图链接">
            <Input />
          </Form.Item>
          <Form.Item name="images" label="图片链接（逗号分隔）">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
