import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic } from 'antd';
import { ShoppingOutlined, TeamOutlined, FileTextOutlined, PictureOutlined, BankOutlined, UserOutlined, BookOutlined, ProfileOutlined } from '@ant-design/icons';
import api, { getData } from '../api';

export default function DashboardPage() {
  const [stats, setStats] = useState<Record<string, number>>({});

  useEffect(() => {
    api.get('/admin/stats').then(res => setStats(getData(res as any)));
  }, []);

  const items = [
    { title: '项目数', value: stats.projects || 0, icon: <ShoppingOutlined />, color: '#E8577B' },
    { title: '医生数', value: stats.doctors || 0, icon: <UserOutlined />, color: '#1890ff' },
    { title: '机构数', value: stats.institutions || 0, icon: <BankOutlined />, color: '#52c41a' },
    { title: '文章数', value: stats.articles || 0, icon: <BookOutlined />, color: '#faad14' },
    { title: '日记数', value: stats.diaries || 0, icon: <ProfileOutlined />, color: '#722ed1' },
    { title: '订单数', value: stats.orders || 0, icon: <FileTextOutlined />, color: '#13c2c2' },
    { title: '用户数', value: stats.users || 0, icon: <TeamOutlined />, color: '#eb2f96' },
    { title: '轮播图数', value: stats.banners || 0, icon: <PictureOutlined />, color: '#fa8c16' },
  ];

  return (
    <div>
      <h2>数据概览</h2>
      <Row gutter={[16, 16]}>
        {items.map(item => (
          <Col xs={12} sm={8} md={6} key={item.title}>
            <Card>
              <Statistic title={item.title} value={item.value} prefix={item.icon} valueStyle={{ color: item.color }} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}
