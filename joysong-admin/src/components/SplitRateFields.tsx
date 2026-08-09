import { Alert, Form, InputNumber, type FormInstance } from 'antd';
import type { OrderSplitPolicy } from '../types/splitRates';
import { calculateDoctorRate, validateSplitRates } from '../utils/splitRates';

type SplitRateFieldsProps = {
  form: FormInstance;
  policy?: OrderSplitPolicy;
  loading: boolean;
  error?: string;
};

export function SplitRateFields({ form, policy, error }: SplitRateFieldsProps) {
  const institutionRate = Form.useWatch('institutionRate', form);
  const commissionRate = Form.useWatch('commissionRate', form);
  const platformRate = policy?.platformRate;
  const doctorRate = calculateDoctorRate(platformRate, institutionRate, commissionRate);
  const validateAllRates = () => {
    const validationError = validateSplitRates(
      platformRate,
      form.getFieldValue('institutionRate'),
      form.getFieldValue('commissionRate'),
    );
    return validationError ? Promise.reject(new Error(validationError)) : Promise.resolve();
  };

  return <>
    {error && <Alert type="error" showIcon title={error} style={{ marginBottom: 16 }} />}
    <Form.Item label="平台分账比例">
      <InputNumber
        aria-label="平台分账比例"
        value={platformRate}
        disabled
        addonAfter="%"
        style={{ width: '100%' }}
      />
    </Form.Item>
    <Form.Item
      name="institutionRate"
      label="合作医疗机构分成比例"
      dependencies={['commissionRate']}
      rules={[{ validator: validateAllRates }]}
    >
      <InputNumber aria-label="合作医疗机构分成比例" min={0} max={100} precision={2} addonAfter="%" style={{ width: '100%' }} />
    </Form.Item>
    <Form.Item
      name="commissionRate"
      label="医美顾问分账比例"
      dependencies={['institutionRate']}
      rules={[{ validator: validateAllRates }]}
    >
      <InputNumber aria-label="医美顾问分账比例" min={0} max={100} precision={2} addonAfter="%" style={{ width: '100%' }} />
    </Form.Item>
    <Form.Item label="医生分账比例" help="100% - 平台 - 合作医疗机构 - 医美顾问">
      <InputNumber
        aria-label="医生分账比例"
        value={doctorRate ?? undefined}
        disabled
        addonAfter="%"
        style={{ width: '100%' }}
      />
    </Form.Item>
  </>;
}
