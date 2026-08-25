import api, { getData } from './api';

export type LegalDocumentType = 'user-agreement' | 'privacy-policy';
export type LegalDocumentLocale = 'zh-CN' | 'en-US';
export type LegalDocumentStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED';

export interface LegalDocumentContent {
  locale: LegalDocumentLocale;
  title: string;
  contentHtml: string;
  contentSha256: string;
}

export interface LegalDocumentReleaseSummary {
  id: string;
  documentType: LegalDocumentType;
  version: number;
  status: LegalDocumentStatus;
  changeSummary: string;
  publishedAt: string | null;
  updatedAt: string;
  lockVersion: number;
}

export interface LegalDocumentSummary {
  documentType: LegalDocumentType;
  draft: LegalDocumentReleaseSummary | null;
  published: LegalDocumentReleaseSummary | null;
}

export interface LegalDocumentRelease extends LegalDocumentReleaseSummary {
  publishedBy: string | null;
  createdAt: string;
  contents: LegalDocumentContent[];
}

export interface UpdateLegalDocumentDraftInput {
  lockVersion: number;
  changeSummary: string;
  contents: Record<LegalDocumentLocale, Pick<LegalDocumentContent, 'title' | 'contentHtml'>>;
}

export interface PublishLegalDocumentReleaseInput {
  lockVersion: number;
}

export async function listLegalDocuments(): Promise<LegalDocumentSummary[]> {
  return getData(await api.get('/admin/legal-documents'));
}

export async function getLegalDocumentHistory(type: LegalDocumentType): Promise<LegalDocumentReleaseSummary[]> {
  return getData(await api.get(`/admin/legal-documents/${type}/history`));
}

export async function createLegalDocumentDraft(type: LegalDocumentType): Promise<LegalDocumentRelease> {
  return getData(await api.post(`/admin/legal-documents/${type}/draft`));
}

export async function getLegalDocumentRelease(id: string): Promise<LegalDocumentRelease> {
  return getData(await api.get(`/admin/legal-documents/releases/${id}`));
}

export async function updateLegalDocumentDraft(
  id: string,
  input: UpdateLegalDocumentDraftInput,
): Promise<LegalDocumentRelease> {
  return getData(await api.put(`/admin/legal-documents/releases/${id}`, input));
}

export async function publishLegalDocumentRelease(
  id: string,
  input: PublishLegalDocumentReleaseInput,
): Promise<LegalDocumentRelease> {
  return getData(await api.post(`/admin/legal-documents/releases/${id}/publish`, input));
}
