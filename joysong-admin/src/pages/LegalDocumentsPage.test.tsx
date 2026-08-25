import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import LegalDocumentsPage from './LegalDocumentsPage';
import {
  createLegalDocumentDraft,
  getLegalDocumentHistory,
  getLegalDocumentRelease,
  listLegalDocuments,
  publishLegalDocumentRelease,
  updateLegalDocumentDraft,
} from '../legalDocuments';

vi.mock('../legalDocuments');
vi.mock('../components/RichTextEditor', () => ({
  default: ({ value, onChange }: { value?: string; onChange?: (value: string) => void }) => (
    <textarea aria-label="正文" value={value} onChange={(event) => onChange?.(event.target.value)} />
  ),
}));

const draft = {
  id: 'privacy-draft',
  documentType: 'privacy-policy' as const,
  version: 2,
  status: 'DRAFT' as const,
  changeSummary: '',
  publishedAt: null,
  publishedBy: null,
  createdAt: '2026-08-26T08:00:00',
  updatedAt: '2026-08-26T09:00:00',
  lockVersion: 3,
  contents: [
    { locale: 'zh-CN' as const, title: '隐私政策', contentHtml: '<p>中文正文</p>', contentSha256: 'zh' },
    { locale: 'en-US' as const, title: 'Privacy Policy', contentHtml: '<p>English body</p>', contentSha256: 'en' },
  ],
};

const summary = (type: 'user-agreement' | 'privacy-policy', currentDraft = type === 'privacy-policy' ? draft : null) => ({
  documentType: type,
  draft: currentDraft ? {
    id: currentDraft.id,
    documentType: currentDraft.documentType,
    version: currentDraft.version,
    status: currentDraft.status,
    changeSummary: currentDraft.changeSummary,
    publishedAt: currentDraft.publishedAt,
    updatedAt: currentDraft.updatedAt,
    lockVersion: currentDraft.lockVersion,
  } : null,
  published: null,
});

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  vi.mocked(listLegalDocuments).mockResolvedValue([summary('user-agreement'), summary('privacy-policy')]);
  vi.mocked(createLegalDocumentDraft).mockResolvedValue(draft);
  vi.mocked(getLegalDocumentRelease).mockResolvedValue(draft);
  vi.mocked(updateLegalDocumentDraft).mockResolvedValue({ ...draft, lockVersion: 4, changeSummary: '修订条款' });
  vi.mocked(publishLegalDocumentRelease).mockResolvedValue({ ...draft, status: 'PUBLISHED', publishedAt: '2026-08-26T10:00:00' });
  vi.mocked(getLegalDocumentHistory).mockResolvedValue([{ ...summary('privacy-policy').draft!, status: 'PUBLISHED', publishedAt: '2026-08-26T10:00:00' }]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('LegalDocumentsPage', () => {
  it('shows two document cards and creates a missing draft into bilingual editing', async () => {
    const user = userEvent.setup();
    render(<LegalDocumentsPage />);

    expect(await screen.findByText('用户协议')).toBeInTheDocument();
    expect(screen.getByText('隐私政策')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '创建用户协议草稿' }));

    expect(createLegalDocumentDraft).toHaveBeenCalledWith('user-agreement');
    expect(await screen.findByRole('tab', { name: '中文' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'English' })).toBeVisible();
  });

  it('saves both locales with the newest server lock version and only enables publishing after complete saved content', async () => {
    const user = userEvent.setup();
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    expect(screen.getByRole('button', { name: /发布/ })).toBeEnabled();
    await user.clear(screen.getByLabelText('变更说明'));
    await user.type(screen.getByLabelText('变更说明'), '修订条款');
    await user.click(screen.getByRole('tab', { name: 'English' }));
    const englishPanel = screen.getByRole('tabpanel', { name: 'English' });
    await user.clear(within(englishPanel).getByLabelText('标题'));
    await user.type(within(englishPanel).getByLabelText('标题'), 'Updated Privacy Policy');
    await user.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => expect(updateLegalDocumentDraft).toHaveBeenCalledWith('privacy-draft', {
      lockVersion: 3,
      changeSummary: '修订条款',
      contents: {
        'zh-CN': { title: '隐私政策', contentHtml: '<p>中文正文</p>' },
        'en-US': { title: 'Updated Privacy Policy', contentHtml: '<p>English body</p>' },
      },
    }));
  });

  it('uses an inert iframe preview, asks for publication confirmation, and shows read-only history', async () => {
    const user = userEvent.setup();
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: /预览/ }));
    const frame = await screen.findByTitle('协议预览');
    expect(frame).toHaveAttribute('sandbox', '');
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining('<p>中文正文</p>'));
    await user.click(screen.getByRole('button', { name: /发布/ }));
    const dialog = screen.getAllByText('确认发布')
      .find((element) => element.classList.contains('ant-modal-title'))
      ?.closest('.ant-modal') as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: '确认发布' }));
    await waitFor(() => expect(publishLegalDocumentRelease).toHaveBeenCalledWith('privacy-draft', { lockVersion: 3 }));

    await user.click(screen.getAllByRole('button', { name: '历史版本' })[1]);
    expect(await screen.findByText('PUBLISHED')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '变更说明' })).not.toBeInTheDocument();
  });

  it('reloads the latest state after a version conflict', async () => {
    const user = userEvent.setup();
    vi.mocked(updateLegalDocumentDraft).mockRejectedValueOnce({ response: { status: 409 } });
    render(<LegalDocumentsPage />);
    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.type(screen.getByLabelText('变更说明'), '更新');
    await user.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => expect(listLegalDocuments).toHaveBeenCalledTimes(2));
  });
});
