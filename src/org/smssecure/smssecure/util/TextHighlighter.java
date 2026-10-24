package org.smssecure.smssecure.util;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

public final class TextHighlighter {

  private TextHighlighter() {}

  public static SpannableString highlight(CharSequence text, String query) {
    SpannableString result = new SpannableString(text == null ? "" : text);
    if (query == null || query.length() == 0 || result.length() == 0) return result;

    String value = result.toString();
    int    from  = 0;

    while (from <= value.length() - query.length()) {
      int match = indexOfIgnoreCase(value, query, from);
      if (match < 0) break;

      int end = match + query.length();
      result.setSpan(new BackgroundColorSpan(Color.YELLOW), match, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      result.setSpan(new ForegroundColorSpan(Color.BLACK), match, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      from = end;
    }

    return result;
  }

  public static int indexOfIgnoreCase(String value, String query, int fromIndex) {
    if (value == null || query == null || query.length() == 0) return -1;
    int start = Math.max(0, fromIndex);

    for (int i = start; i <= value.length() - query.length(); i++) {
      if (value.regionMatches(true, i, query, 0, query.length())) return i;
    }

    return -1;
  }
}
