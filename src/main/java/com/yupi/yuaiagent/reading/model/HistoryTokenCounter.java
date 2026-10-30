package com.yupi.yuaiagent.reading.model;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Local O200K token proxy for history only; not the DeepSeek tokenizer or a billing count. */
public final class HistoryTokenCounter {
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    public static final String METHOD = "O200K_PROXY_NOT_PROVIDER_TOKENS";

    public int count(String text) {
        return ENCODING.countTokensOrdinary(text);
    }

    public String fit(String text, int tokens) {
        if (tokens <= 0) return "";
        if (count(text) <= tokens) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (count(text.substring(0, mid)) <= tokens) low = mid;
            else high = mid - 1;
        }
        if (low > 0 && low < text.length() && Character.isHighSurrogate(text.charAt(low - 1))) low--;
        String result = text.substring(0, low);
        // BPE counts need not be strictly monotonic at every character boundary.
        while (!result.isEmpty() && count(result) > tokens)
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        return result;
    }
}
