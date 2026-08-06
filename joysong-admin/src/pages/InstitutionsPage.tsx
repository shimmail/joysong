import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Input, Space } from 'antd';
import { EyeOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import CrudPage from './CrudPage';
import { debounce, getManagementContext } from '../api';

export default function InstitutionsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
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
      title="机构管理"
      apiPath="/admin/institutions"
      allowCreate={isAdmin}
      allowDelete={isAdmin}
      allowEdit={isAdmin || Boolean(managementContext?.canManageInstitutions)}
      canEdit={(record) => isAdmin || Boolean(managementContext?.managedInstitutionIds.includes(record.id))}
      fields={[
        { key: 'name', label: '机构名称', required: true },
        { key: 'coverImage', label: '封面图', type: 'image', folder: 'institutions' },
        { key: 'address', label: '地址' },
        { key: 'city', label: '城市' },
        { key: 'contactPhone', label: '联系电话' },
        { key: 'businessHours', label: '营业时间' },
        { key: 'establishedYear', label: '成立年份', type: 'number' },
        { key: 'certificationTime', label: '认证时间' },
        { key: 'description', label: '机构介绍', type: 'textarea' },
        { key: 'tags', label: '标签（逗号分隔）' },
        { key: 'specialties', label: '擅长领域（逗号分隔）' },
        { key: 'credentials', label: '资质文本', type: 'textarea' },
        { key: 'userCount', label: '用户规模', type: 'number' },
        { key: 'caseCount', label: '案例数', type: 'number' },
        ...(isAdmin ? [
          { key: 'rating', label: '评分', type: 'number' as const },
          { key: 'reviewCount', label: '评价数', type: 'number' as const },
          { key: 'isVerified', label: '认证状态', type: 'switch' as const },
        ] : []),
        { key: 'credentialImages', label: '资质证书图片（可上传多张）', type: 'multi-image', folder: 'credentials' },
        { key: 'images', label: '环境图片（可上传多张）', type: 'multi-image', folder: 'institutions' },
      ]}
      columns={[
        { title: 'ID', dataIndex: 'id', width: 60 },
        { title: '机构名称', dataIndex: 'name', width: 150 },
        { title: '城市', dataIndex: 'city', width: 100 },
        { title: '地址', dataIndex: 'address', width: 200 },
        { title: '评分', dataIndex: 'rating', width: 70 },
        { title: '案例数', dataIndex: 'caseCount', width: 80 },
        { title: '标签', dataIndex: 'tags', width: 150, ellipsis: true },
        { title: '擅长领域', dataIndex: 'specialties', width: 200, ellipsis: true },
        { title: '资质文本', dataIndex: 'credentials', width: 200, ellipsis: true },
        { title: '认证', dataIndex: 'isVerified', width: 80, render: (v: boolean) => v ? '已认证' : '未认证' },
      ]}
      queryParams={searchTerm ? { keyword: searchTerm } : {}}
      extraHeader={
        <Space>
          <Input placeholder="搜索机构名/ID" value={keyword} onChange={handleKeywordChange}
            onPressEnter={handleSearch} allowClear style={{ width: 200 }} prefix={<SearchOutlined />} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          {searchTerm && <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>}
        </Space>
      }
      extraActions={isAdmin ? (record: any) => (
        <Link to={`/institutions/${record.id}`}>
          <Button icon={<EyeOutlined />} size="small" title="查看详情" />
        </Link>
      ) : undefined}
    />
  );
}
