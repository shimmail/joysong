import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Drawer, Form, Input, Modal, Row, Space, Spin, Table, Tabs, Tag, message } from 'antd';
import RichTextEditor from '../components/RichTextEditor';
import { getApiErrorMessage } from '../api';
import {
  createLegalDocumentDraft,
  getLegalDocumentHistory,
  getLegalDocumentRelease,
  listLegalDocuments,
  publishLegalDocumentRelease,
  updateLegalDocumentDraft,
} from '../legalDocuments';
import type {
  LegalDocumentContent,
  LegalDocumentLocale,
  LegalDocumentRelease,
  LegalDocumentReleaseSummary,
  LegalDocumentSummary,
  LegalDocumentType,
} from '../legalDocuments';

const documentDefinitions: Array<{ type: LegalDocumentType; title: string }> = [
  { type: 'user-agreement', title: '用户协议' },
  { type: 'privacy-policy', title: '隐私政策' },
];
const legalToolbarKeys = ['headerSelect', 'bold', 'italic', 'underline', 'blockquote', 'bulletedList', 'numberedList', 'insertLink', 'undo', 'redo'];
const previewCsp = "default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-src 'none'";

type DraftRequestTarget = { documentType: LegalDocumentType; releaseId?: string };

function escapeHtmlText(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function contentFor(release: LegalDocumentRelease, locale: LegalDocumentLocale): LegalDocumentContent {
  return release.contents.find((item) => item.locale === locale) ?? {
    locale,
    title: '',
    contentHtml: '',
    contentSha256: '',
  };
}

function editableContent(release: LegalDocumentRelease, locale: LegalDocumentLocale) {
  const content = contentFor(release, locale);
  return { title: content.title, contentHtml: content.contentHtml };
}

function hasVisibleContent(html: string) {
  const element = document.createElement('div');
  element.innerHTML = html;
  return Boolean(element.textContent?.trim());
}

function canPublish(release: LegalDocumentRelease | null) {
  return Boolean(release && release.status === 'DRAFT' && (['zh-CN', 'en-US'] as LegalDocumentLocale[]).every((locale) => {
    const content = contentFor(release, locale);
    return content.title.trim() && hasVisibleContent(content.contentHtml);
  }));
}

function releaseSummary(release: LegalDocumentRelease): LegalDocumentReleaseSummary {
  const { publishedBy: _publishedBy, createdAt: _createdAt, contents: _contents, ...summary } = release;
  return summary;
}

export default function LegalDocumentsPage() {
  const [summaries, setSummaries] = useState<LegalDocumentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState<LegalDocumentRelease | null>(null);
  const [savedDraft, setSavedDraft] = useState<LegalDocumentRelease | null>(null);
  const [activeLocale, setActiveLocale] = useState<LegalDocumentLocale>('zh-CN');
  const [changeSummary, setChangeSummary] = useState('');
  const [contents, setContents] = useState<Record<LegalDocumentLocale, Pick<LegalDocumentContent, 'title' | 'contentHtml'>>>({
    'zh-CN': { title: '', contentHtml: '' },
    'en-US': { title: '', contentHtml: '' },
  });
  const [saving, setSaving] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<LegalDocumentReleaseSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const mountedRef = useRef(false);
  const summaryGenerationRef = useRef(0);
  const draftGenerationRef = useRef(0);
  const draftTargetRef = useRef<DraftRequestTarget | null>(null);
  const historyGenerationRef = useRef(0);
  const historyTypeRef = useRef<LegalDocumentType | null>(null);

  const isCurrentSummaryRequest = (generation: number) => (
    mountedRef.current && summaryGenerationRef.current === generation
  );

  const isCurrentDraftRequest = (generation: number, target: DraftRequestTarget) => (
    mountedRef.current && draftGenerationRef.current === generation && draftTargetRef.current === target
  );

  const isExpectedDraft = (release: LegalDocumentRelease, target: DraftRequestTarget) => (
    release.documentType === target.documentType && (!target.releaseId || release.id === target.releaseId)
  );

  const isCurrentHistoryRequest = (generation: number, type: LegalDocumentType) => (
    mountedRef.current && historyGenerationRef.current === generation && historyTypeRef.current === type
  );

  const loadSummaries = async () => {
    const generation = ++summaryGenerationRef.current;
    if (!mountedRef.current) return;
    setLoading(true);
    try {
      const response = await listLegalDocuments();
      if (!isCurrentSummaryRequest(generation)) return;
      setSummaries(response);
    } catch (error) {
      if (!isCurrentSummaryRequest(generation)) return;
      message.error(getApiErrorMessage(error, '加载协议列表失败'));
    } finally {
      if (isCurrentSummaryRequest(generation)) setLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    void loadSummaries();
    return () => {
      mountedRef.current = false;
      summaryGenerationRef.current += 1;
      draftGenerationRef.current += 1;
      draftTargetRef.current = null;
      historyGenerationRef.current += 1;
      historyTypeRef.current = null;
    };
  }, []);

  const openDraft = (release: LegalDocumentRelease, generation: number, target: DraftRequestTarget) => {
    if (!isCurrentDraftRequest(generation, target) || !isExpectedDraft(release, target)) return false;
    target.releaseId = release.id;
    setDraft(release);
    setSavedDraft(release);
    setActiveLocale('zh-CN');
    setChangeSummary(release.changeSummary);
    setContents({
      'zh-CN': editableContent(release, 'zh-CN'),
      'en-US': editableContent(release, 'en-US'),
    });
    return true;
  };

  const closeDraft = () => {
    draftGenerationRef.current += 1;
    draftTargetRef.current = null;
    setDraft(null);
    setSavedDraft(null);
    setPreviewOpen(false);
    setPublishOpen(false);
    setSaving(false);
  };

  const openExistingDraft = async (summary: LegalDocumentReleaseSummary) => {
    const target = { documentType: summary.documentType, releaseId: summary.id };
    const generation = ++draftGenerationRef.current;
    draftTargetRef.current = target;
    setDraft(null);
    setSavedDraft(null);
    setPreviewOpen(false);
    setPublishOpen(false);
    setSaving(false);
    try {
      const response = await getLegalDocumentRelease(summary.id);
      openDraft(response, generation, target);
    } catch (error) {
      if (!isCurrentDraftRequest(generation, target)) return;
      message.error(getApiErrorMessage(error, '加载草稿失败'));
    }
  };

  const createDraft = async (type: LegalDocumentType) => {
    const target = { documentType: type };
    const generation = ++draftGenerationRef.current;
    draftTargetRef.current = target;
    setDraft(null);
    setSavedDraft(null);
    setPreviewOpen(false);
    setPublishOpen(false);
    setSaving(false);
    try {
      const response = await createLegalDocumentDraft(type);
      if (!openDraft(response, generation, target)) return;
      await loadSummaries();
    } catch (error) {
      if (!isCurrentDraftRequest(generation, target)) return;
      message.error(getApiErrorMessage(error, '创建草稿失败'));
    }
  };

  const reloadAfterConflict = async (generation: number, target: DraftRequestTarget) => {
    await loadSummaries();
    if (!isCurrentDraftRequest(generation, target) || !target.releaseId) return;
    try {
      const response = await getLegalDocumentRelease(target.releaseId);
      openDraft(response, generation, target);
    } catch {
      if (!isCurrentDraftRequest(generation, target)) return;
      closeDraft();
    }
  };

  const saveDraft = async () => {
    if (!draft) return;
    const target = { documentType: draft.documentType, releaseId: draft.id };
    const generation = ++draftGenerationRef.current;
    draftTargetRef.current = target;
    setSaving(true);
    try {
      const response = await updateLegalDocumentDraft(draft.id, {
        lockVersion: draft.lockVersion,
        changeSummary: changeSummary.trim(),
        contents,
      });
      if (!openDraft(response, generation, target)) return;
      summaryGenerationRef.current += 1;
      setLoading(false);
      setSummaries((current) => current.map((item) => item.documentType === response.documentType
        ? { ...item, draft: releaseSummary(response) }
        : item));
      message.success('草稿已保存');
    } catch (error: any) {
      if (!isCurrentDraftRequest(generation, target)) return;
      if (error?.response?.status === 409) {
        message.warning('草稿已被其他管理员修改，已重新加载最新版本');
        await reloadAfterConflict(generation, target);
      } else {
        message.error(getApiErrorMessage(error, '保存草稿失败'));
      }
    } finally {
      if (isCurrentDraftRequest(generation, target)) setSaving(false);
    }
  };

  const publishDraft = async () => {
    if (!draft) return;
    const target = { documentType: draft.documentType, releaseId: draft.id };
    const generation = ++draftGenerationRef.current;
    draftTargetRef.current = target;
    setSaving(true);
    try {
      await publishLegalDocumentRelease(draft.id, { lockVersion: draft.lockVersion });
      if (!isCurrentDraftRequest(generation, target)) return;
      message.success('协议已发布');
      closeDraft();
      await loadSummaries();
    } catch (error: any) {
      if (!isCurrentDraftRequest(generation, target)) return;
      if (error?.response?.status === 409) {
        setPublishOpen(false);
        message.warning('草稿已被其他管理员修改，已重新加载最新版本');
        await reloadAfterConflict(generation, target);
      } else {
        message.error(getApiErrorMessage(error, '发布失败'));
      }
    } finally {
      if (isCurrentDraftRequest(generation, target)) setSaving(false);
    }
  };

  const openHistory = async (type: LegalDocumentType) => {
    const generation = ++historyGenerationRef.current;
    historyTypeRef.current = type;
    setHistoryOpen(true);
    setHistory([]);
    setHistoryLoading(true);
    try {
      const response = await getLegalDocumentHistory(type);
      if (!isCurrentHistoryRequest(generation, type)) return;
      setHistory(response);
    } catch (error) {
      if (!isCurrentHistoryRequest(generation, type)) return;
      message.error(getApiErrorMessage(error, '加载历史版本失败'));
    } finally {
      if (isCurrentHistoryRequest(generation, type)) setHistoryLoading(false);
    }
  };

  const closeHistory = () => {
    historyGenerationRef.current += 1;
    historyTypeRef.current = null;
    setHistoryOpen(false);
    setHistory([]);
    setHistoryLoading(false);
  };

  const dirty = useMemo(() => !savedDraft || changeSummary !== savedDraft.changeSummary
    || (['zh-CN', 'en-US'] as LegalDocumentLocale[]).some((locale) => {
      const saved = contentFor(savedDraft, locale);
      const current = contents[locale];
      return saved.title !== current.title || saved.contentHtml !== current.contentHtml;
    }), [changeSummary, contents, savedDraft]);
  const publishEnabled = canPublish(savedDraft) && !dirty;
  const savedPreview = savedDraft ? contentFor(savedDraft, activeLocale) : null;
  const activeContent = contents[activeLocale];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2>协议与隐私</h2>
        <p style={{ color: '#666' }}>维护用户协议和隐私政策的双语草稿、发布版本及变更记录。</p>
      </div>
      {loading ? <Spin /> : (
        <Row gutter={[16, 16]}>
          {documentDefinitions.map(({ type, title }) => {
            const summary = summaries.find((item) => item.documentType === type);
            return (
              <Col xs={24} md={12} key={type}>
                <Card title={title} extra={summary?.draft ? <Tag color="gold">草稿 v{summary.draft.version}</Tag> : <Tag>暂无草稿</Tag>}>
                  <p>已发布：{summary?.published ? `v${summary.published.version}` : '暂无'}</p>
                  <Space>
                    {summary?.draft ? (
                      <Button type="primary" onClick={() => void openExistingDraft(summary.draft!)}>编辑{title}草稿</Button>
                    ) : (
                      <Button type="primary" onClick={() => void createDraft(type)}>创建{title}草稿</Button>
                    )}
                    <Button onClick={() => void openHistory(type)}>历史版本</Button>
                  </Space>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}

      <Drawer
        title={draft ? `${draft.documentType === 'privacy-policy' ? '隐私政策' : '用户协议'}草稿` : '协议草稿'}
        open={Boolean(draft)}
        onClose={closeDraft}
        size="large"
        extra={<Space><Button aria-label="预览" onClick={() => setPreviewOpen(true)} disabled={!savedPreview}>预览</Button><Button aria-label="发布" onClick={() => setPublishOpen(true)} disabled={!publishEnabled}>发布</Button><Button type="primary" onClick={() => void saveDraft()} loading={saving}>保存草稿</Button></Space>}
      >
        {draft && <Form layout="vertical">
          {!publishEnabled && canPublish(savedDraft) && <Alert type="info" showIcon title="保存当前修改后才能发布" style={{ marginBottom: 16 }} />}
          <Form.Item label="变更说明" required>
            <Input aria-label="变更说明" value={changeSummary} onChange={(event) => setChangeSummary(event.target.value)} placeholder="请说明本次变更" />
          </Form.Item>
          <Tabs activeKey={activeLocale} onChange={(key) => setActiveLocale(key as LegalDocumentLocale)} items={[
            { key: 'zh-CN', label: '中文' },
            { key: 'en-US', label: 'English' },
          ].map((item) => ({
            ...item,
            children: <>
              <Form.Item label="标题" required>
                <Input aria-label="标题" value={activeContent.title} onChange={(event) => setContents((current) => ({ ...current, [activeLocale]: { ...current[activeLocale], title: event.target.value } }))} />
              </Form.Item>
              <Form.Item label="正文" required>
                <RichTextEditor value={activeContent.contentHtml} onChange={(contentHtml) => setContents((current) => ({ ...current, [activeLocale]: { ...current[activeLocale], contentHtml } }))} toolbarKeys={legalToolbarKeys} placeholder={`请输入${item.label}正文`} height={320} />
              </Form.Item>
            </>,
          }))} />
        </Form>}
      </Drawer>

      <Modal title="协议预览" open={previewOpen} footer={null} onCancel={() => setPreviewOpen(false)} width={780}>
        {savedPreview && <iframe title="协议预览" sandbox="" srcDoc={`<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${previewCsp}"><meta name="viewport" content="width=device-width"></head><body><h1>${escapeHtmlText(savedPreview.title)}</h1>${savedPreview.contentHtml}</body></html>`} style={{ width: '100%', minHeight: 480, border: 0 }} />}
      </Modal>

      {publishOpen && <Modal title="确认发布" open onCancel={() => setPublishOpen(false)} onOk={() => void publishDraft()} okText="确认发布" cancelText="取消" okButtonProps={{ 'aria-label': '确认发布' }} confirmLoading={saving}>
        发布后将立即替换当前生效版本，确认继续吗？
      </Modal>}

      <Drawer title="历史版本" open={historyOpen} onClose={closeHistory} size="large">
        <Table rowKey="id" loading={historyLoading} dataSource={history} pagination={false} columns={[
          { title: '版本', dataIndex: 'version', render: (value) => `v${value}` },
          { title: '状态', dataIndex: 'status', render: (value) => <Tag>{value}</Tag> },
          { title: '变更说明', dataIndex: 'changeSummary' },
          { title: '发布时间', dataIndex: 'publishedAt', render: (value) => value ?? '-' },
          { title: '更新时间', dataIndex: 'updatedAt' },
        ]} />
      </Drawer>
    </div>
  );
}
