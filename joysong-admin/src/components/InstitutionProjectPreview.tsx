import { Fragment, useMemo, useState, type ReactNode } from 'react';
import { Image, Space, Tag, Typography } from 'antd';
import { getPreviewImageUrl } from '../utils/imageUrl';
import type { InstitutionProjectPreviewModel } from '../types/projectRequests';

const neutralImagePlaceholder = 'data:image/svg+xml;charset=UTF-8,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22240%22 height=%22160%22 viewBox=%220 0 240 160%22%3E%3Crect width=%22240%22 height=%22160%22 fill=%22%23f5f5f5%22/%3E%3Cpath d=%22M70 112l34-38 25 28 17-18 24 28z%22 fill=%22%23d9d9d9%22/%3E%3Ccircle cx=%22150%22 cy=%2255%22 r=%2212%22 fill=%22%23d9d9d9%22/%3E%3C/svg%3E';
const richTags = new Set(['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'blockquote']);

function RichProjectDetail({ html }: { html: string }) {
  const content = useMemo(() => {
    const document = new DOMParser().parseFromString(html, 'text/html');
    const toReact = (node: Node, key: string): ReactNode => {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent;
      if (node.nodeType !== Node.ELEMENT_NODE) return null;
      const element = node as HTMLElement;
      const tag = element.tagName.toLowerCase();
      if (tag === 'script' || tag === 'style') return null;
      const children = Array.from(element.childNodes).map((child, index) => toReact(child, `${key}-${index}`));
      if (!richTags.has(tag)) return <Fragment key={key}>{children}</Fragment>;
      if (tag === 'br') return <br key={key} />;
      if (tag === 'p') return <p key={key}>{children}</p>;
      if (tag === 'strong') return <strong key={key}>{children}</strong>;
      if (tag === 'em') return <em key={key}>{children}</em>;
      if (tag === 'ul') return <ul key={key}>{children}</ul>;
      if (tag === 'ol') return <ol key={key}>{children}</ol>;
      if (tag === 'li') return <li key={key}>{children}</li>;
      if (tag === 'h1') return <h1 key={key}>{children}</h1>;
      if (tag === 'h2') return <h2 key={key}>{children}</h2>;
      if (tag === 'h3') return <h3 key={key}>{children}</h3>;
      return <blockquote key={key}>{children}</blockquote>;
    };
    return Array.from(document.body.childNodes).map((node, index) => toReact(node, String(index)));
  }, [html]);
  return <div>{content}</div>;
}

function SafePreviewImage({ value, alt, width = 120 }: { value: string; alt: string; width?: number }) {
  const [failed, setFailed] = useState(false);
  return <Image
    src={failed ? neutralImagePlaceholder : getPreviewImageUrl(value)}
    fallback={neutralImagePlaceholder}
    preview={!failed}
    alt={alt}
    width={width}
    height={Math.round(width * 2 / 3)}
    style={{ objectFit: 'cover', borderRadius: 6 }}
    onError={() => setFailed(true)}
  />;
}

function money(currency: string, value: number | null) {
  return value == null ? '-' : `${currency} ${value.toFixed(2)}`;
}

export default function InstitutionProjectPreview({ model }: { model: InstitutionProjectPreviewModel }) {
  const cover = model.coverImage?.trim();
  const images = model.images.filter(value => value.trim().length > 0);
  return <section aria-label="机构项目预览">
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={4} style={{ marginBottom: 4 }}>{model.name}</Typography.Title>
        {model.slogan && <Typography.Text type="secondary">{model.slogan}</Typography.Text>}
      </div>
      <Space wrap>
        {model.category && <Tag>{model.category}</Tag>}
        {model.tags.map(tag => <Tag key={tag}>{tag}</Tag>)}
      </Space>
      {model.description && <Typography.Paragraph>{model.description}</Typography.Paragraph>}
      <Image.PreviewGroup>
        <Space wrap align="start">
          {cover && <SafePreviewImage value={cover} alt="项目封面" width={180} />}
          {images.map((value, index) => <SafePreviewImage key={`${value}-${index}`} value={value} alt={`项目图片 ${index + 1}`} />)}
        </Space>
      </Image.PreviewGroup>
      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '6px 12px', margin: 0 }}>
        <dt>价格</dt><dd style={{ margin: 0 }}>{money(model.currency, model.price)}</dd>
        <dt>旅游地接服务费</dt><dd style={{ margin: 0 }}>{money(model.currency, model.travelGroundServiceFee)}</dd>
        {model.scheduleNote != null && <><dt>排期说明</dt><dd style={{ margin: 0 }}>{model.scheduleNote || '-'}</dd></>}
      </dl>
      {model.detailContent && <RichProjectDetail html={model.detailContent} />}
    </Space>
  </section>;
}
