import { useEffect, useMemo, useState } from 'react';
import { Input, Button, Space, message } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import CrudPage from './CrudPage';
import api, { getData, debounce, getApiErrorMessage, getManagementContext } from '../api';

export default function DoctorsPage() {
  const managementContext = getManagementContext();
  const isAdmin = managementContext?.platformRole === 'ADMIN';
  const [institutionOptions, setInstitutionOptions] = useState<{ label: string; value: string }[]>([]);
  const [doctorUserOptions, setDoctorUserOptions] = useState<{ label: string; value: string }[]>([]);
  const [keyword, setKeyword] = useState('');
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    const roleRequest = isAdmin
      ? api.get('/admin/identity/roles', { params: { roleCode: 'DOCTOR', status: 'ACTIVE' } })
      : Promise.resolve(null);
    Promise.all([api.get('/admin/institutions'), roleRequest]).then(([institutionRes, roleRes]) => {
      const institutions = getData<any[]>(institutionRes as any);
      const roles = roleRes ? getData<any[]>(roleRes as any) : [];
      setInstitutionOptions(institutions.map(i => ({ label: i.name, value: i.id })));
      setDoctorUserOptions(roles.map(role => ({
        label: `${role.userName || '未命名用户'} · ${role.userPhone || '无手机号'} · ${role.userId}`,
        value: role.userId,
      })));
    }).catch(error => message.error(getApiErrorMessage(error, '医生身份候选用户加载失败')));
  }, [isAdmin]);

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
      title="医生管理"
      apiPath="/admin/doctors"
      allowCreate={isAdmin}
      allowDelete={isAdmin}
      queryParams={searchTerm ? { keyword: searchTerm } : {}}
      extraHeader={
        <Space>
          <Input
            placeholder="搜索医生姓名"
            value={keyword}
            onChange={handleKeywordChange}
            onPressEnter={handleSearch}
            allowClear
            style={{ width: 200 }}
            prefix={<SearchOutlined />}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          {searchTerm && <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>}
        </Space>
      }
      fields={[
        ...(isAdmin ? [{
          key: 'userId',
          label: '绑定的医生用户',
          type: 'select',
          options: doctorUserOptions,
          required: true,
          disabledOnEdit: true,
          placeholder: '请选择已通过医生身份审核的用户',
          help: '医生档案 ID 与用户 ID 保持一致；创建后不可更换绑定用户。',
        } as const] : []),
        { key: 'name', label: '姓名', required: true },
        { key: 'title', label: '职称' },
        { key: 'avatar', label: '头像', type: 'image', folder: 'avatars' },
        { key: 'bio', label: '医生简介', type: 'textarea' },
        ...(isAdmin ? [
          { key: 'institutionIds', label: '出诊机构', type: 'multi-select' as const, options: institutionOptions },
          { key: 'primaryInstitutionId', label: '主展示机构', type: 'select' as const, options: institutionOptions },
        ] : []),
        { key: 'specialties', label: '擅长领域（逗号分隔）' },
        { key: 'certificationTags', label: '认证标签（逗号分隔）' },
        { key: 'caseCount', label: '案例数', type: 'number' },
        { key: 'consultationCount', label: '咨询数', type: 'number' },
        ...(isAdmin ? [
          { key: 'rating', label: '评分', type: 'number' as const },
          { key: 'reviewCount', label: '评价数', type: 'number' as const },
          { key: 'isVerified', label: '认证状态', type: 'switch' as const },
        ] : []),
        { key: 'credentials', label: '资质文本', type: 'textarea' },
        {
          key: 'credentialImages',
          label: '主页证书图片（可上传多张）',
          type: 'multi-image',
          folder: 'doctor-profile-certificates',
          help: '默认为空，由医生自行上传或删除；仅用于主页展示，与平台资质认证材料和认证状态相互独立。',
        },
      ]}
      columns={[
        { title: '用户/医生 ID', dataIndex: 'id', width: 220 },
        { title: '姓名', dataIndex: 'name', width: 120 },
        { title: '职称', dataIndex: 'title', width: 100 },
        { title: '主展示机构', dataIndex: 'institutionName', width: 150 },
        { title: '绑定机构数', dataIndex: 'institutionCount', width: 100 },
        { title: '评分', dataIndex: 'rating', width: 70 },
        { title: '案例数', dataIndex: 'caseCount', width: 80 },
        { title: '擅长领域', dataIndex: 'specialties', width: 200, ellipsis: true },
        { title: '资质文本', dataIndex: 'credentials', width: 200, ellipsis: true },
        { title: '认证标签', dataIndex: 'certificationTags', width: 150, ellipsis: true },
        { title: '认证', dataIndex: 'isVerified', width: 80, render: (v: boolean) => v ? '已认证' : '未认证' },
      ]}
    />
  );
}
