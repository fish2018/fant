package org.antjs.runtime.demo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;

/** Compact IDE-style action button with an explicit TV focus state. */
final class FocusButton extends Button {
    static final int STYLE_PRIMARY = 1;
    static final int STYLE_SECONDARY = 2;
    static final int STYLE_DANGER = 3;
    static final int STYLE_TAB = 4;
    static final int STYLE_TEXT = 5;

    private int style = STYLE_SECONDARY;
    private boolean selectedTab;
    private final Paint tabIndicator = new Paint(Paint.ANTI_ALIAS_FLAG);

    FocusButton(Context context) {
        this(context, null);
    }

    FocusButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setTextSize(13);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSingleLine(true);
        setMinHeight(dp(44));
        setMinimumWidth(dp(44));
        setPadding(dp(12), 0, dp(12), 0);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setCompoundDrawablePadding(dp(7));
        setLetterSpacing(0);
        setOnFocusChangeListener((view, hasFocus) -> refreshBackground());
        refreshBackground();
    }

    void setStyle(int style) {
        this.style = style;
        if (style == STYLE_TAB) {
            setTextSize(14);
            setMinHeight(dp(48));
        } else {
            setTextSize(13);
            setMinHeight(dp(44));
        }
        refreshBackground();
    }

    void setSelectedTab(boolean selected) {
        selectedTab = selected;
        refreshBackground();
        invalidate();
    }

    void setIcon(int resourceId) {
        setCompoundDrawablesWithIntrinsicBounds(resourceId, 0, 0, 0);
        refreshBackground();
    }

    private void refreshBackground() {
        boolean focused = isFocused();
        boolean enabled = isEnabled();
        int fill;
        int stroke;
        int text;
        if (style == STYLE_TAB) {
            fill = Color.TRANSPARENT;
            stroke = Color.TRANSPARENT;
            text = enabled
                    ? (selectedTab ? Color.rgb(27, 119, 184) : Color.rgb(103, 116, 132))
                    : Color.rgb(170, 177, 186);
            setTypeface(selectedTab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        } else if (style == STYLE_TEXT) {
            fill = Color.TRANSPARENT;
            stroke = Color.TRANSPARENT;
            text = enabled ? Color.rgb(27, 119, 184) : Color.rgb(170, 177, 186);
        } else if (!enabled) {
            fill = Color.rgb(235, 238, 242);
            stroke = Color.rgb(210, 216, 224);
            text = Color.rgb(145, 153, 164);
        } else if (focused) {
            fill = Color.rgb(224, 241, 255);
            stroke = Color.rgb(33, 117, 192);
            text = Color.rgb(18, 66, 105);
        } else if (style == STYLE_PRIMARY) {
            fill = Color.rgb(27, 119, 184);
            stroke = Color.rgb(19, 95, 151);
            text = Color.WHITE;
        } else if (style == STYLE_DANGER) {
            fill = Color.rgb(255, 236, 238);
            stroke = Color.rgb(207, 77, 91);
            text = Color.rgb(151, 35, 48);
        } else {
            fill = Color.rgb(255, 255, 255);
            stroke = Color.rgb(196, 204, 214);
            text = Color.rgb(38, 48, 61);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(style == STYLE_TAB || style == STYLE_TEXT ? 0 : 7));
        if (style != STYLE_TAB && style != STYLE_TEXT) {
            background.setStroke(dp(focused ? 2 : 1), stroke);
        }
        setTextColor(text);
        setBackground(background);
        for (Drawable drawable : getCompoundDrawables()) {
            if (drawable != null) drawable.mutate().setTint(text);
        }
        setScaleX(focused && style != STYLE_TAB && style != STYLE_TEXT ? 1.025f : 1f);
        setScaleY(focused && style != STYLE_TAB && style != STYLE_TEXT ? 1.025f : 1f);
        setElevation(focused && style != STYLE_TAB && style != STYLE_TEXT ? dp(7) : 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (style != STYLE_TAB || (!selectedTab && !isFocused()) || getWidth() <= 0) return;
        tabIndicator.setColor(selectedTab ? Color.rgb(27, 119, 184) : Color.rgb(164, 181, 198));
        tabIndicator.setStyle(Paint.Style.FILL);
        float height = dp(selectedTab ? 3 : 2);
        float inset = 0;
        canvas.drawRoundRect(inset, getHeight() - height, getWidth() - inset,
                getHeight(), height / 2f, height / 2f, tabIndicator);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        refreshBackground();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
