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

vi.mock('./api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
  getData: (response: { data: unknown }) => response.data,
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

describe('legal document admin client', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mockedApi.get.mockResolvedValue({ data: release });
    mockedApi.post.mockResolvedValue({ data: release });
    mockedApi.put.mockResolvedValue({ data: release });
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

    await listLegalDocuments();
    await getLegalDocumentHistory('privacy-policy');
    await createLegalDocumentDraft('privacy-policy');
    await getLegalDocumentRelease('draft-1');
    await updateLegalDocumentDraft('draft-1', update);
    await publishLegalDocumentRelease('draft-1', { lockVersion: 2 });

    expect(mockedApi.get).toHaveBeenNthCalledWith(1, '/admin/legal-documents');
    expect(mockedApi.get).toHaveBeenNthCalledWith(2, '/admin/legal-documents/privacy-policy/history');
    expect(mockedApi.post).toHaveBeenNthCalledWith(1, '/admin/legal-documents/privacy-policy/draft');
    expect(mockedApi.get).toHaveBeenNthCalledWith(3, '/admin/legal-documents/releases/draft-1');
    expect(mockedApi.put).toHaveBeenCalledWith('/admin/legal-documents/releases/draft-1', update);
    expect(mockedApi.post).toHaveBeenNthCalledWith(2, '/admin/legal-documents/releases/draft-1/publish', { lockVersion: 2 });
  });
});
