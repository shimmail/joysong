import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  Card, Descriptions, Tabs, Table, Button, Tag
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import api, { getData } from '../api';
import { identityStatusColor, identityStatusLabel } from '../identity';

function InstitutionDoctorsSummary({ institutionId }: { institutionId: string }) {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    setLoading(true);
    api.get('/admin/identity/doctor-practices', { params: { institutionId } })
      .then(res => setData(getData(res as any)))
      .finally(() => setLoading(false));
  }, [institutionId]);

  return (
    <Card
      title="机构医生执业关系"
      extra={<Link to="/identity"><Button type="primary">前往医生执业审核</Button></Link>}
    >
      <Table
        rowKey="id"
        loading={loading}
        dataSource={data}
        pagination={false}
        columns={[
          { title: '医生', dataIndex: 'doctorName' },
          { title: '手机号', dataIndex: 'doctorPhone', render: (value: string) => value || '-' },
          { title: '医生/用户 ID', dataIndex: 'doctorId', width: 230 },
          { title: '主机构', dataIndex: 'primary', render: (value: boolean) => value ? <Tag color="blue">是</Tag> : '-' },
          { title: '执业登记号', dataIndex: 'registrationNo', render: (value: string) => value || '-' },
          { title: '审核状态', dataIndex: 'status', render: (value: string) => <Tag color={identityStatusColor(value)}>{identityStatusLabel(value)}</Tag> },
        ]}
      />
    </Card>
  );
}

function InstitutionProjectsSummary({ institutionId }: { institutionId: string }) {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    setLoading(true);
    api.get(`/admin/institutions/${institutionId}/projects`)
      .then(res => setData(getData(res as any)))
      .finally(() => setLoading(false));
  }, [institutionId]);

  return (
    <Card title="机构项目" extra={<Link to={`/institution-projects?institutionId=${institutionId}`}><Button type="primary">配置机构项目详情</Button></Link>}>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={data}
        pagination={false}
        columns={[
          { title: '机构项目名称', dataIndex: 'effectiveName' },
          { title: '公共项目', dataIndex: 'baseProjectName' },
          { title: '分类', dataIndex: 'effectiveCategory' },
          { title: '价格', dataIndex: 'price' },
          { title: '详情配置', dataIndex: 'name', render: (_: unknown, record: any) => record.name || record.category || record.description || record.detailContent ? <Tag color="pink">部分自定义</Tag> : <Tag>全部继承</Tag> },
          { title: '状态', dataIndex: 'isActive', render: (value: boolean) => value ? <Tag color="green">上架</Tag> : <Tag>停用</Tag> },
        ]}
      />
    </Card>
  );
}

export default function InstitutionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [institution, setInstitution] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    api.get(`/admin/institutions/${id}`).then(res => {
      const data = getData<any>(res as any);
      setInstitution(data);
    }).finally(() => setLoading(false));
  }, [id]);

  if (!id) return <div>参数错误</div>;
  if (loading) return <div>加载中...</div>;
  if (!institution) return <div>机构不存在</div>;

  return (
    <div>
      <Link to="/institutions">
        <Button icon={<ArrowLeftOutlined />} style={{ marginBottom: 16 }}>返回机构列表</Button>
      </Link>
      <Card title={institution.name} style={{ marginBottom: 24 }}>
        <Descriptions size="small" column={4}>
          <Descriptions.Item label="地址">{institution.address || '-'}</Descriptions.Item>
          <Descriptions.Item label="城市">{institution.city || '-'}</Descriptions.Item>
          <Descriptions.Item label="评分">{institution.rating ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="评价数">{institution.reviewCount ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="认证状态">
            <Tag color={institution.isVerified ? 'green' : 'default'}>{institution.isVerified ? '已认证' : '未认证'}</Tag>
          </Descriptions.Item>
        </Descriptions>
        <p style={{ marginTop: 12, color: '#666' }}>{institution.description || '暂无介绍'}</p>
      </Card>
      <Tabs
        defaultActiveKey="doctors"
        items={[
          {
            key: 'doctors',
            label: '机构医生',
            children: <InstitutionDoctorsSummary institutionId={id} />,
          },
          {
            key: 'projects',
            label: '机构项目',
            children: <InstitutionProjectsSummary institutionId={id} />,
          },
        ]}
      />
    </div>
  );
}
