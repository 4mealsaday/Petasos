package com.petasos.diagnostics.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Temporary, isolated emulator diagnostic. It does not discover navigation apps,
 * start navigation, listen to speech, read location, or access any product data.
 * A reviewer supplies an already observed safe map rectangle in display pixels.
 */
public final class OverlayDiagnosticActivity extends Activity {
    private static final String TAG = "PetasosOverlayDiag";
    private static final long LIFETIME_MS = 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager overlayWindows;
    private TextView button;
    private TextView card;
    private boolean ended;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No input-catching intermediate Activity while the original app resumes.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (savedInstanceState != null) {
            end("RECREATED_CLOSED");
            return;
        }
        handler.postDelayed(() -> end("TIMEOUT_CLOSED"), LIFETIME_MS);
        try {
            if (!Settings.canDrawOverlays(this)) {
                end("PERMISSION_ABSENT");
                return;
            }
            Context overlayContext = createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            overlayWindows = overlayContext.getSystemService(WindowManager.class);
            Rect display = overlayWindows.getMaximumWindowMetrics().getBounds();
            Rect map = readMapBounds(getIntent(), display);
            if (map == null) {
                end("BOUNDS_REJECTED");
                return;
            }
            show(overlayContext, map);
            handler.postDelayed(() -> {
                if (ended) return;
                // These are View-tree observations, not proof of compositor visibility.
                log(button.isAttachedToWindow() && card.isAttachedToWindow()
                        ? "WINDOWS_ATTACHED" : "WINDOWS_NOT_ATTACHED");
                log(button.isShown() && card.isShown()
                        ? "VIEW_TREES_SHOWN" : "VIEW_TREES_NOT_SHOWN");
                moveTaskToBack(true);
            }, 250L);
        } catch (SecurityException denied) {
            end("WINDOW_PERMISSION_DENIED");
        } catch (RuntimeException unavailable) {
            // Never log exception text, input extras, packages, locations, or paths.
            end("WINDOW_UNAVAILABLE");
        }
    }

    private Rect readMapBounds(Intent intent, Rect display) {
        for (String name : new String[] {"map_x", "map_y", "map_width", "map_height"}) {
            if (!intent.hasExtra(name)) return null;
        }
        int x = intent.getIntExtra("map_x", -1);
        int y = intent.getIntExtra("map_y", -1);
        int width = intent.getIntExtra("map_width", -1);
        int height = intent.getIntExtra("map_height", -1);
        if (x < display.left || y < display.top || width <= 0 || height <= 0) return null;
        if (x >= display.right || y >= display.bottom) return null;
        // Clamp the caller-supplied rectangle to the display; long prevents overflow.
        int right = (int) Math.min((long) display.right, (long) x + width);
        int bottom = (int) Math.min((long) display.bottom, (long) y + height);
        Rect map = new Rect(x, y, right, bottom);
        return map.width() >= dp(400) && map.height() >= dp(320) ? map : null;
    }

    private void show(Context context, Rect map) {
        int margin = dp(28);
        int buttonSize = dp(96);
        int cardHeight = dp(112);
        int cardWidth = Math.min(dp(1200), map.width() - margin * 2);

        button = label(context, "P", 40f);
        button.setContentDescription("Overlay input diagnostic. No microphone.");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(37, 121, 203));
        circle.setStroke(dp(2), Color.rgb(175, 223, 255));
        button.setBackground(circle);
        button.setOnClickListener(view -> {
            log("BUTTON_TOUCHED");
            card.setText("Overlay input verified\nNo microphone or playback is active");
        });

        card = label(context, "Petasos overlay diagnostic\nNavigation remains in the original app", 26f);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(18, 64, 104));
        surface.setCornerRadius(dp(28));
        surface.setStroke(dp(2), Color.rgb(175, 223, 255));
        card.setBackground(surface);

        WindowManager.LayoutParams buttonLayout = layout(buttonSize, buttonSize,
                map.right - margin - buttonSize, map.top + margin, false);
        WindowManager.LayoutParams cardLayout = layout(cardWidth, cardHeight,
                map.left + (map.width() - cardWidth) / 2,
                map.bottom - margin - cardHeight, true);
        overlayWindows.addView(button, buttonLayout);
        overlayWindows.addView(card, cardLayout);
        log("WINDOWS_ADDED");
    }

    private TextView label(Context context, String value, float sp) {
        TextView text = new TextView(context);
        text.setGravity(Gravity.CENTER);
        text.setText(value);
        text.setTextColor(Color.WHITE);
        text.setTextSize(sp);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return text;
    }

    private WindowManager.LayoutParams layout(int width, int height, int x, int y, boolean passThrough) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (passThrough) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.x = x;
        layout.y = y;
        // The caller rectangle is already in absolute display coordinates.
        layout.setFitInsetsTypes(0);
        // Below Android's documented default .8 untrusted-touch obscuring limit.
        // No overlapping windows: the button is at the top, the card at the bottom.
        layout.alpha = passThrough ? .72f : .94f;
        layout.setTitle(passThrough ? "Petasos diagnostic card" : "Petasos diagnostic button");
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A second invocation closes the existing diagnostic; it cannot extend the timer.
        end("REENTRY_CLOSED");
    }

    private void end(String code) {
        if (ended) return;
        ended = true;
        handler.removeCallbacksAndMessages(null);
        remove(button);
        remove(card);
        button = null;
        card = null;
        log(code);
        finishAndRemoveTask();
    }

    private void remove(View view) {
        if (view == null || overlayWindows == null) return;
        try { overlayWindows.removeViewImmediate(view); }
        catch (IllegalArgumentException alreadyRemoved) { /* Includes a partially failed attach. */ }
    }

    @Override protected void onDestroy() {
        if (!ended) end("ACTIVITY_DESTROYED");
        super.onDestroy();
    }

    private static void log(String code) { Log.i(TAG, code); }
}
