package dev.localapi.deepseek;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Native input bar. The WebView textarea is kept for desktop tests only; on a
 * phone this EditText owns text input, so Android's IME never fights a DOM
 * layout that changes while the user types.
 */
final class NativeComposer {
    private static final int REQ_IMAGE = 21;
    private static final int MAX_IMAGES = 4;

    private final Activity activity;
    private final WebView web;
    private final LinearLayout bar;
    private final LinearLayout inputWrap;
    private final View divider;
    private final EditText input;
    private final ImageButton attach;
    private final ImageButton send;
    private final HorizontalScrollView attachmentScroll;
    private final LinearLayout attachmentStrip;

    private final List<Pending> pending = new ArrayList<>();
    private final List<Pending> sentPending = new ArrayList<>();
    private boolean streaming;
    private boolean online = true;
    private boolean light;
    private boolean visible;

    NativeComposer(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setVisibility(View.GONE);

        divider = new View(activity);
        bar.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        attachmentScroll = new HorizontalScrollView(activity);
        attachmentScroll.setHorizontalScrollBarEnabled(false);
        attachmentScroll.setVisibility(View.GONE);
        attachmentStrip = new LinearLayout(activity);
        attachmentStrip.setOrientation(LinearLayout.HORIZONTAL);
        attachmentStrip.setPadding(dp(12), dp(8), dp(12), 0);
        attachmentScroll.addView(attachmentStrip, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        bar.addView(attachmentScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(8));
        bar.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        attach = new ImageButton(activity);
        attach.setImageResource(R.drawable.ic_attach);
        attach.setBackgroundColor(Color.TRANSPARENT);
        attach.setPadding(dp(12), dp(12), dp(12), dp(12));
        attach.setContentDescription("Прикрепить фото");
        attach.setOnClickListener(v -> pickImage());
        row.addView(attach, new LinearLayout.LayoutParams(dp(48), dp(48)));

        inputWrap = new LinearLayout(activity);
        inputWrap.setOrientation(LinearLayout.HORIZONTAL);
        inputWrap.setGravity(Gravity.CENTER_VERTICAL);
        inputWrap.setPadding(dp(16), dp(4), dp(4), dp(4));

        input = new EditText(activity);
        input.setBackground(null);
        input.setHint("Сообщение");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setMaxLines(6);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setPadding(0, dp(10), 0, dp(10));
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setVerticalScrollBarEnabled(false);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) web.clearFocus();
        });
        // Fixed height: the bar must not re-layout on every typed line.
        inputWrap.addView(input, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams inputWrapParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputWrapParams.setMarginStart(dp(4));
        row.addView(inputWrap, inputWrapParams);

        send = new ImageButton(activity);
        send.setPadding(dp(12), dp(12), dp(12), dp(12));
        send.setContentDescription("Отправить");
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sendParams =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.setMarginStart(dp(8));
        row.addView(send, sendParams);

        applyTheme();
        updateSendButton();
    }

    View view() {
        return bar;
    }

    void setVisible(boolean visible) {
        if (visible) {
            if (this.visible && bar.getVisibility() == View.VISIBLE) return;
            this.visible = true;
            // The IME serves the focused editor. Keeping the WebView focusable
            // lets it steal the session and reset the native composition.
            web.clearFocus();
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            bar.animate().cancel();
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.animate()
                    .alpha(1f)
                    .setDuration(160)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            return;
        }
        input.clearFocus();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        if (!this.visible && bar.getVisibility() != View.VISIBLE) {
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            return;
        }
        this.visible = false;
        // Fade the bar out instead of yanking it away from under the still
        // closing keyboard; the drawer slides over this short animation.
        bar.animate().cancel();
        bar.setAlpha(bar.getAlpha() == 0f ? 1f : bar.getAlpha());
        bar.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction(() -> {
                    if (this.visible) return;
                    bar.setVisibility(View.GONE);
                    bar.setAlpha(1f);
                })
                .start();
        // Restore WebView focusability so HTML fields in settings still work;
        // the bar keeps its layout slot until the fade completes.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
    }

    /**
     * Hides the bar before a full-screen overlay (drawer) starts sliding:
     * removing its layout slot synchronously keeps the overlay height stable.
     */
    void hideImmediately() {
        input.clearFocus();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        visible = false;
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.setVisibility(View.GONE);
        bar.setTranslationY(0f);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
    }

    /** Lifts the whole bar above the keyboard without a layout pass. */
    void setImeOffset(int px) {
        bar.setTranslationY(-Math.max(0, px));
    }

    void setState(boolean streaming, boolean online) {
        this.streaming = streaming;
        this.online = online;
        attach.setEnabled(online);
        updateSendButton();
    }

    void setTheme(boolean light) {
        this.light = light;
        applyTheme();
        updateSendButton();
    }

    void setTextZoom(int percent) {
        int clamped = Math.max(70, Math.min(200, percent));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * clamped / 100f);
    }

    void focusInput() {
        if (bar.getVisibility() != View.VISIBLE) return;
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    void restoreDraft(String text) {
        if (text == null || text.isEmpty() || input.getText().length() > 0) return;
        // Never rewrite the editor while Gboard owns a composing region: the
        // IME would drop its pending characters.
        if (BaseInputConnection.getComposingSpanStart(input.getText()) >= 0) return;
        input.setText(text);
        input.setSelection(input.getText().length());
        focusInput();
    }

    void clear() {
        // send() already emptied the field synchronously. Clearing it again here
        // would wipe whatever the user typed while the send was in flight.
        for (Pending item : new ArrayList<>(sentPending)) {
            if (pending.remove(item)) attachmentStrip.removeView(item.holder);
        }
        sentPending.clear();
        attachmentScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        updateAttachTint();
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMAGE || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        if (pending.size() >= MAX_IMAGES) {
            Toast.makeText(activity, "Можно прикрепить не больше " + MAX_IMAGES + " фото",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            Encoded encoded = encode(uri);
            activity.runOnUiThread(() -> {
                if (encoded == null) {
                    Toast.makeText(activity, "Не удалось прочитать изображение",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                addPending(encoded);
            });
        }, "image-encode").start();
    }

    private void send() {
        if (streaming) {
            web.evaluateJavascript("window.__nativeStop && window.__nativeStop()", null);
            return;
        }
        if (!online) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) {
            return;
        }
        try {
            JSONArray images = new JSONArray();
            for (Pending item : pending) {
                images.put(item.dataUrl);
            }
            JSONObject payload = new JSONObject();
            payload.put("text", text);
            payload.put("images", images);
            // Snapshot and clear before the async bridge call so a draft typed
            // after the tap is not wiped when JS acknowledges the send.
            sentPending.clear();
            sentPending.addAll(pending);
            input.setText("");
            web.evaluateJavascript(
                    "window.__nativeSend && window.__nativeSend("
                            + JSONObject.quote(payload.toString()) + ")",
                    null);
        } catch (Exception ignored) {
            restoreDraft(text);
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "Фото"), REQ_IMAGE);
        } catch (Exception e) {
            Toast.makeText(activity, "Нет приложения для выбора фото",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void addPending(Encoded encoded) {
        if (pending.size() >= MAX_IMAGES) {
            Toast.makeText(activity, "Можно прикрепить не больше " + MAX_IMAGES + " фото",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        FrameLayout holder = new FrameLayout(activity);
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        thumb.setImageBitmap(encoded.thumb);
        thumb.setOnClickListener(v -> preview(encoded.preview));
        holder.addView(thumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageButton remove = new ImageButton(activity);
        remove.setImageResource(R.drawable.ic_close_small);
        remove.setPadding(dp(4), dp(4), dp(4), dp(4));
        remove.setContentDescription("Убрать фото");
        FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(
                dp(24), dp(24), Gravity.TOP | Gravity.END);
        holder.addView(remove, removeParams);

        Pending item = new Pending(encoded.dataUrl, encoded.preview, encoded.thumb,
                holder, thumb, remove);
        remove.setOnClickListener(v -> {
            pending.remove(item);
            attachmentStrip.removeView(holder);
            attachmentScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
            updateAttachTint();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(64));
        params.setMarginEnd(dp(8));
        attachmentStrip.addView(holder, params);
        pending.add(item);
        attachmentScroll.setVisibility(View.VISIBLE);
        applyTheme();
        updateAttachTint();
    }

    private void preview(Bitmap bitmap) {
        if (bitmap == null) return;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        ImageButton close = new ImageButton(activity);
        close.setImageResource(R.drawable.ic_close_small);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setColorFilter(Color.WHITE);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(24), dp(12), 0);
        frame.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());
        frame.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(frame);
        dialog.show();
    }

    private void updateAttachTint() {
        attach.setColorFilter(pending.isEmpty()
                ? Color.parseColor(light ? "#44474E" : "#C4C6D0")
                : Color.parseColor(light ? "#415F91" : "#AAC7FF"));
    }

    private void updateSendButton() {
        int primary = Color.parseColor(light ? "#415F91" : "#AAC7FF");
        int onPrimary = Color.parseColor(light ? "#FFFFFF" : "#0A305F");
        int error = Color.parseColor(light ? "#BA1A1A" : "#FFB4AB");
        int onError = Color.parseColor(light ? "#FFFFFF" : "#690005");
        if (streaming) {
            send.setImageResource(R.drawable.ic_stop_native);
            send.setBackground(rounded(error, 16));
            send.setColorFilter(onError);
            send.setContentDescription("Остановить");
            send.setEnabled(true);
        } else {
            send.setImageResource(R.drawable.ic_send_native);
            send.setBackground(rounded(primary, 16));
            send.setColorFilter(onPrimary);
            send.setContentDescription("Отправить");
            send.setEnabled(online);
        }
    }

    private void applyTheme() {
        int surface = Color.parseColor(light ? "#F9F9FF" : "#111318");
        int outline = Color.parseColor(light ? "#C4C6D0" : "#44474E");
        int inputSurface = Color.parseColor(light ? "#E7E8EE" : "#282A2F");
        int text = Color.parseColor(light ? "#191C20" : "#E2E2E9");
        int hint = Color.parseColor(light ? "#74777F" : "#C4C6D0");
        int removeBackground = Color.parseColor(light ? "#E7E8EE" : "#33353A");
        int removeTint = Color.parseColor(light ? "#191C20" : "#E2E2E9");

        bar.setBackgroundColor(surface);
        divider.setBackgroundColor(outline);
        inputWrap.setBackground(rounded(inputSurface, 28));
        input.setTextColor(text);
        input.setHintTextColor(hint);
        for (Pending item : pending) {
            item.thumb.setBackground(rounded(inputSurface, 12));
            item.remove.setBackground(rounded(removeBackground, 12));
            item.remove.setColorFilter(removeTint);
        }
        updateAttachTint();
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private Encoded encode(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                return null;
            }

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                ExifInterface exif = new ExifInterface(in);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }
            Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270);
            } else if (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) {
                matrix.postScale(-1, 1);
            } else if (orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) {
                matrix.postScale(1, -1);
            }
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }

            int maxThumb = Math.max(bitmap.getWidth(), bitmap.getHeight());
            int thumbSide = 192;
            Bitmap thumb = maxThumb <= thumbSide ? bitmap : Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, bitmap.getWidth() * thumbSide / maxThumb),
                    Math.max(1, bitmap.getHeight() * thumbSide / maxThumb),
                    true);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out);
            String dataUrl = "data:image/jpeg;base64,"
                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return new Encoded(dataUrl, thumb, bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Encoded {
        final String dataUrl;
        final Bitmap thumb;
        final Bitmap preview;

        Encoded(String dataUrl, Bitmap thumb, Bitmap preview) {
            this.dataUrl = dataUrl;
            this.thumb = thumb;
            this.preview = preview;
        }
    }

    private static final class Pending {
        final String dataUrl;
        final Bitmap preview;
        final Bitmap thumbBitmap;
        final FrameLayout holder;
        final ImageView thumb;
        final ImageButton remove;

        Pending(String dataUrl, Bitmap preview, Bitmap thumbBitmap,
                FrameLayout holder, ImageView thumb, ImageButton remove) {
            this.dataUrl = dataUrl;
            this.preview = preview;
            this.thumbBitmap = thumbBitmap;
            this.holder = holder;
            this.thumb = thumb;
            this.remove = remove;
        }
    }
}
