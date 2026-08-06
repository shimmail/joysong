import { useMemo, useState } from 'react';
import { Input, Button, Space } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import CrudPage from './CrudPage';
import { debounce } from '../api';

export default function DiariesPage() {
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

  return (
    <CrudPage
      title="日记管理"
      apiPath="/admin/diaries"
      fields={[
        { key: 'title', label: '标题', required: true },
        { key: 'authorName', label: '作者' },
        { key: 'content', label: '内容', type: 'textarea' },
        { key: 'coverImage', label: '封面图链接' },
        { key: 'images', label: '图片链接（逗号分隔）' },
        { key: 'likeCount', label: '点赞数', type: 'number' },
        { key: 'commentCount', label: '评论数', type: 'number' },
        { key: 'tags', label: '标签（逗号分隔）' },
        { key: 'publishDate', label: '发布日期' },
      ]}
      columns={[
        { title: 'ID', dataIndex: 'id', width: 60 },
        { title: '标题', dataIndex: 'title', width: 250 },
        { title: '作者', dataIndex: 'authorName', width: 120 },
        { title: '关联机构项目', dataIndex: 'projectName', width: 180, ellipsis: true, render: (value: string, record: any) => value || record.institutionProjectId || '-' },
        { title: '点赞', dataIndex: 'likeCount', width: 80 },
        { title: '评论', dataIndex: 'commentCount', width: 90 },
        { title: '标签', dataIndex: 'tags', width: 150 },
        { title: '发布日期', dataIndex: 'publishDate', width: 120 },
      ]}
      queryParams={searchTerm ? { keyword: searchTerm } : {}}
      extraHeader={
        <Space>
          <Input placeholder="搜索标题/ID" value={keyword} onChange={handleKeywordChange}
            onPressEnter={handleSearch} allowClear style={{ width: 200 }} prefix={<SearchOutlined />} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          {searchTerm && <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>}
        </Space>
      }
    />
  );
}
