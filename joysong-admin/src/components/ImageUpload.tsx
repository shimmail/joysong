import { useState } from 'react';
import { Upload, Button, message, Image } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import type { RcFile } from 'antd/es/upload';
import api from '../api';
import { getPreviewImageUrl } from '../utils/imageUrl';

interface ImageUploadProps {
  value?: string;
  onChange?: (value: string) => void;
  folder?: string;
  recommendedSize?: string;
}

export default function ImageUpload({
  value,
  onChange,
  folder = 'general',
  recommendedSize,
}: ImageUploadProps) {
  const [loading, setLoading] = useState(false);

  const handleUpload = async (file: RcFile) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('folder', folder);

    setLoading(true);
    try {
      // 让浏览器自动附加 multipart boundary，手动设置 Content-Type 可能导致服务端无法解析文件。
      const res = await api.post('/upload', formData);
      const url = res.data?.data?.url || res.data?.url;
      if (url) {
        onChange?.(url);
        message.success('上传成功');
      } else {
        message.error('上传失败：未获取到图片地址，响应: ' + JSON.stringify(res.data));
      }
    } catch (err: any) {
      message.error('上传失败: ' + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
    }
    return false; // 阻止默认上传行为
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        {value && (
          <Image
            src={getPreviewImageUrl(value)}
            width={80}
            height={80}
            style={{ objectFit: 'cover', borderRadius: 4 }}
            fallback="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
          />
        )}
        <Upload
          showUploadList={false}
          beforeUpload={handleUpload}
          accept="image/*"
        >
          <Button icon={<UploadOutlined />} loading={loading}>
            {value ? '更换图片' : '上传图片'}
          </Button>
        </Upload>
      </div>
      {value && (
        <div style={{ marginTop: 8, fontSize: 12, color: '#999', wordBreak: 'break-all' }}>
          {value}
        </div>
      )}
      <div style={{ marginTop: 4, fontSize: 12, color: '#999' }}>
        推荐尺寸：{recommendedSize || recommendedSizeForFolder(folder)}；上传原图，页面按比例裁剪展示
      </div>
    </div>
  );
}

function recommendedSizeForFolder(folder: string): string {
  if (folder === 'banners') return '1920 × 720 px（约 8:3）';
  if (folder === 'avatars') return '400 × 400 px（1:1）';
  return '1200 × 800 px（3:2）';
}
