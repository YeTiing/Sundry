export const PHONE_PATTERN = /^1[3-9]\d{9}$/
export const CODE_PATTERN = /^\d{6}$/
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/

export function isPhone(value: string): boolean {
  return PHONE_PATTERN.test(value)
}
