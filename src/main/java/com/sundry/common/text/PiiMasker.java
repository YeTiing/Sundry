package com.sundry.common.text;

import java.util.regex.Pattern;

/**
 * 审计入库前的轻量脱敏：手机号和 12~32 位长数字（订单号等）。
 */
public final class PiiMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    // 不用 \b：中文紧邻数字时 Java 的 \b 可能不认为有边界
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{12,32}(?!\\d)");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = PHONE.matcher(text).replaceAll(match ->
                match.group().substring(0, 3) + "****" + match.group().substring(7));
        return LONG_DIGITS.matcher(masked).replaceAll(match -> {
            String digits = match.group();
            return digits.substring(0, 4) + "****" + digits.substring(digits.length() - 4);
        });
    }
}
