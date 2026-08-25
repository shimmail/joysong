import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
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
import type { LegalDocumentRelease } from '../legalDocuments';

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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function draftFor(
  documentType: 'user-agreement' | 'privacy-policy',
  id: string,
  title: string,
  lockVersion = 3,
): LegalDocumentRelease {
  return {
    ...draft,
    id,
    documentType,
    lockVersion,
    contents: [
      { ...draft.contents[0], title },
      { ...draft.contents[1], title: `${title} EN` },
    ],
  };
}

const summary = (
  type: 'user-agreement' | 'privacy-policy',
  currentDraft: LegalDocumentRelease | null = type === 'privacy-policy' ? draft : null,
) => ({
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
  vi.mocked(createLegalDocumentDraft).mockResolvedValue(draftFor('user-agreement', 'agreement-draft', '用户协议'));
  vi.mocked(getLegalDocumentRelease).mockResolvedValue(draft);
  vi.mocked(updateLegalDocumentDraft).mockResolvedValue({ ...draft, lockVersion: 4, changeSummary: '修订条款' });
  vi.mocked(publishLegalDocumentRelease).mockResolvedValue({ ...draft, status: 'PUBLISHED', publishedAt: '2026-08-26T10:00:00' });
  vi.mocked(getLegalDocumentHistory).mockResolvedValue([{ ...summary('privacy-policy').draft!, status: 'PUBLISHED', publishedAt: '2026-08-26T10:00:00' }]);
});

afterEach(() => {
  cleanup();
  message.destroy();
  vi.restoreAllMocks();
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

  it('renders a hostile saved title only as literal preview text under a restrictive CSP', async () => {
    const user = userEvent.setup();
    const hostileTitle = `</h1><script>window.pwned=1</script><img src="https://evil.example/pixel" onerror="window.pwned=2"><form action="https://evil.example/submit"><input></form>&amp;&quot; " '`;
    vi.mocked(getLegalDocumentRelease).mockResolvedValueOnce({
      ...draft,
      contents: [
        { ...draft.contents[0], title: hostileTitle },
        draft.contents[1],
      ],
    });
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: /预览/ }));

    const frame = await screen.findByTitle('协议预览');
    const srcDoc = frame.getAttribute('srcdoc')!;
    const previewDocument = new DOMParser().parseFromString(srcDoc, 'text/html');
    expect(frame).toHaveAttribute('sandbox', '');
    expect(previewDocument.querySelector('meta[http-equiv="Content-Security-Policy"]')?.getAttribute('content')).toBe(
      "default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-src 'none'",
    );
    expect(previewDocument.querySelector('h1')?.textContent).toBe(hostileTitle);
    expect(previewDocument.querySelectorAll('script, img, form, input, base, iframe, object')).toHaveLength(0);
    expect(previewDocument.querySelector('[onerror], [onclick], [src], [href], [action]')).toBeNull();
  });

  it('clears previously loaded rows when another document history fails to load', async () => {
    const user = userEvent.setup();
    vi.mocked(getLegalDocumentHistory).mockImplementation((type) => type === 'privacy-policy'
      ? Promise.resolve([{ ...summary('privacy-policy').draft!, status: 'PUBLISHED', publishedAt: '2026-08-26T10:00:00' }])
      : Promise.reject(new Error('历史版本不可用')));
    render(<LegalDocumentsPage />);

    await user.click((await screen.findAllByRole('button', { name: '历史版本' }))[1]);
    expect(await screen.findByText('PUBLISHED')).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Close' }).at(-1)!);

    await user.click(screen.getAllByRole('button', { name: '历史版本' })[0]);
    await waitFor(() => expect(getLegalDocumentHistory).toHaveBeenLastCalledWith('user-agreement'));
    expect(screen.queryByText('PUBLISHED')).not.toBeInTheDocument();
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

  it('does not reopen a draft when save finishes after the drawer is closed', async () => {
    const user = userEvent.setup();
    const save = deferred<typeof draft>();
    vi.mocked(updateLegalDocumentDraft).mockReturnValueOnce(save.promise);
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: '保存草稿' }));
    await user.click(screen.getAllByRole('button', { name: 'Close' })[0]);
    expect(screen.queryByRole('textbox', { name: '变更说明' })).not.toBeInTheDocument();

    await act(async () => save.resolve({ ...draft, lockVersion: 4 }));

    expect(screen.queryByRole('textbox', { name: '变更说明' })).not.toBeInTheDocument();
  });

  it('keeps the latest draft when different document loads resolve out of order', async () => {
    const user = userEvent.setup();
    const privacyLoad = deferred<LegalDocumentRelease>();
    const userAgreementLoad = deferred<LegalDocumentRelease>();
    const userAgreementDraft = draftFor('user-agreement', 'agreement-draft', '用户协议新稿');
    vi.mocked(listLegalDocuments).mockResolvedValueOnce([
      summary('user-agreement', userAgreementDraft),
      summary('privacy-policy'),
    ]);
    vi.mocked(getLegalDocumentRelease).mockImplementation((id) => (
      id === 'privacy-draft' ? privacyLoad.promise : userAgreementLoad.promise
    ));
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: '编辑用户协议草稿' }));
    await act(async () => userAgreementLoad.resolve(userAgreementDraft));
    expect(await screen.findByDisplayValue('用户协议新稿')).toBeInTheDocument();

    await act(async () => privacyLoad.resolve(draft));

    expect(screen.getByDisplayValue('用户协议新稿')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('隐私政策')).not.toBeInTheDocument();
  });

  it('keeps the latest history when closed and crossed requests resolve out of order', async () => {
    const user = userEvent.setup();
    const privacyHistory = deferred<Awaited<ReturnType<typeof getLegalDocumentHistory>>>();
    const agreementHistory = deferred<Awaited<ReturnType<typeof getLegalDocumentHistory>>>();
    vi.mocked(getLegalDocumentHistory).mockImplementation((type) => (
      type === 'privacy-policy' ? privacyHistory.promise : agreementHistory.promise
    ));
    render(<LegalDocumentsPage />);

    await user.click((await screen.findAllByRole('button', { name: '历史版本' }))[1]);
    const firstHistoryDrawer = await screen.findByRole('dialog', { name: '历史版本' });
    await user.click(within(firstHistoryDrawer).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '历史版本' })).not.toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: '历史版本' })[0]);
    await act(async () => agreementHistory.resolve([{
      ...summary('user-agreement', draftFor('user-agreement', 'agreement-draft', '用户协议')).draft!,
      version: 8,
      status: 'PUBLISHED',
      changeSummary: 'latest agreement history',
    }]));
    expect(await screen.findByText('latest agreement history')).toBeInTheDocument();

    await act(async () => privacyHistory.resolve([{
      ...summary('privacy-policy').draft!,
      version: 99,
      status: 'PUBLISHED',
      changeSummary: 'stale privacy history',
    }]));

    expect(screen.getByText('latest agreement history')).toBeInTheDocument();
    expect(screen.queryByText('stale privacy history')).not.toBeInTheDocument();
  });

  it('does not emit save completion side effects after unmounting with a pending request', async () => {
    const user = userEvent.setup();
    const save = deferred<typeof draft>();
    const success = vi.spyOn(message, 'success');
    vi.mocked(updateLegalDocumentDraft).mockReturnValueOnce(save.promise);
    const view = render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: '保存草稿' }));
    view.unmount();
    await act(async () => save.resolve({ ...draft, lockVersion: 4 }));

    expect(success).not.toHaveBeenCalled();
  });

  it('closes a stale publish confirmation during a 409 reload and requires fresh confirmation', async () => {
    const user = userEvent.setup();
    const conflictedPublish = deferred<Awaited<ReturnType<typeof publishLegalDocumentRelease>>>();
    const summaryReload = deferred<Awaited<ReturnType<typeof listLegalDocuments>>>();
    const latestDraft = { ...draft, lockVersion: 9, changeSummary: '其他管理员已更新' };
    vi.mocked(publishLegalDocumentRelease)
      .mockReturnValueOnce(conflictedPublish.promise)
      .mockResolvedValueOnce({ ...latestDraft, status: 'PUBLISHED', publishedAt: '2026-08-26T11:00:00' });
    vi.mocked(listLegalDocuments)
      .mockResolvedValueOnce([summary('user-agreement'), summary('privacy-policy')])
      .mockReturnValueOnce(summaryReload.promise)
      .mockResolvedValue([summary('user-agreement'), summary('privacy-policy', latestDraft)]);
    vi.mocked(getLegalDocumentRelease)
      .mockResolvedValueOnce(draft)
      .mockResolvedValueOnce(latestDraft);
    render(<LegalDocumentsPage />);

    await user.click(await screen.findByRole('button', { name: '编辑隐私政策草稿' }));
    await user.click(screen.getByRole('button', { name: /发布/ }));
    const confirmation = screen.getAllByText('确认发布')
      .find((element) => element.classList.contains('ant-modal-title'))
      ?.closest('.ant-modal') as HTMLElement;
    await user.click(within(confirmation).getByRole('button', { name: '确认发布' }));
    await act(async () => conflictedPublish.reject({ response: { status: 409 } }));

    await waitFor(() => expect(screen.queryByRole('button', { name: '确认发布' })).not.toBeInTheDocument());
    expect(publishLegalDocumentRelease).toHaveBeenCalledTimes(1);
    await act(async () => summaryReload.resolve([summary('user-agreement'), summary('privacy-policy', latestDraft)]));
    await waitFor(() => expect(screen.getByDisplayValue('其他管理员已更新')).toBeInTheDocument());
    expect(publishLegalDocumentRelease).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: /发布/ }));
    const freshConfirmation = screen.getAllByText('确认发布')
      .find((element) => element.classList.contains('ant-modal-title'))
      ?.closest('.ant-modal') as HTMLElement;
    await user.click(within(freshConfirmation).getByRole('button', { name: '确认发布' }));
    await waitFor(() => expect(publishLegalDocumentRelease).toHaveBeenLastCalledWith('privacy-draft', { lockVersion: 9 }));
  });
});
