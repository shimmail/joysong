import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import {
  createLegalDocumentDraft,
  getLegalDocumentHistory,
  getLegalDocumentRelease,
  listLegalDocuments,
  publishLegalDocumentRelease,
  updateLegalDocumentDraft,
} from './legalDocuments';

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const mockedApi = vi.mocked(api);
const release = {
  id: 'draft-1',
  documentType: 'privacy-policy',
  version: 2,
  status: 'DRAFT',
  changeSummary: '更新联系方式',
  publishedAt: null,
  publishedBy: null,
  createdAt: '2026-08-26T08:00:00',
  updatedAt: '2026-08-26T09:00:00',
  lockVersion: 2,
  contents: [{
    locale: 'zh-CN',
    title: '隐私政策',
    contentHtml: '<p>中文</p>',
    contentSha256: 'hash-zh',
  }],
};
const releaseSummary = {
  id: release.id,
  documentType: release.documentType,
  version: release.version,
  status: release.status,
  changeSummary: release.changeSummary,
  publishedAt: release.publishedAt,
  updatedAt: release.updatedAt,
  lockVersion: release.lockVersion,
};
const list = [{ documentType: 'privacy-policy', draft: releaseSummary, published: null }];
const envelope = <T,>(data: T) => ({ data: { code: 200, message: 'OK', data } });

describe('legal document admin client', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mockedApi.get.mockResolvedValueOnce(envelope(list));
    mockedApi.get.mockResolvedValueOnce(envelope([releaseSummary]));
    mockedApi.get.mockResolvedValueOnce(envelope(release));
    mockedApi.post.mockResolvedValueOnce(envelope(release));
    mockedApi.post.mockResolvedValueOnce(envelope(release));
    mockedApi.put.mockResolvedValue(envelope(release));
  });

  it('maps every legal document operation to its admin endpoint and exact request body', async () => {
    const update = {
      lockVersion: 2,
      changeSummary: '更新联系方式',
      contents: {
        'zh-CN': { title: '隐私政策', contentHtml: '<p>中文</p>' },
        'en-US': { title: 'Privacy Policy', contentHtml: '<p>English</p>' },
      },
    };

    expect(await listLegalDocuments()).toEqual(list);
    expect(await getLegalDocumentHistory('privacy-policy')).toEqual([releaseSummary]);
    expect(await createLegalDocumentDraft('privacy-policy')).toEqual(release);
    expect(await getLegalDocumentRelease('draft-1')).toEqual(release);
    expect(await updateLegalDocumentDraft('draft-1', update)).toEqual(release);
    expect(await publishLegalDocumentRelease('draft-1', { lockVersion: 2 })).toEqual(release);

    expect(mockedApi.get).toHaveBeenNthCalledWith(1, '/admin/legal-documents');
    expect(mockedApi.get).toHaveBeenNthCalledWith(2, '/admin/legal-documents/privacy-policy/history');
    expect(mockedApi.post).toHaveBeenNthCalledWith(1, '/admin/legal-documents/privacy-policy/draft');
    expect(mockedApi.get).toHaveBeenNthCalledWith(3, '/admin/legal-documents/releases/draft-1');
    expect(mockedApi.put).toHaveBeenCalledWith('/admin/legal-documents/releases/draft-1', update);
    expect(mockedApi.post).toHaveBeenNthCalledWith(2, '/admin/legal-documents/releases/draft-1/publish', { lockVersion: 2 });
  });
});
