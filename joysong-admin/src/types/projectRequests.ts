export type ProfessionalProjectRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';
export type DoctorProjectChangeStatus = ProfessionalProjectRequestStatus | 'WITHDRAWN';
export type ProjectCurrency = 'CNY' | 'USD';

export interface InstitutionProjectSplit {
  consultationFee: number;
  commissionRate: number;
  institutionRate: number;
  platformRate: number;
  doctorRate: number;
}

export interface ProfessionalProjectRequestResponse {
  id: string;
  requestType: 'PLATFORM' | 'INSTITUTION';
  doctorId: string;
  doctorName: string;
  institutionId: string | null;
  institutionName: string | null;
  projectId: string | null;
  projectName: string | null;
  name: string | null;
  category: string | null;
  description: string | null;
  tags: string[] | null;
  slogan: string | null;
  detailContent: string | null;
  currency: ProjectCurrency;
  coverImage: string | null;
  images: string[] | null;
  salesCount: number;
  referencePrice: number | null;
  categoryTags: string[] | null;
  price: number | null;
  originalPrice: number | null;
  isActive: boolean | null;
  institutionSplit: InstitutionProjectSplit | null;
  notes: string | null;
  status: ProfessionalProjectRequestStatus;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  resultingProjectId: string | null;
  resultingInstitutionProjectId: string | null;
  submittedAt: string;
  updatedAt: string;
}

export type PlatformProjectRequest = ProfessionalProjectRequestResponse & {
  requestType: 'PLATFORM';
  institutionId: null;
  institutionName: null;
  projectId: null;
  projectName: null;
  name: string;
  category: string;
  description: string;
  tags: string[];
  slogan: string;
  coverImage: string;
  images: string[];
  referencePrice: number;
  categoryTags: string[];
  price: null;
  originalPrice: null;
  isActive: null;
  institutionSplit: null;
};

export type InstitutionProjectRequest = ProfessionalProjectRequestResponse & {
  requestType: 'INSTITUTION';
  institutionId: string;
  institutionName: string | null;
  projectId: string;
  projectName: string | null;
  referencePrice: null;
  categoryTags: null;
  price: number;
  isActive: boolean;
  institutionSplit: InstitutionProjectSplit;
};

export type CompleteProfessionalProjectRequestResponse = PlatformProjectRequest | InstitutionProjectRequest;
export type ProfessionalProjectRequest = CompleteProfessionalProjectRequestResponse & { requestSource: 'PROFESSIONAL' };

export interface InstitutionProjectSnapshotV2 {
  schemaVersion: 2;
  association: {
    institutionProjectId: string;
    institutionId: string;
    platformProjectId: string;
  };
  rawOverrides: {
    name: string | null;
    category: string | null;
    description: string | null;
    tags: string[] | null;
    slogan: string | null;
    detailContent: string | null;
    coverImage: string | null;
    images: string[] | null;
  };
  effective: {
    name: string;
    category: string;
    description: string | null;
    tags: string[];
    slogan: string | null;
    detailContent: string | null;
    salesCount: number;
    coverImage: string | null;
    images: string[];
  };
  source: {
    institutionProjectVersion: number;
    platformInheritanceHash: string;
  };
}

export interface DoctorProjectChangeTargetV2 {
  payloadVersion: 2;
  institutionProjectId: string;
  institutionId: string;
  institutionName: string;
  platformProjectId: string;
  platformProjectName: string;
  doctorId: string;
  doctorName: string;
  baseRevision: string;
  currentProject: InstitutionProjectSnapshotV2;
  currentDoctorPrice: number;
  currentDoctorActive: boolean;
  platformRate: number;
  pricingPolicyRevision: string;
  travelGroundServiceFee: number;
}

interface ParsedRequestBase {
  id: string;
  requestType: string;
  doctorId: string;
  doctorName: string;
  institutionId: string;
  institutionName: string;
  institutionProjectId: string;
  reviewable: boolean;
  parseIssue: string | null;
}

export interface DoctorProjectChangeRequestV1 extends ParsedRequestBase {
  kind: 'V1';
  payloadVersion: 1;
  projectName: string;
  serviceDescription: string;
  priceSuggestion: number | null;
  notes: string | null;
  serviceTags: string[];
  scheduleNote: string | null;
  coverImage: string | null;
  images: string[];
  consultationFee?: number | null;
  commissionRate?: number | null;
  institutionRate?: number | null;
  medicalListPrice?: number | null;
  platformRate?: number | null;
  doctorRate?: number | null;
  currentPrice?: number | null;
  currentServiceDescription?: string | null;
  currentServiceTags?: string[] | null;
  currentScheduleNote?: string | null;
  currentCoverImage?: string | null;
  currentImages?: string[] | null;
  currentConsultationFee?: number | null;
  currentMedicalListPrice?: number | null;
  currentCommissionRate?: number | null;
  currentInstitutionRate?: number | null;
  currentPlatformRate?: number | null;
  currentDoctorRate?: number | null;
  forceProcessed?: boolean;
  status: DoctorProjectChangeStatus;
  submittedBy?: string;
  reviewedBy?: string | null;
  reviewerName?: string | null;
  reviewNote?: string | null;
  submittedAt?: string | null;
  reviewedAt?: string | null;
  updatedAt?: string | null;
}

export interface DoctorProjectChangeRequestV2 extends ParsedRequestBase {
  kind: 'V2';
  payloadVersion: 2;
  institutionProjectName: string;
  platformProjectId: string;
  platformProjectName: string;
  baseRevision: string;
  currentProject: InstitutionProjectSnapshotV2 | null;
  proposedProject: InstitutionProjectSnapshotV2 | null;
  latestProject: InstitutionProjectSnapshotV2 | null;
  latestRevision: string | null;
  sharedChanged: boolean;
  currentDoctorPrice: number;
  proposedDoctorPrice: number;
  latestDoctorPrice: number | null;
  currentDoctorActive: boolean;
  proposedDoctorActive: boolean;
  latestDoctorActive: boolean | null;
  platformRate: number;
  pricingPolicyRevision: string;
  travelGroundServiceFee: number;
  requestStatus: DoctorProjectChangeStatus;
  notes: string;
  forceProcessed: boolean;
  submittedBy: string;
  submittedAt: string;
  reviewedBy: string | null;
  reviewerName: string | null;
  reviewNote: string | null;
  reviewedAt: string | null;
  updatedAt: string;
  snapshotState: 'VALID' | 'INVALID';
  snapshotError: string | null;
}

export interface DamagedDoctorProjectChangeRequest extends ParsedRequestBase {
  kind: 'DAMAGED';
  payloadVersion: number | null;
  projectName: string;
  requestStatus: string;
  reviewable: false;
  parseIssue: 'MALFORMED_V1_PAYLOAD' | 'MALFORMED_V2_PAYLOAD' | 'UNSUPPORTED_PAYLOAD_VERSION';
}

export type ParsedDoctorProjectChangeRequest =
  | DoctorProjectChangeRequestV1
  | DoctorProjectChangeRequestV2
  | DamagedDoctorProjectChangeRequest;

export interface InstitutionProjectPreviewModel {
  name: string;
  category: string | null;
  description: string | null;
  tags: string[];
  slogan: string | null;
  detailContent: string | null;
  coverImage: string | null;
  images: string[];
  salesCount: number | null;
  currency: ProjectCurrency;
  price: number | null;
  originalPrice: number | null;
  active: boolean | null;
  travelGroundServiceFee: number | null;
  scheduleNote?: string | null;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const hasOwn = (value: Record<string, unknown>, key: string) => Object.prototype.hasOwnProperty.call(value, key);
const hasExactKeys = (value: Record<string, unknown>, keys: readonly string[]) => {
  const actual = Object.keys(value);
  return actual.length === keys.length && keys.every(key => hasOwn(value, key));
};
const isString = (value: unknown): value is string => typeof value === 'string';
const isNonBlankString = (value: unknown): value is string => isString(value) && value.trim().length > 0;
const isNullableString = (value: unknown): value is string | null => value === null || isString(value);
const isStringList = (value: unknown): value is string[] => Array.isArray(value) && value.every(isString);
const isNullableStringList = (value: unknown): value is string[] | null => value === null || isStringList(value);
const isFiniteNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);
const isNullableNumber = (value: unknown): value is number | null => value === null || isFiniteNumber(value);
const isNullableBoolean = (value: unknown): value is boolean | null => value === null || typeof value === 'boolean';
const isBoundedString = (value: unknown, maximumLength: number): value is string =>
  isString(value) && value.length <= maximumLength;
const isNullableBoundedString = (value: unknown, maximumLength: number): value is string | null =>
  value === null || isBoundedString(value, maximumLength);
const isNormalizedText = (value: unknown, maximumLength: number, allowEmpty = false): value is string =>
  isString(value) && value.length <= maximumLength && value.trim() === value && (allowEmpty || value.length > 0);
const isNullableNormalizedText = (value: unknown, maximumLength: number): value is string | null =>
  value === null || isNormalizedText(value, maximumLength);
const isIdentifier = (value: unknown): value is string => isNormalizedText(value, 36);
const isNullableIdentifier = (value: unknown): value is string | null => value === null || isIdentifier(value);
const isBoundedStringList = (value: unknown, maximumItems: number, maximumItemLength: number, maximumJoinedLength: number): value is string[] =>
  Array.isArray(value) && value.length <= maximumItems
  && value.every(item => isNormalizedText(item, maximumItemLength))
  && value.join(',').length <= maximumJoinedLength;
const isNullableBoundedStringList = (value: unknown, maximumItems: number, maximumItemLength: number, maximumJoinedLength: number): value is string[] | null =>
  value === null || isBoundedStringList(value, maximumItems, maximumItemLength, maximumJoinedLength);
const toHundredths = (value: unknown, minimum: number, maximum: number) => {
  if (!isFiniteNumber(value) || value < minimum || value > maximum) return null;
  const scaled = value * 100;
  const rounded = Math.round(scaled);
  const tolerance = Math.max(1e-9, Number.EPSILON * Math.max(1, Math.abs(scaled)) * 4);
  return Math.abs(scaled - rounded) <= tolerance ? rounded : null;
};
const isMoney = (value: unknown): value is number => toHundredths(value, 0, 99_999_999.99) !== null;
const isNullableMoney = (value: unknown): value is number | null => value === null || isMoney(value);
const isProfessionalStatus = (value: unknown): value is ProfessionalProjectRequestStatus =>
  isString(value) && ['PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED'].includes(value);
const isIsoLocalDateTime = (value: unknown): value is string => {
  if (!isString(value)) return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) return false;
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= daysInMonth[month - 1];
};
const isNullableIsoLocalDateTime = (value: unknown): value is string | null => value === null || isIsoLocalDateTime(value);
const hasValidField = (value: Record<string, unknown>, key: string, predicate: (field: unknown) => boolean) =>
  hasOwn(value, key) && predicate(value[key]);

function isCompleteSplit(value: unknown): value is InstitutionProjectSplit {
  if (!isRecord(value)) return false;
  const consultationFee = toHundredths(value.consultationFee, 0, 99_999_999.99);
  const commissionRate = toHundredths(value.commissionRate, 0, 100);
  const institutionRate = toHundredths(value.institutionRate, 0, 100);
  const platformRate = toHundredths(value.platformRate, 0, 100);
  const doctorRate = toHundredths(value.doctorRate, -100, 100);
  return consultationFee !== null && commissionRate !== null && institutionRate !== null
    && platformRate !== null && doctorRate !== null
    && commissionRate + institutionRate <= 10_000
    && commissionRate + institutionRate + platformRate + doctorRate === 10_000;
}

export function isCompleteProfessionalProjectRequest(value: unknown): value is CompleteProfessionalProjectRequestResponse {
  if (!isRecord(value)) return false;
  const common = hasValidField(value, 'requestType', field => field === 'PLATFORM' || field === 'INSTITUTION')
    && hasValidField(value, 'id', isIdentifier)
    && hasValidField(value, 'doctorId', isIdentifier)
    && hasValidField(value, 'doctorName', field => isBoundedString(field, 100))
    && hasValidField(value, 'institutionId', isNullableIdentifier)
    && hasValidField(value, 'institutionName', field => isNullableBoundedString(field, 200))
    && hasValidField(value, 'projectId', isNullableIdentifier)
    && hasValidField(value, 'projectName', field => isNullableBoundedString(field, 200))
    && hasValidField(value, 'currency', field => field === 'CNY' || field === 'USD')
    && hasValidField(value, 'notes', field => isNullableNormalizedText(field, 2_000))
    && hasValidField(value, 'reviewNote', field => isNullableNormalizedText(field, 1_000))
    && hasValidField(value, 'reviewedBy', isNullableIdentifier)
    && hasValidField(value, 'reviewedAt', isNullableIsoLocalDateTime)
    && hasValidField(value, 'resultingProjectId', isNullableIdentifier)
    && hasValidField(value, 'resultingInstitutionProjectId', isNullableIdentifier)
    && hasValidField(value, 'submittedAt', isIsoLocalDateTime)
    && hasValidField(value, 'updatedAt', isIsoLocalDateTime)
    && hasValidField(value, 'referencePrice', isNullableMoney)
    && hasValidField(value, 'price', isNullableMoney)
    && hasValidField(value, 'originalPrice', isNullableMoney)
    && hasValidField(value, 'salesCount', field => isFiniteNumber(field) && Number.isInteger(field) && field >= 0 && field <= 2_147_483_647)
    && hasValidField(value, 'status', isProfessionalStatus)
    && hasValidField(value, 'isActive', field => field === null || typeof field === 'boolean')
    && hasValidField(value, 'institutionSplit', field => field === null || isCompleteSplit(field));
  if (!common) return false;

  if (value.requestType === 'PLATFORM') {
    return value.institutionId === null && value.institutionName === null
      && value.projectId === null && value.projectName === null
      && hasValidField(value, 'name', field => isNormalizedText(field, 200))
      && hasValidField(value, 'category', field => isNormalizedText(field, 100))
      && hasValidField(value, 'description', field => isNormalizedText(field, 5_000))
      && hasValidField(value, 'tags', field => isBoundedStringList(field, 20, 100, 500))
      && hasValidField(value, 'slogan', field => isNormalizedText(field, 500, true))
      && hasValidField(value, 'detailContent', field => isNullableNormalizedText(field, 20_000))
      && hasValidField(value, 'coverImage', field => isNormalizedText(field, 500, true))
      && hasValidField(value, 'images', field => isBoundedStringList(field, 20, 500, 2_000))
      && isMoney(value.referencePrice)
      && hasValidField(value, 'categoryTags', field => isBoundedStringList(field, 20, 100, 500))
      && value.price === null && value.originalPrice === null && value.isActive === null && value.institutionSplit === null;
  }

  return value.requestType === 'INSTITUTION' && isIdentifier(value.institutionId) && isIdentifier(value.projectId)
    && hasValidField(value, 'name', field => isNullableNormalizedText(field, 200))
    && hasValidField(value, 'category', field => isNullableNormalizedText(field, 100))
    && hasValidField(value, 'description', field => isNullableNormalizedText(field, 5_000))
    && hasValidField(value, 'tags', field => isNullableBoundedStringList(field, 20, 100, 500))
    && hasValidField(value, 'slogan', field => isNullableNormalizedText(field, 500))
    && hasValidField(value, 'detailContent', field => isNullableNormalizedText(field, 20_000))
    && hasValidField(value, 'coverImage', field => isNullableNormalizedText(field, 500))
    && hasValidField(value, 'images', field => isNullableBoundedStringList(field, 20, 500, 2_000))
    && value.referencePrice === null && value.categoryTags === null
    && isMoney(value.price) && typeof value.isActive === 'boolean' && isCompleteSplit(value.institutionSplit);
}

const snapshotKeys = ['schemaVersion', 'association', 'rawOverrides', 'effective', 'source'] as const;
const associationKeys = ['institutionProjectId', 'institutionId', 'platformProjectId'] as const;
const overrideKeys = ['name', 'category', 'description', 'tags', 'slogan', 'detailContent', 'coverImage', 'images'] as const;
const effectiveKeys = ['name', 'category', 'description', 'tags', 'slogan', 'detailContent', 'salesCount', 'coverImage', 'images'] as const;
const sourceKeys = ['institutionProjectVersion', 'platformInheritanceHash'] as const;

function isInstitutionProjectSnapshotV2(value: unknown): value is InstitutionProjectSnapshotV2 {
  if (!isRecord(value) || !hasExactKeys(value, snapshotKeys) || value.schemaVersion !== 2) return false;
  const association = value.association;
  const rawOverrides = value.rawOverrides;
  const effective = value.effective;
  const source = value.source;
  if (!isRecord(association) || !hasExactKeys(association, associationKeys)
    || !associationKeys.every(key => isNonBlankString(association[key]))) return false;
  if (!isRecord(rawOverrides) || !hasExactKeys(rawOverrides, overrideKeys)
    || !['name', 'category', 'description', 'slogan', 'detailContent', 'coverImage'].every(key => isNullableString(rawOverrides[key]))
    || !isNullableStringList(rawOverrides.tags) || !isNullableStringList(rawOverrides.images)) return false;
  if (!isRecord(effective) || !hasExactKeys(effective, effectiveKeys)
    || !isNonBlankString(effective.name) || !isNonBlankString(effective.category)
    || !isNullableString(effective.description) || !isStringList(effective.tags)
    || !isNullableString(effective.slogan) || !isNullableString(effective.detailContent)
    || !isFiniteNumber(effective.salesCount) || !Number.isInteger(effective.salesCount) || effective.salesCount < 0
    || !isNullableString(effective.coverImage) || !isStringList(effective.images)) return false;
  return isRecord(source) && hasExactKeys(source, sourceKeys)
    && isFiniteNumber(source.institutionProjectVersion)
    && Number.isInteger(source.institutionProjectVersion) && source.institutionProjectVersion >= 0
    && isNonBlankString(source.platformInheritanceHash);
}

const targetV2Keys = [
  'payloadVersion', 'institutionProjectId', 'institutionId', 'institutionName', 'platformProjectId',
  'platformProjectName', 'doctorId', 'doctorName', 'baseRevision', 'currentProject',
  'currentDoctorPrice', 'currentDoctorActive', 'platformRate', 'pricingPolicyRevision', 'travelGroundServiceFee',
] as const;

export function parseDoctorProjectChangeTarget(value: unknown): DoctorProjectChangeTargetV2 | null {
  if (!isRecord(value) || !hasExactKeys(value, targetV2Keys) || value.payloadVersion !== 2) return null;
  if (!['institutionProjectId', 'institutionId', 'institutionName', 'platformProjectId', 'platformProjectName', 'doctorId', 'doctorName', 'baseRevision', 'pricingPolicyRevision']
    .every(key => isNonBlankString(value[key]))) return null;
  if (!isInstitutionProjectSnapshotV2(value.currentProject)
    || !isFiniteNumber(value.currentDoctorPrice) || typeof value.currentDoctorActive !== 'boolean'
    || !isFiniteNumber(value.platformRate) || !isFiniteNumber(value.travelGroundServiceFee)) return null;
  return value as unknown as DoctorProjectChangeTargetV2;
}

const requestV2Keys = [
  'payloadVersion', 'id', 'requestType', 'doctorId', 'doctorName', 'institutionId', 'institutionName',
  'institutionProjectId', 'institutionProjectName', 'platformProjectId', 'platformProjectName', 'baseRevision',
  'currentProject', 'proposedProject', 'latestProject', 'latestRevision', 'sharedChanged',
  'currentDoctorPrice', 'proposedDoctorPrice', 'latestDoctorPrice', 'currentDoctorActive', 'proposedDoctorActive',
  'latestDoctorActive', 'platformRate', 'pricingPolicyRevision', 'travelGroundServiceFee', 'requestStatus', 'notes',
  'forceProcessed', 'submittedBy', 'submittedAt', 'reviewedBy', 'reviewerName', 'reviewNote', 'reviewedAt',
  'updatedAt', 'snapshotState', 'snapshotError', 'reviewable',
] as const;

function parseV2(value: Record<string, unknown>): DoctorProjectChangeRequestV2 | null {
  if (!hasExactKeys(value, requestV2Keys)) return null;
  if (!['id', 'requestType', 'doctorId', 'doctorName', 'institutionId', 'institutionName', 'institutionProjectId',
    'institutionProjectName', 'platformProjectId', 'platformProjectName', 'baseRevision',
    'pricingPolicyRevision', 'requestStatus', 'submittedBy', 'submittedAt', 'updatedAt']
    .every(key => isNonBlankString(value[key]))) return null;
  if (!(value.latestProject === null || isInstitutionProjectSnapshotV2(value.latestProject))
    || !isNullableString(value.latestRevision)
    || !isFiniteNumber(value.currentDoctorPrice) || !isFiniteNumber(value.proposedDoctorPrice)
    || !isNullableNumber(value.latestDoctorPrice) || typeof value.currentDoctorActive !== 'boolean'
    || typeof value.proposedDoctorActive !== 'boolean' || !isNullableBoolean(value.latestDoctorActive)
    || !isFiniteNumber(value.platformRate) || !isFiniteNumber(value.travelGroundServiceFee)
    || typeof value.sharedChanged !== 'boolean' || typeof value.forceProcessed !== 'boolean'
    || !isString(value.notes) || !isNullableString(value.reviewedBy) || !isNullableString(value.reviewerName)
    || !isNullableString(value.reviewNote) || !isNullableString(value.reviewedAt)
    || typeof value.reviewable !== 'boolean') return null;

  const snapshotState = value.snapshotState;
  if (snapshotState === 'VALID') {
    if (!isInstitutionProjectSnapshotV2(value.currentProject)
      || !isInstitutionProjectSnapshotV2(value.proposedProject) || value.snapshotError !== null) return null;
  } else if (snapshotState === 'INVALID') {
    if (value.currentProject !== null || value.proposedProject !== null
      || !isNonBlankString(value.snapshotError) || value.reviewable !== false) return null;
  } else {
    return null;
  }

  const parsed = value as unknown as Omit<DoctorProjectChangeRequestV2, 'kind' | 'parseIssue'>;
  return {
    ...parsed,
    kind: 'V2',
    reviewable: parsed.reviewable && snapshotState === 'VALID',
    parseIssue: snapshotState === 'INVALID' ? value.snapshotError as string : null,
  };
}

function optional(value: Record<string, unknown>, key: string, predicate: (field: unknown) => boolean) {
  return !hasOwn(value, key) || predicate(value[key]);
}

function parseV1(value: Record<string, unknown>): DoctorProjectChangeRequestV1 | null {
  if (!['id', 'requestType', 'doctorId', 'doctorName', 'institutionId', 'institutionName', 'institutionProjectId', 'projectName', 'serviceDescription', 'status']
    .every(key => isNonBlankString(value[key]))) return null;
  if (!optional(value, 'priceSuggestion', isNullableNumber)
    || !optional(value, 'notes', isNullableString)
    || !optional(value, 'serviceTags', field => field === undefined || isStringList(field))
    || !optional(value, 'scheduleNote', isNullableString)
    || !optional(value, 'coverImage', isNullableString)
    || !optional(value, 'images', field => field === undefined || isStringList(field))) return null;

  const numericFields = [
    'consultationFee', 'commissionRate', 'institutionRate', 'medicalListPrice', 'platformRate', 'doctorRate',
    'currentPrice', 'currentConsultationFee', 'currentMedicalListPrice', 'currentCommissionRate',
    'currentInstitutionRate', 'currentPlatformRate', 'currentDoctorRate',
  ];
  if (!numericFields.every(key => optional(value, key, isNullableNumber))) return null;
  const stringFields = ['currentServiceDescription', 'currentScheduleNote', 'currentCoverImage', 'reviewedBy', 'reviewerName', 'reviewNote', 'submittedAt', 'reviewedAt', 'updatedAt'];
  if (!stringFields.every(key => optional(value, key, isNullableString))
    || !optional(value, 'currentServiceTags', isNullableStringList)
    || !optional(value, 'currentImages', isNullableStringList)
    || !optional(value, 'forceProcessed', field => typeof field === 'boolean')
    || !optional(value, 'submittedBy', isString)) return null;

  return {
    ...(value as unknown as DoctorProjectChangeRequestV1),
    kind: 'V1', payloadVersion: 1,
    serviceTags: isStringList(value.serviceTags) ? value.serviceTags : [],
    scheduleNote: isNullableString(value.scheduleNote) ? value.scheduleNote : null,
    coverImage: isNullableString(value.coverImage) ? value.coverImage : null,
    images: isStringList(value.images) ? value.images : [],
    priceSuggestion: isNullableNumber(value.priceSuggestion) ? value.priceSuggestion : null,
    notes: isNullableString(value.notes) ? value.notes : null,
    reviewable: value.status === 'PENDING', parseIssue: null,
  };
}

function damaged(value: unknown, issue: DamagedDoctorProjectChangeRequest['parseIssue']): DamagedDoctorProjectChangeRequest {
  const record = isRecord(value) ? value : {};
  const stringValue = (key: string) => isString(record[key]) ? record[key] as string : '';
  const payloadVersion = isFiniteNumber(record.payloadVersion) ? record.payloadVersion : null;
  return {
    kind: 'DAMAGED', payloadVersion, id: stringValue('id') || 'unknown',
    requestType: stringValue('requestType') || 'UNKNOWN', doctorId: stringValue('doctorId'),
    doctorName: stringValue('doctorName') || '-', institutionId: stringValue('institutionId'),
    institutionName: stringValue('institutionName') || '-', institutionProjectId: stringValue('institutionProjectId'),
    projectName: stringValue('institutionProjectName') || stringValue('projectName') || stringValue('platformProjectName') || '-',
    requestStatus: stringValue('requestStatus') || stringValue('status') || 'UNKNOWN',
    reviewable: false, parseIssue: issue,
  };
}

export function parseDoctorProjectChangeRequest(value: unknown): ParsedDoctorProjectChangeRequest {
  if (!isRecord(value)) return damaged(value, 'MALFORMED_V1_PAYLOAD');
  const version = value.payloadVersion;
  if (version === 2) return parseV2(value) ?? damaged(value, 'MALFORMED_V2_PAYLOAD');
  if (version === 1 || version === undefined) return parseV1(value) ?? damaged(value, 'MALFORMED_V1_PAYLOAD');
  return damaged(value, 'UNSUPPORTED_PAYLOAD_VERSION');
}

export function parseDoctorProjectChangeRequests(value: unknown): ParsedDoctorProjectChangeRequest[] {
  return Array.isArray(value) ? value.map(parseDoctorProjectChangeRequest) : [];
}

export function adaptV2ProposedProjectPreview(request: DoctorProjectChangeRequestV2): InstitutionProjectPreviewModel | null {
  const project = request.proposedProject?.effective;
  if (!project) return null;
  return {
    ...project,
    currency: 'USD', price: request.proposedDoctorPrice, originalPrice: null,
    active: request.proposedDoctorActive, travelGroundServiceFee: request.travelGroundServiceFee,
  };
}

export function adaptLegacyProjectRequestPreview(request: DoctorProjectChangeRequestV1): InstitutionProjectPreviewModel {
  return {
    name: request.projectName, category: null, description: request.serviceDescription,
    tags: request.serviceTags, slogan: null, detailContent: null,
    coverImage: request.coverImage, images: request.images, salesCount: null,
    currency: 'USD', price: request.priceSuggestion ?? request.medicalListPrice ?? null,
    originalPrice: null, active: null, travelGroundServiceFee: null,
    scheduleNote: request.scheduleNote,
  };
}

export function adaptCreationRequestPreview(request: ProfessionalProjectRequestResponse): InstitutionProjectPreviewModel {
  return {
    name: request.name ?? request.projectName ?? '-', category: request.category,
    description: request.description, tags: request.tags ?? [], slogan: request.slogan,
    detailContent: request.detailContent, coverImage: request.coverImage, images: request.images ?? [],
    salesCount: request.salesCount, currency: request.currency,
    price: request.requestType === 'PLATFORM' ? request.referencePrice : request.price,
    originalPrice: request.originalPrice, active: request.isActive, travelGroundServiceFee: null,
  };
}
