import 'package:joysong_flutter/core/network/api_exception.dart';

typedef IdentityMessageResolver = String Function(
  String chinese,
  String english,
);

enum IdentityErrorOperation { load, upload, delete, submit, review }

enum IdentityProjectErrorAction {
  none,
  refresh,
  offerForce,
  resubmit,
  upgrade,
  invalid,
  permissionExit,
}

IdentityProjectErrorAction identityProjectErrorAction(Object error) {
  if (error is! ApiException) return IdentityProjectErrorAction.none;
  if (error.httpStatus == 403) {
    return IdentityProjectErrorAction.permissionExit;
  }
  return _projectErrorCodes[error.errorCode]?.action ??
      IdentityProjectErrorAction.none;
}

String identityErrorMessage(
  Object error, {
  required IdentityErrorOperation operation,
  required IdentityMessageResolver resolve,
}) {
  if (error is ApiException) {
    if (operation == IdentityErrorOperation.review &&
        error.httpStatus == 403) {
      return resolve(
        '审核权限已变化，正在刷新权限',
        'Review access changed. Refreshing permissions.',
      );
    }
    final projectError = _projectErrorCodes[error.errorCode];
    if (projectError != null) {
      return resolve(projectError.chinese, projectError.english);
    }
  }
  final raw = switch (error) {
    ApiException(message: final message) => message.trim(),
    ArgumentError(message: final message?) => message.toString().trim(),
    FormatException(message: final message) => message.trim(),
    _ => '',
  };
  final translated = _translateKnownMessage(raw, resolve);
  if (translated != null) return translated;

  final fallback = switch (operation) {
    IdentityErrorOperation.load => resolve(
        '身份信息加载失败，请重试',
        'Unable to load identity information. Please retry.',
      ),
    IdentityErrorOperation.upload => resolve(
        '认证材料上传失败，请重试',
        'Unable to upload the verification document. Please retry.',
      ),
    IdentityErrorOperation.delete => resolve(
        '认证材料删除失败，请重试',
        'Unable to delete the verification document. Please retry.',
      ),
    IdentityErrorOperation.submit => resolve(
        '身份申请提交失败，请重试',
        'Unable to submit the identity application. Please retry.',
      ),
    IdentityErrorOperation.review => resolve(
        '审核提交失败，请重试',
        'Unable to submit the review. Please retry.',
      ),
  };
  if (raw.isEmpty) return fallback;
  return resolve(raw, fallback);
}

const _projectErrorCodes = <
    String,
    ({
      IdentityProjectErrorAction action,
      String chinese,
      String english,
    })>{
  'EDIT_BASE_STALE': (
    action: IdentityProjectErrorAction.resubmit,
    chinese: '编辑基线已变化，请刷新后重新提交',
    english: 'The edit baseline changed. Refresh and submit again.',
  ),
  'APPROVAL_BASE_STALE': (
    action: IdentityProjectErrorAction.offerForce,
    chinese: '审批基线已变化，已刷新最新审核详情',
    english:
        'The approval baseline changed. The latest review details were refreshed.',
  ),
  'INHERITANCE_SOURCE_STALE': (
    action: IdentityProjectErrorAction.resubmit,
    chinese: '平台项目继承源已变化，请医生重新提交',
    english:
        'The inherited platform project changed. Ask the doctor to submit again.',
  ),
  'PRICING_POLICY_STALE': (
    action: IdentityProjectErrorAction.resubmit,
    chinese: '定价策略已变化，请医生重新提交',
    english: 'The pricing policy changed. Ask the doctor to submit again.',
  ),
  'FORCE_BASE_STALE': (
    action: IdentityProjectErrorAction.refresh,
    chinese: '强制批准基线再次变化，请刷新后重新审核',
    english:
        'The force-approval baseline changed again. Refresh and review again.',
  ),
  'REQUEST_ALREADY_PENDING': (
    action: IdentityProjectErrorAction.refresh,
    chinese: '已有待审核申请，已刷新申请列表',
    english: 'A request is already pending. The request list was refreshed.',
  ),
  'REQUEST_ALREADY_HANDLED': (
    action: IdentityProjectErrorAction.refresh,
    chinese: '该申请已处理，已刷新申请列表',
    english: 'This request was already handled. The request list was refreshed.',
  ),
  'CLIENT_UPGRADE_REQUIRED': (
    action: IdentityProjectErrorAction.upgrade,
    chinese: '请升级应用后继续操作',
    english: 'Update the app before continuing.',
  ),
  'FORCE_NOT_APPLICABLE': (
    action: IdentityProjectErrorAction.refresh,
    chinese: '当前不再允许强制批准，请刷新后重新审核',
    english: 'Force approval is no longer available. Refresh and review again.',
  ),
  'PROJECT_PAYLOAD_INVALID': (
    action: IdentityProjectErrorAction.invalid,
    chinese: '项目申请数据异常，无法审核',
    english: 'The project request data is invalid and cannot be reviewed.',
  ),
  'REQUEST_SNAPSHOT_INVALID': (
    action: IdentityProjectErrorAction.invalid,
    chinese: '申请快照异常，无法审核',
    english: 'The request snapshot is invalid and cannot be reviewed.',
  ),
  'INSTITUTION_PROJECT_VERSION_STALE': (
    action: IdentityProjectErrorAction.refresh,
    chinese: '机构项目已变化，请刷新后重试',
    english: 'The institution project changed. Refresh and try again.',
  ),
};

String? _translateKnownMessage(String raw, IdentityMessageResolver resolve) {
  if (raw.isEmpty) return null;

  const exact = <String, String>{
    '暂不支持申请该身份': 'This identity type is not available for application.',
    '不支持申请该身份': 'This identity type is not available for application.',
    '用户不存在或已注销': 'The account does not exist or has been closed.',
    '无法识别当前用户': 'Unable to identify the current user. Please sign in again.',
    '该身份已经认证通过': 'This identity has already been verified.',
    '该身份已有待审核申请，请勿重复提交':
        'An application for this identity is already under review.',
    '认证信息内容过长': 'The verification information is too long.',
    '请输入正确的真实姓名': 'Enter a valid real name.',
    '证件号码格式不正确': 'Enter a valid ID number using 6-30 letters or digits.',
    '请上传认证材料': 'Upload the required verification documents.',
    '认证材料数量不能超过 10 个': 'You can upload at most 10 verification documents.',
    '认证材料不能重复': 'Do not upload the same verification document twice.',
    '存在不支持的认证材料': 'One or more verification document types are not supported.',
    '认证材料不存在、已失效或不属于当前用户':
        'A verification document is missing, expired, or belongs to another user.',
    '不支持的认证材料类型': 'This verification document type is not supported.',
    '认证材料不能为空': 'The verification document cannot be empty.',
    '认证材料不能为空且不能超过 10MB':
        'The verification document cannot be empty or exceed 10 MB.',
    '单个认证文件不能超过 10MB': 'Each verification document must be 10 MB or smaller.',
    '无法识别文件格式': 'The selected file format could not be identified.',
    '认证材料仅支持 JPG、PNG、WebP 或 PDF':
        'Verification documents must be JPG, PNG, WebP, or PDF files.',
    '文件内容与认证材料格式不匹配':
        'The file content does not match its selected format.',
    '文件内容与扩展格式不匹配': 'The file content does not match its extension.',
    '认证材料存储路径不合法': 'The verification document storage path is invalid.',
    '认证材料不存在、已提交或不属于当前用户':
        'The verification document is missing, already submitted, or belongs to another user.',
    '请完整填写认证信息': 'Complete all required verification fields.',
    '请完整上传认证材料': 'Upload all required verification documents.',
    '请求内容格式不正确': 'The request format is invalid.',
    '请求参数不正确': 'One or more request fields are invalid.',
    '请求方法不支持': 'This operation is not supported.',
    '服务器内部错误': 'The server encountered an error. Please try again later.',
    '服务器异常，请稍后重试': 'The server is unavailable. Please try again later.',
    '请求超时，请稍后重试': 'The request timed out. Please retry.',
    '网络连接失败，请检查网络': 'Network connection failed. Check your connection.',
    '网络请求失败，请稍后重试': 'The network request failed. Please retry.',
    '上传超时，请稍后重试': 'The upload timed out. Please retry.',
    '上传请求失败，请稍后重试': 'The upload failed. Please retry.',
    '服务响应格式异常': 'The server returned an invalid response.',
  };
  final english = exact[raw];
  if (english != null) return resolve(raw, english);

  final fieldMatch = RegExp(r'^(.+?)\s*(不能为空|内容过长)$').firstMatch(raw);
  if (fieldMatch != null) {
    final label = fieldMatch.group(1)!;
    final field = _fieldNames[label];
    if (field != null) {
      final suffix = fieldMatch.group(2) == '不能为空' ? ' is required.' : ' is too long.';
      return resolve(raw, '$field$suffix');
    }
  }
  if (raw.startsWith('请完整上传')) {
    return resolve(raw, 'Upload all required verification documents.');
  }
  return null;
}

const _fieldNames = <String, String>{
  '真实姓名': 'Real name',
  '证件号码': 'ID number',
  '联系电话': 'Contact phone number',
  '机构名称': 'Institution name',
  '统一社会信用代码': 'Unified social credit code',
  '所在地区': 'Region',
  '详细地址': 'Address',
  '执业机构': 'Practicing institution',
  '科室': 'Department',
  '职称': 'Professional title',
  '医师资格证编号': 'Doctor qualification certificate number',
  '医师执业证编号': 'Medical practice certificate number',
  '申请理由': 'Application reason',
  '从业经历': 'Professional experience',
  '证明材料说明': 'Supporting document description',
};
