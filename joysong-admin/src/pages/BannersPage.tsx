import CrudPage from './CrudPage';

export default function BannersPage() {
  return (
    <CrudPage
      title="轮播图管理"
      apiPath="/admin/banners"
      fields={[
        { key: 'title', label: '标题', required: true },
        { key: 'subtitle', label: '副标题' },
        { key: 'imageUrl', label: '图片', type: 'image', folder: 'banners', recommendedSize: '1920 × 720 px（约 8:3）' },
        { key: 'accentColor', label: '主题色' },
        { key: 'sortOrder', label: '排序', type: 'number' },
      ]}
      columns={[
        { title: 'ID', dataIndex: 'id', width: 60 },
        { title: '标题', dataIndex: 'title', width: 200 },
        { title: '副标题', dataIndex: 'subtitle', width: 250 },
        { title: '主题色', dataIndex: 'accentColor', width: 100 },
        { title: '排序', dataIndex: 'sortOrder', width: 70 },
      ]}
    />
  );
}
