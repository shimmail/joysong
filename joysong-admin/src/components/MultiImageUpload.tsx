import { useState } from 'react';
import { Upload, Button, message, Image } from 'antd';
import { UploadOutlined, CloseCircleFilled } from '@ant-design/icons';
import type { RcFile } from 'antd/es/upload';
import api from '../api';
import { getPreviewImageUrl } from '../utils/imageUrl';

interface MultiImageUploadProps {
  value?: string;
  onChange?: (value: string) => void;
  folder?: string;
}

export default function MultiImageUpload({ value, onChange, folder = 'general' }: MultiImageUploadProps) {
  const [loading, setLoading] = useState(false);

  const urls = value ? value.split(',').filter(u => u.trim()) : [];

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
        const newUrls = [...urls, url];
        onChange?.(newUrls.join(','));
        message.success('上传成功');
      } else {
        message.error('上传失败：未获取到图片地址');
      }
    } catch (err: any) {
      message.error('上传失败: ' + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
    }
    return false;
  };

  const handleRemove = (index: number) => {
    const newUrls = urls.filter((_, i) => i !== index);
    onChange?.(newUrls.join(','));
  };

  return (
    <div>
      {urls.length > 0 && (
        <Image.PreviewGroup>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
            {urls.map((url, index) => (
              <div key={index} style={{ position: 'relative' }}>
                <Image
                  src={getPreviewImageUrl(url)}
                  width={80}
                  height={80}
                  style={{ objectFit: 'cover', borderRadius: 4 }}
                  fallback="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                />
                <CloseCircleFilled
                  onClick={(e) => { e.stopPropagation(); handleRemove(index); }}
                  style={{
                    position: 'absolute', top: -6, right: -6, fontSize: 16,
                    color: '#ff4d4f', background: '#fff', borderRadius: '50%', cursor: 'pointer'
                  }}
                />
              </div>
            ))}
          </div>
        </Image.PreviewGroup>
      )}
      <Upload
        showUploadList={false}
        beforeUpload={handleUpload}
        accept="image/*"
      >
        <Button icon={<UploadOutlined />} loading={loading}>
          上传图片
        </Button>
      </Upload>
      <div style={{ marginTop: 4, fontSize: 12, color: '#999' }}>
        已上传 {urls.length} 张，可多次上传追加
      </div>
    </div>
  );
}
