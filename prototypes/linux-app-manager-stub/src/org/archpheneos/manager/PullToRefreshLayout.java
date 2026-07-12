package org.archpheneos.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

final class PullToRefreshLayout extends FrameLayout {
    interface OnRefreshListener {
        void onRefresh();
    }

    private final ProgressBar indicator;
    private final int touchSlop;
    private final float triggerDistance;
    private final float settledDistance;
    private OnRefreshListener listener;
    private float initialX;
    private float initialY;
    private boolean dragging;
    private boolean refreshing;

    PullToRefreshLayout(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        triggerDistance = dp(64);
        settledDistance = dp(52);
        setClipChildren(true);

        indicator = new ProgressBar(context);
        indicator.setVisibility(INVISIBLE);
        indicator.setElevation(dp(4));
        LayoutParams indicatorParams = new LayoutParams(dp(36), dp(36),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        indicatorParams.topMargin = dp(8);
        addView(indicator, indicatorParams);
    }

    void setIndicatorColor(int color) {
        indicator.setIndeterminateTintList(ColorStateList.valueOf(color));
    }

    void setOnRefreshListener(OnRefreshListener listener) {
        this.listener = listener;
    }

    void setRefreshing(boolean refreshing) {
        this.refreshing = refreshing;
        View content = contentView();
        if (refreshing) {
            indicator.setVisibility(VISIBLE);
            if (content != null) {
                content.animate().cancel();
                content.animate().translationY(settledDistance).setDuration(160).start();
            }
        } else if (content != null) {
            content.animate().cancel();
            content.animate().translationY(0).setDuration(180).withEndAction(() -> {
                if (!this.refreshing) indicator.setVisibility(INVISIBLE);
            }).start();
        } else {
            indicator.setVisibility(INVISIBLE);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (refreshing) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = event.getX();
                initialY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - initialX);
                float dy = event.getY() - initialY;
                if (dy > touchSlop && dy > dx && !canContentScrollUp()) {
                    dragging = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dragging = false;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragging && event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = event.getX();
                initialY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float pull = Math.max(0, (event.getY() - initialY) * 0.45f);
                pull = Math.min(pull, triggerDistance * 1.35f);
                indicator.setVisibility(pull > 0 ? VISIBLE : INVISIBLE);
                View content = contentView();
                if (content != null) content.setTranslationY(pull);
                return true;
            case MotionEvent.ACTION_UP:
                View releasedContent = contentView();
                boolean shouldRefresh = releasedContent != null
                        && releasedContent.getTranslationY() >= triggerDistance;
                dragging = false;
                if (shouldRefresh && listener != null) {
                    setRefreshing(true);
                    listener.onRefresh();
                } else {
                    setRefreshing(false);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                setRefreshing(false);
                return true;
            default:
                return true;
        }
    }

    private boolean canContentScrollUp() {
        View content = contentView();
        return content != null && content.canScrollVertically(-1);
    }

    private View contentView() {
        return getChildCount() > 1 ? getChildAt(0) : null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}