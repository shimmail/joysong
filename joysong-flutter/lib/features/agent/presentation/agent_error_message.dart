String agentErrorMessage(String message, {required bool isEnglish}) {
  if (!isEnglish) return message;
  return switch (message) {
    '消息长度必须为 1–5000 字符' => 'Message must contain 1–5000 characters.',
    '生成中断' => 'Response generation was interrupted.',
    '请求参数错误' => 'Invalid request. Please check your input.',
    '请求失败，请稍后手动重试' => 'Request failed. Please try again later.',
    '操作失败，请稍后重试' => 'Something went wrong. Please try again later.',
    _ => RegExp(r'[\u3400-\u9fff]').hasMatch(message)
        ? 'Something went wrong. Please try again later.'
        : message,
  };
}
